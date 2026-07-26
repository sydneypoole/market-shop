package com.marketshop.application.membership;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketshop.domain.shared.DomainException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MembershipApplicationService implements MembershipUseCase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> RULE_TYPES = Set.of(
            "SELF_ORDER_TASK",
            "DIRECT_REFERRAL_TASK",
            "DIRECT_REFERRAL_POINTS",
            "FROZEN_POINTS_RELEASE",
            "INACTIVITY_DOWNGRADE",
            "ORDER_TIMER"
    );

    private final MembershipPort port;

    public MembershipApplicationService(MembershipPort port) {
        this.port = port;
    }

    @Override
    public ProfileView profile(long userId) {
        return port.profile(userId);
    }

    @Override
    public InvitationView currentInvitation(long userId) {
        return port.currentInvitation(userId);
    }

    @Override
    public InvitationView invitation(long userId) {
        return port.ensureInvitation(userId);
    }

    @Override
    public void revokeInvitation(long userId) {
        port.revokeInvitation(userId);
    }

    @Override
    public InvitationView regenerateInvitation(long userId, int validityDays) {
        if (validityDays < 1 || validityDays > 3650) {
            throw new DomainException("INVITATION_VALIDITY_INVALID", "邀请码有效期必须在 1 到 3650 天之间");
        }
        return port.regenerateInvitation(userId, validityDays);
    }

    @Override
    public List<DirectMemberView> directMembers(long userId) {
        return port.directMembers(userId);
    }

    @Override
    public List<LedgerEntryView> ledger(long userId) {
        return port.ledger(userId);
    }

    @Override
    public List<RuleView> rules() {
        return port.rules();
    }

    @Override
    public List<RuleView> activeRules() {
        Instant now = Instant.now();
        Map<String, RuleView> current = new LinkedHashMap<>();
        port.rules().stream()
                .filter(rule -> "ACTIVE".equals(rule.status()))
                .filter(rule -> rule.effectiveFrom() == null || !rule.effectiveFrom().isAfter(now))
                .filter(rule -> rule.effectiveTo() == null || rule.effectiveTo().isAfter(now))
                .sorted(Comparator.comparingInt(RuleView::version))
                .forEach(rule -> current.put(rule.ruleCode(), rule));
        return List.copyOf(current.values());
    }

    @Override
    public RuleView publishRule(long adminId, PublishRuleCommand command) {
        RuleValidationView validation = validateRule(command);
        Instant effectiveFrom = command.effectiveFrom() == null ? Instant.now() : command.effectiveFrom();
        return port.publishRule(
                adminId,
                new PublishRuleCommand(
                        command.ruleCode().trim(),
                        command.ruleType(),
                        validation.normalizedParametersJson(),
                        effectiveFrom
                )
        );
    }

    @Override
    public RuleValidationView validateRule(PublishRuleCommand command) {
        if (command == null || command.ruleCode() == null || command.ruleCode().isBlank()
                || command.ruleType() == null || !RULE_TYPES.contains(command.ruleType())
                || command.parametersJson() == null || command.parametersJson().isBlank()) {
            throw new DomainException("RULE_INVALID", "规则编码、类型或参数无效");
        }
        try {
            JsonNode root = JSON.readTree(command.parametersJson());
            if (root == null || !root.isObject()) {
                throw invalid("规则参数必须是 JSON 对象");
            }
            switch (command.ruleType()) {
                case "SELF_ORDER_TASK" -> {
                    positiveLong(root, "minimumCompletedOrderAmountFen");
                    text(root, "targetLevel");
                }
                case "DIRECT_REFERRAL_TASK" -> {
                    positiveInt(root, "requiredCompletedDirectReferrals", 1, 100_000);
                    positiveLong(root, "minimumReferralOrderAmountFen");
                    text(root, "requiredReferralLevel");
                    text(root, "targetLevel");
                }
                case "DIRECT_REFERRAL_POINTS" -> {
                    positiveInt(root, "pointsStartOrdinal", 1, 100_000);
                    long available = nonNegativeLong(root, "availableAPoints");
                    long frozen = nonNegativeLong(root, "frozenBPoints");
                    if (available + frozen <= 0) {
                        throw invalid("A/B 积分至少一项必须大于 0");
                    }
                }
                case "FROZEN_POINTS_RELEASE" -> {
                    positiveLong(root, "minimumCompletedOrderAmountFen");
                    positiveLong(root, "releasePointsPerOrder");
                    optionalEquals(root, "releaseMode", "FIXED");
                    optionalEquals(root, "batchOrder", "FIFO");
                }
                case "INACTIVITY_DOWNGRADE" -> {
                    positiveInt(root, "inactiveMonths", 1, 60);
                    String source = text(root, "sourceLevel");
                    String target = text(root, "targetLevel");
                    if (source.equals(target)) {
                        throw invalid("降级前后等级不能相同");
                    }
                }
                case "ORDER_TIMER" -> {
                    positiveInt(root, "autoReceiveDaysAfterShipment", 1, 365);
                    positiveInt(root, "afterSaleDaysAfterCompletion", 1, 365);
                    positiveInt(root, "proofRetentionDays", 1, 3650);
                    positiveInt(root, "maxProofFiles", 1, 20);
                    long bytes = positiveLong(root, "maxProofSizeBytes");
                    if (bytes < 1024 || bytes > 20L * 1024 * 1024) {
                        throw invalid("单个凭证大小必须在 1KB 到 20MB 之间");
                    }
                }
                default -> throw invalid("未知规则类型");
            }
            return new RuleValidationView(true, JSON.writeValueAsString(root), List.of());
        } catch (JsonProcessingException exception) {
            throw invalid("规则参数不是合法 JSON");
        }
    }

    @Override
    public void cancelRule(long adminId, long ruleId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new DomainException("REASON_REQUIRED", "取消规则必须填写原因");
        }
        port.cancelRule(adminId, ruleId, reason.trim());
    }

    private static long positiveLong(JsonNode root, String field) {
        long value = nonNegativeLong(root, field);
        if (value <= 0) {
            throw invalid(field + " 必须大于 0");
        }
        return value;
    }

    private static long nonNegativeLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid(field + " 必须是非负整数");
        }
        return value.longValue();
    }

    private static int positiveInt(JsonNode root, String field, int min, int max) {
        JsonNode value = root.get(field);
        if (value == null || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) {
            throw invalid(field + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return value.intValue();
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + " 不能为空");
        }
        return value.textValue();
    }

    private static void optionalEquals(JsonNode root, String field, String expected) {
        JsonNode value = root.get(field);
        if (value != null && (!value.isTextual() || !expected.equals(value.textValue()))) {
            throw invalid(field + " 当前仅支持 " + expected);
        }
    }

    private static DomainException invalid(String message) {
        return new DomainException("RULE_PARAMETERS_INVALID", message);
    }
}

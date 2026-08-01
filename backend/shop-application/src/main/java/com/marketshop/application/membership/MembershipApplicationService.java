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
    private static final String ORDER_TIMER_CODE = "ORDER_TIMERS";
    private static final String ORDER_TIMER_TYPE = "ORDER_TIMER";

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
        rejectOrderTimerFromGenericEndpoint(command);
        return publishRuleInternal(adminId, command, false);
    }

    @Override
    public RuleView publishOrderTimer(long adminId, PublishRuleCommand command) {
        PublishRuleCommand normalized = normalizeOrderTimerCommand(command);
        return publishRuleInternal(adminId, normalized, true);
    }

    private RuleView publishRuleInternal(long adminId, PublishRuleCommand command, boolean allowOrderTimer) {
        if (command == null) {
            throw new DomainException("RULE_INVALID", "规则编码、类型或参数无效");
        }
        String ruleCode = command.ruleCode() == null ? null : command.ruleCode().trim();
        PublishRuleCommand normalized = new PublishRuleCommand(
                ruleCode,
                command.ruleType(),
                command.parametersJson(),
                command.effectiveFrom()
        );
        RuleValidationView validation = validateRuleInternal(normalized, allowOrderTimer);
        Instant effectiveFrom = command.effectiveFrom() == null ? Instant.now() : command.effectiveFrom();
        ensureActiveRuleVersionsHealthy(ruleCode, allowOrderTimer);
        return port.publishRule(
                adminId,
                new PublishRuleCommand(
                        ruleCode,
                        normalized.ruleType(),
                        validation.normalizedParametersJson(),
                        effectiveFrom
                )
        );
    }

    @Override
    public RuleView currentOrderTimer() {
        Instant now = Instant.now();
        RuleView current = port.rules().stream()
                .filter(rule -> ORDER_TIMER_CODE.equals(rule.ruleCode()))
                .filter(rule -> ORDER_TIMER_TYPE.equals(rule.ruleType()))
                .filter(rule -> "ACTIVE".equals(rule.status()))
                .filter(rule -> rule.effectiveFrom() == null || !rule.effectiveFrom().isAfter(now))
                .filter(rule -> rule.effectiveTo() == null || rule.effectiveTo().isAfter(now))
                .max(Comparator.comparingInt(RuleView::version))
                .orElseThrow(() -> new DomainException(
                        "ORDER_TIMER_CURRENT_MISSING",
                        "当前订单与凭证策略版本不存在"
                ));
        // A read of an existing version must never turn malformed persisted
        // JSON into an editable default.  Reuse the exact publication parser.
        validateRuleInternal(new PublishRuleCommand(
                current.ruleCode(), current.ruleType(), current.parametersJson(), current.effectiveFrom()
        ), true);
        return current;
    }

    @Override
    public RuleValidationView validateOrderTimer(PublishRuleCommand command) {
        return validateRuleInternal(normalizeOrderTimerCommand(command), true);
    }

    /**
     * A publish operation must not paper over a corrupt active version. Every
     * ACTIVE row for the code is checked, including future and historical rows:
     * an existing array, malformed JSON, or missing required field keeps the
     * rule locked until it is recovered.
     */
    private void ensureActiveRuleVersionsHealthy(String ruleCode, boolean allowOrderTimer) {
        List<RuleView> existing = port.rules().stream()
                .filter(rule -> ruleCode.equals(rule.ruleCode()))
                .filter(rule -> "ACTIVE".equals(rule.status()))
                .toList();
        // A future ACTIVE row can become authoritative after this request. Do not
        // treat it as "no current version" and let a new publication hide a
        // corrupt payload. Historical ACTIVE rows are immutable inputs for order
        // snapshots as well, so they are checked before appending a new version.
        for (RuleView existingRule : existing) {
            try {
                validateRuleInternal(new PublishRuleCommand(
                        existingRule.ruleCode(),
                        existingRule.ruleType(),
                        existingRule.parametersJson(),
                        existingRule.effectiveFrom()
                ), allowOrderTimer);
            } catch (DomainException exception) {
                throw new DomainException(
                        "RULE_CURRENT_INVALID",
                        "当前规则版本无法解析或校验，请恢复后再发布",
                        exception
                );
            }
        }
    }

    @Override
    public RuleValidationView validateRule(PublishRuleCommand command) {
        rejectOrderTimerFromGenericEndpoint(command);
        return validateRuleInternal(command, false);
    }

    private RuleValidationView validateRuleInternal(PublishRuleCommand command, boolean allowOrderTimer) {
        if (command == null || command.ruleCode() == null || command.ruleCode().isBlank()
                || command.ruleType() == null || !RULE_TYPES.contains(command.ruleType())
                || command.parametersJson() == null || command.parametersJson().isBlank()) {
            throw new DomainException("RULE_INVALID", "规则编码、类型或参数无效");
        }
        if (isOrderTimer(command) && !allowOrderTimer) {
            throw orderTimerSettingsOnly();
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
                    long total;
                    try {
                        total = Math.addExact(available, frozen);
                    } catch (ArithmeticException exception) {
                        throw invalid("A/B 积分总量超出安全范围");
                    }
                    if (total <= 0) {
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

    private static boolean isOrderTimer(PublishRuleCommand command) {
        String code = command == null || command.ruleCode() == null ? null : command.ruleCode().trim();
        return command != null
                && (ORDER_TIMER_CODE.equals(code) || ORDER_TIMER_TYPE.equals(command.ruleType()));
    }

    private static void rejectOrderTimerFromGenericEndpoint(PublishRuleCommand command) {
        if (isOrderTimer(command)) {
            throw orderTimerSettingsOnly();
        }
    }

    private static PublishRuleCommand normalizeOrderTimerCommand(PublishRuleCommand command) {
        if (command == null
                || !ORDER_TIMER_CODE.equals(command.ruleCode() == null ? null : command.ruleCode().trim())
                || !ORDER_TIMER_TYPE.equals(command.ruleType())) {
            throw orderTimerSettingsOnly();
        }
        return new PublishRuleCommand(
                ORDER_TIMER_CODE,
                ORDER_TIMER_TYPE,
                command.parametersJson(),
                command.effectiveFrom()
        );
    }

    private static DomainException orderTimerSettingsOnly() {
        return new DomainException(
                "ORDER_TIMER_SETTINGS_ONLY",
                "订单与凭证时限只能在系统配置中维护"
        );
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
        // Jackson's canConvertToLong/canConvertToInt deliberately accepts
        // fractional numeric nodes (for example 1.5 or 1e-1) because their
        // truncated value fits the target type.  Rule parameters are integer
        // contracts; accepting those values would silently change the value
        // when MySQL casts JSON and could make a published rule differ from
        // what the operator validated.
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid(field + " 必须是非负整数");
        }
        return value.longValue();
    }

    private static int positiveInt(JsonNode root, String field, int min, int max) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < min || value.intValue() > max) {
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

package com.marketshop.application.membership;

import com.marketshop.application.identity.IdentityPorts.WeChatMiniprogramPort;
import com.marketshop.application.identity.IdentityPorts.WxaCodeCommand;
import com.marketshop.domain.shared.DomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MembershipApplicationService implements MembershipUseCase {

    private static final String ORDER_TIMER_CODE = "ORDER_TIMERS";
    private static final String ORDER_TIMER_TYPE = "ORDER_TIMER";

    private final MembershipPort port;
    private final WeChatMiniprogramPort weChat;

    public MembershipApplicationService(MembershipPort port) {
        this(port, null);
    }

    @Autowired
    public MembershipApplicationService(MembershipPort port, WeChatMiniprogramPort weChat) {
        this.port = port;
        this.weChat = weChat;
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
        throw immutableInvitation();
    }

    @Override
    public InvitationView regenerateInvitation(long userId, int validityDays) {
        throw immutableInvitation();
    }

    @Override
    public WxacodeView invitationWxacode(long userId) {
        InvitationView invitation = port.currentInvitation(userId);
        if (invitation == null
                || invitation.code() == null
                || invitation.code().isBlank()
                || !"ACTIVE".equals(invitation.status())) {
            throw new DomainException("INVITATION_NOT_FOUND", "当前没有可用的邀请码");
        }
        String registrationPath = invitation.registrationPath() == null ? "" : invitation.registrationPath();
        if (registrationPath.startsWith("/")) {
            registrationPath = registrationPath.substring(1);
        }
        var image = weChat.createWxaCode(new WxaCodeCommand(
                "pages/register/register",
                invitation.code(),
                registrationPath
        ));
        return new WxacodeView(
                image.contentType(),
                Base64.getEncoder().encodeToString(image.image())
        );
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
        return port.rules().stream().map(this::normalizePersistedRuleView).toList();
    }

    @Override
    public List<RuleView> activeRules() {
        Instant now = Instant.now();
        Map<String, RuleView> current = new LinkedHashMap<>();
        rules().stream()
                .filter(rule -> "ACTIVE".equals(rule.status()))
                .filter(rule -> rule.effectiveFrom() == null || !rule.effectiveFrom().isAfter(now))
                .filter(rule -> rule.effectiveTo() == null || rule.effectiveTo().isAfter(now))
                .sorted(Comparator.comparingInt(RuleView::version))
                .forEach(rule -> current.put(rule.ruleCode(), rule));
        return List.copyOf(current.values());
    }

    private RuleView normalizePersistedRuleView(RuleView rule) {
        String expectedType = RuleParameterCodec.expectedType(rule.ruleCode());
        if (expectedType == null) {
            if ("ACTIVE".equals(rule.status())) {
                throw new DomainException("RULE_CURRENT_INVALID", "当前规则编码不受支持");
            }
            return rule;
        }
        if (!expectedType.equals(rule.ruleType())) {
            if ("ACTIVE".equals(rule.status())) {
                throw new DomainException("RULE_CURRENT_INVALID", "当前规则编码与类型不匹配");
            }
            return rule;
        }
        try {
            RuleParameterCodec.Decoded decoded = RuleParameterCodec.decodePersisted(
                    rule.ruleCode(), rule.ruleType(), rule.parametersJson()
            );
            validateTargetLevels(decoded.parameters());
            return new RuleView(
                    rule.id(), rule.ruleCode(), rule.version(), rule.ruleType(), decoded.normalizedJson(),
                    rule.status(), rule.effectiveFrom(), rule.effectiveTo()
            );
        } catch (DomainException exception) {
            if ("ACTIVE".equals(rule.status())) {
                throw new DomainException(
                        "RULE_CURRENT_INVALID",
                        "当前规则版本无法解析或校验，请恢复后再继续操作",
                        exception
                );
            }
            return rule;
        }
    }

    private static DomainException immutableInvitation() {
        return new DomainException("INVITATION_IMMUTABLE", "固定邀请码不可撤销或重建");
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
        RuleValidationView validation = validateRuleInternal(normalized, allowOrderTimer, false);
        Instant effectiveFrom = command.effectiveFrom() == null ? Instant.now() : command.effectiveFrom();
        ensureActiveRuleVersionsHealthy(ruleCode);
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
                .filter(rule -> "ACTIVE".equals(rule.status()))
                .filter(rule -> rule.effectiveFrom() == null || !rule.effectiveFrom().isAfter(now))
                .filter(rule -> rule.effectiveTo() == null || rule.effectiveTo().isAfter(now))
                .max(Comparator.comparingInt(RuleView::version))
                .orElseThrow(() -> new DomainException(
                        "ORDER_TIMER_CURRENT_MISSING",
                        "当前订单与凭证策略版本不存在"
                ));
        try {
            RuleParameterCodec.Decoded decoded = RuleParameterCodec.decodePersisted(
                    current.ruleCode(), current.ruleType(), current.parametersJson()
            );
            return new RuleView(
                    current.id(), current.ruleCode(), current.version(), current.ruleType(), decoded.normalizedJson(),
                    current.status(), current.effectiveFrom(), current.effectiveTo()
            );
        } catch (DomainException exception) {
            throw new DomainException(
                    "ORDER_TIMER_SETTINGS_INVALID",
                    "当前订单与凭证策略版本缺失或无效",
                    exception
            );
        }
    }

    @Override
    public RuleValidationView validateOrderTimer(PublishRuleCommand command) {
        return validateRuleInternal(normalizeOrderTimerCommand(command), true, false);
    }

    /**
     * A publish operation must not paper over a corrupt active version. Every
     * ACTIVE row for the code is checked, including future and historical rows:
     * an existing array, malformed JSON, or missing required field keeps the
     * rule locked until it is recovered.
     */
    private void ensureActiveRuleVersionsHealthy(String ruleCode) {
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
                validatePersistedRule(new PublishRuleCommand(
                        existingRule.ruleCode(),
                        existingRule.ruleType(),
                        existingRule.parametersJson(),
                        existingRule.effectiveFrom()
                ));
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
        return validateRuleInternal(command, false, true);
    }

    private RuleValidationView validateRuleInternal(
            PublishRuleCommand command,
            boolean allowOrderTimer,
            boolean allowLegacyValidationAlias
    ) {
        if (command == null || command.ruleCode() == null || command.ruleCode().isBlank()
                || command.ruleType() == null || command.parametersJson() == null
                || command.parametersJson().isBlank()) {
            throw new DomainException("RULE_INVALID", "规则编码、类型或参数无效");
        }
        if (isOrderTimer(command) && !allowOrderTimer) {
            throw orderTimerSettingsOnly();
        }
        String codecRuleCode = allowLegacyValidationAlias
                && "DOWNGRADE".equals(command.ruleCode().trim())
                && "INACTIVITY_DOWNGRADE".equals(command.ruleType())
                ? "DIVIDEND_INACTIVITY_DOWNGRADE"
                : command.ruleCode();
        RuleParameterCodec.Decoded decoded = RuleParameterCodec.decodeForPublication(
                codecRuleCode, command.ruleType(), command.parametersJson()
        );
        validateTargetLevels(decoded.parameters());
        return new RuleValidationView(true, decoded.normalizedJson(), List.of());
    }

    private void validatePersistedRule(PublishRuleCommand command) {
        RuleParameterCodec.Decoded decoded = RuleParameterCodec.decodePersisted(
                command.ruleCode(), command.ruleType(), command.parametersJson()
        );
        validateTargetLevels(decoded.parameters());
    }

    private void validateTargetLevels(RuleParameters parameters) {
        switch (parameters) {
            case SelfOrderTaskParameters value -> requireActiveLevel(value.targetLevel());
            case DirectReferralTaskParameters value -> {
                requireActiveLevel(value.requiredReferralLevel());
                requireActiveLevel(value.targetLevel());
            }
            case InactivityDowngradeParameters value -> {
                requireActiveLevel(value.sourceLevel());
                requireActiveLevel(value.targetLevel());
            }
            case DirectReferralPointsParameters value -> { }
            case FrozenPointsReleaseParameters value -> { }
            case OrderTimerParameters value -> { }
        }
    }

    private void requireActiveLevel(String levelCode) {
        if (!port.activeMembershipLevelExists(levelCode)) {
            throw new DomainException("RULE_TARGET_LEVEL_INVALID", "规则引用的会员等级不存在或未启用");
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

}

package com.marketshop.application.membership;

import com.marketshop.domain.shared.DomainException;

/**
 * Resolves persisted rule JSON into the same typed values used by publication.
 * Runtime callers must use this boundary instead of extracting individual JSON
 * paths in infrastructure SQL or adapters.
 */
public final class RuleRuntimeResolver {

    private RuleRuntimeResolver() {
    }

    public static RuleParameters resolve(String ruleCode, String ruleType, String parametersJson) {
        try {
            return RuleParameterCodec.decodePersisted(ruleCode, ruleType, parametersJson).parameters();
        } catch (DomainException exception) {
            throw new DomainException("RULE_RUNTIME_INVALID", "当前规则版本参数无效", exception);
        }
    }

    public static OrderTimerParameters orderTimer(String ruleCode, String ruleType, String parametersJson) {
        try {
            return cast(
                    RuleParameterCodec.decodePersisted(ruleCode, ruleType, parametersJson).parameters(),
                    OrderTimerParameters.class
            );
        } catch (DomainException exception) {
            throw invalidOrderTimer();
        }
    }

    public static OrderTimerParameters orderTimer(String parametersJson) {
        return orderTimer("ORDER_TIMERS", "ORDER_TIMER", parametersJson);
    }

    public static SelfOrderTaskParameters selfOrder(String ruleCode, String ruleType, String parametersJson) {
        return cast(resolve(ruleCode, ruleType, parametersJson), SelfOrderTaskParameters.class);
    }

    public static DirectReferralTaskParameters directReferralTask(
            String ruleCode, String ruleType, String parametersJson
    ) {
        return cast(resolve(ruleCode, ruleType, parametersJson), DirectReferralTaskParameters.class);
    }

    public static DirectReferralPointsParameters directReferralPoints(
            String ruleCode, String ruleType, String parametersJson
    ) {
        return cast(resolve(ruleCode, ruleType, parametersJson), DirectReferralPointsParameters.class);
    }

    public static FrozenPointsReleaseParameters frozenPointsRelease(
            String ruleCode, String ruleType, String parametersJson
    ) {
        return cast(resolve(ruleCode, ruleType, parametersJson), FrozenPointsReleaseParameters.class);
    }

    public static InactivityDowngradeParameters inactivityDowngrade(
            String ruleCode, String ruleType, String parametersJson
    ) {
        return cast(resolve(ruleCode, ruleType, parametersJson), InactivityDowngradeParameters.class);
    }

    public static DomainException invalidOrderTimer() {
        return new DomainException("ORDER_TIMER_SETTINGS_INVALID", "订单时效规则缺失或无效");
    }

    private static <T extends RuleParameters> T cast(RuleParameters value, Class<T> expected) {
        if (!expected.isInstance(value)) {
            throw new DomainException("RULE_RUNTIME_INVALID", "当前规则版本类型无效");
        }
        return expected.cast(value);
    }
}

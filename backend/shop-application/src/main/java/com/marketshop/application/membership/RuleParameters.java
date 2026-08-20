package com.marketshop.application.membership;

import java.util.List;

public sealed interface RuleParameters
        permits SelfOrderTaskParameters,
        DirectReferralTaskParameters,
        DirectReferralPointsParameters,
        FrozenPointsReleaseParameters,
        InactivityDowngradeParameters,
        OrderTimerParameters {

    String ruleType();

    default List<String> eligibleSalesScenes() {
        return List.of();
    }
}

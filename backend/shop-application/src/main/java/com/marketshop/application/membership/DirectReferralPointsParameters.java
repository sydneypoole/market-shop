package com.marketshop.application.membership;

import java.util.List;

public record DirectReferralPointsParameters(
        int qualificationCount,
        int pointsStartOrdinal,
        long totalPoints,
        long availableAPoints,
        long frozenBPoints,
        int maxRewardDepth,
        List<String> eligibleSalesScenes
) implements RuleParameters {

    public DirectReferralPointsParameters {
        eligibleSalesScenes = List.copyOf(eligibleSalesScenes);
    }

    @Override
    public String ruleType() {
        return "DIRECT_REFERRAL_POINTS";
    }
}

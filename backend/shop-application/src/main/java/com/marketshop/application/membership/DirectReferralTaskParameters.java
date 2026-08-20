package com.marketshop.application.membership;

import java.util.List;

public record DirectReferralTaskParameters(
        int requiredCompletedDirectReferrals,
        long minimumReferralOrderAmountFen,
        List<String> eligibleSalesScenes,
        String requiredReferralLevel,
        String targetLevel
) implements RuleParameters {

    public DirectReferralTaskParameters {
        eligibleSalesScenes = List.copyOf(eligibleSalesScenes);
    }

    @Override
    public String ruleType() {
        return "DIRECT_REFERRAL_TASK";
    }
}

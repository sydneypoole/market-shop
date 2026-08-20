package com.marketshop.application.membership;

import java.util.List;

public record FrozenPointsReleaseParameters(
        List<String> eligibleSalesScenes,
        long minimumCompletedOrderAmountFen,
        String releaseMode,
        long releasePointsPerOrder,
        String batchOrder
) implements RuleParameters {

    public FrozenPointsReleaseParameters {
        eligibleSalesScenes = List.copyOf(eligibleSalesScenes);
    }

    @Override
    public String ruleType() {
        return "FROZEN_POINTS_RELEASE";
    }
}

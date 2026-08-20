package com.marketshop.application.membership;

import java.util.List;

public record SelfOrderTaskParameters(
        long minimumCompletedOrderAmountFen,
        List<String> eligibleSalesScenes,
        String targetLevel
) implements RuleParameters {

    public SelfOrderTaskParameters {
        eligibleSalesScenes = List.copyOf(eligibleSalesScenes);
    }

    @Override
    public String ruleType() {
        return "SELF_ORDER_TASK";
    }
}

package com.marketshop.application.membership;

public record InactivityDowngradeParameters(
        int inactiveMonths,
        String sourceLevel,
        String targetLevel
) implements RuleParameters {

    @Override
    public String ruleType() {
        return "INACTIVITY_DOWNGRADE";
    }
}

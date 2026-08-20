package com.marketshop.application.membership;

public record OrderTimerParameters(
        int autoReceiveDaysAfterShipment,
        int afterSaleDaysAfterCompletion,
        int pendingSuperiorTimeoutDays,
        int pendingAdminReviewTimeoutDays,
        int pendingShipmentTimeoutDays,
        int proofRetentionDays,
        int maxProofFiles,
        long maxProofSizeBytes
) implements RuleParameters {

    @Override
    public String ruleType() {
        return "ORDER_TIMER";
    }
}

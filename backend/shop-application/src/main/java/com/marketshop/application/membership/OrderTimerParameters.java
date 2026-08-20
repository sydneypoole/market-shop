package com.marketshop.application.membership;

public record OrderTimerParameters(
        int autoReceiveDays,
        int afterSaleDaysAfterCompletion,
        int pendingSuperiorTimeoutDays,
        int pendingAdminReviewTimeoutDays,
        int pendingShipmentTimeoutDays,
        int awaitingReturnTimeoutDays,
        int returnShippedTimeoutDays,
        int offlineRefundTimeoutDays,
        int buyerRefundConfirmTimeoutDays,
        int proofRetentionDays,
        int maxProofFiles,
        long maxProofSizeBytes
) implements RuleParameters {

    @Override
    public String ruleType() {
        return "ORDER_TIMER";
    }
}

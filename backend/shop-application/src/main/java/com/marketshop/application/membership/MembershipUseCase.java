package com.marketshop.application.membership;

import java.time.Instant;
import java.util.List;

public interface MembershipUseCase {

    ProfileView profile(long userId);

    InvitationView currentInvitation(long userId);

    InvitationView invitation(long userId);

    void revokeInvitation(long userId);

    InvitationView regenerateInvitation(long userId, int validityDays);

    List<DirectMemberView> directMembers(long userId);

    List<LedgerEntryView> ledger(long userId);

    List<RuleView> rules();

    List<RuleView> activeRules();

    /**
     * Generic rule publication deliberately excludes ORDER_TIMERS.  The timer
     * policy has one owner (the system-settings workbench), so callers must use
     * the dedicated operations below instead of smuggling the type through the
     * general rules endpoint.
     */
    RuleView publishRule(long adminId, PublishRuleCommand command);

    RuleValidationView validateRule(PublishRuleCommand command);

    RuleView currentOrderTimer();

    RuleView publishOrderTimer(long adminId, PublishRuleCommand command);

    RuleValidationView validateOrderTimer(PublishRuleCommand command);

    void cancelRule(long adminId, long ruleId, String reason);

    record ProfileView(long userId, String nickname, String avatarUrl, String phoneMasked,
                       Instant phoneVerifiedAt, String levelCode, String levelName,
                       long availablePoints, long frozenPoints, int qualifiedDirectCount) {
    }

    record InvitationView(String code, String status, int useCount, String registrationPath, Instant expiresAt) {
    }

    record DirectMemberView(long userId, String publicId, String nickname, String levelName,
                            int completedOrdinal, long performanceFen, String performanceStatus) {
    }

    record LedgerEntryView(long id, String entryType, long availableDelta, long frozenDelta,
                           String sourceType, long sourceId, Long sourceOrderId, Long ruleVersionId,
                           Long originalEntryId, Long frozenBatchId, Long frozenBatchOriginalPoints,
                           Long frozenBatchRemainingPoints, String frozenBatchStatus, Instant occurredAt) {
    }

    record RuleView(long id, String ruleCode, int version, String ruleType, String parametersJson,
                    String status, Instant effectiveFrom, Instant effectiveTo) {
    }

    record PublishRuleCommand(String ruleCode, String ruleType, String parametersJson, Instant effectiveFrom) {
    }

    record RuleValidationView(boolean valid, String normalizedParametersJson, List<String> warnings) {
    }
}

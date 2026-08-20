package com.marketshop.infrastructure.persistence.model;

import java.time.LocalDateTime;

public final class DistributionPersistenceModels {

    private DistributionPersistenceModels() {
    }

    public static class OutboxRow {
        public Long id;
        public String eventId;
        public String aggregateId;
        public String eventType;
        public Integer attemptCount;
    }

    public static class ProjectionOrderRow {
        public Long orderId;
        public Long buyerUserId;
        public Long superiorUserId;
        public Long totalAmountFen;
        public Boolean hasUpgrade;
        public Boolean hasRepurchase;
    }

    public static class SelfRuleRow {
        public Long id;
        public Long minimumAmountFen;
        public String targetLevel;
        public Integer targetRank;
    }

    public static class DirectRuleRow {
        public Long id;
        public Integer requiredCount;
        public Long minimumAmountFen;
        public String requiredLevel;
        public Integer requiredRank;
        public String targetLevel;
    }

    public static class PointsRuleRow {
        public Long id;
        public Integer qualificationCount;
        public Integer pointsStartOrdinal;
        public Long totalPoints;
        public Long availablePoints;
        public Long frozenPoints;
        public Integer maxRewardDepth;
        public String eligibleSalesScene;
    }

    public static class ReleaseRuleRow {
        public Long id;
        public Long minimumAmountFen;
        public Long releasePoints;
        public String eligibleSalesScene;
    }

    public static class MemberLevelRow {
        public Long levelId;
        public String code;
        public Integer rankNo;
    }

    public static class LedgerAccountRow {
        public Long id;
        public Long availablePoints;
        public Long frozenPoints;
        public Integer version;
    }

    public static class FrozenBatchRow {
        public Long id;
        public Long accountId;
        public Long sourceLedgerEntryId;
        public Long sourceOrderId;
        public Long ruleVersionId;
        public Long originalPoints;
        public Long remainingPoints;
        public String status;
        public String sourceEntryType;
        public Long sourceAccountId;
        public Long sourceOrderIdFromLedger;
        public Long sourceRuleVersionId;
        public Long sourceFrozenDelta;
        public LocalDateTime createdAt;
    }

    public static class FrozenReleaseItemRow {
        public Long batchId;
        public Long accountId;
        public Long sourceLedgerEntryId;
        public Long originalPoints;
        public Long remainingPoints;
        public String status;
        public Long sourceAccountId;
        public String sourceEntryType;
        public Long sourceOrderId;
        public Long sourceRuleVersionId;
        public Long sourceFrozenDelta;
        public Long batchSourceOrderId;
        public Long batchRuleVersionId;
        public Long points;
    }

    public static class MembershipProfileRow {
        public Long userId;
        public String nickname;
        public String avatarUrl;
        public String phoneMasked;
        public LocalDateTime phoneVerifiedAt;
        public String levelCode;
        public String levelName;
        public Long availablePoints;
        public Long frozenPoints;
        public Integer qualifiedDirectCount;
        public Boolean invitationEnabled;
    }

    public static class InvitationRow {
        public String code;
        public String status;
        public Integer useCount;
        public LocalDateTime expiresAt;
    }

    public static class DirectMemberRow {
        public Long userId;
        public String publicId;
        public String nickname;
        public String levelName;
        public Integer completedOrdinal;
        public Long performanceFen;
        public String performanceStatus;
    }

    public static class LedgerEntryRow {
        public Long id;
        public String entryType;
        public Long availableDelta;
        public Long frozenDelta;
        public String sourceType;
        public Long sourceId;
        public Long sourceOrderId;
        public Long ruleVersionId;
        public Long originalEntryId;
        public Long frozenBatchId;
        public Long frozenBatchOriginalPoints;
        public Long frozenBatchRemainingPoints;
        public String frozenBatchStatus;
        public LocalDateTime occurredAt;
    }

    public static class RuleRow {
        public Long id;
        public String ruleCode;
        public Integer versionNo;
        public String ruleType;
        public String parametersJson;
        public String status;
        public LocalDateTime effectiveFrom;
        public LocalDateTime effectiveTo;
    }

    public static class ReversibleLedgerRow {
        public Long id;
        public Long accountId;
        public String entryType;
        public Long availableDelta;
        public Long frozenDelta;
        public Long ruleVersionId;
        public Long originalEntryId;
    }

    public static class InactivityRuleRow {
        public Long id;
        public Integer inactiveMonths;
        public String sourceLevel;
        public String targetLevel;
    }

    public static class InactiveMemberRow {
        public Long userId;
        public String beforeLevel;
        public String targetLevel;
        public LocalDateTime performanceReference;
    }

    public static class MemberAdminRow {
        public Long userId;
        public String publicId;
        public String nickname;
        public String avatarUrl;
        public String phoneMasked;
        public LocalDateTime phoneVerifiedAt;
        public String status;
        public String levelCode;
        public String levelName;
        public Long superiorUserId;
        public Integer directCount;
        public Integer qualifiedDirectCount;
        public Long availablePoints;
        public Long frozenPoints;
        public LocalDateTime createdAt;
    }

    public static class EvidenceRow {
        public Long id;
        public String evidenceType;
        public Long sourceOrderId;
        public Long ruleVersionId;
        public String valueJson;
        public String status;
        public LocalDateTime createdAt;
        public LocalDateTime invalidatedAt;
    }

    public static class LevelChangeRow {
        public Long id;
        public String beforeLevelCode;
        public String afterLevelCode;
        public String triggerType;
        public String triggerId;
        public Long ruleVersionId;
        public String actorType;
        public String actorId;
        public String reason;
        public LocalDateTime occurredAt;
    }

    public static class LedgerDetailRow {
        public Long id;
        public String entryType;
        public Long availableDelta;
        public Long frozenDelta;
        public String sourceType;
        public Long sourceId;
        public Long sourceOrderId;
        public Long ruleVersionId;
        public Long originalEntryId;
        public Long frozenBatchId;
        public Long frozenBatchOriginalPoints;
        public Long frozenBatchRemainingPoints;
        public String frozenBatchStatus;
        public LocalDateTime occurredAt;
    }
}

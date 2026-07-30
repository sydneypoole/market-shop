package com.marketshop.application.membership;

import java.time.Instant;
import java.util.List;

public interface MemberAdminUseCase {

    MemberPage search(MemberQuery query);

    MemberDetail detail(long userId);

    void updateStatus(long adminId, long userId, StatusCommand command);

    void recompute(long adminId, long userId, RecomputeCommand command);

    record MemberQuery(String keyword, String levelCode, String status, int page, int size) {
    }

    record MemberPage(List<MemberSummary> items, long total, int page, int size) {
    }

    record MemberSummary(long userId, String publicId, String nickname, String status,
                         String levelCode, String levelName, Long superiorUserId,
                         int directCount, int qualifiedDirectCount, long availablePoints,
                         long frozenPoints, Instant createdAt) {
    }

    record MemberDetail(MemberSummary member, List<EvidenceView> evidence,
                        List<LevelChangeView> levelChanges, List<LedgerView> ledger) {
    }

    record EvidenceView(long id, String type, Long sourceOrderId, Long ruleVersionId,
                        String valueJson, String status, Instant createdAt, Instant invalidatedAt) {
    }

    record LevelChangeView(long id, String beforeLevel, String afterLevel, String triggerType,
                           String triggerId, Long ruleVersionId, String actorType, String actorId,
                           String reason, Instant occurredAt) {
    }

    record LedgerView(long id, String entryType, long availableDelta, long frozenDelta,
                      String sourceType, long sourceId, Long sourceOrderId, Long ruleVersionId,
                      Long originalEntryId, Long frozenBatchId, Long frozenBatchOriginalPoints,
                      Long frozenBatchRemainingPoints, String frozenBatchStatus, Instant occurredAt) {
    }

    record StatusCommand(String status, String reason, String requestId) {
    }

    record RecomputeCommand(String reason, String requestId) {
    }
}

package com.marketshop.infrastructure.reliability;

import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.DistributionMapper;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.DirectRuleRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.FrozenBatchRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.FrozenReleaseItemRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.LedgerAccountRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.MemberLevelRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.OutboxRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.PointsRuleRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.ProjectionOrderRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.ReleaseRuleRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.ReversibleLedgerRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.SelfRuleRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Component
public class OutboxProjectionProcessor {

    private static final String CONSUMER = "membership-distribution-projector";

    private final DistributionMapper mapper;

    public OutboxProjectionProcessor(DistributionMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public boolean processNext() {
        OutboxRow event = mapper.lockNextOutbox();
        if (event == null) {
            return false;
        }
        try {
            if (mapper.inboxExists(CONSUMER, event.eventId) > 0) {
                mapper.markOutboxPublished(event.id);
                return true;
            }
            switch (event.eventType) {
                case "ORDER_COMPLETED" -> projectCompletedOrder(Long.parseLong(event.aggregateId));
                case "AFTERSALE_COMPLETED" -> reverseCompletedAfterSale(Long.parseLong(event.aggregateId));
                default -> {
                    // Other lifecycle events are intentionally acknowledged by this projector.
                }
            }
            mapper.insertInbox(CONSUMER, event.eventId);
            mapper.markOutboxPublished(event.id);
            return true;
        } catch (RuntimeException exception) {
            throw new OutboxProjectionFailure(
                    event.id,
                    event.eventId,
                    event.attemptCount == null ? 0 : event.attemptCount,
                    exception
            );
        }
    }

    private void projectCompletedOrder(long orderId) {
        if (mapper.countCompletedAfterSales(orderId) > 0) {
            return;
        }
        ProjectionOrderRow order = mapper.projectionOrder(orderId);
        if (order == null) {
            throw new DomainException("PROJECTION_ORDER_INVALID", "待投影订单不存在或尚未完成");
        }
        if (Boolean.TRUE.equals(order.hasUpgrade)) {
            projectSelfMembership(order);
            projectDirectPerformance(order);
        }
        if (Boolean.TRUE.equals(order.hasRepurchase)) {
            projectRepurchaseRelease(order);
        }
    }

    private void projectSelfMembership(ProjectionOrderRow order) {
        List<SelfRuleRow> rules = mapper.snapshottedSelfRules(order.orderId);
        for (SelfRuleRow rule : rules) {
            if (order.totalAmountFen >= rule.minimumAmountFen) {
                mapper.insertSelfEvidence(
                        order.buyerUserId,
                        order.orderId,
                        rule.id,
                        order.totalAmountFen,
                        rule.targetLevel
                );
                promote(order.buyerUserId, rule.targetLevel, rule.id,
                        "ORDER_COMPLETED", Long.toString(order.orderId),
                        "order-promotion:" + order.buyerUserId + ":" + order.orderId + ":" + rule.id);
            }
        }
    }

    private void projectDirectPerformance(ProjectionOrderRow order) {
        DirectRuleRow rule = mapper.snapshottedDirectRule(order.orderId);
        if (rule == null || order.totalAmountFen < rule.minimumAmountFen) {
            return;
        }
        MemberLevelRow referredLevel = mapper.lockMemberLevel(order.buyerUserId);
        if (referredLevel == null || referredLevel.rankNo < rule.requiredRank) {
            return;
        }
        if (mapper.lockDirectPerformanceOwner(order.superiorUserId) == null) {
            throw new DomainException("PROJECTION_SUPERIOR_MEMBERSHIP_INVALID", "直属上级会员账户不存在");
        }
        Integer allocatedOrdinal = mapper.nextDirectOrdinal(order.superiorUserId);
        int ordinal = allocatedOrdinal == null ? 1 : allocatedOrdinal;
        int inserted = mapper.insertDirectPerformance(
                order.superiorUserId,
                order.buyerUserId,
                order.orderId,
                rule.id,
                ordinal,
                order.totalAmountFen
        );
        if (inserted == 0) {
            return;
        }
        mapper.touchPerformance(order.superiorUserId);
        int activeDirectCount = mapper.activeDirectCount(order.superiorUserId);
        if (activeDirectCount >= rule.requiredCount) {
            promote(order.superiorUserId, rule.targetLevel, rule.id,
                    "DIRECT_REFERRAL_QUALIFIED", Long.toString(order.orderId),
                    "direct-promotion:" + order.superiorUserId + ":" + order.orderId + ":" + rule.id);
        }
        PointsRuleRow points = mapper.snapshottedPointsRule(order.orderId);
        if (points != null && activeDirectCount >= points.pointsStartOrdinal) {
            LedgerAward award = award(
                    order.superiorUserId,
                    points.availablePoints,
                    points.frozenPoints,
                    "DIRECT_REFERRAL_AWARD",
                    "DIRECT_PERFORMANCE",
                    order.orderId,
                    order.orderId,
                    points.id,
                    null,
                    "direct-points:" + order.superiorUserId + ":" + order.orderId
            );
            if (points.frozenPoints > 0 && mapper.insertFrozenBatch(award.entryId()) != 1) {
                throw new DomainException("FROZEN_BATCH_CREATE_CONFLICT", "B 池冻结批次创建失败");
            }
        }
    }

    private void projectRepurchaseRelease(ProjectionOrderRow order) {
        ReleaseRuleRow rule = mapper.snapshottedReleaseRule(order.orderId);
        if (rule == null || order.totalAmountFen < rule.minimumAmountFen) {
            return;
        }
        LedgerAccountRow account = mapper.lockLedger(order.buyerUserId);
        if (account == null || account.frozenPoints <= 0) {
            return;
        }
        long release = Math.min(rule.releasePoints, account.frozenPoints);
        if (mapper.insertFrozenRelease(account.id, order.orderId, rule.id, release) == 0) {
            return;
        }
        List<FrozenBatchRow> batches = mapper.lockFrozenBatches(account.id);
        long batchBalance = 0;
        for (FrozenBatchRow batch : batches) {
            if (batch.remainingPoints == null
                    || batch.remainingPoints <= 0
                    || batchBalance > Long.MAX_VALUE - batch.remainingPoints) {
                throw frozenBatchBalanceConflict();
            }
            batchBalance += batch.remainingPoints;
        }
        if (batchBalance != account.frozenPoints) {
            throw frozenBatchBalanceConflict();
        }
        long remaining = release;
        for (FrozenBatchRow batch : batches) {
            if (remaining == 0) {
                break;
            }
            long points = Math.min(remaining, batch.remainingPoints);
            LedgerAward award = award(
                    order.buyerUserId,
                    points,
                    -points,
                    "FROZEN_POINTS_RELEASED",
                    "FROZEN_BATCH",
                    batch.id,
                    order.orderId,
                    rule.id,
                    batch.sourceLedgerEntryId,
                    "repurchase-release:" + order.buyerUserId + ":" + order.orderId + ":" + batch.id
            );
            if (mapper.insertFrozenReleaseItem(award.entryId(), batch.id, points) != 1) {
                throw new DomainException("FROZEN_RELEASE_ITEM_CREATE_CONFLICT", "B 池释放明细创建失败");
            }
            if (mapper.consumeFrozenBatch(batch.id, points) != 1) {
                throw frozenBatchBalanceConflict();
            }
            remaining -= points;
        }
        if (remaining != 0 || mapper.completeFrozenRelease(order.orderId, release) != 1) {
            throw frozenBatchBalanceConflict();
        }
    }

    private static DomainException frozenBatchBalanceConflict() {
        return new DomainException("FROZEN_BATCH_BALANCE_CONFLICT", "B 池冻结批次与账户余额不一致");
    }

    private LedgerAward award(long userId, long availableDelta, long frozenDelta, String entryType,
                              String sourceType, long sourceId, Long orderId, Long ruleId,
                              Long originalEntryId, String idempotencyKey) {
        LedgerAccountRow account = mapper.lockLedger(userId);
        if (account == null) {
            throw new DomainException("LEDGER_ACCOUNT_NOT_FOUND", "积分账户不存在");
        }
        int inserted = mapper.insertLedgerEntry(
                account.id,
                entryType,
                availableDelta,
                frozenDelta,
                sourceType,
                sourceId,
                orderId,
                ruleId,
                originalEntryId,
                idempotencyKey
        );
        if (inserted == 1 && mapper.updateLedger(account.id, availableDelta, frozenDelta) != 1) {
            throw new DomainException("LEDGER_BALANCE_CONFLICT", "积分余额更新失败");
        }
        Long entryId = mapper.ledgerEntryId(idempotencyKey);
        if (entryId == null) {
            throw new DomainException("LEDGER_ENTRY_NOT_FOUND", "积分流水写入失败");
        }
        return new LedgerAward(entryId);
    }

    private void reverseCompletedAfterSale(long afterSaleId) {
        Long orderId = mapper.completedAfterSaleOrderId(afterSaleId);
        if (orderId == null) {
            throw new DomainException("AFTERSALE_PROJECTION_INVALID", "售后单尚未完成");
        }
        for (ReversibleLedgerRow entry : mapper.reversibleEntries(orderId)) {
            LedgerAccountRow account = mapper.lockLedgerById(entry.accountId);
            if (account == null || account.frozenPoints == null) {
                throw frozenBatchBalanceConflict();
            }
            ReversalPlan plan = prepareReversal(entry, account);
            int inserted = mapper.insertLedgerEntry(
                    entry.accountId,
                    "REVERSAL",
                    plan.availableDelta,
                    plan.frozenDelta,
                    "AFTERSALE",
                    afterSaleId,
                    orderId,
                    entry.ruleVersionId,
                    entry.id,
                    "aftersale-reversal:" + afterSaleId + ":" + entry.id
            );
            if (inserted == 0) {
                continue;
            }
            applyBatchReversal(entry, plan);
            if (mapper.updateLedger(entry.accountId, plan.availableDelta, plan.frozenDelta) != 1) {
                throw new DomainException("LEDGER_REVERSAL_CONFLICT", "售后积分冲正失败");
            }
        }
        mapper.reverseFrozenRelease(orderId, afterSaleId);
        mapper.invalidateEvidence(orderId, afterSaleId);
        mapper.reversePerformance(orderId, afterSaleId);
        recalculateMember(mapper.orderBuyer(orderId));
        recalculateBeneficiary(mapper.orderSuperior(orderId));
    }

    private ReversalPlan prepareReversal(ReversibleLedgerRow entry, LedgerAccountRow account) {
        if (entry.accountId == null || entry.availableDelta == null || entry.frozenDelta == null) {
            throw frozenBatchBalanceConflict();
        }
        if (entry.frozenDelta == 0) {
            if ("FROZEN_POINTS_RELEASED".equals(entry.entryType)) {
                throw frozenBatchBalanceConflict();
            }
            return new ReversalPlan(negateExact(entry.availableDelta), 0, null, List.of());
        }
        assertFrozenBatchBalance(account);
        if ("DIRECT_REFERRAL_AWARD".equals(entry.entryType) && entry.frozenDelta > 0) {
            FrozenBatchRow batch = mapper.lockFrozenBatchBySourceEntry(entry.id);
            if (!validSourceBatch(batch, entry)) {
                throw frozenBatchBalanceConflict();
            }
            return new ReversalPlan(
                    negateExact(entry.availableDelta),
                    negateExact(batch.remainingPoints),
                    batch,
                    List.of()
            );
        }
        if ("FROZEN_POINTS_RELEASED".equals(entry.entryType) && entry.frozenDelta < 0) {
            validateReleaseDeltas(entry);
            return prepareReleaseReversal(entry);
        }
        throw frozenBatchBalanceConflict();
    }

    private ReversalPlan prepareReleaseReversal(ReversibleLedgerRow entry) {
        List<FrozenReleaseItemRow> items = mapper.lockFrozenReleaseItems(entry.id);
        if (items == null || items.isEmpty()
                || (entry.originalEntryId != null
                && (items.size() != 1
                || !entry.originalEntryId.equals(items.getFirst().sourceLedgerEntryId)))) {
            throw frozenBatchBalanceConflict();
        }
        long expected = positiveMagnitude(entry.frozenDelta);
        long mapped = 0;
        long restored = 0;
        Set<Long> batchIds = new java.util.HashSet<>();
        List<FrozenReleaseItemRow> restorable = new java.util.ArrayList<>();
        for (FrozenReleaseItemRow item : items) {
            if (item == null || item.batchId == null || item.accountId == null
                    || item.sourceLedgerEntryId == null || item.originalPoints == null
                    || item.remainingPoints == null || item.points == null
                    || item.sourceAccountId == null || item.sourceEntryType == null
                    || item.sourceOrderId == null || item.sourceRuleVersionId == null
                    || item.sourceFrozenDelta == null || item.batchSourceOrderId == null
                    || item.batchRuleVersionId == null
                    || item.points <= 0 || !batchIds.add(item.batchId)
                    || item.accountId.longValue() != entry.accountId.longValue()
                    || item.sourceAccountId.longValue() != entry.accountId.longValue()
                    || !"DIRECT_REFERRAL_AWARD".equals(item.sourceEntryType)
                    || !item.sourceOrderId.equals(item.batchSourceOrderId)
                    || !item.sourceRuleVersionId.equals(item.batchRuleVersionId)
                    || item.sourceFrozenDelta <= 0
                    || item.originalPoints.longValue() != item.sourceFrozenDelta
                    || item.originalPoints <= 0 || item.remainingPoints < 0
                    || item.remainingPoints > item.originalPoints
                    || !validBatchStatus(item.status)
                    || ("ACTIVE".equals(item.status) && item.remainingPoints == 0)
                    || ("CONSUMED".equals(item.status) && item.remainingPoints != 0)
                    || ("REVERSED".equals(item.status) && item.remainingPoints != 0)) {
                throw frozenBatchBalanceConflict();
            }
            if (mapped > Long.MAX_VALUE - item.points) {
                throw frozenBatchBalanceConflict();
            }
            mapped += item.points;
            if (!"REVERSED".equals(item.status)) {
                if (item.remainingPoints > item.originalPoints - item.points) {
                    throw frozenBatchBalanceConflict();
                }
                if (restored > Long.MAX_VALUE - item.points) {
                    throw frozenBatchBalanceConflict();
                }
                restored += item.points;
                restorable.add(item);
            }
        }
        if (mapped != expected) {
            throw frozenBatchBalanceConflict();
        }
        return new ReversalPlan(negateExact(restored), restored, null, List.copyOf(restorable));
    }

    private void applyBatchReversal(ReversibleLedgerRow entry, ReversalPlan plan) {
        if ("DIRECT_REFERRAL_AWARD".equals(entry.entryType) && entry.frozenDelta > 0) {
            if (plan.sourceBatch == null
                    || mapper.reverseFrozenBatch(entry.id, negateExact(plan.frozenDelta)) != 1) {
                throw frozenBatchBalanceConflict();
            }
            return;
        }
        if ("FROZEN_POINTS_RELEASED".equals(entry.entryType) && entry.frozenDelta < 0) {
            for (FrozenReleaseItemRow item : plan.restorableItems) {
                if (mapper.restoreFrozenBatchById(
                        item.batchId,
                        item.accountId,
                        item.sourceLedgerEntryId,
                        item.points
                ) != 1) {
                    throw frozenBatchBalanceConflict();
                }
            }
            return;
        }
        if (entry.frozenDelta != 0) {
            throw frozenBatchBalanceConflict();
        }
    }

    private void assertFrozenBatchBalance(LedgerAccountRow account) {
        if (account == null || account.id == null || account.frozenPoints == null) {
            throw frozenBatchBalanceConflict();
        }
        Long batchBalance = mapper.activeFrozenBatchBalance(account.id);
        if (batchBalance == null || batchBalance < 0 || !batchBalance.equals(account.frozenPoints)) {
            throw frozenBatchBalanceConflict();
        }
    }

    private static boolean validSourceBatch(FrozenBatchRow batch, ReversibleLedgerRow entry) {
        return batch != null
                && entry.accountId != null
                && batch.accountId != null && batch.accountId.longValue() == entry.accountId.longValue()
                && batch.sourceLedgerEntryId != null
                && batch.sourceEntryType != null
                && "DIRECT_REFERRAL_AWARD".equals(batch.sourceEntryType)
                && batch.sourceAccountId != null
                && batch.sourceAccountId.longValue() == entry.accountId.longValue()
                && batch.sourceOrderIdFromLedger != null
                && batch.sourceOrderId != null
                && batch.sourceOrderId.equals(batch.sourceOrderIdFromLedger)
                && batch.sourceRuleVersionId != null
                && batch.ruleVersionId != null
                && batch.ruleVersionId.equals(batch.sourceRuleVersionId)
                && batch.sourceFrozenDelta != null && batch.sourceFrozenDelta > 0
                && batch.originalPoints != null
                && batch.originalPoints.longValue() == batch.sourceFrozenDelta
                && batch.originalPoints > 0
                && batch.remainingPoints != null
                && batch.remainingPoints >= 0
                && batch.remainingPoints <= batch.originalPoints
                && validBatchStatus(batch.status)
                && (("ACTIVE".equals(batch.status) && batch.remainingPoints > 0)
                || ("CONSUMED".equals(batch.status) && batch.remainingPoints == 0));
    }

    private static boolean validBatchStatus(String status) {
        return "ACTIVE".equals(status) || "CONSUMED".equals(status) || "REVERSED".equals(status);
    }

    private static void validateReleaseDeltas(ReversibleLedgerRow entry) {
        long expectedAvailable = positiveMagnitude(entry.frozenDelta);
        if (entry.availableDelta == null || entry.availableDelta <= 0
                || entry.availableDelta != expectedAvailable) {
            throw frozenBatchBalanceConflict();
        }
    }

    private static long positiveMagnitude(long value) {
        if (value >= 0 || value == Long.MIN_VALUE) {
            throw frozenBatchBalanceConflict();
        }
        return -value;
    }

    private static long negateExact(long value) {
        if (value == Long.MIN_VALUE) {
            throw frozenBatchBalanceConflict();
        }
        return -value;
    }

    private record ReversalPlan(long availableDelta, long frozenDelta,
                                FrozenBatchRow sourceBatch,
                                List<FrozenReleaseItemRow> restorableItems) {
    }

    private void recalculateMember(Long userId) {
        if (userId == null) {
            return;
        }
        mapper.resetMemberToBasic(userId);
        String evidenceLevel = mapper.highestEvidenceLevel(userId);
        if (evidenceLevel != null) {
            mapper.promoteMember(userId, evidenceLevel);
        }
        DirectRuleRow directRule = mapper.activeDirectRule();
        if (directRule != null && mapper.activeDirectCount(userId) >= directRule.requiredCount) {
            mapper.promoteMember(userId, directRule.targetLevel);
        }
    }

    private void recalculateBeneficiary(Long userId) {
        if (userId == null) {
            return;
        }
        DirectRuleRow directRule = mapper.activeDirectRule();
        if (directRule != null && mapper.activeDirectCount(userId) >= directRule.requiredCount) {
            mapper.promoteMember(userId, directRule.targetLevel);
        } else {
            mapper.downgradeDividendToSuper(userId);
        }
    }

    private void promote(long userId, String targetLevel, Long ruleId, String triggerType,
                         String triggerId, String idempotencyKey) {
        MemberLevelRow before = mapper.lockMemberLevel(userId);
        if (before != null && mapper.promoteMember(userId, targetLevel) == 1) {
            mapper.insertLevelChange(
                    userId,
                    before.code,
                    targetLevel,
                    triggerType,
                    triggerId,
                    ruleId,
                    "SYSTEM",
                    "membership-projector",
                    "会员任务满足后自动升级",
                    idempotencyKey
            );
        }
    }

    private record LedgerAward(long entryId) {
    }
}

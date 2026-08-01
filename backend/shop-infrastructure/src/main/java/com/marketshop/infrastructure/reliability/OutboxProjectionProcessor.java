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
        if (ordinal >= rule.requiredCount) {
            promote(order.superiorUserId, rule.targetLevel, rule.id,
                    "DIRECT_REFERRAL_QUALIFIED", Long.toString(order.orderId),
                    "direct-promotion:" + order.superiorUserId + ":" + order.orderId + ":" + rule.id);
        }
        PointsRuleRow points = mapper.snapshottedPointsRule(order.orderId);
        if (points != null && ordinal >= points.pointsStartOrdinal) {
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
            long availableDelta = -entry.availableDelta;
            long frozenDelta = -entry.frozenDelta;
            if ("DIRECT_REFERRAL_AWARD".equals(entry.entryType) && entry.frozenDelta > 0) {
                mapper.reverseFrozenBatch(entry.id);
            } else if ("FROZEN_POINTS_RELEASED".equals(entry.entryType) && entry.frozenDelta < 0) {
                long restoredPoints = restoreReleasedBatches(entry);
                availableDelta = -restoredPoints;
                frozenDelta = restoredPoints;
            }
            if (account.frozenPoints + frozenDelta < 0) {
                long deficit = -(account.frozenPoints + frozenDelta);
                frozenDelta = -account.frozenPoints;
                availableDelta -= deficit;
            }
            int inserted = mapper.insertLedgerEntry(
                    entry.accountId,
                    "REVERSAL",
                    availableDelta,
                    frozenDelta,
                    "AFTERSALE",
                    afterSaleId,
                    orderId,
                    entry.ruleVersionId,
                    entry.id,
                    "aftersale-reversal:" + afterSaleId + ":" + entry.id
            );
            if (inserted == 1 && mapper.updateLedger(entry.accountId, availableDelta, frozenDelta) != 1) {
                throw new DomainException("LEDGER_REVERSAL_CONFLICT", "售后积分冲正失败");
            }
        }
        mapper.reverseFrozenRelease(orderId, afterSaleId);
        mapper.invalidateEvidence(orderId, afterSaleId);
        mapper.reversePerformance(orderId, afterSaleId);
        recalculateMember(mapper.orderBuyer(orderId));
        recalculateBeneficiary(mapper.orderSuperior(orderId));
    }

    private long restoreReleasedBatches(ReversibleLedgerRow entry) {
        List<FrozenReleaseItemRow> items = mapper.lockFrozenReleaseItems(entry.id);
        long restoredPoints = 0;
        for (FrozenReleaseItemRow item : items) {
            if (mapper.restoreFrozenBatchById(item.batchId, item.points) == 1) {
                restoredPoints += item.points;
            }
        }
        if (items.isEmpty()
                && entry.originalEntryId != null
                && mapper.restoreFrozenBatch(entry.originalEntryId, -entry.frozenDelta) == 1) {
            restoredPoints = -entry.frozenDelta;
        }
        return restoredPoints;
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

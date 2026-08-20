package com.marketshop.infrastructure.reliability;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxProjectionProcessorTest {

    @Mock
    private DistributionMapper mapper;

    @InjectMocks
    private OutboxProjectionProcessor processor;

    @Test
    void releasesFrozenPointsAcrossBatchesInFifoOrder() {
        when(mapper.lockNextOutbox()).thenReturn(event("release-event", "900", "ORDER_COMPLETED"));
        when(mapper.projectionOrder(900)).thenReturn(order(900, 42, false, true, 199_800));
        when(mapper.snapshottedReleaseRule(900)).thenReturn(releaseRule(33, 199_800, 160));
        when(mapper.lockLedger(42)).thenReturn(account(7, 320, 250));
        when(mapper.insertFrozenRelease(7, 900, 33, 160)).thenReturn(1);
        when(mapper.lockFrozenBatches(7)).thenReturn(List.of(
                batch(21, 101, 100),
                batch(22, 102, 150)
        ));
        when(mapper.insertLedgerEntry(
                anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyLong(),
                nullable(Long.class), nullable(Long.class), nullable(Long.class), anyString()
        )).thenReturn(1);
        when(mapper.updateLedger(anyLong(), anyLong(), anyLong())).thenReturn(1);
        when(mapper.ledgerEntryId(anyString())).thenReturn(501L, 502L);
        when(mapper.insertFrozenReleaseItem(anyLong(), anyLong(), anyLong())).thenReturn(1);
        when(mapper.consumeFrozenBatch(anyLong(), anyLong())).thenReturn(1);
        when(mapper.completeFrozenRelease(900, 160)).thenReturn(1);

        assertThat(processor.processNext()).isTrue();

        verify(mapper).insertLedgerEntry(
                7, "FROZEN_POINTS_RELEASED", 100, -100, "FROZEN_BATCH", 21,
                900L, 33L, 101L, "repurchase-release:42:900:21"
        );
        verify(mapper).insertLedgerEntry(
                7, "FROZEN_POINTS_RELEASED", 60, -60, "FROZEN_BATCH", 22,
                900L, 33L, 102L, "repurchase-release:42:900:22"
        );
        verify(mapper).insertFrozenReleaseItem(501, 21, 100);
        verify(mapper).insertFrozenReleaseItem(502, 22, 60);
        InOrder fifo = inOrder(mapper);
        fifo.verify(mapper).consumeFrozenBatch(21, 100);
        fifo.verify(mapper).consumeFrozenBatch(22, 60);
    }

    @Test
    void duplicateCompletedOrderCannotReleaseAnotherBatch() {
        when(mapper.lockNextOutbox()).thenReturn(event("duplicate-event", "900", "ORDER_COMPLETED"));
        when(mapper.projectionOrder(900)).thenReturn(order(900, 42, false, true, 199_800));
        when(mapper.snapshottedReleaseRule(900)).thenReturn(releaseRule(33, 199_800, 160));
        when(mapper.lockLedger(42)).thenReturn(account(7, 320, 250));
        when(mapper.insertFrozenRelease(7, 900, 33, 160)).thenReturn(0);

        assertThat(processor.processNext()).isTrue();

        verify(mapper, never()).lockFrozenBatches(anyLong());
        verify(mapper, never()).consumeFrozenBatch(anyLong(), anyLong());
    }

    @Test
    void batchSumMismatchRollsBackBeforeAnyConsumption() {
        when(mapper.lockNextOutbox()).thenReturn(event("mismatch-event", "903", "ORDER_COMPLETED"));
        when(mapper.projectionOrder(903)).thenReturn(order(903, 42, false, true, 199_800));
        when(mapper.snapshottedReleaseRule(903)).thenReturn(releaseRule(33, 199_800, 160));
        when(mapper.lockLedger(42)).thenReturn(account(7, 320, 250));
        when(mapper.insertFrozenRelease(7, 903, 33, 160)).thenReturn(1);
        when(mapper.lockFrozenBatches(7)).thenReturn(List.of(
                batch(21, 101, 100),
                batch(22, 102, 200)
        ));

        assertThatThrownBy(() -> processor.processNext())
                .isInstanceOf(OutboxProjectionFailure.class)
                .hasRootCauseMessage("B 池冻结批次与账户余额不一致");

        verify(mapper, never()).consumeFrozenBatch(anyLong(), anyLong());
        verify(mapper, never()).insertFrozenReleaseItem(anyLong(), anyLong(), anyLong());
    }

    @Test
    void completedOrderWithCompletedAftersaleDoesNotProjectAwards() {
        when(mapper.lockNextOutbox()).thenReturn(event("blocked-complete", "900", "ORDER_COMPLETED"));
        when(mapper.countCompletedAfterSales(900)).thenReturn(1);

        assertThat(processor.processNext()).isTrue();

        verify(mapper, never()).projectionOrder(anyLong());
        verify(mapper, never()).snapshottedSelfRules(anyLong());
        verify(mapper, never()).snapshottedDirectRule(anyLong());
        verify(mapper, never()).insertFrozenRelease(anyLong(), anyLong(), anyLong(), anyLong());
        verify(mapper).insertInbox(anyString(), eq("blocked-complete"));
        verify(mapper).markOutboxPublished(1L);
    }

    @Test
    void ordinaryCompletedOrderDoesNotEnterRepurchaseRelease() {
        when(mapper.lockNextOutbox()).thenReturn(event("ordinary-event", "901", "ORDER_COMPLETED"));
        when(mapper.projectionOrder(901)).thenReturn(order(901, 42, false, false, 199_800));

        assertThat(processor.processNext()).isTrue();

        verify(mapper, never()).snapshottedReleaseRule(901);
        verify(mapper, never()).insertFrozenRelease(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void repurchaseBelowConfiguredThresholdDoesNotRelease() {
        when(mapper.lockNextOutbox()).thenReturn(event("small-repurchase-event", "902", "ORDER_COMPLETED"));
        when(mapper.projectionOrder(902)).thenReturn(order(902, 42, false, true, 99_800));
        when(mapper.snapshottedReleaseRule(902)).thenReturn(releaseRule(33, 199_800, 160));

        assertThat(processor.processNext()).isTrue();

        verify(mapper, never()).lockLedger(anyLong());
        verify(mapper, never()).insertFrozenRelease(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void sixthQualifiedReferralCreatesFrozenBatch() {
        when(mapper.lockNextOutbox()).thenReturn(event("direct-event", "700", "ORDER_COMPLETED"));
        when(mapper.projectionOrder(700)).thenReturn(order(700, 77, true, false, 199_800));
        when(mapper.snapshottedSelfRules(700)).thenReturn(List.of());
        DirectRuleRow direct = new DirectRuleRow();
        direct.id = 31L;
        direct.minimumAmountFen = 199_800L;
        direct.requiredRank = 2;
        direct.requiredCount = 5;
        direct.targetLevel = "DIVIDEND_MEMBER";
        when(mapper.snapshottedDirectRule(700)).thenReturn(direct);
        MemberLevelRow level = new MemberLevelRow();
        level.rankNo = 2;
        when(mapper.lockMemberLevel(77)).thenReturn(level);
        when(mapper.lockDirectPerformanceOwner(42)).thenReturn(9L);
        when(mapper.nextDirectOrdinal(42)).thenReturn(6);
        when(mapper.insertDirectPerformance(42, 77, 700, 31, 6, 199_800)).thenReturn(1);
        when(mapper.activeDirectCount(42)).thenReturn(6);
        PointsRuleRow points = new PointsRuleRow();
        points.id = 32L;
        points.pointsStartOrdinal = 6;
        points.availablePoints = 160L;
        points.frozenPoints = 160L;
        when(mapper.snapshottedPointsRule(700)).thenReturn(points);
        when(mapper.lockLedger(42)).thenReturn(account(7, 0, 0));
        when(mapper.insertLedgerEntry(
                anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyLong(),
                nullable(Long.class), nullable(Long.class), nullable(Long.class), anyString()
        )).thenReturn(1);
        when(mapper.updateLedger(7, 160, 160)).thenReturn(1);
        when(mapper.ledgerEntryId("direct-points:42:700")).thenReturn(501L);
        when(mapper.insertFrozenBatch(501)).thenReturn(1);

        assertThat(processor.processNext()).isTrue();

        verify(mapper).insertFrozenBatch(501);
        verify(mapper).insertLedgerEntry(
                7, "DIRECT_REFERRAL_AWARD", 160, 160, "DIRECT_PERFORMANCE", 700,
                700L, 32L, null, "direct-points:42:700"
        );
        verify(mapper).snapshottedDirectRule(700);
        verify(mapper).snapshottedPointsRule(700);
        verify(mapper, never()).activeDirectRule();
        InOrder ordinalAllocation = inOrder(mapper);
        ordinalAllocation.verify(mapper).lockDirectPerformanceOwner(42);
        ordinalAllocation.verify(mapper).nextDirectOrdinal(42);
        ordinalAllocation.verify(mapper).insertDirectPerformance(42, 77, 700, 31, 6, 199_800);
    }

    @Test
    void duplicateActiveReferralDoesNotTouchPerformanceOrAwardPoints() {
        when(mapper.lockNextOutbox()).thenReturn(event("duplicate-direct-event", "701", "ORDER_COMPLETED"));
        when(mapper.projectionOrder(701)).thenReturn(order(701, 77, true, false, 199_800));
        when(mapper.snapshottedSelfRules(701)).thenReturn(List.of());
        DirectRuleRow direct = new DirectRuleRow();
        direct.id = 31L;
        direct.minimumAmountFen = 199_800L;
        direct.requiredRank = 2;
        direct.requiredCount = 5;
        direct.targetLevel = "DIVIDEND_MEMBER";
        when(mapper.snapshottedDirectRule(701)).thenReturn(direct);
        MemberLevelRow level = new MemberLevelRow();
        level.rankNo = 2;
        when(mapper.lockMemberLevel(77)).thenReturn(level);
        when(mapper.lockDirectPerformanceOwner(42)).thenReturn(9L);
        when(mapper.nextDirectOrdinal(42)).thenReturn(6);
        when(mapper.insertDirectPerformance(42, 77, 701, 31, 6, 199_800)).thenReturn(0);

        assertThat(processor.processNext()).isTrue();

        verify(mapper, never()).touchPerformance(42);
        verify(mapper, never()).snapshottedPointsRule(701);
        verify(mapper, never()).lockLedger(42);
    }

    @Test
    void reversedHistoryKeepsTheNextOrdinalInTheAllHistorySequence() {
        when(mapper.lockNextOutbox()).thenReturn(event("reversed-history-event", "702", "ORDER_COMPLETED"));
        when(mapper.projectionOrder(702)).thenReturn(order(702, 77, true, false, 199_800));
        when(mapper.snapshottedSelfRules(702)).thenReturn(List.of());
        DirectRuleRow direct = new DirectRuleRow();
        direct.id = 31L;
        direct.minimumAmountFen = 199_800L;
        direct.requiredRank = 2;
        direct.requiredCount = 5;
        direct.targetLevel = "DIVIDEND_MEMBER";
        when(mapper.snapshottedDirectRule(702)).thenReturn(direct);
        MemberLevelRow level = new MemberLevelRow();
        level.rankNo = 2;
        when(mapper.lockMemberLevel(77)).thenReturn(level);
        when(mapper.lockDirectPerformanceOwner(42)).thenReturn(9L);
        when(mapper.nextDirectOrdinal(42)).thenReturn(7);
        when(mapper.insertDirectPerformance(42, 77, 702, 31, 7, 199_800)).thenReturn(1);
        when(mapper.activeDirectCount(42)).thenReturn(5);
        PointsRuleRow points = new PointsRuleRow();
        points.id = 32L;
        points.pointsStartOrdinal = 6;
        points.availablePoints = 160L;
        points.frozenPoints = 160L;
        when(mapper.snapshottedPointsRule(702)).thenReturn(points);

        assertThat(processor.processNext()).isTrue();

        verify(mapper).insertDirectPerformance(42, 77, 702, 31, 7, 199_800);
        verify(mapper).snapshottedPointsRule(702);
        verify(mapper, never()).lockLedger(42);
    }

    @Test
    void activeCountControlsQualificationAndRewardWhenHistoricalOrdinalIsBelowThreshold() {
        when(mapper.lockNextOutbox()).thenReturn(event("active-count-boundary-event", "703", "ORDER_COMPLETED"));
        when(mapper.projectionOrder(703)).thenReturn(order(703, 77, true, false, 199_800));
        when(mapper.snapshottedSelfRules(703)).thenReturn(List.of());
        DirectRuleRow direct = new DirectRuleRow();
        direct.id = 31L;
        direct.minimumAmountFen = 199_800L;
        direct.requiredRank = 2;
        direct.requiredCount = 5;
        direct.targetLevel = "DIVIDEND_MEMBER";
        when(mapper.snapshottedDirectRule(703)).thenReturn(direct);
        MemberLevelRow referredLevel = new MemberLevelRow();
        referredLevel.rankNo = 2;
        when(mapper.lockMemberLevel(77)).thenReturn(referredLevel);
        MemberLevelRow superiorLevel = new MemberLevelRow();
        superiorLevel.code = "SUPER_MEMBER";
        when(mapper.lockMemberLevel(42)).thenReturn(superiorLevel);
        when(mapper.lockDirectPerformanceOwner(42)).thenReturn(9L);
        when(mapper.nextDirectOrdinal(42)).thenReturn(5);
        when(mapper.insertDirectPerformance(42, 77, 703, 31, 5, 199_800)).thenReturn(1);
        when(mapper.activeDirectCount(42)).thenReturn(6);
        PointsRuleRow points = new PointsRuleRow();
        points.id = 32L;
        points.pointsStartOrdinal = 6;
        points.availablePoints = 160L;
        points.frozenPoints = 160L;
        when(mapper.snapshottedPointsRule(703)).thenReturn(points);
        when(mapper.lockLedger(42)).thenReturn(account(7, 0, 0));
        when(mapper.insertLedgerEntry(
                anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyLong(),
                nullable(Long.class), nullable(Long.class), nullable(Long.class), anyString()
        )).thenReturn(1);
        when(mapper.updateLedger(7, 160, 160)).thenReturn(1);
        when(mapper.ledgerEntryId("direct-points:42:703")).thenReturn(503L);
        when(mapper.insertFrozenBatch(503)).thenReturn(1);

        assertThat(processor.processNext()).isTrue();

        verify(mapper).promoteMember(42, "DIVIDEND_MEMBER");
        verify(mapper).insertLedgerEntry(
                7, "DIRECT_REFERRAL_AWARD", 160, 160, "DIRECT_PERFORMANCE", 703,
                703L, 32L, null, "direct-points:42:703"
        );
        verify(mapper).insertFrozenBatch(503);
    }

    @Test
    void repurchaseAfterSaleRestoresAllMappedSourceBatches() {
        when(mapper.lockNextOutbox()).thenReturn(event("after-sale-event", "88", "AFTERSALE_COMPLETED"));
        when(mapper.completedAfterSaleOrderId(88)).thenReturn(900L);
        ReversibleLedgerRow release = new ReversibleLedgerRow();
        release.id = 501L;
        release.accountId = 7L;
        release.entryType = "FROZEN_POINTS_RELEASED";
        release.availableDelta = 160L;
        release.frozenDelta = -160L;
        release.ruleVersionId = 33L;
        release.originalEntryId = null;
        when(mapper.reversibleEntries(900)).thenReturn(List.of(release));
        when(mapper.lockLedgerById(7)).thenReturn(account(7, 320, 0));
        when(mapper.activeFrozenBatchBalance(7)).thenReturn(0L);
        when(mapper.lockFrozenReleaseItems(501)).thenReturn(List.of(
                releaseItem(21, 7, 101, 100, 0, "CONSUMED", 100),
                releaseItem(22, 7, 102, 150, 0, "CONSUMED", 60)
        ));
        when(mapper.restoreFrozenBatchById(21, 7, 101, 100)).thenReturn(1);
        when(mapper.restoreFrozenBatchById(22, 7, 102, 60)).thenReturn(1);
        when(mapper.insertLedgerEntry(
                anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyLong(),
                nullable(Long.class), nullable(Long.class), nullable(Long.class), anyString()
        )).thenReturn(1);
        when(mapper.updateLedger(7, -160, 160)).thenReturn(1);

        assertThat(processor.processNext()).isTrue();

        verify(mapper).restoreFrozenBatchById(21, 7, 101, 100);
        verify(mapper).restoreFrozenBatchById(22, 7, 102, 60);
        verify(mapper).insertLedgerEntry(
                7, "REVERSAL", -160, 160, "AFTERSALE", 88,
                900L, 33L, 501L, "aftersale-reversal:88:501"
        );
        verify(mapper).reverseFrozenRelease(900, 88);
    }

    @Test
    void directAwardAfterSaleClosesItsUnreleasedBatch() {
        when(mapper.lockNextOutbox()).thenReturn(event("direct-after-sale-event", "89", "AFTERSALE_COMPLETED"));
        when(mapper.completedAfterSaleOrderId(89)).thenReturn(700L);
        ReversibleLedgerRow award = new ReversibleLedgerRow();
        award.id = 601L;
        award.accountId = 7L;
        award.entryType = "DIRECT_REFERRAL_AWARD";
        award.availableDelta = 160L;
        award.frozenDelta = 160L;
        award.ruleVersionId = 32L;
        when(mapper.reversibleEntries(700)).thenReturn(List.of(award));
        when(mapper.lockLedgerById(7)).thenReturn(account(7, 160, 160));
        when(mapper.activeFrozenBatchBalance(7)).thenReturn(160L);
        when(mapper.lockFrozenBatchBySourceEntry(601))
                .thenReturn(batch(601, 7, 601, 160, 160, "ACTIVE"));
        when(mapper.reverseFrozenBatch(601, 160)).thenReturn(1);
        when(mapper.insertLedgerEntry(
                anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyLong(),
                nullable(Long.class), nullable(Long.class), nullable(Long.class), anyString()
        )).thenReturn(1);
        when(mapper.updateLedger(7, -160, -160)).thenReturn(1);

        assertThat(processor.processNext()).isTrue();

        verify(mapper).reverseFrozenBatch(601, 160);
        verify(mapper).insertLedgerEntry(
                7, "REVERSAL", -160, -160, "AFTERSALE", 89,
                700L, 32L, 601L, "aftersale-reversal:89:601"
        );
    }

    @Test
    void sourceAwardReversalAfterReleaseReversalUsesTheRestoredSourceBatch() {
        when(mapper.lockNextOutbox()).thenReturn(
                event("release-before-source", "88", "AFTERSALE_COMPLETED"),
                event("source-after-release", "89", "AFTERSALE_COMPLETED")
        );
        when(mapper.completedAfterSaleOrderId(88)).thenReturn(900L);
        when(mapper.completedAfterSaleOrderId(89)).thenReturn(700L);
        ReversibleLedgerRow release = release(501L, 7L, 160L, -160L, 601L);
        ReversibleLedgerRow award = new ReversibleLedgerRow();
        award.id = 601L;
        award.accountId = 7L;
        award.entryType = "DIRECT_REFERRAL_AWARD";
        award.availableDelta = 160L;
        award.frozenDelta = 160L;
        award.ruleVersionId = 32L;
        when(mapper.reversibleEntries(900)).thenReturn(List.of(release));
        when(mapper.reversibleEntries(700)).thenReturn(List.of(award));
        when(mapper.lockLedgerById(7)).thenReturn(account(7, 320, 0), account(7, 160, 160));
        when(mapper.activeFrozenBatchBalance(7)).thenReturn(0L, 160L);
        when(mapper.lockFrozenReleaseItems(501)).thenReturn(List.of(
                releaseItem(21, 7, 601, 160, 0, "CONSUMED", 160)
        ));
        when(mapper.restoreFrozenBatchById(21, 7, 601, 160)).thenReturn(1);
        when(mapper.lockFrozenBatchBySourceEntry(601))
                .thenReturn(batch(21, 7, 601, 160, 160, "ACTIVE"));
        when(mapper.reverseFrozenBatch(601, 160)).thenReturn(1);
        when(mapper.insertLedgerEntry(
                anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyLong(),
                nullable(Long.class), nullable(Long.class), nullable(Long.class), anyString()
        )).thenReturn(1);
        when(mapper.updateLedger(7, -160, 160)).thenReturn(1);
        when(mapper.updateLedger(7, -160, -160)).thenReturn(1);

        assertThat(processor.processNext()).isTrue();
        assertThat(processor.processNext()).isTrue();

        verify(mapper).restoreFrozenBatchById(21, 7, 601, 160);
        verify(mapper).reverseFrozenBatch(601, 160);
        verify(mapper).insertLedgerEntry(
                7, "REVERSAL", -160, 160, "AFTERSALE", 88,
                900L, 33L, 501L, "aftersale-reversal:88:501"
        );
        verify(mapper).insertLedgerEntry(
                7, "REVERSAL", -160, -160, "AFTERSALE", 89,
                700L, 32L, 601L, "aftersale-reversal:89:601"
        );
    }

    @ParameterizedTest
    @CsvSource({"100, ACTIVE, 100", "0, CONSUMED, 0"})
    void directAwardReversalUsesOnlyTheSourceBatchAmountStillRemaining(
            long remainingPoints, String batchStatus, long accountFrozenPoints) {
        when(mapper.lockNextOutbox()).thenReturn(event("direct-remaining-" + remainingPoints, "91", "AFTERSALE_COMPLETED"));
        when(mapper.completedAfterSaleOrderId(91)).thenReturn(700L);
        ReversibleLedgerRow award = new ReversibleLedgerRow();
        award.id = 601L;
        award.accountId = 7L;
        award.entryType = "DIRECT_REFERRAL_AWARD";
        award.availableDelta = 160L;
        award.frozenDelta = 160L;
        award.ruleVersionId = 32L;
        when(mapper.reversibleEntries(700)).thenReturn(List.of(award));
        when(mapper.lockLedgerById(7)).thenReturn(account(7, 160, accountFrozenPoints));
        when(mapper.activeFrozenBatchBalance(7)).thenReturn(accountFrozenPoints);
        when(mapper.lockFrozenBatchBySourceEntry(601))
                .thenReturn(batch(601, 7, 601, 160, remainingPoints, batchStatus));
        when(mapper.reverseFrozenBatch(601, remainingPoints)).thenReturn(1);
        when(mapper.insertLedgerEntry(
                anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyLong(),
                nullable(Long.class), nullable(Long.class), nullable(Long.class), anyString()
        )).thenReturn(1);
        when(mapper.updateLedger(7, -160, -remainingPoints)).thenReturn(1);

        assertThat(processor.processNext()).isTrue();

        verify(mapper).reverseFrozenBatch(601, remainingPoints);
        verify(mapper).insertLedgerEntry(
                7, "REVERSAL", -160, -remainingPoints, "AFTERSALE", 91,
                700L, 32L, 601L, "aftersale-reversal:91:601"
        );
    }

    @Test
    void missingReleaseProvenanceFailsBeforeLedgerMutation() {
        when(mapper.lockNextOutbox()).thenReturn(event("missing-release-mapping", "92", "AFTERSALE_COMPLETED"));
        when(mapper.completedAfterSaleOrderId(92)).thenReturn(900L);
        ReversibleLedgerRow release = release(801L, 7L, 100L, -100L, null);
        when(mapper.reversibleEntries(900)).thenReturn(List.of(release));
        when(mapper.lockLedgerById(7)).thenReturn(account(7, 200, 100));
        when(mapper.activeFrozenBatchBalance(7)).thenReturn(100L);
        when(mapper.lockFrozenReleaseItems(801)).thenReturn(List.of());

        assertThatThrownBy(() -> processor.processNext())
                .isInstanceOf(OutboxProjectionFailure.class)
                .hasRootCauseMessage("B 池冻结批次与账户余额不一致");

        verify(mapper, never()).insertLedgerEntry(
                anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyLong(),
                nullable(Long.class), nullable(Long.class), nullable(Long.class), anyString()
        );
        verify(mapper, never()).updateLedger(anyLong(), anyLong(), anyLong());
    }

    @Test
    void conflictingReleaseProvenanceFailsBeforeLedgerMutation() {
        when(mapper.lockNextOutbox()).thenReturn(event("conflicting-release-mapping", "93", "AFTERSALE_COMPLETED"));
        when(mapper.completedAfterSaleOrderId(93)).thenReturn(900L);
        ReversibleLedgerRow release = release(802L, 7L, 100L, -100L, null);
        when(mapper.reversibleEntries(900)).thenReturn(List.of(release));
        when(mapper.lockLedgerById(7)).thenReturn(account(7, 200, 100));
        when(mapper.activeFrozenBatchBalance(7)).thenReturn(100L);
        when(mapper.lockFrozenReleaseItems(802)).thenReturn(List.of(
                releaseItem(21, 7, 101, 100, 0, "CONSUMED", 60)
        ));

        assertThatThrownBy(() -> processor.processNext())
                .isInstanceOf(OutboxProjectionFailure.class)
                .hasRootCauseMessage("B 池冻结批次与账户余额不一致");

        verify(mapper, never()).insertLedgerEntry(
                anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyLong(),
                nullable(Long.class), nullable(Long.class), nullable(Long.class), anyString()
        );
        verify(mapper, never()).updateLedger(anyLong(), anyLong(), anyLong());
    }

    @Test
    void directAwardReversalRejectsOriginalBatchPointsMismatch() {
        when(mapper.lockNextOutbox()).thenReturn(event("direct-points-mismatch", "101", "AFTERSALE_COMPLETED"));
        when(mapper.completedAfterSaleOrderId(101)).thenReturn(700L);
        ReversibleLedgerRow award = new ReversibleLedgerRow();
        award.id = 601L;
        award.accountId = 7L;
        award.entryType = "DIRECT_REFERRAL_AWARD";
        award.availableDelta = 160L;
        award.frozenDelta = 160L;
        award.ruleVersionId = 32L;
        when(mapper.reversibleEntries(700)).thenReturn(List.of(award));
        when(mapper.lockLedgerById(7)).thenReturn(account(7, 160, 160));
        when(mapper.activeFrozenBatchBalance(7)).thenReturn(160L);
        FrozenBatchRow batch = batch(601, 7, 601, 160, 160, "ACTIVE");
        batch.sourceFrozenDelta = 159L;
        when(mapper.lockFrozenBatchBySourceEntry(601)).thenReturn(batch);
        assertProvenanceConflict();
    }

    @Test
    void releaseReversalAcceptsNumericallyEqualBoxedHighAccountIds() {
        ReversibleLedgerRow release = release(810L, 1000L, 100L, -100L, null);
        FrozenReleaseItemRow item = releaseItem(21L, 1000L, 1001L, 100L, 0L, "CONSUMED", 100L);
        configureReleaseReversal(102, release, item);
        when(mapper.insertLedgerEntry(
                anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyLong(),
                nullable(Long.class), nullable(Long.class), nullable(Long.class), anyString()
        )).thenReturn(1);
        when(mapper.restoreFrozenBatchById(21L, 1000L, 1001L, 100L)).thenReturn(1);
        when(mapper.updateLedger(1000L, -100L, 100L)).thenReturn(1);

        assertThat(processor.processNext()).isTrue();

        verify(mapper).restoreFrozenBatchById(21L, 1000L, 1001L, 100L);
        verify(mapper).updateLedger(1000L, -100L, 100L);
    }

    @Test
    void releaseReversalRejectsNumericallyDifferentHighAccountId() {
        ReversibleLedgerRow release = release(811L, 1000L, 100L, -100L, null);
        FrozenReleaseItemRow item = releaseItem(21L, 1000L, 1001L, 100L, 0L, "CONSUMED", 100L);
        item.sourceAccountId = 1001L;
        configureReleaseReversal(103, release, item);
        assertProvenanceConflict();
    }

    @Test
    void releaseReversalRejectsSourceAccountMismatch() {
        FrozenReleaseItemRow item = releaseItem(21, 7, 101, 100, 0, "CONSUMED", 100);
        item.sourceAccountId = 8L;
        configureReleaseReversal(94, release(803L, 7L, 100L, -100L, null), item);
        assertProvenanceConflict();
    }

    @Test
    void releaseReversalRejectsSourceTypeMismatch() {
        FrozenReleaseItemRow item = releaseItem(21, 7, 101, 100, 0, "CONSUMED", 100);
        item.sourceEntryType = "REVERSAL";
        configureReleaseReversal(95, release(804L, 7L, 100L, -100L, null), item);
        assertProvenanceConflict();
    }

    @Test
    void releaseReversalRejectsOriginalPointsMismatch() {
        FrozenReleaseItemRow item = releaseItem(21, 7, 101, 100, 0, "CONSUMED", 100);
        item.sourceFrozenDelta = 99L;
        configureReleaseReversal(96, release(805L, 7L, 100L, -100L, null), item);
        assertProvenanceConflict();
    }

    @Test
    void releaseReversalRejectsSourceOrderOrRuleMismatch() {
        FrozenReleaseItemRow item = releaseItem(21, 7, 101, 100, 0, "CONSUMED", 100);
        item.batchSourceOrderId = 999L;
        item.batchRuleVersionId = 999L;
        configureReleaseReversal(97, release(806L, 7L, 100L, -100L, null), item);
        assertProvenanceConflict();
    }

    @Test
    void releaseReversalRejectsAvailableFrozenMismatchAndLongMinValue() {
        configureReleaseReversal(98, release(807L, 7L, 90L, -100L, null), null);
        assertProvenanceConflict();

        configureReleaseReversal(99, release(808L, 7L, Long.MIN_VALUE, Long.MIN_VALUE, null), null);
        assertProvenanceConflict();
    }

    @Test
    void releaseReversalRejectsExplicitSourceThatDiffersFromTheItem() {
        FrozenReleaseItemRow item = releaseItem(21, 7, 101, 100, 0, "CONSUMED", 100);
        configureReleaseReversal(100, release(809L, 7L, 100L, -100L, 102L), item);
        assertProvenanceConflict();
    }

    @Test
    void releaseAfterSaleWritesZeroMarkerWhenSourceBatchWasAlreadyReversed() {
        when(mapper.lockNextOutbox()).thenReturn(event("late-after-sale-event", "90", "AFTERSALE_COMPLETED"));
        when(mapper.completedAfterSaleOrderId(90)).thenReturn(900L);
        ReversibleLedgerRow release = new ReversibleLedgerRow();
        release.id = 701L;
        release.accountId = 7L;
        release.entryType = "FROZEN_POINTS_RELEASED";
        release.availableDelta = 160L;
        release.frozenDelta = -160L;
        release.ruleVersionId = 33L;
        release.originalEntryId = 101L;
        when(mapper.reversibleEntries(900)).thenReturn(List.of(release));
        when(mapper.lockLedgerById(7)).thenReturn(account(7, 0, 0));
        when(mapper.activeFrozenBatchBalance(7)).thenReturn(0L);
        when(mapper.lockFrozenReleaseItems(701)).thenReturn(List.of(
                releaseItem(21, 7, 101, 160, 0, "REVERSED", 160)
        ));
        when(mapper.insertLedgerEntry(
                anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyLong(),
                nullable(Long.class), nullable(Long.class), nullable(Long.class), anyString()
        )).thenReturn(1);
        when(mapper.updateLedger(7, 0, 0)).thenReturn(1);

        assertThat(processor.processNext()).isTrue();

        verify(mapper, never()).restoreFrozenBatchById(anyLong(), anyLong(), anyLong(), anyLong());
        verify(mapper).insertLedgerEntry(
                7, "REVERSAL", 0, 0, "AFTERSALE", 90,
                900L, 33L, 701L, "aftersale-reversal:90:701"
        );
    }

    private void configureReleaseReversal(long afterSaleId, ReversibleLedgerRow release,
                                          FrozenReleaseItemRow item) {
        when(mapper.lockNextOutbox()).thenReturn(
                event("release-validation-" + afterSaleId, Long.toString(afterSaleId), "AFTERSALE_COMPLETED")
        );
        when(mapper.completedAfterSaleOrderId(afterSaleId)).thenReturn(900L);
        when(mapper.reversibleEntries(900)).thenReturn(List.of(release));
        when(mapper.lockLedgerById(release.accountId)).thenReturn(account(release.accountId, 200, 0));
        when(mapper.activeFrozenBatchBalance(release.accountId)).thenReturn(0L);
        if (item != null) {
            when(mapper.lockFrozenReleaseItems(release.id)).thenReturn(List.of(item));
        }
    }

    private void assertProvenanceConflict() {
        assertThatThrownBy(() -> processor.processNext())
                .isInstanceOf(OutboxProjectionFailure.class)
                .hasRootCauseMessage("B 池冻结批次与账户余额不一致");
        verify(mapper, never()).insertLedgerEntry(
                anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyLong(),
                nullable(Long.class), nullable(Long.class), nullable(Long.class), anyString()
        );
        verify(mapper, never()).updateLedger(anyLong(), anyLong(), anyLong());
    }

    private static OutboxRow event(String eventId, String aggregateId, String type) {
        OutboxRow event = new OutboxRow();
        event.id = 1L;
        event.eventId = eventId;
        event.aggregateId = aggregateId;
        event.eventType = type;
        event.attemptCount = 0;
        return event;
    }

    private static ProjectionOrderRow order(long orderId, long buyerId, boolean upgrade,
                                            boolean repurchase, long amountFen) {
        ProjectionOrderRow order = new ProjectionOrderRow();
        order.orderId = orderId;
        order.buyerUserId = buyerId;
        order.superiorUserId = 42L;
        order.totalAmountFen = amountFen;
        order.hasUpgrade = upgrade;
        order.hasRepurchase = repurchase;
        return order;
    }

    private static ReleaseRuleRow releaseRule(long id, long minimumAmountFen, long points) {
        ReleaseRuleRow rule = new ReleaseRuleRow();
        rule.id = id;
        rule.minimumAmountFen = minimumAmountFen;
        rule.releasePoints = points;
        return rule;
    }

    private static ReversibleLedgerRow release(long id, long accountId, long availableDelta,
                                                long frozenDelta, Long originalEntryId) {
        ReversibleLedgerRow release = new ReversibleLedgerRow();
        release.id = id;
        release.accountId = accountId;
        release.entryType = "FROZEN_POINTS_RELEASED";
        release.availableDelta = availableDelta;
        release.frozenDelta = frozenDelta;
        release.ruleVersionId = 33L;
        release.originalEntryId = originalEntryId;
        return release;
    }

    private static LedgerAccountRow account(long id, long available, long frozen) {
        LedgerAccountRow account = new LedgerAccountRow();
        account.id = id;
        account.availablePoints = available;
        account.frozenPoints = frozen;
        account.version = 0;
        return account;
    }

    private static FrozenBatchRow batch(long id, long sourceEntryId, long remaining) {
        return batch(id, 7, sourceEntryId, remaining, remaining, "ACTIVE");
    }

    private static FrozenBatchRow batch(long id, long accountId, long sourceEntryId,
                                        long originalPoints, long remaining, String status) {
        FrozenBatchRow batch = new FrozenBatchRow();
        batch.id = id;
        batch.accountId = accountId;
        batch.sourceLedgerEntryId = sourceEntryId;
        batch.sourceOrderId = sourceEntryId;
        batch.ruleVersionId = 32L;
        batch.originalPoints = originalPoints;
        batch.remainingPoints = remaining;
        batch.status = status;
        batch.sourceEntryType = "DIRECT_REFERRAL_AWARD";
        batch.sourceAccountId = accountId;
        batch.sourceOrderIdFromLedger = sourceEntryId;
        batch.sourceRuleVersionId = 32L;
        batch.sourceFrozenDelta = originalPoints;
        return batch;
    }

    private static FrozenReleaseItemRow releaseItem(long batchId, long accountId,
                                                     long sourceEntryId, long originalPoints,
                                                     long remainingPoints, String status, long points) {
        FrozenReleaseItemRow item = new FrozenReleaseItemRow();
        item.batchId = batchId;
        item.accountId = accountId;
        item.sourceLedgerEntryId = sourceEntryId;
        item.originalPoints = originalPoints;
        item.remainingPoints = remainingPoints;
        item.status = status;
        item.sourceAccountId = accountId;
        item.sourceEntryType = "DIRECT_REFERRAL_AWARD";
        item.sourceOrderId = sourceEntryId;
        item.sourceRuleVersionId = 32L;
        item.sourceFrozenDelta = originalPoints;
        item.batchSourceOrderId = sourceEntryId;
        item.batchRuleVersionId = 32L;
        item.points = points;
        return item;
    }
}

package com.marketshop.infrastructure.persistence.mapper;

import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.DirectMemberRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InactiveMemberRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InvitationEligibilityRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InvitationRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.LedgerAccountRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.LedgerDetailRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.LedgerEntryRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.LevelChangeRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.MemberAdminRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.MemberLevelRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.MembershipProfileRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.OutboxRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.ProjectionOrderRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.ReversibleLedgerRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.RuleRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.EvidenceRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.FrozenBatchRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.FrozenReleaseItemRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface DistributionMapper {

    @Select("""
            SELECT id, event_id, aggregate_id, event_type, attempt_count
            FROM sys_outbox_event
            WHERE status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(3)
            ORDER BY id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    OutboxRow lockNextOutbox();

    @Select("""
            SELECT COUNT(*)
            FROM sys_inbox_event
            WHERE consumer_name = #{consumer} AND event_id = #{eventId}
            """)
    int inboxExists(@Param("consumer") String consumer, @Param("eventId") String eventId);

    @Insert("""
            INSERT INTO sys_inbox_event (consumer_name, event_id, processed_at, result)
            VALUES (#{consumer}, #{eventId}, CURRENT_TIMESTAMP(3), 'SUCCESS')
            """)
    int insertInbox(@Param("consumer") String consumer, @Param("eventId") String eventId);

    @Update("""
            UPDATE sys_outbox_event
            SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(3), last_error = NULL
            WHERE id = #{id}
            """)
    int markOutboxPublished(@Param("id") long id);

    @Select("""
            SELECT o.id AS order_id, o.buyer_user_id, o.superior_user_id, o.total_amount_fen,
                   MAX(oi.sales_scene = 'UPGRADE') AS has_upgrade,
                   MAX(oi.sales_scene = 'REPURCHASE') AS has_repurchase
            FROM trade_order o
            JOIN trade_order_item oi ON oi.order_id = o.id
            WHERE o.id = #{orderId} AND o.status = 'COMPLETED'
            GROUP BY o.id, o.buyer_user_id, o.superior_user_id, o.total_amount_fen
            """)
    ProjectionOrderRow projectionOrder(@Param("orderId") long orderId);

    @Select("""
            SELECT r.id, r.rule_code, r.version_no, r.rule_type,
                   CAST(r.parameters_json AS CHAR) AS parameters_json,
                   r.status, r.effective_from, r.effective_to
            FROM operation_rule_version r
            JOIN trade_order_rule_snapshot snapshot
              ON snapshot.rule_version_id = r.id AND snapshot.order_id = #{orderId}
            WHERE r.rule_code IN ('EXPERIENCE_OFFICER_UPGRADE', 'SUPER_MEMBER_UPGRADE')
            ORDER BY r.version_no
            """)
    List<RuleRow> snapshottedSelfRuleVersions(@Param("orderId") long orderId);

    @Select("""
            SELECT r.id, r.rule_code, r.version_no, r.rule_type,
                   CAST(r.parameters_json AS CHAR) AS parameters_json,
                   r.status, r.effective_from, r.effective_to
            FROM operation_rule_version r
            WHERE r.rule_code = 'DIVIDEND_MEMBER_QUALIFICATION'
              AND r.status = 'ACTIVE'
              AND r.effective_from <= CURRENT_TIMESTAMP(3)
              AND (r.effective_to IS NULL OR r.effective_to > CURRENT_TIMESTAMP(3))
            ORDER BY r.version_no DESC
            LIMIT 1
            """)
    RuleRow activeDirectRuleVersion();

    @Select("""
            SELECT r.id, r.rule_code, r.version_no, r.rule_type,
                   CAST(r.parameters_json AS CHAR) AS parameters_json,
                   r.status, r.effective_from, r.effective_to
            FROM trade_order_rule_snapshot snapshot
            JOIN operation_rule_version r ON r.id = snapshot.rule_version_id
            WHERE snapshot.order_id = #{orderId}
              AND r.rule_code = 'DIVIDEND_MEMBER_QUALIFICATION'
            LIMIT 1
            """)
    RuleRow snapshottedDirectRuleVersion(@Param("orderId") long orderId);

    @Select("""
            SELECT rule_version.id, rule_version.rule_code, rule_version.version_no, rule_version.rule_type,
                   CAST(rule_version.parameters_json AS CHAR) AS parameters_json,
                   rule_version.status, rule_version.effective_from, rule_version.effective_to
            FROM trade_order_rule_snapshot snapshot
            JOIN operation_rule_version rule_version ON rule_version.id = snapshot.rule_version_id
            WHERE snapshot.order_id = #{orderId}
              AND rule_version.rule_code = 'DIRECT_REFERRAL_POINTS'
            LIMIT 1
            """)
    RuleRow snapshottedPointsRuleVersion(@Param("orderId") long orderId);

    @Select("""
            SELECT rule_version.id, rule_version.rule_code, rule_version.version_no, rule_version.rule_type,
                   CAST(rule_version.parameters_json AS CHAR) AS parameters_json,
                   rule_version.status, rule_version.effective_from, rule_version.effective_to
            FROM trade_order_rule_snapshot snapshot
            JOIN operation_rule_version rule_version ON rule_version.id = snapshot.rule_version_id
            WHERE snapshot.order_id = #{orderId}
              AND rule_version.rule_code = 'REPURCHASE_RELEASE'
            LIMIT 1
            """)
    RuleRow snapshottedReleaseRuleVersion(@Param("orderId") long orderId);

    @Select("""
            SELECT l.id AS level_id, l.code, l.rank_no
            FROM membership_account a
            JOIN membership_level l ON l.id = a.current_level_id
            WHERE a.user_id = #{userId}
            LIMIT 1
            FOR UPDATE
            """)
    MemberLevelRow lockMemberLevel(@Param("userId") long userId);

    @Select("""
            SELECT u.id AS user_id,
                   u.status AS user_status,
                   current_level.status AS level_status,
                   current_level.invitation_enabled
            FROM iam_user_account u
            JOIN membership_account membership ON membership.user_id = u.id
            JOIN membership_level current_level ON current_level.id = membership.current_level_id
            WHERE u.id = #{userId}
            LIMIT 1
            FOR UPDATE
            """)
    InvitationEligibilityRow lockInvitationEligibility(@Param("userId") long userId);

    @Insert("""
            INSERT IGNORE INTO membership_evidence
                (user_id, evidence_type, source_order_id, rule_version_id, value_json, status)
            VALUES
                (#{userId}, 'SELF_ORDER_COMPLETED', #{orderId}, #{ruleId},
                 JSON_OBJECT('amountFen', #{amountFen}, 'targetLevel', #{targetLevel}), 'ACTIVE')
            """)
    int insertSelfEvidence(
            @Param("userId") long userId,
            @Param("orderId") long orderId,
            @Param("ruleId") long ruleId,
            @Param("amountFen") long amountFen,
            @Param("targetLevel") String targetLevel
    );

    @Update("""
            UPDATE membership_account a
            JOIN membership_level current_level ON current_level.id = a.current_level_id
            JOIN membership_level target_level ON target_level.code = #{targetLevel}
            SET a.current_level_id = target_level.id,
                a.qualified_at = CURRENT_TIMESTAMP(3),
                a.last_performance_at = CURRENT_TIMESTAMP(3),
                a.version = a.version + 1
            WHERE a.user_id = #{userId} AND current_level.rank_no < target_level.rank_no
            """)
    int promoteMember(@Param("userId") long userId, @Param("targetLevel") String targetLevel);

    @Update("""
            UPDATE membership_account a
            JOIN membership_level target ON target.code = #{targetLevel} AND target.status = 'ACTIVE'
            SET a.current_level_id = target.id,
                a.qualified_at = CURRENT_TIMESTAMP(3),
                a.version = a.version + 1
            WHERE a.user_id = #{userId}
            """)
    int assignMemberLevel(@Param("userId") long userId, @Param("targetLevel") String targetLevel);

    @Select("""
            SELECT id
            FROM membership_account
            WHERE user_id = #{userId}
            LIMIT 1
            FOR UPDATE
            """)
    Long lockDirectPerformanceOwner(@Param("userId") long userId);

    @Select("""
            SELECT COUNT(DISTINCT d.referred_user_id)
            FROM distribution_direct_performance d
            WHERE d.beneficiary_user_id = #{userId} AND d.status = 'ACTIVE'
            """)
    int activeDirectCount(@Param("userId") long userId);

    @Select("""
            SELECT completed_ordinal + 1
            FROM distribution_direct_performance
            WHERE beneficiary_user_id = #{userId}
            ORDER BY completed_ordinal DESC
            LIMIT 1
            FOR UPDATE
            """)
    Integer nextDirectOrdinal(@Param("userId") long userId);

    @Insert("""
            INSERT INTO distribution_direct_performance
                (beneficiary_user_id, referred_user_id, source_order_id, rule_version_id,
                 completed_ordinal, performance_fen, status)
            SELECT #{beneficiaryUserId}, #{referredUserId}, #{orderId}, #{ruleId},
                   #{ordinal}, #{performanceFen}, 'ACTIVE'
            FROM DUAL
            WHERE NOT EXISTS (
                SELECT 1
                FROM distribution_direct_performance existing
                WHERE existing.beneficiary_user_id = #{beneficiaryUserId}
                  AND existing.source_order_id = #{orderId}
            )
              AND NOT EXISTS (
                SELECT 1
                FROM distribution_direct_performance existing
                WHERE existing.beneficiary_user_id = #{beneficiaryUserId}
                  AND existing.referred_user_id = #{referredUserId}
                  AND existing.status = 'ACTIVE'
            )
            """)
    int insertDirectPerformance(
            @Param("beneficiaryUserId") long beneficiaryUserId,
            @Param("referredUserId") long referredUserId,
            @Param("orderId") long orderId,
            @Param("ruleId") long ruleId,
            @Param("ordinal") int ordinal,
            @Param("performanceFen") long performanceFen
    );

    @Select("""
            SELECT id, available_points, frozen_points, version
            FROM ledger_account
            WHERE user_id = #{userId} AND account_type = 'DEMO_POINTS'
            LIMIT 1
            FOR UPDATE
            """)
    LedgerAccountRow lockLedger(@Param("userId") long userId);

    @Select("""
            SELECT id, available_points, frozen_points, version
            FROM ledger_account
            WHERE id = #{accountId}
            LIMIT 1
            FOR UPDATE
            """)
    LedgerAccountRow lockLedgerById(@Param("accountId") long accountId);

    @Update("""
            UPDATE ledger_account
            SET available_points = available_points + #{availableDelta},
                frozen_points = frozen_points + #{frozenDelta},
                version = version + 1
            WHERE id = #{accountId}
              AND available_points + #{availableDelta} >= 0
              AND frozen_points + #{frozenDelta} >= 0
            """)
    int updateLedger(
            @Param("accountId") long accountId,
            @Param("availableDelta") long availableDelta,
            @Param("frozenDelta") long frozenDelta
    );

    @Insert("""
            INSERT IGNORE INTO ledger_entry
                (account_id, entry_type, available_delta, frozen_delta, source_type, source_id,
                 source_order_id, rule_version_id, original_entry_id, idempotency_key, occurred_at)
            VALUES
                (#{accountId}, #{entryType}, #{availableDelta}, #{frozenDelta}, #{sourceType}, #{sourceId},
                 #{orderId}, #{ruleId}, #{originalEntryId}, #{idempotencyKey}, CURRENT_TIMESTAMP(3))
            """)
    int insertLedgerEntry(
            @Param("accountId") long accountId,
            @Param("entryType") String entryType,
            @Param("availableDelta") long availableDelta,
            @Param("frozenDelta") long frozenDelta,
            @Param("sourceType") String sourceType,
            @Param("sourceId") long sourceId,
            @Param("orderId") Long orderId,
            @Param("ruleId") Long ruleId,
            @Param("originalEntryId") Long originalEntryId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Select("""
            SELECT id
            FROM ledger_entry
            WHERE idempotency_key = #{idempotencyKey}
            LIMIT 1
            """)
    Long ledgerEntryId(@Param("idempotencyKey") String idempotencyKey);

    @Insert("""
            INSERT IGNORE INTO ledger_frozen_batch
                (account_id, source_ledger_entry_id, source_order_id, rule_version_id,
                 original_points, remaining_points, status, created_at)
            SELECT account_id, id, source_order_id, rule_version_id,
                   frozen_delta, frozen_delta, 'ACTIVE', occurred_at
            FROM ledger_entry
            WHERE id = #{sourceLedgerEntryId}
              AND entry_type = 'DIRECT_REFERRAL_AWARD'
              AND frozen_delta > 0
              AND source_order_id IS NOT NULL
              AND rule_version_id IS NOT NULL
            """)
    int insertFrozenBatch(@Param("sourceLedgerEntryId") long sourceLedgerEntryId);

    @Select("""
            SELECT id, account_id, source_ledger_entry_id, source_order_id, rule_version_id,
                   original_points, remaining_points, status, created_at
            FROM ledger_frozen_batch
            WHERE account_id = #{accountId}
              AND status = 'ACTIVE'
              AND remaining_points > 0
            ORDER BY created_at, id
            FOR UPDATE
            """)
    List<FrozenBatchRow> lockFrozenBatches(@Param("accountId") long accountId);

    @Select("""
            SELECT batch.id, batch.account_id, batch.source_ledger_entry_id, batch.source_order_id,
                   batch.rule_version_id, batch.original_points, batch.remaining_points,
                   batch.status, batch.created_at,
                   source_entry.entry_type AS source_entry_type,
                   source_entry.account_id AS source_account_id,
                   source_entry.source_order_id AS source_order_id_from_ledger,
                   source_entry.rule_version_id AS source_rule_version_id,
                   source_entry.frozen_delta AS source_frozen_delta
            FROM ledger_frozen_batch batch
            JOIN ledger_entry source_entry ON source_entry.id = batch.source_ledger_entry_id
            WHERE batch.source_ledger_entry_id = #{sourceLedgerEntryId}
              AND source_entry.entry_type = 'DIRECT_REFERRAL_AWARD'
            LIMIT 1
            FOR UPDATE
            """)
    FrozenBatchRow lockFrozenBatchBySourceEntry(@Param("sourceLedgerEntryId") long sourceLedgerEntryId);

    @Select("""
            SELECT COALESCE(SUM(remaining_points), 0)
            FROM ledger_frozen_batch
            WHERE account_id = #{accountId} AND status = 'ACTIVE'
            """)
    Long activeFrozenBatchBalance(@Param("accountId") long accountId);

    @Update("""
            UPDATE ledger_frozen_batch
            SET remaining_points = remaining_points - #{points},
                status = CASE WHEN remaining_points = #{points} THEN 'CONSUMED' ELSE 'ACTIVE' END
            WHERE id = #{batchId}
              AND status = 'ACTIVE'
              AND remaining_points >= #{points}
            """)
    int consumeFrozenBatch(@Param("batchId") long batchId, @Param("points") long points);

    @Insert("""
            INSERT INTO ledger_frozen_release_item
                (release_ledger_entry_id, frozen_batch_id, points)
            VALUES
                (#{releaseLedgerEntryId}, #{batchId}, #{points})
            """)
    int insertFrozenReleaseItem(@Param("releaseLedgerEntryId") long releaseLedgerEntryId,
                                @Param("batchId") long batchId,
                                @Param("points") long points);

    @Select("""
            SELECT item.frozen_batch_id AS batch_id,
                   batch.account_id,
                   batch.source_ledger_entry_id,
                   batch.original_points,
                   batch.remaining_points,
                   batch.status,
                   source_entry.account_id AS source_account_id,
                   source_entry.entry_type AS source_entry_type,
                   source_entry.source_order_id,
                   source_entry.rule_version_id AS source_rule_version_id,
                   source_entry.frozen_delta AS source_frozen_delta,
                   batch.source_order_id AS batch_source_order_id,
                   batch.rule_version_id AS batch_rule_version_id,
                   item.points
            FROM ledger_frozen_release_item item
            JOIN ledger_frozen_batch batch ON batch.id = item.frozen_batch_id
            JOIN ledger_entry source_entry ON source_entry.id = batch.source_ledger_entry_id
            WHERE item.release_ledger_entry_id = #{releaseLedgerEntryId}
            ORDER BY item.id
            FOR UPDATE
            """)
    List<FrozenReleaseItemRow> lockFrozenReleaseItems(
            @Param("releaseLedgerEntryId") long releaseLedgerEntryId
    );

    @Update("""
            UPDATE ledger_frozen_batch
            SET remaining_points = remaining_points + #{points},
                status = 'ACTIVE'
            WHERE id = #{batchId}
              AND account_id = #{accountId}
              AND source_ledger_entry_id = #{sourceLedgerEntryId}
              AND status IN ('ACTIVE', 'CONSUMED')
              AND remaining_points + #{points} <= original_points
            """)
    int restoreFrozenBatchById(@Param("batchId") long batchId,
                               @Param("accountId") long accountId,
                               @Param("sourceLedgerEntryId") long sourceLedgerEntryId,
                               @Param("points") long points);

    @Update("""
            UPDATE ledger_frozen_batch
            SET remaining_points = 0, status = 'REVERSED'
            WHERE source_ledger_entry_id = #{sourceLedgerEntryId}
              AND status IN ('ACTIVE', 'CONSUMED')
              AND remaining_points = #{remainingPoints}
            """)
    int reverseFrozenBatch(@Param("sourceLedgerEntryId") long sourceLedgerEntryId,
                           @Param("remainingPoints") long remainingPoints);

    @Insert("""
            INSERT IGNORE INTO ledger_frozen_release
                (account_id, source_order_id, rule_version_id, requested_points, status)
            VALUES
                (#{accountId}, #{sourceOrderId}, #{ruleVersionId}, #{requestedPoints}, 'PROCESSING')
            """)
    int insertFrozenRelease(
            @Param("accountId") long accountId,
            @Param("sourceOrderId") long sourceOrderId,
            @Param("ruleVersionId") long ruleVersionId,
            @Param("requestedPoints") long requestedPoints
    );

    @Update("""
            UPDATE ledger_frozen_release
            SET released_points = #{releasedPoints},
                status = 'COMPLETED',
                completed_at = CURRENT_TIMESTAMP(3)
            WHERE source_order_id = #{sourceOrderId}
              AND status = 'PROCESSING'
              AND requested_points = #{releasedPoints}
            """)
    int completeFrozenRelease(@Param("sourceOrderId") long sourceOrderId,
                              @Param("releasedPoints") long releasedPoints);

    @Update("""
            UPDATE ledger_frozen_release
            SET status = 'REVERSED',
                reversed_by_after_sale_id = #{afterSaleId},
                reversed_at = CURRENT_TIMESTAMP(3)
            WHERE source_order_id = #{sourceOrderId}
              AND status = 'COMPLETED'
            """)
    int reverseFrozenRelease(@Param("sourceOrderId") long sourceOrderId,
                             @Param("afterSaleId") long afterSaleId);

    @Select("""
            SELECT u.id AS user_id, u.nickname, u.avatar_url, u.phone_masked, u.phone_verified_at,
                   l.code AS level_code, l.name AS level_name,
                   a.available_points, a.frozen_points,
                   (SELECT COUNT(DISTINCT d.referred_user_id)
                    FROM distribution_direct_performance d
                    WHERE d.beneficiary_user_id = u.id AND d.status = 'ACTIVE') AS qualified_direct_count,
                   l.invitation_enabled
            FROM iam_user_account u
            JOIN membership_account m ON m.user_id = u.id
            JOIN membership_level l ON l.id = m.current_level_id
            JOIN ledger_account a ON a.user_id = u.id AND a.account_type = 'DEMO_POINTS'
            WHERE u.id = #{userId}
            LIMIT 1
            """)
    MembershipProfileRow profile(@Param("userId") long userId);

    @Select("""
            SELECT code, status, use_count, expires_at
            FROM customer_invitation_code
            WHERE inviter_user_id = #{userId} AND status = 'ACTIVE'
              AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(3))
            ORDER BY id DESC
            LIMIT 1
            """)
    InvitationRow invitation(@Param("userId") long userId);

    @Select("""
            SELECT code, status, use_count, expires_at
            FROM customer_invitation_code
            WHERE inviter_user_id = #{userId} AND status = 'ACTIVE'
            ORDER BY id
            FOR UPDATE
            """)
    List<InvitationRow> lockActiveInvitations(@Param("userId") long userId);

    @Insert("""
            INSERT INTO customer_invitation_code (code, inviter_user_id, status, expires_at)
            VALUES (#{code}, #{userId}, 'ACTIVE', #{expiresAt})
            """)
    int insertInvitation(@Param("userId") long userId, @Param("code") String code,
                         @Param("expiresAt") LocalDateTime expiresAt);

    @Update("""
            UPDATE customer_invitation_code
            SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP(3), version = version + 1
            WHERE inviter_user_id = #{userId} AND status = 'ACTIVE'
            """)
    int revokeInvitations(@Param("userId") long userId);

    @Select("""
            SELECT u.id AS user_id, u.public_id, u.nickname, l.name AS level_name,
                   COALESCE(d.completed_ordinal, 0) AS completed_ordinal,
                   COALESCE(d.performance_fen, 0) AS performance_fen,
                   COALESCE(d.status, 'UNQUALIFIED') AS performance_status
            FROM customer_relation r
            JOIN iam_user_account u ON u.id = r.member_user_id
            JOIN membership_account m ON m.user_id = u.id
            JOIN membership_level l ON l.id = m.current_level_id
            LEFT JOIN (
                SELECT ranked.referred_user_id, ranked.completed_ordinal,
                       ranked.performance_fen, ranked.status
                FROM (
                    SELECT d.referred_user_id, d.completed_ordinal,
                           d.performance_fen, d.status,
                           ROW_NUMBER() OVER (
                               PARTITION BY d.referred_user_id
                               ORDER BY CASE WHEN d.status = 'ACTIVE' THEN 0 ELSE 1 END,
                                        d.completed_ordinal DESC, d.id DESC
                           ) AS performance_rank
                    FROM distribution_direct_performance d
                    WHERE d.beneficiary_user_id = #{userId}
                ) ranked
                WHERE ranked.performance_rank = 1
            ) d ON d.referred_user_id = u.id
            WHERE r.superior_user_id = #{userId}
            ORDER BY r.bound_at, u.id
            """)
    List<DirectMemberRow> directMembers(@Param("userId") long userId);

    @Select("""
            SELECT e.id, e.entry_type, e.available_delta, e.frozen_delta,
                   e.source_type, e.source_id, e.source_order_id, e.rule_version_id,
                   e.original_entry_id, batch.id AS frozen_batch_id,
                   batch.original_points AS frozen_batch_original_points,
                   batch.remaining_points AS frozen_batch_remaining_points,
                   batch.status AS frozen_batch_status, e.occurred_at
            FROM ledger_entry e
            JOIN ledger_account a ON a.id = e.account_id
            LEFT JOIN ledger_frozen_batch batch
              ON batch.source_ledger_entry_id = CASE
                  WHEN e.entry_type = 'FROZEN_POINTS_RELEASED' THEN e.original_entry_id
                  ELSE e.id
              END
            WHERE a.user_id = #{userId}
            ORDER BY e.occurred_at DESC, e.id DESC
            LIMIT 500
            """)
    List<LedgerEntryRow> ledger(@Param("userId") long userId);

    @Select("""
            SELECT id, rule_code, version_no, rule_type, CAST(parameters_json AS CHAR) AS parameters_json,
                   status, effective_from, effective_to
            FROM operation_rule_version
            ORDER BY rule_code, version_no DESC
            """)
    List<RuleRow> rules();

    @Select("""
            SELECT COUNT(*)
            FROM membership_level
            WHERE code = #{levelCode} AND status = 'ACTIVE'
            """)
    int activeMembershipLevelExists(@Param("levelCode") String levelCode);

    @Select("""
            SELECT rank_no
            FROM membership_level
            WHERE code = #{levelCode} AND status = 'ACTIVE'
            LIMIT 1
            """)
    Integer activeMembershipLevelRank(@Param("levelCode") String levelCode);

    @Select("""
            SELECT id, rule_code, version_no, rule_type, CAST(parameters_json AS CHAR) AS parameters_json,
                   status, effective_from, effective_to
            FROM operation_rule_version
            WHERE id = #{ruleId}
            LIMIT 1
            """)
    RuleRow rule(@Param("ruleId") long ruleId);

    @Select("""
            SELECT COALESCE(MAX(version_no), 0)
            FROM operation_rule_version
            WHERE rule_code = #{ruleCode}
            FOR UPDATE
            """)
    int maxRuleVersion(@Param("ruleCode") String ruleCode);

    @Update("""
            UPDATE operation_rule_version
            SET effective_to = #{effectiveFrom}
            WHERE rule_code = #{ruleCode} AND status = 'ACTIVE'
              AND effective_from < #{effectiveFrom}
              AND (effective_to IS NULL OR effective_to > #{effectiveFrom})
            """)
    int supersedeRules(@Param("ruleCode") String ruleCode, @Param("effectiveFrom") LocalDateTime effectiveFrom);

    @Update("""
            UPDATE operation_rule_version
            SET status = 'CANCELLED', effective_to = CURRENT_TIMESTAMP(3)
            WHERE id = #{ruleId} AND effective_from > CURRENT_TIMESTAMP(3)
              AND status = 'ACTIVE'
            """)
    int cancelFutureRule(@Param("ruleId") long ruleId);

    @Insert("""
            INSERT INTO operation_rule_version
                (rule_code, version_no, rule_type, parameters_json, status, effective_from, published_by_admin_id)
            VALUES
                (#{ruleCode}, #{version}, #{ruleType}, CAST(#{parametersJson} AS JSON),
                 'ACTIVE', #{effectiveFrom}, #{adminId})
            """)
    int insertRule(
            @Param("ruleCode") String ruleCode,
            @Param("version") int version,
            @Param("ruleType") String ruleType,
            @Param("parametersJson") String parametersJson,
            @Param("effectiveFrom") LocalDateTime effectiveFrom,
            @Param("adminId") long adminId
    );

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("""
            SELECT e.id, e.account_id, e.entry_type, e.available_delta, e.frozen_delta,
                   e.rule_version_id, e.original_entry_id
            FROM ledger_entry e
            WHERE e.source_order_id = #{orderId}
              AND e.entry_type <> 'REVERSAL'
              AND NOT EXISTS (
                  SELECT 1 FROM ledger_entry reversal WHERE reversal.original_entry_id = e.id
              )
            ORDER BY e.id
            FOR UPDATE
            """)
    List<ReversibleLedgerRow> reversibleEntries(@Param("orderId") long orderId);

    @Update("""
            UPDATE membership_evidence
            SET status = 'INVALID', invalidated_by_after_sale_id = #{afterSaleId},
                invalidated_at = CURRENT_TIMESTAMP(3)
            WHERE source_order_id = #{orderId} AND status = 'ACTIVE'
            """)
    int invalidateEvidence(@Param("orderId") long orderId, @Param("afterSaleId") long afterSaleId);

    @Update("""
            UPDATE distribution_direct_performance
            SET status = 'REVERSED', reversed_by_after_sale_id = #{afterSaleId},
                reversed_at = CURRENT_TIMESTAMP(3)
            WHERE source_order_id = #{orderId} AND status = 'ACTIVE'
            """)
    int reversePerformance(@Param("orderId") long orderId, @Param("afterSaleId") long afterSaleId);

    @Select("SELECT order_id FROM trade_after_sale WHERE id = #{afterSaleId} AND status = 'COMPLETED'")
    Long completedAfterSaleOrderId(@Param("afterSaleId") long afterSaleId);

    @Select("""
            SELECT COUNT(*)
            FROM trade_after_sale
            WHERE order_id = #{orderId}
              AND status = 'COMPLETED'
            """)
    int countCompletedAfterSales(@Param("orderId") long orderId);

    @Update("""
            UPDATE membership_account
            SET current_level_id = 1, qualified_at = CURRENT_TIMESTAMP(3), version = version + 1
            WHERE user_id = #{userId}
            """)
    int resetMemberToBasic(@Param("userId") long userId);

    @Select("""
            SELECT JSON_UNQUOTE(JSON_EXTRACT(e.value_json, '$.targetLevel'))
            FROM membership_evidence e
            JOIN membership_level l
              ON l.code = JSON_UNQUOTE(JSON_EXTRACT(e.value_json, '$.targetLevel'))
            WHERE e.user_id = #{userId} AND e.status = 'ACTIVE'
            ORDER BY l.rank_no DESC
            LIMIT 1
            """)
    String highestEvidenceLevel(@Param("userId") long userId);

    @Select("SELECT buyer_user_id FROM trade_order WHERE id = #{orderId}")
    Long orderBuyer(@Param("orderId") long orderId);

    @Select("SELECT superior_user_id FROM trade_order WHERE id = #{orderId}")
    Long orderSuperior(@Param("orderId") long orderId);

    @Update("""
            UPDATE membership_account a
            JOIN membership_level current_level ON current_level.id = a.current_level_id
            JOIN membership_level target_level ON target_level.code = 'SUPER_MEMBER'
            SET a.current_level_id = target_level.id,
                a.qualified_at = CURRENT_TIMESTAMP(3),
                a.version = a.version + 1
            WHERE a.user_id = #{userId} AND current_level.code = 'DIVIDEND_MEMBER'
            """)
    int downgradeDividendToSuper(@Param("userId") long userId);

    @Select("""
            SELECT id, rule_code, version_no, rule_type,
                   CAST(parameters_json AS CHAR) AS parameters_json,
                   status, effective_from, effective_to
            FROM operation_rule_version
            WHERE rule_code = 'DIVIDEND_INACTIVITY_DOWNGRADE'
              AND status = 'ACTIVE'
              AND effective_from <= CURRENT_TIMESTAMP(3)
              AND (effective_to IS NULL OR effective_to > CURRENT_TIMESTAMP(3))
            ORDER BY version_no DESC
            LIMIT 1
            """)
    RuleRow activeInactivityRuleVersion();

    @Select("""
            SELECT STRAIGHT_JOIN membership.user_id, current_level.code AS before_level,
                   target_level.code AS target_level,
                   COALESCE(membership.last_performance_at, membership.qualified_at, membership.created_at)
                       AS performance_reference
            FROM iam_user_account user_account
            JOIN membership_account membership ON membership.user_id = user_account.id
            JOIN membership_level current_level ON current_level.id = membership.current_level_id
            JOIN membership_level target_level ON target_level.code = #{targetLevel}
            WHERE current_level.code = #{sourceLevel}
              AND COALESCE(membership.last_performance_at, membership.qualified_at, membership.created_at) < #{cutoff}
            ORDER BY COALESCE(membership.last_performance_at, membership.qualified_at, membership.created_at),
                     membership.user_id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    InactiveMemberRow lockInactiveMember(@Param("sourceLevel") String sourceLevel,
                                         @Param("targetLevel") String targetLevel,
                                         @Param("cutoff") LocalDateTime cutoff);

    @Update("""
            UPDATE membership_account a
            JOIN membership_level current_level ON current_level.id = a.current_level_id
            JOIN membership_level target_level ON target_level.code = #{targetLevel}
            SET a.current_level_id = target_level.id,
                a.qualified_at = CURRENT_TIMESTAMP(3),
                a.version = a.version + 1
            WHERE a.user_id = #{userId} AND current_level.code = #{sourceLevel}
              AND target_level.rank_no < current_level.rank_no
            """)
    int downgradeInactiveMember(@Param("userId") long userId,
                                @Param("sourceLevel") String sourceLevel,
                                @Param("targetLevel") String targetLevel);

    @Update("""
            UPDATE membership_account
            SET last_performance_at = CURRENT_TIMESTAMP(3), version = version + 1
            WHERE user_id = #{userId}
            """)
    int touchPerformance(@Param("userId") long userId);

    @Insert("""
            INSERT IGNORE INTO membership_level_change
                (user_id, before_level_code, after_level_code, trigger_type, trigger_id,
                 rule_version_id, actor_type, actor_id, reason, idempotency_key, occurred_at)
            VALUES
                (#{userId}, #{beforeLevel}, #{afterLevel}, #{triggerType}, #{triggerId},
                 #{ruleId}, #{actorType}, #{actorId}, #{reason}, #{idempotencyKey}, CURRENT_TIMESTAMP(3))
            """)
    int insertLevelChange(@Param("userId") long userId,
                          @Param("beforeLevel") String beforeLevel,
                          @Param("afterLevel") String afterLevel,
                          @Param("triggerType") String triggerType,
                          @Param("triggerId") String triggerId,
                          @Param("ruleId") Long ruleId,
                          @Param("actorType") String actorType,
                          @Param("actorId") String actorId,
                          @Param("reason") String reason,
                          @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            <script>
            SELECT u.id AS user_id, u.public_id, u.nickname, u.avatar_url,
                   u.phone_masked, u.phone_verified_at, u.status,
                   l.code AS level_code, l.name AS level_name, r.superior_user_id,
                   (SELECT COUNT(*) FROM customer_relation direct WHERE direct.superior_user_id = u.id)
                       AS direct_count,
                   (SELECT COUNT(DISTINCT d.referred_user_id)
                    FROM distribution_direct_performance d
                    WHERE d.beneficiary_user_id = u.id AND d.status = 'ACTIVE') AS qualified_direct_count,
                   points.available_points, points.frozen_points, u.created_at
            FROM iam_user_account u
            JOIN membership_account a ON a.user_id = u.id
            JOIN membership_level l ON l.id = a.current_level_id
            LEFT JOIN customer_relation r ON r.member_user_id = u.id
            JOIN ledger_account points ON points.user_id = u.id AND points.account_type = 'DEMO_POINTS'
            <where>
                <if test="keyword != null">
                    AND (u.public_id LIKE CONCAT('%', #{keyword}, '%')
                         OR u.nickname LIKE CONCAT('%', #{keyword}, '%')
                         OR u.phone_masked LIKE CONCAT('%', #{keyword}, '%')
                         OR CAST(u.id AS CHAR) = #{keyword})
                </if>
                <if test="levelCode != null">AND l.code = #{levelCode}</if>
                <if test="status != null">AND u.status = #{status}</if>
            </where>
            ORDER BY u.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<MemberAdminRow> adminMembers(@Param("keyword") String keyword,
                                      @Param("levelCode") String levelCode,
                                      @Param("status") String status,
                                      @Param("offset") int offset,
                                      @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM iam_user_account u
            JOIN membership_account a ON a.user_id = u.id
            JOIN membership_level l ON l.id = a.current_level_id
            <where>
                <if test="keyword != null">
                    AND (u.public_id LIKE CONCAT('%', #{keyword}, '%')
                         OR u.nickname LIKE CONCAT('%', #{keyword}, '%')
                         OR u.phone_masked LIKE CONCAT('%', #{keyword}, '%')
                         OR CAST(u.id AS CHAR) = #{keyword})
                </if>
                <if test="levelCode != null">AND l.code = #{levelCode}</if>
                <if test="status != null">AND u.status = #{status}</if>
            </where>
            </script>
            """)
    long countAdminMembers(@Param("keyword") String keyword,
                           @Param("levelCode") String levelCode,
                           @Param("status") String status);

    @Select("""
            SELECT u.id AS user_id, u.public_id, u.nickname, u.avatar_url,
                   u.phone_masked, u.phone_verified_at, u.status,
                   l.code AS level_code, l.name AS level_name, r.superior_user_id,
                   (SELECT COUNT(*) FROM customer_relation direct WHERE direct.superior_user_id = u.id)
                       AS direct_count,
                   (SELECT COUNT(DISTINCT d.referred_user_id)
                    FROM distribution_direct_performance d
                    WHERE d.beneficiary_user_id = u.id AND d.status = 'ACTIVE') AS qualified_direct_count,
                   points.available_points, points.frozen_points, u.created_at
            FROM iam_user_account u
            JOIN membership_account a ON a.user_id = u.id
            JOIN membership_level l ON l.id = a.current_level_id
            LEFT JOIN customer_relation r ON r.member_user_id = u.id
            JOIN ledger_account points ON points.user_id = u.id AND points.account_type = 'DEMO_POINTS'
            WHERE u.id = #{userId}
            """)
    MemberAdminRow adminMember(@Param("userId") long userId);

    @Select("""
            SELECT id, evidence_type, source_order_id, rule_version_id,
                   CAST(value_json AS CHAR) AS value_json, status, created_at, invalidated_at
            FROM membership_evidence
            WHERE user_id = #{userId}
            ORDER BY id DESC
            LIMIT 500
            """)
    List<EvidenceRow> memberEvidence(@Param("userId") long userId);

    @Select("""
            SELECT id, before_level_code, after_level_code, trigger_type, trigger_id,
                   rule_version_id, actor_type, actor_id, reason, occurred_at
            FROM membership_level_change
            WHERE user_id = #{userId}
            ORDER BY id DESC
            LIMIT 500
            """)
    List<LevelChangeRow> memberLevelChanges(@Param("userId") long userId);

    @Select("""
            SELECT e.id, e.entry_type, e.available_delta, e.frozen_delta,
                   e.source_type, e.source_id, e.source_order_id, e.rule_version_id,
                   e.original_entry_id, batch.id AS frozen_batch_id,
                   batch.original_points AS frozen_batch_original_points,
                   batch.remaining_points AS frozen_batch_remaining_points,
                   batch.status AS frozen_batch_status, e.occurred_at
            FROM ledger_entry e
            JOIN ledger_account a ON a.id = e.account_id
            LEFT JOIN ledger_frozen_batch batch
              ON batch.source_ledger_entry_id = CASE
                  WHEN e.entry_type = 'FROZEN_POINTS_RELEASED' THEN e.original_entry_id
                  ELSE e.id
              END
            WHERE a.user_id = #{userId}
            ORDER BY e.id DESC
            LIMIT 500
            """)
    List<LedgerDetailRow> memberLedgerDetail(@Param("userId") long userId);

    @Select("SELECT status FROM iam_user_account WHERE id = #{userId}")
    String memberStatus(@Param("userId") long userId);

    @Update("""
            UPDATE iam_user_account
            SET status = #{status}, auth_epoch = auth_epoch + 1, version = version + 1
            WHERE id = #{userId}
            """)
    int updateMemberStatus(@Param("userId") long userId, @Param("status") String status);
}

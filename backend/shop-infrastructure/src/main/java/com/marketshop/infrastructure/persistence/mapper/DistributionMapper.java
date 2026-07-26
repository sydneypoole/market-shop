package com.marketshop.infrastructure.persistence.mapper;

import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.DirectMemberRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.DirectRuleRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InvitationRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InactiveMemberRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InactivityRuleRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.LedgerAccountRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.LedgerDetailRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.LedgerEntryRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.LevelChangeRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.MemberAdminRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.MemberLevelRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.MembershipProfileRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.OutboxRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.PointsRuleRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.ProjectionOrderRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.ReleaseRuleRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.ReversibleLedgerRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.RuleRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.SelfRuleRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.EvidenceRow;
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

    @Update("""
            UPDATE sys_outbox_event
            SET attempt_count = attempt_count + 1,
                next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND),
                last_error = #{error}
            WHERE id = #{id}
            """)
    int rescheduleOutbox(@Param("id") long id, @Param("error") String error);

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
            SELECT r.id,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(r.parameters_json, '$.minimumCompletedOrderAmountFen')) AS UNSIGNED)
                       AS minimum_amount_fen,
                   JSON_UNQUOTE(JSON_EXTRACT(r.parameters_json, '$.targetLevel')) AS target_level,
                   l.rank_no AS target_rank
            FROM operation_rule_version r
            JOIN membership_level l
              ON l.code = JSON_UNQUOTE(JSON_EXTRACT(r.parameters_json, '$.targetLevel'))
            WHERE r.rule_type = 'SELF_ORDER_TASK' AND r.status = 'ACTIVE'
              AND r.effective_from <= CURRENT_TIMESTAMP(3)
              AND (r.effective_to IS NULL OR r.effective_to > CURRENT_TIMESTAMP(3))
            ORDER BY l.rank_no
            """)
    List<SelfRuleRow> activeSelfRules();

    @Select("""
            SELECT r.id,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(r.parameters_json, '$.requiredCompletedDirectReferrals')) AS UNSIGNED)
                       AS required_count,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(r.parameters_json, '$.minimumReferralOrderAmountFen')) AS UNSIGNED)
                       AS minimum_amount_fen,
                   JSON_UNQUOTE(JSON_EXTRACT(r.parameters_json, '$.requiredReferralLevel')) AS required_level,
                   required.rank_no AS required_rank,
                   JSON_UNQUOTE(JSON_EXTRACT(r.parameters_json, '$.targetLevel')) AS target_level
            FROM operation_rule_version r
            JOIN membership_level required
              ON required.code = JSON_UNQUOTE(JSON_EXTRACT(r.parameters_json, '$.requiredReferralLevel'))
            WHERE r.rule_code = 'DIVIDEND_MEMBER_QUALIFICATION' AND r.status = 'ACTIVE'
              AND r.effective_from <= CURRENT_TIMESTAMP(3)
              AND (r.effective_to IS NULL OR r.effective_to > CURRENT_TIMESTAMP(3))
            ORDER BY r.version_no DESC
            LIMIT 1
            """)
    DirectRuleRow activeDirectRule();

    @Select("""
            SELECT id,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.pointsStartOrdinal')) AS UNSIGNED)
                       AS points_start_ordinal,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.availableAPoints')) AS UNSIGNED)
                       AS available_points,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.frozenBPoints')) AS UNSIGNED)
                       AS frozen_points
            FROM operation_rule_version
            WHERE rule_code = 'DIRECT_REFERRAL_POINTS' AND status = 'ACTIVE'
              AND effective_from <= CURRENT_TIMESTAMP(3)
              AND (effective_to IS NULL OR effective_to > CURRENT_TIMESTAMP(3))
            ORDER BY version_no DESC
            LIMIT 1
            """)
    PointsRuleRow activePointsRule();

    @Select("""
            SELECT id,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.minimumCompletedOrderAmountFen')) AS UNSIGNED)
                       AS minimum_amount_fen,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.releasePointsPerOrder')) AS UNSIGNED)
                       AS release_points
            FROM operation_rule_version
            WHERE rule_code = 'REPURCHASE_RELEASE' AND status = 'ACTIVE'
              AND effective_from <= CURRENT_TIMESTAMP(3)
              AND (effective_to IS NULL OR effective_to > CURRENT_TIMESTAMP(3))
            ORDER BY version_no DESC
            LIMIT 1
            """)
    ReleaseRuleRow activeReleaseRule();

    @Select("""
            SELECT l.id AS level_id, l.code, l.rank_no
            FROM membership_account a
            JOIN membership_level l ON l.id = a.current_level_id
            WHERE a.user_id = #{userId}
            LIMIT 1
            FOR UPDATE
            """)
    MemberLevelRow lockMemberLevel(@Param("userId") long userId);

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

    @Select("""
            SELECT COUNT(*)
            FROM distribution_direct_performance
            WHERE beneficiary_user_id = #{userId} AND status = 'ACTIVE'
            """)
    int activeDirectCount(@Param("userId") long userId);

    @Insert("""
            INSERT IGNORE INTO distribution_direct_performance
                (beneficiary_user_id, referred_user_id, source_order_id, rule_version_id,
                 completed_ordinal, performance_fen, status)
            VALUES
                (#{beneficiaryUserId}, #{referredUserId}, #{orderId}, #{ruleId},
                 #{ordinal}, #{performanceFen}, 'ACTIVE')
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
            SELECT u.id AS user_id, u.nickname, l.code AS level_code, l.name AS level_name,
                   a.available_points, a.frozen_points,
                   (SELECT COUNT(*) FROM distribution_direct_performance d
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
            LEFT JOIN distribution_direct_performance d
              ON d.referred_user_id = u.id AND d.beneficiary_user_id = #{userId}
            WHERE r.superior_user_id = #{userId}
            ORDER BY r.bound_at, u.id
            """)
    List<DirectMemberRow> directMembers(@Param("userId") long userId);

    @Select("""
            SELECT e.id, e.entry_type, e.available_delta, e.frozen_delta,
                   e.source_type, e.source_id, e.occurred_at
            FROM ledger_entry e
            JOIN ledger_account a ON a.id = e.account_id
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
            SELECT e.id, e.account_id, e.available_delta, e.frozen_delta, e.rule_version_id
            FROM ledger_entry e
            WHERE e.source_order_id = #{orderId}
              AND e.original_entry_id IS NULL
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
            SELECT id,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.inactiveMonths')) AS UNSIGNED)
                       AS inactive_months,
                   JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.sourceLevel')) AS source_level,
                   JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.targetLevel')) AS target_level
            FROM operation_rule_version
            WHERE rule_type = 'INACTIVITY_DOWNGRADE' AND status = 'ACTIVE'
              AND effective_from <= CURRENT_TIMESTAMP(3)
              AND (effective_to IS NULL OR effective_to > CURRENT_TIMESTAMP(3))
            ORDER BY version_no DESC
            LIMIT 1
            """)
    InactivityRuleRow activeInactivityRule();

    @Select("""
            SELECT a.user_id, current_level.code AS before_level, target_level.code AS target_level,
                   COALESCE(a.last_performance_at, a.qualified_at, a.created_at) AS performance_reference
            FROM membership_account a
            JOIN membership_level current_level ON current_level.id = a.current_level_id
            JOIN membership_level target_level ON target_level.code = #{targetLevel}
            WHERE current_level.code = #{sourceLevel}
              AND COALESCE(a.last_performance_at, a.qualified_at, a.created_at) < #{cutoff}
            ORDER BY COALESCE(a.last_performance_at, a.qualified_at, a.created_at), a.user_id
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
            SELECT u.id AS user_id, u.public_id, u.nickname, u.status,
                   l.code AS level_code, l.name AS level_name, r.superior_user_id,
                   (SELECT COUNT(*) FROM customer_relation direct WHERE direct.superior_user_id = u.id)
                       AS direct_count,
                   (SELECT COUNT(*) FROM distribution_direct_performance d
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
            SELECT u.id AS user_id, u.public_id, u.nickname, u.status,
                   l.code AS level_code, l.name AS level_name, r.superior_user_id,
                   (SELECT COUNT(*) FROM customer_relation direct WHERE direct.superior_user_id = u.id)
                       AS direct_count,
                   (SELECT COUNT(*) FROM distribution_direct_performance d
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
                   e.source_type, e.source_id, e.source_order_id, e.occurred_at
            FROM ledger_entry e
            JOIN ledger_account a ON a.id = e.account_id
            WHERE a.user_id = #{userId}
            ORDER BY e.id DESC
            LIMIT 500
            """)
    List<LedgerDetailRow> memberLedgerDetail(@Param("userId") long userId);

    @Select("SELECT status FROM iam_user_account WHERE id = #{userId}")
    String memberStatus(@Param("userId") long userId);

    @Update("""
            UPDATE iam_user_account
            SET status = #{status}, version = version + 1
            WHERE id = #{userId}
            """)
    int updateMemberStatus(@Param("userId") long userId, @Param("status") String status);
}

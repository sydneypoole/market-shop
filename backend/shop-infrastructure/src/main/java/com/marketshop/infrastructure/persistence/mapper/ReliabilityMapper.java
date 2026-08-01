package com.marketshop.infrastructure.persistence.mapper;

import com.marketshop.infrastructure.persistence.model.ReliabilityPersistenceModels.DeadLetterRow;
import com.marketshop.infrastructure.persistence.model.ReliabilityPersistenceModels.OutboxSummaryRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface ReliabilityMapper {

    @Update("""
            UPDATE sys_outbox_event
            SET attempt_count = attempt_count + 1,
                status = CASE WHEN #{dead} THEN 'DEAD' ELSE 'PENDING' END,
                next_attempt_at = #{nextAttemptAt},
                last_error = #{lastError},
                dead_at = CASE WHEN #{dead} THEN CURRENT_TIMESTAMP(3) ELSE NULL END
            WHERE id = #{outboxId}
              AND status = 'PENDING'
              AND attempt_count = #{expectedAttemptCount}
            """)
    int recordFailure(
            @Param("outboxId") long outboxId,
            @Param("expectedAttemptCount") int expectedAttemptCount,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("lastError") String lastError,
            @Param("dead") boolean dead
    );

    @Select("""
            SELECT id, event_id, aggregate_type, aggregate_id, event_type,
                   attempt_count, last_error, occurred_at, dead_at,
                   replay_count, last_replayed_at
            FROM sys_outbox_event
            WHERE status = 'DEAD'
            ORDER BY dead_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<DeadLetterRow> deadLetters(@Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM sys_outbox_event WHERE status = 'DEAD'")
    long countDeadLetters();

    @Select("""
            SELECT id, event_id, aggregate_type, aggregate_id, event_type,
                   attempt_count, last_error, occurred_at, dead_at,
                   replay_count, last_replayed_at
            FROM sys_outbox_event
            WHERE id = #{outboxId} AND status = 'DEAD'
            LIMIT 1
            FOR UPDATE
            """)
    DeadLetterRow deadLetter(@Param("outboxId") long outboxId);

    @Update("""
            UPDATE sys_outbox_event
            SET status = 'PENDING',
                attempt_count = 0,
                next_attempt_at = CURRENT_TIMESTAMP(3),
                published_at = NULL,
                last_error = NULL,
                dead_at = NULL,
                replay_count = replay_count + 1,
                last_replayed_at = CURRENT_TIMESTAMP(3),
                last_replayed_by_admin_id = #{adminId}
            WHERE id = #{outboxId} AND status = 'DEAD'
            """)
    int replayDeadLetter(@Param("outboxId") long outboxId, @Param("adminId") long adminId);

    @Select("""
            SELECT
                SUM(status = 'PENDING') AS pending_count,
                SUM(status = 'DEAD') AS dead_count,
                COALESCE(
                    TIMESTAMPDIFF(
                        SECOND,
                        MIN(CASE WHEN status = 'PENDING' THEN created_at END),
                        CURRENT_TIMESTAMP(3)
                    ),
                    0
                ) AS oldest_pending_age_seconds
            FROM sys_outbox_event
            """)
    OutboxSummaryRow outboxSummary();

    @Select("SELECT COUNT(*) FROM sys_outbox_event WHERE status = 'PENDING'")
    long pendingCount();

    @Select("SELECT COUNT(*) FROM sys_outbox_event WHERE status = 'DEAD'")
    long deadCount();

    @Select("""
            SELECT COALESCE(
                TIMESTAMPDIFF(SECOND, MIN(created_at), CURRENT_TIMESTAMP(3)),
                0
            )
            FROM sys_outbox_event
            WHERE status = 'PENDING'
            """)
    long oldestPendingAgeSeconds();
}

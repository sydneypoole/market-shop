package com.marketshop.application.reliability;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxOperationsPort {

    List<DeadLetterRecord> deadLetters(int offset, int limit);

    long countDeadLetters();

    Optional<DeadLetterRecord> deadLetter(long outboxId);

    boolean replayDeadLetter(long outboxId, long adminId);

    OutboxSummaryRecord summary();

    record DeadLetterRecord(
            long id,
            String eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            int attemptCount,
            String lastError,
            Instant occurredAt,
            Instant deadAt,
            int replayCount,
            Instant lastReplayedAt
    ) {
    }

    record OutboxSummaryRecord(long pendingCount, long deadCount, long oldestPendingAgeSeconds) {
    }
}

package com.marketshop.application.reliability;

import java.time.Instant;
import java.util.List;

public interface OutboxOperationsUseCase {

    DeadLetterPage deadLetters(int page, int pageSize);

    OutboxSummaryView summary();

    ReplayResult replay(long adminId, long outboxId, String reason);

    record DeadLetterView(
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

    record DeadLetterPage(List<DeadLetterView> items, long total, int page, int pageSize) {
    }

    record OutboxSummaryView(long pendingCount, long deadCount, long oldestPendingAgeSeconds) {
    }

    record ReplayResult(long outboxId, String eventId, int replayCount, String status) {
    }
}

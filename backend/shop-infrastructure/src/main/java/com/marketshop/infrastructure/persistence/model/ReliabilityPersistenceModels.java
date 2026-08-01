package com.marketshop.infrastructure.persistence.model;

import java.time.LocalDateTime;

public final class ReliabilityPersistenceModels {

    private ReliabilityPersistenceModels() {
    }

    public static class DeadLetterRow {
        public Long id;
        public String eventId;
        public String aggregateType;
        public String aggregateId;
        public String eventType;
        public Integer attemptCount;
        public String lastError;
        public LocalDateTime occurredAt;
        public LocalDateTime deadAt;
        public Integer replayCount;
        public LocalDateTime lastReplayedAt;
    }

    public static class OutboxSummaryRow {
        public Long pendingCount;
        public Long deadCount;
        public Long oldestPendingAgeSeconds;
    }
}

package com.marketshop.infrastructure.reliability;

import com.marketshop.application.reliability.OutboxOperationsPort;
import com.marketshop.infrastructure.persistence.mapper.ReliabilityMapper;
import com.marketshop.infrastructure.persistence.model.ReliabilityPersistenceModels.DeadLetterRow;
import com.marketshop.infrastructure.persistence.model.ReliabilityPersistenceModels.OutboxSummaryRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisOutboxOperationsAdapter implements OutboxOperationsPort {

    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);

    private final ReliabilityMapper mapper;

    public MyBatisOutboxOperationsAdapter(ReliabilityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<DeadLetterRecord> deadLetters(int offset, int limit) {
        return mapper.deadLetters(offset, limit).stream().map(this::record).toList();
    }

    @Override
    public long countDeadLetters() {
        return mapper.countDeadLetters();
    }

    @Override
    public Optional<DeadLetterRecord> deadLetter(long outboxId) {
        return Optional.ofNullable(mapper.deadLetter(outboxId)).map(this::record);
    }

    @Override
    public boolean replayDeadLetter(long outboxId, long adminId) {
        return mapper.replayDeadLetter(outboxId, adminId) == 1;
    }

    @Override
    public OutboxSummaryRecord summary() {
        OutboxSummaryRow row = mapper.outboxSummary();
        if (row == null) return new OutboxSummaryRecord(0, 0, 0);
        return new OutboxSummaryRecord(
                zero(row.pendingCount),
                zero(row.deadCount),
                zero(row.oldestPendingAgeSeconds)
        );
    }

    private DeadLetterRecord record(DeadLetterRow row) {
        return new DeadLetterRecord(
                row.id,
                row.eventId,
                row.aggregateType,
                row.aggregateId,
                row.eventType,
                row.attemptCount == null ? 0 : row.attemptCount,
                row.lastError,
                instant(row.occurredAt),
                instant(row.deadAt),
                row.replayCount == null ? 0 : row.replayCount,
                instant(row.lastReplayedAt)
        );
    }

    private static long zero(Long value) {
        return value == null ? 0 : value;
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(BUSINESS_ZONE);
    }
}

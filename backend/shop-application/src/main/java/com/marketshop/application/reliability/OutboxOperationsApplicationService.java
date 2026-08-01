package com.marketshop.application.reliability;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.audit.AdminAuditPort.AuditRecord;
import com.marketshop.application.reliability.OutboxOperationsPort.DeadLetterRecord;
import com.marketshop.application.reliability.OutboxOperationsPort.OutboxSummaryRecord;
import com.marketshop.domain.shared.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class OutboxOperationsApplicationService implements OutboxOperationsUseCase {

    private final OutboxOperationsPort outbox;
    private final AdminAuditPort audit;

    public OutboxOperationsApplicationService(OutboxOperationsPort outbox, AdminAuditPort audit) {
        this.outbox = outbox;
        this.audit = audit;
    }

    @Override
    public DeadLetterPage deadLetters(int page, int pageSize) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.min(Math.max(pageSize, 1), 100);
        int offset = (normalizedPage - 1) * normalizedSize;
        return new DeadLetterPage(
                outbox.deadLetters(offset, normalizedSize).stream().map(this::view).toList(),
                outbox.countDeadLetters(),
                normalizedPage,
                normalizedSize
        );
    }

    @Override
    public OutboxSummaryView summary() {
        OutboxSummaryRecord value = outbox.summary();
        return new OutboxSummaryView(
                value.pendingCount(),
                value.deadCount(),
                Math.max(value.oldestPendingAgeSeconds(), 0)
        );
    }

    @Override
    @Transactional
    public ReplayResult replay(long adminId, long outboxId, String reason) {
        String normalizedReason = requiredReason(reason);
        DeadLetterRecord before = outbox.deadLetter(outboxId)
                .orElseThrow(() -> new DomainException(
                        "OUTBOX_DEAD_LETTER_NOT_FOUND",
                        "Outbox 死信不存在或已被重放"
                ));
        if (!outbox.replayDeadLetter(outboxId, adminId)) {
            throw new DomainException("OUTBOX_REPLAY_CONFLICT", "Outbox 死信状态已变更，请刷新后重试");
        }
        int replayCount = before.replayCount() + 1;
        audit.record(new AuditRecord(
                "ADMIN",
                Long.toString(adminId),
                "OUTBOX_DEAD_LETTER_REPLAYED",
                "OUTBOX_EVENT",
                Long.toString(outboxId),
                stateJson("DEAD", before.attemptCount(), before.replayCount()),
                stateJson("PENDING", 0, replayCount),
                normalizedReason,
                UUID.randomUUID().toString(),
                null,
                "application-service",
                Instant.now()
        ));
        return new ReplayResult(outboxId, before.eventId(), replayCount, "PENDING");
    }

    private DeadLetterView view(DeadLetterRecord value) {
        return new DeadLetterView(
                value.id(),
                value.eventId(),
                value.aggregateType(),
                value.aggregateId(),
                value.eventType(),
                value.attemptCount(),
                value.lastError(),
                value.occurredAt(),
                value.deadAt(),
                value.replayCount(),
                value.lastReplayedAt()
        );
    }

    private static String requiredReason(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 500) {
            throw new DomainException("OUTBOX_REPLAY_REASON_INVALID", "重放原因不能为空且长度不能超过 500");
        }
        return value.trim();
    }

    private static String stateJson(String status, int attemptCount, int replayCount) {
        return "{\"status\":\"" + status + "\",\"attemptCount\":" + attemptCount
                + ",\"replayCount\":" + replayCount + "}";
    }
}

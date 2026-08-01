package com.marketshop.application.reliability;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.audit.AdminAuditPort.AuditRecord;
import com.marketshop.application.reliability.OutboxOperationsPort.DeadLetterRecord;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxOperationsApplicationServiceTest {

    @Mock
    private OutboxOperationsPort outbox;

    @Mock
    private AdminAuditPort audit;

    @InjectMocks
    private OutboxOperationsApplicationService service;

    @Test
    void replayRequiresADeadLetterAndWritesAReasonedAuditRecord() {
        DeadLetterRecord dead = deadLetter();
        when(outbox.deadLetter(41)).thenReturn(Optional.of(dead));
        when(outbox.replayDeadLetter(41, 7)).thenReturn(true);

        OutboxOperationsUseCase.ReplayResult result = service.replay(7, 41, "修复上游数据后人工重放");

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.replayCount()).isEqualTo(3);
        ArgumentCaptor<AuditRecord> auditRecord = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(auditRecord.capture());
        assertThat(auditRecord.getValue().action()).isEqualTo("OUTBOX_DEAD_LETTER_REPLAYED");
        assertThat(auditRecord.getValue().resourceId()).isEqualTo("41");
        assertThat(auditRecord.getValue().reason()).isEqualTo("修复上游数据后人工重放");
        assertThat(auditRecord.getValue().beforeJson()).contains("\"status\":\"DEAD\"");
        assertThat(auditRecord.getValue().afterJson()).contains("\"status\":\"PENDING\"");
    }

    @Test
    void replayRejectsMissingDeadLetterAndBlankReason() {
        assertThatThrownBy(() -> service.replay(7, 41, " "))
                .isInstanceOfSatisfying(DomainException.class,
                        value -> assertThat(value.code()).isEqualTo("OUTBOX_REPLAY_REASON_INVALID"));

        when(outbox.deadLetter(41)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.replay(7, 41, "retry after repair"))
                .isInstanceOfSatisfying(DomainException.class,
                        value -> assertThat(value.code()).isEqualTo("OUTBOX_DEAD_LETTER_NOT_FOUND"));
    }

    @Test
    void replayCompareAndSetConflictDoesNotWriteAFalseAuditRecord() {
        when(outbox.deadLetter(41)).thenReturn(Optional.of(deadLetter()));
        when(outbox.replayDeadLetter(41, 7)).thenReturn(false);

        assertThatThrownBy(() -> service.replay(7, 41, "修复后重放"))
                .isInstanceOfSatisfying(DomainException.class,
                        value -> assertThat(value.code()).isEqualTo("OUTBOX_REPLAY_CONFLICT"));

        verifyNoInteractions(audit);
    }

    private static DeadLetterRecord deadLetter() {
        return new DeadLetterRecord(
                41,
                "event-41",
                "ORDER",
                "900",
                "ORDER_COMPLETED",
                5,
                "POISON_EVENT: invalid fixture event",
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:01:00Z"),
                2,
                Instant.parse("2026-07-31T00:00:00Z")
        );
    }
}

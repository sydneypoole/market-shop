package com.marketshop.infrastructure.reliability;

import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.ReliabilityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxFailureRecorderTest {

    @Mock
    private ReliabilityMapper mapper;

    @Test
    void recordsExponentialBackoffAndMovesTheMaximumAttemptToDead() {
        OutboxFailureRecorder recorder = new OutboxFailureRecorder(mapper, 3, 5, 60);
        when(mapper.recordFailure(eq(41L), eq(0), any(LocalDateTime.class), any(), eq(false))).thenReturn(1);
        when(mapper.recordFailure(eq(41L), eq(2), any(LocalDateTime.class), any(), eq(true))).thenReturn(1);

        OutboxFailureRecorder.FailureRecordResult first = recorder.record(failure(0));
        OutboxFailureRecorder.FailureRecordResult dead = recorder.record(failure(2));

        assertThat(first).isEqualTo(new OutboxFailureRecorder.FailureRecordResult(true, 1, "PENDING", 5));
        assertThat(dead).isEqualTo(new OutboxFailureRecorder.FailureRecordResult(true, 3, "DEAD", 0));
        verify(mapper).recordFailure(eq(41L), eq(0), any(LocalDateTime.class),
                eq("POISON_EVENT: invalid fixture event"), eq(false));
        verify(mapper).recordFailure(eq(41L), eq(2), any(LocalDateTime.class),
                eq("POISON_EVENT: invalid fixture event"), eq(true));
    }

    @Test
    void backoffDoublesAndIsCapped() {
        OutboxFailureRecorder recorder = new OutboxFailureRecorder(mapper, 20, 5, 60);

        assertThat(recorder.backoffSeconds(1)).isEqualTo(5);
        assertThat(recorder.backoffSeconds(2)).isEqualTo(10);
        assertThat(recorder.backoffSeconds(3)).isEqualTo(20);
        assertThat(recorder.backoffSeconds(8)).isEqualTo(60);
    }

    @Test
    void failurePersistenceUsesAnIndependentTransaction() throws Exception {
        Transactional annotation = OutboxFailureRecorder.class
                .getMethod("record", OutboxProjectionFailure.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private static OutboxProjectionFailure failure(int attempt) {
        return new OutboxProjectionFailure(
                41,
                "poison-event",
                attempt,
                new DomainException("POISON_EVENT", "invalid fixture event")
        );
    }
}

package com.marketshop.infrastructure.reliability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxProjectionJobTest {

    @Mock
    private OutboxProjectionProcessor processor;

    @Mock
    private OutboxFailureRecorder failureRecorder;

    @Test
    void poisonedEventIsRecordedAndDoesNotBlockTheFollowingValidEvent() {
        OutboxProjectionFailure poison = new OutboxProjectionFailure(
                1,
                "poison-event",
                4,
                new IllegalArgumentException("fixture")
        );
        when(processor.processNext()).thenThrow(poison).thenReturn(true, false);
        when(failureRecorder.record(poison)).thenReturn(
                new OutboxFailureRecorder.FailureRecordResult(true, 5, "DEAD", 0)
        );

        new OutboxProjectionJob(processor, failureRecorder).project();

        verify(processor, times(3)).processNext();
        verify(failureRecorder).record(poison);
    }
}

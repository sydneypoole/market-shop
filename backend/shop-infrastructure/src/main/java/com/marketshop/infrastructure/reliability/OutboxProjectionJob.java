package com.marketshop.infrastructure.reliability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxProjectionJob {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxProjectionJob.class);
    private static final int BATCH_LIMIT = 20;

    private final OutboxProjectionProcessor processor;
    private final OutboxFailureRecorder failureRecorder;

    public OutboxProjectionJob(OutboxProjectionProcessor processor, OutboxFailureRecorder failureRecorder) {
        this.processor = processor;
        this.failureRecorder = failureRecorder;
    }

    @Scheduled(fixedDelayString = "${market-shop.jobs.outbox-delay-ms:5000}")
    public void project() {
        for (int index = 0; index < BATCH_LIMIT; index++) {
            try {
                if (!processor.processNext()) {
                    return;
                }
            } catch (OutboxProjectionFailure failure) {
                try {
                    OutboxFailureRecorder.FailureRecordResult result = failureRecorder.record(failure);
                    LOG.warn(
                            "Outbox projection failed eventId={} attemptCount={} status={} retryDelaySeconds={} recorded={}",
                            failure.eventId(),
                            result.attemptCount(),
                            result.status(),
                            result.delaySeconds(),
                            result.updated()
                    );
                } catch (RuntimeException recordFailure) {
                    LOG.error("Outbox failure state could not be recorded eventId={}", failure.eventId(), recordFailure);
                    return;
                }
            } catch (RuntimeException exception) {
                LOG.error("Outbox projection worker failed before an event could be isolated", exception);
                return;
            }
        }
    }
}

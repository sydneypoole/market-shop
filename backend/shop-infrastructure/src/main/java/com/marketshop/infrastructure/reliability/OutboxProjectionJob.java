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

    public OutboxProjectionJob(OutboxProjectionProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${market-shop.jobs.outbox-delay-ms:5000}")
    public void project() {
        for (int index = 0; index < BATCH_LIMIT; index++) {
            try {
                if (!processor.processNext()) {
                    return;
                }
            } catch (RuntimeException exception) {
                LOG.warn("Outbox projection failed and will be retried: {}", exception.getMessage());
                return;
            }
        }
    }
}

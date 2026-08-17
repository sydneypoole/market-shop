package com.marketshop.infrastructure.reliability;

import com.marketshop.infrastructure.persistence.mapper.CommerceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AftersaleTimeoutJob {

    private static final Logger LOG = LoggerFactory.getLogger(AftersaleTimeoutJob.class);
    private static final String JOB_NAME = "aftersale-timeout";
    private static final int LEASE_SECONDS = 120;
    private static final int BATCH_LIMIT = 50;

    private final CommerceMapper mapper;
    private final AftersaleTimeoutProcessor processor;
    private final String ownerId = UUID.randomUUID().toString();

    public AftersaleTimeoutJob(CommerceMapper mapper, AftersaleTimeoutProcessor processor) {
        this.mapper = mapper;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${market-shop.jobs.aftersale-timeout-delay-ms:300000}")
    public void timeout() {
        mapper.acquireJobLease(JOB_NAME, ownerId, LEASE_SECONDS);
        if (mapper.ownsJobLease(JOB_NAME, ownerId) != 1) {
            return;
        }
        for (int index = 0; index < BATCH_LIMIT; index++) {
            try {
                if (!processor.processNext()) {
                    return;
                }
            } catch (RuntimeException exception) {
                LOG.warn("Aftersale timeout failed and will be retried: {}", exception.getMessage());
                return;
            }
        }
    }
}

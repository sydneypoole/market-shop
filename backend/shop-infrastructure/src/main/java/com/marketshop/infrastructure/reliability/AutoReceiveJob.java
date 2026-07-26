package com.marketshop.infrastructure.reliability;

import com.marketshop.infrastructure.persistence.mapper.CommerceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AutoReceiveJob {

    private static final Logger LOG = LoggerFactory.getLogger(AutoReceiveJob.class);
    private static final String JOB_NAME = "order-auto-receive";
    private static final int LEASE_SECONDS = 45;
    private static final int BATCH_LIMIT = 100;

    private final CommerceMapper mapper;
    private final AutoReceiveProcessor processor;
    private final String ownerId = UUID.randomUUID().toString();

    public AutoReceiveJob(CommerceMapper mapper, AutoReceiveProcessor processor) {
        this.mapper = mapper;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${market-shop.jobs.auto-receive-delay-ms:60000}")
    public void receive() {
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
                LOG.warn("Auto receive failed and will be retried: {}", exception.getMessage());
                return;
            }
        }
    }
}

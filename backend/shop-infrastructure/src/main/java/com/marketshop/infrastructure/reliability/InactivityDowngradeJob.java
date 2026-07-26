package com.marketshop.infrastructure.reliability;

import com.marketshop.infrastructure.persistence.mapper.CommerceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class InactivityDowngradeJob {

    private static final Logger LOG = LoggerFactory.getLogger(InactivityDowngradeJob.class);
    private static final String JOB_NAME = "membership-inactivity-downgrade";
    private static final int LEASE_SECONDS = 300;

    private final CommerceMapper mapper;
    private final InactivityDowngradeProcessor processor;
    private final String ownerId = UUID.randomUUID().toString();

    public InactivityDowngradeJob(CommerceMapper mapper, InactivityDowngradeProcessor processor) {
        this.mapper = mapper;
        this.processor = processor;
    }

    @Scheduled(cron = "${market-shop.jobs.inactivity-downgrade-cron:0 15 2 * * *}")
    public void downgrade() {
        mapper.acquireJobLease(JOB_NAME, ownerId, LEASE_SECONDS);
        if (mapper.ownsJobLease(JOB_NAME, ownerId) != 1) {
            return;
        }
        for (int index = 0; index < 1000; index++) {
            try {
                if (!processor.processNext()) {
                    return;
                }
            } catch (RuntimeException exception) {
                LOG.warn("Inactivity downgrade failed and will be retried: {}", exception.getMessage());
                return;
            }
        }
    }
}

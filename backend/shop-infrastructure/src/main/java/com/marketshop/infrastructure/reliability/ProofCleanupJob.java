package com.marketshop.infrastructure.reliability;

import com.marketshop.application.proof.OrderProofUseCase;
import com.marketshop.application.aftersale.AfterSaleProofUseCase;
import com.marketshop.infrastructure.persistence.mapper.CommerceMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ProofCleanupJob {

    private static final String JOB_NAME = "proof-retention-cleanup";
    private static final int LEASE_SECONDS = 120;

    private final CommerceMapper mapper;
    private final OrderProofUseCase proofs;
    private final AfterSaleProofUseCase afterSaleProofs;
    private final String ownerId = UUID.randomUUID().toString();

    public ProofCleanupJob(CommerceMapper mapper, OrderProofUseCase proofs, AfterSaleProofUseCase afterSaleProofs) {
        this.mapper = mapper;
        this.proofs = proofs;
        this.afterSaleProofs = afterSaleProofs;
    }

    @Scheduled(fixedDelayString = "${market-shop.jobs.proof-cleanup-delay-ms:3600000}")
    public void cleanup() {
        mapper.acquireJobLease(JOB_NAME, ownerId, LEASE_SECONDS);
        if (mapper.ownsJobLease(JOB_NAME, ownerId) == 0) {
            return;
        }
        for (int batch = 0; batch < 20 && proofs.cleanupExpired() == 100; batch++) {
            // Continue bounded batches while due records remain.
        }
        for (int batch = 0; batch < 20 && afterSaleProofs.cleanupExpired() == 100; batch++) {
            // Continue bounded batches while due records remain.
        }
    }
}

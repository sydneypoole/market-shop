package com.marketshop.application.aftersale;

import java.time.Instant;
import java.util.List;

public interface AfterSaleProofPort {

    boolean canUserAccess(long userId, long afterSaleId);

    int count(long afterSaleId);

    long save(Metadata metadata);

    List<Metadata> list(long afterSaleId);

    Metadata find(long proofId);

    List<Metadata> expired(int limit);

    void markCleaned(long proofId);

    record Metadata(long id, long afterSaleId, String proofType, String objectKey, String sha256,
                    String mediaType, long sizeBytes, Long uploadedByUserId, Instant retainUntil,
                    Instant createdAt, long applicantUserId, long superiorUserId) {
    }
}

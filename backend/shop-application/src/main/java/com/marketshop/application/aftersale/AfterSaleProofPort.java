package com.marketshop.application.aftersale;

import java.time.Instant;
import java.util.List;

public interface AfterSaleProofPort {

    /**
     * Locks the owning after-sale row for the complete upload transaction.
     * Besides checking ownership/status, this serializes the max-files check
     * with concurrent uploads and lifecycle transitions.
     */
    UploadAccess lockForUpload(long afterSaleId);

    boolean canUserAccess(long userId, long afterSaleId);

    int count(long afterSaleId);

    long save(Metadata metadata);

    List<Metadata> list(long afterSaleId);

    Metadata find(long proofId);

    /**
     * Loads a proof while holding the database row lock for the whole
     * retention/delete transaction.  Implementations backed by an in-memory
     * test store may use the regular lookup.
     */
    default Metadata findForUpdate(long proofId) {
        return find(proofId);
    }

    List<Metadata> expired(int limit);

    void markCleaned(long proofId);

    record UploadAccess(long applicantUserId, long superiorUserId, String status) {
    }

    record Metadata(long id, long afterSaleId, String proofType, String objectKey, String sha256,
                    String mediaType, long sizeBytes, Long uploadedByUserId, Instant retainUntil,
                    Instant createdAt, long applicantUserId, long superiorUserId) {
    }
}

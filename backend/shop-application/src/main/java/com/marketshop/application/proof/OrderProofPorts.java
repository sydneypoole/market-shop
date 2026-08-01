package com.marketshop.application.proof;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class OrderProofPorts {

    private OrderProofPorts() {
    }

    public interface ProofMetadataPort {
        OrderProofAccess orderAccess(long orderId);

        boolean canUserAccessOrder(long userId, long orderId);

        int countOrderProofs(long orderId);

        int maxFiles();

        long maxSizeBytes();

        long retentionDays();

        long save(ProofMetadata metadata);

        ProofMetadata find(long proofId);

        /**
         * Loads a proof while holding the database row lock for the whole
         * delete/cleanup transaction. Implementations backed by an in-memory
         * test store may use the regular lookup.
         */
        default ProofMetadata findForUpdate(long proofId) {
            return find(proofId);
        }

        List<ProofMetadata> listOrderProofs(long orderId);

        List<ProofMetadata> findExpired(int limit);

        void markCleaned(long proofId);
    }

    public interface PrivateObjectStoragePort {
        StoredObject put(long orderId, String originalFilename, String mediaType, byte[] bytes);

        String signedGetUrl(String objectKey, Duration duration);

        void delete(String objectKey);
    }

    public interface ProofSanitizerPort {
        SanitizedImage sanitize(byte[] bytes);
    }

    public record SanitizedImage(String mediaType, String extension, byte[] bytes) {
    }

    public record StoredObject(String objectKey, String sha256, long sizeBytes) {
    }

    public record OrderProofAccess(long buyerUserId, long superiorUserId, String orderStatus) {
    }

    public record ProofMetadata(long id, long orderId, String objectKey, String sha256, String mediaType,
                                long sizeBytes, long uploadedBy, Instant retainUntil, Instant createdAt,
                                long buyerUserId, long superiorUserId, String orderStatus) {
    }
}

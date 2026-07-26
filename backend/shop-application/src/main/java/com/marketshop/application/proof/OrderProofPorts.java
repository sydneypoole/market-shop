package com.marketshop.application.proof;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class OrderProofPorts {

    private OrderProofPorts() {
    }

    public interface ProofMetadataPort {
        boolean canUserAccessOrder(long userId, long orderId);

        int countOrderProofs(long orderId);

        int maxFiles();

        long maxSizeBytes();

        long retentionDays();

        long save(ProofMetadata metadata);

        ProofMetadata find(long proofId);

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

    public record ProofMetadata(long id, long orderId, String objectKey, String sha256, String mediaType,
                                long sizeBytes, long uploadedBy, Instant retainUntil, Instant createdAt,
                                long buyerUserId, long superiorUserId, String orderStatus) {
    }
}

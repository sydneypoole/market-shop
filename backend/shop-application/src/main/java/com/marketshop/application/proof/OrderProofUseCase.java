package com.marketshop.application.proof;

import java.time.Instant;
import java.util.List;

public interface OrderProofUseCase {

    UploadLimits uploadLimits();

    ProofView upload(long userId, UploadCommand command);

    List<ProofView> listUser(long userId, long orderId);

    List<ProofView> listAdmin(long adminId, long orderId);

    DownloadView userDownload(long userId, long proofId);

    DownloadView adminDownload(long adminId, long proofId);

    void userDelete(long userId, long proofId);

    void adminDelete(long adminId, long proofId, String reason);

    int cleanupExpired();

    record UploadCommand(long orderId, String originalFilename, String mediaType, byte[] bytes) {
    }

    record UploadLimits(int maxProofFiles, long maxProofSizeBytes) {
    }

    record ProofView(
            long proofId,
            long orderId,
            String mediaType,
            long sizeBytes,
            long uploadedBy,
            Instant retainUntil,
            Instant createdAt
    ) {
    }

    record DownloadView(String signedUrl, Instant expiresAt) {
    }
}

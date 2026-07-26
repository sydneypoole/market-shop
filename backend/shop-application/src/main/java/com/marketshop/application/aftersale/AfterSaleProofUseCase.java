package com.marketshop.application.aftersale;

import java.time.Instant;
import java.util.List;

public interface AfterSaleProofUseCase {

    ProofView uploadUser(long userId, long afterSaleId, String proofType, byte[] bytes);

    List<ProofView> listUser(long userId, long afterSaleId);

    List<ProofView> listAdmin(long adminId, long afterSaleId);

    DownloadView userDownload(long userId, long proofId);

    DownloadView adminDownload(long adminId, long proofId);

    int cleanupExpired();

    record ProofView(long id, long afterSaleId, String proofType, String mediaType,
                     long sizeBytes, Long uploadedByUserId, Instant retainUntil, Instant createdAt) {
    }

    record DownloadView(String signedUrl, Instant expiresAt) {
    }
}

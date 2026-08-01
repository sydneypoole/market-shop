package com.marketshop.application.aftersale;

import com.marketshop.application.aftersale.AfterSaleProofPort.Metadata;
import com.marketshop.application.aftersale.AfterSaleProofPort.UploadAccess;
import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.audit.AdminAuditPort.AuditRecord;
import com.marketshop.application.proof.OrderProofPorts.PrivateObjectStoragePort;
import com.marketshop.application.proof.OrderProofPorts.ProofMetadataPort;
import com.marketshop.application.proof.OrderProofPorts.ProofSanitizerPort;
import com.marketshop.domain.shared.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class AfterSaleProofApplicationService implements AfterSaleProofUseCase {

    private static final Set<String> TYPES = Set.of("APPLICATION", "RETURN", "REFUND");
    private static final Set<String> UPLOADABLE_STATUSES = Set.of(
            "PENDING_ADMIN_REVIEW",
            "AWAITING_RETURN",
            "RETURN_SHIPPED",
            "PENDING_OFFLINE_REFUND",
            "PENDING_BUYER_REFUND_CONFIRMATION"
    );
    private final AfterSaleProofPort port;
    private final ProofMetadataPort configuration;
    private final ProofSanitizerPort sanitizer;
    private final PrivateObjectStoragePort storage;
    private final AdminAuditPort audit;
    private final Duration signedUrlTtl;

    public AfterSaleProofApplicationService(AfterSaleProofPort port, ProofMetadataPort configuration,
                                            ProofSanitizerPort sanitizer, PrivateObjectStoragePort storage,
                                            AdminAuditPort audit,
                                            @Value("${market-shop.object-storage.signed-url-minutes:5}")
                                            long signedUrlMinutes,
                                            @Value("${market-shop.object-storage.signed-url-seconds:0}")
                                            long signedUrlSeconds) {
        this.port = port;
        this.configuration = configuration;
        this.sanitizer = sanitizer;
        this.storage = storage;
        this.audit = audit;
        this.signedUrlTtl = signedUrlSeconds > 0
                ? Duration.ofSeconds(Math.min(signedUrlSeconds, 3600))
                : Duration.ofMinutes(Math.max(1, Math.min(signedUrlMinutes, 60)));
    }

    @Override
    public ProofView uploadUser(long userId, long afterSaleId, String proofType, byte[] bytes) {
        // Lock the parent after-sale row before checking the current count.
        // A plain canUserAccess()+count() pair lets concurrent requests both
        // observe a free slot and exceed the configured max-files limit.
        UploadAccess access = port.lockForUpload(afterSaleId);
        if (access.applicantUserId() != userId) {
            throw new DomainException("AFTERSALE_ACCESS_DENIED", "无权访问此售后单");
        }
        if (!UPLOADABLE_STATUSES.contains(access.status())) {
            throw new DomainException("AFTERSALE_STATUS_CONFLICT", "当前售后状态不允许上传凭证");
        }
        String type = proofType == null ? "APPLICATION" : proofType.trim().toUpperCase();
        if (!TYPES.contains(type)) {
            throw new DomainException("AFTERSALE_PROOF_TYPE_INVALID", "售后凭证类型无效");
        }
        if (bytes == null || bytes.length == 0 || bytes.length > configuration.maxSizeBytes()) {
            throw new DomainException("PROOF_SIZE_INVALID", "售后凭证大小无效");
        }
        if (port.count(afterSaleId) >= configuration.maxFiles()) {
            throw new DomainException("PROOF_COUNT_EXCEEDED", "售后凭证数量已达到后台配置上限");
        }
        var image = sanitizer.sanitize(bytes);
        var object = storage.put(afterSaleId, "aftersale." + image.extension(), image.mediaType(), image.bytes());
        Instant now = Instant.now();
        try {
            long id = port.save(new Metadata(
                    0, afterSaleId, type, object.objectKey(), object.sha256(), image.mediaType(),
                    object.sizeBytes(), userId, now.plus(Duration.ofDays(configuration.retentionDays())),
                    now, 0, 0
            ));
            record("USER", userId, "AFTERSALE_PROOF_UPLOAD", id);
            return new ProofView(
                    id,
                    afterSaleId,
                    type,
                    image.mediaType(),
                    object.sizeBytes(),
                    userId,
                    now.plus(Duration.ofDays(configuration.retentionDays())),
                    now
            );
        } catch (RuntimeException exception) {
            try { storage.delete(object.objectKey()); } catch (RuntimeException ignored) { }
            throw exception;
        }
    }

    @Override
    public List<ProofView> listUser(long userId, long afterSaleId) {
        if (!port.canUserAccess(userId, afterSaleId)) {
            throw new DomainException("AFTERSALE_ACCESS_DENIED", "无权访问此售后单");
        }
        return port.list(afterSaleId).stream().map(AfterSaleProofApplicationService::view).toList();
    }

    @Override
    public List<ProofView> listAdmin(long adminId, long afterSaleId) {
        List<ProofView> values = port.list(afterSaleId).stream()
                .map(AfterSaleProofApplicationService::view)
                .toList();
        record("ADMIN", adminId, "AFTERSALE_PROOF_LIST", afterSaleId);
        return values;
    }

    @Override
    public DownloadView userDownload(long userId, long proofId) {
        // Serialize metadata lookup with retention cleanup so a signed URL is
        // never issued for an object that is being deleted concurrently.
        Metadata proof = port.findForUpdate(proofId);
        if (proof.applicantUserId() != userId && proof.superiorUserId() != userId) {
            throw new DomainException("AFTERSALE_ACCESS_DENIED", "无权查看此售后凭证");
        }
        record("USER", userId, "AFTERSALE_PROOF_DOWNLOAD", proofId);
        return download(proof);
    }

    @Override
    public DownloadView adminDownload(long adminId, long proofId) {
        Metadata proof = port.findForUpdate(proofId);
        record("ADMIN", adminId, "AFTERSALE_PROOF_DOWNLOAD", proofId);
        return download(proof);
    }

    @Override
    public int cleanupExpired() {
        int count = 0;
        for (Metadata proof : port.expired(100)) {
            // The candidate list is only a hint. Re-read the proof while
            // holding its row (and joined order) lock immediately before the
            // destructive object operation. Otherwise a concurrent upload,
            // transition, or cleaner could win the TOCTOU window and leave
            // metadata/object state inconsistent.
            try {
                Metadata locked = port.findForUpdate(proof.id());
                storage.delete(locked.objectKey());
                port.markCleaned(locked.id());
                record("SYSTEM", 0, "AFTERSALE_PROOF_DELETE", locked.id());
                count++;
            } catch (DomainException exception) {
                if (!"AFTERSALE_PROOF_NOT_FOUND".equals(exception.code())) {
                    throw exception;
                }
                // Another worker already cleaned the row; continue with the
                // remaining batch rather than aborting retention processing.
            }
        }
        return count;
    }

    private DownloadView download(Metadata proof) {
        return new DownloadView(
                storage.signedGetUrl(proof.objectKey(), signedUrlTtl),
                Instant.now().plus(signedUrlTtl)
        );
    }

    private void record(String actorType, long actorId, String action, long proofId) {
        audit.record(new AuditRecord(
                actorType, Long.toString(actorId), action, "AFTERSALE_PROOF", Long.toString(proofId),
                null, null, null, action.toLowerCase() + ":" + UUID.randomUUID(),
                null, null, Instant.now()
        ));
    }

    private static ProofView view(Metadata value) {
        return new ProofView(
                value.id(), value.afterSaleId(), value.proofType(), value.mediaType(),
                value.sizeBytes(), value.uploadedByUserId(), value.retainUntil(), value.createdAt()
        );
    }
}

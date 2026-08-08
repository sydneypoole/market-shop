package com.marketshop.application.proof;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.audit.AdminAuditPort.AuditRecord;
import com.marketshop.application.proof.OrderProofPorts.PrivateObjectStoragePort;
import com.marketshop.application.proof.OrderProofPorts.OrderProofAccess;
import com.marketshop.application.proof.OrderProofPorts.ProofMetadata;
import com.marketshop.application.proof.OrderProofPorts.ProofMetadataPort;
import com.marketshop.application.proof.OrderProofPorts.ProofSanitizerPort;
import com.marketshop.application.proof.OrderProofPorts.SanitizedImage;
import com.marketshop.application.proof.OrderProofPorts.StoredObject;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.domain.trade.OrderStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class OrderProofApplicationService implements OrderProofUseCase {

    private final ProofMetadataPort metadataPort;
    private final PrivateObjectStoragePort storagePort;
    private final ProofSanitizerPort sanitizerPort;
    private final AdminAuditPort auditPort;
    private final Duration signedUrlTtl;

    public OrderProofApplicationService(
            ProofMetadataPort metadataPort,
            PrivateObjectStoragePort storagePort,
            ProofSanitizerPort sanitizerPort,
            AdminAuditPort auditPort,
            @Value("${market-shop.object-storage.signed-url-minutes:5}") long signedUrlMinutes,
            @Value("${market-shop.object-storage.signed-url-seconds:0}") long signedUrlSeconds
    ) {
        this.metadataPort = metadataPort;
        this.storagePort = storagePort;
        this.sanitizerPort = sanitizerPort;
        this.auditPort = auditPort;
        this.signedUrlTtl = signedUrlSeconds > 0
                ? Duration.ofSeconds(Math.min(signedUrlSeconds, 3600))
                : Duration.ofMinutes(Math.max(1, Math.min(signedUrlMinutes, 60)));
    }

    @Override
    @Transactional(readOnly = true)
    public UploadLimits uploadLimits() {
        return new UploadLimits(metadataPort.maxFiles(), metadataPort.maxSizeBytes());
    }

    @Override
    public ProofView upload(long userId, UploadCommand command) {
        OrderProofAccess access = metadataPort.orderAccess(command.orderId());
        if (access.buyerUserId() != userId
                || !OrderStatus.PENDING_SUPERIOR.name().equals(access.orderStatus())) {
            throw new DomainException("PROOF_UPLOAD_DENIED", "仅订单买家可在上级确认前上传付款凭证");
        }
        long maxSize = metadataPort.maxSizeBytes();
        if (command.bytes() == null || command.bytes().length == 0 || command.bytes().length > maxSize) {
            throw new DomainException("PROOF_SIZE_INVALID", "付款凭证大小必须在 1 字节到后台配置上限之间");
        }
        if (metadataPort.countOrderProofs(command.orderId()) >= metadataPort.maxFiles()) {
            throw new DomainException("PROOF_COUNT_EXCEEDED", "付款凭证数量已达到后台配置上限");
        }
        SanitizedImage sanitized = sanitizerPort.sanitize(command.bytes());
        StoredObject object = storagePort.put(
                command.orderId(),
                "proof." + sanitized.extension(),
                sanitized.mediaType(),
                sanitized.bytes()
        );
        Instant now = Instant.now();
        try {
            long id = metadataPort.save(new ProofMetadata(
                    0,
                    command.orderId(),
                    object.objectKey(),
                    object.sha256(),
                    sanitized.mediaType(),
                    object.sizeBytes(),
                    userId,
                    now.plus(Duration.ofDays(metadataPort.retentionDays())),
                    now,
                    0,
                    0,
                    null
            ));
            audit("USER", userId, "PROOF_UPLOAD", id, null,
                    "{\"orderId\":" + command.orderId() + ",\"mediaType\":\"" + sanitized.mediaType()
                            + "\",\"sizeBytes\":" + object.sizeBytes() + "}",
                    null);
            return new ProofView(
                    id,
                    command.orderId(),
                    sanitized.mediaType(),
                    object.sizeBytes(),
                    userId,
                    now.plus(Duration.ofDays(metadataPort.retentionDays())),
                    now
            );
        } catch (RuntimeException exception) {
            try {
                storagePort.delete(object.objectKey());
            } catch (RuntimeException ignored) {
                // The retention reconciler will retry private object cleanup.
            }
            throw exception;
        }
    }

    @Override
    public List<ProofView> listUser(long userId, long orderId) {
        if (!metadataPort.canUserAccessOrder(userId, orderId)) {
            throw new DomainException("ORDER_ACCESS_DENIED", "无权查看此订单的付款凭证");
        }
        List<ProofView> proofs = metadataPort.listOrderProofs(orderId).stream()
                .map(OrderProofApplicationService::view)
                .toList();
        audit("USER", userId, "PROOF_LIST", orderId, null,
                "{\"count\":" + proofs.size() + "}", null);
        return proofs;
    }

    @Override
    public List<ProofView> listAdmin(long adminId, long orderId) {
        List<ProofView> proofs = metadataPort.listOrderProofs(orderId).stream()
                .map(OrderProofApplicationService::view)
                .toList();
        audit("ADMIN", adminId, "PROOF_LIST", orderId, null,
                "{\"count\":" + proofs.size() + "}", null);
        return proofs;
    }

    @Override
    public DownloadView userDownload(long userId, long proofId) {
        ProofMetadata proof = metadataPort.find(proofId);
        if (proof.buyerUserId() != userId && proof.superiorUserId() != userId) {
            throw new DomainException("PROOF_ACCESS_DENIED", "无权查看此付款凭证");
        }
        audit("USER", userId, "PROOF_DOWNLOAD", proofId, null, null, null);
        return download(proof);
    }

    @Override
    public DownloadView adminDownload(long adminId, long proofId) {
        ProofMetadata proof = metadataPort.find(proofId);
        audit("ADMIN", adminId, "PROOF_DOWNLOAD", proofId, null, null, null);
        return download(proof);
    }

    @Override
    public void userDelete(long userId, long proofId) {
        // Re-read under the order/proof row lock immediately before the
        // destructive object operation. A non-locking lookup here would let a
        // concurrent superior decision win between the status check and the
        // delete, violating the pending-superior policy.
        ProofMetadata proof = metadataPort.findForUpdate(proofId);
        if (proof.uploadedBy() != userId || !OrderStatus.PENDING_SUPERIOR.name().equals(proof.orderStatus())) {
            throw new DomainException("PROOF_DELETE_DENIED", "仅上传人可在上级确认前删除付款凭证");
        }
        delete(proof, "USER", userId, "用户主动删除");
    }

    @Override
    public void adminDelete(long adminId, long proofId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new DomainException("REASON_REQUIRED", "管理员删除凭证必须填写原因");
        }
        delete(metadataPort.findForUpdate(proofId), "ADMIN", adminId, reason.trim());
    }

    @Override
    public int cleanupExpired() {
        int cleaned = 0;
        for (ProofMetadata proof : metadataPort.findExpired(100)) {
            try {
                // The candidate list is only a hint. Lock and re-read each
                // row so another cleaner/admin cannot win the TOCTOU window.
                delete(metadataPort.findForUpdate(proof.id()), "SYSTEM", 0, "凭证保存期到期自动清理");
                cleaned++;
            } catch (DomainException exception) {
                if (!"PROOF_NOT_FOUND".equals(exception.code())) {
                    throw exception;
                }
                // Another worker already cleaned this row; continue with the
                // remaining batch instead of aborting the scheduler.
            }
        }
        return cleaned;
    }

    private DownloadView download(ProofMetadata proof) {
        Instant expiresAt = Instant.now().plus(signedUrlTtl);
        return new DownloadView(storagePort.signedGetUrl(proof.objectKey(), signedUrlTtl), expiresAt);
    }

    private static ProofView view(ProofMetadata proof) {
        return new ProofView(
                proof.id(),
                proof.orderId(),
                proof.mediaType(),
                proof.sizeBytes(),
                proof.uploadedBy(),
                proof.retainUntil(),
                proof.createdAt()
        );
    }

    private void delete(ProofMetadata proof, String actorType, long actorId, String reason) {
        storagePort.delete(proof.objectKey());
        metadataPort.markCleaned(proof.id());
        audit(actorType, actorId, "PROOF_DELETE", proof.id(),
                "{\"objectKey\":\"PRIVATE\",\"sha256\":\"" + proof.sha256() + "\"}",
                "{\"cleaned\":true}", reason);
    }

    private void audit(String actorType, long actorId, String action, long proofId,
                       String beforeJson, String afterJson, String reason) {
        auditPort.record(new AuditRecord(
                actorType,
                Long.toString(actorId),
                action,
                "ORDER_PROOF",
                Long.toString(proofId),
                beforeJson,
                afterJson,
                reason,
                action.toLowerCase() + ":" + UUID.randomUUID(),
                null,
                null,
                Instant.now()
        ));
    }
}

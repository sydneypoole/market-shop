package com.marketshop.infrastructure.commerce;

import com.marketshop.application.proof.OrderProofPorts.ProofMetadata;
import com.marketshop.application.proof.OrderProofPorts.ProofMetadataPort;
import com.marketshop.application.proof.OrderProofPorts.OrderProofAccess;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.CommerceMapper;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderProofPo;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.ProofRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class MyBatisProofMetadataAdapter implements ProofMetadataPort {

    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);

    private final CommerceMapper mapper;

    public MyBatisProofMetadataAdapter(CommerceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public OrderProofAccess orderAccess(long orderId) {
        // Upload runs in one application transaction. Holding the order-row lock
        // through object persistence serializes proof creation with every order
        // transition, so a superior decision cannot race the eligibility check.
        OrderRow row = mapper.lockOrderForProofUpload(orderId);
        if (row == null) {
            throw new DomainException("ORDER_NOT_FOUND", "订单不存在");
        }
        return new OrderProofAccess(row.buyerUserId, row.superiorUserId, row.status);
    }

    @Override
    public boolean canUserAccessOrder(long userId, long orderId) {
        return mapper.canUserAccessOrder(userId, orderId) > 0;
    }

    @Override
    public int countOrderProofs(long orderId) {
        return mapper.countOrderProofs(orderId);
    }

    @Override
    public int maxFiles() {
        Integer value = mapper.maxProofFiles();
        if (value == null || value < 1 || value > 20) {
            throw invalidOrderTimerSettings();
        }
        return value;
    }

    @Override
    public long maxSizeBytes() {
        Long value = mapper.maxProofSizeBytes();
        if (value == null || value < 1024 || value > 20L * 1024 * 1024) {
            throw invalidOrderTimerSettings();
        }
        return value;
    }

    @Override
    public long retentionDays() {
        Integer days = mapper.proofRetentionDays();
        return days == null || days <= 0 || days > 3650 ? 180 : days;
    }

    @Override
    public long save(ProofMetadata metadata) {
        OrderProofPo row = new OrderProofPo();
        row.orderId = metadata.orderId();
        row.objectKey = metadata.objectKey();
        row.sha256 = metadata.sha256();
        row.mediaType = metadata.mediaType();
        row.sizeBytes = metadata.sizeBytes();
        row.uploadedBy = metadata.uploadedBy();
        row.retainUntil = LocalDateTime.ofInstant(metadata.retainUntil(), BUSINESS_ZONE);
        mapper.insertOrderProof(row);
        return row.id;
    }

    @Override
    public ProofMetadata find(long proofId) {
        ProofRow row = mapper.proof(proofId);
        return requireMetadata(row);
    }

    @Override
    public ProofMetadata findForUpdate(long proofId) {
        return requireMetadata(mapper.lockProof(proofId));
    }

    @Override
    public List<ProofMetadata> listOrderProofs(long orderId) {
        return mapper.orderProofs(orderId).stream().map(this::metadata).toList();
    }

    @Override
    public List<ProofMetadata> findExpired(int limit) {
        return mapper.expiredProofs(Math.max(1, Math.min(limit, 500))).stream().map(this::metadata).toList();
    }

    @Override
    public void markCleaned(long proofId) {
        mapper.markProofCleaned(proofId);
    }

    private ProofMetadata metadata(ProofRow row) {
        return new ProofMetadata(
                row.id,
                row.orderId,
                row.objectKey,
                row.sha256,
                row.mediaType,
                row.sizeBytes,
                row.uploadedBy,
                instant(row.retainUntil),
                instant(row.createdAt),
                row.buyerUserId,
                row.superiorUserId,
                row.orderStatus
        );
    }

    private static ProofMetadata requireMetadata(ProofRow row) {
        if (row == null) {
            throw new DomainException("PROOF_NOT_FOUND", "付款凭证不存在或已清理");
        }
        return new ProofMetadata(
                row.id,
                row.orderId,
                row.objectKey,
                row.sha256,
                row.mediaType,
                row.sizeBytes,
                row.uploadedBy,
                instant(row.retainUntil),
                instant(row.createdAt),
                row.buyerUserId,
                row.superiorUserId,
                row.orderStatus
        );
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(BUSINESS_ZONE);
    }

    private static DomainException invalidOrderTimerSettings() {
        return new DomainException("ORDER_TIMER_SETTINGS_INVALID", "订单时效规则缺失或无效");
    }
}

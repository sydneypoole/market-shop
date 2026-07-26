package com.marketshop.infrastructure.aftersale;

import com.marketshop.application.aftersale.AfterSaleProofPort;
import com.marketshop.application.aftersale.AfterSaleProofPort.Metadata;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.AfterSaleMapper;
import com.marketshop.infrastructure.persistence.model.AfterSalePersistenceModels.AfterSaleProofPo;
import com.marketshop.infrastructure.persistence.model.AfterSalePersistenceModels.AfterSaleProofRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class MyBatisAfterSaleProofAdapter implements AfterSaleProofPort {

    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);
    private final AfterSaleMapper mapper;

    public MyBatisAfterSaleProofAdapter(AfterSaleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean canUserAccess(long userId, long afterSaleId) {
        return mapper.canUserAccessAfterSale(userId, afterSaleId) > 0;
    }

    @Override
    public int count(long afterSaleId) {
        return mapper.countAfterSaleProofs(afterSaleId);
    }

    @Override
    public long save(Metadata metadata) {
        AfterSaleProofPo row = new AfterSaleProofPo();
        row.afterSaleId = metadata.afterSaleId();
        row.proofType = metadata.proofType();
        row.objectKey = metadata.objectKey();
        row.sha256 = metadata.sha256();
        row.mediaType = metadata.mediaType();
        row.sizeBytes = metadata.sizeBytes();
        row.uploadedByUserId = metadata.uploadedByUserId();
        row.retainUntil = local(metadata.retainUntil());
        mapper.insertAfterSaleProof(row);
        return row.id;
    }

    @Override
    public List<Metadata> list(long afterSaleId) {
        return mapper.afterSaleProofs(afterSaleId).stream().map(this::metadata).toList();
    }

    @Override
    public Metadata find(long proofId) {
        AfterSaleProofRow row = mapper.afterSaleProof(proofId);
        if (row == null) {
            throw new DomainException("AFTERSALE_PROOF_NOT_FOUND", "售后凭证不存在或已清理");
        }
        return metadata(row);
    }

    @Override
    public List<Metadata> expired(int limit) {
        return mapper.expiredAfterSaleProofs(limit).stream().map(this::metadata).toList();
    }

    @Override
    public void markCleaned(long proofId) {
        mapper.markAfterSaleProofCleaned(proofId);
    }

    private Metadata metadata(AfterSaleProofRow row) {
        return new Metadata(
                row.id, row.afterSaleId, row.proofType, row.objectKey, row.sha256, row.mediaType,
                row.sizeBytes, row.uploadedByUserId, instant(row.retainUntil), instant(row.createdAt),
                row.applicantUserId, row.superiorUserId
        );
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(BUSINESS_ZONE);
    }

    private static LocalDateTime local(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, BUSINESS_ZONE);
    }
}

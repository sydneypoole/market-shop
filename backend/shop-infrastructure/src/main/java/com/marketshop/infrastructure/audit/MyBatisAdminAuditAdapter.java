package com.marketshop.infrastructure.audit;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.infrastructure.persistence.mapper.AuditMapper;
import com.marketshop.infrastructure.persistence.model.AuditPersistenceModels.AuditRow;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class MyBatisAdminAuditAdapter implements AdminAuditPort {

    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);

    private final AuditMapper mapper;

    public MyBatisAdminAuditAdapter(AuditMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(AuditRecord record) {
        mapper.insert(
                record.actorType(),
                record.actorId(),
                record.action(),
                record.resourceType(),
                record.resourceId(),
                record.beforeJson(),
                record.afterJson(),
                record.reason(),
                record.requestId(),
                record.maskedIp(),
                record.userAgentSummary(),
                LocalDateTime.ofInstant(record.occurredAt(), BUSINESS_ZONE)
        );
    }

    @Override
    public List<AuditView> search(AuditQuery query) {
        return mapper.search(
                query.actorType(),
                query.actorId(),
                query.action(),
                query.resourceType(),
                query.resourceId(),
                query.requestId(),
                local(query.from()),
                local(query.to()),
                query.offset(),
                query.limit()
        ).stream().map(MyBatisAdminAuditAdapter::view).toList();
    }

    @Override
    public long count(AuditQuery query) {
        return mapper.count(
                query.actorType(),
                query.actorId(),
                query.action(),
                query.resourceType(),
                query.resourceId(),
                query.requestId(),
                local(query.from()),
                local(query.to())
        );
    }

    private static AuditView view(AuditRow row) {
        return new AuditView(
                row.id,
                row.actorType,
                row.actorId,
                row.action,
                row.resourceType,
                row.resourceId,
                row.beforeJson,
                row.afterJson,
                row.reason,
                row.requestId,
                row.ipMasked,
                row.userAgentSummary,
                row.occurredAt.toInstant(BUSINESS_ZONE)
        );
    }

    private static LocalDateTime local(java.time.Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, BUSINESS_ZONE);
    }
}

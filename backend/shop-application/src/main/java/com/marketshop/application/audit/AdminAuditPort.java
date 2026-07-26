package com.marketshop.application.audit;

import java.time.Instant;
import java.util.List;

public interface AdminAuditPort {

    void record(AuditRecord record);

    List<AuditView> search(AuditQuery query);

    long count(AuditQuery query);

    record AuditRecord(
            String actorType,
            String actorId,
            String action,
            String resourceType,
            String resourceId,
            String beforeJson,
            String afterJson,
            String reason,
            String requestId,
            String maskedIp,
            String userAgentSummary,
            Instant occurredAt
    ) {
    }

    record AuditQuery(
            String actorType,
            String actorId,
            String action,
            String resourceType,
            String resourceId,
            String requestId,
            Instant from,
            Instant to,
            int offset,
            int limit
    ) {
    }

    record AuditView(
            long id,
            String actorType,
            String actorId,
            String action,
            String resourceType,
            String resourceId,
            String beforeJson,
            String afterJson,
            String reason,
            String requestId,
            String maskedIp,
            String userAgentSummary,
            Instant occurredAt
    ) {
    }
}

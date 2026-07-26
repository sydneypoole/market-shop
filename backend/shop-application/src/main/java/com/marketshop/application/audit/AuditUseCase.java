package com.marketshop.application.audit;

import com.marketshop.application.audit.AdminAuditPort.AuditView;

import java.time.Instant;
import java.util.List;

public interface AuditUseCase {

    AuditPage search(SearchCommand command);

    String exportCsv(SearchCommand command);

    record SearchCommand(
            String actorType,
            String actorId,
            String action,
            String resourceType,
            String resourceId,
            String requestId,
            Instant from,
            Instant to,
            int page,
            int pageSize
    ) {
    }

    record AuditPage(List<AuditView> items, long total, int page, int pageSize) {
    }
}

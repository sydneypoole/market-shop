package com.marketshop.infrastructure.persistence.model;

import java.time.LocalDateTime;

public final class AuditPersistenceModels {

    private AuditPersistenceModels() {
    }

    public static class AuditRow {
        public Long id;
        public String actorType;
        public String actorId;
        public String action;
        public String resourceType;
        public String resourceId;
        public String beforeJson;
        public String afterJson;
        public String reason;
        public String requestId;
        public String ipMasked;
        public String userAgentSummary;
        public LocalDateTime occurredAt;
    }
}

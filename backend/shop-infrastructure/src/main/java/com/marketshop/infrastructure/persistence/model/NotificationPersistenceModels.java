package com.marketshop.infrastructure.persistence.model;

import java.time.LocalDateTime;

public final class NotificationPersistenceModels {

    private NotificationPersistenceModels() {
    }

    public static class NotificationRow {
        public Long id;
        public String channel;
        public String templateCode;
        public String title;
        public String content;
        public String businessType;
        public String businessId;
        public String status;
        public LocalDateTime readAt;
        public LocalDateTime createdAt;
    }
}

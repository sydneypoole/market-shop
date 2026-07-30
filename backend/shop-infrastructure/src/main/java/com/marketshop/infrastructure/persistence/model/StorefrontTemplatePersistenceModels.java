package com.marketshop.infrastructure.persistence.model;

import java.time.LocalDateTime;

public final class StorefrontTemplatePersistenceModels {

    private StorefrontTemplatePersistenceModels() {
    }

    public static class TemplateRow {
        public Long id;
        public String templateCode;
        public String templateName;
        public String presetType;
        public String status;
        public Boolean active;
        public String designTokensJson;
        public String layoutJson;
        public Integer version;
        public LocalDateTime publishedAt;
        public LocalDateTime updatedAt;
    }
}

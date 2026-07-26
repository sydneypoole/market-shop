package com.marketshop.infrastructure.persistence.model;

import java.time.LocalDateTime;

public final class CatalogPersistenceModels {

    private CatalogPersistenceModels() {
    }

    public static class CategoryRow {
        public Long id;
        public Long parentId;
        public String name;
        public String code;
        public Integer sortOrder;
        public String status;
    }

    public static class ProductAdminRow {
        public Long productId;
        public Long categoryId;
        public String name;
        public String subtitle;
        public String coverUrl;
        public String descriptionHtml;
        public String salesScene;
        public String status;
        public Integer sortOrder;
        public Long skuId;
        public String skuCode;
        public String skuName;
        public Long priceFen;
        public Long marketPriceFen;
        public String attributesJson;
        public String skuStatus;
        public Integer availableQuantity;
        public Integer reservedQuantity;
    }

    public static class ProductPo {
        public Long id;
        public Long categoryId;
        public String name;
        public String subtitle;
        public String coverUrl;
        public String descriptionHtml;
        public String salesScene;
        public String status;
        public Integer sortOrder;
    }

    public static class SkuPo {
        public Long id;
        public Long productId;
        public String skuCode;
        public String name;
        public Long priceFen;
        public Long marketPriceFen;
        public String attributesJson;
        public String status;
    }

    public static class InventoryRow {
        public Long skuId;
        public Integer availableQuantity;
        public Integer reservedQuantity;
    }

    public static class InventoryAdjustmentRow {
        public Long id;
        public Long skuId;
        public Long adminId;
        public Integer beforeQuantity;
        public Integer afterQuantity;
        public String reason;
        public String requestId;
        public LocalDateTime createdAt;
    }

    public static class ContentAdminRow {
        public Long id;
        public String contentType;
        public String title;
        public String summary;
        public String coverUrl;
        public String targetUrl;
        public String bodyHtml;
        public String status;
        public Integer sortOrder;
    }

    public static class CatalogMediaAssetRow {
        public Long id;
        public String objectKey;
        public String sha256;
        public String originalFilename;
        public String mediaType;
        public Long sizeBytes;
        public Long uploadedByAdminId;
        public LocalDateTime createdAt;
    }
}

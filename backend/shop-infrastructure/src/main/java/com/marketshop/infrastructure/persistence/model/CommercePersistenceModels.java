package com.marketshop.infrastructure.persistence.model;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

public final class CommercePersistenceModels {

    private CommercePersistenceModels() {
    }

    @Table("trade_order")
    public static class OrderPo {
        @Id(keyType = KeyType.Auto)
        public Long id;
        public String orderNo;
        public Long buyerUserId;
        public Long superiorUserId;
        public String addressSnapshotJson;
        public String buyerNote;
        public Long totalAmountFen;
        public String status;
        public String source;
        public String clientRequestId;
        public Integer version;
    }

    @Table("trade_order_proof")
    public static class OrderProofPo {
        @Id(keyType = KeyType.Auto)
        public Long id;
        public Long orderId;
        public String objectKey;
        public String sha256;
        public String mediaType;
        public Long sizeBytes;
        public Long uploadedBy;
        public LocalDateTime retainUntil;
    }

    public static class ProductRow {
        public Long productId;
        public Long categoryId;
        public String categoryName;
        public String name;
        public String subtitle;
        public String coverUrl;
        public String descriptionHtml;
        public String salesScene;
        public Long skuId;
        public String skuCode;
        public String skuName;
        public Long priceFen;
        public Long marketPriceFen;
        public Long minPriceFen;
        public Long maxPriceFen;
        public Integer skuCount;
        public String attributesJson;
        public Integer inventory;
    }

    public static class CategoryRow {
        public Long id;
        public Long parentId;
        public String name;
        public String code;
        public Integer sortOrder;
        public Integer productCount;
    }

    public static class ContentRow {
        public Long id;
        public String contentType;
        public String title;
        public String summary;
        public String coverUrl;
        public String targetUrl;
        public String bodyHtml;
    }

    public static class CartRow {
        public Long id;
        public Long skuId;
        public String productName;
        public String skuName;
        public String coverUrl;
        public Long priceFen;
        public Integer quantity;
        public Boolean selected;
        public Integer inventory;
    }

    public static class SkuRow {
        public Long productId;
        public Long skuId;
        public String productName;
        public String skuName;
        public String coverUrl;
        public String salesScene;
        public Long unitPriceFen;
        public Integer availableQuantity;
    }

    public static class OrderRow {
        public Long id;
        public String orderNo;
        public Long buyerUserId;
        public Long superiorUserId;
        public String addressSnapshotJson;
        public String buyerNote;
        public Long totalAmountFen;
        public String status;
        public String reason;
        public LocalDateTime superiorConfirmedAt;
        public LocalDateTime adminReviewedAt;
        public LocalDateTime shippedAt;
        public LocalDateTime autoReceiveAt;
        public LocalDateTime completedAt;
        public LocalDateTime createdAt;
        public Integer version;
    }

    public static class OrderItemRow {
        public Long productId;
        public Long skuId;
        public String productName;
        public String skuName;
        public String coverUrl;
        public String salesScene;
        public Long unitPriceFen;
        public Integer quantity;
        public Long subtotalFen;
    }

    public static class ShipmentRow {
        public String carrierCode;
        public String carrierName;
        public String trackingNo;
        public LocalDateTime shippedAt;
    }

    public static class ProofRow {
        public Long id;
        public Long orderId;
        public String objectKey;
        public String sha256;
        public String mediaType;
        public Long sizeBytes;
        public Long uploadedBy;
        public LocalDateTime retainUntil;
        public LocalDateTime createdAt;
        public Long buyerUserId;
        public Long superiorUserId;
        public String orderStatus;
    }

    public static class OrderNoteRow {
        public Long id;
        public Long adminId;
        public String note;
        public LocalDateTime createdAt;
    }
}

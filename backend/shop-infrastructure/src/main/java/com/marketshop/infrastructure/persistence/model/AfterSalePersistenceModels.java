package com.marketshop.infrastructure.persistence.model;

import java.time.LocalDateTime;

public final class AfterSalePersistenceModels {

    private AfterSalePersistenceModels() {
    }

    public static class EligibilityRow {
        public Long orderId;
        public Long buyerUserId;
        public String status;
        public LocalDateTime completedAt;
        public Integer activeAfterSaleCount;
        public Integer completedAfterSaleCount;
    }

    public static class AfterSaleRow {
        public Long id;
        public String afterSaleNo;
        public Long orderId;
        public Long applicantUserId;
        public Long superiorUserId;
        public String type;
        public String status;
        public String reason;
        public String adminReason;
        public String returnAddressJson;
        public String returnCarrier;
        public String returnTrackingNo;
        public LocalDateTime createdAt;
        public LocalDateTime completedAt;
    }

    public static class AfterSaleProofPo {
        public Long id;
        public Long afterSaleId;
        public String proofType;
        public String objectKey;
        public String sha256;
        public String mediaType;
        public Long sizeBytes;
        public Long uploadedByUserId;
        public LocalDateTime retainUntil;
    }

    public static class AfterSaleProofUploadAccessRow {
        public Long applicantUserId;
        public Long superiorUserId;
        public String status;
    }

    public static class AfterSaleProofRow extends AfterSaleProofPo {
        public LocalDateTime createdAt;
        public Long applicantUserId;
        public Long superiorUserId;
    }
}

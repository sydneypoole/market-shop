package com.marketshop.application.catalog;

import java.util.List;
import java.time.Instant;

public interface CatalogAdminUseCase {

    List<CategoryView> categories();

    CategoryView saveCategory(SaveCategoryCommand command);

    void disableCategory(long categoryId);

    List<ProductAdminView> products();

    ProductAdminView saveProduct(SaveProductCommand command);

    void adjustInventory(long adminId, InventoryAdjustmentCommand command);

    List<InventoryAdjustmentView> inventoryAdjustments(long skuId);

    List<ContentAdminView> contents();

    ContentAdminView saveContent(SaveContentCommand command);

    void deleteContent(long contentId);

    record CategoryView(long id, Long parentId, String name, String code, int sortOrder, String status) {
    }

    record SaveCategoryCommand(Long id, Long parentId, String name, String code, int sortOrder, String status) {
    }

    record ProductAdminView(
            long productId,
            long categoryId,
            String name,
            String subtitle,
            String coverUrl,
            String descriptionHtml,
            String salesScene,
            String status,
            int sortOrder,
            long skuId,
            String skuCode,
            String skuName,
            long priceFen,
            Long marketPriceFen,
            String attributesJson,
            String skuStatus,
            int availableQuantity,
            int reservedQuantity
    ) {
    }

    record SaveProductCommand(
            Long productId,
            long categoryId,
            String name,
            String subtitle,
            String coverUrl,
            String descriptionHtml,
            String salesScene,
            String status,
            int sortOrder,
            Long skuId,
            String skuCode,
            String skuName,
            long priceFen,
            Long marketPriceFen,
            String attributesJson,
            String skuStatus,
            int initialInventory
    ) {
    }

    record InventoryAdjustmentCommand(long skuId, int afterQuantity, String reason, String requestId) {
    }

    record InventoryAdjustmentView(
            long id,
            long skuId,
            long adminId,
            int beforeQuantity,
            int afterQuantity,
            String reason,
            String requestId,
            Instant createdAt
    ) {
    }

    record ContentAdminView(
            long id,
            String contentType,
            String title,
            String summary,
            String coverUrl,
            String targetUrl,
            String bodyHtml,
            String status,
            int sortOrder
    ) {
    }

    record SaveContentCommand(
            Long id,
            String contentType,
            String title,
            String summary,
            String coverUrl,
            String targetUrl,
            String bodyHtml,
            String status,
            int sortOrder
    ) {
    }
}

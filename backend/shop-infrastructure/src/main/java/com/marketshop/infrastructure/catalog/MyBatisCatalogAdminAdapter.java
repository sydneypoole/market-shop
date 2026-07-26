package com.marketshop.infrastructure.catalog;

import com.marketshop.application.catalog.CatalogAdminPort;
import com.marketshop.application.catalog.CatalogAssetPort;
import com.marketshop.application.catalog.CatalogAssetPort.AssetMetadata;
import com.marketshop.application.catalog.CatalogAdminUseCase.CategoryView;
import com.marketshop.application.catalog.CatalogAdminUseCase.ContentAdminView;
import com.marketshop.application.catalog.CatalogAdminUseCase.InventoryAdjustmentCommand;
import com.marketshop.application.catalog.CatalogAdminUseCase.InventoryAdjustmentView;
import com.marketshop.application.catalog.CatalogAdminUseCase.ProductAdminView;
import com.marketshop.application.catalog.CatalogAdminUseCase.SaveCategoryCommand;
import com.marketshop.application.catalog.CatalogAdminUseCase.SaveContentCommand;
import com.marketshop.application.catalog.CatalogAdminUseCase.SaveProductCommand;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.CatalogAdminMapper;
import com.marketshop.infrastructure.persistence.model.CatalogPersistenceModels.CategoryRow;
import com.marketshop.infrastructure.persistence.model.CatalogPersistenceModels.ContentAdminRow;
import com.marketshop.infrastructure.persistence.model.CatalogPersistenceModels.InventoryRow;
import com.marketshop.infrastructure.persistence.model.CatalogPersistenceModels.InventoryAdjustmentRow;
import com.marketshop.infrastructure.persistence.model.CatalogPersistenceModels.ProductPo;
import com.marketshop.infrastructure.persistence.model.CatalogPersistenceModels.SkuPo;
import com.marketshop.infrastructure.persistence.model.CatalogPersistenceModels.CatalogMediaAssetRow;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.ZoneOffset;

@Repository
public class MyBatisCatalogAdminAdapter implements CatalogAdminPort, CatalogAssetPort {

    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);
    private final CatalogAdminMapper mapper;

    public MyBatisCatalogAdminAdapter(CatalogAdminMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<CategoryView> categories() {
        return mapper.categories().stream().map(MyBatisCatalogAdminAdapter::category).toList();
    }

    @Override
    @Transactional
    public CategoryView saveCategory(SaveCategoryCommand command) {
        CategoryRow row = new CategoryRow();
        row.id = command.id();
        row.parentId = command.parentId();
        row.name = command.name();
        row.code = command.code();
        row.sortOrder = command.sortOrder();
        row.status = command.status();
        int changed = row.id == null ? mapper.insertCategory(row) : mapper.updateCategory(row);
        if (changed != 1) {
            throw notFound("分类");
        }
        return category(mapper.category(row.id));
    }

    @Override
    public void disableCategory(long categoryId) {
        if (mapper.disableCategory(categoryId) != 1) {
            throw notFound("分类");
        }
    }

    @Override
    public List<ProductAdminView> products() {
        return mapper.products().stream().map(MyBatisCatalogAdminAdapter::product).toList();
    }

    @Override
    @Transactional
    public ProductAdminView saveProduct(SaveProductCommand command) {
        ProductPo product = new ProductPo();
        product.id = command.productId();
        product.categoryId = command.categoryId();
        product.name = command.name();
        product.subtitle = command.subtitle();
        product.coverUrl = command.coverUrl();
        product.descriptionHtml = command.descriptionHtml();
        product.salesScene = command.salesScene();
        product.status = command.status();
        product.sortOrder = command.sortOrder();

        SkuPo sku = new SkuPo();
        sku.id = command.skuId();
        sku.skuCode = command.skuCode();
        sku.name = command.skuName();
        sku.priceFen = command.priceFen();
        sku.marketPriceFen = command.marketPriceFen();
        sku.attributesJson = command.attributesJson();
        sku.status = command.skuStatus();

        if (product.id == null) {
            mapper.insertProduct(product);
            sku.productId = product.id;
            mapper.insertSku(sku);
            mapper.insertInventory(sku.id, command.initialInventory());
        } else {
            sku.productId = product.id;
            if (mapper.updateProduct(product) != 1) {
                throw notFound("商品或规格");
            }
            if (sku.id == null) {
                mapper.insertSku(sku);
                mapper.insertInventory(sku.id, command.initialInventory());
            } else if (mapper.updateSku(sku) != 1) {
                throw notFound("商品或规格");
            }
        }
        return product(mapper.product(product.id, sku.id));
    }

    @Override
    @Transactional
    public void adjustInventory(long adminId, InventoryAdjustmentCommand command) {
        if (mapper.adjustmentExists(command.requestId()) > 0) {
            return;
        }
        InventoryRow current = mapper.lockInventory(command.skuId());
        if (current == null) {
            throw notFound("库存");
        }
        mapper.setInventory(command.skuId(), command.afterQuantity());
        mapper.insertAdjustment(
                command.skuId(), adminId, current.availableQuantity,
                command.afterQuantity(), command.reason(), command.requestId()
        );
    }

    @Override
    public List<InventoryAdjustmentView> inventoryAdjustments(long skuId) {
        return mapper.inventoryAdjustments(skuId).stream()
                .map(MyBatisCatalogAdminAdapter::inventoryAdjustment)
                .toList();
    }

    @Override
    public List<ContentAdminView> contents() {
        return mapper.contents().stream().map(MyBatisCatalogAdminAdapter::content).toList();
    }

    @Override
    @Transactional
    public ContentAdminView saveContent(SaveContentCommand command) {
        ContentAdminRow row = new ContentAdminRow();
        row.id = command.id();
        row.contentType = command.contentType();
        row.title = command.title();
        row.summary = command.summary();
        row.coverUrl = command.coverUrl();
        row.targetUrl = command.targetUrl();
        row.bodyHtml = command.bodyHtml();
        row.status = command.status();
        row.sortOrder = command.sortOrder();
        int changed = row.id == null ? mapper.insertContent(row) : mapper.updateContent(row);
        if (changed != 1) {
            throw notFound("内容");
        }
        return content(mapper.content(row.id));
    }

    @Override
    public void deleteContent(long contentId) {
        if (mapper.deleteContent(contentId) != 1) {
            throw notFound("内容");
        }
    }

    @Override
    public long save(AssetMetadata metadata) {
        CatalogMediaAssetRow row = new CatalogMediaAssetRow();
        row.objectKey = metadata.objectKey();
        row.sha256 = metadata.sha256();
        row.originalFilename = metadata.originalFilename();
        row.mediaType = metadata.mediaType();
        row.sizeBytes = metadata.sizeBytes();
        row.uploadedByAdminId = metadata.uploadedByAdminId();
        mapper.insertAsset(row);
        return row.id;
    }

    @Override
    public List<AssetMetadata> assets() {
        return mapper.assets().stream().map(MyBatisCatalogAdminAdapter::asset).toList();
    }

    @Override
    public AssetMetadata find(long assetId) {
        CatalogMediaAssetRow row = mapper.asset(assetId);
        if (row == null) {
            throw notFound("商品素材");
        }
        return asset(row);
    }

    @Override
    public void markDeleted(long assetId) {
        if (mapper.markAssetDeleted(assetId) != 1) {
            throw notFound("商品素材");
        }
    }

    private static CategoryView category(CategoryRow row) {
        return new CategoryView(row.id, row.parentId, row.name, row.code, row.sortOrder, row.status);
    }

    private static ProductAdminView product(
            com.marketshop.infrastructure.persistence.model.CatalogPersistenceModels.ProductAdminRow row
    ) {
        if (row == null) {
            throw notFound("商品");
        }
        return new ProductAdminView(
                row.productId, row.categoryId, row.name, row.subtitle, row.coverUrl, row.descriptionHtml,
                row.salesScene, row.status, row.sortOrder, row.skuId, row.skuCode, row.skuName,
                row.priceFen, row.marketPriceFen, row.attributesJson, row.skuStatus,
                row.availableQuantity, row.reservedQuantity
        );
    }

    private static ContentAdminView content(ContentAdminRow row) {
        if (row == null) {
            throw notFound("内容");
        }
        return new ContentAdminView(
                row.id, row.contentType, row.title, row.summary, row.coverUrl,
                row.targetUrl, row.bodyHtml, row.status, row.sortOrder
        );
    }

    private static InventoryAdjustmentView inventoryAdjustment(InventoryAdjustmentRow row) {
        return new InventoryAdjustmentView(
                row.id,
                row.skuId,
                row.adminId,
                row.beforeQuantity,
                row.afterQuantity,
                row.reason,
                row.requestId,
                row.createdAt.toInstant(BUSINESS_ZONE)
        );
    }

    private static AssetMetadata asset(CatalogMediaAssetRow row) {
        return new AssetMetadata(
                row.id,
                row.objectKey,
                row.sha256,
                row.originalFilename,
                row.mediaType,
                row.sizeBytes,
                row.uploadedByAdminId,
                row.createdAt.toInstant(BUSINESS_ZONE)
        );
    }

    private static DomainException notFound(String resource) {
        return new DomainException("CATALOG_RESOURCE_NOT_FOUND", resource + "不存在");
    }
}

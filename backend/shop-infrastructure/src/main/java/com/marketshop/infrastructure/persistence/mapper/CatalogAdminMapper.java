package com.marketshop.infrastructure.persistence.mapper;

import com.marketshop.infrastructure.persistence.model.CatalogPersistenceModels.CategoryRow;
import com.marketshop.infrastructure.persistence.model.CatalogPersistenceModels.ContentAdminRow;
import com.marketshop.infrastructure.persistence.model.CatalogPersistenceModels.InventoryRow;
import com.marketshop.infrastructure.persistence.model.CatalogPersistenceModels.InventoryAdjustmentRow;
import com.marketshop.infrastructure.persistence.model.CatalogPersistenceModels.ProductAdminRow;
import com.marketshop.infrastructure.persistence.model.CatalogPersistenceModels.ProductPo;
import com.marketshop.infrastructure.persistence.model.CatalogPersistenceModels.SkuPo;
import com.marketshop.infrastructure.persistence.model.CatalogPersistenceModels.CatalogMediaAssetRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface CatalogAdminMapper {

    @Select("""
            SELECT id, parent_id, name, code, sort_order, status
            FROM catalog_category
            ORDER BY sort_order, id
            """)
    List<CategoryRow> categories();

    @Select("SELECT id, parent_id, name, code, sort_order, status FROM catalog_category WHERE id = #{id}")
    CategoryRow category(@Param("id") long id);

    @Insert("""
            INSERT INTO catalog_category (parent_id, name, code, sort_order, status)
            VALUES (#{parentId}, #{name}, #{code}, #{sortOrder}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertCategory(CategoryRow row);

    @Update("""
            UPDATE catalog_category
            SET parent_id = #{parentId}, name = #{name}, code = #{code},
                sort_order = #{sortOrder}, status = #{status}
            WHERE id = #{id}
            """)
    int updateCategory(CategoryRow row);

    @Update("UPDATE catalog_category SET status = 'DISABLED' WHERE id = #{id}")
    int disableCategory(@Param("id") long id);

    @Select("""
            SELECT p.id AS product_id, p.category_id, p.name, p.subtitle, p.cover_url, p.description_html,
                   p.sales_scene, p.status, p.sort_order, s.id AS sku_id, s.sku_code, s.name AS sku_name,
                   s.price_fen, s.market_price_fen, CAST(s.attributes_json AS CHAR) AS attributes_json,
                   s.status AS sku_status, i.available_quantity, i.reserved_quantity
            FROM catalog_product p
            JOIN catalog_sku s ON s.product_id = p.id
            JOIN catalog_inventory i ON i.sku_id = s.id
            ORDER BY p.sort_order, p.id, s.id
            """)
    List<ProductAdminRow> products();

    @Select("""
            SELECT p.id AS product_id, p.category_id, p.name, p.subtitle, p.cover_url, p.description_html,
                   p.sales_scene, p.status, p.sort_order, s.id AS sku_id, s.sku_code, s.name AS sku_name,
                   s.price_fen, s.market_price_fen, CAST(s.attributes_json AS CHAR) AS attributes_json,
                   s.status AS sku_status, i.available_quantity, i.reserved_quantity
            FROM catalog_product p
            JOIN catalog_sku s ON s.product_id = p.id
            JOIN catalog_inventory i ON i.sku_id = s.id
            WHERE p.id = #{productId} AND s.id = #{skuId}
            """)
    ProductAdminRow product(@Param("productId") long productId, @Param("skuId") long skuId);

    @Insert("""
            INSERT INTO catalog_product
                (category_id, name, subtitle, cover_url, description_html, sales_scene, status, sort_order)
            VALUES
                (#{categoryId}, #{name}, #{subtitle}, #{coverUrl}, #{descriptionHtml},
                 #{salesScene}, #{status}, #{sortOrder})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertProduct(ProductPo row);

    @Update("""
            UPDATE catalog_product
            SET category_id = #{categoryId}, name = #{name}, subtitle = #{subtitle},
                cover_url = #{coverUrl}, description_html = #{descriptionHtml},
                sales_scene = #{salesScene}, status = #{status}, sort_order = #{sortOrder},
                version = version + 1
            WHERE id = #{id}
            """)
    int updateProduct(ProductPo row);

    @Insert("""
            INSERT INTO catalog_sku
                (product_id, sku_code, name, price_fen, market_price_fen, attributes_json, status)
            VALUES
                (#{productId}, #{skuCode}, #{name}, #{priceFen}, #{marketPriceFen},
                 CAST(#{attributesJson} AS JSON), #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSku(SkuPo row);

    @Update("""
            UPDATE catalog_sku
            SET sku_code = #{skuCode}, name = #{name}, price_fen = #{priceFen},
                market_price_fen = #{marketPriceFen}, attributes_json = CAST(#{attributesJson} AS JSON),
                status = #{status}, version = version + 1
            WHERE id = #{id} AND product_id = #{productId}
            """)
    int updateSku(SkuPo row);

    @Insert("""
            INSERT INTO catalog_inventory (sku_id, available_quantity, reserved_quantity)
            VALUES (#{skuId}, #{quantity}, 0)
            """)
    int insertInventory(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Select("""
            SELECT sku_id, available_quantity, reserved_quantity
            FROM catalog_inventory
            WHERE sku_id = #{skuId}
            FOR UPDATE
            """)
    InventoryRow lockInventory(@Param("skuId") long skuId);

    @Update("""
            UPDATE catalog_inventory
            SET available_quantity = #{quantity}, version = version + 1
            WHERE sku_id = #{skuId}
            """)
    int setInventory(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Select("SELECT COUNT(*) FROM catalog_inventory_adjustment WHERE request_id = #{requestId}")
    int adjustmentExists(@Param("requestId") String requestId);

    @Insert("""
            INSERT INTO catalog_inventory_adjustment
                (sku_id, admin_id, before_quantity, after_quantity, reason, request_id)
            VALUES
                (#{skuId}, #{adminId}, #{beforeQuantity}, #{afterQuantity}, #{reason}, #{requestId})
            """)
    int insertAdjustment(@Param("skuId") long skuId,
                         @Param("adminId") long adminId,
                         @Param("beforeQuantity") int beforeQuantity,
                         @Param("afterQuantity") int afterQuantity,
                         @Param("reason") String reason,
                         @Param("requestId") String requestId);

    @Select("""
            SELECT id, sku_id, admin_id, before_quantity, after_quantity, reason, request_id, created_at
            FROM catalog_inventory_adjustment
            WHERE sku_id = #{skuId}
            ORDER BY created_at DESC, id DESC
            LIMIT 200
            """)
    List<InventoryAdjustmentRow> inventoryAdjustments(@Param("skuId") long skuId);

    @Select("""
            SELECT id, content_type, title, summary, cover_url, target_url, body_html, status, sort_order
            FROM operation_content
            WHERE status <> 'DELETED'
            ORDER BY sort_order, id
            """)
    List<ContentAdminRow> contents();

    @Select("""
            SELECT id, content_type, title, summary, cover_url, target_url, body_html, status, sort_order
            FROM operation_content WHERE id = #{id}
            """)
    ContentAdminRow content(@Param("id") long id);

    @Insert("""
            INSERT INTO operation_content
                (content_type, title, summary, cover_url, target_url, body_html, status, sort_order, published_at)
            VALUES
                (#{contentType}, #{title}, #{summary}, #{coverUrl}, #{targetUrl}, #{bodyHtml},
                 #{status}, #{sortOrder}, IF(#{status} = 'PUBLISHED', CURRENT_TIMESTAMP(3), NULL))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertContent(ContentAdminRow row);

    @Update("""
            UPDATE operation_content
            SET content_type = #{contentType}, title = #{title}, summary = #{summary},
                cover_url = #{coverUrl}, target_url = #{targetUrl}, body_html = #{bodyHtml},
                status = #{status}, sort_order = #{sortOrder},
                published_at = IF(#{status} = 'PUBLISHED', COALESCE(published_at, CURRENT_TIMESTAMP(3)), NULL)
            WHERE id = #{id} AND status <> 'DELETED'
            """)
    int updateContent(ContentAdminRow row);

    @Update("UPDATE operation_content SET status = 'DELETED', published_at = NULL WHERE id = #{id}")
    int deleteContent(@Param("id") long id);

    @Insert("""
            INSERT INTO catalog_media_asset
                (object_key, sha256, original_filename, media_type, size_bytes, uploaded_by_admin_id)
            VALUES
                (#{objectKey}, #{sha256}, #{originalFilename}, #{mediaType}, #{sizeBytes}, #{uploadedByAdminId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertAsset(CatalogMediaAssetRow row);

    @Select("""
            SELECT id, object_key, sha256, original_filename, media_type, size_bytes,
                   uploaded_by_admin_id, created_at
            FROM catalog_media_asset
            WHERE status = 'ACTIVE'
            ORDER BY created_at DESC, id DESC
            LIMIT 500
            """)
    List<CatalogMediaAssetRow> assets();

    @Select("""
            SELECT id, object_key, sha256, original_filename, media_type, size_bytes,
                   uploaded_by_admin_id, created_at
            FROM catalog_media_asset
            WHERE id = #{assetId} AND status = 'ACTIVE'
            """)
    CatalogMediaAssetRow asset(@Param("assetId") long assetId);

    @Update("""
            UPDATE catalog_media_asset
            SET status = 'DELETED', deleted_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{assetId} AND status = 'ACTIVE'
            """)
    int markAssetDeleted(@Param("assetId") long assetId);
}

package com.marketshop.interfaces.commerce;

import com.marketshop.application.catalog.CatalogAdminUseCase;
import com.marketshop.application.catalog.CatalogAssetUseCase;
import com.marketshop.application.catalog.CatalogAdminUseCase.InventoryAdjustmentCommand;
import com.marketshop.application.catalog.CatalogAdminUseCase.SaveCategoryCommand;
import com.marketshop.application.catalog.CatalogAdminUseCase.SaveContentCommand;
import com.marketshop.application.catalog.CatalogAdminUseCase.SaveProductCommand;
import com.marketshop.interfaces.security.StpAdminKit;
import com.marketshop.interfaces.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/catalog")
public class AdminCatalogController {

    private final CatalogAdminUseCase catalog;
    private final CatalogAssetUseCase assets;

    public AdminCatalogController(CatalogAdminUseCase catalog, CatalogAssetUseCase assets) {
        this.catalog = catalog;
        this.assets = assets;
    }

    @GetMapping("/categories")
    public ApiResponse<List<CatalogAdminUseCase.CategoryView>> categories() {
        StpAdminKit.requirePermission("catalog:read");
        return ApiResponse.ok(catalog.categories());
    }

    @PostMapping("/categories")
    public ApiResponse<CatalogAdminUseCase.CategoryView> createCategory(
            @Valid @RequestBody CategoryRequest request
    ) {
        StpAdminKit.requirePermission("catalog:write");
        return ApiResponse.ok(catalog.saveCategory(request.command(null)));
    }

    @PutMapping("/categories/{categoryId}")
    public ApiResponse<CatalogAdminUseCase.CategoryView> updateCategory(
            @PathVariable long categoryId,
            @Valid @RequestBody CategoryRequest request
    ) {
        StpAdminKit.requirePermission("catalog:write");
        return ApiResponse.ok(catalog.saveCategory(request.command(categoryId)));
    }

    @DeleteMapping("/categories/{categoryId}")
    public ApiResponse<Void> disableCategory(@PathVariable long categoryId) {
        StpAdminKit.requirePermission("catalog:write");
        catalog.disableCategory(categoryId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/products")
    public ApiResponse<List<CatalogAdminUseCase.ProductAdminView>> products() {
        StpAdminKit.requirePermission("catalog:read");
        return ApiResponse.ok(catalog.products());
    }

    @PostMapping("/products")
    public ApiResponse<CatalogAdminUseCase.ProductAdminView> createProduct(
            @Valid @RequestBody ProductRequest request
    ) {
        StpAdminKit.requirePermission("catalog:write");
        return ApiResponse.ok(catalog.saveProduct(request.command(null)));
    }

    @PutMapping("/products/{productId}")
    public ApiResponse<CatalogAdminUseCase.ProductAdminView> updateProduct(
            @PathVariable long productId,
            @Valid @RequestBody ProductRequest request
    ) {
        StpAdminKit.requirePermission("catalog:write");
        return ApiResponse.ok(catalog.saveProduct(request.command(productId)));
    }

    @PostMapping("/skus/{skuId}/inventory-adjustments")
    public ApiResponse<Void> adjustInventory(
            @PathVariable long skuId,
            @Valid @RequestBody InventoryRequest request
    ) {
        StpAdminKit.requirePermission("catalog:write");
        catalog.adjustInventory(
                StpAdminKit.logic().getLoginIdAsLong(),
                new InventoryAdjustmentCommand(skuId, request.afterQuantity(), request.reason(), request.requestId())
        );
        return ApiResponse.ok(null);
    }

    @GetMapping("/skus/{skuId}/inventory-adjustments")
    public ApiResponse<List<CatalogAdminUseCase.InventoryAdjustmentView>> inventoryAdjustments(
            @PathVariable long skuId
    ) {
        StpAdminKit.requirePermission("catalog:read");
        return ApiResponse.ok(catalog.inventoryAdjustments(skuId));
    }

    @GetMapping("/contents")
    public ApiResponse<List<CatalogAdminUseCase.ContentAdminView>> contents() {
        StpAdminKit.requirePermission("catalog:read");
        return ApiResponse.ok(catalog.contents());
    }

    @PostMapping("/contents")
    public ApiResponse<CatalogAdminUseCase.ContentAdminView> createContent(
            @Valid @RequestBody ContentRequest request
    ) {
        StpAdminKit.requirePermission("content:write");
        return ApiResponse.ok(catalog.saveContent(request.command(null)));
    }

    @PutMapping("/contents/{contentId}")
    public ApiResponse<CatalogAdminUseCase.ContentAdminView> updateContent(
            @PathVariable long contentId,
            @Valid @RequestBody ContentRequest request
    ) {
        StpAdminKit.requirePermission("content:write");
        return ApiResponse.ok(catalog.saveContent(request.command(contentId)));
    }

    @DeleteMapping("/contents/{contentId}")
    public ApiResponse<Void> deleteContent(@PathVariable long contentId) {
        StpAdminKit.requirePermission("content:write");
        catalog.deleteContent(contentId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/assets")
    public ApiResponse<List<CatalogAssetUseCase.AssetView>> assets() {
        StpAdminKit.requireAnyPermission("catalog:read", "content:write");
        return ApiResponse.ok(assets.assets());
    }

    @PostMapping(value = "/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<CatalogAssetUseCase.AssetView> uploadAsset(
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        StpAdminKit.requireAnyPermission("catalog:write", "content:write");
        return ApiResponse.ok(assets.upload(
                StpAdminKit.logic().getLoginIdAsLong(),
                new CatalogAssetUseCase.UploadAssetCommand(file.getOriginalFilename(), file.getBytes())
        ));
    }

    @DeleteMapping("/assets/{assetId}")
    public ApiResponse<Void> deleteAsset(
            @PathVariable long assetId,
            @Valid @RequestBody DeleteAssetRequest request
    ) {
        StpAdminKit.requireAnyPermission("catalog:write", "content:write");
        assets.delete(StpAdminKit.logic().getLoginIdAsLong(), assetId, request.reason());
        return ApiResponse.ok(null);
    }

    public record CategoryRequest(Long parentId, @NotBlank String name, @NotBlank String code,
                                  int sortOrder, @NotBlank String status) {
        SaveCategoryCommand command(Long id) {
            return new SaveCategoryCommand(id, parentId, name, code, sortOrder, status);
        }
    }

    public record ProductRequest(
            @Min(1) long categoryId,
            @NotBlank String name,
            String subtitle,
            String coverUrl,
            String descriptionHtml,
            @NotBlank String salesScene,
            @NotBlank String status,
            int sortOrder,
            Long skuId,
            @NotBlank String skuCode,
            @NotBlank String skuName,
            @Min(0) long priceFen,
            Long marketPriceFen,
            String attributesJson,
            @NotBlank String skuStatus,
            @Min(0) int initialInventory
    ) {
        SaveProductCommand command(Long productId) {
            return new SaveProductCommand(
                    productId, categoryId, name, subtitle, coverUrl, descriptionHtml, salesScene, status,
                    sortOrder, skuId, skuCode, skuName, priceFen, marketPriceFen, attributesJson,
                    skuStatus, initialInventory
            );
        }
    }

    public record InventoryRequest(@Min(0) int afterQuantity, @NotBlank String reason,
                                   @NotBlank String requestId) {
    }

    public record DeleteAssetRequest(@NotBlank String reason) {
    }

    public record ContentRequest(
            @NotBlank String contentType,
            @NotBlank String title,
            String summary,
            String coverUrl,
            String targetUrl,
            String bodyHtml,
            @NotBlank String status,
            int sortOrder
    ) {
        SaveContentCommand command(Long id) {
            return new SaveContentCommand(
                    id, contentType, title, summary, coverUrl, targetUrl, bodyHtml, status, sortOrder
            );
        }
    }
}

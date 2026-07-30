package com.marketshop.interfaces.commerce;

import com.marketshop.application.catalog.CatalogAssetUseCase;
import com.marketshop.application.commerce.CommerceUseCase;
import com.marketshop.interfaces.shared.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final CommerceUseCase commerce;
    private final CatalogAssetUseCase assets;

    public CatalogController(CommerceUseCase commerce, CatalogAssetUseCase assets) {
        this.commerce = commerce;
        this.assets = assets;
    }

    @GetMapping("/catalog/products")
    public ApiResponse<List<CommerceUseCase.ProductView>> products() {
        return ApiResponse.ok(commerce.products());
    }

    @GetMapping("/catalog/products/{productId}")
    public ApiResponse<CommerceUseCase.ProductDetail> product(@PathVariable long productId) {
        return ApiResponse.ok(commerce.product(productId));
    }

    @GetMapping("/catalog/categories")
    public ApiResponse<List<CommerceUseCase.CategoryView>> categories() {
        return ApiResponse.ok(commerce.categories());
    }

    @GetMapping("/content")
    public ApiResponse<List<CommerceUseCase.ContentView>> contents() {
        return ApiResponse.ok(commerce.contents());
    }

    @GetMapping("/content/{contentId}")
    public ApiResponse<CommerceUseCase.ContentView> content(@PathVariable long contentId) {
        return ApiResponse.ok(commerce.content(contentId));
    }

    @GetMapping("/catalog/assets/{assetId}")
    public ResponseEntity<byte[]> asset(@PathVariable long assetId) {
        CatalogAssetUseCase.AssetContent content = assets.content(assetId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mediaType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(content.bytes());
    }
}

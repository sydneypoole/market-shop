package com.marketshop.interfaces.shared;

import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsMissingResourcesToNotFound() {
        var response = handler.handleDomain(new DomainException("CATALOG_RESOURCE_NOT_FOUND", "商品素材不存在"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("CATALOG_RESOURCE_NOT_FOUND");
    }

    @Test
    void mapsPermissionFailuresToForbidden() {
        var response = handler.handleDomain(new DomainException("ADMIN_PERMISSION_DENIED", "当前账号无权限"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void keepsStateConflictsAsConflict() {
        var response = handler.handleDomain(new DomainException("ORDER_STATE_CONFLICT", "订单状态不允许操作"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void mapsUploadFailuresToActionableHttpStatuses() {
        assertThat(handler.handleDomain(new DomainException(
                "CATALOG_ASSET_SIZE_INVALID", "图片太大"
        )).getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(handler.handleDomain(new DomainException(
                "CATALOG_ASSET_IMAGE_INVALID", "图片损坏"
        )).getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(handler.handleDomain(new DomainException(
                "CATALOG_ASSET_STORAGE_FAILED", "存储不可用"
        )).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(handler.handleDomain(new DomainException(
                "CATALOG_ASSET_CONTENT_REQUIRED", "请选择图片"
        )).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.handleMissingUpload(
                new MissingServletRequestPartException("file")
        ).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void mapsMalformedTemplateConfigurationToBadRequest() {
        var response = handler.handleDomain(new DomainException(
                "STOREFRONT_TEMPLATE_CONFIG_INVALID", "模板配置不是有效 JSON"
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

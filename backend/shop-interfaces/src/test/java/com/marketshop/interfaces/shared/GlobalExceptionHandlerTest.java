package com.marketshop.interfaces.shared;

import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

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
}

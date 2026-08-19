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
    void mapsInactiveAccountsAndCrossOriginWritesToForbidden() {
        assertThat(handler.handleDomain(new DomainException(
                "MEMBER_DISABLED", "会员已停用"
        )).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(handler.handleDomain(new DomainException(
                "CROSS_ORIGIN_WRITE_DENIED", "跨站修改请求已拒绝"
        )).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void keepsStateConflictsAsConflict() {
        var response = handler.handleDomain(new DomainException("ORDER_STATE_CONFLICT", "订单状态不允许操作"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.handleDomain(new DomainException(
                "MEMBER_PROFILE_CONFLICT", "会员资料已变更"
        )).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.handleDomain(new DomainException(
                "MEMBER_NICKNAME_INVALID", "会员昵称无效"
        )).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void mapsWechatExchangeFailuresToBadGateway() {
        var response = handler.handleDomain(new DomainException(
                "WECHAT_CODE_EXCHANGE_FAILED", "微信登录失败，请重试"
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("WECHAT_CODE_EXCHANGE_FAILED");
        assertThat(response.getBody().message()).isEqualTo("微信登录失败，请重试");

        assertThat(handler.handleDomain(new DomainException(
                "WECHAT_PHONE_EXCHANGE_FAILED", "微信手机号验证失败"
        )).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(handler.handleDomain(new DomainException(
                "WECHAT_WXACODE_FAILED", "邀请二维码生成失败，请稍后重试"
        )).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(handler.handleDomain(new DomainException(
                "WECHAT_PHONE_CODE_INVALID", "微信手机号授权已过期"
        )).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.handleDomain(new DomainException(
                "INVITATION_WXACODE_UNSUPPORTED", "当前邀请码无法生成小程序码"
        )).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.handleDomain(new DomainException(
                "INVITATION_NOT_FOUND", "当前没有可用的邀请码"
        )).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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
        assertThat(handler.handleDomain(new DomainException(
                "AVATAR_READ_FAILED", "头像存储读取失败"
        )).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(handler.handleMissingUpload(
                new MissingServletRequestPartException("file")
        ).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

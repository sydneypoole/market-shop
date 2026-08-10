package com.marketshop.interfaces.shared;

import com.marketshop.domain.shared.DomainException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.SaTokenException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiResponse<Void>> handleDomain(DomainException exception) {
        HttpStatus status = domainStatus(exception.code());
        if (status.is5xxServerError()) {
            log.error("Domain operation failed requestId={} code={} status={}",
                    requestId(), exception.code(), status.value(), exception);
        } else {
            log.warn("Business request rejected requestId={} code={} status={} message={}",
                    requestId(), exception.code(), status.value(), exception.getMessage());
        }
        return ResponseEntity.status(status)
                .body(ApiResponse.failure(exception.code(), exception.getMessage()));
    }

    private static HttpStatus domainStatus(String code) {
        if (code == null) {
            return HttpStatus.CONFLICT;
        }
        if (code.endsWith("_NOT_FOUND")) {
            return HttpStatus.NOT_FOUND;
        }
        if (code.endsWith("_ACCESS_DENIED") || code.endsWith("_PERMISSION_DENIED")
                || code.endsWith("_DELETE_DENIED") || code.endsWith("_ACTOR_INVALID")
                || "OBJECT_SIGNING_INVALID".equals(code)
                || "CROSS_ORIGIN_WRITE_DENIED".equals(code)
                || "MEMBER_DISABLED".equals(code) || "MEMBER_LOCKED".equals(code)
                || "ADMIN_DISABLED".equals(code) || "ADMIN_LOCKED".equals(code)) {
            return HttpStatus.FORBIDDEN;
        }
        if (code.endsWith("_SIZE_INVALID")) {
            return HttpStatus.CONTENT_TOO_LARGE;
        }
        if ("WECHAT_CODE_EXCHANGE_FAILED".equals(code)) {
            return HttpStatus.BAD_GATEWAY;
        }
        if (code.endsWith("_TYPE_INVALID") || code.endsWith("_MEDIA_INVALID")
                || code.endsWith("_IMAGE_INVALID")) {
            return HttpStatus.UNSUPPORTED_MEDIA_TYPE;
        }
        if (code.startsWith("OBJECT_") && code.endsWith("_FAILED")
                || code.startsWith("CATALOG_ASSET_") && (
                code.endsWith("_STORAGE_FAILED") || code.endsWith("_READ_FAILED")
                        || code.endsWith("_DELETE_FAILED"))) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (code.endsWith("_INVALID") || code.endsWith("_REQUIRED")
                || code.endsWith("_MALFORMED")) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.CONFLICT;
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception exception) {
        log.warn("Request validation failed requestId={} exception={}",
                requestId(), exception.getClass().getSimpleName());
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("VALIDATION_FAILED", "请求参数校验失败"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException exception) {
        log.warn("Upload too large requestId={}", requestId());
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(ApiResponse.failure("UPLOAD_SIZE_EXCEEDED", "上传文件超过服务器允许的大小"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadMedia(HttpMediaTypeNotSupportedException exception) {
        log.warn("Upload media rejected requestId={} exception={}",
                requestId(), exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.failure("UPLOAD_MEDIA_INVALID", "请使用 multipart/form-data 上传受支持的图片"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingUpload(MissingServletRequestPartException exception) {
        log.warn("Upload content missing requestId={} part={}", requestId(), exception.getRequestPartName());
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("UPLOAD_CONTENT_REQUIRED", "请选择需要上传的文件"));
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotLogin(NotLoginException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.failure("NOT_LOGGED_IN", "请先登录"));
    }

    @ExceptionHandler(SaTokenException.class)
    public ResponseEntity<ApiResponse<Void>> handleSaToken(SaTokenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.failure("ACCESS_DENIED", "无权访问此资源"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("Unexpected request failure requestId={}", requestId(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure("INTERNAL_ERROR", "系统暂时不可用"));
    }

    private static String requestId() {
        String value = MDC.get("requestId");
        return value == null ? "unknown" : value;
    }
}

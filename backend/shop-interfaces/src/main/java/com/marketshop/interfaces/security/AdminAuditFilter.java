package com.marketshop.interfaces.security;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.audit.AdminAuditPort.AuditRecord;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Component
public class AdminAuditFilter extends OncePerRequestFilter {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Logger log = LoggerFactory.getLogger(AdminAuditFilter.class);

    private final AdminAuditPort auditPort;

    public AdminAuditFilter(AdminAuditPort auditPort) {
        this.auditPort = auditPort;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/admin/")
                || !MUTATING_METHODS.contains(request.getMethod())
                || request.getRequestURI().equals("/api/v1/admin/auth/login");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Object correlated = request.getAttribute(RequestLoggingFilter.REQUEST_ID_ATTRIBUTE);
        String requestId = correlated == null ? UUID.randomUUID().toString() : correlated.toString();
        response.setHeader("X-Request-Id", requestId);
        filterChain.doFilter(request, response);
        if (response.getStatus() < 400 && StpAdminKit.logic().isLogin()) {
            String uri = request.getRequestURI();
            try {
                auditPort.record(new AuditRecord(
                        "ADMIN",
                        String.valueOf(StpAdminKit.logic().getLoginIdAsLong()),
                        request.getMethod() + " " + uri,
                        resourceType(uri),
                        uri,
                        null,
                        null,
                        null,
                        requestId,
                        maskIp(request.getRemoteAddr()),
                        summarize(request.getHeader("User-Agent")),
                        Instant.now()
                ));
            } catch (RuntimeException exception) {
                log.error("Admin audit persistence failed requestId={} method={} path={}",
                        requestId, request.getMethod(), uri, exception);
            }
        }
    }

    private static String resourceType(String uri) {
        if (uri.contains("/orders")) {
            return "ORDER";
        }
        if (uri.contains("/after-sales")) {
            return "AFTERSALE";
        }
        if (uri.contains("/catalog")) {
            return "CATALOG";
        }
        if (uri.contains("/storefront/templates")) {
            return "STOREFRONT_TEMPLATE";
        }
        if (uri.contains("/rules")) {
            return "RULE";
        }
        if (uri.contains("/outbox")) {
            return "OUTBOX_EVENT";
        }
        if (uri.contains("/accounts") || uri.contains("/roles")) {
            return "ADMIN_ACCOUNT";
        }
        if (uri.contains("/audit")) {
            return "AUDIT";
        }
        return "ADMIN_RESOURCE";
    }

    private static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        if (ip.contains(".")) {
            int lastDot = ip.lastIndexOf('.');
            return ip.substring(0, lastDot + 1) + "0";
        }
        int separator = ip.lastIndexOf(':');
        return separator > 0 ? ip.substring(0, separator) + ":0" : ip;
    }

    private static String summarize(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.substring(0, Math.min(userAgent.length(), 255));
    }
}

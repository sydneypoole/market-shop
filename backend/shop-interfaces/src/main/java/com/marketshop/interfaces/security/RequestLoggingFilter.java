package com.marketshop.interfaces.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTRIBUTE = RequestLoggingFilter.class.getName() + ".requestId";
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = requestId(request.getHeader("X-Request-Id"));
        long startedAt = System.nanoTime();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader("X-Request-Id", requestId);
        MDC.put("requestId", requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            String path = request.getRequestURI();
            if (path.startsWith("/actuator/health")) {
                log.debug("HTTP request method={} path={} status={} durationMs={} requestId={}",
                        request.getMethod(), path, response.getStatus(), durationMs, requestId);
            } else {
                log.info("HTTP request method={} path={} status={} durationMs={} requestId={}",
                        request.getMethod(), path, response.getStatus(), durationMs, requestId);
            }
            MDC.remove("requestId");
        }
    }

    private static String requestId(String candidate) {
        if (candidate != null) {
            String value = candidate.trim();
            if (!value.isEmpty() && value.length() <= 128 && value.matches("[A-Za-z0-9._-]+")) {
                return value;
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}

package com.marketshop.interfaces.security;

import com.marketshop.domain.shared.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URI;
import java.util.Locale;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rejects browser credentialed writes from another origin. Requests without an
 * Origin header remain available to trusted non-browser clients.
 */
final class SameOriginWriteInterceptor implements HandlerInterceptor {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final Set<OriginKey> additionalOrigins;

    SameOriginWriteInterceptor() {
        this(Set.of());
    }

    SameOriginWriteInterceptor(String configuredOrigins) {
        this(parseConfiguredOrigins(configuredOrigins));
    }

    SameOriginWriteInterceptor(Collection<String> configuredOrigins) {
        this.additionalOrigins = configuredOrigins == null
                ? Set.of()
                : configuredOrigins.stream()
                .map(SameOriginWriteInterceptor::parseConfiguredOrigin)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!MUTATING_METHODS.contains(request.getMethod())) {
            return true;
        }
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        OriginKey supplied = parseRequestOrigin(origin);
        if (!sameOrigin(origin, request) && (supplied == null || !additionalOrigins.contains(supplied))) {
            throw new DomainException("CROSS_ORIGIN_WRITE_DENIED", "跨站修改请求已拒绝");
        }
        return true;
    }

    static boolean sameOrigin(String origin, HttpServletRequest request) {
        try {
            OriginKey supplied = parseRequestOrigin(origin);
            if (supplied == null) {
                return false;
            }
            String requestScheme = request.getScheme().toLowerCase(Locale.ROOT);
            return supplied.scheme().equalsIgnoreCase(requestScheme)
                    && supplied.host().equalsIgnoreCase(request.getServerName())
                    && supplied.port()
                    == effectivePort(requestScheme, request.getServerPort());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static Set<String> parseConfiguredOrigins(String configuredOrigins) {
        if (configuredOrigins == null || configuredOrigins.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static OriginKey parseConfiguredOrigin(String value) {
        OriginKey parsed = parseOrigin(value, true);
        if (parsed == null) {
            throw new IllegalArgumentException("Invalid additional write origin");
        }
        return parsed;
    }

    private static OriginKey parseRequestOrigin(String value) {
        return parseOrigin(value, false);
    }

    private static OriginKey parseOrigin(String value, boolean allowRootPath) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0
                || value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            return null;
        }
        try {
            URI origin = URI.create(value.trim());
            String scheme = origin.getScheme();
            String host = origin.getHost();
            String path = origin.getRawPath();
            if (scheme == null || host == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || origin.isOpaque()
                    || origin.getRawUserInfo() != null
                    || (path != null && !path.isEmpty() && !(allowRootPath && "/".equals(path)))
                    || origin.getRawQuery() != null || origin.getRawFragment() != null) {
                return null;
            }
            return new OriginKey(
                    scheme.toLowerCase(Locale.ROOT),
                    host.toLowerCase(Locale.ROOT),
                    effectivePort(scheme, origin.getPort())
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record OriginKey(String scheme, String host, int port) {
    }

    private static int effectivePort(String scheme, int port) {
        if (port >= 0) {
            return port;
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            return 80;
        }
        return -1;
    }
}

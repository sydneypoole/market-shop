package com.marketshop.interfaces.security;

import cn.dev33.satoken.interceptor.SaInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
public class SaTokenSecurityConfiguration implements WebMvcConfigurer {

    private final AccountSessionEpochGuard sessionEpochGuard;
    private final String additionalWriteOrigins;

    public SaTokenSecurityConfiguration(
            @Value("${market-shop.security.secure-cookie:false}") boolean secureCookie,
            AccountSessionEpochGuard sessionEpochGuard
    ) {
        this(secureCookie, sessionEpochGuard, "");
    }

    @Autowired
    public SaTokenSecurityConfiguration(
            @Value("${market-shop.security.secure-cookie:false}") boolean secureCookie,
            AccountSessionEpochGuard sessionEpochGuard,
            @Value("${market-shop.security.additional-write-origins:}") String additionalWriteOrigins
    ) {
        this.sessionEpochGuard = sessionEpochGuard;
        this.additionalWriteOrigins = additionalWriteOrigins == null ? "" : additionalWriteOrigins;
        StpUserKit.configureCookie(secureCookie);
        StpAdminKit.configureCookie(secureCookie);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SameOriginWriteInterceptor(additionalWriteOrigins))
                .addPathPatterns("/api/**")
                .order(Ordered.HIGHEST_PRECEDENCE);
        registry.addInterceptor(new SaInterceptor(handle -> sessionEpochGuard.requireMemberSession()))
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        "/api/v1/auth/wechat/**",
                        "/api/v1/auth/dev-login",
                        "/api/v1/admin/**",
                        "/api/v1/catalog/**",
                        "/api/v1/content/**",
                        "/api/v1/rules/**",
                        "/api/v1/storage/private/**",
                        "/api/v1/system/**"
                );
        registry.addInterceptor(new SaInterceptor(handle -> sessionEpochGuard.requireAdminSession()))
                .addPathPatterns("/api/v1/admin/**")
                .excludePathPatterns("/api/v1/admin/auth/login");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> allowedOrigins = new ArrayList<>();
        if (additionalWriteOrigins != null && !additionalWriteOrigins.isBlank()) {
            allowedOrigins.addAll(Arrays.stream(additionalWriteOrigins.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(value -> value.endsWith("/") ? value.substring(0, value.length() - 1) : value)
                    .toList());
        }
        var mapping = registry.addMapping("/api/**");
        // CorsRegistration starts with the framework's permit-all origin.  It
        // is incompatible with credentialed requests and, more importantly,
        // would make a cross-origin read permissive when no explicit origin is
        // configured.  Always replace that default, including with an empty
        // list for the production single-origin deployment.
        mapping.allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}

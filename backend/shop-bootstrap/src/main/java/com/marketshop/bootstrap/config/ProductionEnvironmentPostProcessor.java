package com.marketshop.bootstrap.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ProductionEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        ProductionRuntimeProperties properties = Binder.get(environment)
                .bind("market-shop.production", Bindable.of(ProductionRuntimeProperties.class))
                .orElseThrow(() -> new IllegalStateException(
                        "Production configuration rejected: market-shop.production is missing"
                ));
        ProductionRuntimeProperties.validateAdditionalWriteOrigins(
                environment.getProperty("market-shop.security.additional-write-origins", "")
        );
        validateS3BackendMode(
                environment.getProperty(
                        "MARKET_SHOP_S3_BACKEND_MODE",
                        environment.getProperty("market-shop.s3-backend-mode", "external")
                ),
                environment.getProperty(
                        "MARKET_SHOP_STORAGE_PROVIDER",
                        environment.getProperty("market-shop.production.storage.provider", "local")
                ),
                true
        );
        Set<String> profiles = new LinkedHashSet<>(Arrays.asList(environment.getActiveProfiles()));
        properties.validate(profiles);
    }

    static void validateS3BackendMode(String mode, String provider) {
        validateS3BackendMode(mode, provider, false);
    }

    static void validateS3BackendMode(String mode, String provider, boolean productionProfile) {
        String normalizedMode = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("bundled", "external").contains(normalizedMode)) {
            throw new IllegalStateException(
                    "Production configuration rejected: MARKET_SHOP_S3_BACKEND_MODE must be bundled or external"
            );
        }
        if ("bundled".equals(normalizedMode) && !"s3".equals(normalizedProvider)) {
            throw new IllegalStateException(
                    "Production configuration rejected: bundled S3 mode requires MARKET_SHOP_STORAGE_PROVIDER=s3"
            );
        }
        if (productionProfile && "bundled".equals(normalizedMode)) {
            throw new IllegalStateException(
                    "Production configuration rejected: MARKET_SHOP_S3_BACKEND_MODE=bundled is reserved for local/e2e; prod requires external object snapshot hooks"
            );
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

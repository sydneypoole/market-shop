package com.marketshop.bootstrap.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionEnvironmentPostProcessorTest {

    @Test
    void bindsTypedProductionConfigurationBeforeContextStartup() {
        MockEnvironment environment = validEnvironment();

        assertThatCode(() -> new ProductionEnvironmentPostProcessor()
                .postProcessEnvironment(environment, null))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMockCapabilityWithoutIncludingSecretValues() {
        MockEnvironment environment = validEnvironment()
                .withProperty("market-shop.production.wechat.mock-enabled", "true");

        assertThatThrownBy(() -> new ProductionEnvironmentPostProcessor()
                .postProcessEnvironment(environment, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MARKET_SHOP_WECHAT_MOCK_ENABLED")
                .hasMessageNotContaining("strong-database-password");
    }

    @Test
    void rejectsNonHttpsAdditionalWriteOriginBeforeContextStartup() {
        MockEnvironment environment = validEnvironment()
                .withProperty("market-shop.security.additional-write-origins", "http://localhost:5173");

        assertThatThrownBy(() -> new ProductionEnvironmentPostProcessor()
                .postProcessEnvironment(environment, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MARKET_SHOP_ADDITIONAL_WRITE_ORIGINS");
    }

    @Test
    void rejectsProductionBucketCreationFlagBeforeContextStartup() {
        MockEnvironment environment = validEnvironment()
                .withProperty("market-shop.production.storage.create-bucket", "true");

        assertThatThrownBy(() -> new ProductionEnvironmentPostProcessor()
                .postProcessEnvironment(environment, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MARKET_SHOP_STORAGE_CREATE_BUCKET");
    }

    @Test
    void rejectsUnknownOrMisappliedS3BackendModes() {
        assertThatThrownBy(() -> ProductionEnvironmentPostProcessor.validateS3BackendMode(
                "sidecar", "s3"
        )).hasMessageContaining("MARKET_SHOP_S3_BACKEND_MODE");
        assertThatThrownBy(() -> ProductionEnvironmentPostProcessor.validateS3BackendMode(
                "bundled", "local"
        )).hasMessageContaining("bundled S3 mode");
        assertThatCode(() -> ProductionEnvironmentPostProcessor.validateS3BackendMode(
                "external", "local"
        )).doesNotThrowAnyException();
        assertThatCode(() -> ProductionEnvironmentPostProcessor.validateS3BackendMode(
                "bundled", "s3", false
        )).doesNotThrowAnyException();
        assertThatThrownBy(() -> ProductionEnvironmentPostProcessor.validateS3BackendMode(
                "bundled", "s3", true
        )).hasMessageContaining("reserved for local/e2e");
    }

    @Test
    void rejectsBundledModeBeforeProductionContextStartup() {
        MockEnvironment environment = validEnvironment()
                .withProperty("MARKET_SHOP_S3_BACKEND_MODE", "bundled")
                .withProperty("MARKET_SHOP_STORAGE_PROVIDER", "s3");

        assertThatThrownBy(() -> new ProductionEnvironmentPostProcessor()
                .postProcessEnvironment(environment, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reserved for local/e2e");
    }

    @Test
    void bindsWechatCallbackBaseUrlAliasBeforeProductionContextStartup() {
        MockEnvironment environment = validEnvironment()
                .withProperty("market-shop.production.wechat.enabled", "true")
                .withProperty("market-shop.production.wechat.official-account-app-id", "oa-app-id")
                .withProperty(
                        "market-shop.production.wechat.official-account-secret",
                        "strong-official-secret-123456"
                )
                .withProperty("market-shop.production.wechat.website-app-id", "web-app-id")
                .withProperty(
                        "market-shop.production.wechat.website-secret",
                        "strong-website-secret-123456"
                )
                // This is the typed ProductionRuntimeProperties alias. The
                // OAuth-facing market-shop.wechat.oauth-callback-base-url
                // remains a separate key consumed by AuthApplicationService.
                .withProperty(
                        "market-shop.production.wechat.callback-base-url",
                        "https://shop.acme.internal"
                )
                .withProperty(
                        "market-shop.production.wechat.storefront-base-url",
                        "https://shop.acme.internal"
                );

        assertThatCode(() -> new ProductionEnvironmentPostProcessor()
                .postProcessEnvironment(environment, null))
                .doesNotThrowAnyException();
    }

    private static MockEnvironment validEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment
                .withProperty("market-shop.production.validation-enabled", "true")
                .withProperty("market-shop.production.secure-cookie", "true")
                .withProperty("market-shop.production.database.password", "strong-database-password-123456")
                .withProperty("market-shop.production.redis.password", "strong-redis-password-123456")
                .withProperty("market-shop.production.storage.provider", "local")
                .withProperty(
                        "market-shop.production.storage.local-signing-secret",
                        "strong-local-signing-secret-1234567890"
                )
                .withProperty("market-shop.production.wechat.enabled", "false")
                .withProperty("market-shop.production.wechat.mock-enabled", "false")
                .withProperty("market-shop.production.bootstrap-admin.enabled", "false");
    }
}

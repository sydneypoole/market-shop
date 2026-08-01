package com.marketshop.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionRuntimePropertiesTest {

    @Test
    void acceptsStrongLocalProductionConfiguration() {
        assertThatCode(() -> validLocal().validate(Set.of("prod")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMockProfilePlaceholderSecretsAndInsecureCookie() {
        ProductionRuntimeProperties properties = new ProductionRuntimeProperties(
                true,
                false,
                new ProductionRuntimeProperties.Credential("CHANGE_ME_database_password"),
                new ProductionRuntimeProperties.Credential("strong-redis-password-123456"),
                validLocal().storage(),
                validLocal().wechat(),
                validLocal().bootstrapAdmin()
        );

        assertThatThrownBy(() -> properties.validate(Set.of("prod", "local")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod cannot be combined");
        assertThatThrownBy(() -> properties.validate(Set.of("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MARKET_SHOP_COOKIE_SECURE")
                .hasMessageNotContaining("CHANGE_ME_database_password");
    }

    @Test
    void rejectsMockLoginAndHttpOauthInProduction() {
        ProductionRuntimeProperties valid = validLocal();
        ProductionRuntimeProperties mockEnabled = new ProductionRuntimeProperties(
                true,
                true,
                valid.database(),
                valid.redis(),
                valid.storage(),
                new ProductionRuntimeProperties.Wechat(
                        false,
                        true,
                        "",
                        "",
                        "",
                        "",
                        "http://localhost:8080",
                        "http://localhost:5173"
                ),
                valid.bootstrapAdmin()
        );

        assertThatThrownBy(() -> mockEnabled.validate(Set.of("prod")))
                .hasMessageContaining("MARKET_SHOP_WECHAT_MOCK_ENABLED");

        ProductionRuntimeProperties httpCallback = new ProductionRuntimeProperties(
                true,
                true,
                valid.database(),
                valid.redis(),
                valid.storage(),
                new ProductionRuntimeProperties.Wechat(
                        true,
                        false,
                        "oa-app-id",
                        "strong-official-secret-123456",
                        "web-app-id",
                        "strong-website-secret-123456",
                        "http://shop.acme.internal",
                        "https://shop.acme.internal"
                ),
                valid.bootstrapAdmin()
        );
        assertThatThrownBy(() -> httpCallback.validate(Set.of("prod")))
                .hasMessageContaining("MARKET_SHOP_WECHAT_CALLBACK_BASE_URL");
    }

    @Test
    void rejectsOriginCredentialsQueryFragmentAndNonRootPaths() {
        for (String invalidOrigin : List.of(
                "https://user:password@shop.acme.internal",
                "https://shop.acme.internal/oauth/callback",
                "https://shop.acme.internal?tenant=one",
                "https://shop.acme.internal#fragment"
        )) {
            ProductionRuntimeProperties properties = withWechatOrigins(invalidOrigin, "https://shop.acme.internal");

            assertThatThrownBy(() -> properties.validate(Set.of("prod")))
                    .as(invalidOrigin)
                    .hasMessageContaining("MARKET_SHOP_WECHAT_CALLBACK_BASE_URL");
        }

        assertThatCode(() -> withWechatOrigins(
                "https://api.shop.acme.internal:8443/",
                "https://shop.acme.internal/"
        ).validate(Set.of("prod"))).doesNotThrowAnyException();
    }

    @Test
    void requiresHttpsAndStrongCredentialsForS3() {
        ProductionRuntimeProperties valid = validLocal();
        ProductionRuntimeProperties properties = new ProductionRuntimeProperties(
                true,
                true,
                valid.database(),
                valid.redis(),
                new ProductionRuntimeProperties.Storage(
                        ProductionRuntimeProperties.StorageProvider.S3,
                        "http://rustfs:9000",
                        "access-key",
                        "strong-storage-secret-with-32-characters",
                        "market-shop-private",
                        "",
                        false
                ),
                valid.wechat(),
                valid.bootstrapAdmin()
        );

        assertThatThrownBy(() -> properties.validate(Set.of("prod")))
                .hasMessageContaining("MARKET_SHOP_RUSTFS_ENDPOINT");
    }

    @Test
    void rejectsLocalFixturePasswordsEvenWhenTheyMeetTheLengthFloor() {
        ProductionRuntimeProperties valid = validLocal();
        ProductionRuntimeProperties fixture = new ProductionRuntimeProperties(
                true,
                true,
                new ProductionRuntimeProperties.Credential("local_root_password_please_replace"),
                valid.redis(),
                valid.storage(),
                valid.wechat(),
                valid.bootstrapAdmin()
        );

        assertThatThrownBy(() -> fixture.validate(Set.of("prod")))
                .hasMessageContaining("MARKET_SHOP_DB_PASSWORD");
    }

    @Test
    void rejectsImplicitBucketCreationInProduction() {
        ProductionRuntimeProperties valid = validLocal();
        ProductionRuntimeProperties properties = new ProductionRuntimeProperties(
                true,
                true,
                valid.database(),
                valid.redis(),
                new ProductionRuntimeProperties.Storage(
                        valid.storage().provider(),
                        valid.storage().endpoint(),
                        valid.storage().accessKey(),
                        valid.storage().secretKey(),
                        valid.storage().bucket(),
                        valid.storage().localSigningSecret(),
                        true
                ),
                valid.wechat(),
                valid.bootstrapAdmin()
        );

        assertThatThrownBy(() -> properties.validate(Set.of("prod")))
                .hasMessageContaining("MARKET_SHOP_STORAGE_CREATE_BUCKET");
    }

    @Test
    void additionalProductionWriteOriginsAreExplicitHttpsOrigins() {
        assertThatCode(() -> ProductionRuntimeProperties.validateAdditionalWriteOrigins(
                "https://shop.acme.internal,https://admin.acme.internal:8443/"
        )).doesNotThrowAnyException();

        for (String invalid : new String[]{
                "http://shop.acme.internal",
                "https://shop.acme.internal/path",
                "https://shop.acme.internal?tenant=one",
                "https://user:password@shop.acme.internal",
                "https://*.acme.internal",
                "https://shop.acme.internal,"
        }) {
            assertThatThrownBy(() -> ProductionRuntimeProperties.validateAdditionalWriteOrigins(invalid))
                    .as(invalid)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MARKET_SHOP_ADDITIONAL_WRITE_ORIGINS");
        }
    }

    private static ProductionRuntimeProperties validLocal() {
        return new ProductionRuntimeProperties(
                true,
                true,
                new ProductionRuntimeProperties.Credential("strong-database-password-123456"),
                new ProductionRuntimeProperties.Credential("strong-redis-password-123456"),
                new ProductionRuntimeProperties.Storage(
                        ProductionRuntimeProperties.StorageProvider.LOCAL,
                        "",
                        "",
                        "",
                        "",
                        "strong-local-signing-secret-1234567890",
                        false
                ),
                new ProductionRuntimeProperties.Wechat(
                        false,
                        false,
                        "",
                        "",
                        "",
                        "",
                        "https://shop.acme.internal",
                        "https://shop.acme.internal"
                ),
                new ProductionRuntimeProperties.BootstrapAdmin(false, "", "", "")
        );
    }

    private static ProductionRuntimeProperties withWechatOrigins(String callbackOrigin, String storefrontOrigin) {
        ProductionRuntimeProperties valid = validLocal();
        return new ProductionRuntimeProperties(
                true,
                true,
                valid.database(),
                valid.redis(),
                valid.storage(),
                new ProductionRuntimeProperties.Wechat(
                        true,
                        false,
                        "oa-app-id",
                        "strong-official-secret-123456",
                        "web-app-id",
                        "strong-website-secret-123456",
                        callbackOrigin,
                        storefrontOrigin
                ),
                valid.bootstrapAdmin()
        );
    }
}

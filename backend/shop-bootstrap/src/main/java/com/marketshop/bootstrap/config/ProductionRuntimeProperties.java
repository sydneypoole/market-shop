package com.marketshop.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

@ConfigurationProperties("market-shop.production")
public record ProductionRuntimeProperties(
        boolean validationEnabled,
        boolean secureCookie,
        Credential database,
        Credential redis,
        Storage storage,
        Wechat wechat,
        BootstrapAdmin bootstrapAdmin
) {
    private static final Set<String> FORBIDDEN_PROFILES = Set.of("local", "dev", "test", "mock");

    public void validate(Set<String> activeProfiles) {
        if (!validationEnabled) {
            throw invalid("market-shop.production.validation-enabled must remain true in prod");
        }
        if (activeProfiles == null || !activeProfiles.contains("prod")) {
            throw invalid("prod profile must be active");
        }
        for (String profile : activeProfiles) {
            if (FORBIDDEN_PROFILES.contains(profile.toLowerCase(Locale.ROOT))) {
                throw invalid("prod cannot be combined with local, dev, test or mock profiles");
            }
        }
        if (!secureCookie) {
            throw invalid("MARKET_SHOP_COOKIE_SECURE must be true in prod");
        }

        requireCredential(database, "MARKET_SHOP_DB_PASSWORD", 16);
        requireCredential(redis, "MARKET_SHOP_REDIS_PASSWORD", 16);
        requireSection(storage, "market-shop.production.storage");
        requireSection(wechat, "market-shop.production.wechat");
        requireSection(bootstrapAdmin, "market-shop.production.bootstrap-admin");

        if (storage.provider() == null) {
            throw invalid("MARKET_SHOP_STORAGE_PROVIDER must be local or s3");
        }
        if (storage.createBucket()) {
            throw invalid("MARKET_SHOP_STORAGE_CREATE_BUCKET must be false in prod");
        }
        if (storage.provider() == StorageProvider.LOCAL) {
            requireSecret(storage.localSigningSecret(), "MARKET_SHOP_LOCAL_STORAGE_SIGNING_SECRET", 32);
        } else {
            requireSecret(storage.accessKey(), "MARKET_SHOP_RUSTFS_ACCESS_KEY", 3);
            requireSecret(storage.secretKey(), "MARKET_SHOP_RUSTFS_SECRET_KEY", 32);
            requireSecret(storage.bucket(), "MARKET_SHOP_RUSTFS_BUCKET", 3);
            requireHttpsOrigin(storage.endpoint(), "MARKET_SHOP_RUSTFS_ENDPOINT");
        }

        if (wechat.mockEnabled()) {
            throw invalid("MARKET_SHOP_WECHAT_MOCK_ENABLED must be false in prod");
        }
        if (wechat.enabled()) {
            requireHttpsOrigin(wechat.callbackBaseUrl(), "MARKET_SHOP_WECHAT_CALLBACK_BASE_URL");
            requireHttpsOrigin(wechat.storefrontBaseUrl(), "MARKET_SHOP_STOREFRONT_BASE_URL");
            requireSecret(wechat.officialAccountAppId(), "MARKET_SHOP_WECHAT_OA_APP_ID", 3);
            requireSecret(wechat.officialAccountSecret(), "MARKET_SHOP_WECHAT_OA_SECRET", 16);
            requireSecret(wechat.websiteAppId(), "MARKET_SHOP_WECHAT_WEB_APP_ID", 3);
            requireSecret(wechat.websiteSecret(), "MARKET_SHOP_WECHAT_WEB_SECRET", 16);
        }
        if (bootstrapAdmin.enabled()) {
            requireSecret(bootstrapAdmin.password(), "MARKET_SHOP_BOOTSTRAP_ADMIN_PASSWORD", 12);
            requireSecret(bootstrapAdmin.inviteCode(), "MARKET_SHOP_BOOTSTRAP_INVITE_CODE", 16);
            requireSecret(
                    bootstrapAdmin.sponsorClaimSecret(),
                    "MARKET_SHOP_BOOTSTRAP_SPONSOR_CLAIM_SECRET",
                    32
            );
        }
    }

    /**
     * Production write-origin exceptions are an explicit allow-list, never a
     * wildcard.  Keep this validation beside the other fail-fast production
     * checks so a typo cannot silently weaken CSRF/origin protection.
     */
    public static void validateAdditionalWriteOrigins(String configuredOrigins) {
        if (configuredOrigins == null || configuredOrigins.isBlank()) {
            return;
        }
        for (String value : configuredOrigins.split(",", -1)) {
            String origin = value.trim();
            if (origin.isEmpty()) {
                throw invalid("MARKET_SHOP_ADDITIONAL_WRITE_ORIGINS contains an empty origin");
            }
            requireHttpsOrigin(origin, "MARKET_SHOP_ADDITIONAL_WRITE_ORIGINS");
        }
    }

    private static void requireCredential(Credential credential, String key, int minimumLength) {
        requireSection(credential, key);
        requireSecret(credential.password(), key, minimumLength);
    }

    private static void requireSection(Object value, String key) {
        if (value == null) {
            throw invalid(key + " configuration is required");
        }
    }

    private static void requireSecret(String value, String key, int minimumLength) {
        if (value == null || value.length() < minimumLength || isPlaceholder(value)) {
            throw invalid(key + " must be a non-placeholder value with at least " + minimumLength + " characters");
        }
    }

    private static boolean isPlaceholder(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return normalized.isBlank()
                || normalized.contains("change_me")
                || normalized.contains("changeme")
                || normalized.contains("placeholder")
                || normalized.contains("replace_me")
                || normalized.contains("please_replace")
                || normalized.contains("example")
                || normalized.contains("fixture")
                || normalized.contains("e2e")
                || normalized.startsWith("local_")
                || normalized.contains("market_shop_dev")
                || normalized.contains("marketshop_dev")
                || normalized.equals("marketshop")
                || normalized.equals("bootstrap2026");
    }

    private static void requireHttpsOrigin(String value, String key) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            String host = uri.getHost();
            String path = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || uri.isOpaque()
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || path != null && !path.isEmpty() && !"/".equals(path)
                    || host.equalsIgnoreCase("localhost")
                    || host.equals("127.0.0.1")
                    || host.equals("::1")
                    || host.endsWith(".example")
                    || host.endsWith(".invalid")
                    || host.endsWith(".test")
                    || host.endsWith(".local")) {
                throw invalid(key + " must be a non-placeholder HTTPS origin");
            }
        } catch (IllegalArgumentException exception) {
            throw invalid(key + " must be a non-placeholder HTTPS origin");
        }
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("Production configuration rejected: " + message);
    }

    public record Credential(String password) {
    }

    public enum StorageProvider {
        LOCAL,
        S3
    }

    public record Storage(
            StorageProvider provider,
            String endpoint,
            String accessKey,
            String secretKey,
            String bucket,
            String localSigningSecret,
            boolean createBucket
    ) {
    }

    public record Wechat(
            boolean enabled,
            boolean mockEnabled,
            String officialAccountAppId,
            String officialAccountSecret,
            String websiteAppId,
            String websiteSecret,
            String callbackBaseUrl,
            String storefrontBaseUrl
    ) {
    }

    public record BootstrapAdmin(
            boolean enabled,
            String password,
            String inviteCode,
            String sponsorClaimSecret
    ) {
    }
}

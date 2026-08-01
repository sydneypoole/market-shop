package com.marketshop.application.identity;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.audit.AdminAuditPort.AuditRecord;
import com.marketshop.application.identity.IdentityPorts.OAuthStateStore;
import com.marketshop.application.identity.IdentityPorts.RegistrationResult;
import com.marketshop.application.identity.IdentityPorts.StateConsumeResult;
import com.marketshop.application.identity.IdentityPorts.StateConsumeStatus;
import com.marketshop.application.identity.IdentityPorts.StatePayload;
import com.marketshop.application.identity.IdentityPorts.UserIdentityPort;
import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.application.identity.IdentityPorts.WeChatOAuthPort;
import com.marketshop.domain.shared.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuthApplicationService implements AuthUseCase {

    private static final Duration STATE_TTL = Duration.ofMinutes(5);
    private static final Set<String> SCENES = Set.of("H5", "WEB");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final WeChatOAuthPort weChatOAuthPort;
    private final OAuthStateStore stateStore;
    private final UserIdentityPort userIdentityPort;
    private final AdminAuditPort auditPort;
    private final String callbackBaseUrl;
    private final URI storefrontOrigin;
    private final boolean mockEnabled;

    public AuthApplicationService(
            WeChatOAuthPort weChatOAuthPort,
            OAuthStateStore stateStore,
            UserIdentityPort userIdentityPort,
            AdminAuditPort auditPort,
            @Value("${market-shop.wechat.oauth-callback-base-url}") String callbackBaseUrl,
            @Value("${market-shop.wechat.storefront-base-url:${market-shop.wechat.oauth-callback-base-url}}")
            String storefrontBaseUrl,
            @Value("${market-shop.wechat.mock-enabled:false}") boolean mockEnabled
    ) {
        this.weChatOAuthPort = weChatOAuthPort;
        this.stateStore = stateStore;
        this.userIdentityPort = userIdentityPort;
        this.auditPort = auditPort;
        // Keep the provider callback an origin as well.  Production config
        // validates this at startup, but enforcing it at the application
        // boundary prevents a malformed/local override from producing an
        // attacker-controlled redirect URI.
        // WeChat may be disabled in a production profile.  Keep the bean
        // constructible in that mode even when the optional callback values
        // are omitted; the production post-processor still requires real
        // HTTPS origins whenever WeChat is enabled.
        String callback = blankOr(callbackBaseUrl, "http://localhost:8080");
        String storefront = blankOr(storefrontBaseUrl, "http://localhost:5173");
        this.callbackBaseUrl = stripTrailingSlash(requireOrigin(callback).toString());
        this.storefrontOrigin = requireOrigin(storefront);
        this.mockEnabled = mockEnabled;
    }

    @Override
    public StartResult begin(BeginCommand command) {
        if (command == null) {
            throw new DomainException("OAUTH_STATE_INVALID", "登录请求参数无效");
        }
        String scene = normalizeScene(command.scene());
        String inviteCode = trimToNull(command.inviteCode());
        String rawClaimSecret = trimToNull(command.sponsorClaimSecret());
        if (inviteCode != null && rawClaimSecret != null) {
            throw new DomainException(
                    "AUTH_CREDENTIAL_AMBIGUOUS",
                    "邀请码和发起人认领密钥不能同时提交"
            );
        }
        if (rawClaimSecret != null && rawClaimSecret.length() < SponsorClaimSecrets.MINIMUM_LENGTH) {
            throw new DomainException("SPONSOR_CLAIM_SECRET_INVALID", "发起人认领密钥无效或已使用");
        }
        String claimSecretHash = rawClaimSecret == null ? null : SponsorClaimSecrets.sha256(rawClaimSecret);
        String redirectUri = normalizeRedirect(command.redirectUri());
        String browserBindingHash = requireBindingHash(command.browserBindingHash());
        StatePayload payload = new StatePayload(scene, inviteCode, claimSecretHash, redirectUri);
        String state = stateStore.create(payload, browserBindingHash, STATE_TTL);
        String callbackUri = callbackBaseUrl + "/api/v1/auth/wechat/callback";
        return new StartResult(
                weChatOAuthPort.authorizationUrl(scene, state, callbackUri),
                state,
                STATE_TTL.toSeconds()
        );
    }

    @Override
    @Transactional
    public LoginResult complete(CompleteCommand command) {
        if (command == null || trimToNull(command.code()) == null || trimToNull(command.state()) == null) {
            throw new DomainException("OAUTH_STATE_INVALID", "登录请求已失效，请重新扫码");
        }
        String browserBinding = trimToNull(command.browserBinding());
        if (browserBinding == null) {
            throw new DomainException("OAUTH_BROWSER_MISMATCH", "微信登录请求与当前浏览器不匹配");
        }
        String bindingHash = IdentitySecretHashes.sha256(browserBinding);
        StateConsumeResult consumed = stateStore.consume(command.state().trim(), bindingHash);
        if (consumed.status() == StateConsumeStatus.BINDING_MISMATCH) {
            throw new DomainException("OAUTH_BROWSER_MISMATCH", "微信登录请求与当前浏览器不匹配");
        }
        if (consumed.status() != StateConsumeStatus.CONSUMED || consumed.payload() == null) {
            throw new DomainException("OAUTH_STATE_INVALID", "登录请求已失效，请重新扫码");
        }
        StatePayload payload = consumed.payload();
        WeChatIdentity identity = weChatOAuthPort.exchange(payload.scene(), command.code().trim());
        return authenticatedResult(
                userIdentityPort.findOrRegister(
                        identity,
                        payload.inviteCode(),
                        payload.sponsorClaimSecretHash()
                ),
                identity,
                payload.redirectUri()
        );
    }

    @Override
    @Transactional
    public LoginResult devLogin(DevLoginCommand command) {
        if (!mockEnabled) {
            throw new DomainException("DEV_LOGIN_DISABLED", "开发登录未启用");
        }
        String openId = trimToNull(command.openId());
        if (openId == null) {
            throw new DomainException("OPEN_ID_REQUIRED", "开发登录标识不能为空");
        }
        WeChatIdentity identity = new WeChatIdentity(
                "WECHAT_MOCK",
                "local",
                openId,
                "mock-union-" + openId,
                trimToNull(command.nickname()) == null ? "微信演示用户" : command.nickname().trim(),
                null
        );
        return authenticatedResult(
                userIdentityPort.findOrRegister(identity, trimToNull(command.inviteCode()), null),
                identity,
                "/"
        );
    }

    private LoginResult authenticatedResult(
            RegistrationResult result,
            WeChatIdentity identity,
            String redirectUri
    ) {
        requireActive(result.status());
        userIdentityPort.recordLogin(result.userId());
        if (result.sponsorClaimed()) {
            auditSponsorClaim(result.userId(), identity.provider(), identity.appId());
        }
        return new LoginResult(
                result.userId(),
                result.publicId(),
                result.nickname(),
                result.authEpoch(),
                result.newlyRegistered(),
                redirectUri
        );
    }

    private void auditSponsorClaim(long sponsorUserId, String provider, String appId) {
        auditPort.record(new AuditRecord(
                "USER",
                Long.toString(sponsorUserId),
                "BOOTSTRAP_SPONSOR_CLAIMED",
                "BOOTSTRAP_SPONSOR_CLAIM",
                Long.toString(sponsorUserId),
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"CLAIMED\",\"provider\":" + quote(provider)
                        + ",\"appId\":" + quote(appId) + "}",
                null,
                UUID.randomUUID().toString(),
                null,
                "oauth-application-service",
                Instant.now()
        ));
    }

    private static void requireActive(String status) {
        if ("LOCKED".equals(status)) {
            throw new DomainException("MEMBER_LOCKED", "会员账号已锁定，请联系管理员");
        }
        if (!"ACTIVE".equals(status)) {
            throw new DomainException("MEMBER_DISABLED", "会员账号已停用，请联系管理员");
        }
    }

    private static String normalizeScene(String scene) {
        String normalized = scene == null ? "" : scene.trim().toUpperCase(Locale.ROOT);
        if (!SCENES.contains(normalized)) {
            throw new DomainException("OAUTH_SCENE_INVALID", "仅支持 H5 或 WEB 微信登录");
        }
        return normalized;
    }

    private String normalizeRedirect(String redirectUri) {
        try {
            if (redirectUri == null || redirectUri.isBlank() || redirectUri.contains("\\")) {
                throw new IllegalArgumentException();
            }
            URI candidate = URI.create(redirectUri.trim());
            if (!candidate.isAbsolute()) {
                if (candidate.getRawAuthority() != null
                        || candidate.getRawPath() == null
                        || !candidate.getRawPath().startsWith("/")
                        || candidate.getRawPath().startsWith("//")) {
                    throw new IllegalArgumentException();
                }
                return relativeLocation(candidate);
            }
            if (!sameOrigin(storefrontOrigin, candidate) || candidate.getRawUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            return relativeLocation(candidate);
        } catch (RuntimeException exception) {
            throw new DomainException("REDIRECT_URI_INVALID", "登录回跳地址无效");
        }
    }

    private static String relativeLocation(URI value) {
        try {
            String path = value.getRawPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            return new URI(null, null, path, value.getRawQuery(), value.getRawFragment()).toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private static URI requireOrigin(String value) {
        try {
            String candidate = value == null ? "" : value.trim();
            if (candidate.indexOf('\\') >= 0
                    || candidate.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
                throw new IllegalArgumentException();
            }
            URI uri = URI.create(candidate);
            String path = uri.getRawPath();
            if (!uri.isAbsolute()
                    || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (path != null && !path.isBlank() && !"/".equals(path))) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Configured storefront base URL must be an HTTP(S) origin");
        }
    }

    private static boolean sameOrigin(URI configured, URI candidate) {
        return configured.getScheme().equalsIgnoreCase(candidate.getScheme())
                && configured.getHost().equalsIgnoreCase(candidate.getHost())
                && effectivePort(configured) == effectivePort(candidate);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String requireBindingHash(String value) {
        String normalized = trimToNull(value);
        if (normalized == null || !SHA_256.matcher(normalized).matches()) {
            throw new DomainException("OAUTH_BROWSER_BINDING_INVALID", "无法建立微信登录浏览器绑定");
        }
        return normalized;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

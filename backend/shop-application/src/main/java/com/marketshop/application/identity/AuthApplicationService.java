package com.marketshop.application.identity;

import com.marketshop.application.identity.IdentityPorts.OAuthStateStore;
import com.marketshop.application.identity.IdentityPorts.RegistrationResult;
import com.marketshop.application.identity.IdentityPorts.StatePayload;
import com.marketshop.application.identity.IdentityPorts.UserIdentityPort;
import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.application.identity.IdentityPorts.WeChatOAuthPort;
import com.marketshop.domain.shared.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

@Service
public class AuthApplicationService implements AuthUseCase {

    private static final Duration STATE_TTL = Duration.ofMinutes(5);
    private static final Set<String> SCENES = Set.of("H5", "WEB");

    private final WeChatOAuthPort weChatOAuthPort;
    private final OAuthStateStore stateStore;
    private final UserIdentityPort userIdentityPort;
    private final String callbackBaseUrl;
    private final boolean mockEnabled;

    public AuthApplicationService(
            WeChatOAuthPort weChatOAuthPort,
            OAuthStateStore stateStore,
            UserIdentityPort userIdentityPort,
            @Value("${market-shop.wechat.oauth-callback-base-url}") String callbackBaseUrl,
            @Value("${market-shop.wechat.mock-enabled:false}") boolean mockEnabled
    ) {
        this.weChatOAuthPort = weChatOAuthPort;
        this.stateStore = stateStore;
        this.userIdentityPort = userIdentityPort;
        this.callbackBaseUrl = callbackBaseUrl;
        this.mockEnabled = mockEnabled;
    }

    @Override
    public StartResult begin(BeginCommand command) {
        String scene = normalizeScene(command.scene());
        validateRedirect(command.redirectUri());
        StatePayload payload = new StatePayload(scene, trimToNull(command.inviteCode()), command.redirectUri());
        String state = stateStore.create(payload, STATE_TTL);
        String callbackUri = callbackBaseUrl + "/api/v1/auth/wechat/callback";
        return new StartResult(
                weChatOAuthPort.authorizationUrl(scene, state, callbackUri),
                state,
                STATE_TTL.toSeconds()
        );
    }

    @Override
    public LoginResult complete(CompleteCommand command) {
        StatePayload payload = stateStore.consume(command.state())
                .orElseThrow(() -> new DomainException("OAUTH_STATE_INVALID", "登录请求已失效，请重新扫码"));
        WeChatIdentity identity = weChatOAuthPort.exchange(payload.scene(), command.code());
        return toResult(userIdentityPort.findOrRegister(identity, payload.inviteCode()), payload.redirectUri());
    }

    @Override
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
        return toResult(userIdentityPort.findOrRegister(identity, trimToNull(command.inviteCode())), null);
    }

    private static LoginResult toResult(RegistrationResult result, String redirectUri) {
        return new LoginResult(
                result.userId(),
                result.publicId(),
                result.nickname(),
                result.newlyRegistered(),
                redirectUri
        );
    }

    private static String normalizeScene(String scene) {
        String normalized = scene == null ? "" : scene.trim().toUpperCase(Locale.ROOT);
        if (!SCENES.contains(normalized)) {
            throw new DomainException("OAUTH_SCENE_INVALID", "仅支持 H5 或 WEB 微信登录");
        }
        return normalized;
    }

    private static void validateRedirect(String redirectUri) {
        try {
            URI uri = URI.create(redirectUri);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new DomainException("REDIRECT_URI_INVALID", "登录回跳地址无效");
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

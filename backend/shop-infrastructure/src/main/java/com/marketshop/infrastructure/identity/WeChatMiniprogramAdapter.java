package com.marketshop.infrastructure.identity;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.application.identity.IdentityPorts.WeChatMiniprogramPort;
import com.marketshop.application.identity.IdentityPorts.VerifiedPhone;
import com.marketshop.application.identity.IdentityPorts.WxaCodeCommand;
import com.marketshop.application.identity.IdentityPorts.WxaCodeImage;
import com.marketshop.domain.shared.DomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class WeChatMiniprogramAdapter implements WeChatMiniprogramPort {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };
    private static final byte[] DECORATIVE_PNG = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final Pattern SCENE_SAFE =
            Pattern.compile("[0-9A-Za-z!#$&'()*+,/:;=?@._~-]{1,32}");

    private final RestClient restClient;
    private final boolean enabled;
    private final boolean mockEnabled;
    private final String appId;
    private final String secret;
    private final StringRedisTemplate redis;
    private final Object accessTokenMonitor = new Object();
    private volatile String localAccessToken;
    private volatile Instant localAccessTokenExpiresAt = Instant.EPOCH;

    @Autowired
    public WeChatMiniprogramAdapter(
            @Value("${market-shop.wechat.enabled:false}") boolean enabled,
            @Value("${market-shop.wechat.mock-enabled:false}") boolean mockEnabled,
            @Value("${market-shop.wechat.miniprogram-app-id:}") String appId,
            @Value("${market-shop.wechat.miniprogram-secret:}") String secret,
            ObjectProvider<StringRedisTemplate> redisProvider
    ) {
        this(enabled, mockEnabled, appId, secret, RestClient.create(), redisProvider.getIfAvailable());
    }

    public WeChatMiniprogramAdapter(
            boolean enabled,
            boolean mockEnabled,
            String appId,
            String secret
    ) {
        this(enabled, mockEnabled, appId, secret, RestClient.create(), null);
    }

    WeChatMiniprogramAdapter(
            boolean enabled,
            boolean mockEnabled,
            String appId,
            String secret,
            RestClient restClient
    ) {
        this(enabled, mockEnabled, appId, secret, restClient, null);
    }

    WeChatMiniprogramAdapter(
            boolean enabled,
            boolean mockEnabled,
            String appId,
            String secret,
            RestClient restClient,
            StringRedisTemplate redis
    ) {
        this.restClient = restClient;
        this.enabled = enabled;
        this.mockEnabled = mockEnabled;
        this.appId = appId == null ? "" : appId;
        this.secret = secret == null ? "" : secret;
        this.redis = redis;
    }

    @Override
    public WeChatIdentity exchangeMiniprogramCode(String jsCode) {
        String code = jsCode == null ? "" : jsCode.trim();
        if (code.isEmpty()) {
            throw new DomainException("WECHAT_CODE_REQUIRED", "微信登录凭证不能为空");
        }
        if (mockEnabled) {
            return new WeChatIdentity(
                    "WECHAT_MP",
                    "local",
                    code,
                    "mock-union-" + code,
                    null,
                    null
            );
        }
        requireEnabled();
        if (appId.isBlank() || secret.isBlank()) {
            throw new DomainException("WECHAT_NOT_CONFIGURED", "微信小程序参数未配置");
        }
        Map<String, Object> payload = getMap(code);
        if (payload.containsKey("errcode") && !"0".equals(string(payload.get("errcode")))) {
            throw exchangeFailure();
        }
        Object openIdValue = payload.get("openid");
        if (!(openIdValue instanceof String openId) || openId.isBlank()) {
            throw exchangeFailure();
        }
        return new WeChatIdentity(
                "WECHAT_MP",
                appId,
                openId,
                optionalString(payload, "unionid"),
                null,
                null
        );
    }

    @Override
    public VerifiedPhone exchangePhoneCode(String dynamicCode) {
        String code = dynamicCode == null ? "" : dynamicCode.trim();
        if (code.isEmpty()) {
            throw new DomainException("WECHAT_PHONE_CODE_REQUIRED", "请重新授权微信手机号");
        }
        if (mockEnabled) {
            return new VerifiedPhone("13800138000");
        }
        requireConfigured();
        return exchangePhoneCode(code, false);
    }

    @Override
    public WxaCodeImage createWxaCode(WxaCodeCommand command) {
        if (mockEnabled || !enabled || appId.isBlank() || secret.isBlank()) {
            return new WxaCodeImage("image/png", DECORATIVE_PNG);
        }
        return createWxaCode(command, false);
    }

    private WxaCodeImage createWxaCode(WxaCodeCommand command, boolean retriedAccessToken) {
        String scene = command == null || command.scene() == null ? "" : command.scene();
        String path = command == null || command.path() == null ? "" : command.path();
        String page = command == null || command.page() == null ? "" : command.page();
        boolean sceneSafe = SCENE_SAFE.matcher(scene).matches();
        if (!sceneSafe && (path.isBlank() || path.length() > 128)) {
            throw new DomainException("INVITATION_WXACODE_UNSUPPORTED", "当前邀请码无法生成小程序码");
        }
        String accessToken = accessToken();
        byte[] body;
        try {
            if (sceneSafe) {
                body = restClient.post()
                        .uri(
                                "https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token={accessToken}",
                                accessToken
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "page", page,
                                "scene", scene,
                                "width", 280,
                                "check_path", false,
                                "env_version", "release"
                        ))
                        .retrieve()
                        .body(byte[].class);
            } else {
                body = restClient.post()
                        .uri(
                                "https://api.weixin.qq.com/wxa/getwxacode?access_token={accessToken}",
                                accessToken
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "path", path,
                                "width", 280,
                                "env_version", "release"
                        ))
                        .retrieve()
                        .body(byte[].class);
            }
        } catch (RestClientException exception) {
            throw wxacodeFailure();
        }
        if (body == null || body.length == 0) {
            throw wxacodeFailure();
        }
        if (body[0] == '{') {
            Map<String, Object> payload;
            try {
                payload = JSON.readValue(body, MAP_TYPE);
            } catch (IOException exception) {
                throw wxacodeFailure();
            }
            String errorCode = string(payload == null ? null : payload.get("errcode"));
            if (!retriedAccessToken && isAccessTokenFailure(errorCode)) {
                invalidateAccessToken();
                return createWxaCode(command, true);
            }
            throw wxacodeFailure();
        }
        return new WxaCodeImage("image/png", body);
    }

    private VerifiedPhone exchangePhoneCode(String dynamicCode, boolean retriedAccessToken) {
        String accessToken = accessToken();
        Map<String, Object> payload = postPhoneMap(accessToken, dynamicCode);
        String errorCode = string(payload.get("errcode"));
        if (!errorCode.isEmpty() && !"0".equals(errorCode)) {
            if (!retriedAccessToken && isAccessTokenFailure(errorCode)) {
                invalidateAccessToken();
                return exchangePhoneCode(dynamicCode, true);
            }
            if (isPhoneCodeFailure(errorCode)) {
                throw new DomainException("WECHAT_PHONE_CODE_INVALID", "微信手机号授权已过期或已使用");
            }
            throw phoneExchangeFailure();
        }
        Object phoneInfoValue = payload.get("phone_info");
        if (!(phoneInfoValue instanceof Map<?, ?> phoneInfo)) {
            throw phoneExchangeFailure();
        }
        Object purePhoneValue = phoneInfo.get("purePhoneNumber");
        if (!(purePhoneValue instanceof String purePhone) || purePhone.isBlank()) {
            throw phoneExchangeFailure();
        }
        validatePhoneWatermark(phoneInfo);
        return new VerifiedPhone(purePhone);
    }

    private Map<String, Object> getMap(String code) {
        byte[] body;
        try {
            body = restClient.get()
                    .uri(
                            "https://api.weixin.qq.com/sns/jscode2session"
                                    + "?appid={appId}&secret={secret}&js_code={code}"
                                    + "&grant_type=authorization_code",
                            appId, secret, code
                    )
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientException exception) {
            throw exchangeFailure();
        }
        if (body == null || body.length == 0) {
            throw exchangeFailure();
        }
        try {
            Map<String, Object> payload = JSON.readValue(body, MAP_TYPE);
            if (payload == null) {
                throw exchangeFailure();
            }
            return payload;
        } catch (IOException exception) {
            throw exchangeFailure();
        }
    }

    private Map<String, Object> postPhoneMap(String accessToken, String dynamicCode) {
        byte[] body;
        try {
            body = restClient.post()
                    .uri(
                            "https://api.weixin.qq.com/wxa/business/getuserphonenumber"
                                    + "?access_token={accessToken}",
                            accessToken
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("code", dynamicCode))
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientException exception) {
            throw phoneExchangeFailure();
        }
        return readMap(body, WeChatMiniprogramAdapter::phoneExchangeFailure);
    }

    private String accessToken() {
        String current = localAccessToken;
        if (current != null && Instant.now().isBefore(localAccessTokenExpiresAt)) {
            return current;
        }
        String cached = redisAccessToken();
        if (cached != null) {
            return cached;
        }
        synchronized (accessTokenMonitor) {
            current = localAccessToken;
            if (current != null && Instant.now().isBefore(localAccessTokenExpiresAt)) {
                return current;
            }
            cached = redisAccessToken();
            if (cached != null) {
                return cached;
            }
            Map<String, Object> payload = getAccessTokenMap();
            if (payload.containsKey("errcode") && !"0".equals(string(payload.get("errcode")))) {
                throw phoneExchangeFailure();
            }
            Object tokenValue = payload.get("access_token");
            Object expiresValue = payload.get("expires_in");
            if (!(tokenValue instanceof String token) || token.isBlank()
                    || !(expiresValue instanceof Number expiresNumber)) {
                throw phoneExchangeFailure();
            }
            long expiresSeconds = expiresNumber.longValue();
            if (expiresSeconds <= 120) {
                throw phoneExchangeFailure();
            }
            long cacheSeconds = expiresSeconds - 120;
            localAccessToken = token;
            localAccessTokenExpiresAt = Instant.now().plusSeconds(cacheSeconds);
            cacheAccessToken(token, cacheSeconds);
            return token;
        }
    }

    private Map<String, Object> getAccessTokenMap() {
        byte[] body;
        try {
            body = restClient.get()
                    .uri(
                            "https://api.weixin.qq.com/cgi-bin/token"
                                    + "?grant_type=client_credential&appid={appId}&secret={secret}",
                            appId,
                            secret
                    )
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientException exception) {
            throw phoneExchangeFailure();
        }
        return readMap(body, WeChatMiniprogramAdapter::phoneExchangeFailure);
    }

    private String redisAccessToken() {
        if (redis == null) {
            return null;
        }
        try {
            String token = redis.opsForValue().get(accessTokenCacheKey());
            Long ttl = redis.getExpire(accessTokenCacheKey(), TimeUnit.SECONDS);
            if (token == null || token.isBlank() || ttl == null || ttl <= 30) {
                return null;
            }
            localAccessToken = token;
            localAccessTokenExpiresAt = Instant.now().plusSeconds(ttl);
            return token;
        } catch (RuntimeException cacheUnavailable) {
            return null;
        }
    }

    private void cacheAccessToken(String token, long cacheSeconds) {
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(accessTokenCacheKey(), token, Duration.ofSeconds(cacheSeconds));
        } catch (RuntimeException cacheUnavailable) {
            // The bounded in-process cache still prevents per-request token calls.
        }
    }

    private void invalidateAccessToken() {
        synchronized (accessTokenMonitor) {
            localAccessToken = null;
            localAccessTokenExpiresAt = Instant.EPOCH;
            if (redis != null) {
                try {
                    redis.delete(accessTokenCacheKey());
                } catch (RuntimeException cacheUnavailable) {
                    // A failed eviction is bounded by Redis TTL and the one-retry rule.
                }
            }
        }
    }

    private String accessTokenCacheKey() {
        return "market-shop:wechat:miniprogram:access-token:" + appId;
    }

    private static Map<String, Object> readMap(
            byte[] body,
            java.util.function.Supplier<DomainException> failure
    ) {
        if (body == null || body.length == 0) {
            throw failure.get();
        }
        try {
            Map<String, Object> payload = JSON.readValue(body, MAP_TYPE);
            if (payload == null) {
                throw failure.get();
            }
            return payload;
        } catch (IOException exception) {
            throw failure.get();
        }
    }

    private void validatePhoneWatermark(Map<?, ?> phoneInfo) {
        Object watermarkValue = phoneInfo.get("watermark");
        if (watermarkValue == null) {
            return;
        }
        if (!(watermarkValue instanceof Map<?, ?> watermark)) {
            throw phoneExchangeFailure();
        }
        Object watermarkAppId = watermark.get("appid");
        if (watermarkAppId != null
                && (!(watermarkAppId instanceof String value) || !appId.equals(value))) {
            throw phoneExchangeFailure();
        }
    }

    private static boolean isAccessTokenFailure(String errorCode) {
        return "40001".equals(errorCode) || "40014".equals(errorCode) || "42001".equals(errorCode);
    }

    private static boolean isPhoneCodeFailure(String errorCode) {
        return "40029".equals(errorCode) || "40163".equals(errorCode);
    }

    private static DomainException exchangeFailure() {
        return new DomainException(
                "WECHAT_CODE_EXCHANGE_FAILED",
                "微信登录失败，请重试"
        );
    }

    private static DomainException phoneExchangeFailure() {
        return new DomainException(
                "WECHAT_PHONE_EXCHANGE_FAILED",
                "微信手机号验证失败，请重新授权"
        );
    }

    private static DomainException wxacodeFailure() {
        return new DomainException("WECHAT_WXACODE_FAILED", "邀请二维码生成失败，请稍后重试");
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new DomainException("WECHAT_DISABLED", "微信登录尚未启用");
        }
    }

    private void requireConfigured() {
        requireEnabled();
        if (appId.isBlank() || secret.isBlank()) {
            throw new DomainException("WECHAT_NOT_CONFIGURED", "微信小程序参数未配置");
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String optionalString(Map<String, Object> payload, String field) {
        if (!payload.containsKey(field)) {
            return null;
        }
        Object value = payload.get(field);
        if (!(value instanceof String result)) {
            throw exchangeFailure();
        }
        return result.isBlank() ? null : result;
    }
}

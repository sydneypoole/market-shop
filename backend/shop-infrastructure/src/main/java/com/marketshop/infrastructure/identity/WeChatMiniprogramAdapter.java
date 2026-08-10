package com.marketshop.infrastructure.identity;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.application.identity.IdentityPorts.WeChatMiniprogramPort;
import com.marketshop.domain.shared.DomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.util.Map;

@Component
public class WeChatMiniprogramAdapter implements WeChatMiniprogramPort {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    private final RestClient restClient;
    private final boolean enabled;
    private final boolean mockEnabled;
    private final String appId;
    private final String secret;

    @Autowired
    public WeChatMiniprogramAdapter(
            @Value("${market-shop.wechat.enabled:false}") boolean enabled,
            @Value("${market-shop.wechat.mock-enabled:false}") boolean mockEnabled,
            @Value("${market-shop.wechat.miniprogram-app-id:}") String appId,
            @Value("${market-shop.wechat.miniprogram-secret:}") String secret
    ) {
        this(enabled, mockEnabled, appId, secret, RestClient.create());
    }

    WeChatMiniprogramAdapter(
            boolean enabled,
            boolean mockEnabled,
            String appId,
            String secret,
            RestClient restClient
    ) {
        this.restClient = restClient;
        this.enabled = enabled;
        this.mockEnabled = mockEnabled;
        this.appId = appId == null ? "" : appId;
        this.secret = secret == null ? "" : secret;
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

    private static DomainException exchangeFailure() {
        return new DomainException(
                "WECHAT_CODE_EXCHANGE_FAILED",
                "微信登录失败，请重试"
        );
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new DomainException("WECHAT_DISABLED", "微信登录尚未启用");
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

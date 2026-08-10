package com.marketshop.infrastructure.identity;

import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.application.identity.IdentityPorts.WeChatMiniprogramPort;
import com.marketshop.domain.shared.DomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class WeChatMiniprogramAdapter implements WeChatMiniprogramPort {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
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
        Map<String, Object> payload = getMap(
                "https://api.weixin.qq.com/sns/jscode2session?appid=" + encode(appId)
                        + "&secret=" + encode(secret)
                        + "&js_code=" + encode(code)
                        + "&grant_type=authorization_code"
        );
        if (payload.containsKey("errcode") && !"0".equals(string(payload.get("errcode")))) {
            throw new DomainException("WECHAT_CODE_EXCHANGE_FAILED", "微信登录失败，请重试");
        }
        String openId = string(payload.get("openid"));
        if (openId.isBlank()) {
            throw new DomainException("WECHAT_CODE_EXCHANGE_FAILED", "微信登录失败，请重试");
        }
        return new WeChatIdentity(
                "WECHAT_MP",
                appId,
                openId,
                nullableString(payload.get("unionid")),
                null,
                null
        );
    }

    private Map<String, Object> getMap(String uri) {
        Map<String, Object> body = restClient.get().uri(uri).retrieve().body(MAP_TYPE);
        if (body == null) {
            throw new DomainException("WECHAT_EMPTY_RESPONSE", "微信登录服务未返回有效数据");
        }
        return body;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new DomainException("WECHAT_DISABLED", "微信登录尚未启用");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullableString(Object value) {
        String result = string(value);
        return result.isBlank() ? null : result;
    }
}

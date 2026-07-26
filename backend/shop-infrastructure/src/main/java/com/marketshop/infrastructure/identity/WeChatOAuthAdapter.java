package com.marketshop.infrastructure.identity;

import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.application.identity.IdentityPorts.WeChatOAuthPort;
import com.marketshop.domain.shared.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class WeChatOAuthAdapter implements WeChatOAuthPort {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final boolean enabled;
    private final String officialAppId;
    private final String officialSecret;
    private final String websiteAppId;
    private final String websiteSecret;

    public WeChatOAuthAdapter(
            @Value("${market-shop.wechat.enabled:false}") boolean enabled,
            @Value("${market-shop.wechat.official-account-app-id:}") String officialAppId,
            @Value("${market-shop.wechat.official-account-secret:}") String officialSecret,
            @Value("${market-shop.wechat.website-app-id:}") String websiteAppId,
            @Value("${market-shop.wechat.website-secret:}") String websiteSecret
    ) {
        this.restClient = RestClient.create();
        this.enabled = enabled;
        this.officialAppId = officialAppId;
        this.officialSecret = officialSecret;
        this.websiteAppId = websiteAppId;
        this.websiteSecret = websiteSecret;
    }

    @Override
    public String authorizationUrl(String scene, String state, String callbackUri) {
        requireEnabled();
        String appId = appId(scene);
        String redirect = encode(callbackUri);
        if ("WEB".equals(scene)) {
            return "https://open.weixin.qq.com/connect/qrconnect?appid=" + encode(appId)
                    + "&redirect_uri=" + redirect
                    + "&response_type=code&scope=snsapi_login&state=" + encode(state)
                    + "#wechat_redirect";
        }
        return "https://open.weixin.qq.com/connect/oauth2/authorize?appid=" + encode(appId)
                + "&redirect_uri=" + redirect
                + "&response_type=code&scope=snsapi_userinfo&state=" + encode(state)
                + "#wechat_redirect";
    }

    @Override
    public WeChatIdentity exchange(String scene, String code) {
        requireEnabled();
        String appId = appId(scene);
        Map<String, Object> token = getMap(
                "https://api.weixin.qq.com/sns/oauth2/access_token?appid=" + encode(appId)
                        + "&secret=" + encode(secret(scene))
                        + "&code=" + encode(code)
                        + "&grant_type=authorization_code"
        );
        assertWeChatSuccess(token);
        String accessToken = string(token.get("access_token"));
        String openId = string(token.get("openid"));
        Map<String, Object> profile = getMap(
                "https://api.weixin.qq.com/sns/userinfo?access_token=" + encode(accessToken)
                        + "&openid=" + encode(openId)
                        + "&lang=zh_CN"
        );
        assertWeChatSuccess(profile);
        String unionId = nullableString(profile.get("unionid"));
        return new WeChatIdentity(
                "WECHAT_" + scene,
                appId,
                openId,
                unionId,
                nullableString(profile.get("nickname")),
                nullableString(profile.get("headimgurl"))
        );
    }

    private Map<String, Object> getMap(String uri) {
        Map<String, Object> body = restClient.get().uri(uri).retrieve().body(MAP_TYPE);
        if (body == null) {
            throw new DomainException("WECHAT_EMPTY_RESPONSE", "微信登录服务未返回有效数据");
        }
        return body;
    }

    private void assertWeChatSuccess(Map<String, Object> payload) {
        if (payload.containsKey("errcode") && !"0".equals(string(payload.get("errcode")))) {
            throw new DomainException("WECHAT_OAUTH_FAILED", "微信登录失败，请重试");
        }
    }

    private String appId(String scene) {
        String value = "WEB".equals(scene) ? websiteAppId : officialAppId;
        if (value.isBlank()) {
            throw new DomainException("WECHAT_NOT_CONFIGURED", "微信应用参数未配置");
        }
        return value;
    }

    private String secret(String scene) {
        String value = "WEB".equals(scene) ? websiteSecret : officialSecret;
        if (value.isBlank()) {
            throw new DomainException("WECHAT_NOT_CONFIGURED", "微信应用密钥未配置");
        }
        return value;
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

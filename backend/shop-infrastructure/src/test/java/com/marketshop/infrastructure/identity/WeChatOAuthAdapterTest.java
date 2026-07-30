package com.marketshop.infrastructure.identity;

import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeChatOAuthAdapterTest {

    @Test
    void buildsH5AndDesktopAuthorizationUrlsWithTheirOwnApplications() {
        var adapter = new WeChatOAuthAdapter(
                true, "oa-app", "oa-secret", "web-app", "web-secret"
        );

        String h5 = adapter.authorizationUrl(
                "H5", "safe state", "https://shop.example.com/api/v1/auth/wechat/callback"
        );
        String web = adapter.authorizationUrl(
                "WEB", "safe state", "https://shop.example.com/api/v1/auth/wechat/callback"
        );

        assertThat(h5)
                .startsWith("https://open.weixin.qq.com/connect/oauth2/authorize")
                .contains("appid=oa-app", "scope=snsapi_userinfo", "state=safe+state");
        assertThat(web)
                .startsWith("https://open.weixin.qq.com/connect/qrconnect")
                .contains("appid=web-app", "scope=snsapi_login", "state=safe+state");
    }

    @Test
    void failsClosedWhenWechatIsDisabledOrSceneIsNotConfigured() {
        var disabled = new WeChatOAuthAdapter(
                false, "oa-app", "oa-secret", "web-app", "web-secret"
        );
        var missingDesktop = new WeChatOAuthAdapter(
                true, "oa-app", "oa-secret", "", ""
        );

        assertThatThrownBy(() -> disabled.authorizationUrl("H5", "state", "https://shop.example.com/callback"))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("WECHAT_DISABLED");
        assertThatThrownBy(() -> missingDesktop.authorizationUrl("WEB", "state", "https://shop.example.com/callback"))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("WECHAT_NOT_CONFIGURED");
    }
}

package com.marketshop.infrastructure.identity;

import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WeChatMiniprogramAdapterTest {

    @Test
    void mockModeUsesCodeAsOpenIdWithoutCallingWeChat() {
        var adapter = new WeChatMiniprogramAdapter(true, true, "mp-app", "mp-secret");

        var identity = adapter.exchangeMiniprogramCode("code-1");

        assertThat(identity.provider()).isEqualTo("WECHAT_MP");
        assertThat(identity.appId()).isEqualTo("local");
        assertThat(identity.openId()).isEqualTo("code-1");
        assertThat(identity.unionId()).isEqualTo("mock-union-code-1");
        assertThat(identity.nickname()).isNull();
    }

    @Test
    void realModeExchangesCodeAndMapsErrcodeToDomainFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new WeChatMiniprogramAdapter(
                true, false, "mp-app", "mp-secret", builder.build()
        );

        server.expect(requestTo(org.hamcrest.Matchers.containsString("jscode2session")))
                .andRespond(withSuccess(
                        "{\"openid\":\"o-open\",\"unionid\":\"u-union\",\"session_key\":\"s\"}",
                        MediaType.APPLICATION_JSON
                ));
        var identity = adapter.exchangeMiniprogramCode("valid-code");
        assertThat(identity.provider()).isEqualTo("WECHAT_MP");
        assertThat(identity.appId()).isEqualTo("mp-app");
        assertThat(identity.openId()).isEqualTo("o-open");
        assertThat(identity.unionId()).isEqualTo("u-union");
        server.verify();

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("jscode2session")))
                .andRespond(withSuccess(
                        "{\"errcode\":40029,\"errmsg\":\"invalid code\"}",
                        MediaType.APPLICATION_JSON
                ));
        assertThatThrownBy(() -> adapter.exchangeMiniprogramCode("bad-code"))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("WECHAT_CODE_EXCHANGE_FAILED");
        server.verify();
    }

    @Test
    void failsClosedWhenWechatIsDisabledOrNotConfigured() {
        var disabled = new WeChatMiniprogramAdapter(false, false, "mp-app", "mp-secret");
        var missing = new WeChatMiniprogramAdapter(true, false, "", "");

        assertThatThrownBy(() -> disabled.exchangeMiniprogramCode("code"))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("WECHAT_DISABLED");
        assertThatThrownBy(() -> missing.exchangeMiniprogramCode("code"))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("WECHAT_NOT_CONFIGURED");
    }
}

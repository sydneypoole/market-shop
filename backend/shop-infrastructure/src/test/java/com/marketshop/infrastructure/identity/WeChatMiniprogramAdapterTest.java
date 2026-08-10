package com.marketshop.infrastructure.identity;

import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WeChatMiniprogramAdapterTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WeChatAdapterConfiguration.class)
            .withPropertyValues(
                    "market-shop.wechat.enabled=true",
                    "market-shop.wechat.mock-enabled=true",
                    "market-shop.wechat.miniprogram-app-id=mp-app",
                    "market-shop.wechat.miniprogram-secret=mp-secret"
            );

    @Test
    void springContextSelectsTheConfigurationConstructor() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WeChatMiniprogramAdapter.class);
        });
    }

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
                        MediaType.TEXT_PLAIN
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
                        "{\"openid\":\"o-json\",\"session_key\":\"s\"}",
                        MediaType.APPLICATION_JSON
                ));
        assertThat(adapter.exchangeMiniprogramCode("json-code").openId()).isEqualTo("o-json");
        server.verify();

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("jscode2session")))
                .andRespond(withSuccess(
                        "{\"errcode\":40029,\"errmsg\":\"invalid code\"}",
                        MediaType.TEXT_PLAIN
                ));
        assertThatThrownBy(() -> adapter.exchangeMiniprogramCode("bad-code"))
                .isInstanceOf(DomainException.class)
                .hasNoCause()
                .hasMessage("微信登录失败，请重试")
                .extracting("code")
                .isEqualTo("WECHAT_CODE_EXCHANGE_FAILED");
        server.verify();
    }

    @Test
    void mapsMalformedOrUnavailableWechatResponsesToTheStableExchangeFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new WeChatMiniprogramAdapter(
                true, false, "mp-app", "mp-secret", builder.build()
        );

        assertStableExchangeFailure(server, adapter, "", "empty-code");
        assertStableExchangeFailure(server, adapter, " \n\t ", "blank-code");
        assertStableExchangeFailure(server, adapter, "not-json", "malformed-code");
        assertStableExchangeFailure(server, adapter, "null", "null-code");
        assertStableExchangeFailure(server, adapter, "[]", "array-code");
        assertStableExchangeFailure(server, adapter, "\"scalar\"", "scalar-code");
        assertStableExchangeFailure(server, adapter, "{\"session_key\":\"s\"}", "missing-openid-code");
        assertStableExchangeFailure(server, adapter, "{\"openid\":\"  \"}", "blank-openid-code");
        assertStableExchangeFailure(server, adapter, "{\"openid\":123}", "numeric-openid-code");
        assertStableExchangeFailure(
                server,
                adapter,
                "{\"openid\":\"o-open\",\"unionid\":null}",
                "null-unionid-code"
        );
        assertStableExchangeFailure(
                server,
                adapter,
                "{\"openid\":\"o-open\",\"unionid\":{\"unexpected\":true}}",
                "object-unionid-code"
        );
        assertStableExchangeFailure(
                server,
                adapter,
                "{\"openid\":\"first\",\"openid\":\"second\"}",
                "duplicate-field-code"
        );
        assertStableExchangeFailure(
                server,
                adapter,
                "{\"openid\":\"o-open\"} {\"extra\":true}",
                "trailing-token-code"
        );

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("jscode2session")))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("{\"session_key\":\"must-not-leak\"}"));
        assertThatThrownBy(() -> adapter.exchangeMiniprogramCode("unavailable-code"))
                .isInstanceOf(DomainException.class)
                .hasNoCause()
                .hasMessage("微信登录失败，请重试")
                .extracting("code")
                .isEqualTo("WECHAT_CODE_EXCHANGE_FAILED");
        server.verify();

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("jscode2session")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("{\"errmsg\":\"must-not-leak\"}"));
        assertThatThrownBy(() -> adapter.exchangeMiniprogramCode("rejected-code"))
                .isInstanceOf(DomainException.class)
                .hasNoCause()
                .hasMessage("微信登录失败，请重试")
                .extracting("code")
                .isEqualTo("WECHAT_CODE_EXCHANGE_FAILED");
        server.verify();

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("jscode2session")))
                .andRespond(request -> {
                    throw new IOException("transport failure with must-not-leak details");
                });
        assertThatThrownBy(() -> adapter.exchangeMiniprogramCode("transport-code"))
                .isInstanceOf(DomainException.class)
                .hasNoCause()
                .hasMessage("微信登录失败，请重试")
                .extracting("code")
                .isEqualTo("WECHAT_CODE_EXCHANGE_FAILED");
        server.verify();
    }

    private static void assertStableExchangeFailure(
            MockRestServiceServer server,
            WeChatMiniprogramAdapter adapter,
            String body,
            String code
    ) {
        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("jscode2session")))
                .andRespond(withSuccess(body, MediaType.TEXT_PLAIN));
        assertThatThrownBy(() -> adapter.exchangeMiniprogramCode(code))
                .isInstanceOf(DomainException.class)
                .hasNoCause()
                .hasMessage("微信登录失败，请重试")
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

    @Configuration(proxyBeanMethods = false)
    @Import(WeChatMiniprogramAdapter.class)
    static class WeChatAdapterConfiguration {
    }
}

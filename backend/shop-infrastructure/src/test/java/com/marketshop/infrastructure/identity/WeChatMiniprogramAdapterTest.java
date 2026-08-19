package com.marketshop.infrastructure.identity;

import com.marketshop.application.identity.IdentityPorts.WxaCodeCommand;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
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
        assertThat(adapter.exchangePhoneCode("phone-code").purePhoneNumber())
                .isEqualTo("13800138000");
    }

    @Test
    void phoneExchangeCachesAccessTokenAndValidatesTheWechatWatermark() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new WeChatMiniprogramAdapter(
                true, false, "mp-app", "mp-secret", builder.build()
        );

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/cgi-bin/token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-1\",\"expires_in\":7200}",
                        MediaType.TEXT_PLAIN
                ));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getuserphonenumber")))
                .andRespond(withSuccess(phonePayload("13800138000"), MediaType.TEXT_PLAIN));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getuserphonenumber")))
                .andRespond(withSuccess(phonePayload("13900139000"), MediaType.APPLICATION_JSON));

        assertThat(adapter.exchangePhoneCode("phone-code-1").purePhoneNumber())
                .isEqualTo("13800138000");
        assertThat(adapter.exchangePhoneCode("phone-code-2").purePhoneNumber())
                .isEqualTo("13900139000");
        server.verify();
    }

    @Test
    void invalidOrConsumedPhoneCodeIsAStableClientFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new WeChatMiniprogramAdapter(
                true, false, "mp-app", "mp-secret", builder.build()
        );

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/cgi-bin/token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-1\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getuserphonenumber")))
                .andRespond(withSuccess(
                        "{\"errcode\":40163,\"errmsg\":\"code been used\"}",
                        MediaType.TEXT_PLAIN
                ));

        assertThatThrownBy(() -> adapter.exchangePhoneCode("consumed-code"))
                .isInstanceOf(DomainException.class)
                .hasNoCause()
                .extracting("code")
                .isEqualTo("WECHAT_PHONE_CODE_INVALID");
        server.verify();
    }

    @Test
    void phoneExchangeRefreshesAnInvalidAccessTokenOnce() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new WeChatMiniprogramAdapter(
                true, false, "mp-app", "mp-secret", builder.build()
        );

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/cgi-bin/token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"stale-token\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getuserphonenumber")))
                .andRespond(withSuccess(
                        "{\"errcode\":40001,\"errmsg\":\"invalid token\"}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/cgi-bin/token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"fresh-token\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getuserphonenumber")))
                .andRespond(withSuccess(phonePayload("13800138000"), MediaType.APPLICATION_JSON));

        assertThat(adapter.exchangePhoneCode("phone-code").purePhoneNumber())
                .isEqualTo("13800138000");
        server.verify();
    }

    @Test
    void malformedPhoneOrUnsafeTokenLifetimeMapsToStableUpstreamFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var shortTokenAdapter = new WeChatMiniprogramAdapter(
                true, false, "mp-app", "mp-secret", builder.build()
        );
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/cgi-bin/token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token\",\"expires_in\":60}",
                        MediaType.APPLICATION_JSON
                ));
        assertPhoneExchangeFailure(() -> shortTokenAdapter.exchangePhoneCode("phone-code"));
        server.verify();

        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var malformedAdapter = new WeChatMiniprogramAdapter(
                true, false, "mp-app", "mp-secret", builder.build()
        );
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/cgi-bin/token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getuserphonenumber")))
                .andRespond(withSuccess("{\"errcode\":0,\"phone_info\":{}}", MediaType.TEXT_PLAIN));
        assertPhoneExchangeFailure(() -> malformedAdapter.exchangePhoneCode("phone-code"));
        server.verify();
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

    private static String phonePayload(String phone) {
        return "{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"" + phone
                + "\",\"watermark\":{\"appid\":\"mp-app\"}}}";
    }

    private static WxaCodeCommand shortInviteCommand() {
        return new WxaCodeCommand(
                "pages/register/register",
                "MSABCDEF1234",
                "pages/register/register?inviteCode=MSABCDEF1234"
        );
    }

    private static byte[] officialPng() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    }

    private static void assertPhoneExchangeFailure(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DomainException.class)
                .hasNoCause()
                .hasMessage("微信手机号验证失败，请重新授权")
                .extracting("code")
                .isEqualTo("WECHAT_PHONE_EXCHANGE_FAILED");
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

    @Test
    void wxacodeInMockDisabledOrUnconfiguredModeReturnsPngWithoutCallingWeChat() {
        var mock = new WeChatMiniprogramAdapter(true, true, "mp-app", "mp-secret");
        var disabled = new WeChatMiniprogramAdapter(false, false, "mp-app", "mp-secret");
        var missing = new WeChatMiniprogramAdapter(true, false, "", "");
        var command = shortInviteCommand();

        for (var adapter : new WeChatMiniprogramAdapter[]{mock, disabled, missing}) {
            var image = adapter.createWxaCode(command);
            assertThat(image.contentType()).isEqualTo("image/png");
            assertThat(image.image()).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47);
        }
    }

    @Test
    void shortSceneSafeCodeUsesUnlimitedWxacode() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new WeChatMiniprogramAdapter(
                true, false, "mp-app", "mp-secret", builder.build()
        );

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/cgi-bin/token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-1\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getwxacodeunlimit")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "page":"pages/register/register",
                          "scene":"MSABCDEF1234",
                          "width":280,
                          "check_path":false,
                          "env_version":"release"
                        }
                        """))
                .andRespond(withSuccess(officialPng(), MediaType.IMAGE_PNG));

        var image = adapter.createWxaCode(shortInviteCommand());

        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.image()).isEqualTo(officialPng());
        server.verify();
    }

    @Test
    void sceneUnsafeCodeFallsBackToPathWxacode() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new WeChatMiniprogramAdapter(
                true, false, "mp-app", "mp-secret", builder.build()
        );
        String path = "pages/register/register?inviteCode=INVITE%20%2B%2F%3F%26";

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/cgi-bin/token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-1\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/wxa/getwxacode?")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "path":"pages/register/register?inviteCode=INVITE%20%2B%2F%3F%26",
                          "width":280,
                          "env_version":"release"
                        }
                        """))
                .andRespond(withSuccess(officialPng(), MediaType.IMAGE_PNG));

        var image = adapter.createWxaCode(new WxaCodeCommand(
                "pages/register/register",
                "INVITE +/?&",
                path
        ));

        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.image()).isEqualTo(officialPng());
        server.verify();
    }

    @Test
    void wxacodeJsonErrorIsAStableDomainFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new WeChatMiniprogramAdapter(
                true, false, "mp-app", "mp-secret", builder.build()
        );

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/cgi-bin/token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-1\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getwxacodeunlimit")))
                .andRespond(withSuccess(
                        "{\"errcode\":41030,\"errmsg\":\"invalid page must-not-leak\"}",
                        MediaType.APPLICATION_JSON
                ));

        assertThatThrownBy(() -> adapter.createWxaCode(shortInviteCommand()))
                .isInstanceOf(DomainException.class)
                .hasNoCause()
                .hasMessage("邀请二维码生成失败，请稍后重试")
                .extracting("code")
                .isEqualTo("WECHAT_WXACODE_FAILED")
                .satisfies(code -> assertThat(code.toString()).doesNotContain("must-not-leak"));
        server.verify();
    }

    @Test
    void wxacodeRefreshesAnInvalidAccessTokenOnce() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new WeChatMiniprogramAdapter(
                true, false, "mp-app", "mp-secret", builder.build()
        );

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/cgi-bin/token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"stale-token\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getwxacodeunlimit")))
                .andRespond(withSuccess(
                        "{\"errcode\":40001,\"errmsg\":\"invalid token\"}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/cgi-bin/token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"fresh-token\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getwxacodeunlimit")))
                .andRespond(withSuccess(officialPng(), MediaType.IMAGE_PNG));

        var image = adapter.createWxaCode(shortInviteCommand());

        assertThat(image.image()).isEqualTo(officialPng());
        server.verify();
    }

    @Test
    void wxacodeHttpErrorWithInvalidAccessTokenRetriesOnce() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new WeChatMiniprogramAdapter(
                true, false, "mp-app", "mp-secret", builder.build()
        );

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/cgi-bin/token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"stale-token\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getwxacodeunlimit")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errcode\":40001,\"errmsg\":\"invalid token must-not-leak\"}"));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/cgi-bin/token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"fresh-token\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getwxacodeunlimit")))
                .andRespond(withSuccess(officialPng(), MediaType.IMAGE_PNG));

        var image = adapter.createWxaCode(shortInviteCommand());

        assertThat(image.image()).isEqualTo(officialPng());
        server.verify();
    }

    @Test
    void wxacodeHttpJsonErrorIsAStableDomainFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new WeChatMiniprogramAdapter(
                true, false, "mp-app", "mp-secret", builder.build()
        );

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/cgi-bin/token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-1\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getwxacodeunlimit")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errcode\":41030,\"errmsg\":\"invalid page must-not-leak\"}"));

        assertThatThrownBy(() -> adapter.createWxaCode(shortInviteCommand()))
                .isInstanceOf(DomainException.class)
                .hasNoCause()
                .hasMessage("邀请二维码生成失败，请稍后重试")
                .extracting("code")
                .isEqualTo("WECHAT_WXACODE_FAILED")
                .satisfies(code -> assertThat(code.toString()).doesNotContain("must-not-leak"));
        server.verify();
    }

    @Test
    void wxacodeHttpHtmlErrorIsNotReturnedAsAnImage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new WeChatMiniprogramAdapter(
                true, false, "mp-app", "mp-secret", builder.build()
        );

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/cgi-bin/token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-1\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getwxacodeunlimit")))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>must-not-leak</html>"));

        assertThatThrownBy(() -> adapter.createWxaCode(shortInviteCommand()))
                .isInstanceOf(DomainException.class)
                .hasNoCause()
                .hasMessage("邀请二维码生成失败，请稍后重试")
                .extracting("code")
                .isEqualTo("WECHAT_WXACODE_FAILED")
                .satisfies(code -> assertThat(code.toString()).doesNotContain("must-not-leak"));
        server.verify();
    }

    @Test
    void rejectsAPathThatCannotFitEitherWxacodeApi() {
        var adapter = new WeChatMiniprogramAdapter(true, false, "mp-app", "mp-secret");
        String tooLongPath = "pages/register/register?inviteCode=" + "A".repeat(100);

        assertThatThrownBy(() -> adapter.createWxaCode(new WxaCodeCommand(
                "pages/register/register",
                "INVITE " + "A".repeat(40),
                tooLongPath
        )))
                .isInstanceOf(DomainException.class)
                .hasNoCause()
                .extracting("code")
                .isEqualTo("INVITATION_WXACODE_UNSUPPORTED");
    }

    @Configuration(proxyBeanMethods = false)
    @Import(WeChatMiniprogramAdapter.class)
    static class WeChatAdapterConfiguration {
    }
}

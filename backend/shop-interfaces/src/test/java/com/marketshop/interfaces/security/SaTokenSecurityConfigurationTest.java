package com.marketshop.interfaces.security;

import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaTokenSecurityConfigurationTest {

    private final SameOriginWriteInterceptor originInterceptor = new SameOriginWriteInterceptor();

    @Test
    void productionCookiesAreHttpOnlySecureLaxAndNeverWrittenToHeaders() {
        new SaTokenSecurityConfiguration(true, null);

        assertCookiePolicy(StpUserKit.logic().getConfig(), true, true);
        assertCookiePolicy(StpAdminKit.logic().getConfig(), true, false);
    }

    @Test
    void localHttpKeepsEveryCookieProtectionExceptSecureTransportFlag() {
        new SaTokenSecurityConfiguration(false, null);

        assertCookiePolicy(StpUserKit.logic().getConfig(), false, true);
        assertCookiePolicy(StpAdminKit.logic().getConfig(), false, false);
    }

    @Test
    void sameOriginAndTrustedOriginlessWritesPass() {
        MockHttpServletRequest sameOrigin = request("POST", "https", "shop.example.com", 443);
        sameOrigin.addHeader("Origin", "https://shop.example.com");
        MockHttpServletRequest noOrigin = request("PATCH", "http", "localhost", 8080);

        assertThat(originInterceptor.preHandle(sameOrigin, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(originInterceptor.preHandle(noOrigin, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void browserCrossSiteAndMalformedOriginsAreRejectedButReadsRemainPublic() {
        MockHttpServletRequest crossSite = request("DELETE", "https", "shop.example.com", 443);
        crossSite.addHeader("Origin", "https://evil.example");
        MockHttpServletRequest malformed = request("POST", "http", "localhost", 8080);
        malformed.addHeader("Origin", "null");
        MockHttpServletRequest read = request("GET", "https", "shop.example.com", 443);
        read.addHeader("Origin", "https://evil.example");

        assertThatThrownBy(() -> originInterceptor.preHandle(
                crossSite, new MockHttpServletResponse(), new Object()
        )).isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("CROSS_ORIGIN_WRITE_DENIED");
        assertThatThrownBy(() -> originInterceptor.preHandle(
                malformed, new MockHttpServletResponse(), new Object()
        )).isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("CROSS_ORIGIN_WRITE_DENIED");
        assertThat(originInterceptor.preHandle(read, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void explicitlyConfiguredLocalFrontendOriginsPassWithoutOpeningArbitraryCrossSiteWrites() {
        SameOriginWriteInterceptor configured = new SameOriginWriteInterceptor(
                "http://localhost:5174,http://127.0.0.1:5174/"
        );
        MockHttpServletRequest adminLocalhost = request("POST", "http", "127.0.0.1", 8080);
        adminLocalhost.addHeader("Origin", "http://localhost:5174");
        MockHttpServletRequest adminLoopback = request("POST", "http", "localhost", 8080);
        adminLoopback.addHeader("Origin", "http://127.0.0.1:5174");
        MockHttpServletRequest evil = request("POST", "http", "localhost", 8080);
        evil.addHeader("Origin", "http://evil.example");

        assertThat(configured.preHandle(adminLocalhost, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(configured.preHandle(adminLoopback, new MockHttpServletResponse(), new Object())).isTrue();
        assertThatThrownBy(() -> configured.preHandle(
                evil, new MockHttpServletResponse(), new Object()
        )).isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("CROSS_ORIGIN_WRITE_DENIED");
    }

    @Test
    void configuredOriginsRejectPathsQueriesCredentialsAndWildcards() {
        for (String invalid : new String[]{
                "http://localhost:5174/path",
                "http://localhost:5174?tenant=one",
                "http://user:password@localhost:5174",
                "http://localhost:*"
        }) {
            assertThatThrownBy(() -> new SameOriginWriteInterceptor(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void credentialedCorsReplacesFrameworkWildcardWithExplicitOrigins() {
        ExposedCorsRegistry emptyRegistry = new ExposedCorsRegistry();
        new SaTokenSecurityConfiguration(false, null, "").addCorsMappings(emptyRegistry);
        CorsConfiguration empty = emptyRegistry.configurations().get("/api/**");

        assertThat(empty.getAllowedOrigins()).isEmpty();
        assertThat(empty.getAllowCredentials()).isTrue();
        assertThat(empty.checkOrigin("https://evil.example")).isNull();

        ExposedCorsRegistry configuredRegistry = new ExposedCorsRegistry();
        new SaTokenSecurityConfiguration(
                false,
                null,
                "http://localhost:5174,http://127.0.0.1:5174/"
        ).addCorsMappings(configuredRegistry);
        CorsConfiguration configured = configuredRegistry.configurations().get("/api/**");

        assertThat(configured.getAllowedOrigins())
                .containsExactly("http://localhost:5174", "http://127.0.0.1:5174");
        assertThat(configured.checkOrigin("http://localhost:5174"))
                .isEqualTo("http://localhost:5174");
        assertThat(configured.checkOrigin("https://evil.example")).isNull();
    }

    private static MockHttpServletRequest request(String method, String scheme, String host, int port) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/test");
        request.setScheme(scheme);
        request.setServerName(host);
        request.setServerPort(port);
        return request;
    }

    private static void assertCookiePolicy(
            cn.dev33.satoken.config.SaTokenConfig config,
            boolean secure,
            boolean readHeader
    ) {
        assertThat(config.getIsReadCookie()).isTrue();
        assertThat(config.getIsReadBody()).isFalse();
        assertThat(config.getIsReadHeader()).isEqualTo(readHeader);
        assertThat(config.getIsWriteHeader()).isFalse();
        assertThat(config.getCookie().getPath()).isEqualTo("/");
        assertThat(config.getCookie().getHttpOnly()).isTrue();
        assertThat(config.getCookie().getSecure()).isEqualTo(secure);
        assertThat(config.getCookie().getSameSite()).isEqualTo("Lax");
    }

    private static final class ExposedCorsRegistry extends CorsRegistry {
        private Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}

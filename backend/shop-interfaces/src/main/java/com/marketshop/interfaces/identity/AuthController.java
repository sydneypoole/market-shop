package com.marketshop.interfaces.identity;

import com.marketshop.application.identity.AuthUseCase;
import com.marketshop.application.identity.AuthUseCase.BeginCommand;
import com.marketshop.application.identity.AuthUseCase.CompleteCommand;
import com.marketshop.application.identity.AuthUseCase.DevLoginCommand;
import com.marketshop.application.identity.AuthUseCase.LoginResult;
import com.marketshop.application.identity.IdentitySecretHashes;
import com.marketshop.interfaces.security.AccountSessionEpochGuard;
import com.marketshop.interfaces.security.StpUserKit;
import com.marketshop.interfaces.shared.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final String OAUTH_BINDING_COOKIE = "market-shop-oauth-binding";
    private static final String OAUTH_COOKIE_PATH = "/api/v1/auth/wechat";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AuthUseCase authUseCase;
    private final AccountSessionEpochGuard sessionEpochGuard;
    private final boolean secureCookie;
    private final URI storefrontOrigin;

    public AuthController(
            AuthUseCase authUseCase,
            AccountSessionEpochGuard sessionEpochGuard,
            @Value("${market-shop.security.secure-cookie:false}") boolean secureCookie,
            @Value("${market-shop.wechat.storefront-base-url:}") String storefrontBaseUrl
    ) {
        this.authUseCase = authUseCase;
        this.sessionEpochGuard = sessionEpochGuard;
        this.secureCookie = secureCookie;
        // The application service has already reduced the requested redirect
        // to a relative path.  Keep the final hop anchored to the configured
        // storefront origin so an API host and a storefront host may be
        // deployed separately without sending the browser to api.example.com
        // (which would otherwise receive a relative /orders location).
        this.storefrontOrigin = configuredOrigin(storefrontBaseUrl, secureCookie);
    }

    @PostMapping("/wechat/authorize")
    public ResponseEntity<ApiResponse<AuthUseCase.StartResult>> authorize(
            @Valid @RequestBody BeginRequest request
    ) {
        String browserBinding = newBrowserBinding();
        AuthUseCase.StartResult result = authUseCase.begin(new BeginCommand(
                request.scene(),
                request.inviteCode(),
                request.sponsorClaimSecret(),
                request.redirectUri(),
                IdentitySecretHashes.sha256(browserBinding)
        ));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, bindingCookie(browserBinding, Duration.ofMinutes(5)).toString())
                .body(ApiResponse.ok(result));
    }

    @GetMapping("/wechat/callback")
    public ResponseEntity<Void> callback(
            @org.springframework.web.bind.annotation.RequestParam String code,
            @org.springframework.web.bind.annotation.RequestParam String state,
            @CookieValue(name = OAUTH_BINDING_COOKIE, required = false) String browserBinding,
            HttpServletResponse response
    ) {
        clearBindingCookie(response);
        LoginResult result = authUseCase.complete(new CompleteCommand(code, state, browserBinding));
        establishSession(result);
        URI location = successfulRedirect(result.redirectUri(), storefrontOrigin);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location.toString())
                .build();
    }

    @PostMapping("/wechat/complete")
    public ApiResponse<SessionView> complete(
            @Valid @RequestBody CompleteRequest request,
            @CookieValue(name = OAUTH_BINDING_COOKIE, required = false) String browserBinding,
            HttpServletResponse response
    ) {
        clearBindingCookie(response);
        LoginResult result = authUseCase.complete(new CompleteCommand(
                request.code(), request.state(), browserBinding
        ));
        return ApiResponse.ok(establishSession(result));
    }

    @PostMapping("/dev-login")
    public ApiResponse<SessionView> devLogin(@Valid @RequestBody DevLoginRequest request) {
        LoginResult result = authUseCase.devLogin(
                new DevLoginCommand(request.openId(), request.nickname(), request.inviteCode())
        );
        return ApiResponse.ok(establishSession(result));
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        sessionEpochGuard.requireMemberSession();
        var session = StpUserKit.logic().getTokenSession();
        return ApiResponse.ok(Map.of(
                "userId", StpUserKit.logic().getLoginIdAsLong(),
                "publicId", session.getString("publicId"),
                "nickname", session.getString("nickname")
        ));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        StpUserKit.logic().logout();
        return ApiResponse.ok(null);
    }

    private static SessionView establishSession(LoginResult result) {
        StpUserKit.logic().login(result.userId());
        var session = StpUserKit.logic().getTokenSession();
        session.set("publicId", result.publicId());
        session.set("nickname", result.nickname());
        session.set("authEpoch", result.authEpoch());
        return new SessionView(
                result.publicId(),
                result.nickname(),
                result.newlyRegistered()
        );
    }

    public record CompleteRequest(@NotBlank String code, @NotBlank String state) {
    }

    public record BeginRequest(
            @NotBlank String scene,
            String inviteCode,
            String sponsorClaimSecret,
            @NotBlank String redirectUri
    ) {
    }

    public record DevLoginRequest(@NotBlank String openId, String nickname, String inviteCode) {
    }

    public record SessionView(
            String publicId,
            String nickname,
            boolean newlyRegistered
    ) {
    }

    private static String newBrowserBinding() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ResponseCookie bindingCookie(String value, Duration maxAge) {
        return ResponseCookie.from(OAUTH_BINDING_COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path(OAUTH_COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }

    private void clearBindingCookie(HttpServletResponse response) {
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                bindingCookie("", Duration.ZERO).toString()
        );
    }

    static URI successfulRedirect(String redirectUri, URI storefrontOrigin) {
        try {
            URI target = URI.create(redirectUri);
            if (target.isAbsolute() || target.getRawAuthority() != null
                    || target.getRawPath() == null || !target.getRawPath().startsWith("/")
                    || target.getRawPath().startsWith("//") || target.getRawUserInfo() != null
                    || target.getRawPath().indexOf('\\') >= 0
                    || target.getRawPath().chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
                throw new IllegalStateException("Validated OAuth redirect must be relative");
            }
            String query = target.getRawQuery();
            String nextQuery = query == null || query.isBlank()
                    ? "wechatLogin=success"
                    : query + "&wechatLogin=success";
            return new URI(
                    storefrontOrigin.getScheme(),
                    storefrontOrigin.getRawAuthority(),
                    target.getRawPath(),
                    nextQuery,
                    target.getRawFragment()
            );
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Validated OAuth redirect could not be rebuilt", exception);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Validated OAuth redirect must be relative", exception);
        }
    }

    private static URI configuredOrigin(String value, boolean secureCookie) {
        String fallback = secureCookie ? "https://localhost" : "http://localhost:5173";
        String candidate = value == null || value.isBlank() ? fallback : value.trim();
        try {
            if (candidate.indexOf('\\') >= 0
                    || candidate.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
                throw new IllegalArgumentException();
            }
            URI origin = URI.create(candidate);
            String path = origin.getRawPath();
            if (!origin.isAbsolute()
                    || !Set.of("http", "https").contains(origin.getScheme().toLowerCase())
                    || origin.getHost() == null
                    || origin.isOpaque()
                    || origin.getRawUserInfo() != null
                    || origin.getRawQuery() != null
                    || origin.getRawFragment() != null
                    || path != null && !path.isBlank() && !"/".equals(path)) {
                throw new IllegalArgumentException();
            }
            return origin;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Configured storefront base URL must be an HTTP(S) origin");
        }
    }
}

package com.marketshop.interfaces.identity;

import com.marketshop.application.identity.AuthUseCase;
import com.marketshop.application.identity.AuthUseCase.BeginCommand;
import com.marketshop.application.identity.AuthUseCase.CompleteCommand;
import com.marketshop.application.identity.AuthUseCase.DevLoginCommand;
import com.marketshop.application.identity.AuthUseCase.LoginResult;
import com.marketshop.interfaces.security.StpUserKit;
import com.marketshop.interfaces.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthUseCase authUseCase;

    public AuthController(AuthUseCase authUseCase) {
        this.authUseCase = authUseCase;
    }

    @GetMapping("/wechat/authorize")
    public ApiResponse<AuthUseCase.StartResult> authorize(
            @RequestParam String scene,
            @RequestParam(required = false) String inviteCode,
            @RequestParam String redirectUri
    ) {
        return ApiResponse.ok(authUseCase.begin(new BeginCommand(scene, inviteCode, redirectUri)));
    }

    @GetMapping("/wechat/callback")
    public ResponseEntity<Void> callback(@RequestParam String code, @RequestParam String state) {
        LoginResult result = authUseCase.complete(new CompleteCommand(code, state));
        establishSession(result);
        URI location = URI.create(result.redirectUri() + (result.redirectUri().contains("?") ? "&" : "?")
                + "wechatLogin=success");
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location.toString())
                .build();
    }

    @PostMapping("/wechat/complete")
    public ApiResponse<SessionView> complete(@Valid @RequestBody CompleteRequest request) {
        LoginResult result = authUseCase.complete(new CompleteCommand(request.code(), request.state()));
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
        return new SessionView(
                StpUserKit.logic().getTokenName(),
                StpUserKit.logic().getTokenValue(),
                result.publicId(),
                result.nickname(),
                result.newlyRegistered()
        );
    }

    public record CompleteRequest(@NotBlank String code, @NotBlank String state) {
    }

    public record DevLoginRequest(@NotBlank String openId, String nickname, String inviteCode) {
    }

    public record SessionView(
            String tokenName,
            String tokenValue,
            String publicId,
            String nickname,
            boolean newlyRegistered
    ) {
    }
}

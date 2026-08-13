package com.marketshop.interfaces.identity;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.marketshop.application.identity.AuthUseCase;
import com.marketshop.application.identity.AuthUseCase.DevLoginCommand;
import com.marketshop.application.identity.AuthUseCase.LoginResult;
import com.marketshop.application.identity.AuthUseCase.MiniprogramLoginCommand;
import com.marketshop.application.identity.AuthUseCase.MiniprogramRegistrationCommand;
import com.marketshop.interfaces.security.AccountSessionEpochGuard;
import com.marketshop.interfaces.security.StpUserKit;
import com.marketshop.interfaces.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthUseCase authUseCase;
    private final AccountSessionEpochGuard sessionEpochGuard;

    public AuthController(AuthUseCase authUseCase, AccountSessionEpochGuard sessionEpochGuard) {
        this.authUseCase = authUseCase;
        this.sessionEpochGuard = sessionEpochGuard;
    }

    @PostMapping("/wechat/miniprogram/login")
    public ApiResponse<MiniprogramLoginView> miniprogramLogin(
            @Valid @RequestBody MiniprogramLoginRequest request
    ) {
        LoginResult result = authUseCase.miniprogramLogin(new MiniprogramLoginCommand(
                request.code()
        ));
        return ApiResponse.ok(establishMiniprogramSession(result));
    }

    @PostMapping("/wechat/miniprogram/register")
    public ApiResponse<MiniprogramLoginView> miniprogramRegister(
            @Valid @RequestBody MiniprogramRegistrationRequest request
    ) {
        LoginResult result = authUseCase.miniprogramRegister(new MiniprogramRegistrationCommand(
                request.code(),
                request.inviteCode(),
                request.sponsorClaimSecret()
        ));
        return ApiResponse.ok(establishMiniprogramSession(result));
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

    private static MiniprogramLoginView establishMiniprogramSession(LoginResult result) {
        StpUserKit.logic().login(result.userId());
        var session = StpUserKit.logic().getTokenSession();
        session.set("publicId", result.publicId());
        session.set("nickname", result.nickname());
        session.set("authEpoch", result.authEpoch());
        return new MiniprogramLoginView(
                StpUserKit.logic().getTokenValue(),
                result.publicId(),
                result.nickname(),
                result.newlyRegistered()
        );
    }

    public record MiniprogramLoginRequest(@NotBlank String code) {
        @JsonAnySetter
        public void rejectUnknownField(String fieldName, Object ignoredValue) {
            throw new IllegalArgumentException("Unsupported login request field: " + fieldName);
        }
    }

    public record MiniprogramRegistrationRequest(
            @NotBlank String code,
            String inviteCode,
            String sponsorClaimSecret
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String fieldName, Object ignoredValue) {
            throw new IllegalArgumentException("Unsupported registration request field: " + fieldName);
        }
    }

    public record DevLoginRequest(@NotBlank String openId, String nickname, String inviteCode) {
    }

    public record SessionView(
            String publicId,
            String nickname,
            boolean newlyRegistered
    ) {
    }

    public record MiniprogramLoginView(
            String token,
            String publicId,
            String nickname,
            boolean newlyRegistered
    ) {
    }
}

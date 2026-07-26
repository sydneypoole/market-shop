package com.marketshop.interfaces.identity;

import com.marketshop.application.identity.AdminAuthUseCase;
import com.marketshop.application.identity.AdminAuthUseCase.LoginResult;
import com.marketshop.application.identity.AdminManagementUseCase;
import com.marketshop.application.identity.AdminManagementUseCase.ChangePasswordCommand;
import com.marketshop.interfaces.security.StpAdminKit;
import com.marketshop.interfaces.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/auth")
public class AdminAuthController {

    private final AdminAuthUseCase authUseCase;
    private final AdminManagementUseCase management;

    public AdminAuthController(AdminAuthUseCase authUseCase, AdminManagementUseCase management) {
        this.authUseCase = authUseCase;
        this.management = management;
    }

    @PostMapping("/login")
    public ApiResponse<AdminSessionView> login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = authUseCase.login(new AdminAuthUseCase.LoginCommand(
                request.username(),
                request.password()
        ));
        StpAdminKit.logic().login(result.adminId());
        var session = StpAdminKit.logic().getTokenSession();
        session.set("username", result.username());
        session.set("displayName", result.displayName());
        session.set("roles", result.roles());
        session.set("permissions", result.permissions());
        session.set("mustChangePassword", result.mustChangePassword());
        return ApiResponse.ok(new AdminSessionView(
                StpAdminKit.logic().getTokenName(),
                StpAdminKit.logic().getTokenValue(),
                result.username(),
                result.displayName(),
                result.mustChangePassword(),
                result.roles(),
                result.permissions()
        ));
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        var session = StpAdminKit.logic().getTokenSession();
        return ApiResponse.ok(Map.of(
                "adminId", StpAdminKit.logic().getLoginIdAsLong(),
                "username", session.getString("username"),
                "displayName", session.getString("displayName"),
                "mustChangePassword", Boolean.TRUE.equals(session.get("mustChangePassword")),
                "roles", toStringSet(session.get("roles")),
                "permissions", toStringSet(session.get("permissions"))
        ));
    }

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        long adminId = StpAdminKit.logic().getLoginIdAsLong();
        management.changePassword(adminId, new ChangePasswordCommand(
                request.currentPassword(),
                request.newPassword()
        ));
        StpAdminKit.logic().getTokenSession().set("mustChangePassword", false);
        return ApiResponse.ok(null);
    }

    private Set<String> toStringSet(Object value) {
        if (!(value instanceof Set<?> values)) {
            return Set.of();
        }
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.toUnmodifiableSet());
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        StpAdminKit.logic().logout();
        return ApiResponse.ok(null);
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    public record AdminSessionView(
            String tokenName,
            String tokenValue,
            String username,
            String displayName,
            boolean mustChangePassword,
            Set<String> roles,
            Set<String> permissions
    ) {
    }
}

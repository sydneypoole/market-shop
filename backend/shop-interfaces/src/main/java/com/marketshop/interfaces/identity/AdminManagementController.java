package com.marketshop.interfaces.identity;

import com.marketshop.application.identity.AdminManagementUseCase;
import com.marketshop.application.identity.AdminManagementUseCase.CreateAdminCommand;
import com.marketshop.application.identity.AdminManagementUseCase.LinkUserCommand;
import com.marketshop.application.identity.AdminManagementUseCase.ResetPasswordCommand;
import com.marketshop.application.identity.AdminManagementUseCase.RolesCommand;
import com.marketshop.application.identity.AdminManagementUseCase.SensitiveCommand;
import com.marketshop.application.identity.AdminManagementUseCase.SaveRoleCommand;
import com.marketshop.application.identity.AdminManagementUseCase.StatusCommand;
import com.marketshop.interfaces.security.StpAdminKit;
import com.marketshop.interfaces.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminManagementController {

    private final AdminManagementUseCase management;

    public AdminManagementController(AdminManagementUseCase management) {
        this.management = management;
    }

    @GetMapping("/accounts")
    public ApiResponse<List<AdminManagementUseCase.AdminView>> accounts() {
        StpAdminKit.requirePermission("admin:account:manage");
        return ApiResponse.ok(management.admins());
    }

    @GetMapping("/roles")
    public ApiResponse<List<AdminManagementUseCase.RoleView>> roles() {
        StpAdminKit.requirePermission("admin:role:manage");
        return ApiResponse.ok(management.roles());
    }

    @GetMapping("/permissions")
    public ApiResponse<Set<String>> permissions() {
        StpAdminKit.requirePermission("admin:role:manage");
        return ApiResponse.ok(management.permissions());
    }

    @PostMapping("/roles")
    public ApiResponse<AdminManagementUseCase.RoleView> saveRole(
            @Valid @RequestBody SaveRoleRequest request
    ) {
        StpAdminKit.requirePermission("admin:role:manage");
        return ApiResponse.ok(management.saveRole(actorId(), new SaveRoleCommand(
                request.code(),
                request.name(),
                request.permissions(),
                request.currentPassword(),
                request.reason()
        )));
    }

    @DeleteMapping("/roles/{roleCode}")
    public ApiResponse<Void> deleteRole(
            @PathVariable String roleCode,
            @Valid @RequestBody SensitiveRequest request
    ) {
        StpAdminKit.requirePermission("admin:role:manage");
        management.deleteRole(actorId(), roleCode,
                new SensitiveCommand(request.currentPassword(), request.reason()));
        return ApiResponse.ok(null);
    }

    @PostMapping("/accounts")
    public ApiResponse<AdminManagementUseCase.AdminView> create(@Valid @RequestBody CreateRequest request) {
        StpAdminKit.requirePermission("admin:account:manage");
        return ApiResponse.ok(management.create(actorId(), new CreateAdminCommand(
                request.username(),
                request.displayName(),
                request.temporaryPassword(),
                request.linkedUserId(),
                request.roles(),
                request.currentPassword(),
                request.reason()
        )));
    }

    @PutMapping("/accounts/{adminId}/status")
    public ApiResponse<Void> status(@PathVariable long adminId, @Valid @RequestBody StatusRequest request) {
        StpAdminKit.requirePermission("admin:account:manage");
        management.changeStatus(actorId(), adminId,
                new StatusCommand(request.currentPassword(), request.status(), request.reason()));
        return ApiResponse.ok(null);
    }

    @PostMapping("/accounts/{adminId}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable long adminId,
                                           @Valid @RequestBody ResetPasswordRequest request) {
        StpAdminKit.requirePermission("admin:account:manage");
        management.resetPassword(actorId(), adminId,
                new ResetPasswordCommand(request.currentPassword(), request.temporaryPassword(), request.reason()));
        return ApiResponse.ok(null);
    }

    @PostMapping("/accounts/{adminId}/unlock")
    public ApiResponse<Void> unlock(@PathVariable long adminId, @Valid @RequestBody SensitiveRequest request) {
        StpAdminKit.requirePermission("admin:account:manage");
        management.unlock(actorId(), adminId, new SensitiveCommand(request.currentPassword(), request.reason()));
        return ApiResponse.ok(null);
    }

    @PutMapping("/accounts/{adminId}/roles")
    public ApiResponse<Void> roles(@PathVariable long adminId, @Valid @RequestBody RolesRequest request) {
        StpAdminKit.requirePermission("admin:role:manage");
        management.assignRoles(actorId(), adminId,
                new RolesCommand(request.currentPassword(), request.roles(), request.reason()));
        return ApiResponse.ok(null);
    }

    @PutMapping("/accounts/{adminId}/linked-user")
    public ApiResponse<Void> linkedUser(@PathVariable long adminId, @Valid @RequestBody LinkUserRequest request) {
        StpAdminKit.requirePermission("admin:account:manage");
        management.linkUser(actorId(), adminId,
                new LinkUserCommand(request.currentPassword(), request.linkedUserId(), request.reason()));
        return ApiResponse.ok(null);
    }

    private static long actorId() {
        return StpAdminKit.logic().getLoginIdAsLong();
    }

    public record CreateRequest(
            @NotBlank String username,
            @NotBlank String displayName,
            @Size(min = 12, max = 72) String temporaryPassword,
            Long linkedUserId,
            @NotEmpty Set<String> roles,
            @NotBlank String currentPassword,
            @NotBlank String reason
    ) {
    }

    public record StatusRequest(@NotBlank String currentPassword, @NotBlank String status,
                                @NotBlank String reason) {
    }

    public record ResetPasswordRequest(@NotBlank String currentPassword,
                                       @Size(min = 12, max = 72) String temporaryPassword,
                                       @NotBlank String reason) {
    }

    public record SensitiveRequest(@NotBlank String currentPassword, @NotBlank String reason) {
    }

    public record RolesRequest(@NotBlank String currentPassword, @NotEmpty Set<String> roles,
                               @NotBlank String reason) {
    }

    public record SaveRoleRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotEmpty Set<String> permissions,
            @NotBlank String currentPassword,
            @NotBlank String reason
    ) {
    }

    public record LinkUserRequest(@NotBlank String currentPassword, Long linkedUserId,
                                  @NotBlank String reason) {
    }
}

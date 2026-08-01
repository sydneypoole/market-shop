package com.marketshop.application.identity;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface AdminManagementUseCase {

    List<AdminView> admins();

    List<RoleView> roles();

    Set<String> permissions();

    RoleView saveRole(long actorAdminId, SaveRoleCommand command);

    void deleteRole(long actorAdminId, String roleCode, SensitiveCommand command);

    AdminView create(long actorAdminId, CreateAdminCommand command);

    PasswordChangeResult changePassword(long adminId, ChangePasswordCommand command);

    void resetPassword(long actorAdminId, long targetAdminId, ResetPasswordCommand command);

    void changeStatus(long actorAdminId, long targetAdminId, StatusCommand command);

    void unlock(long actorAdminId, long targetAdminId, SensitiveCommand command);

    void assignRoles(long actorAdminId, long targetAdminId, RolesCommand command);

    void linkUser(long actorAdminId, long targetAdminId, LinkUserCommand command);

    record CreateAdminCommand(
            String username,
            String displayName,
            String temporaryPassword,
            Long linkedUserId,
            Set<String> roles,
            String currentPassword,
            String reason
    ) {
    }

    record ChangePasswordCommand(String currentPassword, String newPassword) {
    }

    record PasswordChangeResult(long authEpoch) {
    }

    record ResetPasswordCommand(String currentPassword, String temporaryPassword, String reason) {
    }

    record StatusCommand(String currentPassword, String status, String reason) {
    }

    record SensitiveCommand(String currentPassword, String reason) {
    }

    record RolesCommand(String currentPassword, Set<String> roles, String reason) {
    }

    record LinkUserCommand(String currentPassword, Long linkedUserId, String reason) {
    }

    record SaveRoleCommand(
            String code,
            String name,
            Set<String> permissions,
            String currentPassword,
            String reason
    ) {
    }

    record AdminView(
            long id,
            String username,
            String displayName,
            String status,
            Long linkedUserId,
            boolean mustChangePassword,
            int failedAttempts,
            Instant lockedUntil,
            Instant lastLoginAt,
            Set<String> roles
    ) {
    }

    record RoleView(String code, String name, boolean builtin, Set<String> permissions) {
    }
}

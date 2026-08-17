package com.marketshop.application.identity;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.audit.AdminAuditPort.AuditRecord;
import com.marketshop.application.identity.AdminManagementUseCase.AdminView;
import com.marketshop.application.identity.AdminManagementUseCase.RoleView;
import com.marketshop.application.identity.IdentityPorts.AdminCredential;
import com.marketshop.application.identity.IdentityPorts.AdminIdentityPort;
import com.marketshop.application.identity.IdentityPorts.PasswordHasher;
import com.marketshop.domain.shared.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Transactional
public class AdminManagementApplicationService implements AdminManagementUseCase {

    private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9._-]{3,64}");
    private static final Pattern ROLE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");

    private final AdminManagementPort managementPort;
    private final AdminIdentityPort identityPort;
    private final PasswordHasher passwordHasher;
    private final AdminAuditPort auditPort;
    private final AccountSessionControlPort sessionControlPort;

    public AdminManagementApplicationService(
            AdminManagementPort managementPort,
            AdminIdentityPort identityPort,
            PasswordHasher passwordHasher,
            AdminAuditPort auditPort,
            AccountSessionControlPort sessionControlPort
    ) {
        this.managementPort = managementPort;
        this.identityPort = identityPort;
        this.passwordHasher = passwordHasher;
        this.auditPort = auditPort;
        this.sessionControlPort = sessionControlPort;
    }

    @Override
    public List<AdminView> admins() {
        return managementPort.admins();
    }

    @Override
    public List<RoleView> roles() {
        return managementPort.roles();
    }

    @Override
    public Set<String> permissions() {
        return managementPort.permissions();
    }

    @Override
    public RoleView saveRole(long actorAdminId, SaveRoleCommand command) {
        requireSuperAdmin(actorAdminId, command.currentPassword());
        String reason = requiredReason(command.reason());
        String code = text(command.code(), "ADMIN_ROLE_CODE_REQUIRED", "角色编码不能为空").toUpperCase();
        if (!ROLE_CODE.matcher(code).matches()) {
            throw new DomainException("ADMIN_ROLE_CODE_INVALID", "角色编码必须为 3-64 位大写字母、数字或下划线");
        }
        RoleView existing = roles().stream().filter(role -> role.code().equals(code)).findFirst().orElse(null);
        if (existing != null && existing.builtin()) {
            throw new DomainException("ADMIN_BUILTIN_ROLE_IMMUTABLE", "内置角色权限不可修改");
        }
        String name = text(command.name(), "ADMIN_ROLE_NAME_REQUIRED", "角色名称不能为空");
        if (command.permissions() == null || command.permissions().isEmpty()
                || !permissions().containsAll(command.permissions())) {
            throw new DomainException("ADMIN_PERMISSION_INVALID", "角色至少包含一个有效权限");
        }
        Set<Long> affectedAdmins = existing == null ? Set.of() : managementPort.adminIdsWithRole(code);
        RoleView saved = managementPort.saveRole(code, name, Set.copyOf(command.permissions()));
        affectedAdmins.forEach(managementPort::incrementAdminAuthEpoch);
        auditRole(actorAdminId, "ADMIN_ROLE_SAVED", code, existing == null ? null : roleJson(existing),
                roleJson(saved), reason);
        affectedAdmins.forEach(sessionControlPort::invalidateAdminSessions);
        return saved;
    }

    @Override
    public void deleteRole(long actorAdminId, String roleCode, SensitiveCommand command) {
        requireSuperAdmin(actorAdminId, command.currentPassword());
        String reason = requiredReason(command.reason());
        String code = text(roleCode, "ADMIN_ROLE_CODE_REQUIRED", "角色编码不能为空").toUpperCase();
        RoleView existing = roles().stream()
                .filter(role -> role.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new DomainException("ADMIN_ROLE_NOT_FOUND", "后台角色不存在"));
        if (existing.builtin()) {
            throw new DomainException("ADMIN_BUILTIN_ROLE_IMMUTABLE", "内置角色不可删除");
        }
        Set<Long> affectedAdmins = managementPort.adminIdsWithRole(code);
        managementPort.deleteRole(code);
        affectedAdmins.forEach(managementPort::incrementAdminAuthEpoch);
        auditRole(actorAdminId, "ADMIN_ROLE_DELETED", code, roleJson(existing), null, reason);
        affectedAdmins.forEach(sessionControlPort::invalidateAdminSessions);
    }

    @Override
    public AdminView create(long actorAdminId, CreateAdminCommand command) {
        requireSuperAdmin(actorAdminId, command.currentPassword());
        String reason = requiredReason(command.reason());
        String username = text(command.username(), "ADMIN_USERNAME_REQUIRED", "账号不能为空");
        if (!USERNAME.matcher(username).matches()) {
            throw new DomainException("ADMIN_USERNAME_INVALID", "账号仅支持 3-64 位字母、数字、点、下划线或短横线");
        }
        if (managementPort.usernameExists(username)) {
            throw new DomainException("ADMIN_USERNAME_EXISTS", "后台账号已存在");
        }
        validatePassword(command.temporaryPassword());
        validateRoles(command.roles());
        validateLinkedUser(command.linkedUserId());
        AdminView created = managementPort.create(
                username,
                passwordHasher.encode(command.temporaryPassword()),
                text(command.displayName(), "ADMIN_DISPLAY_NAME_REQUIRED", "显示名称不能为空"),
                command.linkedUserId(),
                Set.copyOf(command.roles()),
                actorAdminId
        );
        audit(actorAdminId, "ADMIN_ACCOUNT_CREATED", created.id(), null, adminJson(created), reason);
        return created;
    }

    @Override
    public PasswordChangeResult changePassword(long adminId, ChangePasswordCommand command) {
        AdminCredential current = verify(adminId, command.currentPassword());
        validatePassword(command.newPassword());
        if (passwordHasher.matches(command.newPassword(), current.passwordHash())) {
            throw new DomainException("ADMIN_PASSWORD_REUSED", "新密码不能与当前密码相同");
        }
        managementPort.updatePassword(adminId, passwordHasher.encode(command.newPassword()), false);
        audit(adminId, "ADMIN_PASSWORD_CHANGED", adminId, null,
                "{\"mustChangePassword\":false}", "管理员主动修改密码");
        sessionControlPort.invalidateAdminSessions(adminId);
        AdminCredential refreshed = identityPort.findById(adminId)
                .orElseThrow(() -> new DomainException("ADMIN_NOT_FOUND", "后台账号不存在"));
        return new PasswordChangeResult(refreshed.authEpoch());
    }

    @Override
    public void resetPassword(long actorAdminId, long targetAdminId, ResetPasswordCommand command) {
        requireSuperAdmin(actorAdminId, command.currentPassword());
        requireTarget(targetAdminId);
        validatePassword(command.temporaryPassword());
        managementPort.updatePassword(targetAdminId, passwordHasher.encode(command.temporaryPassword()), true);
        audit(actorAdminId, "ADMIN_PASSWORD_RESET", targetAdminId, null,
                "{\"mustChangePassword\":true}", requiredReason(command.reason()));
        sessionControlPort.invalidateAdminSessions(targetAdminId);
    }

    @Override
    public void changeStatus(long actorAdminId, long targetAdminId, StatusCommand command) {
        requireSuperAdmin(actorAdminId, command.currentPassword());
        String status = text(command.status(), "ADMIN_STATUS_REQUIRED", "账号状态不能为空").toUpperCase();
        if (actorAdminId == targetAdminId && "DISABLED".equals(status)) {
            throw new DomainException("ADMIN_SELF_DISABLE_FORBIDDEN", "不能停用当前登录账号");
        }
        AdminView before = requireTarget(targetAdminId);
        if (!STATUSES.contains(status)) {
            throw new DomainException("ADMIN_STATUS_INVALID", "后台账号状态无效");
        }
        managementPort.updateStatus(targetAdminId, status);
        audit(actorAdminId, "ADMIN_STATUS_CHANGED", targetAdminId, adminJson(before),
                "{\"status\":" + quote(status) + "}", requiredReason(command.reason()));
        sessionControlPort.invalidateAdminSessions(targetAdminId);
    }

    @Override
    public void unlock(long actorAdminId, long targetAdminId, SensitiveCommand command) {
        requireSuperAdmin(actorAdminId, command.currentPassword());
        AdminView before = requireTarget(targetAdminId);
        managementPort.unlock(targetAdminId);
        audit(actorAdminId, "ADMIN_UNLOCKED", targetAdminId, adminJson(before),
                "{\"failedAttempts\":0,\"lockedUntil\":null}", requiredReason(command.reason()));
        sessionControlPort.invalidateAdminSessions(targetAdminId);
    }

    @Override
    public void assignRoles(long actorAdminId, long targetAdminId, RolesCommand command) {
        requireSuperAdmin(actorAdminId, command.currentPassword());
        AdminView before = requireTarget(targetAdminId);
        validateRoles(command.roles());
        if (actorAdminId == targetAdminId && !command.roles().contains("SUPER_ADMIN")) {
            throw new DomainException("ADMIN_SELF_ROLE_DOWNGRADE_FORBIDDEN", "不能移除当前账号的超级管理员角色");
        }
        managementPort.replaceRoles(targetAdminId, Set.copyOf(command.roles()), actorAdminId);
        audit(actorAdminId, "ADMIN_ROLES_CHANGED", targetAdminId, adminJson(before),
                "{\"roles\":" + stringArray(command.roles()) + "}", requiredReason(command.reason()));
        sessionControlPort.invalidateAdminSessions(targetAdminId);
    }

    @Override
    public void linkUser(long actorAdminId, long targetAdminId, LinkUserCommand command) {
        requireSuperAdmin(actorAdminId, command.currentPassword());
        AdminView before = requireTarget(targetAdminId);
        validateLinkedUser(command.linkedUserId());
        managementPort.updateLinkedUser(targetAdminId, command.linkedUserId());
        audit(actorAdminId, "ADMIN_LINKED_USER_CHANGED", targetAdminId, adminJson(before),
                "{\"linkedUserId\":" + (command.linkedUserId() == null ? "null" : command.linkedUserId()) + "}",
                requiredReason(command.reason()));
    }

    private void requireSuperAdmin(long actorAdminId, String currentPassword) {
        verify(actorAdminId, currentPassword);
        if (!managementPort.hasRole(actorAdminId, "SUPER_ADMIN")) {
            throw new DomainException("ADMIN_SUPER_REQUIRED", "此敏感操作仅超级管理员可执行");
        }
    }

    private AdminCredential verify(long adminId, String password) {
        AdminCredential credential = identityPort.findById(adminId)
                .orElseThrow(() -> new DomainException("ADMIN_NOT_FOUND", "后台账号不存在"));
        if (!passwordHasher.matches(password, credential.passwordHash())) {
            throw new DomainException("ADMIN_REAUTH_FAILED", "当前管理员密码校验失败");
        }
        return credential;
    }

    private AdminView requireTarget(long adminId) {
        return managementPort.admins().stream()
                .filter(admin -> admin.id() == adminId)
                .findFirst()
                .orElseThrow(() -> new DomainException("ADMIN_NOT_FOUND", "后台账号不存在"));
    }

    private void validateRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new DomainException("ADMIN_ROLE_REQUIRED", "至少分配一个后台角色");
        }
        Set<String> valid = this.roles().stream().map(RoleView::code).collect(java.util.stream.Collectors.toSet());
        if (!valid.containsAll(roles)) {
            throw new DomainException("ADMIN_ROLE_INVALID", "包含不存在的后台角色");
        }
    }

    private void validateLinkedUser(Long userId) {
        if (userId != null && !managementPort.userExists(userId)) {
            throw new DomainException("LINKED_USER_NOT_FOUND", "关联会员不存在");
        }
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 72
                || password.chars().noneMatch(Character::isLetter)
                || password.chars().noneMatch(Character::isDigit)) {
            throw new DomainException("ADMIN_PASSWORD_WEAK", "密码必须为 12-72 位且同时包含字母和数字");
        }
    }

    private void audit(long actorId, String action, long targetId, String beforeJson, String afterJson, String reason) {
        audit(actorId, action, "ADMIN_ACCOUNT", String.valueOf(targetId), beforeJson, afterJson, reason);
    }

    private void auditRole(long actorId, String action, String roleCode,
                           String beforeJson, String afterJson, String reason) {
        audit(actorId, action, "ADMIN_ROLE", roleCode, beforeJson, afterJson, reason);
    }

    private void audit(long actorId, String action, String resourceType, String resourceId,
                       String beforeJson, String afterJson, String reason) {
        auditPort.record(new AuditRecord(
                "ADMIN",
                String.valueOf(actorId),
                action,
                resourceType,
                resourceId,
                beforeJson,
                afterJson,
                reason,
                UUID.randomUUID().toString(),
                null,
                "application-service",
                Instant.now()
        ));
    }

    private static String adminJson(AdminView view) {
        return "{\"id\":" + view.id()
                + ",\"username\":" + quote(view.username())
                + ",\"status\":" + quote(view.status())
                + ",\"linkedUserId\":" + (view.linkedUserId() == null ? "null" : view.linkedUserId())
                + ",\"roles\":" + stringArray(view.roles()) + "}";
    }

    private static String roleJson(RoleView view) {
        return "{\"code\":" + quote(view.code())
                + ",\"name\":" + quote(view.name())
                + ",\"permissions\":" + stringArray(view.permissions()) + "}";
    }

    private static String stringArray(Set<String> values) {
        return values.stream().sorted().map(AdminManagementApplicationService::quote)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String requiredReason(String reason) {
        return text(reason, "ADMIN_REASON_REQUIRED", "敏感操作必须填写原因");
    }

    private static String text(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new DomainException(code, message);
        }
        return value.trim();
    }
}

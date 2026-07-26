package com.marketshop.infrastructure.identity;

import com.marketshop.application.identity.IdentityPorts.AdminCredential;
import com.marketshop.application.identity.IdentityPorts.AdminIdentityPort;
import com.marketshop.application.identity.IdentityPorts.RegistrationResult;
import com.marketshop.application.identity.IdentityPorts.UserIdentityPort;
import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.application.identity.AdminManagementPort;
import com.marketshop.application.identity.AdminManagementUseCase.AdminView;
import com.marketshop.application.identity.AdminManagementUseCase.RoleView;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.IdentityMapper;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminAccountPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminCredentialRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminManagementRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.ExternalIdentityPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.UserAccountPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.UserLoginRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class MyBatisIdentityAdapter implements UserIdentityPort, AdminIdentityPort, AdminManagementPort {

    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);

    private final IdentityMapper mapper;

    public MyBatisIdentityAdapter(IdentityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public RegistrationResult findOrRegister(WeChatIdentity identity, String inviteCode) {
        UserLoginRow existing = mapper.findUserByExternal(identity.provider(), identity.appId(), identity.openId());
        if (existing != null) {
            mapper.touchUserLogin(existing.id);
            return registration(existing, false);
        }

        UserLoginRow unionUser = identity.unionId() == null ? null : mapper.findUserByUnionId(identity.unionId());
        if (unionUser != null) {
            insertExternalIdentity(identity, unionUser.id);
            mapper.touchUserLogin(unionUser.id);
            return registration(unionUser, false);
        }

        if (inviteCode == null || inviteCode.isBlank()) {
            throw new DomainException("INVITE_CODE_REQUIRED", "首次注册必须填写有效邀请码");
        }
        InvitationRow invitation = mapper.lockInvitation(inviteCode.trim());
        validateInvitation(invitation);

        UserAccountPo user = new UserAccountPo();
        user.publicId = newPublicId();
        user.status = "ACTIVE";
        user.nickname = identity.nickname() == null || identity.nickname().isBlank() ? "微信用户" : identity.nickname();
        user.avatarUrl = identity.avatarUrl();
        mapper.insertUser(user);
        insertExternalIdentity(identity, user.id);
        if (identity.unionId() != null && !identity.unionId().isBlank()) {
            try {
                mapper.insertUnionPrincipal(identity.unionId(), user.id);
            } catch (DuplicateKeyException duplicate) {
                throw new DomainException("WECHAT_UNION_CONFLICT", "微信身份已绑定其他账号");
            }
        }
        mapper.insertCustomerProfile(user.id);
        mapper.insertRelation(user.id, invitation.inviterUserId, invitation.id);
        mapper.insertBasicMembership(user.id);
        mapper.insertLedgerAccount(user.id);
        mapper.incrementInvitation(invitation.id);
        return new RegistrationResult(user.id, user.publicId, user.nickname, true);
    }

    @Override
    public Optional<AdminCredential> findByUsername(String username) {
        AdminCredentialRow row = mapper.findAdminByUsername(username);
        return credential(row);
    }

    @Override
    public Optional<AdminCredential> findById(long adminId) {
        return credential(mapper.findAdminById(adminId));
    }

    private Optional<AdminCredential> credential(AdminCredentialRow row) {
        if (row == null) {
            return Optional.empty();
        }
        Set<String> roles = new LinkedHashSet<>(mapper.findAdminRoles(row.id));
        Set<String> permissions = new LinkedHashSet<>(mapper.findAdminPermissions(row.id));
        return Optional.of(new AdminCredential(
                row.id,
                row.username,
                row.passwordHash,
                row.displayName,
                row.status,
                Boolean.TRUE.equals(row.mustChangePassword),
                row.failedAttempts == null ? 0 : row.failedAttempts,
                toInstant(row.lockedUntil),
                Set.copyOf(roles),
                Set.copyOf(permissions)
        ));
    }

    @Override
    public void recordFailure(long adminId, int nextFailedAttempts, Instant lockedUntil) {
        mapper.updateAdminFailure(adminId, nextFailedAttempts, toLocalDateTime(lockedUntil));
    }

    @Override
    public void recordSuccess(long adminId) {
        mapper.updateAdminSuccess(adminId);
    }

    @Override
    public java.util.List<AdminView> admins() {
        return mapper.admins().stream().map(this::adminView).toList();
    }

    @Override
    public java.util.List<RoleView> roles() {
        return mapper.roles().stream()
                .map(role -> new RoleView(
                        role.code,
                        role.name,
                        Boolean.TRUE.equals(role.builtin),
                        Set.copyOf(mapper.rolePermissions(role.code))
                ))
                .toList();
    }

    @Override
    public Set<String> permissions() {
        return Set.copyOf(mapper.permissions());
    }

    @Override
    @Transactional
    public RoleView saveRole(String code, String name, Set<String> permissions) {
        var existing = mapper.role(code);
        if (existing == null) {
            mapper.insertRole(code, name);
        } else if (mapper.updateCustomRole(code, name) != 1) {
            throw new DomainException("ADMIN_BUILTIN_ROLE_IMMUTABLE", "内置角色权限不可修改");
        }
        mapper.deleteCustomRolePermissions(code);
        for (String permission : permissions) {
            if (mapper.insertCustomRolePermission(code, permission) != 1) {
                throw new DomainException("ADMIN_PERMISSION_INVALID", "包含不存在的后台权限");
            }
        }
        var saved = mapper.role(code);
        return new RoleView(saved.code, saved.name, false, Set.copyOf(mapper.rolePermissions(code)));
    }

    @Override
    @Transactional
    public void deleteRole(String code) {
        if (mapper.roleAssignmentCount(code) > 0) {
            throw new DomainException("ADMIN_ROLE_IN_USE", "角色仍分配给后台账号，不能删除");
        }
        mapper.deleteCustomRolePermissions(code);
        if (mapper.deleteCustomRole(code) != 1) {
            throw new DomainException("ADMIN_ROLE_NOT_FOUND", "自定义角色不存在或不可删除");
        }
    }

    @Override
    public boolean usernameExists(String username) {
        return mapper.usernameExists(username) > 0;
    }

    @Override
    public boolean userExists(long userId) {
        return mapper.userExists(userId) > 0;
    }

    @Override
    public boolean hasRole(long adminId, String roleCode) {
        return mapper.adminHasRole(adminId, roleCode) > 0;
    }

    @Override
    @Transactional
    public AdminView create(String username, String passwordHash, String displayName, Long linkedUserId,
                            Set<String> roles, long grantedBy) {
        AdminAccountPo row = new AdminAccountPo();
        row.username = username;
        row.passwordHash = passwordHash;
        row.displayName = displayName;
        row.status = "ACTIVE";
        row.linkedUserId = linkedUserId;
        row.mustChangePassword = true;
        try {
            mapper.insertAdmin(row);
            for (String role : roles) {
                mapper.insertAdminRole(row.id, role, grantedBy);
            }
        } catch (DuplicateKeyException exception) {
            throw new DomainException("ADMIN_CONFLICT", "后台账号或关联会员已被占用");
        }
        return admins().stream().filter(admin -> admin.id() == row.id).findFirst()
                .orElseThrow(() -> new DomainException("ADMIN_CREATE_FAILED", "后台账号创建失败"));
    }

    @Override
    public void updatePassword(long adminId, String passwordHash, boolean mustChangePassword) {
        requireUpdated(mapper.updateAdminPassword(adminId, passwordHash, mustChangePassword));
    }

    @Override
    public void updateStatus(long adminId, String status) {
        requireUpdated(mapper.updateAdminStatus(adminId, status));
    }

    @Override
    public void unlock(long adminId) {
        requireUpdated(mapper.unlockAdmin(adminId));
    }

    @Override
    @Transactional
    public void replaceRoles(long adminId, Set<String> roles, long grantedBy) {
        mapper.deleteAdminRoles(adminId);
        for (String role : roles) {
            if (mapper.insertAdminRole(adminId, role, grantedBy) != 1) {
                throw new DomainException("ADMIN_ROLE_INVALID", "后台角色不存在");
            }
        }
    }

    @Override
    public void updateLinkedUser(long adminId, Long linkedUserId) {
        try {
            requireUpdated(mapper.updateAdminLinkedUser(adminId, linkedUserId));
        } catch (DuplicateKeyException exception) {
            throw new DomainException("ADMIN_LINKED_USER_CONFLICT", "该商城会员已关联其他后台账号");
        }
    }

    private AdminView adminView(AdminManagementRow row) {
        return new AdminView(
                row.id,
                row.username,
                row.displayName,
                row.status,
                row.linkedUserId,
                Boolean.TRUE.equals(row.mustChangePassword),
                row.failedAttempts == null ? 0 : row.failedAttempts,
                toInstant(row.lockedUntil),
                toInstant(row.lastLoginAt),
                Set.copyOf(mapper.findAdminRoles(row.id))
        );
    }

    private static void requireUpdated(int updated) {
        if (updated != 1) {
            throw new DomainException("ADMIN_NOT_FOUND", "后台账号不存在");
        }
    }

    private void insertExternalIdentity(WeChatIdentity identity, long userId) {
        ExternalIdentityPo external = new ExternalIdentityPo();
        external.userId = userId;
        external.provider = identity.provider();
        external.appId = identity.appId();
        external.openId = identity.openId();
        external.unionId = identity.unionId();
        mapper.insertExternalIdentity(external);
    }

    private static void validateInvitation(InvitationRow invitation) {
        if (invitation == null || !"ACTIVE".equals(invitation.status)) {
            throw new DomainException("INVITE_CODE_INVALID", "邀请码无效或已停用");
        }
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        if (invitation.expiresAt != null && !invitation.expiresAt.isAfter(now)) {
            throw new DomainException("INVITE_CODE_EXPIRED", "邀请码已过期");
        }
        if (invitation.maxUses != null && invitation.useCount >= invitation.maxUses) {
            throw new DomainException("INVITE_CODE_EXHAUSTED", "邀请码使用次数已达上限");
        }
    }

    private static RegistrationResult registration(UserLoginRow row, boolean created) {
        return new RegistrationResult(row.id, row.publicId, row.nickname, created);
    }

    private static String newPublicId() {
        String timestamp = String.format("%013d", System.currentTimeMillis());
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 13).toUpperCase();
        return timestamp + random;
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(BUSINESS_ZONE);
    }

    private static LocalDateTime toLocalDateTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, BUSINESS_ZONE);
    }
}

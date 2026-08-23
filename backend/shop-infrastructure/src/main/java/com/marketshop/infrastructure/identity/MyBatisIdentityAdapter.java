package com.marketshop.infrastructure.identity;

import com.marketshop.application.identity.IdentityPorts.AccountAuthState;
import com.marketshop.application.identity.IdentityPorts.AccountAuthStatePort;
import com.marketshop.application.identity.IdentityPorts.AdminCredential;
import com.marketshop.application.identity.IdentityPorts.AdminFailureResult;
import com.marketshop.application.identity.IdentityPorts.AdminIdentityPort;
import com.marketshop.application.identity.IdentityPorts.RegistrationResult;
import com.marketshop.application.identity.IdentityPorts.UserIdentityPort;
import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.application.identity.AdminManagementPort;
import com.marketshop.application.identity.MemberProfilePort;
import com.marketshop.application.identity.MemberProfilePort.AvatarMetadata;
import com.marketshop.application.identity.MemberProfilePort.ProfileRecord;
import com.marketshop.application.identity.AdminManagementUseCase.AdminView;
import com.marketshop.application.identity.AdminManagementUseCase.RoleView;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.IdentityMapper;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InvitationEligibilityRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AccountAuthStateRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminAccountPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminCredentialRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminFailureRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminManagementRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.ExternalIdentityPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationOwnerRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.MemberProfileRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.SponsorClaimRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.UserAccountPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.UserLoginRow;
import com.marketshop.infrastructure.invitation.FixedInvitationCodes;
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
public class MyBatisIdentityAdapter
        implements UserIdentityPort, AdminIdentityPort, AdminManagementPort, AccountAuthStatePort,
        MemberProfilePort {

    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);
    private static final Set<String> SPONSOR_CLAIM_PROVIDERS = Set.of("WECHAT_MP");

    private final IdentityMapper mapper;

    public MyBatisIdentityAdapter(IdentityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public RegistrationResult findOrRegister(
            WeChatIdentity identity,
            String inviteCode,
            String sponsorClaimSecretHash
    ) {
        if (sponsorClaimSecretHash != null) {
            return findAndClaimSponsor(identity, sponsorClaimSecretHash);
        }

        UserLoginRow existing = mapper.findUserByExternal(
                identity.provider(), identity.appId(), identity.openId()
        );
        if (existing != null) {
            // Registration is idempotent for an already-bound identity. Never
            // mutate its superior or overwrite an existing verified profile.
            return registration(existing, false);
        }

        UserLoginRow unionUser = identity.unionId() == null
                ? null
                : mapper.findUserByUnionId(identity.unionId());
        if (unionUser != null) {
            insertExternalIdentity(identity, unionUser.id);
            return registration(unionUser, false);
        }

        if (mapper.countUnresolvedBootstrapInvitationRepairs() > 0) {
            throw new DomainException(
                    "BOOTSTRAP_INVITATION_REPAIR_REQUIRED",
                    "系统邀请码正在修复，请联系管理员完成配置"
            );
        }

        if (inviteCode == null || inviteCode.isBlank()) {
            throw new DomainException("INVITE_CODE_REQUIRED", "首次注册必须填写有效邀请码");
        }
        String normalizedInviteCode = inviteCode.trim();
        InvitationOwnerRow owner = mapper.findInvitationOwner(normalizedInviteCode);
        if (owner == null || owner.inviterUserId == null) {
            throw new DomainException("INVITE_CODE_INVALID", "邀请码无效或已停用");
        }
        InvitationEligibilityRow eligibility = mapper.lockInviterEligibility(owner.inviterUserId);
        InvitationRow invitation = mapper.lockInvitation(normalizedInviteCode);
        validateInvitation(invitation, owner.inviterUserId, eligibility);

        UserAccountPo user = new UserAccountPo();
        user.publicId = newPublicId();
        user.status = "ACTIVE";
        user.nickname = generatedNickname(user.publicId);
        user.avatarUrl = null;
        if (mapper.insertUser(user) != 1 || user.id == null) {
            throw new IllegalStateException("Member user was not created");
        }
        long userId = user.id;
        insertExternalIdentity(identity, userId);
        insertUnionPrincipal(identity, userId);
        mapper.insertCustomerProfile(userId);
        mapper.insertRelation(userId, invitation.inviterUserId, invitation.id);
        mapper.insertBasicMembership(userId);
        mapper.insertLedgerAccount(userId);
        insertFixedInvitation(userId);
        consumeInvitation(invitation);
        return new RegistrationResult(
                userId,
                user.publicId,
                user.nickname,
                user.status,
                0L,
                true,
                false
        );
    }

    @Override
    public void recordLogin(long userId) {
        if (mapper.touchUserLogin(userId) != 1) {
            throw new DomainException("MEMBER_NOT_FOUND", "会员账号不存在");
        }
    }

    @Override
    public ProfileRecord profile(long userId) {
        MemberProfileRow row = mapper.memberProfile(userId);
        if (row == null) {
            throw new DomainException("MEMBER_NOT_FOUND", "会员账号不存在");
        }
        return new ProfileRecord(
                row.userId,
                row.nickname,
                row.avatarUrl,
                row.phoneMasked,
                toInstant(row.phoneVerifiedAt),
                row.avatarObjectKey,
                row.avatarMediaType,
                row.avatarSha256,
                row.avatarSizeBytes,
                toInstant(row.avatarUpdatedAt),
                row.version == null ? 0 : row.version
        );
    }

    @Override
    public void updateWechatProfile(
            long userId,
            String nickname,
            String phoneMasked,
            Instant phoneVerifiedAt
    ) {
        if (mapper.updateWechatProfile(
                userId,
                nickname,
                phoneMasked,
                toLocalDateTime(phoneVerifiedAt)
        ) != 1) {
            throw new DomainException("MEMBER_NOT_FOUND", "会员账号不存在");
        }
    }

    @Override
    public void updateNickname(long userId, int expectedVersion, String nickname) {
        if (mapper.updateMemberNickname(userId, expectedVersion, nickname) != 1) {
            throw new DomainException("MEMBER_PROFILE_CONFLICT", "会员资料已变更，请重试");
        }
    }

    @Override
    public void replaceAvatar(
            long userId,
            int expectedVersion,
            String avatarUrl,
            AvatarMetadata avatar
    ) {
        if (mapper.replaceMemberAvatar(
                userId,
                expectedVersion,
                avatarUrl,
                avatar.objectKey(),
                avatar.mediaType(),
                avatar.sha256(),
                avatar.sizeBytes(),
                toLocalDateTime(avatar.updatedAt())
        ) != 1) {
            throw new DomainException("MEMBER_PROFILE_CONFLICT", "会员资料已变更，请重试");
        }
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
                row.authEpoch == null ? 0L : row.authEpoch,
                Set.copyOf(roles),
                Set.copyOf(permissions)
        ));
    }

    @Override
    @Transactional
    public AdminFailureResult recordFailure(long adminId, int lockThreshold, Instant lockedUntil) {
        mapper.incrementAdminFailure(adminId, lockThreshold, toLocalDateTime(lockedUntil));
        AdminFailureRow row = mapper.adminFailureState(adminId);
        if (row == null) {
            // The login path must not turn a concurrent delete/disable race
            // into an account-existence oracle.  Keep the public failure
            // contract identical to an invalid password.
            throw new DomainException("ADMIN_CREDENTIALS_INVALID", "用户名或密码错误");
        }
        return new AdminFailureResult(
                row.failedAttempts == null ? 0 : row.failedAttempts,
                toInstant(row.lockedUntil),
                row.authEpoch == null ? 0L : row.authEpoch
        );
    }

    @Override
    public void recordSuccess(long adminId) {
        mapper.updateAdminSuccess(adminId);
    }

    @Override
    public Optional<AccountAuthState> memberState(long userId) {
        return authState(mapper.memberAuthState(userId));
    }

    @Override
    public Optional<AccountAuthState> adminState(long adminId) {
        return authState(mapper.adminAuthState(adminId));
    }

    private Optional<AccountAuthState> authState(AccountAuthStateRow row) {
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new AccountAuthState(
                row.status,
                row.authEpoch == null ? 0L : row.authEpoch,
                toInstant(row.lockedUntil)
        ));
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
    public Set<Long> adminIdsWithRole(String roleCode) {
        return Set.copyOf(mapper.findAdminIdsByRole(roleCode));
    }

    @Override
    public void incrementAdminAuthEpoch(long adminId) {
        requireUpdated(mapper.incrementAdminAuthEpoch(adminId));
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
        requireUpdated(mapper.incrementAdminAuthEpoch(adminId));
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
        try {
            mapper.insertExternalIdentity(external);
        } catch (DuplicateKeyException duplicate) {
            // Only a duplicate from the external-identity insert represents the
            // optimistic openid race. Do not collapse unrelated duplicate-key
            // failures from user, relation, membership, ledger or audit writes
            // into this client-retryable conflict.
            throw new DomainException(
                    "MEMBER_REGISTRATION_CONFLICT",
                    "注册状态已变更，请重新授权后重试",
                    duplicate
            );
        }
    }

    private RegistrationResult claimSponsor(
            WeChatIdentity identity,
            SponsorClaimRow claim,
            String claimSecretHash
    ) {
        insertExternalIdentity(identity, claim.sponsorUserId);
        insertUnionPrincipal(identity, claim.sponsorUserId);
        int expectedVersion = claim.version == null ? -1 : claim.version;
        if (mapper.claimBootstrapSponsor(
                claim.id,
                expectedVersion,
                claimSecretHash,
                identity.provider(),
                identity.appId()
        ) != 1) {
            throw new DomainException(
                    "SPONSOR_CLAIM_CONFLICT",
                    "发起人认领状态已变更，请重新登录"
            );
        }
        return new RegistrationResult(
                claim.sponsorUserId,
                claim.publicId,
                claim.nickname,
                claim.userStatus,
                claim.authEpoch == null ? 0L : claim.authEpoch,
                false,
                true
        );
    }

    private RegistrationResult findAndClaimSponsor(
            WeChatIdentity identity,
            String claimSecretHash
    ) {
        if (!SPONSOR_CLAIM_PROVIDERS.contains(identity.provider())) {
            throw new DomainException("SPONSOR_CLAIM_PROVIDER_INVALID", "发起人只能使用真实微信身份认领");
        }
        SponsorClaimRow sponsorClaim = mapper.lockSponsorClaim(claimSecretHash);
        if (sponsorClaim == null) {
            throw new DomainException("SPONSOR_CLAIM_SECRET_INVALID", "发起人认领密钥无效或已使用");
        }
        UserLoginRow external = mapper.findUserByExternal(
                identity.provider(), identity.appId(), identity.openId()
        );
        UserLoginRow union = identity.unionId() == null
                ? null
                : mapper.findUserByUnionId(identity.unionId());
        if (external != null || union != null) {
            throw new DomainException(
                    "SPONSOR_CLAIM_IDENTITY_CONFLICT",
                    "当前微信身份已绑定其他会员，不能认领发起人"
            );
        }
        return claimSponsor(identity, sponsorClaim, claimSecretHash);
    }

    private void insertUnionPrincipal(WeChatIdentity identity, long userId) {
        if (identity.unionId() == null || identity.unionId().isBlank()) {
            return;
        }
        try {
            mapper.insertUnionPrincipal(identity.unionId(), userId);
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException("WECHAT_UNION_CONFLICT", "微信身份已绑定其他账号");
        }
    }

    private void consumeInvitation(InvitationRow invitation) {
        if (Boolean.TRUE.equals(invitation.bootstrap)) {
            if (mapper.consumeBootstrapInvitation(invitation.id) != 1) {
                throw new DomainException("INVITE_CODE_EXHAUSTED", "邀请码使用次数已达上限");
            }
            return;
        }
        mapper.incrementInvitation(invitation.id);
    }

    private void insertFixedInvitation(long userId) {
        for (int attempt = 0; attempt < FixedInvitationCodes.INSERT_ATTEMPTS; attempt++) {
            if (mapper.insertOrdinaryInvitation(userId, FixedInvitationCodes.generate()) == 1) {
                return;
            }
        }
        throw new DomainException("INVITATION_CREATE_FAILED", "固定邀请码生成失败，请重试");
    }

    private static void validateInvitation(
            InvitationRow invitation,
            Long expectedInviterUserId,
            InvitationEligibilityRow eligibility
    ) {
        if (invitation == null || invitation.id == null || invitation.inviterUserId == null
                || invitation.useCount == null || !"ACTIVE".equals(invitation.status)
                || expectedInviterUserId == null
                || !expectedInviterUserId.equals(invitation.inviterUserId)) {
            throw new DomainException("INVITE_CODE_INVALID", "邀请码无效或已停用");
        }
        if (eligibility == null
                || !"ACTIVE".equals(eligibility.userStatus)
                || !"ACTIVE".equals(eligibility.levelStatus)
                || !Boolean.TRUE.equals(eligibility.invitationEnabled)) {
            throw new DomainException("INVITE_CODE_INVALID", "邀请码发起人当前不具备邀请资格");
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
        return new RegistrationResult(
                row.id,
                row.publicId,
                row.nickname,
                row.status,
                row.authEpoch == null ? 0L : row.authEpoch,
                created,
                false
        );
    }

    private static String newPublicId() {
        String timestamp = String.format("%013d", System.currentTimeMillis());
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 13).toUpperCase();
        return timestamp + random;
    }

    private static String generatedNickname(String publicId) {
        return "宏杉会员-" + publicId;
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(BUSINESS_ZONE);
    }

    private static LocalDateTime toLocalDateTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, BUSINESS_ZONE);
    }
}

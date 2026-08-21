package com.marketshop.infrastructure.persistence.mapper;

import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InvitationEligibilityRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminAccountPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AccountAuthStateRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminCredentialRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminFailureRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminManagementRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.BootstrapInvitationRepairGuardRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.BootstrapInvitationRepairRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.ExternalIdentityPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationOwnerRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.MemberProfileRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.RoleRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.SponsorClaimRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.UserAccountPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.UserLoginRow;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface IdentityMapper extends BaseMapper<UserAccountPo> {

    @Select("""
            SELECT u.id, u.public_id, u.nickname, u.status, u.auth_epoch
            FROM iam_external_identity e
            JOIN iam_user_account u ON u.id = e.user_id
            WHERE e.provider = #{provider} AND e.app_id = #{appId} AND e.open_id = #{openId}
            LIMIT 1
            """)
    UserLoginRow findUserByExternal(
            @Param("provider") String provider,
            @Param("appId") String appId,
            @Param("openId") String openId
    );

    @Select("""
            SELECT u.id, u.public_id, u.nickname, u.status, u.auth_epoch
            FROM iam_union_principal p
            JOIN iam_user_account u ON u.id = p.user_id
            WHERE p.union_id = #{unionId}
            LIMIT 1
            """)
    UserLoginRow findUserByUnionId(@Param("unionId") String unionId);

    @Select("""
            SELECT inviter_user_id
            FROM customer_invitation_code
            WHERE code = #{code}
            LIMIT 1
            """)
    InvitationOwnerRow findInvitationOwner(@Param("code") String code);

    @Select("""
            SELECT u.id AS user_id,
                   u.status AS user_status,
                   current_level.status AS level_status,
                   current_level.invitation_enabled
            FROM iam_user_account u
            JOIN membership_account membership ON membership.user_id = u.id
            JOIN membership_level current_level ON current_level.id = membership.current_level_id
            WHERE u.id = #{userId}
            LIMIT 1
            FOR UPDATE
            """)
    InvitationEligibilityRow lockInviterEligibility(@Param("userId") long userId);

    @Select("""
            SELECT id, inviter_user_id, status, expires_at, max_uses, use_count,
                   is_bootstrap AS bootstrap
            FROM customer_invitation_code
            WHERE code = #{code}
            LIMIT 1
            FOR UPDATE
            """)
    InvitationRow lockInvitation(@Param("code") String code);

    @Select("""
            SELECT claim.id, claim.sponsor_user_id, claim.status, claim.version,
                   sponsor.public_id, sponsor.nickname, sponsor.status AS user_status,
                   sponsor.auth_epoch
            FROM iam_bootstrap_sponsor_claim claim
            JOIN iam_user_account sponsor ON sponsor.id = claim.sponsor_user_id
            WHERE claim.status = 'PENDING'
              AND claim.claim_secret_hash = #{claimSecretHash}
            LIMIT 1
            FOR UPDATE
            """)
    SponsorClaimRow lockSponsorClaim(@Param("claimSecretHash") String claimSecretHash);

    @Insert("""
            INSERT INTO iam_user_account (public_id, status, nickname, avatar_url, last_login_at)
            VALUES (#{publicId}, #{status}, #{nickname}, #{avatarUrl}, CURRENT_TIMESTAMP(3))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertUser(UserAccountPo user);

    @Insert("""
            INSERT INTO iam_external_identity (user_id, provider, app_id, open_id, union_id)
            VALUES (#{userId}, #{provider}, #{appId}, #{openId}, #{unionId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertExternalIdentity(ExternalIdentityPo identity);

    @Insert("""
            INSERT INTO iam_union_principal (union_id, user_id)
            VALUES (#{unionId}, #{userId})
            """)
    int insertUnionPrincipal(@Param("unionId") String unionId, @Param("userId") long userId);

    @Update("""
            UPDATE iam_bootstrap_sponsor_claim
            SET status = 'CLAIMED',
                claimed_provider = #{provider},
                claimed_app_id = #{appId},
                claimed_at = CURRENT_TIMESTAMP(3),
                claim_secret_hash = NULL,
                version = version + 1
            WHERE id = #{claimId}
              AND status = 'PENDING'
              AND version = #{expectedVersion}
              AND claim_secret_hash = #{claimSecretHash}
            """)
    int claimBootstrapSponsor(
            @Param("claimId") long claimId,
            @Param("expectedVersion") int expectedVersion,
            @Param("claimSecretHash") String claimSecretHash,
            @Param("provider") String provider,
            @Param("appId") String appId
    );

    @Insert("INSERT INTO customer_profile (user_id) VALUES (#{userId})")
    int insertCustomerProfile(@Param("userId") long userId);

    @Insert("""
            INSERT INTO customer_relation (member_user_id, superior_user_id, invitation_id)
            VALUES (#{memberId}, #{superiorId}, #{invitationId})
            """)
    int insertRelation(
            @Param("memberId") long memberId,
            @Param("superiorId") long superiorId,
            @Param("invitationId") long invitationId
    );

    @Insert("""
            INSERT INTO membership_account (user_id, current_level_id, qualified_at)
            VALUES (#{userId}, 1, CURRENT_TIMESTAMP(3))
            """)
    int insertBasicMembership(@Param("userId") long userId);

    @Insert("""
            INSERT INTO ledger_account (user_id, account_type, available_points, frozen_points)
            VALUES (#{userId}, 'DEMO_POINTS', 0, 0)
            """)
    int insertLedgerAccount(@Param("userId") long userId);

    @Update("""
            UPDATE customer_invitation_code
            SET use_count = use_count + 1, version = version + 1
            WHERE id = #{invitationId}
            """)
    int incrementInvitation(@Param("invitationId") long invitationId);

    @Update("""
            UPDATE customer_invitation_code
            SET use_count = use_count + 1,
                status = 'REVOKED',
                revoked_at = CURRENT_TIMESTAMP(3),
                version = version + 1
            WHERE id = #{invitationId}
              AND is_bootstrap = 1
              AND status = 'ACTIVE'
              AND max_uses IS NOT NULL
              AND use_count < max_uses
            """)
    int consumeBootstrapInvitation(@Param("invitationId") long invitationId);

    @Update("""
            UPDATE iam_user_account
            SET last_login_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{userId}
            """)
    int touchUserLogin(@Param("userId") long userId);

    @Select("""
            SELECT id AS user_id, nickname, avatar_url, phone_masked, phone_verified_at,
                   avatar_object_key, avatar_media_type, avatar_sha256, avatar_size_bytes,
                   avatar_updated_at, version
            FROM iam_user_account
            WHERE id = #{userId}
            LIMIT 1
            """)
    MemberProfileRow memberProfile(@Param("userId") long userId);

    @Update("""
            UPDATE iam_user_account
            SET nickname = #{nickname},
                phone_masked = #{phoneMasked},
                phone_verified_at = #{phoneVerifiedAt},
                version = version + 1
            WHERE id = #{userId}
            """)
    int updateWechatProfile(
            @Param("userId") long userId,
            @Param("nickname") String nickname,
            @Param("phoneMasked") String phoneMasked,
            @Param("phoneVerifiedAt") LocalDateTime phoneVerifiedAt
    );

    @Update("""
            UPDATE iam_user_account
            SET nickname = #{nickname},
                version = version + 1
            WHERE id = #{userId} AND version = #{expectedVersion}
            """)
    int updateMemberNickname(
            @Param("userId") long userId,
            @Param("expectedVersion") int expectedVersion,
            @Param("nickname") String nickname
    );

    @Update("""
            UPDATE iam_user_account
            SET avatar_url = #{avatarUrl},
                avatar_object_key = #{objectKey},
                avatar_media_type = #{mediaType},
                avatar_sha256 = #{sha256},
                avatar_size_bytes = #{sizeBytes},
                avatar_updated_at = #{updatedAt},
                version = version + 1
            WHERE id = #{userId} AND version = #{expectedVersion}
            """)
    int replaceMemberAvatar(
            @Param("userId") long userId,
            @Param("expectedVersion") int expectedVersion,
            @Param("avatarUrl") String avatarUrl,
            @Param("objectKey") String objectKey,
            @Param("mediaType") String mediaType,
            @Param("sha256") String sha256,
            @Param("sizeBytes") long sizeBytes,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Select("""
            SELECT id, username, password_hash, display_name, status,
                   must_change_password, failed_attempts, locked_until, auth_epoch
            FROM iam_admin_account
            WHERE username = #{username}
            LIMIT 1
            """)
    AdminCredentialRow findAdminByUsername(@Param("username") String username);

    @Select("""
            SELECT id, username, password_hash, display_name, status,
                   must_change_password, failed_attempts, locked_until, auth_epoch
            FROM iam_admin_account
            WHERE id = #{adminId}
            LIMIT 1
            """)
    AdminCredentialRow findAdminById(@Param("adminId") long adminId);

    @Select("""
            SELECT r.code
            FROM iam_admin_role ar
            JOIN iam_role r ON r.id = ar.role_id
            WHERE ar.admin_id = #{adminId}
            ORDER BY r.code
            """)
    List<String> findAdminRoles(@Param("adminId") long adminId);

    @Select("""
            SELECT DISTINCT p.code
            FROM iam_admin_role ar
            JOIN iam_role_permission rp ON rp.role_id = ar.role_id
            JOIN iam_permission p ON p.id = rp.permission_id
            WHERE ar.admin_id = #{adminId}
            ORDER BY p.code
            """)
    List<String> findAdminPermissions(@Param("adminId") long adminId);

    /**
     * Atomically records one bad password attempt.
     *
     * <p>The {@code locked_until} assignment deliberately precedes the
     * {@code failed_attempts} assignment.  MySQL evaluates single-row UPDATE
     * assignments from left to right; this lets an expired threshold row be
     * reset to one attempt while a row crossing the threshold is locked in the
     * same statement.  A row that is still locked is left untouched, so a
     * burst of concurrent requests cannot increment the counter (or the auth
     * epoch) beyond the fifth failure.</p>
     */
    @Update("""
            UPDATE iam_admin_account
            SET auth_epoch = auth_epoch
                    + CASE WHEN failed_attempts = #{lockThreshold} - 1 THEN 1 ELSE 0 END,
                locked_until = CASE
                    WHEN failed_attempts >= #{lockThreshold}
                         AND (locked_until IS NULL OR locked_until <= CURRENT_TIMESTAMP(3))
                        THEN NULL
                    WHEN failed_attempts = #{lockThreshold} - 1
                        THEN #{lockedUntil}
                    ELSE locked_until
                END,
                failed_attempts = CASE
                    WHEN failed_attempts >= #{lockThreshold}
                         AND (locked_until IS NULL OR locked_until <= CURRENT_TIMESTAMP(3))
                        THEN 1
                    ELSE LEAST(failed_attempts + 1, #{lockThreshold})
                END,
                version = version + 1
            WHERE id = #{adminId}
              AND (
                    failed_attempts < #{lockThreshold}
                    OR locked_until IS NULL
                    OR locked_until <= CURRENT_TIMESTAMP(3)
                  )
            """)
    int incrementAdminFailure(
            @Param("adminId") long adminId,
            @Param("lockThreshold") int lockThreshold,
            @Param("lockedUntil") LocalDateTime lockedUntil
    );

    @Select("""
            SELECT failed_attempts, locked_until, auth_epoch
            FROM iam_admin_account
            WHERE id = #{adminId}
            """)
    AdminFailureRow adminFailureState(@Param("adminId") long adminId);

    @Select("SELECT status, auth_epoch FROM iam_user_account WHERE id = #{userId}")
    AccountAuthStateRow memberAuthState(@Param("userId") long userId);

    @Select("SELECT status, auth_epoch, locked_until FROM iam_admin_account WHERE id = #{adminId}")
    AccountAuthStateRow adminAuthState(@Param("adminId") long adminId);

    @Update("""
            UPDATE iam_admin_account
            SET failed_attempts = 0, locked_until = NULL, last_login_at = CURRENT_TIMESTAMP(3), version = version + 1
            WHERE id = #{adminId}
            """)
    int updateAdminSuccess(@Param("adminId") long adminId);

    @Select("""
            SELECT a.id
            FROM iam_admin_account a
            JOIN iam_admin_role ar ON ar.admin_id = a.id
            JOIN iam_role r ON r.id = ar.role_id
            WHERE r.code = #{roleCode}
            ORDER BY a.id
            """)
    List<Long> findAdminIdsByRole(@Param("roleCode") String roleCode);

    @Select("""
            SELECT id, username, display_name, status, linked_user_id, must_change_password,
                   failed_attempts, locked_until, last_login_at
            FROM iam_admin_account
            ORDER BY id
            """)
    List<AdminManagementRow> admins();

    @Select("SELECT COUNT(*) FROM iam_admin_account WHERE username = #{username}")
    int usernameExists(@Param("username") String username);

    @Select("SELECT COUNT(*) FROM iam_user_account WHERE id = #{userId}")
    int userExists(@Param("userId") long userId);

    @Select("""
            SELECT COUNT(*)
            FROM iam_admin_role ar
            JOIN iam_role r ON r.id = ar.role_id
            WHERE ar.admin_id = #{adminId} AND r.code = #{roleCode}
            """)
    int adminHasRole(@Param("adminId") long adminId, @Param("roleCode") String roleCode);

    @Select("SELECT code, name, builtin FROM iam_role ORDER BY id")
    List<RoleRow> roles();

    @Select("SELECT code, name, builtin FROM iam_role WHERE code = #{roleCode}")
    RoleRow role(@Param("roleCode") String roleCode);

    @Select("SELECT code FROM iam_permission ORDER BY code")
    List<String> permissions();

    @Select("""
            SELECT p.code
            FROM iam_role_permission rp
            JOIN iam_permission p ON p.id = rp.permission_id
            JOIN iam_role r ON r.id = rp.role_id
            WHERE r.code = #{roleCode}
            ORDER BY p.code
            """)
    List<String> rolePermissions(@Param("roleCode") String roleCode);

    @Insert("INSERT INTO iam_role (code, name, builtin) VALUES (#{code}, #{name}, 0)")
    int insertRole(@Param("code") String code, @Param("name") String name);

    @Update("UPDATE iam_role SET name = #{name} WHERE code = #{code} AND builtin = 0")
    int updateCustomRole(@Param("code") String code, @Param("name") String name);

    @Delete("""
            DELETE rp FROM iam_role_permission rp
            JOIN iam_role r ON r.id = rp.role_id
            WHERE r.code = #{roleCode} AND r.builtin = 0
            """)
    int deleteCustomRolePermissions(@Param("roleCode") String roleCode);

    @Insert("""
            INSERT INTO iam_role_permission (role_id, permission_id)
            SELECT r.id, p.id
            FROM iam_role r
            JOIN iam_permission p ON p.code = #{permissionCode}
            WHERE r.code = #{roleCode} AND r.builtin = 0
            """)
    int insertCustomRolePermission(
            @Param("roleCode") String roleCode,
            @Param("permissionCode") String permissionCode
    );

    @Select("""
            SELECT COUNT(*)
            FROM iam_admin_role ar
            JOIN iam_role r ON r.id = ar.role_id
            WHERE r.code = #{roleCode}
            """)
    int roleAssignmentCount(@Param("roleCode") String roleCode);

    @Delete("DELETE FROM iam_role WHERE code = #{roleCode} AND builtin = 0")
    int deleteCustomRole(@Param("roleCode") String roleCode);

    @Update("""
            UPDATE iam_admin_account
            SET password_hash = #{passwordHash}, must_change_password = #{mustChangePassword},
                failed_attempts = 0, locked_until = NULL,
                auth_epoch = auth_epoch + 1, version = version + 1
            WHERE id = #{adminId}
            """)
    int updateAdminPassword(
            @Param("adminId") long adminId,
            @Param("passwordHash") String passwordHash,
            @Param("mustChangePassword") boolean mustChangePassword
    );

    @Update("""
            UPDATE iam_admin_account
            SET status = #{status}, auth_epoch = auth_epoch + 1, version = version + 1
            WHERE id = #{adminId}
            """)
    int updateAdminStatus(@Param("adminId") long adminId, @Param("status") String status);

    @Update("""
            UPDATE iam_admin_account
            SET failed_attempts = 0, locked_until = NULL,
                auth_epoch = auth_epoch + 1, version = version + 1
            WHERE id = #{adminId}
            """)
    int unlockAdmin(@Param("adminId") long adminId);

    @Delete("DELETE FROM iam_admin_role WHERE admin_id = #{adminId}")
    int deleteAdminRoles(@Param("adminId") long adminId);

    @Insert("""
            INSERT INTO iam_admin_role (admin_id, role_id, granted_by)
            SELECT #{adminId}, id, #{grantedBy} FROM iam_role WHERE code = #{roleCode}
            """)
    int insertAdminRole(
            @Param("adminId") long adminId,
            @Param("roleCode") String roleCode,
            @Param("grantedBy") long grantedBy
    );

    @Update("""
            UPDATE iam_admin_account
            SET auth_epoch = auth_epoch + 1, version = version + 1
            WHERE id = #{adminId}
            """)
    int incrementAdminAuthEpoch(@Param("adminId") long adminId);

    @Update("""
            UPDATE iam_admin_account
            SET linked_user_id = #{linkedUserId}, version = version + 1
            WHERE id = #{adminId}
            """)
    int updateAdminLinkedUser(@Param("adminId") long adminId, @Param("linkedUserId") Long linkedUserId);

    @Select("SELECT COUNT(*) FROM iam_admin_account")
    long countAdmins();

    @Insert("""
            INSERT INTO iam_admin_account
                (username, password_hash, display_name, status, must_change_password)
            VALUES
                (#{username}, #{passwordHash}, #{displayName}, #{status}, #{mustChangePassword})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertAdmin(AdminAccountPo admin);

    @Insert("""
            INSERT INTO iam_admin_role (admin_id, role_id)
            SELECT #{adminId}, id FROM iam_role WHERE code = #{roleCode}
            """)
    int assignRole(@Param("adminId") long adminId, @Param("roleCode") String roleCode);

    @Select("SELECT COUNT(*) FROM iam_user_account")
    long countUsers();

    @Select("""
            SELECT CASE WHEN NOT EXISTS (
                       SELECT 1
                       FROM iam_bootstrap_invitation_repair_guard
                       WHERE id = 1
                   )
                   OR EXISTS (
                       SELECT 1
                       FROM iam_bootstrap_invitation_repair_guard
                       WHERE id = 1
                         AND (
                               repair_required IS NULL
                               OR repair_required <> 0
                               OR version IS NULL
                               OR version < 0
                             )
                   )
                   OR EXISTS (
                       SELECT 1
                       FROM iam_bootstrap_sponsor_claim
                       WHERE invitation_repair_required = 1
                   )
                   THEN 1 ELSE 0 END
            """)
    int countUnresolvedBootstrapInvitationRepairs();

    @Select("""
            SELECT id, repair_required, version
            FROM iam_bootstrap_invitation_repair_guard
            WHERE id = 1
            LIMIT 1
            FOR UPDATE
            """)
    BootstrapInvitationRepairGuardRow lockBootstrapInvitationRepairGuard();

    @Update("""
            UPDATE iam_bootstrap_invitation_repair_guard
            SET repair_required = 0,
                version = version + 1
            WHERE id = 1
              AND repair_required = 1
              AND version = #{expectedVersion}
            """)
    int clearBootstrapInvitationRepairGuard(@Param("expectedVersion") int expectedVersion);

    @Select("""
            SELECT claim.id AS claim_id,
                   claim.sponsor_user_id,
                   claim.status AS claim_status,
                   claim.version AS claim_version,
                   claim.invitation_repair_required,
                   claim.bootstrap_invitation_id
            FROM iam_bootstrap_sponsor_claim claim
            WHERE claim.invitation_repair_required = 1
            ORDER BY claim.id
            FOR UPDATE
            """)
    List<BootstrapInvitationRepairRow> lockUnresolvedBootstrapInvitationRepairs();

    @Select("""
            SELECT id
            FROM iam_bootstrap_sponsor_claim
            WHERE bootstrap_invitation_id = #{invitationId}
            LIMIT 1
            FOR UPDATE
            """)
    Long lockBootstrapClaimByInvitation(@Param("invitationId") long invitationId);

    @Select("""
            SELECT claim.id AS claim_id,
                   claim.sponsor_user_id,
                   claim.status AS claim_status,
                   claim.version AS claim_version,
                   claim.invitation_repair_required,
                   claim.bootstrap_invitation_id
            FROM iam_bootstrap_sponsor_claim claim
            WHERE claim.sponsor_user_id = #{sponsorUserId}
            LIMIT 1
            FOR UPDATE
            """)
    BootstrapInvitationRepairRow lockBootstrapClaimBySponsor(@Param("sponsorUserId") long sponsorUserId);

    @Select("""
            SELECT COUNT(*)
            FROM customer_relation
            WHERE member_user_id = #{userId}
            """)
    int countSuperiorRelations(@Param("userId") long userId);

    @Select("""
            SELECT id
            FROM customer_invitation_code
            WHERE inviter_user_id = #{userId}
            ORDER BY id
            LIMIT 1
            FOR UPDATE
            """)
    Long lockEarliestInvitationId(@Param("userId") long userId);

    @Insert("""
            INSERT INTO customer_invitation_code
                (code, inviter_user_id, status, max_uses, is_bootstrap)
            VALUES
                (#{code}, #{inviterUserId}, 'ACTIVE', 1, 1)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertBootstrapInvitation(InvitationPo invitation);

    @Update("""
            UPDATE customer_invitation_code
            SET is_bootstrap = 1,
                max_uses = 1,
                status = CASE
                    WHEN use_count >= 1 THEN 'REVOKED'
                    ELSE status
                END,
                revoked_at = CASE
                    WHEN use_count >= 1
                        THEN COALESCE(revoked_at, CURRENT_TIMESTAMP(3))
                    ELSE revoked_at
                END,
                version = version + 1
            WHERE id = #{invitationId}
              AND inviter_user_id = #{sponsorUserId}
              AND status IN ('ACTIVE', 'REVOKED')
            """)
    int markBootstrapInvitation(
            @Param("invitationId") long invitationId,
            @Param("sponsorUserId") long sponsorUserId
    );

    @Update("""
            UPDATE iam_bootstrap_sponsor_claim
            SET bootstrap_invitation_id = #{invitationId},
                invitation_repair_required = 0,
                version = version + 1
            WHERE id = #{claimId}
              AND invitation_repair_required = 1
              AND (
                    bootstrap_invitation_id IS NULL
                    OR bootstrap_invitation_id = #{invitationId}
                  )
              AND version = #{expectedVersion}
            """)
    int linkBootstrapInvitation(
            @Param("claimId") long claimId,
            @Param("invitationId") long invitationId,
            @Param("expectedVersion") int expectedVersion
    );

    @Insert("""
            INSERT INTO iam_bootstrap_sponsor_claim
                (sponsor_user_id, status, claim_secret_hash,
                 claimed_provider, claimed_app_id, claimed_at,
                 invitation_repair_required)
            SELECT invitation.inviter_user_id,
                   CASE WHEN real_identity.id IS NULL THEN 'PENDING' ELSE 'CLAIMED' END,
                   CASE WHEN real_identity.id IS NULL THEN #{claimSecretHash} ELSE NULL END,
                   real_identity.provider,
                   real_identity.app_id,
                   real_identity.created_at,
                   1
            FROM customer_invitation_code invitation
            LEFT JOIN iam_external_identity real_identity
              ON real_identity.id = (
                  SELECT candidate.id
                  FROM iam_external_identity candidate
                  WHERE candidate.user_id = invitation.inviter_user_id
                    AND candidate.provider IN ('WECHAT_MP')
                  ORDER BY candidate.created_at, candidate.id
                  LIMIT 1
              )
            JOIN iam_user_account sponsor
              ON sponsor.id = invitation.inviter_user_id
            WHERE invitation.code = #{inviteCode}
              AND (
                    EXISTS (
                        SELECT 1
                        FROM iam_bootstrap_sponsor_claim existing_claim
                        WHERE existing_claim.sponsor_user_id = sponsor.id
                    )
                    OR (
                        sponsor.nickname = '商城发起人'
                        AND NOT EXISTS (
                            SELECT 1
                            FROM customer_relation relation
                            WHERE relation.member_user_id = sponsor.id
                        )
                    )
              )
            LIMIT 1
            ON DUPLICATE KEY UPDATE id = iam_bootstrap_sponsor_claim.id
            """)
    int ensureBootstrapSponsorClaim(
            @Param("inviteCode") String inviteCode,
            @Param("claimSecretHash") String claimSecretHash
    );

    @Insert("""
            INSERT INTO iam_bootstrap_sponsor_claim
                (sponsor_user_id, bootstrap_invitation_id, status, claim_secret_hash,
                 invitation_repair_required)
            VALUES (#{sponsorUserId}, #{invitationId}, 'PENDING', #{claimSecretHash}, 0)
            """)
    int insertBootstrapSponsorClaim(
            @Param("sponsorUserId") long sponsorUserId,
            @Param("invitationId") long invitationId,
            @Param("claimSecretHash") String claimSecretHash
    );

    @Insert("""
            INSERT IGNORE INTO iam_external_identity
                (user_id, provider, app_id, open_id, union_id)
            SELECT invitation.inviter_user_id, 'WECHAT_MOCK', 'local', 'bootstrap-sponsor',
                   'mock-union-bootstrap-sponsor'
            FROM customer_invitation_code invitation
            JOIN iam_user_account sponsor
              ON sponsor.id = invitation.inviter_user_id
            WHERE invitation.code = #{inviteCode}
              AND (
                    EXISTS (
                        SELECT 1
                        FROM iam_bootstrap_sponsor_claim claim
                        WHERE claim.sponsor_user_id = sponsor.id
                          AND claim.status = 'PENDING'
                    )
                    OR (
                        sponsor.nickname = '商城发起人'
                        AND NOT EXISTS (
                            SELECT 1
                            FROM customer_relation relation
                            WHERE relation.member_user_id = sponsor.id
                        )
                    )
              )
            LIMIT 1
            """)
    int repairBootstrapSponsorExternalIdentity(@Param("inviteCode") String inviteCode);

    @Insert("""
            INSERT IGNORE INTO iam_union_principal (union_id, user_id)
            SELECT 'mock-union-bootstrap-sponsor', invitation.inviter_user_id
            FROM customer_invitation_code invitation
            JOIN iam_user_account sponsor
              ON sponsor.id = invitation.inviter_user_id
            WHERE invitation.code = #{inviteCode}
              AND (
                    EXISTS (
                        SELECT 1
                        FROM iam_bootstrap_sponsor_claim claim
                        WHERE claim.sponsor_user_id = sponsor.id
                          AND claim.status = 'PENDING'
                    )
                    OR (
                        sponsor.nickname = '商城发起人'
                        AND NOT EXISTS (
                            SELECT 1
                            FROM customer_relation relation
                            WHERE relation.member_user_id = sponsor.id
                        )
                    )
              )
            LIMIT 1
            """)
    int repairBootstrapSponsorUnionPrincipal(@Param("inviteCode") String inviteCode);

    @Update("""
            UPDATE membership_account
            SET current_level_id = 3, qualified_at = CURRENT_TIMESTAMP(3), version = version + 1
            WHERE user_id = #{userId}
            """)
    int promoteBootstrapSponsor(@Param("userId") long userId);
}

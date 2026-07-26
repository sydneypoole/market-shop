package com.marketshop.infrastructure.persistence.mapper;

import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminAccountPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminCredentialRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminManagementRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.ExternalIdentityPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.RoleRow;
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
            SELECT u.id, u.public_id, u.nickname
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
            SELECT u.id, u.public_id, u.nickname
            FROM iam_union_principal p
            JOIN iam_user_account u ON u.id = p.user_id
            WHERE p.union_id = #{unionId}
            LIMIT 1
            """)
    UserLoginRow findUserByUnionId(@Param("unionId") String unionId);

    @Select("""
            SELECT id, inviter_user_id, status, expires_at, max_uses, use_count
            FROM customer_invitation_code
            WHERE code = #{code}
            LIMIT 1
            FOR UPDATE
            """)
    InvitationRow lockInvitation(@Param("code") String code);

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
            UPDATE iam_user_account
            SET last_login_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{userId}
            """)
    int touchUserLogin(@Param("userId") long userId);

    @Select("""
            SELECT id, username, password_hash, display_name, status,
                   must_change_password, failed_attempts, locked_until
            FROM iam_admin_account
            WHERE username = #{username}
            LIMIT 1
            """)
    AdminCredentialRow findAdminByUsername(@Param("username") String username);

    @Select("""
            SELECT id, username, password_hash, display_name, status,
                   must_change_password, failed_attempts, locked_until
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

    @Update("""
            UPDATE iam_admin_account
            SET failed_attempts = #{failedAttempts}, locked_until = #{lockedUntil}, version = version + 1
            WHERE id = #{adminId}
            """)
    int updateAdminFailure(
            @Param("adminId") long adminId,
            @Param("failedAttempts") int failedAttempts,
            @Param("lockedUntil") LocalDateTime lockedUntil
    );

    @Update("""
            UPDATE iam_admin_account
            SET failed_attempts = 0, locked_until = NULL, last_login_at = CURRENT_TIMESTAMP(3), version = version + 1
            WHERE id = #{adminId}
            """)
    int updateAdminSuccess(@Param("adminId") long adminId);

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
                failed_attempts = 0, locked_until = NULL, version = version + 1
            WHERE id = #{adminId}
            """)
    int updateAdminPassword(
            @Param("adminId") long adminId,
            @Param("passwordHash") String passwordHash,
            @Param("mustChangePassword") boolean mustChangePassword
    );

    @Update("""
            UPDATE iam_admin_account
            SET status = #{status}, version = version + 1
            WHERE id = #{adminId}
            """)
    int updateAdminStatus(@Param("adminId") long adminId, @Param("status") String status);

    @Update("""
            UPDATE iam_admin_account
            SET failed_attempts = 0, locked_until = NULL, version = version + 1
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

    @Insert("""
            INSERT INTO customer_invitation_code
                (code, inviter_user_id, status, max_uses)
            VALUES
                (#{code}, #{inviterUserId}, 'ACTIVE', NULL)
            """)
    int insertBootstrapInvitation(@Param("code") String code, @Param("inviterUserId") long inviterUserId);

    @Update("""
            UPDATE membership_account
            SET current_level_id = 3, qualified_at = CURRENT_TIMESTAMP(3), version = version + 1
            WHERE user_id = #{userId}
            """)
    int promoteBootstrapSponsor(@Param("userId") long userId);
}

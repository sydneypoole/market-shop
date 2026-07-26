package com.marketshop.infrastructure.persistence.model;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

public final class IdentityPersistenceModels {

    private IdentityPersistenceModels() {
    }

    @Table("iam_user_account")
    public static class UserAccountPo {
        @Id(keyType = KeyType.Auto)
        public Long id;
        public String publicId;
        public String status;
        public String nickname;
        public String avatarUrl;
    }

    @Table("iam_external_identity")
    public static class ExternalIdentityPo {
        @Id(keyType = KeyType.Auto)
        public Long id;
        public Long userId;
        public String provider;
        public String appId;
        public String openId;
        public String unionId;
    }

    @Table("iam_admin_account")
    public static class AdminAccountPo {
        @Id(keyType = KeyType.Auto)
        public Long id;
        public String username;
        public String passwordHash;
        public String displayName;
        public String status;
        public Long linkedUserId;
        public Boolean mustChangePassword;
    }

    public static class UserLoginRow {
        public Long id;
        public String publicId;
        public String nickname;
    }

    public static class InvitationRow {
        public Long id;
        public Long inviterUserId;
        public String status;
        public LocalDateTime expiresAt;
        public Integer maxUses;
        public Integer useCount;
    }

    public static class AdminCredentialRow {
        public Long id;
        public String username;
        public String passwordHash;
        public String displayName;
        public String status;
        public Boolean mustChangePassword;
        public Integer failedAttempts;
        public LocalDateTime lockedUntil;
    }

    public static class AdminManagementRow {
        public Long id;
        public String username;
        public String displayName;
        public String status;
        public Long linkedUserId;
        public Boolean mustChangePassword;
        public Integer failedAttempts;
        public LocalDateTime lockedUntil;
        public LocalDateTime lastLoginAt;
    }

    public static class RoleRow {
        public String code;
        public String name;
        public Boolean builtin;
    }
}

package com.marketshop.application.identity;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.audit.AdminAuditPort.AuditRecord;
import com.marketshop.application.identity.IdentityPorts.RegistrationResult;
import com.marketshop.application.identity.IdentityPorts.UserIdentityPort;
import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.application.identity.IdentityPorts.WeChatMiniprogramPort;
import com.marketshop.domain.shared.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthApplicationService implements AuthUseCase {

    private final WeChatMiniprogramPort weChatMiniprogramPort;
    private final UserIdentityPort userIdentityPort;
    private final AdminAuditPort auditPort;
    private final boolean mockEnabled;

    public AuthApplicationService(
            WeChatMiniprogramPort weChatMiniprogramPort,
            UserIdentityPort userIdentityPort,
            AdminAuditPort auditPort,
            @Value("${market-shop.wechat.mock-enabled:false}") boolean mockEnabled
    ) {
        this.weChatMiniprogramPort = weChatMiniprogramPort;
        this.userIdentityPort = userIdentityPort;
        this.auditPort = auditPort;
        this.mockEnabled = mockEnabled;
    }

    @Override
    @Transactional
    public LoginResult miniprogramLogin(MiniprogramLoginCommand command) {
        if (command == null || trimToNull(command.code()) == null) {
            throw new DomainException("WECHAT_CODE_REQUIRED", "微信登录凭证不能为空");
        }
        String inviteCode = trimToNull(command.inviteCode());
        String rawClaimSecret = trimToNull(command.sponsorClaimSecret());
        if (inviteCode != null && rawClaimSecret != null) {
            throw new DomainException(
                    "AUTH_CREDENTIAL_AMBIGUOUS",
                    "邀请码和发起人认领密钥不能同时提交"
            );
        }
        if (rawClaimSecret != null && rawClaimSecret.length() < SponsorClaimSecrets.MINIMUM_LENGTH) {
            throw new DomainException("SPONSOR_CLAIM_SECRET_INVALID", "发起人认领密钥无效或已使用");
        }
        String claimSecretHash = rawClaimSecret == null ? null : SponsorClaimSecrets.sha256(rawClaimSecret);
        WeChatIdentity identity = weChatMiniprogramPort.exchangeMiniprogramCode(command.code().trim());
        return authenticatedResult(
                userIdentityPort.findOrRegister(identity, inviteCode, claimSecretHash),
                identity
        );
    }

    @Override
    @Transactional
    public LoginResult devLogin(DevLoginCommand command) {
        if (!mockEnabled) {
            throw new DomainException("DEV_LOGIN_DISABLED", "开发登录未启用");
        }
        String openId = trimToNull(command.openId());
        if (openId == null) {
            throw new DomainException("OPEN_ID_REQUIRED", "开发登录标识不能为空");
        }
        WeChatIdentity identity = new WeChatIdentity(
                "WECHAT_MOCK",
                "local",
                openId,
                "mock-union-" + openId,
                trimToNull(command.nickname()) == null ? "微信演示用户" : command.nickname().trim(),
                null
        );
        return authenticatedResult(
                userIdentityPort.findOrRegister(identity, trimToNull(command.inviteCode()), null),
                identity
        );
    }

    private LoginResult authenticatedResult(RegistrationResult result, WeChatIdentity identity) {
        requireActive(result.status());
        userIdentityPort.recordLogin(result.userId());
        if (result.sponsorClaimed()) {
            auditSponsorClaim(result.userId(), identity.provider(), identity.appId());
        }
        return new LoginResult(
                result.userId(),
                result.publicId(),
                result.nickname(),
                result.authEpoch(),
                result.newlyRegistered()
        );
    }

    private void auditSponsorClaim(long sponsorUserId, String provider, String appId) {
        auditPort.record(new AuditRecord(
                "USER",
                Long.toString(sponsorUserId),
                "BOOTSTRAP_SPONSOR_CLAIMED",
                "BOOTSTRAP_SPONSOR_CLAIM",
                Long.toString(sponsorUserId),
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"CLAIMED\",\"provider\":" + quote(provider)
                        + ",\"appId\":" + quote(appId) + "}",
                null,
                UUID.randomUUID().toString(),
                null,
                "miniprogram-auth-service",
                Instant.now()
        ));
    }

    private static void requireActive(String status) {
        if ("LOCKED".equals(status)) {
            throw new DomainException("MEMBER_LOCKED", "会员账号已锁定，请联系管理员");
        }
        if (!"ACTIVE".equals(status)) {
            throw new DomainException("MEMBER_DISABLED", "会员账号已停用，请联系管理员");
        }
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

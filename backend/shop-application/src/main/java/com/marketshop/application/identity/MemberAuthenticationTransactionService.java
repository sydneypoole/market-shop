package com.marketshop.application.identity;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.audit.AdminAuditPort.AuditRecord;
import com.marketshop.application.identity.IdentityPorts.RegistrationResult;
import com.marketshop.application.identity.IdentityPorts.UserIdentityPort;
import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.domain.shared.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Owns the local database transaction after all one-time WeChat credentials
 * have been exchanged. WeChat HTTP calls and Sa-Token creation deliberately
 * sit outside this boundary because neither can participate in the MySQL
 * transaction.
 */
@Service
public class MemberAuthenticationTransactionService {

    private final UserIdentityPort userIdentityPort;
    private final AdminAuditPort auditPort;

    public MemberAuthenticationTransactionService(
            UserIdentityPort userIdentityPort,
            AdminAuditPort auditPort
    ) {
        this.userIdentityPort = userIdentityPort;
        this.auditPort = auditPort;
    }

    @Transactional
    public RegistrationResult login(WeChatIdentity identity, String devInviteCode) {
        return complete(
                userIdentityPort.findOrRegister(identity, devInviteCode, null),
                identity
        );
    }

    @Transactional
    public RegistrationResult register(
            WeChatIdentity identity,
            String inviteCode,
            String sponsorClaimSecretHash
    ) {
        return complete(
                userIdentityPort.findOrRegister(identity, inviteCode, sponsorClaimSecretHash),
                identity
        );
    }

    private RegistrationResult complete(RegistrationResult result, WeChatIdentity identity) {
        requireActive(result.status());
        userIdentityPort.recordLogin(result.userId());
        if (result.sponsorClaimed()) {
            auditSponsorClaim(result.userId(), identity.provider(), identity.appId());
        }
        return result;
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
}

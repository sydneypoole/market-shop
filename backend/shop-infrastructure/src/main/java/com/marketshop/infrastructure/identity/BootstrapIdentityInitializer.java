package com.marketshop.infrastructure.identity;

import cn.hutool.crypto.digest.BCrypt;
import com.marketshop.application.identity.SponsorClaimSecrets;
import com.marketshop.infrastructure.persistence.mapper.IdentityMapper;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminAccountPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.UserAccountPo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class BootstrapIdentityInitializer implements ApplicationRunner {

    private static final Pattern ADMIN_USERNAME = Pattern.compile("[a-zA-Z0-9._-]{3,64}");
    private static final Pattern INVITE_CODE = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}._-]{2,63}");

    private final IdentityMapper mapper;
    private final boolean enabled;
    private final String adminUsername;
    private final String bootstrapPassword;
    private final String inviteCode;
    private final String sponsorClaimSecret;
    private final boolean mockEnabled;

    @Autowired
    public BootstrapIdentityInitializer(
            IdentityMapper mapper,
            @Value("${market-shop.bootstrap-admin.enabled:false}") boolean enabled,
            @Value("${market-shop.bootstrap-admin.username:admin}") String adminUsername,
            @Value("${market-shop.bootstrap-admin.password:}") String bootstrapPassword,
            @Value("${market-shop.bootstrap-admin.invite-code:BOOTSTRAP2026}") String inviteCode,
            @Value("${market-shop.bootstrap-admin.sponsor-claim-secret:}") String sponsorClaimSecret,
            @Value("${market-shop.wechat.mock-enabled:false}") boolean mockEnabled
    ) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.adminUsername = adminUsername;
        this.bootstrapPassword = bootstrapPassword;
        this.inviteCode = inviteCode;
        this.sponsorClaimSecret = sponsorClaimSecret;
        this.mockEnabled = mockEnabled;
    }

    /** Retained for migration/unit fixtures that model the local profile. */
    public BootstrapIdentityInitializer(
            IdentityMapper mapper,
            boolean enabled,
            String adminUsername,
            String bootstrapPassword,
            String inviteCode,
            String sponsorClaimSecret
    ) {
        this(mapper, enabled, adminUsername, bootstrapPassword, inviteCode, sponsorClaimSecret, true);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (bootstrapPassword == null || bootstrapPassword.length() < 12) {
            throw new IllegalStateException("Bootstrap admin password must contain at least 12 characters");
        }
        String normalizedAdminUsername = normalizeAdminUsername(adminUsername);
        String normalizedInviteCode = normalizeInviteCode(inviteCode);
        if (sponsorClaimSecret == null
                || sponsorClaimSecret.length() < SponsorClaimSecrets.MINIMUM_LENGTH) {
            throw new IllegalStateException(
                    "MARKET_SHOP_BOOTSTRAP_SPONSOR_CLAIM_SECRET must contain at least 32 characters"
            );
        }
        String claimSecretHash = SponsorClaimSecrets.sha256(sponsorClaimSecret);
        createSponsorIfNecessary(normalizedInviteCode, claimSecretHash);
        repairSponsorIdentity(normalizedInviteCode, claimSecretHash);
        if (mapper.countAdmins() > 0) {
            return;
        }
        String passwordHash = BCrypt.hashpw(bootstrapPassword, BCrypt.gensalt(12));
        AdminAccountPo admin = new AdminAccountPo();
        admin.username = normalizedAdminUsername;
        admin.passwordHash = passwordHash;
        admin.displayName = "超级管理员";
        admin.status = "ACTIVE";
        admin.mustChangePassword = true;
        mapper.insertAdmin(admin);
        mapper.assignRole(admin.id, "SUPER_ADMIN");
    }

    private void createSponsorIfNecessary(String normalizedInviteCode, String claimSecretHash) {
        if (mapper.countUsers() > 0) {
            return;
        }
        UserAccountPo sponsor = new UserAccountPo();
        sponsor.publicId = String.format("%013d", System.currentTimeMillis())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 13).toUpperCase();
        sponsor.status = "ACTIVE";
        sponsor.nickname = "商城发起人";
        mapper.insertUser(sponsor);
        mapper.insertCustomerProfile(sponsor.id);
        mapper.insertBasicMembership(sponsor.id);
        mapper.promoteBootstrapSponsor(sponsor.id);
        mapper.insertLedgerAccount(sponsor.id);
        mapper.insertBootstrapInvitation(normalizedInviteCode, sponsor.id);
        mapper.insertBootstrapSponsorClaim(sponsor.id, claimSecretHash);
    }

    private void repairSponsorIdentity(String normalizedInviteCode, String claimSecretHash) {
        // Flyway runs before ApplicationRunner on a fresh database, so V4 cannot
        // attach the local identity to a sponsor that does not exist yet. These
        // invitation-based inserts also repair databases bootstrapped by an
        // older application after V4 had already run.
        if (mockEnabled) {
            mapper.repairBootstrapSponsorExternalIdentity(normalizedInviteCode);
            mapper.repairBootstrapSponsorUnionPrincipal(normalizedInviteCode);
        }
        // WECHAT_MOCK is intentionally excluded by the mapper. The ordinary
        // invitation locates an older sponsor but is never stored as, or
        // converted into, the claim credential. A fresh sponsor remains
        // PENDING until the first H5/WEB identity presents the independent secret;
        // an upgraded sponsor that already owns a real identity is sealed as
        // CLAIMED and can never be reopened by startup.
        mapper.ensureBootstrapSponsorClaim(normalizedInviteCode, claimSecretHash);
    }

    private static String normalizeAdminUsername(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!ADMIN_USERNAME.matcher(normalized).matches()) {
            throw new IllegalStateException(
                    "MARKET_SHOP_BOOTSTRAP_ADMIN_USERNAME must be 3-64 letters, numbers, '.', '_' or '-'");
        }
        return normalized;
    }

    private static String normalizeInviteCode(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!INVITE_CODE.matcher(normalized).matches()) {
            throw new IllegalStateException(
                    "MARKET_SHOP_BOOTSTRAP_INVITE_CODE must be 3-64 letters, numbers, '.', '_' or '-'");
        }
        return normalized;
    }

}

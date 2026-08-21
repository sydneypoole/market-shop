package com.marketshop.infrastructure.identity;

import cn.hutool.crypto.digest.BCrypt;
import com.marketshop.application.identity.SponsorClaimSecrets;
import com.marketshop.infrastructure.persistence.mapper.IdentityMapper;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminAccountPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.BootstrapInvitationRepairGuardRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.BootstrapInvitationRepairRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationOwnerRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.UserAccountPo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
            @Value("${market-shop.bootstrap-admin.invite-code:}") String inviteCode,
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
            repairBootstrapInvitation(
                    normalizeOptionalInviteCode(inviteCode, false),
                    usableClaimSecretHash()
            );
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
        if (repairBootstrapInvitation(normalizedInviteCode, claimSecretHash)) {
            repairSponsorIdentity(normalizedInviteCode, claimSecretHash);
        }
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
        if (mapper.insertAdmin(admin) != 1 || admin.id == null) {
            throw new IllegalStateException("Bootstrap admin was not created");
        }
        requireOne(mapper.assignRole(admin.id, "SUPER_ADMIN"),
                "Bootstrap admin role was not assigned");
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
        if (mapper.insertUser(sponsor) != 1 || sponsor.id == null) {
            throw new IllegalStateException("Bootstrap sponsor user was not created");
        }
        requireOne(mapper.insertCustomerProfile(sponsor.id), "Bootstrap sponsor profile was not created");
        requireOne(mapper.insertBasicMembership(sponsor.id), "Bootstrap sponsor membership was not created");
        requireOne(mapper.promoteBootstrapSponsor(sponsor.id), "Bootstrap sponsor level was not promoted");
        requireOne(mapper.insertLedgerAccount(sponsor.id), "Bootstrap sponsor ledger was not created");
        InvitationPo invitation = new InvitationPo();
        invitation.code = normalizedInviteCode;
        invitation.inviterUserId = sponsor.id;
        if (mapper.insertBootstrapInvitation(invitation) != 1 || invitation.id == null) {
            throw new IllegalStateException("Bootstrap invitation was not created");
        }
        if (mapper.insertBootstrapSponsorClaim(sponsor.id, invitation.id, claimSecretHash) != 1) {
            throw new IllegalStateException("Bootstrap sponsor claim was not created");
        }
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

    private boolean repairBootstrapInvitation(String configuredInviteCode, String claimSecretHash) {
        BootstrapInvitationRepairGuardRow guard = mapper.lockBootstrapInvitationRepairGuard();
        if (guard == null || guard.id == null || guard.id != 1
                || guard.repairRequired == null || guard.version == null || guard.version < 0) {
            return false;
        }
        List<BootstrapInvitationRepairRow> unresolvedClaims =
                mapper.lockUnresolvedBootstrapInvitationRepairs();
        if (unresolvedClaims == null || unresolvedClaims.stream().anyMatch(
                claim -> claim == null
                        || claim.claimId == null
                        || claim.sponsorUserId == null
                        || claim.claimVersion == null
                        || claim.claimVersion < 0
                        || claim.invitationRepairRequired == null
                        || claim.claimStatus == null
        )) {
            return false;
        }
        if (!Boolean.TRUE.equals(guard.repairRequired) && unresolvedClaims.isEmpty()) {
            return true;
        }
        if (configuredInviteCode == null || unresolvedClaims.size() > 1) {
            return false;
        }

        InvitationOwnerRow owner = mapper.findInvitationOwner(configuredInviteCode);
        if (owner == null || owner.inviterUserId == null) {
            return false;
        }
        long sponsorUserId = owner.inviterUserId;
        if (mapper.lockInviterEligibility(sponsorUserId) == null
                || mapper.countSuperiorRelations(sponsorUserId) != 0) {
            return false;
        }
        InvitationRow invitation = mapper.lockInvitation(configuredInviteCode);
        if (invitation == null || invitation.id == null || invitation.inviterUserId == null
                || invitation.useCount == null || invitation.bootstrap == null
                || (!"ACTIVE".equals(invitation.status) && !"REVOKED".equals(invitation.status))
                || !Long.valueOf(sponsorUserId).equals(invitation.inviterUserId)) {
            return false;
        }
        Long earliestInvitationId = mapper.lockEarliestInvitationId(sponsorUserId);
        if (!invitation.id.equals(earliestInvitationId)) {
            return false;
        }

        BootstrapInvitationRepairRow existingClaim = mapper.lockBootstrapClaimBySponsor(sponsorUserId);
        if (existingClaim != null
                && (existingClaim.claimId == null || existingClaim.sponsorUserId == null
                || existingClaim.claimVersion == null || existingClaim.claimVersion < 0
                || existingClaim.invitationRepairRequired == null
                || existingClaim.claimStatus == null
                || sponsorUserId != existingClaim.sponsorUserId)) {
            return false;
        }
        if (unresolvedClaims.size() == 1
                && sponsorUserId != unresolvedClaims.getFirst().sponsorUserId) {
            return false;
        }
        if (existingClaim != null
                && existingClaim.bootstrapInvitationId != null
                && !invitation.id.equals(existingClaim.bootstrapInvitationId)) {
            return false;
        }
        if (existingClaim != null
                && !Boolean.TRUE.equals(existingClaim.invitationRepairRequired)
                && existingClaim.bootstrapInvitationId == null) {
            return false;
        }
        Long conflictingClaimId = mapper.lockBootstrapClaimByInvitation(invitation.id);
        if (conflictingClaimId != null
                && (existingClaim == null || !conflictingClaimId.equals(existingClaim.claimId))) {
            return false;
        }

        if (existingClaim == null) {
            if (claimSecretHash == null
                    || mapper.insertBootstrapSponsorClaim(
                    sponsorUserId,
                    invitation.id,
                    claimSecretHash
            ) != 1) {
                return false;
            }
        }
        if (mapper.markBootstrapInvitation(invitation.id, sponsorUserId) != 1) {
            throw new IllegalStateException("Bootstrap invitation repair did not update the invitation");
        }
        if (existingClaim != null && Boolean.TRUE.equals(existingClaim.invitationRepairRequired)) {
            if (existingClaim.claimId == null || existingClaim.claimVersion == null
                    || mapper.linkBootstrapInvitation(
                    existingClaim.claimId,
                    invitation.id,
                    existingClaim.claimVersion
            ) != 1) {
                throw new IllegalStateException("Bootstrap invitation repair did not link the claim");
            }
        }
        if (Boolean.TRUE.equals(guard.repairRequired)) {
            if (guard.version == null
                    || mapper.clearBootstrapInvitationRepairGuard(guard.version) != 1) {
                throw new IllegalStateException("Bootstrap invitation repair did not clear the guard");
            }
        }
        return true;
    }

    private String usableClaimSecretHash() {
        return sponsorClaimSecret == null
                || sponsorClaimSecret.length() < SponsorClaimSecrets.MINIMUM_LENGTH
                ? null
                : SponsorClaimSecrets.sha256(sponsorClaimSecret);
    }

    private static void requireOne(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new IllegalStateException(message);
        }
    }

    private static String normalizeOptionalInviteCode(String value, boolean strict) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (!INVITE_CODE.matcher(normalized).matches()) {
            if (strict) {
                throw new IllegalStateException(
                        "MARKET_SHOP_BOOTSTRAP_INVITE_CODE must be 3-64 letters, numbers, '.', '_' or '-'");
            }
            return null;
        }
        return normalized;
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

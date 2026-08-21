package com.marketshop.infrastructure.identity;

import cn.hutool.crypto.digest.BCrypt;
import com.marketshop.application.identity.SponsorClaimSecrets;
import com.marketshop.infrastructure.persistence.mapper.IdentityMapper;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InvitationEligibilityRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminAccountPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.BootstrapInvitationRepairGuardRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.BootstrapInvitationRepairRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationOwnerRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.UserAccountPo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapIdentityInitializerTest {

    private static final String CLAIM_SECRET = "owner-only-claim-secret-2026-abcdef";
    private static final String CLAIM_SECRET_HASH = SponsorClaimSecrets.sha256(CLAIM_SECRET);

    @Test
    void createsOnlyConfiguredSuperAdministrator() throws Exception {
        List<AdminAccountPo> insertedAdmins = new ArrayList<>();
        List<String> assignedRoles = new ArrayList<>();
        IdentityMapper mapper = (IdentityMapper) Proxy.newProxyInstance(
                IdentityMapper.class.getClassLoader(),
                new Class<?>[]{IdentityMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "countUsers" -> 1L;
                    case "lockBootstrapInvitationRepairGuard" -> clearRepairGuard();
                    case "lockUnresolvedBootstrapInvitationRepairs" -> List.of();
                    case "countAdmins" -> 0L;
                    case "insertAdmin" -> {
                        AdminAccountPo admin = (AdminAccountPo) arguments[0];
                        admin.id = 7L;
                        insertedAdmins.add(admin);
                        yield 1;
                    }
                    case "assignRole" -> {
                        assignedRoles.add((String) arguments[1]);
                        yield 1;
                    }
                    case "repairBootstrapSponsorExternalIdentity",
                         "repairBootstrapSponsorUnionPrincipal",
                         "ensureBootstrapSponsorClaim" -> 0;
                    default -> throw new AssertionError("Unexpected mapper call: " + method.getName());
                }
        );
        String temporaryPassword = "StrongAdmin2026";
        BootstrapIdentityInitializer initializer = new BootstrapIdentityInitializer(
                mapper,
                true,
                "admin",
                temporaryPassword,
                "BOOTSTRAP2026",
                CLAIM_SECRET
        );

        initializer.run(null);

        assertThat(insertedAdmins).singleElement().satisfies(admin -> {
            assertThat(admin.username).isEqualTo("admin");
            assertThat(admin.displayName).isEqualTo("超级管理员");
            assertThat(admin.status).isEqualTo("ACTIVE");
            assertThat(admin.mustChangePassword).isTrue();
            assertThat(BCrypt.checkpw(temporaryPassword, admin.passwordHash)).isTrue();
        });
        assertThat(assignedRoles).containsExactly("SUPER_ADMIN");
    }

    @Test
    void freshBootstrapCreatesLoginIdentityForSponsorAfterFlyway() throws Exception {
        List<String> calls = new ArrayList<>();
        IdentityMapper mapper = (IdentityMapper) Proxy.newProxyInstance(
                IdentityMapper.class.getClassLoader(),
                new Class<?>[]{IdentityMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "countUsers" -> 0L;
                    case "lockBootstrapInvitationRepairGuard" -> clearRepairGuard();
                    case "lockUnresolvedBootstrapInvitationRepairs" -> List.of();
                    case "insertUser" -> {
                        UserAccountPo sponsor = (UserAccountPo) arguments[0];
                        sponsor.id = 41L;
                        calls.add("user:" + sponsor.nickname);
                        yield 1;
                    }
                    case "insertCustomerProfile", "insertBasicMembership", "promoteBootstrapSponsor",
                         "insertLedgerAccount" -> {
                        assertThat(arguments[0]).isEqualTo(41L);
                        calls.add(method.getName());
                        yield 1;
                    }
                    case "insertBootstrapInvitation" -> {
                        var invitation = (com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationPo)
                                arguments[0];
                        assertThat(invitation.code).isEqualTo("BOOTSTRAP2026");
                        assertThat(invitation.inviterUserId).isEqualTo(41L);
                        invitation.id = 12L;
                        calls.add("invitation");
                        yield 1;
                    }
                    case "insertBootstrapSponsorClaim" -> {
                        assertThat(arguments).containsExactly(41L, 12L, CLAIM_SECRET_HASH);
                        assertThat(arguments[2]).isNotEqualTo(CLAIM_SECRET);
                        calls.add("claim");
                        yield 1;
                    }
                    case "repairBootstrapSponsorExternalIdentity",
                         "repairBootstrapSponsorUnionPrincipal" -> {
                        assertThat(arguments[0]).isEqualTo("BOOTSTRAP2026");
                        calls.add(method.getName());
                        yield 1;
                    }
                    case "ensureBootstrapSponsorClaim" -> {
                        assertThat(arguments).containsExactly("BOOTSTRAP2026", CLAIM_SECRET_HASH);
                        calls.add(method.getName());
                        yield 1;
                    }
                    case "countAdmins" -> 1L;
                    default -> throw new AssertionError("Unexpected mapper call: " + method.getName());
                }
        );
        BootstrapIdentityInitializer initializer = new BootstrapIdentityInitializer(
                mapper,
                true,
                "admin",
                "StrongAdmin2026",
                "BOOTSTRAP2026",
                CLAIM_SECRET
        );

        initializer.run(null);

        assertThat(calls).containsExactly(
                "user:商城发起人",
                "insertCustomerProfile",
                "insertBasicMembership",
                "promoteBootstrapSponsor",
                "insertLedgerAccount",
                "invitation",
                "claim",
                "repairBootstrapSponsorExternalIdentity",
                "repairBootstrapSponsorUnionPrincipal",
                "ensureBootstrapSponsorClaim"
        );
    }

    @Test
    void ambiguousLegacyRepairSkipsSponsorIdentitySideEffects() {
        List<String> calls = new ArrayList<>();
        IdentityMapper mapper = (IdentityMapper) Proxy.newProxyInstance(
                IdentityMapper.class.getClassLoader(),
                new Class<?>[]{IdentityMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "countUsers" -> 1L;
                    case "lockBootstrapInvitationRepairGuard" -> repairGuard();
                    case "lockUnresolvedBootstrapInvitationRepairs" ->
                            List.of(pendingRepair(), pendingRepair());
                    case "repairBootstrapSponsorExternalIdentity",
                         "repairBootstrapSponsorUnionPrincipal",
                         "ensureBootstrapSponsorClaim" -> {
                        calls.add(method.getName());
                        yield 1;
                    }
                    case "countAdmins" -> 1L;
                    default -> throw new AssertionError("Unexpected mapper call: " + method.getName());
                }
        );

        new BootstrapIdentityInitializer(
                mapper, true, "admin", "StrongAdmin2026", "BOOTSTRAP2026", CLAIM_SECRET
        ).run(null);

        assertThat(calls).isEmpty();
    }

    @Test
    void repeatedInitializationDoesNotRecreateOrResetTheBootstrapInvitation() {
        AtomicInteger users = new AtomicInteger();
        AtomicInteger invitations = new AtomicInteger();
        IdentityMapper mapper = (IdentityMapper) Proxy.newProxyInstance(
                IdentityMapper.class.getClassLoader(),
                new Class<?>[]{IdentityMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "countUsers" -> (long) users.get();
                    case "lockBootstrapInvitationRepairGuard" -> clearRepairGuard();
                    case "lockUnresolvedBootstrapInvitationRepairs" -> List.of();
                    case "insertUser" -> {
                        UserAccountPo sponsor = (UserAccountPo) arguments[0];
                        sponsor.id = 41L;
                        users.incrementAndGet();
                        yield 1;
                    }
                    case "insertCustomerProfile", "insertBasicMembership", "promoteBootstrapSponsor",
                         "insertLedgerAccount", "insertBootstrapSponsorClaim",
                         "repairBootstrapSponsorExternalIdentity", "repairBootstrapSponsorUnionPrincipal",
                         "ensureBootstrapSponsorClaim" -> 1;
                    case "insertBootstrapInvitation" -> {
                        var invitation = (com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationPo)
                                arguments[0];
                        invitation.id = 12L;
                        invitations.incrementAndGet();
                        yield 1;
                    }
                    case "countAdmins" -> 1L;
                    default -> throw new AssertionError("Unexpected mapper call: " + method.getName());
                }
        );
        BootstrapIdentityInitializer initializer = new BootstrapIdentityInitializer(
                mapper, true, "admin", "StrongAdmin2026", "BOOTSTRAP2026", CLAIM_SECRET
        );

        initializer.run(null);
        initializer.run(null);

        assertThat(users).hasValue(1);
        assertThat(invitations).hasValue(1);
    }

    @Test
    void disabledBootstrapRepairsLockSponsorRootBeforeInvitation() {
        List<String> calls = new ArrayList<>();
        IdentityMapper mapper = repairMapper(calls, 1);

        new BootstrapIdentityInitializer(
                mapper, false, "admin", "", "BOOTSTRAP2026", ""
        ).run(null);

        assertThat(calls).containsExactly(
                "lockGuard", "lockRepairs", "findOwner", "lockRoot", "countRelations", "lockInvitation",
                "lockEarliest", "lockSponsorClaim", "lockConflict", "markInvitation", "linkClaim", "clearGuard"
        );
    }

    @Test
    void bootstrapRepairAffectedRowZeroFailsBeforeClaimLinking() {
        List<String> calls = new ArrayList<>();
        IdentityMapper mapper = repairMapper(calls, 0);

        assertThatThrownBy(() -> new BootstrapIdentityInitializer(
                mapper, false, "admin", "", "BOOTSTRAP2026", ""
        ).run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not update");
        assertThat(calls).containsExactly(
                "lockGuard", "lockRepairs", "findOwner", "lockRoot", "countRelations", "lockInvitation",
                "lockEarliest", "lockSponsorClaim", "lockConflict", "markInvitation"
        );
    }

    @Test
    void disabledBootstrapDoesNotCreateOrRepairWhenTheGuardIsAlreadyClear() {
        IdentityMapper mapper = (IdentityMapper) Proxy.newProxyInstance(
                IdentityMapper.class.getClassLoader(),
                new Class<?>[]{IdentityMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "lockBootstrapInvitationRepairGuard" -> clearRepairGuard();
                    case "lockUnresolvedBootstrapInvitationRepairs" -> List.of();
                    default -> throw new AssertionError("Unexpected mapper call: " + method.getName());
                }
        );
        BootstrapIdentityInitializer initializer = new BootstrapIdentityInitializer(
                mapper, false, "admin", "", "", ""
        );

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();
    }

    @Test
    void missingRepairGuardFailsClosedBeforeSponsorIdentityRepair() {
        List<String> calls = new ArrayList<>();
        IdentityMapper mapper = (IdentityMapper) Proxy.newProxyInstance(
                IdentityMapper.class.getClassLoader(),
                new Class<?>[]{IdentityMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "countUsers" -> 1L;
                    case "lockBootstrapInvitationRepairGuard" -> null;
                    case "countAdmins" -> {
                        calls.add("countAdmins");
                        yield 1L;
                    }
                    case "repairBootstrapSponsorExternalIdentity",
                         "repairBootstrapSponsorUnionPrincipal",
                         "ensureBootstrapSponsorClaim" -> throw new AssertionError(
                            "repair side effects must not run without the guard row");
                    default -> throw new AssertionError("Unexpected mapper call: " + method.getName());
                }
        );

        assertThatCode(() -> new BootstrapIdentityInitializer(
                mapper, true, "admin", "StrongAdmin2026", "BOOTSTRAP2026", CLAIM_SECRET
        ).run(null)).doesNotThrowAnyException();
        assertThat(calls).containsExactly("countAdmins");
    }

    @Test
    void malformedRepairGuardFailsClosedBeforeSponsorIdentityRepair() {
        List<String> calls = new ArrayList<>();
        IdentityMapper mapper = (IdentityMapper) Proxy.newProxyInstance(
                IdentityMapper.class.getClassLoader(),
                new Class<?>[]{IdentityMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "countUsers" -> 1L;
                    case "lockBootstrapInvitationRepairGuard" -> malformedRepairGuard();
                    case "countAdmins" -> {
                        calls.add("countAdmins");
                        yield 1L;
                    }
                    case "repairBootstrapSponsorExternalIdentity",
                         "repairBootstrapSponsorUnionPrincipal",
                         "ensureBootstrapSponsorClaim" -> throw new AssertionError(
                            "repair side effects must not run with a malformed guard");
                    default -> throw new AssertionError("Unexpected mapper call: " + method.getName());
                }
        );

        assertThatCode(() -> new BootstrapIdentityInitializer(
                mapper, true, "admin", "StrongAdmin2026", "BOOTSTRAP2026", CLAIM_SECRET
        ).run(null)).doesNotThrowAnyException();
        assertThat(calls).containsExactly("countAdmins");
    }

    @Test
    void freshBootstrapFailsClosedWhenSponsorGeneratedIdIsMissing() {
        IdentityMapper mapper = (IdentityMapper) Proxy.newProxyInstance(
                IdentityMapper.class.getClassLoader(),
                new Class<?>[]{IdentityMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "countUsers" -> 0L;
                    case "insertUser" -> 1;
                    default -> throw new AssertionError("Unexpected mapper call: " + method.getName());
                }
        );

        assertThatThrownBy(() -> new BootstrapIdentityInitializer(
                mapper, true, "admin", "StrongAdmin2026", "BOOTSTRAP2026", CLAIM_SECRET
        ).run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sponsor user");
    }

    @Test
    void freshBootstrapFailsClosedWhenGeneratedInvitationIdIsMissing() {
        IdentityMapper mapper = (IdentityMapper) Proxy.newProxyInstance(
                IdentityMapper.class.getClassLoader(),
                new Class<?>[]{IdentityMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "countUsers" -> 0L;
                    case "insertUser" -> {
                        ((UserAccountPo) arguments[0]).id = 41L;
                        yield 1;
                    }
                    case "insertCustomerProfile", "insertBasicMembership", "promoteBootstrapSponsor",
                         "insertLedgerAccount" -> 1;
                    case "insertBootstrapInvitation" -> 1;
                    case "insertBootstrapSponsorClaim" -> throw new AssertionError(
                            "claim insert must not run without the invitation id");
                    default -> throw new AssertionError("Unexpected mapper call: " + method.getName());
                }
        );

        assertThatThrownBy(() -> new BootstrapIdentityInitializer(
                mapper, true, "admin", "StrongAdmin2026", "BOOTSTRAP2026", CLAIM_SECRET
        ).run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invitation");
    }

    @Test
    void freshBootstrapFailsClosedWhenClaimInsertDoesNotAffectOneRow() {
        IdentityMapper mapper = (IdentityMapper) Proxy.newProxyInstance(
                IdentityMapper.class.getClassLoader(),
                new Class<?>[]{IdentityMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "countUsers" -> 0L;
                    case "insertUser" -> {
                        ((UserAccountPo) arguments[0]).id = 41L;
                        yield 1;
                    }
                    case "insertCustomerProfile", "insertBasicMembership", "promoteBootstrapSponsor",
                         "insertLedgerAccount" -> 1;
                    case "insertBootstrapInvitation" -> {
                        ((com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationPo)
                                arguments[0]).id = 12L;
                        yield 1;
                    }
                    case "insertBootstrapSponsorClaim" -> 0;
                    default -> throw new AssertionError("Unexpected mapper call: " + method.getName());
                }
        );

        assertThatThrownBy(() -> new BootstrapIdentityInitializer(
                mapper, true, "admin", "StrongAdmin2026", "BOOTSTRAP2026", CLAIM_SECRET
        ).run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim");
    }

    @Test
    void enabledBootstrapRejectsAnInvalidAdminUsernameBeforeTouchingTheDatabase() {
        IdentityMapper mapper = mapperThatFailsOnUnexpectedCall();
        BootstrapIdentityInitializer initializer = new BootstrapIdentityInitializer(
                mapper,
                true,
                "admin name",
                "StrongAdmin2026",
                "BOOTSTRAP2026",
                CLAIM_SECRET
        );

        assertThatCode(() -> initializer.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MARKET_SHOP_BOOTSTRAP_ADMIN_USERNAME");
    }

    @Test
    void enabledBootstrapRejectsAnInvalidInviteCodeBeforeTouchingTheDatabase() {
        IdentityMapper mapper = mapperThatFailsOnUnexpectedCall();
        BootstrapIdentityInitializer initializer = new BootstrapIdentityInitializer(
                mapper,
                true,
                "admin",
                "StrongAdmin2026",
                " ",
                CLAIM_SECRET
        );

        assertThatCode(() -> initializer.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MARKET_SHOP_BOOTSTRAP_INVITE_CODE");
    }

    @Test
    void existingAdministratorPreventsAdditionalBootstrapAccount() {
        List<String> calls = new ArrayList<>();
        IdentityMapper mapper = (IdentityMapper) Proxy.newProxyInstance(
                IdentityMapper.class.getClassLoader(),
                new Class<?>[]{IdentityMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "countUsers" -> {
                        calls.add("countUsers");
                        yield 1L;
                    }
                    case "lockBootstrapInvitationRepairGuard" -> clearRepairGuard();
                    case "lockUnresolvedBootstrapInvitationRepairs" -> List.of();
                    case "repairBootstrapSponsorExternalIdentity",
                         "repairBootstrapSponsorUnionPrincipal" -> {
                        calls.add(method.getName());
                        yield 1;
                    }
                    case "ensureBootstrapSponsorClaim" -> {
                        assertThat(arguments).containsExactly("BOOTSTRAP2026", CLAIM_SECRET_HASH);
                        calls.add(method.getName());
                        yield 0;
                    }
                    case "countAdmins" -> {
                        calls.add("countAdmins");
                        yield 1L;
                    }
                    default -> throw new AssertionError("Unexpected mapper call: " + method.getName());
                }
        );
        BootstrapIdentityInitializer initializer = new BootstrapIdentityInitializer(
                mapper,
                true,
                "admin",
                "StrongAdmin2026",
                "BOOTSTRAP2026",
                CLAIM_SECRET
        );

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();
        assertThat(calls).containsExactly(
                "countUsers",
                "repairBootstrapSponsorExternalIdentity",
                "repairBootstrapSponsorUnionPrincipal",
                "ensureBootstrapSponsorClaim",
                "countAdmins"
        );
    }

    @Test
    void productionBootstrapNeverManufacturesMockSponsorIdentity() {
        List<String> calls = new ArrayList<>();
        IdentityMapper mapper = (IdentityMapper) Proxy.newProxyInstance(
                IdentityMapper.class.getClassLoader(),
                new Class<?>[]{IdentityMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "countUsers" -> 1L;
                    case "lockBootstrapInvitationRepairGuard" -> clearRepairGuard();
                    case "lockUnresolvedBootstrapInvitationRepairs" -> List.of();
                    case "ensureBootstrapSponsorClaim" -> {
                        calls.add(method.getName());
                        yield 0;
                    }
                    case "countAdmins" -> 1L;
                    case "repairBootstrapSponsorExternalIdentity",
                         "repairBootstrapSponsorUnionPrincipal" ->
                            throw new AssertionError("production must not create WECHAT_MOCK identities");
                    default -> throw new AssertionError("Unexpected mapper call: " + method.getName());
                }
        );
        BootstrapIdentityInitializer initializer = new BootstrapIdentityInitializer(
                mapper, true, "admin", "StrongAdmin2026", "BOOTSTRAP2026", CLAIM_SECRET, false
        );

        initializer.run(null);

        assertThat(calls).containsExactly("ensureBootstrapSponsorClaim");
    }

    private static IdentityMapper repairMapper(List<String> calls, int markResult) {
        return (IdentityMapper) Proxy.newProxyInstance(
                IdentityMapper.class.getClassLoader(),
                new Class<?>[]{IdentityMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "lockBootstrapInvitationRepairGuard" -> {
                        calls.add("lockGuard");
                        yield repairGuard();
                    }
                    case "lockUnresolvedBootstrapInvitationRepairs" -> {
                        calls.add("lockRepairs");
                        yield List.of(pendingRepair());
                    }
                    case "findInvitationOwner" -> {
                        assertThat(arguments).containsExactly("BOOTSTRAP2026");
                        calls.add("findOwner");
                        yield invitationOwner();
                    }
                    case "lockInviterEligibility" -> {
                        assertThat(arguments).containsExactly(41L);
                        calls.add("lockRoot");
                        yield activeEligibility();
                    }
                    case "lockInvitation" -> {
                        assertThat(arguments).containsExactly("BOOTSTRAP2026");
                        calls.add("lockInvitation");
                        yield legacyInvitation();
                    }
                    case "countSuperiorRelations" -> {
                        assertThat(arguments).containsExactly(41L);
                        calls.add("countRelations");
                        yield 0;
                    }
                    case "lockEarliestInvitationId" -> {
                        assertThat(arguments).containsExactly(41L);
                        calls.add("lockEarliest");
                        yield 12L;
                    }
                    case "lockBootstrapClaimBySponsor" -> {
                        assertThat(arguments).containsExactly(41L);
                        calls.add("lockSponsorClaim");
                        yield pendingRepair();
                    }
                    case "lockBootstrapClaimByInvitation" -> {
                        assertThat(arguments).containsExactly(12L);
                        calls.add("lockConflict");
                        yield null;
                    }
                    case "markBootstrapInvitation" -> {
                        assertThat(arguments).containsExactly(12L, 41L);
                        calls.add("markInvitation");
                        yield markResult;
                    }
                    case "linkBootstrapInvitation" -> {
                        assertThat(arguments).containsExactly(9L, 12L, 3);
                        calls.add("linkClaim");
                        yield 1;
                    }
                    case "clearBootstrapInvitationRepairGuard" -> {
                        assertThat(arguments).containsExactly(5);
                        calls.add("clearGuard");
                        yield 1;
                    }
                    default -> throw new AssertionError("Unexpected mapper call: " + method.getName());
                }
        );
    }

    private static BootstrapInvitationRepairGuardRow clearRepairGuard() {
        BootstrapInvitationRepairGuardRow row = new BootstrapInvitationRepairGuardRow();
        row.id = 1;
        row.repairRequired = false;
        row.version = 0;
        return row;
    }

    private static BootstrapInvitationRepairGuardRow malformedRepairGuard() {
        BootstrapInvitationRepairGuardRow row = new BootstrapInvitationRepairGuardRow();
        row.id = 1;
        row.repairRequired = null;
        row.version = 0;
        return row;
    }

    private static BootstrapInvitationRepairGuardRow repairGuard() {
        BootstrapInvitationRepairGuardRow row = new BootstrapInvitationRepairGuardRow();
        row.id = 1;
        row.repairRequired = true;
        row.version = 5;
        return row;
    }

    private static BootstrapInvitationRepairRow pendingRepair() {
        BootstrapInvitationRepairRow row = new BootstrapInvitationRepairRow();
        row.claimId = 9L;
        row.sponsorUserId = 41L;
        row.claimStatus = "PENDING";
        row.claimVersion = 3;
        row.invitationRepairRequired = true;
        return row;
    }

    private static InvitationOwnerRow invitationOwner() {
        InvitationOwnerRow row = new InvitationOwnerRow();
        row.inviterUserId = 41L;
        return row;
    }

    private static InvitationRow legacyInvitation() {
        InvitationRow row = new InvitationRow();
        row.id = 12L;
        row.inviterUserId = 41L;
        row.status = "ACTIVE";
        row.useCount = 1;
        row.bootstrap = false;
        return row;
    }

    private static InvitationEligibilityRow activeEligibility() {
        InvitationEligibilityRow row = new InvitationEligibilityRow();
        row.userId = 41L;
        row.userStatus = "ACTIVE";
        row.levelStatus = "ACTIVE";
        row.invitationEnabled = true;
        return row;
    }

    private static IdentityMapper mapperThatFailsOnUnexpectedCall() {
        return (IdentityMapper) Proxy.newProxyInstance(
                IdentityMapper.class.getClassLoader(),
                new Class<?>[]{IdentityMapper.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("Unexpected mapper call: " + method.getName());
                }
        );
    }
}

package com.marketshop.infrastructure.identity;

import cn.hutool.crypto.digest.BCrypt;
import com.marketshop.application.identity.SponsorClaimSecrets;
import com.marketshop.infrastructure.persistence.mapper.IdentityMapper;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminAccountPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.UserAccountPo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
                        assertThat(arguments).containsExactly("BOOTSTRAP2026", 41L);
                        calls.add("invitation");
                        yield 1;
                    }
                    case "insertBootstrapSponsorClaim" -> {
                        assertThat(arguments).containsExactly(41L, CLAIM_SECRET_HASH);
                        assertThat(arguments[1]).isNotEqualTo(CLAIM_SECRET);
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
    void disabledBootstrapDoesNotReadOrWriteIdentityData() {
        IdentityMapper mapper = mapperThatFailsOnUnexpectedCall();
        BootstrapIdentityInitializer initializer = new BootstrapIdentityInitializer(
                mapper,
                false,
                "admin",
                "",
                "BOOTSTRAP2026",
                ""
        );

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();
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

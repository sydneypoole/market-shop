package com.marketshop.infrastructure.identity;

import cn.hutool.crypto.digest.BCrypt;
import com.marketshop.infrastructure.persistence.mapper.IdentityMapper;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminAccountPo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BootstrapIdentityInitializerTest {

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
                    default -> throw new AssertionError("Unexpected mapper call: " + method.getName());
                }
        );
        String temporaryPassword = "StrongAdmin2026";
        BootstrapIdentityInitializer initializer = new BootstrapIdentityInitializer(
                mapper,
                true,
                "admin",
                temporaryPassword,
                "BOOTSTRAP2026"
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
    void disabledBootstrapDoesNotReadOrWriteIdentityData() {
        IdentityMapper mapper = mapperThatFailsOnUnexpectedCall();
        BootstrapIdentityInitializer initializer = new BootstrapIdentityInitializer(
                mapper,
                false,
                "admin",
                "",
                "BOOTSTRAP2026"
        );

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();
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
                "BOOTSTRAP2026"
        );

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();
        assertThat(calls).containsExactly("countUsers", "countAdmins");
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

package com.marketshop.application.identity;

import com.marketshop.application.identity.IdentityPorts.AdminCredential;
import com.marketshop.application.identity.IdentityPorts.AdminIdentityPort;
import com.marketshop.application.identity.IdentityPorts.PasswordHasher;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAuthApplicationServiceTest {

    @Test
    void returnsRolesAndPermissionsAfterSuccessfulPasswordVerification() {
        FakeAdminPort port = new FakeAdminPort(activeCredential(null, 0));
        PasswordHasher hasher = (raw, encoded) -> "correct".equals(raw);
        AdminAuthApplicationService service = new AdminAuthApplicationService(port, hasher);

        var result = service.login(new AdminAuthUseCase.LoginCommand("admin", "correct"));

        assertThat(result.roles()).containsExactly("SUPER_ADMIN");
        assertThat(result.permissions()).contains("order:review");
        assertThat(port.successRecorded).isTrue();
    }

    @Test
    void locksAccountOnFifthFailure() {
        FakeAdminPort port = new FakeAdminPort(activeCredential(null, 4));
        AdminAuthApplicationService service = new AdminAuthApplicationService(port, (raw, encoded) -> false);

        assertThatThrownBy(() -> service.login(new AdminAuthUseCase.LoginCommand("admin", "wrong")))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("ADMIN_CREDENTIALS_INVALID");
        assertThat(port.failedAttempts).isEqualTo(5);
        assertThat(port.lockedUntil).isAfter(Instant.now());
    }

    @Test
    void refusesLoginWhileAccountIsLocked() {
        FakeAdminPort port = new FakeAdminPort(activeCredential(Instant.now().plusSeconds(60), 5));
        AdminAuthApplicationService service = new AdminAuthApplicationService(port, (raw, encoded) -> true);

        assertThatThrownBy(() -> service.login(new AdminAuthUseCase.LoginCommand("admin", "correct")))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("ADMIN_LOCKED");
        assertThat(port.successRecorded).isFalse();
    }

    private static AdminCredential activeCredential(Instant lockedUntil, int failedAttempts) {
        return new AdminCredential(
                1,
                "admin",
                "hash",
                "超级管理员",
                "ACTIVE",
                true,
                failedAttempts,
                lockedUntil,
                Set.of("SUPER_ADMIN"),
                Set.of("order:review")
        );
    }

    private static final class FakeAdminPort implements AdminIdentityPort {
        private final AdminCredential credential;
        private int failedAttempts;
        private Instant lockedUntil;
        private boolean successRecorded;

        private FakeAdminPort(AdminCredential credential) {
            this.credential = credential;
        }

        @Override
        public Optional<AdminCredential> findByUsername(String username) {
            return Optional.of(credential);
        }

        @Override
        public Optional<AdminCredential> findById(long adminId) {
            return Optional.of(credential);
        }

        @Override
        public void recordFailure(long adminId, int nextFailedAttempts, Instant lockedUntil) {
            this.failedAttempts = nextFailedAttempts;
            this.lockedUntil = lockedUntil;
        }

        @Override
        public void recordSuccess(long adminId) {
            successRecorded = true;
        }
    }
}

package com.marketshop.application.identity;

import com.marketshop.application.identity.IdentityPorts.AdminCredential;
import com.marketshop.application.identity.IdentityPorts.AdminFailureResult;
import com.marketshop.application.identity.IdentityPorts.AdminIdentityPort;
import com.marketshop.application.identity.IdentityPorts.PasswordHasher;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAuthApplicationServiceTest {

    @Test
    void returnsRolesAndPermissionsAfterSuccessfulPasswordVerification() {
        FakeAdminPort port = new FakeAdminPort(activeCredential(null, 0));
        PasswordHasher hasher = (raw, encoded) -> "correct".equals(raw);
        CapturingSessionControl sessions = new CapturingSessionControl();
        AdminAuthApplicationService service = new AdminAuthApplicationService(port, hasher, sessions);

        var result = service.login(new AdminAuthUseCase.LoginCommand("admin", "correct"));

        assertThat(result.roles()).containsExactly("SUPER_ADMIN");
        assertThat(result.permissions()).contains("order:review");
        assertThat(result.authEpoch()).isEqualTo(3L);
        assertThat(port.successRecorded).isTrue();
        assertThat(sessions.invalidatedAdminId).isNull();
    }

    @Test
    void locksAccountOnFifthFailure() {
        FakeAdminPort port = new FakeAdminPort(activeCredential(null, 4));
        CapturingSessionControl sessions = new CapturingSessionControl();
        AdminAuthApplicationService service = new AdminAuthApplicationService(
                port, (raw, encoded) -> false, sessions
        );

        assertThatThrownBy(() -> service.login(new AdminAuthUseCase.LoginCommand("admin", "wrong")))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("ADMIN_CREDENTIALS_INVALID");
        assertThat(port.failedAttempts.get()).isEqualTo(5);
        assertThat(port.lockedUntil).isAfter(Instant.now());
        assertThat(sessions.invalidatedAdminId).isEqualTo(1);
    }

    @Test
    void refusesLoginWhileAccountIsLocked() {
        FakeAdminPort port = new FakeAdminPort(activeCredential(Instant.now().plusSeconds(60), 5));
        CapturingSessionControl sessions = new CapturingSessionControl();
        AdminAuthApplicationService service = new AdminAuthApplicationService(
                port, (raw, encoded) -> true, sessions
        );

        assertThatThrownBy(() -> service.login(new AdminAuthUseCase.LoginCommand("admin", "correct")))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("ADMIN_CREDENTIALS_INVALID");
        assertThat(port.successRecorded).isFalse();
        assertThat(sessions.invalidatedAdminId).isEqualTo(1);
    }

    @Test
    void passwordResetRacingSuccessfulLoginCannotAuthorizeTheOldPasswordAtTheNewEpoch() {
        AdminCredential initial = activeCredential(null, 0);
        AdminCredential reset = new AdminCredential(
                initial.adminId(),
                initial.username(),
                "reset-hash",
                initial.displayName(),
                initial.status(),
                true,
                0,
                null,
                initial.authEpoch() + 1,
                initial.roles(),
                initial.permissions()
        );
        FakeAdminPort port = new FakeAdminPort(initial);
        port.refreshedCredential = reset;
        PasswordHasher hasher = (raw, encoded) ->
                "old-password".equals(raw) && "hash".equals(encoded);
        CapturingSessionControl sessions = new CapturingSessionControl();
        AdminAuthApplicationService service = new AdminAuthApplicationService(port, hasher, sessions);

        assertThatThrownBy(() -> service.login(
                new AdminAuthUseCase.LoginCommand("admin", "old-password")
        )).isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("ADMIN_CREDENTIALS_INVALID");
        assertThat(sessions.invalidatedAdminId).isEqualTo(initial.adminId());
    }

    @Test
    void concurrentFailuresCapAtFiveAndBumpTheEpochOnlyOnce() throws Exception {
        FakeAdminPort port = new FakeAdminPort(activeCredential(null, 0));
        CapturingSessionControl sessions = new CapturingSessionControl();
        AdminAuthApplicationService service = new AdminAuthApplicationService(
                port, (raw, encoded) -> false, sessions
        );
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(index -> executor.submit(() -> {
                        try {
                            service.login(new AdminAuthUseCase.LoginCommand("admin", "wrong"));
                        } catch (DomainException exception) {
                            assertThat(exception.code()).isEqualTo("ADMIN_CREDENTIALS_INVALID");
                        }
                    }))
                    .toList();
            for (var future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        }

        assertThat(port.failedAttempts.get()).isEqualTo(5);
        assertThat(port.authEpoch.get()).isEqualTo(4L);
        assertThat(port.lockedUntil).isAfter(Instant.now());
        assertThat(sessions.invalidatedAdminId).isEqualTo(1L);
    }

    private static final class CapturingSessionControl implements AccountSessionControlPort {
        private Long invalidatedAdminId;

        @Override
        public void invalidateMemberSessions(long userId) {
        }

        @Override
        public void invalidateAdminSessions(long adminId) {
            invalidatedAdminId = adminId;
        }
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
                3L,
                Set.of("SUPER_ADMIN"),
                Set.of("order:review")
        );
    }

    private static final class FakeAdminPort implements AdminIdentityPort {
        private final AdminCredential credential;
        private AdminCredential refreshedCredential;
        private final AtomicInteger failedAttempts;
        private final AtomicLong authEpoch = new AtomicLong(3L);
        private Instant lockedUntil;
        private boolean successRecorded;

        private FakeAdminPort(AdminCredential credential) {
            this.credential = credential;
            this.refreshedCredential = credential;
            this.failedAttempts = new AtomicInteger(credential.failedAttempts());
        }

        @Override
        public Optional<AdminCredential> findByUsername(String username) {
            return Optional.of(credential);
        }

        @Override
        public Optional<AdminCredential> findById(long adminId) {
            return Optional.of(refreshedCredential);
        }

        @Override
        public synchronized AdminFailureResult recordFailure(long adminId, int lockThreshold, Instant lockedUntil) {
            int before = failedAttempts.get();
            int current = Math.min(before + 1, lockThreshold);
            failedAttempts.set(current);
            if (before == lockThreshold - 1) {
                authEpoch.incrementAndGet();
            }
            this.lockedUntil = current >= lockThreshold ? lockedUntil : null;
            return new AdminFailureResult(current, this.lockedUntil, authEpoch.get());
        }

        @Override
        public void recordSuccess(long adminId) {
            successRecorded = true;
        }
    }
}

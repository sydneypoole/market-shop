package com.marketshop.application.identity;

import com.marketshop.application.identity.IdentityPorts.AdminCredential;
import com.marketshop.application.identity.IdentityPorts.AdminFailureResult;
import com.marketshop.application.identity.IdentityPorts.AdminIdentityPort;
import com.marketshop.application.identity.IdentityPorts.PasswordHasher;
import com.marketshop.domain.shared.DomainException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class AdminAuthApplicationService implements AdminAuthUseCase {

    private static final int MAX_FAILURES = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final AdminIdentityPort adminIdentityPort;
    private final PasswordHasher passwordHasher;
    private final AccountSessionControlPort sessionControlPort;

    public AdminAuthApplicationService(
            AdminIdentityPort adminIdentityPort,
            PasswordHasher passwordHasher,
            AccountSessionControlPort sessionControlPort
    ) {
        this.adminIdentityPort = adminIdentityPort;
        this.passwordHasher = passwordHasher;
        this.sessionControlPort = sessionControlPort;
    }

    @Override
    public LoginResult login(LoginCommand command) {
        String username = command == null || command.username() == null
                ? ""
                : command.username().trim();
        String password = command == null || command.password() == null
                ? ""
                : command.password();
        AdminCredential credential = adminIdentityPort.findByUsername(username)
                .orElseThrow(this::invalidCredentials);
        Instant now = Instant.now();
        if (!"ACTIVE".equals(credential.status())) {
            sessionControlPort.invalidateAdminSessions(credential.adminId());
            throw new DomainException("ADMIN_DISABLED", "后台账号已停用");
        }
        if (credential.lockedUntil() != null && credential.lockedUntil().isAfter(now)) {
            sessionControlPort.invalidateAdminSessions(credential.adminId());
            throw invalidCredentials();
        }
        if (!passwordHasher.matches(password, credential.passwordHash())) {
            AdminFailureResult failure = adminIdentityPort.recordFailure(
                    credential.adminId(),
                    MAX_FAILURES,
                    now.plus(LOCK_DURATION)
            );
            if (failure.lockedUntil() != null && failure.lockedUntil().isAfter(now)) {
                sessionControlPort.invalidateAdminSessions(credential.adminId());
            }
            throw invalidCredentials();
        }
        adminIdentityPort.recordSuccess(credential.adminId());

        /*
         * Password verification and the success counter reset are separate
         * writes.  A status/password/role mutation can therefore race with
         * the first read above.  Re-read the credential after the reset so a
         * newly issued browser session carries the current auth epoch and
         * permission snapshot rather than an immediately stale one.  The
         * epoch guard still closes the tiny window after this read and before
         * the HTTP response is committed.
         */
        AdminCredential refreshed = adminIdentityPort.findById(credential.adminId())
                .orElseThrow(this::invalidCredentials);
        Instant afterReset = Instant.now();
        if (!"ACTIVE".equals(refreshed.status())
                || refreshed.lockedUntil() != null && refreshed.lockedUntil().isAfter(afterReset)
                // A password reset can commit after the first credential read.
                // Carrying the refreshed epoch alone would otherwise turn an
                // already verified old password into a valid new session.
                || !passwordHasher.matches(password, refreshed.passwordHash())) {
            sessionControlPort.invalidateAdminSessions(refreshed.adminId());
            throw invalidCredentials();
        }
        return new LoginResult(
                refreshed.adminId(),
                refreshed.username(),
                refreshed.displayName(),
                refreshed.mustChangePassword(),
                refreshed.authEpoch(),
                refreshed.roles(),
                refreshed.permissions()
        );
    }

    private DomainException invalidCredentials() {
        return new DomainException("ADMIN_CREDENTIALS_INVALID", "用户名或密码错误");
    }
}

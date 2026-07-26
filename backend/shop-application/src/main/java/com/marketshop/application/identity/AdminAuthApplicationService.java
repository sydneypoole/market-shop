package com.marketshop.application.identity;

import com.marketshop.application.identity.IdentityPorts.AdminCredential;
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

    public AdminAuthApplicationService(AdminIdentityPort adminIdentityPort, PasswordHasher passwordHasher) {
        this.adminIdentityPort = adminIdentityPort;
        this.passwordHasher = passwordHasher;
    }

    @Override
    public LoginResult login(LoginCommand command) {
        AdminCredential credential = adminIdentityPort.findByUsername(command.username().trim())
                .orElseThrow(this::invalidCredentials);
        Instant now = Instant.now();
        if (!"ACTIVE".equals(credential.status())) {
            throw new DomainException("ADMIN_DISABLED", "后台账号已停用");
        }
        if (credential.lockedUntil() != null && credential.lockedUntil().isAfter(now)) {
            throw new DomainException("ADMIN_LOCKED", "登录失败次数过多，请稍后再试");
        }
        if (!passwordHasher.matches(command.password(), credential.passwordHash())) {
            int failures = credential.failedAttempts() + 1;
            Instant lockedUntil = failures >= MAX_FAILURES ? now.plus(LOCK_DURATION) : null;
            adminIdentityPort.recordFailure(credential.adminId(), failures, lockedUntil);
            throw invalidCredentials();
        }
        adminIdentityPort.recordSuccess(credential.adminId());
        return new LoginResult(
                credential.adminId(),
                credential.username(),
                credential.displayName(),
                credential.mustChangePassword(),
                credential.roles(),
                credential.permissions()
        );
    }

    private DomainException invalidCredentials() {
        return new DomainException("ADMIN_CREDENTIALS_INVALID", "用户名或密码错误");
    }
}

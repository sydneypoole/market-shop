package com.marketshop.application.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public final class IdentityPorts {

    private IdentityPorts() {
    }

    public interface WeChatOAuthPort {
        String authorizationUrl(String scene, String state, String callbackUri);

        WeChatIdentity exchange(String scene, String code);
    }

    public interface OAuthStateStore {
        String create(StatePayload payload, Duration ttl);

        Optional<StatePayload> consume(String state);
    }

    public interface UserIdentityPort {
        RegistrationResult findOrRegister(WeChatIdentity identity, String inviteCode);
    }

    public interface AdminIdentityPort {
        Optional<AdminCredential> findByUsername(String username);

        Optional<AdminCredential> findById(long adminId);

        void recordFailure(long adminId, int nextFailedAttempts, Instant lockedUntil);

        void recordSuccess(long adminId);
    }

    public interface PasswordHasher {
        boolean matches(String rawPassword, String encodedPassword);

        default String encode(String rawPassword) {
            throw new UnsupportedOperationException("Password encoding is not configured");
        }
    }

    public record StatePayload(String scene, String inviteCode, String redirectUri) {
    }

    public record WeChatIdentity(
            String provider,
            String appId,
            String openId,
            String unionId,
            String nickname,
            String avatarUrl
    ) {
    }

    public record RegistrationResult(long userId, String publicId, String nickname, boolean newlyRegistered) {
    }

    public record AdminCredential(
            long adminId,
            String username,
            String passwordHash,
            String displayName,
            String status,
            boolean mustChangePassword,
            int failedAttempts,
            Instant lockedUntil,
            Set<String> roles,
            Set<String> permissions
    ) {
    }
}

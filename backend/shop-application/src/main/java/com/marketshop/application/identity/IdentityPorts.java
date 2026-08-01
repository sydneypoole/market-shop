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
        String create(StatePayload payload, String browserBindingHash, Duration ttl);

        StateConsumeResult consume(String state, String browserBindingHash);
    }

    public interface UserIdentityPort {
        RegistrationResult findOrRegister(
                WeChatIdentity identity,
                String inviteCode,
                String sponsorClaimSecretHash
        );

        void recordLogin(long userId);
    }

    public interface AdminIdentityPort {
        Optional<AdminCredential> findByUsername(String username);

        Optional<AdminCredential> findById(long adminId);

        AdminFailureResult recordFailure(long adminId, int lockThreshold, Instant lockedUntil);

        void recordSuccess(long adminId);
    }

    public interface AccountAuthStatePort {
        Optional<AccountAuthState> memberState(long userId);

        Optional<AccountAuthState> adminState(long adminId);
    }

    public interface PasswordHasher {
        boolean matches(String rawPassword, String encodedPassword);

        default String encode(String rawPassword) {
            throw new UnsupportedOperationException("Password encoding is not configured");
        }
    }

    public enum StateConsumeStatus {
        CONSUMED,
        MISSING,
        BINDING_MISMATCH
    }

    public record StateConsumeResult(StateConsumeStatus status, StatePayload payload) {
    }

    public record StatePayload(
            String scene,
            String inviteCode,
            String sponsorClaimSecretHash,
            String redirectUri
    ) {
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

    public record RegistrationResult(
            long userId,
            String publicId,
            String nickname,
            String status,
            long authEpoch,
            boolean newlyRegistered,
            boolean sponsorClaimed
    ) {
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
            long authEpoch,
            Set<String> roles,
            Set<String> permissions
    ) {
    }

    public record AccountAuthState(String status, long authEpoch, Instant lockedUntil) {
    }

    public record AdminFailureResult(int failedAttempts, Instant lockedUntil, long authEpoch) {
    }
}

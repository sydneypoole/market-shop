package com.marketshop.application.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public final class IdentityPorts {

    private IdentityPorts() {
    }

    public interface WeChatMiniprogramPort {
        WeChatIdentity exchangeMiniprogramCode(String jsCode);

        VerifiedPhone exchangePhoneCode(String dynamicCode);

        default WxaCodeImage createWxaCode(WxaCodeCommand command) {
            throw new UnsupportedOperationException("createWxaCode");
        }
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

    public record WeChatIdentity(
            String provider,
            String appId,
            String openId,
            String unionId,
            String nickname,
            String avatarUrl
    ) {
    }

    /**
     * A phone number verified by WeChat's server-side getPhoneNumber API.
     *
     * <p>The unmasked value is intentionally confined to this short-lived
     * application boundary. Persistence adapters accept only the derived
     * masked representation.</p>
     */
    public record VerifiedPhone(String purePhoneNumber) {
    }

    public record WxaCodeCommand(String page, String scene, String path) {
    }

    public record WxaCodeImage(String contentType, byte[] image) {
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

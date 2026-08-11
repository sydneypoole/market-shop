package com.marketshop.application.identity;

import com.marketshop.application.identity.IdentityAvatarSanitizerPort.SanitizedAvatar;
import com.marketshop.application.identity.IdentityAvatarStoragePort.StoredAvatar;
import com.marketshop.application.identity.IdentityPorts.VerifiedPhone;
import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.application.identity.MemberProfilePort.AvatarMetadata;
import com.marketshop.application.identity.MemberProfilePort.ProfileRecord;
import com.marketshop.application.identity.MemberProfileUseCase.UpdateNicknameCommand;
import com.marketshop.application.identity.MemberProfileUseCase.UpdateWechatProfileCommand;
import com.marketshop.application.identity.MemberProfileUseCase.UploadAvatarCommand;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemberProfileApplicationServiceTest {

    private final PhoneFake weChat = new PhoneFake();
    private final ProfileFake profiles = new ProfileFake();
    private final StorageFake storage = new StorageFake();
    private final SanitizerFake sanitizer = new SanitizerFake();
    private final MemberProfileApplicationService service = new MemberProfileApplicationService(
            weChat, profiles, storage, sanitizer, 5L * 1024 * 1024
    );

    @Test
    void verifiesPhoneServerSideAndPersistsOnlyTheMaskedValue() {
        var view = service.updateWechatProfile(
                42,
                new UpdateWechatProfileCommand("  宏杉会员  ", " one-time-phone-code ")
        );

        assertThat(weChat.lastPhoneCode).isEqualTo("one-time-phone-code");
        assertThat(profiles.lastNickname).isEqualTo("宏杉会员");
        assertThat(profiles.lastPhoneMasked).isEqualTo("138****8000");
        assertThat(profiles.lastPhoneMasked).doesNotContain("13800138000");
        assertThat(view.nickname()).isEqualTo("宏杉会员");
        assertThat(view.phoneMasked()).isEqualTo("138****8000");
        assertThat(view.phoneVerifiedAt()).isNotNull();
    }

    @Test
    void rejectsInvalidNicknameAndPhoneBeforePersistence() {
        assertThatThrownBy(() -> service.updateWechatProfile(
                42, new UpdateWechatProfileCommand(" ", "phone-code")
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("MEMBER_NICKNAME_REQUIRED"));

        assertThatThrownBy(() -> service.updateWechatProfile(
                42, new UpdateWechatProfileCommand("x".repeat(33), "phone-code")
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("MEMBER_NICKNAME_INVALID"));

        assertThatThrownBy(() -> service.updateWechatProfile(
                42, new UpdateWechatProfileCommand("会员", " ")
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("WECHAT_PHONE_CODE_REQUIRED"));

        assertThat(weChat.calls).isZero();
        assertThat(profiles.lastPhoneMasked).isNull();
    }

    @Test
    void rejectsAnInvalidPhoneReturnedByTheWechatBoundary() {
        weChat.phone = "138-0013-8000";

        assertThatThrownBy(() -> service.updateWechatProfile(
                42, new UpdateWechatProfileCommand("会员", "phone-code")
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("WECHAT_PHONE_INVALID"));
        assertThat(profiles.lastPhoneMasked).isNull();
    }

    @Test
    void updatesOnlyTheNormalizedNicknameWithTheCurrentProfileVersion() {
        profiles.phoneMasked = "138****8000";
        profiles.phoneVerifiedAt = Instant.parse("2026-08-12T01:00:00Z");
        profiles.avatarUrl = "/api/v1/member-avatars/42";
        int versionBefore = profiles.version;

        var view = service.updateNickname(42, new UpdateNicknameCommand("  杉杉  "));

        assertThat(weChat.calls).isZero();
        assertThat(profiles.nicknameUpdates).isEqualTo(1);
        assertThat(profiles.lastExpectedNicknameVersion).isEqualTo(versionBefore);
        assertThat(profiles.lastNickname).isEqualTo("杉杉");
        assertThat(profiles.version).isEqualTo(versionBefore + 1);
        assertThat(view.nickname()).isEqualTo("杉杉");
        assertThat(view.avatarUrl()).isEqualTo("/api/v1/member-avatars/42");
        assertThat(view.phoneMasked()).isEqualTo("138****8000");
        assertThat(view.phoneVerifiedAt()).isEqualTo(Instant.parse("2026-08-12T01:00:00Z"));
    }

    @Test
    void sameNormalizedNicknameIsANoOpWithoutPhoneExchangeOrVersionChange() {
        profiles.nickname = "宏杉会员";
        int versionBefore = profiles.version;

        var view = service.updateNickname(42, new UpdateNicknameCommand("  宏杉会员  "));
        var unicodeWhitespaceView = service.updateNickname(
                42, new UpdateNicknameCommand("\u3000宏杉会员\u3000")
        );

        assertThat(view.nickname()).isEqualTo("宏杉会员");
        assertThat(unicodeWhitespaceView.nickname()).isEqualTo("宏杉会员");
        assertThat(weChat.calls).isZero();
        assertThat(profiles.nicknameUpdates).isZero();
        assertThat(profiles.version).isEqualTo(versionBefore);
    }

    @Test
    void nicknameOnlyUpdateUsesUnicodeCodePointsAndRejectsControls() {
        String thirtyTwoEmoji = "🌲".repeat(32);
        service.updateNickname(42, new UpdateNicknameCommand(thirtyTwoEmoji));
        assertThat(profiles.nickname).isEqualTo(thirtyTwoEmoji);

        assertThatThrownBy(() -> service.updateNickname(
                42, new UpdateNicknameCommand("🌲".repeat(33))
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("MEMBER_NICKNAME_INVALID"));
        assertThatThrownBy(() -> service.updateNickname(
                42, new UpdateNicknameCommand("会员\u0007")
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("MEMBER_NICKNAME_INVALID"));
        assertThatThrownBy(() -> service.updateNickname(
                42, new UpdateNicknameCommand(" ")
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("MEMBER_NICKNAME_REQUIRED"));
        assertThat(weChat.calls).isZero();
    }

    @Test
    void nicknameCompareAndSetLossRemainsAStableConflict() {
        profiles.failNicknameUpdate = true;

        assertThatThrownBy(() -> service.updateNickname(
                42, new UpdateNicknameCommand("并发昵称")
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("MEMBER_PROFILE_CONFLICT"));
        assertThat(weChat.calls).isZero();
    }

    @Test
    void storesOnlySanitizedAvatarBytesAndPublishesAStableOwnedUrl() {
        profiles.avatarObjectKey = "avatars/42/previous.png";
        byte[] temporaryWechatBytes = {1, 2, 3};

        var view = service.uploadAvatar(
                42,
                new UploadAvatarCommand("wxfile://tmp/avatar.png", temporaryWechatBytes)
        );

        assertThat(sanitizer.input).isEqualTo(temporaryWechatBytes);
        assertThat(storage.storedBytes).containsExactly(9, 8, 7);
        assertThat(storage.originalFilename).isEqualTo("avatar.png");
        assertThat(storage.objectKey).startsWith("avatars/42/");
        assertThat(profiles.avatarUrl).isEqualTo("/api/v1/member-avatars/42");
        assertThat(profiles.avatarObjectKey).isEqualTo(storage.objectKey);
        assertThat(profiles.avatarObjectKey).doesNotContain("wxfile://");
        assertThat(storage.deletedKeys).containsExactly("avatars/42/previous.png");
        assertThat(view.avatarUrl()).isEqualTo("/api/v1/member-avatars/42");
    }

    @Test
    void deletesTheNewObjectWhenMetadataPersistenceLosesTheVersionRace() {
        profiles.failAvatarUpdate = true;

        assertThatThrownBy(() -> service.uploadAvatar(
                42, new UploadAvatarCommand("avatar.png", new byte[]{1})
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("MEMBER_PROFILE_CONFLICT"));

        assertThat(storage.deletedKeys).containsExactly(storage.objectKey);
    }

    @Test
    void deletesTheNewObjectButKeepsThePreviousAvatarWhenTheTransactionRollsBack() {
        profiles.avatarObjectKey = "avatars/42/previous.png";
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.uploadAvatar(42, new UploadAvatarCommand("avatar.png", new byte[]{1}));

            assertThat(storage.deletedKeys).isEmpty();
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            synchronizations.forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            assertThat(storage.deletedKeys)
                    .containsExactly(storage.objectKey)
                    .doesNotContain("avatars/42/previous.png");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void keepsTheNewObjectAndDeletesThePreviousAvatarOnlyAfterCommit() {
        profiles.avatarObjectKey = "avatars/42/previous.png";
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.uploadAvatar(42, new UploadAvatarCommand("avatar.png", new byte[]{1}));

            assertThat(storage.deletedKeys).isEmpty();
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            synchronizations.forEach(TransactionSynchronization::afterCommit);
            synchronizations.forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

            assertThat(storage.deletedKeys)
                    .containsExactly("avatars/42/previous.png")
                    .doesNotContain(storage.objectKey);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void mapsAvatarValidationAndSizeFailuresToIdentityErrors() {
        assertThatThrownBy(() -> service.uploadAvatar(
                42, new UploadAvatarCommand("avatar.png", new byte[0])
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("MEMBER_AVATAR_CONTENT_REQUIRED"));

        MemberProfileApplicationService tinyLimit = new MemberProfileApplicationService(
                weChat, profiles, storage, sanitizer, 2
        );
        assertThatThrownBy(() -> tinyLimit.uploadAvatar(
                42, new UploadAvatarCommand("avatar.png", new byte[]{1, 2, 3})
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("MEMBER_AVATAR_SIZE_INVALID"));

        sanitizer.failureCode = "PROOF_IMAGE_INVALID";
        assertThatThrownBy(() -> service.uploadAvatar(
                42, new UploadAvatarCommand("avatar.png", new byte[]{1})
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("MEMBER_AVATAR_IMAGE_INVALID"));
    }

    @Test
    void readsAvatarOnlyThroughTheStoredOwnedObjectReference() {
        profiles.avatarObjectKey = "avatars/42/current.png";
        profiles.avatarMediaType = "image/png";
        storage.readBytes = new byte[]{4, 5, 6};

        var content = service.avatar(42);

        assertThat(storage.readKey).isEqualTo("avatars/42/current.png");
        assertThat(content.mediaType()).isEqualTo("image/png");
        assertThat(content.bytes()).containsExactly(4, 5, 6);
    }

    @Test
    void phoneMaskNeverExposesASevenDigitNumber() {
        assertThat(MemberProfileApplicationService.maskPhone("1234567"))
                .isEqualTo("1****67")
                .doesNotContain("1234567");
    }

    private static final class PhoneFake implements IdentityPorts.WeChatMiniprogramPort {
        private String phone = "13800138000";
        private String lastPhoneCode;
        private int calls;

        @Override
        public WeChatIdentity exchangeMiniprogramCode(String jsCode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VerifiedPhone exchangePhoneCode(String dynamicCode) {
            calls++;
            lastPhoneCode = dynamicCode;
            return new VerifiedPhone(phone);
        }
    }

    private static final class ProfileFake implements MemberProfilePort {
        private String nickname = "微信用户";
        private String avatarUrl;
        private String phoneMasked;
        private Instant phoneVerifiedAt;
        private String avatarObjectKey;
        private String avatarMediaType;
        private String avatarSha256;
        private Long avatarSizeBytes;
        private Instant avatarUpdatedAt;
        private int version = 2;
        private String lastNickname;
        private String lastPhoneMasked;
        private int lastExpectedNicknameVersion = -1;
        private int nicknameUpdates;
        private boolean failNicknameUpdate;
        private boolean failAvatarUpdate;

        @Override
        public ProfileRecord profile(long userId) {
            return new ProfileRecord(
                    userId, nickname, avatarUrl, phoneMasked, phoneVerifiedAt,
                    avatarObjectKey, avatarMediaType, avatarSha256, avatarSizeBytes,
                    avatarUpdatedAt, version
            );
        }

        @Override
        public void updateWechatProfile(
                long userId,
                String nickname,
                String phoneMasked,
                Instant phoneVerifiedAt
        ) {
            lastNickname = nickname;
            lastPhoneMasked = phoneMasked;
            this.nickname = nickname;
            this.phoneMasked = phoneMasked;
            this.phoneVerifiedAt = phoneVerifiedAt;
            version++;
        }

        @Override
        public void updateNickname(long userId, int expectedVersion, String nickname) {
            if (failNicknameUpdate || expectedVersion != version) {
                throw new DomainException("MEMBER_PROFILE_CONFLICT", "conflict");
            }
            nicknameUpdates++;
            lastExpectedNicknameVersion = expectedVersion;
            lastNickname = nickname;
            this.nickname = nickname;
            version++;
        }

        @Override
        public void replaceAvatar(
                long userId,
                int expectedVersion,
                String avatarUrl,
                AvatarMetadata avatar
        ) {
            if (failAvatarUpdate || expectedVersion != version) {
                throw new DomainException("MEMBER_PROFILE_CONFLICT", "conflict");
            }
            this.avatarUrl = avatarUrl;
            avatarObjectKey = avatar.objectKey();
            avatarMediaType = avatar.mediaType();
            avatarSha256 = avatar.sha256();
            avatarSizeBytes = avatar.sizeBytes();
            avatarUpdatedAt = avatar.updatedAt();
            version++;
        }
    }

    private static final class StorageFake implements IdentityAvatarStoragePort {
        private final List<String> deletedKeys = new ArrayList<>();
        private String originalFilename;
        private byte[] storedBytes;
        private String objectKey;
        private String readKey;
        private byte[] readBytes = {9};

        @Override
        public StoredAvatar putAvatar(
                long userId,
                String originalFilename,
                String mediaType,
                byte[] bytes
        ) {
            this.originalFilename = originalFilename;
            storedBytes = bytes;
            objectKey = "avatars/" + userId + "/owned.png";
            return new StoredAvatar(objectKey, "a".repeat(64), bytes.length);
        }

        @Override
        public byte[] readAvatar(String objectKey) {
            readKey = objectKey;
            return readBytes;
        }

        @Override
        public void deleteAvatar(String objectKey) {
            deletedKeys.add(objectKey);
        }
    }

    private static final class SanitizerFake implements IdentityAvatarSanitizerPort {
        private byte[] input;
        private String failureCode;

        @Override
        public SanitizedAvatar sanitizeAvatar(byte[] bytes) {
            input = bytes;
            if (failureCode != null) {
                throw new DomainException(failureCode, "invalid");
            }
            return new SanitizedAvatar("image/png", "png", new byte[]{9, 8, 7});
        }
    }
}

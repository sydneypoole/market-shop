package com.marketshop.application.identity;

import com.marketshop.application.identity.IdentityAvatarStoragePort.StoredAvatar;
import com.marketshop.application.identity.IdentityAvatarSanitizerPort.SanitizedAvatar;
import com.marketshop.application.identity.IdentityPorts.VerifiedPhone;
import com.marketshop.application.identity.MemberProfilePort.AvatarMetadata;
import com.marketshop.application.identity.MemberProfilePort.ProfileRecord;
import com.marketshop.domain.shared.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Objects;

@Service
@Transactional
public class MemberProfileApplicationService implements MemberProfileUseCase {

    private static final int MAX_NICKNAME_CODE_POINTS = 32;

    private final IdentityPorts.WeChatMiniprogramPort weChat;
    private final MemberProfilePort profiles;
    private final IdentityAvatarStoragePort avatarStorage;
    private final IdentityAvatarSanitizerPort sanitizer;
    private final long maxAvatarSizeBytes;

    public MemberProfileApplicationService(
            IdentityPorts.WeChatMiniprogramPort weChat,
            MemberProfilePort profiles,
            IdentityAvatarStoragePort avatarStorage,
            IdentityAvatarSanitizerPort sanitizer,
            @Value("${market-shop.identity.avatar-max-size-bytes:5242880}") long maxAvatarSizeBytes
    ) {
        this.weChat = weChat;
        this.profiles = profiles;
        this.avatarStorage = avatarStorage;
        this.sanitizer = sanitizer;
        this.maxAvatarSizeBytes = Math.max(1, Math.min(maxAvatarSizeBytes, 10L * 1024 * 1024));
    }

    @Override
    public ProfileView updateWechatProfile(long userId, UpdateWechatProfileCommand command) {
        if (command == null) {
            throw new DomainException("MEMBER_PROFILE_INVALID", "微信注册资料不能为空");
        }
        String nickname = normalizeNickname(command.nickname());
        String phoneCode = trimToNull(command.phoneCode());
        if (phoneCode == null || phoneCode.length() > 256) {
            throw new DomainException("WECHAT_PHONE_CODE_REQUIRED", "请重新授权微信手机号");
        }
        VerifiedPhone verifiedPhone = weChat.exchangePhoneCode(phoneCode);
        String phoneMasked = maskPhone(verifiedPhone == null ? null : verifiedPhone.purePhoneNumber());
        profiles.updateWechatProfile(userId, nickname, phoneMasked, Instant.now());
        return view(profiles.profile(userId));
    }

    @Override
    public ProfileView updateNickname(long userId, UpdateNicknameCommand command) {
        if (command == null) {
            throw new DomainException("MEMBER_PROFILE_INVALID", "会员昵称资料不能为空");
        }
        String nickname = normalizeNickname(command.nickname());
        ProfileRecord current = profiles.profile(userId);
        if (Objects.equals(nickname, current.nickname())) {
            return view(current);
        }
        profiles.updateNickname(userId, current.version(), nickname);
        return view(profiles.profile(userId));
    }

    @Override
    public ProfileView uploadAvatar(long userId, UploadAvatarCommand command) {
        if (command == null || command.bytes() == null || command.bytes().length == 0) {
            throw new DomainException("MEMBER_AVATAR_CONTENT_REQUIRED", "请选择微信头像");
        }
        if (command.bytes().length > maxAvatarSizeBytes) {
            throw new DomainException("MEMBER_AVATAR_SIZE_INVALID", "会员头像不能超过上传大小限制");
        }
        ProfileRecord before = profiles.profile(userId);
        SanitizedAvatar image = sanitizeAvatar(command.bytes());
        StoredAvatar stored = avatarStorage.putAvatar(
                userId,
                "avatar." + image.extension(),
                image.mediaType(),
                image.bytes()
        );
        boolean rollbackCleanupRegistered = registerNewAvatarRollbackCleanup(stored.objectKey());
        Instant updatedAt = Instant.now();
        try {
            profiles.replaceAvatar(
                    userId,
                    before.version(),
                    stableAvatarUrl(userId),
                    new AvatarMetadata(
                            stored.objectKey(),
                            image.mediaType(),
                            stored.sha256(),
                            stored.sizeBytes(),
                            updatedAt
                    )
            );
            deletePreviousAvatarAfterCommit(before.avatarObjectKey(), stored.objectKey());
            return view(profiles.profile(userId));
        } catch (RuntimeException exception) {
            if (!rollbackCleanupRegistered) {
                deleteAvatarQuietly(stored.objectKey());
            }
            throw exception;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AvatarContent avatar(long userId) {
        ProfileRecord profile = profiles.profile(userId);
        if (trimToNull(profile.avatarObjectKey()) == null
                || trimToNull(profile.avatarMediaType()) == null) {
            throw new DomainException("MEMBER_AVATAR_NOT_FOUND", "会员头像不存在");
        }
        byte[] bytes = avatarStorage.readAvatar(profile.avatarObjectKey());
        if (bytes == null || bytes.length == 0) {
            throw new DomainException("MEMBER_AVATAR_NOT_FOUND", "会员头像不存在");
        }
        return new AvatarContent(profile.avatarMediaType(), bytes);
    }

    static String maskPhone(String value) {
        String phone = trimToNull(value);
        if (phone == null || phone.length() < 6 || phone.length() > 20
                || !phone.chars().allMatch(character -> character >= '0' && character <= '9')) {
            throw new DomainException("WECHAT_PHONE_INVALID", "微信返回的手机号无效，请重试");
        }
        if (phone.length() <= 7) {
            return phone.substring(0, 1)
                    + "*".repeat(phone.length() - 3)
                    + phone.substring(phone.length() - 2);
        }
        return phone.substring(0, 3)
                + "*".repeat(phone.length() - 7)
                + phone.substring(phone.length() - 4);
    }

    private static String normalizeNickname(String value) {
        if (value != null && value.codePoints().anyMatch(Character::isISOControl)) {
            throw new DomainException("MEMBER_NICKNAME_INVALID", "微信昵称长度或内容无效");
        }
        String nickname = trimToNull(value);
        if (nickname == null) {
            throw new DomainException("MEMBER_NICKNAME_REQUIRED", "请输入微信昵称");
        }
        if (nickname.codePointCount(0, nickname.length()) > MAX_NICKNAME_CODE_POINTS) {
            throw new DomainException("MEMBER_NICKNAME_INVALID", "微信昵称长度或内容无效");
        }
        return nickname;
    }

    private SanitizedAvatar sanitizeAvatar(byte[] bytes) {
        try {
            return sanitizer.sanitizeAvatar(bytes);
        } catch (DomainException exception) {
            if ("PROOF_TYPE_INVALID".equals(exception.code())) {
                throw new DomainException(
                        "MEMBER_AVATAR_TYPE_INVALID",
                        "会员头像真实文件类型仅支持 JPG、PNG 或 WebP"
                );
            }
            if ("PROOF_IMAGE_INVALID".equals(exception.code())) {
                throw new DomainException(
                        "MEMBER_AVATAR_IMAGE_INVALID",
                        "会员头像图片损坏、尺寸异常或格式不受支持"
                );
            }
            throw exception;
        }
    }

    private boolean registerNewAvatarRollbackCleanup(String objectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteAvatarQuietly(objectKey);
                }
            }
        });
        return true;
    }

    private void deleteAvatarQuietly(String objectKey) {
        try {
            avatarStorage.deleteAvatar(objectKey);
        } catch (RuntimeException cleanupFailure) {
            // The provider's operational telemetry retains the failed object cleanup.
        }
    }

    private void deletePreviousAvatarAfterCommit(String previousKey, String currentKey) {
        if (previousKey == null || previousKey.isBlank() || previousKey.equals(currentKey)) {
            return;
        }
        Runnable cleanup = () -> {
            try {
                avatarStorage.deleteAvatar(previousKey);
            } catch (RuntimeException cleanupFailure) {
                // A later storage reconciliation can remove the unreferenced previous object.
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            cleanup.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanup.run();
            }
        });
    }

    private static ProfileView view(ProfileRecord profile) {
        return new ProfileView(
                profile.userId(),
                profile.nickname(),
                profile.avatarUrl(),
                profile.phoneMasked(),
                profile.phoneVerifiedAt()
        );
    }

    private static String stableAvatarUrl(long userId) {
        return "/api/v1/member-avatars/" + userId;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isNicknameBoundaryWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!isNicknameBoundaryWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return start == end ? null : value.substring(start, end);
    }

    private static boolean isNicknameBoundaryWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || codePoint == 0xFEFF;
    }
}

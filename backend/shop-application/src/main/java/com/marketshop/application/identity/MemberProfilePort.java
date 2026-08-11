package com.marketshop.application.identity;

import java.time.Instant;

public interface MemberProfilePort {

    ProfileRecord profile(long userId);

    void updateWechatProfile(
            long userId,
            String nickname,
            String phoneMasked,
            Instant phoneVerifiedAt
    );

    void updateNickname(long userId, int expectedVersion, String nickname);

    void replaceAvatar(
            long userId,
            int expectedVersion,
            String avatarUrl,
            AvatarMetadata avatar
    );

    record ProfileRecord(
            long userId,
            String nickname,
            String avatarUrl,
            String phoneMasked,
            Instant phoneVerifiedAt,
            String avatarObjectKey,
            String avatarMediaType,
            String avatarSha256,
            Long avatarSizeBytes,
            Instant avatarUpdatedAt,
            int version
    ) {
    }

    record AvatarMetadata(
            String objectKey,
            String mediaType,
            String sha256,
            long sizeBytes,
            Instant updatedAt
    ) {
    }
}

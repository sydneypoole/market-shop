package com.marketshop.application.identity;

import java.time.Instant;

public interface MemberProfileUseCase {

    ProfileView updateWechatProfile(long userId, UpdateWechatProfileCommand command);

    ProfileView uploadAvatar(long userId, UploadAvatarCommand command);

    AvatarContent avatar(long userId);

    record UpdateWechatProfileCommand(String nickname, String phoneCode) {
    }

    record UploadAvatarCommand(String originalFilename, byte[] bytes) {
    }

    record ProfileView(
            long userId,
            String nickname,
            String avatarUrl,
            String phoneMasked,
            Instant phoneVerifiedAt
    ) {
    }

    record AvatarContent(String mediaType, byte[] bytes) {
    }
}

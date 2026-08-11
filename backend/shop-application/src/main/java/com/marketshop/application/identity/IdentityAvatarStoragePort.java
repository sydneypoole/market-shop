package com.marketshop.application.identity;

public interface IdentityAvatarStoragePort {

    StoredAvatar putAvatar(
            long userId,
            String originalFilename,
            String mediaType,
            byte[] bytes
    );

    byte[] readAvatar(String objectKey);

    void deleteAvatar(String objectKey);

    record StoredAvatar(String objectKey, String sha256, long sizeBytes) {
    }
}

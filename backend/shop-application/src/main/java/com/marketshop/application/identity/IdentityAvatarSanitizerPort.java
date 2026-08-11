package com.marketshop.application.identity;

public interface IdentityAvatarSanitizerPort {

    SanitizedAvatar sanitizeAvatar(byte[] bytes);

    record SanitizedAvatar(String mediaType, String extension, byte[] bytes) {
    }
}

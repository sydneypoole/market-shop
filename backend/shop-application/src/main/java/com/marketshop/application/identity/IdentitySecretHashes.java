package com.marketshop.application.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class IdentitySecretHashes {

    private IdentitySecretHashes() {
    }

    public static String sha256(String rawSecret) {
        if (rawSecret == null || rawSecret.isBlank()) {
            throw new IllegalArgumentException("Identity secret is required");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawSecret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

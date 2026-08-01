package com.marketshop.application.identity;

public final class SponsorClaimSecrets {

    public static final int MINIMUM_LENGTH = 32;

    private SponsorClaimSecrets() {
    }

    public static String sha256(String rawSecret) {
        return IdentitySecretHashes.sha256(rawSecret);
    }
}

package com.marketshop.infrastructure.invitation;

import java.util.UUID;

public final class FixedInvitationCodes {

    public static final int INSERT_ATTEMPTS = 8;

    private FixedInvitationCodes() {
    }

    public static String generate() {
        return "MS" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 10).toUpperCase();
    }
}

package com.marketshop.interfaces.identity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class LoginResponseContractTest {

    @Test
    void miniprogramLoginResponseExposesTokenWhileAdminSessionRemainsCookieOnly() {
        assertThat(componentNames(AuthController.MiniprogramLoginView.class))
                .containsExactly("token", "publicId", "nickname", "newlyRegistered")
                .contains("token");
        assertThat(componentNames(AuthController.SessionView.class))
                .containsExactly("publicId", "nickname", "newlyRegistered")
                .doesNotContain("token", "tokenName", "tokenValue");
        assertThat(componentNames(AdminAuthController.AdminSessionView.class))
                .containsExactly("username", "displayName", "mustChangePassword", "roles", "permissions")
                .doesNotContain("token", "tokenName", "tokenValue");
    }

    private static String[] componentNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toArray(String[]::new);
    }
}

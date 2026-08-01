package com.marketshop.interfaces.identity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class LoginResponseContractTest {

    @Test
    void browserLoginResponsesNeverExposeSaTokenMaterialToJavaScript() {
        assertThat(componentNames(AuthController.SessionView.class))
                .containsExactly("publicId", "nickname", "newlyRegistered")
                .doesNotContain("tokenName", "tokenValue");
        assertThat(componentNames(AdminAuthController.AdminSessionView.class))
                .containsExactly("username", "displayName", "mustChangePassword", "roles", "permissions")
                .doesNotContain("tokenName", "tokenValue");
    }

    private static String[] componentNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toArray(String[]::new);
    }
}

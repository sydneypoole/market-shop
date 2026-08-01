package com.marketshop.interfaces.identity;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthControllerRedirectTest {

    @Test
    void callbackRedirectIsAnchoredToTheConfiguredStorefrontOrigin() {
        URI location = AuthController.successfulRedirect(
                "/orders?tab=history#proofs",
                URI.create("https://shop.example.com")
        );

        assertThat(location.toString())
                .isEqualTo("https://shop.example.com/orders?tab=history&wechatLogin=success#proofs");
    }

    @Test
    void callbackRejectsAnUnexpectedAbsoluteOrNetworkPath() {
        for (String redirect : new String[]{
                "https://evil.example/steal",
                "//evil.example/steal",
                "/\\evil.example/steal"
        }) {
            assertThatThrownBy(() -> AuthController.successfulRedirect(
                    redirect, URI.create("https://shop.example.com")
            )).isInstanceOf(IllegalStateException.class);
        }
    }
}

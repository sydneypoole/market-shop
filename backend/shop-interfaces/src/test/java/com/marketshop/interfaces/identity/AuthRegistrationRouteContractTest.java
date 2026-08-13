package com.marketshop.interfaces.identity;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketshop.application.identity.AuthUseCase;
import com.marketshop.application.identity.IdentityPorts.AccountAuthStatePort;
import com.marketshop.interfaces.security.AccountSessionEpochGuard;
import com.marketshop.interfaces.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class AuthRegistrationRouteContractTest {

    @Test
    void separatesStrictCodeOnlyLoginFromCredentialOnlyJsonRegistration() throws Exception {
        Method login = AuthController.class.getMethod(
                "miniprogramLogin", AuthController.MiniprogramLoginRequest.class
        );
        Method register = AuthController.class.getMethod(
                "miniprogramRegister", AuthController.MiniprogramRegistrationRequest.class
        );

        assertThat(login.getAnnotation(PostMapping.class).value())
                .containsExactly("/wechat/miniprogram/login");
        assertThat(register.getAnnotation(PostMapping.class).value())
                .containsExactly("/wechat/miniprogram/register");
        assertThat(register.getParameterCount()).isOne();
    }

    @Test
    void registrationCommandContainsOnlyLoginAndRelationshipCredentials() {
        String[] fields = Arrays.stream(
                        AuthUseCase.MiniprogramRegistrationCommand.class.getRecordComponents()
                )
                .map(component -> component.getName())
                .toArray(String[]::new);

        assertThat(fields).containsExactly("code", "inviteCode", "sponsorClaimSecret");
        assertThat(fields).doesNotContain(
                "phone", "phoneCode", "phoneNumber", "nickname",
                "avatar", "avatarUrl", "avatarBytes"
        );
    }

    @Test
    void authDtosRejectProfileAndRelationshipFieldsEvenWhenGlobalBindingIgnoresUnknowns() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        assertThat(mapper.readValue(
                "{\"code\":\"LOGIN-CODE\"}", AuthController.MiniprogramLoginRequest.class
        ).code()).isEqualTo("LOGIN-CODE");
        assertThatThrownBy(() -> mapper.readValue(
                "{\"code\":\"LOGIN-CODE\",\"inviteCode\":\"MUST-NOT-BE-ACCEPTED\"}",
                AuthController.MiniprogramLoginRequest.class
        )).hasMessageContaining("Unsupported login request field: inviteCode");

        assertThat(mapper.readValue(
                "{\"code\":\"REGISTER-CODE\",\"inviteCode\":\"INVITE\"}",
                AuthController.MiniprogramRegistrationRequest.class
        ).inviteCode()).isEqualTo("INVITE");
        for (String field : new String[]{"phoneCode", "phone", "nickname", "avatarUrl"}) {
            assertThatThrownBy(() -> mapper.readValue(
                    "{\"code\":\"REGISTER-CODE\",\"inviteCode\":\"INVITE\",\""
                            + field + "\":\"MUST-NOT-BE-ACCEPTED\"}",
                    AuthController.MiniprogramRegistrationRequest.class
            )).hasMessageContaining("Unsupported registration request field: " + field);
        }
    }

    @Test
    void routesRejectUnknownEmptyAndMalformedBodiesBeforeTheUseCase() throws Exception {
        AtomicReference<String> invoked = new AtomicReference<>();
        AuthUseCase auth = (AuthUseCase) Proxy.newProxyInstance(
                AuthUseCase.class.getClassLoader(),
                new Class<?>[]{AuthUseCase.class},
                (proxy, method, arguments) -> {
                    invoked.set(method.getName());
                    throw new AssertionError("request body must be rejected before the use case");
                }
        );
        AccountAuthStatePort states = new AccountAuthStatePort() {
            @Override
            public Optional<com.marketshop.application.identity.IdentityPorts.AccountAuthState> memberState(
                    long userId
            ) {
                return Optional.empty();
            }

            @Override
            public Optional<com.marketshop.application.identity.IdentityPorts.AccountAuthState> adminState(
                    long adminId
            ) {
                return Optional.empty();
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(auth, new AccountSessionEpochGuard(states))
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        ObjectMapper json = new ObjectMapper();

        for (String body : new String[]{
                "",
                "{",
                "{\"code\":\"LOGIN\",\"nickname\":\"MUST-NOT-BE-ACCEPTED\"}"
        }) {
            var response = mvc.perform(post("/api/v1/auth/wechat/miniprogram/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(json.readTree(response.getContentAsString()).path("code").asText())
                    .isEqualTo("REQUEST_BODY_INVALID");
        }
        for (String body : new String[]{
                "",
                "{",
                "{\"code\":\"REGISTER\",\"inviteCode\":\"INVITE\",\"phoneCode\":\"NO\"}",
                "{\"code\":\"REGISTER\",\"inviteCode\":\"INVITE\",\"nickname\":\"NO\"}",
                "{\"code\":\"REGISTER\",\"inviteCode\":\"INVITE\",\"avatarUrl\":\"NO\"}"
        }) {
            var response = mvc.perform(post("/api/v1/auth/wechat/miniprogram/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(json.readTree(response.getContentAsString()).path("code").asText())
                    .isEqualTo("REQUEST_BODY_INVALID");
        }
        assertThat(invoked.get()).isNull();
    }
}

package com.marketshop.interfaces.membership;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketshop.application.identity.MemberProfileUseCase;
import com.marketshop.application.identity.MemberProfileUseCase.UpdateNicknameCommand;
import com.marketshop.application.membership.MembershipUseCase;
import com.marketshop.interfaces.identity.MemberAvatarController;
import com.marketshop.interfaces.security.StpUserKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class MemberProfileRouteContractTest {

    private SaTokenDao previousDao;

    @BeforeEach
    void useIsolatedSessionStore() {
        previousDao = SaManager.getSaTokenDao();
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
    }

    @AfterEach
    void restoreSessionStore() {
        SaTokenContextMockUtil.clearContext();
        SaManager.setSaTokenDao(previousDao);
    }

    @Test
    void exposesSeparateProtectedProfileAndAvatarCompletionRoutes() throws Exception {
        assertThat(MembershipController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/membership");
        var profileMethod = MembershipController.class.getMethod(
                "updateWechatProfile", MembershipController.WechatProfileRequest.class
        );
        var avatarMethod = MembershipController.class.getMethod("uploadAvatar", MultipartFile.class);

        assertThat(profileMethod.getAnnotation(PutMapping.class).value())
                .containsExactly("/wechat-profile");
        assertThat(avatarMethod.getAnnotation(PostMapping.class).value())
                .containsExactly("/avatar");
        assertThat(avatarMethod.getAnnotation(PostMapping.class).consumes())
                .containsExactly(MediaType.MULTIPART_FORM_DATA_VALUE);
        assertThat(componentNames(MembershipController.WechatProfileRequest.class))
                .containsExactly("nickname", "phoneCode")
                .doesNotContain("phone", "phoneNumber", "avatarUrl");
    }

    @Test
    void stableAvatarReadAndProfileResponsesExposeOnlyOwnedPublicMetadata() throws Exception {
        assertThat(MemberAvatarController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/member-avatars");
        assertThat(MemberAvatarController.class.getMethod("avatar", long.class)
                .getAnnotation(GetMapping.class).value()).containsExactly("/{userId}");
        assertThat(componentNames(MemberProfileUseCase.ProfileView.class))
                .containsExactly("userId", "nickname", "avatarUrl", "phoneMasked", "phoneVerifiedAt")
                .doesNotContain("phoneNumber", "objectKey", "sha256");
    }

    @Test
    void invitationApiPreservesTheNativeRegistrationPathProjection() throws Exception {
        var invitationMethod = MembershipController.class.getMethod("currentInvitation");
        assertThat(invitationMethod.getAnnotation(GetMapping.class).value())
                .containsExactly("/invitation");

        MembershipUseCase.InvitationView invitation = new MembershipUseCase.InvitationView(
                "INVITE +/?&",
                "ACTIVE",
                2,
                "/pages/register/register?inviteCode=INVITE%20%2B%2F%3F%26",
                Instant.parse("2027-08-13T00:00:00Z")
        );
        MembershipUseCase membership = (MembershipUseCase) Proxy.newProxyInstance(
                MembershipUseCase.class.getClassLoader(),
                new Class<?>[]{MembershipUseCase.class},
                (proxy, method, arguments) -> {
                    if ("currentInvitation".equals(method.getName())) {
                        assertThat(arguments).containsExactly(42L);
                        return invitation;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
        MemberProfileUseCase memberProfile = (MemberProfileUseCase) Proxy.newProxyInstance(
                MemberProfileUseCase.class.getClassLoader(),
                new Class<?>[]{MemberProfileUseCase.class},
                (proxy, method, arguments) -> {
                    throw new UnsupportedOperationException(method.getName());
                }
        );
        MembershipController controller = new MembershipController(membership, memberProfile);

        SaTokenContextMockUtil.setMockContext(() -> {
            StpUserKit.logic().login(42L);

            var response = controller.currentInvitation();

            assertThat(response.data()).isSameAs(invitation);
            assertThat(response.data().registrationPath())
                    .isEqualTo("/pages/register/register?inviteCode=INVITE%20%2B%2F%3F%26");
        });
    }

    @Test
    void invitationWxacodeRouteReturnsTheAuthenticatedPngCard() throws Exception {
        var method = MembershipController.class.getMethod("invitationWxacode");
        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/invitation/wxacode");

        MembershipUseCase.WxacodeView card = new MembershipUseCase.WxacodeView(
                "image/png",
                "aW52aXRlLXFy"
        );
        MembershipUseCase membership = (MembershipUseCase) Proxy.newProxyInstance(
                MembershipUseCase.class.getClassLoader(),
                new Class<?>[]{MembershipUseCase.class},
                (proxy, invoked, arguments) -> {
                    if ("invitationWxacode".equals(invoked.getName())) {
                        assertThat(arguments).containsExactly(42L);
                        return card;
                    }
                    throw new UnsupportedOperationException(invoked.getName());
                }
        );
        MemberProfileUseCase memberProfile = (MemberProfileUseCase) Proxy.newProxyInstance(
                MemberProfileUseCase.class.getClassLoader(),
                new Class<?>[]{MemberProfileUseCase.class},
                (proxy, invoked, arguments) -> {
                    throw new UnsupportedOperationException(invoked.getName());
                }
        );
        MembershipController controller = new MembershipController(membership, memberProfile);

        SaTokenContextMockUtil.setMockContext(() -> {
            StpUserKit.logic().login(42L);

            var response = controller.invitationWxacode();

            assertThat(response.data()).isSameAs(card);
            assertThat(response.data().contentType()).isEqualTo("image/png");
            assertThat(response.data().imageBase64()).isEqualTo("aW52aXRlLXFy");
        });
    }

    @Test
    void exposesNicknameOnlyRouteAndSynchronizesTheCurrentMemberSession() throws Exception {
        var nicknameMethod = MembershipController.class.getMethod(
                "updateNickname", MembershipController.NicknameRequest.class
        );
        assertThat(nicknameMethod.getAnnotation(PutMapping.class).value())
                .containsExactly("/nickname");
        assertThat(componentNames(MembershipController.NicknameRequest.class))
                .containsExactly("nickname")
                .doesNotContain("phone", "phoneCode", "avatarUrl");

        AtomicReference<UpdateNicknameCommand> captured = new AtomicReference<>();
        AtomicReference<Long> actor = new AtomicReference<>();
        MemberProfileUseCase memberProfile = (MemberProfileUseCase) Proxy.newProxyInstance(
                MemberProfileUseCase.class.getClassLoader(),
                new Class<?>[]{MemberProfileUseCase.class},
                (proxy, method, arguments) -> {
                    if ("updateNickname".equals(method.getName())) {
                        actor.set((Long) arguments[0]);
                        captured.set((UpdateNicknameCommand) arguments[1]);
                        return new MemberProfileUseCase.ProfileView(
                                42,
                                "新昵称",
                                "/api/v1/member-avatars/42",
                                "138****8000",
                                Instant.parse("2026-08-12T01:00:00Z")
                        );
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
        MembershipUseCase membership = (MembershipUseCase) Proxy.newProxyInstance(
                MembershipUseCase.class.getClassLoader(),
                new Class<?>[]{MembershipUseCase.class},
                (proxy, method, arguments) -> {
                    throw new UnsupportedOperationException(method.getName());
                }
        );
        MembershipController controller = new MembershipController(membership, memberProfile);

        SaTokenContextMockUtil.setMockContext(() -> {
            StpUserKit.logic().login(42L);
            StpUserKit.logic().getTokenSession().set("nickname", "旧昵称");

            var response = controller.updateNickname(new MembershipController.NicknameRequest(" 新昵称 "));

            assertThat(response.data().nickname()).isEqualTo("新昵称");
            assertThat(actor.get()).isEqualTo(42L);
            assertThat(captured.get().nickname()).isEqualTo(" 新昵称 ");
            assertThat(StpUserKit.logic().getTokenSession().getString("nickname"))
                    .isEqualTo("新昵称");
        });
    }

    @Test
    void nicknameRequestRejectsUnknownJsonFieldsEvenWhenGlobalBindingIgnoresThem() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        var request = mapper.readValue(
                "{\"nickname\":\"新昵称\"}", MembershipController.NicknameRequest.class
        );
        assertThat(request.nickname()).isEqualTo("新昵称");
        assertThatThrownBy(() -> mapper.readValue(
                "{\"nickname\":\"新昵称\",\"phoneCode\":\"MUST_NOT_BE_ACCEPTED\"}",
                MembershipController.NicknameRequest.class
        )).hasMessageContaining("Unsupported nickname request field: phoneCode");
    }

    @Test
    void nicknameRouteRejectsUnknownEmptyAndMalformedBodiesBeforeTheUseCase() throws Exception {
        AtomicReference<String> invoked = new AtomicReference<>();
        MemberProfileUseCase memberProfile = (MemberProfileUseCase) Proxy.newProxyInstance(
                MemberProfileUseCase.class.getClassLoader(),
                new Class<?>[]{MemberProfileUseCase.class},
                (proxy, method, arguments) -> {
                    invoked.set(method.getName());
                    throw new AssertionError("request body must be rejected before the use case");
                }
        );
        MembershipUseCase membership = (MembershipUseCase) Proxy.newProxyInstance(
                MembershipUseCase.class.getClassLoader(),
                new Class<?>[]{MembershipUseCase.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("membership use case must not be called");
                }
        );
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new MembershipController(membership, memberProfile))
                .build();

        for (String body : new String[]{
                "",
                "{",
                "{\"nickname\":\"新昵称\",\"phoneCode\":\"MUST_NOT_BE_ACCEPTED\"}"
        }) {
            var response = mvc.perform(put("/api/v1/membership/nickname")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn()
                    .getResponse();
            assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(new ObjectMapper().readTree(response.getContentAsString()).path("code").asText())
                    .isEqualTo("REQUEST_BODY_INVALID");
        }
        assertThat(invoked.get()).isNull();
    }

    private static String[] componentNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);
    }
}

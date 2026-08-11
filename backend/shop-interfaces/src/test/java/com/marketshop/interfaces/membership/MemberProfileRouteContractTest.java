package com.marketshop.interfaces.membership;

import com.marketshop.application.identity.MemberProfileUseCase;
import com.marketshop.interfaces.identity.MemberAvatarController;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class MemberProfileRouteContractTest {

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

    private static String[] componentNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);
    }
}

package com.marketshop.interfaces.membership;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.marketshop.application.membership.MembershipUseCase;
import com.marketshop.application.identity.MemberProfileUseCase;
import com.marketshop.application.identity.MemberProfileUseCase.UpdateNicknameCommand;
import com.marketshop.application.identity.MemberProfileUseCase.UpdateWechatProfileCommand;
import com.marketshop.application.identity.MemberProfileUseCase.UploadAvatarCommand;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.interfaces.security.StpUserKit;
import com.marketshop.interfaces.shared.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/membership")
public class MembershipController {

    private final MembershipUseCase membership;
    private final MemberProfileUseCase memberProfile;

    public MembershipController(MembershipUseCase membership, MemberProfileUseCase memberProfile) {
        this.membership = membership;
        this.memberProfile = memberProfile;
    }

    @GetMapping("/me")
    public ApiResponse<MembershipUseCase.ProfileView> profile() {
        return ApiResponse.ok(membership.profile(StpUserKit.logic().getLoginIdAsLong()));
    }

    @PutMapping("/wechat-profile")
    public ApiResponse<MemberProfileUseCase.ProfileView> updateWechatProfile(
            @RequestBody WechatProfileRequest request
    ) {
        var view = memberProfile.updateWechatProfile(
                StpUserKit.logic().getLoginIdAsLong(),
                new UpdateWechatProfileCommand(request.nickname(), request.phoneCode())
        );
        updateSessionProfile(view);
        return ApiResponse.ok(view);
    }

    @PutMapping("/nickname")
    public ApiResponse<MemberProfileUseCase.ProfileView> updateNickname(
            @RequestBody NicknameRequest request
    ) {
        var view = memberProfile.updateNickname(
                StpUserKit.logic().getLoginIdAsLong(),
                new UpdateNicknameCommand(request.nickname())
        );
        updateSessionProfile(view);
        return ApiResponse.ok(view);
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MemberProfileUseCase.ProfileView> uploadAvatar(
            @RequestPart("file") MultipartFile file
    ) {
        var view = memberProfile.uploadAvatar(
                StpUserKit.logic().getLoginIdAsLong(),
                new UploadAvatarCommand(file.getOriginalFilename(), readAvatarBytes(file))
        );
        updateSessionProfile(view);
        return ApiResponse.ok(view);
    }

    @GetMapping("/invitation")
    public ApiResponse<MembershipUseCase.InvitationView> currentInvitation() {
        return ApiResponse.ok(membership.currentInvitation(StpUserKit.logic().getLoginIdAsLong()));
    }

    @GetMapping("/invitation/wxacode")
    public ApiResponse<MembershipUseCase.WxacodeView> invitationWxacode() {
        return ApiResponse.ok(membership.invitationWxacode(StpUserKit.logic().getLoginIdAsLong()));
    }

    @PostMapping("/invitation")
    public ApiResponse<MembershipUseCase.InvitationView> invitation() {
        return ApiResponse.ok(membership.invitation(StpUserKit.logic().getLoginIdAsLong()));
    }

    @PostMapping("/invitation/revoke")
    public ApiResponse<Void> revokeInvitation() {
        membership.revokeInvitation(StpUserKit.logic().getLoginIdAsLong());
        return ApiResponse.ok(null);
    }

    @PostMapping("/invitation/regenerate")
    public ApiResponse<MembershipUseCase.InvitationView> regenerateInvitation(
            @RequestParam(defaultValue = "365") int validityDays
    ) {
        return ApiResponse.ok(membership.regenerateInvitation(
                StpUserKit.logic().getLoginIdAsLong(),
                validityDays
        ));
    }

    @GetMapping("/direct-members")
    public ApiResponse<List<MembershipUseCase.DirectMemberView>> directMembers() {
        return ApiResponse.ok(membership.directMembers(StpUserKit.logic().getLoginIdAsLong()));
    }

    @GetMapping("/ledger")
    public ApiResponse<List<MembershipUseCase.LedgerEntryView>> ledger() {
        return ApiResponse.ok(membership.ledger(StpUserKit.logic().getLoginIdAsLong()));
    }

    private static void updateSessionProfile(MemberProfileUseCase.ProfileView view) {
        var session = StpUserKit.logic().getTokenSession();
        session.set("nickname", view.nickname());
        if (view.avatarUrl() != null) {
            session.set("avatarUrl", view.avatarUrl());
        }
        if (view.phoneMasked() != null) {
            session.set("phoneMasked", view.phoneMasked());
        }
        if (view.phoneVerifiedAt() != null) {
            session.set("phoneVerifiedAt", view.phoneVerifiedAt());
        }
    }

    private static byte[] readAvatarBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new DomainException("AVATAR_UPLOAD_READ_FAILED", "会员头像上传读取失败");
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleUnreadableRequestBody() {
        return ApiResponse.failure("REQUEST_BODY_INVALID", "请求体格式无效");
    }

    public record WechatProfileRequest(String nickname, String phoneCode) {
    }

    public record NicknameRequest(String nickname) {
        @JsonAnySetter
        public void rejectUnknownField(String fieldName, Object ignoredValue) {
            throw new IllegalArgumentException("Unsupported nickname request field: " + fieldName);
        }
    }
}

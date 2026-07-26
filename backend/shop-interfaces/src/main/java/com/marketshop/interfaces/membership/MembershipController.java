package com.marketshop.interfaces.membership;

import com.marketshop.application.membership.MembershipUseCase;
import com.marketshop.interfaces.security.StpUserKit;
import com.marketshop.interfaces.shared.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/membership")
public class MembershipController {

    private final MembershipUseCase membership;

    public MembershipController(MembershipUseCase membership) {
        this.membership = membership;
    }

    @GetMapping("/me")
    public ApiResponse<MembershipUseCase.ProfileView> profile() {
        return ApiResponse.ok(membership.profile(StpUserKit.logic().getLoginIdAsLong()));
    }

    @GetMapping("/invitation")
    public ApiResponse<MembershipUseCase.InvitationView> currentInvitation() {
        return ApiResponse.ok(membership.currentInvitation(StpUserKit.logic().getLoginIdAsLong()));
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
}

package com.marketshop.interfaces.membership;

import com.marketshop.application.membership.MembershipUseCase;
import com.marketshop.interfaces.shared.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rules")
public class PublicRuleController {

    private final MembershipUseCase membership;

    public PublicRuleController(MembershipUseCase membership) {
        this.membership = membership;
    }

    @GetMapping("/active")
    public ApiResponse<List<MembershipUseCase.RuleView>> activeRules() {
        return ApiResponse.ok(membership.activeRules());
    }
}

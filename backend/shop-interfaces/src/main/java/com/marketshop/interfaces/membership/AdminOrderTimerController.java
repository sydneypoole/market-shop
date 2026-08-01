package com.marketshop.interfaces.membership;

import com.marketshop.application.membership.MembershipUseCase;
import com.marketshop.application.membership.MembershipUseCase.PublishRuleCommand;
import com.marketshop.interfaces.security.StpAdminKit;
import com.marketshop.interfaces.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * The system-settings workbench is the sole owner of ORDER_TIMERS.  Keeping
 * this route separate from the generic rule workbench makes the ownership
 * boundary visible to both clients and the server-side use case.
 */
@RestController
@RequestMapping("/api/v1/admin/settings/order-timers")
public class AdminOrderTimerController {

    private final MembershipUseCase membership;

    public AdminOrderTimerController(MembershipUseCase membership) {
        this.membership = membership;
    }

    @GetMapping
    public ApiResponse<MembershipUseCase.RuleView> current() {
        StpAdminKit.requirePermission("rule:publish");
        return ApiResponse.ok(membership.currentOrderTimer());
    }

    @PostMapping("/validate")
    public ApiResponse<MembershipUseCase.RuleValidationView> validate(
            @Valid @RequestBody PublishOrderTimerRequest request
    ) {
        StpAdminKit.requirePermission("rule:publish");
        return ApiResponse.ok(membership.validateOrderTimer(toCommand(request)));
    }

    @PostMapping
    public ApiResponse<MembershipUseCase.RuleView> publish(
            @Valid @RequestBody PublishOrderTimerRequest request
    ) {
        StpAdminKit.requirePermission("rule:publish");
        return ApiResponse.ok(membership.publishOrderTimer(
                StpAdminKit.logic().getLoginIdAsLong(),
                toCommand(request)
        ));
    }

    private static PublishRuleCommand toCommand(PublishOrderTimerRequest request) {
        return new PublishRuleCommand(
                request.ruleCode(),
                request.ruleType(),
                request.parametersJson(),
                request.effectiveFrom()
        );
    }

    public record PublishOrderTimerRequest(
            @NotBlank String ruleCode,
            @NotBlank String ruleType,
            @NotBlank String parametersJson,
            Instant effectiveFrom
    ) {
    }
}

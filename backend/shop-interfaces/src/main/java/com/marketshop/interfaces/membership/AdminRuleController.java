package com.marketshop.interfaces.membership;

import com.marketshop.application.membership.MembershipUseCase;
import com.marketshop.application.membership.MembershipUseCase.PublishRuleCommand;
import com.marketshop.interfaces.security.StpAdminKit;
import com.marketshop.interfaces.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/rules")
public class AdminRuleController {

    private final MembershipUseCase membership;

    public AdminRuleController(MembershipUseCase membership) {
        this.membership = membership;
    }

    @GetMapping
    public ApiResponse<List<MembershipUseCase.RuleView>> rules() {
        // Rule payloads are configuration data; loading them is intentionally
        // protected by the same permission as validation/publication.  Timer
        // versions are exposed through the dedicated settings route, while
        // this endpoint remains for the other rule workbench views.
        StpAdminKit.requirePermission("rule:publish");
        return ApiResponse.ok(membership.rules());
    }

    @PostMapping
    public ApiResponse<MembershipUseCase.RuleView> publish(@Valid @RequestBody PublishRuleRequest request) {
        StpAdminKit.requirePermission("rule:publish");
        return ApiResponse.ok(membership.publishRule(
                StpAdminKit.logic().getLoginIdAsLong(),
                new PublishRuleCommand(
                        request.ruleCode(),
                        request.ruleType(),
                        request.parametersJson(),
                        request.effectiveFrom()
                )
        ));
    }

    @PostMapping("/validate")
    public ApiResponse<MembershipUseCase.RuleValidationView> validate(
            @Valid @RequestBody PublishRuleRequest request
    ) {
        StpAdminKit.requirePermission("rule:publish");
        return ApiResponse.ok(membership.validateRule(new PublishRuleCommand(
                request.ruleCode(),
                request.ruleType(),
                request.parametersJson(),
                request.effectiveFrom()
        )));
    }

    @DeleteMapping("/{ruleId}")
    public ApiResponse<Void> cancel(@PathVariable long ruleId, @RequestBody CancelRuleRequest request) {
        StpAdminKit.requirePermission("rule:publish");
        membership.cancelRule(StpAdminKit.logic().getLoginIdAsLong(), ruleId, request.reason());
        return ApiResponse.ok(null);
    }

    public record PublishRuleRequest(@NotBlank String ruleCode, @NotBlank String ruleType,
                                     @NotBlank String parametersJson, Instant effectiveFrom) {
    }

    public record CancelRuleRequest(@NotBlank String reason) {
    }
}

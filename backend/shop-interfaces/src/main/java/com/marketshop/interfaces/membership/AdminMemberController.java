package com.marketshop.interfaces.membership;

import com.marketshop.application.membership.MemberAdminUseCase;
import com.marketshop.application.membership.MemberAdminUseCase.MemberQuery;
import com.marketshop.application.membership.MemberAdminUseCase.LevelCommand;
import com.marketshop.application.membership.MemberAdminUseCase.RecomputeCommand;
import com.marketshop.application.membership.MemberAdminUseCase.StatusCommand;
import com.marketshop.interfaces.security.StpAdminKit;
import com.marketshop.interfaces.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/members")
public class AdminMemberController {

    private final MemberAdminUseCase members;

    public AdminMemberController(MemberAdminUseCase members) {
        this.members = members;
    }

    @GetMapping
    public ApiResponse<MemberAdminUseCase.MemberPage> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String levelCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        StpAdminKit.requirePermission("member:read");
        return ApiResponse.ok(members.search(new MemberQuery(keyword, levelCode, status, page, size)));
    }

    @GetMapping("/{userId}")
    public ApiResponse<MemberAdminUseCase.MemberDetail> detail(@PathVariable long userId) {
        StpAdminKit.requirePermission("member:read");
        return ApiResponse.ok(members.detail(userId));
    }

    @PutMapping("/{userId}/status")
    public ApiResponse<Void> status(
            @PathVariable long userId,
            @Valid @RequestBody StatusRequest request
    ) {
        StpAdminKit.requirePermission("member:write");
        members.updateStatus(
                StpAdminKit.logic().getLoginIdAsLong(),
                userId,
                new StatusCommand(request.status(), request.reason(), request.requestId())
        );
        return ApiResponse.ok(null);
    }

    @PutMapping("/{userId}/level")
    public ApiResponse<Void> level(
            @PathVariable long userId,
            @Valid @RequestBody LevelRequest request
    ) {
        StpAdminKit.requirePermission("member:write");
        members.updateLevel(
                StpAdminKit.logic().getLoginIdAsLong(),
                userId,
                new LevelCommand(request.levelCode(), request.reason(), request.requestId())
        );
        return ApiResponse.ok(null);
    }

    @PostMapping("/{userId}/recompute")
    public ApiResponse<Void> recompute(
            @PathVariable long userId,
            @Valid @RequestBody RecomputeRequest request
    ) {
        StpAdminKit.requirePermission("member:write");
        members.recompute(
                StpAdminKit.logic().getLoginIdAsLong(),
                userId,
                new RecomputeCommand(request.reason(), request.requestId())
        );
        return ApiResponse.ok(null);
    }

    public record StatusRequest(@NotBlank String status, @NotBlank String reason,
                                @NotBlank String requestId) {
    }

    public record LevelRequest(@NotBlank String levelCode, @NotBlank String reason,
                               @NotBlank String requestId) {
    }

    public record RecomputeRequest(@NotBlank String reason, @NotBlank String requestId) {
    }
}

package com.marketshop.interfaces.reliability;

import com.marketshop.application.reliability.OutboxOperationsUseCase;
import com.marketshop.interfaces.security.StpAdminKit;
import com.marketshop.interfaces.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/outbox")
public class AdminOutboxController {

    private final OutboxOperationsUseCase outbox;

    public AdminOutboxController(OutboxOperationsUseCase outbox) {
        this.outbox = outbox;
    }

    @GetMapping("/dead-letters")
    public ApiResponse<OutboxOperationsUseCase.DeadLetterPage> deadLetters(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        StpAdminKit.requirePermission("outbox:read");
        return ApiResponse.ok(outbox.deadLetters(page, pageSize));
    }

    @GetMapping("/summary")
    public ApiResponse<OutboxOperationsUseCase.OutboxSummaryView> summary() {
        StpAdminKit.requirePermission("outbox:read");
        return ApiResponse.ok(outbox.summary());
    }

    @PostMapping("/dead-letters/{outboxId}/replay")
    public ApiResponse<OutboxOperationsUseCase.ReplayResult> replay(
            @PathVariable long outboxId,
            @Valid @RequestBody ReplayRequest request
    ) {
        StpAdminKit.requirePermission("outbox:replay");
        return ApiResponse.ok(outbox.replay(
                StpAdminKit.logic().getLoginIdAsLong(),
                outboxId,
                request.reason()
        ));
    }

    public record ReplayRequest(@NotBlank String reason) {
    }
}

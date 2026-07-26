package com.marketshop.interfaces.operation;

import com.marketshop.application.operation.OperationSettingsUseCase;
import com.marketshop.application.operation.OperationSettingsUseCase.SaveSettingsCommand;
import com.marketshop.interfaces.security.StpAdminKit;
import com.marketshop.interfaces.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/settings")
public class AdminOperationSettingsController {

    private final OperationSettingsUseCase settings;

    public AdminOperationSettingsController(OperationSettingsUseCase settings) {
        this.settings = settings;
    }

    @GetMapping
    public ApiResponse<OperationSettingsUseCase.SettingsView> settings() {
        StpAdminKit.requireAnyPermission("system:setting:manage", "aftersale:review");
        return ApiResponse.ok(settings.settings());
    }

    @PutMapping
    public ApiResponse<OperationSettingsUseCase.SettingsView> save(@Valid @RequestBody SaveRequest request) {
        StpAdminKit.requirePermission("system:setting:manage");
        return ApiResponse.ok(settings.save(
                StpAdminKit.logic().getLoginIdAsLong(),
                new SaveSettingsCommand(
                        request.afterSaleReturnReceiver(),
                        request.afterSaleReturnPhone(),
                        request.afterSaleReturnAddress(),
                        request.lowInventoryThreshold(),
                        request.reason()
                )
        ));
    }

    public record SaveRequest(
            @NotBlank String afterSaleReturnReceiver,
            @NotBlank String afterSaleReturnPhone,
            @NotBlank String afterSaleReturnAddress,
            @Min(0) @Max(100_000) int lowInventoryThreshold,
            @NotBlank String reason
    ) {
    }
}

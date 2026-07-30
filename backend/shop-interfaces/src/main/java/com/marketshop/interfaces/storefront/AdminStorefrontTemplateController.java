package com.marketshop.interfaces.storefront;

import com.marketshop.application.storefront.StorefrontTemplateUseCase;
import com.marketshop.application.storefront.StorefrontTemplateUseCase.CreateTemplateCommand;
import com.marketshop.application.storefront.StorefrontTemplateUseCase.UpdateTemplateCommand;
import com.marketshop.interfaces.security.StpAdminKit;
import com.marketshop.interfaces.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/storefront/templates")
public class AdminStorefrontTemplateController {

    private final StorefrontTemplateUseCase templates;

    public AdminStorefrontTemplateController(StorefrontTemplateUseCase templates) {
        this.templates = templates;
    }

    @GetMapping
    public ApiResponse<List<StorefrontTemplateUseCase.TemplateView>> templates() {
        requirePermission();
        return ApiResponse.ok(templates.templates());
    }

    @PostMapping
    public ApiResponse<StorefrontTemplateUseCase.TemplateView> create(
            @Valid @RequestBody CreateRequest request
    ) {
        requirePermission();
        return ApiResponse.ok(templates.create(adminId(), new CreateTemplateCommand(
                request.name(), request.presetType()
        )));
    }

    @PutMapping("/{templateId}")
    public ApiResponse<StorefrontTemplateUseCase.TemplateView> update(
            @PathVariable long templateId,
            @Valid @RequestBody UpdateRequest request
    ) {
        requirePermission();
        return ApiResponse.ok(templates.update(adminId(), templateId, new UpdateTemplateCommand(
                request.name(), request.designTokensJson(), request.layoutJson(), request.expectedVersion()
        )));
    }

    @PostMapping("/{templateId}/duplicate")
    public ApiResponse<StorefrontTemplateUseCase.TemplateView> duplicate(
            @PathVariable long templateId,
            @Valid @RequestBody DuplicateRequest request
    ) {
        requirePermission();
        return ApiResponse.ok(templates.duplicate(adminId(), templateId, request.name()));
    }

    @PostMapping("/{templateId}/publish")
    public ApiResponse<StorefrontTemplateUseCase.TemplateView> publish(
            @PathVariable long templateId,
            @Valid @RequestBody VersionRequest request
    ) {
        requirePermission();
        return ApiResponse.ok(templates.publish(adminId(), templateId, request.expectedVersion()));
    }

    @DeleteMapping("/{templateId}")
    public ApiResponse<Void> archive(
            @PathVariable long templateId,
            @Valid @RequestBody VersionRequest request
    ) {
        requirePermission();
        templates.archive(adminId(), templateId, request.expectedVersion());
        return ApiResponse.ok(null);
    }

    private static void requirePermission() {
        StpAdminKit.requirePermission("storefront:template:manage");
    }

    private static long adminId() {
        return StpAdminKit.logic().getLoginIdAsLong();
    }

    public record CreateRequest(@NotBlank String name, @NotBlank String presetType) {
    }

    public record UpdateRequest(
            @NotBlank String name,
            @NotBlank String designTokensJson,
            @NotBlank String layoutJson,
            @Min(0) int expectedVersion
    ) {
    }

    public record DuplicateRequest(@NotBlank String name) {
    }

    public record VersionRequest(@Min(0) int expectedVersion) {
    }
}

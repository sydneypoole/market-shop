package com.marketshop.interfaces.storefront;

import com.marketshop.application.storefront.StorefrontTemplateUseCase;
import com.marketshop.interfaces.shared.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/storefront")
public class StorefrontTemplateController {

    private final StorefrontTemplateUseCase templates;

    public StorefrontTemplateController(StorefrontTemplateUseCase templates) {
        this.templates = templates;
    }

    @GetMapping("/template")
    public ApiResponse<StorefrontTemplateUseCase.TemplateView> active() {
        return ApiResponse.ok(templates.active());
    }
}

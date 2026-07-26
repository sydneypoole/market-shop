package com.marketshop.interfaces.commerce;

import com.marketshop.application.commerce.OrderOperationsUseCase;
import com.marketshop.interfaces.security.StpAdminKit;
import com.marketshop.interfaces.shared.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class AdminDashboardController {

    private final OrderOperationsUseCase operations;

    public AdminDashboardController(OrderOperationsUseCase operations) {
        this.operations = operations;
    }

    @GetMapping
    public ApiResponse<OrderOperationsUseCase.DashboardView> dashboard() {
        StpAdminKit.requirePermission("order:read");
        return ApiResponse.ok(operations.dashboard());
    }
}

package com.marketshop.interfaces.aftersale;

import com.marketshop.application.aftersale.AfterSaleUseCase;
import com.marketshop.interfaces.security.StpAdminKit;
import com.marketshop.interfaces.shared.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/after-sales")
public class AdminAfterSaleController {

    private final AfterSaleUseCase afterSales;

    public AdminAfterSaleController(AfterSaleUseCase afterSales) {
        this.afterSales = afterSales;
    }

    @GetMapping
    public ApiResponse<List<AfterSaleUseCase.View>> list(@RequestParam(required = false) String status) {
        StpAdminKit.requirePermission("aftersale:review");
        return ApiResponse.ok(afterSales.adminAfterSales(status));
    }

    @PostMapping("/{afterSaleId}/review")
    public ApiResponse<Void> review(@PathVariable long afterSaleId, @RequestBody ReviewRequest request) {
        StpAdminKit.requirePermission("aftersale:review");
        afterSales.adminDecision(
                StpAdminKit.logic().getLoginIdAsLong(),
                afterSaleId,
                request.approve(),
                request.reason(),
                request.returnAddressJson()
        );
        return ApiResponse.ok(null);
    }

    @PostMapping("/{afterSaleId}/confirm-return-received")
    public ApiResponse<Void> confirmReturnReceived(
            @PathVariable long afterSaleId,
            @RequestBody ConfirmRefundRequest request
    ) {
        StpAdminKit.requirePermission("aftersale:review");
        afterSales.adminConfirmReturnReceived(
                StpAdminKit.logic().getLoginIdAsLong(),
                afterSaleId,
                request.reason()
        );
        return ApiResponse.ok(null);
    }

    public record ReviewRequest(boolean approve, String reason, String returnAddressJson) {
    }

    public record ConfirmRefundRequest(String reason) {
    }
}

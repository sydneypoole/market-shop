package com.marketshop.interfaces.aftersale;

import com.marketshop.application.aftersale.AfterSaleUseCase;
import com.marketshop.application.aftersale.AfterSaleUseCase.ApplyCommand;
import com.marketshop.interfaces.security.StpUserKit;
import com.marketshop.interfaces.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/after-sales")
public class AfterSaleController {

    private final AfterSaleUseCase afterSales;

    public AfterSaleController(AfterSaleUseCase afterSales) {
        this.afterSales = afterSales;
    }

    @PostMapping
    public ApiResponse<AfterSaleUseCase.View> apply(@Valid @RequestBody ApplyRequest request) {
        return ApiResponse.ok(afterSales.apply(
                StpUserKit.logic().getLoginIdAsLong(),
                new ApplyCommand(
                        request.orderId(),
                        request.clientRequestId(),
                        request.type(),
                        request.reason(),
                        request.description()
                )
        ));
    }

    @GetMapping
    public ApiResponse<List<AfterSaleUseCase.View>> list() {
        return ApiResponse.ok(afterSales.userAfterSales(StpUserKit.logic().getLoginIdAsLong()));
    }

    @GetMapping("/superior")
    public ApiResponse<List<AfterSaleUseCase.View>> superiorList() {
        return ApiResponse.ok(afterSales.superiorAfterSales(StpUserKit.logic().getLoginIdAsLong()));
    }

    @GetMapping("/{afterSaleId}")
    public ApiResponse<AfterSaleUseCase.View> detail(@PathVariable long afterSaleId) {
        return ApiResponse.ok(afterSales.afterSale(
                StpUserKit.logic().getLoginIdAsLong(),
                afterSaleId
        ));
    }

    @PostMapping("/{afterSaleId}/return-shipment")
    public ApiResponse<Void> returnShipment(
            @PathVariable long afterSaleId,
            @Valid @RequestBody ReturnShipmentRequest request
    ) {
        afterSales.submitReturn(
                StpUserKit.logic().getLoginIdAsLong(),
                afterSaleId,
                request.carrier(),
                request.trackingNo()
        );
        return ApiResponse.ok(null);
    }

    @PostMapping("/{afterSaleId}/confirm-refund")
    public ApiResponse<Void> confirmRefund(@PathVariable long afterSaleId) {
        afterSales.userConfirmRefund(StpUserKit.logic().getLoginIdAsLong(), afterSaleId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{afterSaleId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable long afterSaleId, @Valid @RequestBody CancelRequest request) {
        afterSales.userCancel(StpUserKit.logic().getLoginIdAsLong(), afterSaleId, request.reason());
        return ApiResponse.ok(null);
    }

    @PostMapping("/superior/{afterSaleId}/confirm-offline-refund")
    public ApiResponse<Void> superiorConfirmOfflineRefund(
            @PathVariable long afterSaleId,
            @Valid @RequestBody ConfirmRefundRequest request
    ) {
        afterSales.superiorConfirmOfflineRefund(
                StpUserKit.logic().getLoginIdAsLong(),
                afterSaleId,
                request.reason()
        );
        return ApiResponse.ok(null);
    }

    public record ApplyRequest(@Min(1) long orderId, @NotBlank String clientRequestId,
                               @NotBlank String type, @NotBlank String reason, String description) {
    }

    public record ReturnShipmentRequest(@NotBlank String carrier, @NotBlank String trackingNo) {
    }

    public record CancelRequest(@NotBlank String reason) {
    }

    public record ConfirmRefundRequest(String reason) {
    }
}

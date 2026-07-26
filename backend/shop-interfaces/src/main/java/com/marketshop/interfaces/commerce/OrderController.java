package com.marketshop.interfaces.commerce;

import com.marketshop.application.commerce.CommerceUseCase;
import com.marketshop.application.commerce.CommerceUseCase.AddressSnapshot;
import com.marketshop.application.commerce.CommerceUseCase.OrderItemCommand;
import com.marketshop.application.commerce.CommerceUseCase.OrderView;
import com.marketshop.application.commerce.CommerceUseCase.SubmitOrderCommand;
import com.marketshop.interfaces.security.StpUserKit;
import com.marketshop.interfaces.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class OrderController {

    private final CommerceUseCase commerce;

    public OrderController(CommerceUseCase commerce) {
        this.commerce = commerce;
    }

    @PostMapping("/orders")
    public ApiResponse<OrderView> submit(@Valid @RequestBody SubmitOrderRequest request) {
        long userId = StpUserKit.logic().getLoginIdAsLong();
        return ApiResponse.ok(commerce.submit(userId, new SubmitOrderCommand(
                request.clientRequestId(),
                request.source(),
                new AddressSnapshot(
                        request.address().recipientName(),
                        request.address().phone(),
                        request.address().province(),
                        request.address().city(),
                        request.address().district(),
                        request.address().detailAddress(),
                        request.address().postalCode()
                ),
                request.items().stream().map(item -> new OrderItemCommand(item.skuId(), item.quantity())).toList()
        )));
    }

    @GetMapping("/orders")
    public ApiResponse<List<OrderView>> buyerOrders() {
        return ApiResponse.ok(commerce.buyerOrders(StpUserKit.logic().getLoginIdAsLong()));
    }

    @GetMapping("/orders/{orderId}")
    public ApiResponse<CommerceUseCase.OrderDetail> order(@PathVariable long orderId) {
        return ApiResponse.ok(commerce.order(StpUserKit.logic().getLoginIdAsLong(), orderId));
    }

    @PostMapping("/orders/{orderId}/receive")
    public ApiResponse<Void> receive(@PathVariable long orderId) {
        commerce.receive(StpUserKit.logic().getLoginIdAsLong(), orderId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/orders/{orderId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable long orderId, @Valid @RequestBody CancelRequest request) {
        commerce.cancel(StpUserKit.logic().getLoginIdAsLong(), orderId, request.reason());
        return ApiResponse.ok(null);
    }

    @GetMapping("/superior/orders")
    public ApiResponse<List<OrderView>> superiorOrders() {
        return ApiResponse.ok(commerce.superiorOrders(StpUserKit.logic().getLoginIdAsLong()));
    }

    @PostMapping("/superior/orders/{orderId}/decision")
    public ApiResponse<Void> superiorDecision(
            @PathVariable long orderId,
            @Valid @RequestBody DecisionRequest request
    ) {
        commerce.superiorDecision(
                StpUserKit.logic().getLoginIdAsLong(),
                orderId,
                request.approve(),
                request.reason()
        );
        return ApiResponse.ok(null);
    }

    public record SubmitOrderRequest(
            @NotBlank String clientRequestId,
            String source,
            @Valid AddressRequest address,
            @NotEmpty List<@Valid ItemRequest> items
    ) {
    }

    public record AddressRequest(
            @NotBlank String recipientName,
            @NotBlank String phone,
            @NotBlank String province,
            @NotBlank String city,
            @NotBlank String district,
            @NotBlank String detailAddress,
            String postalCode
    ) {
    }

    public record ItemRequest(@Min(1) long skuId, @Min(1) @Max(99) int quantity) {
    }

    public record DecisionRequest(boolean approve, String reason) {
    }

    public record CancelRequest(@NotBlank String reason) {
    }
}

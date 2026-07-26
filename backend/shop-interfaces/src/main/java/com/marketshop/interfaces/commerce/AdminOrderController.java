package com.marketshop.interfaces.commerce;

import com.marketshop.application.commerce.CommerceUseCase;
import com.marketshop.application.commerce.CommerceUseCase.ShipmentCommand;
import com.marketshop.application.commerce.OrderOperationsUseCase;
import com.marketshop.application.commerce.OrderOperationsUseCase.BatchShipment;
import com.marketshop.application.commerce.OrderOperationsUseCase.OrderSearchQuery;
import com.marketshop.interfaces.security.StpAdminKit;
import com.marketshop.interfaces.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/orders")
public class AdminOrderController {

    private final CommerceUseCase commerce;
    private final OrderOperationsUseCase operations;

    public AdminOrderController(CommerceUseCase commerce, OrderOperationsUseCase operations) {
        this.commerce = commerce;
        this.operations = operations;
    }

    @GetMapping
    public ApiResponse<List<CommerceUseCase.OrderView>> orders(
            @RequestParam(required = false) String status
    ) {
        StpAdminKit.requirePermission("order:read");
        return ApiResponse.ok(commerce.adminOrders(status));
    }

    @GetMapping("/search")
    public ApiResponse<OrderOperationsUseCase.OrderPage> search(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long buyerUserId,
            @RequestParam(required = false) Long superiorUserId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        StpAdminKit.requirePermission("order:read");
        return ApiResponse.ok(operations.search(
                new OrderSearchQuery(orderNo, buyerUserId, superiorUserId, status, from, to, page, size)
        ));
    }

    @GetMapping(value = "/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long buyerUserId,
            @RequestParam(required = false) Long superiorUserId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to
    ) {
        StpAdminKit.requirePermission("order:read");
        byte[] bytes = operations.exportCsv(
                new OrderSearchQuery(orderNo, buyerUserId, superiorUserId, status, from, to, 1, 10_000)
        ).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=orders.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(bytes);
    }

    @GetMapping("/{orderId}")
    public ApiResponse<CommerceUseCase.OrderDetail> order(@PathVariable long orderId) {
        StpAdminKit.requirePermission("order:read");
        return ApiResponse.ok(commerce.adminOrder(orderId));
    }

    @PostMapping("/{orderId}/review")
    public ApiResponse<Void> review(@PathVariable long orderId, @RequestBody ReviewRequest request) {
        StpAdminKit.requirePermission("order:review");
        commerce.adminDecision(
                StpAdminKit.logic().getLoginIdAsLong(),
                orderId,
                request.approve(),
                request.reason()
        );
        return ApiResponse.ok(null);
    }

    @PostMapping("/{orderId}/ship")
    public ApiResponse<Void> ship(@PathVariable long orderId, @Valid @RequestBody ShipRequest request) {
        StpAdminKit.requirePermission("order:ship");
        commerce.ship(
                StpAdminKit.logic().getLoginIdAsLong(),
                orderId,
                new ShipmentCommand(request.carrierCode(), request.carrierName(), request.trackingNo())
        );
        return ApiResponse.ok(null);
    }

    @PostMapping("/batch-ship")
    public ApiResponse<List<OrderOperationsUseCase.BatchResult>> batchShip(
            @Valid @RequestBody BatchShipRequest request
    ) {
        StpAdminKit.requirePermission("order:ship");
        return ApiResponse.ok(operations.batchShip(
                StpAdminKit.logic().getLoginIdAsLong(),
                request.items().stream().map(item -> new BatchShipment(
                        item.orderId(),
                        new ShipmentCommand(item.carrierCode(), item.carrierName(), item.trackingNo())
                )).toList()
        ));
    }

    @GetMapping("/{orderId}/notes")
    public ApiResponse<List<OrderOperationsUseCase.OrderNoteView>> notes(@PathVariable long orderId) {
        StpAdminKit.requirePermission("order:read");
        return ApiResponse.ok(operations.notes(orderId));
    }

    @PostMapping("/{orderId}/notes")
    public ApiResponse<Void> addNote(@PathVariable long orderId, @Valid @RequestBody NoteRequest request) {
        StpAdminKit.requirePermission("order:read");
        operations.addNote(StpAdminKit.logic().getLoginIdAsLong(), orderId, request.note());
        return ApiResponse.ok(null);
    }

    public record ReviewRequest(boolean approve, String reason) {
    }

    public record ShipRequest(@NotBlank String carrierCode, @NotBlank String carrierName,
                              @NotBlank String trackingNo) {
    }

    public record BatchShipRequest(
            @NotEmpty(message = "批量发货列表不能为空")
            @Size(max = 100, message = "单次批量发货不能超过 100 条")
            List<@Valid BatchShipItem> items
    ) {
    }

    public record BatchShipItem(long orderId, @NotBlank String carrierCode,
                                @NotBlank String carrierName, @NotBlank String trackingNo) {
    }

    public record NoteRequest(@NotBlank String note) {
    }
}

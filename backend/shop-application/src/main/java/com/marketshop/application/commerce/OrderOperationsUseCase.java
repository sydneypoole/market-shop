package com.marketshop.application.commerce;

import com.marketshop.application.commerce.CommerceUseCase.OrderView;
import com.marketshop.application.commerce.CommerceUseCase.ShipmentCommand;

import java.time.Instant;
import java.util.List;

public interface OrderOperationsUseCase {

    OrderPage search(OrderSearchQuery query);

    String exportCsv(OrderSearchQuery query);

    List<OrderNoteView> notes(long orderId);

    void addNote(long adminId, long orderId, String note);

    List<BatchResult> batchShip(long adminId, List<BatchShipment> shipments);

    DashboardView dashboard();

    record OrderSearchQuery(String orderNo, Long buyerUserId, Long superiorUserId, String status,
                            Instant from, Instant to, int page, int size) {
    }

    record OrderPage(List<OrderView> items, long total, int page, int size) {
    }

    record OrderNoteView(long id, long adminId, String note, Instant createdAt) {
    }

    record BatchShipment(long orderId, ShipmentCommand shipment) {
    }

    record BatchResult(long orderId, boolean success, String message) {
    }

    record DashboardView(long memberCount, long todayOrderCount, long todayCompletedAmountFen,
                         long pendingSuperiorCount, long pendingReviewCount, long pendingShipCount,
                         long activeAfterSaleCount, long onSaleProductCount, long lowInventorySkuCount) {
    }
}

package com.marketshop.application.aftersale;

import java.time.Instant;
import java.util.List;

public interface AfterSaleUseCase {

    View apply(long userId, ApplyCommand command);

    List<View> userAfterSales(long userId);

    List<View> superiorAfterSales(long superiorUserId);

    View afterSale(long userId, long afterSaleId);

    List<View> adminAfterSales(String status);

    void adminDecision(long adminId, long afterSaleId, boolean approve, String reason, String returnAddressJson);

    void submitReturn(long userId, long afterSaleId, String carrier, String trackingNo);

    void adminConfirmReturnReceived(long adminId, long afterSaleId, String reason);

    void superiorConfirmOfflineRefund(long superiorUserId, long afterSaleId, String reason);

    void userConfirmRefund(long userId, long afterSaleId);

    void userCancel(long userId, long afterSaleId, String reason);

    record ApplyCommand(long orderId, String clientRequestId, String type, String reason, String description) {
    }

    record View(long id, String afterSaleNo, long orderId, long applicantUserId, long superiorUserId,
                String type, String status, String reason, String adminReason, String returnAddressJson,
                String returnCarrier, String returnTrackingNo,
                Instant createdAt, Instant completedAt) {
    }
}

package com.marketshop.application.aftersale;

import com.marketshop.application.aftersale.AfterSaleUseCase.ApplyCommand;
import com.marketshop.application.aftersale.AfterSaleUseCase.View;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AfterSalePort {

    Optional<OrderEligibility> orderEligibility(long orderId);

    Optional<View> findByClientRequest(long userId, String clientRequestId);

    View create(long userId, String afterSaleNo, ApplyCommand command);

    List<View> userAfterSales(long userId);

    List<View> superiorAfterSales(long superiorUserId);

    List<View> adminAfterSales(String status);

    View load(long afterSaleId);

    void transition(long afterSaleId, String expectedStatus, String targetStatus, TransitionData data);

    int afterSaleWindowDays();

    record OrderEligibility(long orderId, long buyerUserId, String status, Instant completedAt,
                            int activeAfterSaleCount) {
    }

    record TransitionData(String adminReason, String returnAddressJson, String returnCarrier,
                          String returnTrackingNo, Long refundConfirmedByUserId, Instant refundConfirmedAt,
                          Instant completedAt, boolean emitCompletedEvent) {
    }
}

package com.marketshop.infrastructure.reliability;

import com.marketshop.application.aftersale.AfterSalePort;
import com.marketshop.application.aftersale.AfterSalePort.TransitionData;
import com.marketshop.infrastructure.persistence.mapper.AfterSaleMapper;
import com.marketshop.infrastructure.persistence.model.AfterSalePersistenceModels.AfterSaleRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class AftersaleTimeoutProcessor {

    private final AfterSaleMapper mapper;
    private final AfterSalePort port;
    private final int awaitingReturnDays;
    private final int returnShippedDays;
    private final int offlineRefundDays;
    private final int buyerConfirmDays;

    public AftersaleTimeoutProcessor(
            AfterSaleMapper mapper,
            AfterSalePort port,
            @Value("${market-shop.jobs.aftersale-awaiting-return-timeout-days:15}") int awaitingReturnDays,
            @Value("${market-shop.jobs.aftersale-return-shipped-timeout-days:15}") int returnShippedDays,
            @Value("${market-shop.jobs.aftersale-offline-refund-timeout-days:7}") int offlineRefundDays,
            @Value("${market-shop.jobs.aftersale-buyer-confirm-timeout-days:7}") int buyerConfirmDays
    ) {
        this.mapper = mapper;
        this.port = port;
        this.awaitingReturnDays = awaitingReturnDays;
        this.returnShippedDays = returnShippedDays;
        this.offlineRefundDays = offlineRefundDays;
        this.buyerConfirmDays = buyerConfirmDays;
    }

    @Transactional
    public boolean processNext() {
        AfterSaleRow row = mapper.lockDueAftersaleTimeout(
                awaitingReturnDays, returnShippedDays, offlineRefundDays, buyerConfirmDays);
        if (row == null) {
            return false;
        }
        TransitionData data = transitionFor(row.status);
        port.transition(row.id, row.status, targetStatus(row.status), data);
        return true;
    }

    private static String targetStatus(String status) {
        return switch (status) {
            case "AWAITING_RETURN" -> "CANCELLED";
            case "RETURN_SHIPPED" -> "PENDING_OFFLINE_REFUND";
            case "PENDING_OFFLINE_REFUND" -> "PENDING_BUYER_REFUND_CONFIRMATION";
            case "PENDING_BUYER_REFUND_CONFIRMATION" -> "COMPLETED";
            default -> throw new IllegalStateException("Unexpected aftersale status for timeout: " + status);
        };
    }

    private static TransitionData transitionFor(String status) {
        Instant now = Instant.now();
        return switch (status) {
            case "AWAITING_RETURN" -> new TransitionData(
                    "超时未寄回商品，系统自动取消", null, null, null, null, null, null, false);
            case "RETURN_SHIPPED" -> new TransitionData(
                    "超时未确认收货，系统自动推进", null, null, null, null, null, null, false);
            case "PENDING_OFFLINE_REFUND" -> new TransitionData(
                    "超时未确认线下退款，系统自动确认", null, null, null, null, now, null, false);
            case "PENDING_BUYER_REFUND_CONFIRMATION" -> new TransitionData(
                    "超时未确认收款，系统自动完成", null, null, null, null, null, now, true);
            default -> throw new IllegalStateException("Unexpected aftersale status for timeout: " + status);
        };
    }
}

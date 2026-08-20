package com.marketshop.infrastructure.reliability;

import com.marketshop.application.aftersale.AfterSalePort;
import com.marketshop.application.aftersale.AfterSalePort.TransitionData;
import com.marketshop.application.membership.OrderTimerParameters;
import com.marketshop.application.membership.RuleRuntimeResolver;
import com.marketshop.infrastructure.persistence.mapper.AfterSaleMapper;
import com.marketshop.infrastructure.persistence.model.AfterSalePersistenceModels.AfterSaleRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class AftersaleTimeoutProcessor {

    private final AfterSaleMapper mapper;
    private final AfterSalePort port;

    public AftersaleTimeoutProcessor(AfterSaleMapper mapper, AfterSalePort port) {
        this.mapper = mapper;
        this.port = port;
    }

    @Transactional
    public boolean processNext() {
        AfterSaleRow row = mapper.lockDueAftersaleTimeout();
        if (row == null) {
            return false;
        }
        timer(row);
        Instant now = Instant.now();
        port.transition(row.id, row.status, targetStatus(row.status), transitionFor(row.status, now));
        return true;
    }

    private static OrderTimerParameters timer(AfterSaleRow row) {
        if (row == null || row.timerRuleCode == null || row.timerRuleType == null
                || row.timerParametersJson == null) {
            throw RuleRuntimeResolver.invalidOrderTimer();
        }
        return RuleRuntimeResolver.orderTimer(
                row.timerRuleCode, row.timerRuleType, row.timerParametersJson
        );
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

    private static TransitionData transitionFor(String status, Instant now) {
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

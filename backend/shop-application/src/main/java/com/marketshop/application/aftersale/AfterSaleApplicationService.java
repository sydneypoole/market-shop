package com.marketshop.application.aftersale;

import com.marketshop.application.aftersale.AfterSalePort.OrderEligibility;
import com.marketshop.application.aftersale.AfterSalePort.TransitionData;
import com.marketshop.application.membership.OrderTimerParameters;
import com.marketshop.domain.shared.DomainException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class AfterSaleApplicationService implements AfterSaleUseCase {

    private static final Set<String> TYPES = Set.of("RETURN_REFUND", "REFUND_ONLY");

    private final AfterSalePort port;

    public AfterSaleApplicationService(AfterSalePort port) {
        this.port = port;
    }

    @Override
    public View apply(long userId, ApplyCommand command) {
        String clientRequestId = requireText(
                command.clientRequestId(),
                "CLIENT_REQUEST_REQUIRED",
                "客户端请求号不能为空"
        );
        if (clientRequestId.length() > 80) {
            throw new DomainException("CLIENT_REQUEST_INVALID", "客户端请求号过长");
        }
        requireText(command.reason(), "AFTERSALE_REASON_REQUIRED", "售后原因不能为空");
        String type = command.type() == null ? "" : command.type().trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new DomainException("AFTERSALE_TYPE_INVALID", "售后类型无效");
        }
        var existing = port.findByClientRequest(userId, clientRequestId);
        if (existing.isPresent()) {
            return existing.get();
        }
        OrderEligibility order = port.orderEligibility(command.orderId())
                .orElseThrow(() -> new DomainException("ORDER_NOT_FOUND", "订单不存在"));
        if (order.buyerUserId() != userId) {
            throw new DomainException("AFTERSALE_ACCESS_DENIED", "仅订单买家可以申请售后");
        }
        if (!Set.of("SHIPPED", "COMPLETED").contains(order.status())) {
            throw new DomainException("AFTERSALE_ORDER_STATUS_INVALID", "仅已发货或已完成订单可申请售后");
        }
        OrderTimerParameters timer = port.orderTimer(command.orderId());
        if ("COMPLETED".equals(order.status()) && order.completedAt() != null
                && order.completedAt().plus(timer.afterSaleDaysAfterCompletion(), ChronoUnit.DAYS)
                .isBefore(Instant.now())) {
            throw new DomainException("AFTERSALE_WINDOW_EXPIRED", "订单已超过售后申请期限");
        }
        if (order.completedAfterSaleCount() > 0) {
            throw new DomainException("AFTERSALE_ALREADY_COMPLETED", "该订单已完成售后，不能再次申请");
        }
        if (order.activeAfterSaleCount() > 0) {
            throw new DomainException("AFTERSALE_ALREADY_EXISTS", "当前订单已有进行中的售后");
        }
        return port.create(
                userId,
                "AS" + System.currentTimeMillis()
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(),
                new ApplyCommand(
                        command.orderId(),
                        clientRequestId,
                        type,
                        command.reason().trim(),
                        command.description()
                )
        );
    }

    @Override
    public List<View> userAfterSales(long userId) {
        return port.userAfterSales(userId);
    }

    @Override
    public List<View> superiorAfterSales(long superiorUserId) {
        return port.superiorAfterSales(superiorUserId);
    }

    @Override
    public View afterSale(long userId, long afterSaleId) {
        View current = port.load(afterSaleId);
        if (current.applicantUserId() != userId && current.superiorUserId() != userId) {
            throw new DomainException("AFTERSALE_ACCESS_DENIED", "无权查看此售后单");
        }
        return current;
    }

    @Override
    public List<View> adminAfterSales(String status) {
        return port.adminAfterSales(status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT));
    }

    @Override
    public void adminDecision(long adminId, long afterSaleId, boolean approve, String reason,
                              String returnAddressJson) {
        View current = port.load(afterSaleId);
        if (!"PENDING_ADMIN_REVIEW".equals(current.status())) {
            throw statusConflict();
        }
        if (approve && "RETURN_REFUND".equals(current.type())) {
            requireText(returnAddressJson, "RETURN_ADDRESS_REQUIRED", "退货退款必须配置退货地址");
            port.transition(
                    afterSaleId,
                    current.status(),
                    "AWAITING_RETURN",
                    new TransitionData(reason, returnAddressJson, null, null, null, null, null, false)
            );
        } else if (approve) {
            port.transition(
                    afterSaleId,
                    current.status(),
                    "PENDING_OFFLINE_REFUND",
                    new TransitionData(reason, null, null, null, null, null, null, false)
            );
        } else {
            requireText(reason, "ADMIN_REASON_REQUIRED", "拒绝售后必须填写原因");
            port.transition(
                    afterSaleId,
                    current.status(),
                    "REJECTED",
                    new TransitionData(reason, null, null, null, null, null, null, false)
            );
        }
    }

    @Override
    public void submitReturn(long userId, long afterSaleId, String carrier, String trackingNo) {
        View current = requireApplicant(userId, afterSaleId);
        if (!"AWAITING_RETURN".equals(current.status())) {
            throw statusConflict();
        }
        requireText(carrier, "RETURN_CARRIER_REQUIRED", "回寄物流不能为空");
        requireText(trackingNo, "RETURN_TRACKING_REQUIRED", "回寄物流单号不能为空");
        port.transition(
                afterSaleId,
                current.status(),
                "RETURN_SHIPPED",
                new TransitionData(null, null, carrier.trim(), trackingNo.trim(), null, null, null, false)
        );
    }

    @Override
    public void adminConfirmReturnReceived(long adminId, long afterSaleId, String reason) {
        View current = port.load(afterSaleId);
        if (!"RETURN_SHIPPED".equals(current.status())) {
            throw statusConflict();
        }
        port.transition(
                afterSaleId,
                current.status(),
                "PENDING_OFFLINE_REFUND",
                new TransitionData(reason, null, null, null, null, null, null, false)
        );
    }

    @Override
    public void superiorConfirmOfflineRefund(long superiorUserId, long afterSaleId, String reason) {
        View current = port.load(afterSaleId);
        if (current.superiorUserId() != superiorUserId) {
            throw new DomainException("AFTERSALE_ACCESS_DENIED", "仅订单直属上级可以确认线下退款");
        }
        if (!"PENDING_OFFLINE_REFUND".equals(current.status())) {
            throw statusConflict();
        }
        Instant now = Instant.now();
        port.transition(
                afterSaleId,
                current.status(),
                "PENDING_BUYER_REFUND_CONFIRMATION",
                new TransitionData(reason, null, null, null, superiorUserId, now, null, false)
        );
    }

    @Override
    public void userConfirmRefund(long userId, long afterSaleId) {
        View current = requireApplicant(userId, afterSaleId);
        if (!"PENDING_BUYER_REFUND_CONFIRMATION".equals(current.status())) {
            throw statusConflict();
        }
        Instant now = Instant.now();
        port.transition(
                afterSaleId,
                current.status(),
                "COMPLETED",
                new TransitionData(null, null, null, null, null, null, now, true)
        );
    }

    @Override
    public void userCancel(long userId, long afterSaleId, String reason) {
        View current = requireApplicant(userId, afterSaleId);
        if (!Set.of("PENDING_ADMIN_REVIEW", "AWAITING_RETURN").contains(current.status())) {
            throw statusConflict();
        }
        requireText(reason, "AFTERSALE_CANCEL_REASON_REQUIRED", "撤销售后必须填写原因");
        port.transition(
                afterSaleId,
                current.status(),
                "CANCELLED",
                new TransitionData(reason.trim(), null, null, null, null, null, null, false)
        );
    }

    private View requireApplicant(long userId, long afterSaleId) {
        View current = port.load(afterSaleId);
        if (current.applicantUserId() != userId) {
            throw new DomainException("AFTERSALE_ACCESS_DENIED", "无权操作此售后单");
        }
        return current;
    }

    private static DomainException statusConflict() {
        return new DomainException("AFTERSALE_STATUS_CONFLICT", "当前售后状态不允许执行此操作");
    }

    private static String requireText(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new DomainException(code, message);
        }
        return value.trim();
    }
}

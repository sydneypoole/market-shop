package com.marketshop.domain.trade;

import com.marketshop.domain.shared.DomainException;
import com.marketshop.domain.shared.Money;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class Order {

    public static final int MAX_BUYER_NOTE_LENGTH = 500;

    private final Long id;
    private final String orderNo;
    private final long buyerId;
    private final long superiorId;
    private final List<OrderLine> lines;
    private final Money totalAmount;
    private final String buyerNote;
    private OrderStatus status;
    private Instant superiorConfirmedAt;
    private Instant adminReviewedAt;
    private Instant shippedAt;
    private Instant autoReceiveAt;
    private Instant completedAt;
    private String reason;
    private int version;

    private Order(
            Long id,
            String orderNo,
            long buyerId,
            long superiorId,
            List<OrderLine> lines,
            Money totalAmount,
            String buyerNote,
            OrderStatus status,
            Instant superiorConfirmedAt,
            Instant adminReviewedAt,
            Instant shippedAt,
            Instant autoReceiveAt,
            Instant completedAt,
            String reason,
            int version
    ) {
        this.id = id;
        this.orderNo = requireText(orderNo, "ORDER_NO_REQUIRED", "订单号不能为空");
        if (buyerId <= 0 || superiorId <= 0 || buyerId == superiorId) {
            throw new DomainException("ORDER_PARTY_INVALID", "订单用户与直属上级无效");
        }
        if (lines == null || lines.isEmpty()) {
            throw new DomainException("ORDER_LINE_REQUIRED", "订单至少包含一个商品");
        }
        this.buyerId = buyerId;
        this.superiorId = superiorId;
        this.lines = List.copyOf(lines);
        this.totalAmount = Objects.requireNonNull(totalAmount, "totalAmount");
        this.buyerNote = normalizeBuyerNote(buyerNote);
        this.status = Objects.requireNonNull(status, "status");
        this.superiorConfirmedAt = superiorConfirmedAt;
        this.adminReviewedAt = adminReviewedAt;
        this.shippedAt = shippedAt;
        this.autoReceiveAt = autoReceiveAt;
        this.completedAt = completedAt;
        this.reason = reason;
        this.version = version;
    }

    public static Order submit(
            String orderNo,
            long buyerId,
            long superiorId,
            List<OrderLine> lines
    ) {
        return submit(orderNo, buyerId, superiorId, lines, null);
    }

    public static Order submit(
            String orderNo,
            long buyerId,
            long superiorId,
            List<OrderLine> lines,
            String buyerNote
    ) {
        Money total = lines.stream()
                .map(OrderLine::subtotal)
                .reduce(Money.ZERO, Money::add);
        return new Order(
                null,
                orderNo,
                buyerId,
                superiorId,
                lines,
                total,
                buyerNote,
                OrderStatus.PENDING_SUPERIOR,
                null,
                null,
                null,
                null,
                null,
                null,
                0
        );
    }

    public static Order rehydrate(
            long id,
            String orderNo,
            long buyerId,
            long superiorId,
            List<OrderLine> lines,
            Money totalAmount,
            OrderStatus status,
            Instant superiorConfirmedAt,
            Instant adminReviewedAt,
            Instant shippedAt,
            Instant autoReceiveAt,
            Instant completedAt,
            String reason,
            int version
    ) {
        return rehydrate(
                id,
                orderNo,
                buyerId,
                superiorId,
                lines,
                totalAmount,
                status,
                superiorConfirmedAt,
                adminReviewedAt,
                shippedAt,
                autoReceiveAt,
                completedAt,
                reason,
                version,
                null
        );
    }

    public static Order rehydrate(
            long id,
            String orderNo,
            long buyerId,
            long superiorId,
            List<OrderLine> lines,
            Money totalAmount,
            OrderStatus status,
            Instant superiorConfirmedAt,
            Instant adminReviewedAt,
            Instant shippedAt,
            Instant autoReceiveAt,
            Instant completedAt,
            String reason,
            int version,
            String buyerNote
    ) {
        return new Order(
                id,
                orderNo,
                buyerId,
                superiorId,
                lines,
                totalAmount,
                buyerNote,
                status,
                superiorConfirmedAt,
                adminReviewedAt,
                shippedAt,
                autoReceiveAt,
                completedAt,
                reason,
                version
        );
    }

    public void cancel(String cancelReason) {
        requireStatus(OrderStatus.PENDING_SUPERIOR);
        reason = requireReason(cancelReason);
        status = OrderStatus.CANCELLED;
        version++;
    }

    public void superiorConfirm(Instant now) {
        requireStatus(OrderStatus.PENDING_SUPERIOR);
        superiorConfirmedAt = Objects.requireNonNull(now, "now");
        status = OrderStatus.PENDING_ADMIN_REVIEW;
        reason = null;
        version++;
    }

    public void superiorReject(String rejectReason) {
        requireStatus(OrderStatus.PENDING_SUPERIOR);
        reason = requireReason(rejectReason);
        status = OrderStatus.SUPERIOR_REJECTED;
        version++;
    }

    public void adminApprove(Instant now) {
        requireStatus(OrderStatus.PENDING_ADMIN_REVIEW);
        adminReviewedAt = Objects.requireNonNull(now, "now");
        status = OrderStatus.PENDING_SHIPMENT;
        reason = null;
        version++;
    }

    public void adminReject(String rejectReason, Instant now) {
        requireStatus(OrderStatus.PENDING_ADMIN_REVIEW);
        reason = requireReason(rejectReason);
        adminReviewedAt = Objects.requireNonNull(now, "now");
        status = OrderStatus.ADMIN_REJECTED;
        version++;
    }

    public void ship(Instant now, Instant configuredAutoReceiveAt) {
        requireStatus(OrderStatus.PENDING_SHIPMENT);
        shippedAt = Objects.requireNonNull(now, "now");
        autoReceiveAt = Objects.requireNonNull(configuredAutoReceiveAt, "configuredAutoReceiveAt");
        if (!autoReceiveAt.isAfter(shippedAt)) {
            throw new DomainException("AUTO_RECEIVE_INVALID", "自动收货时间必须晚于发货时间");
        }
        status = OrderStatus.SHIPPED;
        version++;
    }

    public void receive(Instant now) {
        requireStatus(OrderStatus.SHIPPED);
        completedAt = Objects.requireNonNull(now, "now");
        status = OrderStatus.COMPLETED;
        version++;
    }

    public void timeoutClose() {
        requireStatus(OrderStatus.PENDING_SHIPMENT);
        reason = "超时未发货，系统自动关闭";
        status = OrderStatus.CANCELLED;
        version++;
    }

    private void requireStatus(OrderStatus expected) {
        if (status != expected) {
            throw new DomainException(
                    "ORDER_STATUS_CONFLICT",
                    "订单状态 " + status + " 不允许执行该操作，期望状态为 " + expected
            );
        }
    }

    private static String requireReason(String value) {
        return requireText(value, "ORDER_REASON_REQUIRED", "必须填写原因");
    }

    public static void validateBuyerNote(String value) {
        normalizeBuyerNote(value);
    }

    private static String normalizeBuyerNote(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.codePointCount(0, normalized.length()) > MAX_BUYER_NOTE_LENGTH) {
            throw new DomainException("ORDER_BUYER_NOTE_INVALID", "订单备注不能超过 500 个字符");
        }
        return normalized;
    }

    private static String requireText(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new DomainException(code, message);
        }
        return value.trim();
    }

    public Long id() {
        return id;
    }

    public String orderNo() {
        return orderNo;
    }

    public long buyerId() {
        return buyerId;
    }

    public long superiorId() {
        return superiorId;
    }

    public List<OrderLine> lines() {
        return lines;
    }

    public Money totalAmount() {
        return totalAmount;
    }

    public String buyerNote() {
        return buyerNote;
    }

    public OrderStatus status() {
        return status;
    }

    public Instant superiorConfirmedAt() {
        return superiorConfirmedAt;
    }

    public Instant adminReviewedAt() {
        return adminReviewedAt;
    }

    public Instant shippedAt() {
        return shippedAt;
    }

    public Instant autoReceiveAt() {
        return autoReceiveAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public String reason() {
        return reason;
    }

    public int version() {
        return version;
    }
}

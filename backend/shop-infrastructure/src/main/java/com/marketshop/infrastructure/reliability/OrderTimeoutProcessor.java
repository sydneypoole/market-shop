package com.marketshop.infrastructure.reliability;

import com.marketshop.application.commerce.CommercePort;
import com.marketshop.domain.shared.Money;
import com.marketshop.domain.trade.Order;
import com.marketshop.domain.trade.OrderLine;
import com.marketshop.domain.trade.OrderStatus;
import com.marketshop.infrastructure.persistence.mapper.CommerceMapper;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderItemRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Component
public class OrderTimeoutProcessor {

    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);

    private final CommerceMapper mapper;
    private final CommercePort port;

    public OrderTimeoutProcessor(CommerceMapper mapper, CommercePort port) {
        this.mapper = mapper;
        this.port = port;
    }

    /**
     * Closes a due pending order through persistTransition so reserved inventory
     * is released. PENDING_ADMIN_REVIEW and PENDING_SHIPMENT have already been
     * collected offline; ops refunds that money outside this job.
     */
    @Transactional
    public boolean processNext() {
        OrderRow row = mapper.lockDueOrderTimeout();
        if (row == null) {
            return false;
        }
        List<OrderLine> lines = mapper.orderItems(row.id).stream()
                .map(this::line)
                .toList();
        Order order = Order.rehydrate(
                row.id,
                row.orderNo,
                row.buyerUserId,
                row.superiorUserId,
                lines,
                new Money(row.totalAmountFen),
                OrderStatus.valueOf(row.status),
                instant(row.superiorConfirmedAt),
                instant(row.adminReviewedAt),
                instant(row.shippedAt),
                instant(row.autoReceiveAt),
                instant(row.completedAt),
                row.reason,
                row.version,
                row.buyerNote
        );
        int expectedVersion = order.version();
        switch (order.status()) {
            case PENDING_SUPERIOR -> order.cancel("超时未确认收款，系统自动取消");
            case PENDING_ADMIN_REVIEW -> order.adminReject("超时未审核，系统自动驳回", Instant.now());
            case PENDING_SHIPMENT -> order.timeoutClose();
            default -> throw new IllegalStateException("lockDueOrderTimeout returned unexpected status " + order.status());
        }
        String eventType = order.status() == OrderStatus.ADMIN_REJECTED ? "ORDER_ADMIN_DECIDED" : "ORDER_CANCELLED";
        port.persistTransition(order, expectedVersion, eventType);
        return true;
    }

    private OrderLine line(OrderItemRow item) {
        return new OrderLine(
                item.skuId,
                item.skuName,
                new Money(item.unitPriceFen),
                item.quantity,
                item.salesScene
        );
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(BUSINESS_ZONE);
    }
}

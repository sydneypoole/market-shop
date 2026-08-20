package com.marketshop.infrastructure.reliability;

import com.marketshop.application.membership.OrderTimerParameters;
import com.marketshop.application.membership.RuleRuntimeResolver;
import com.marketshop.domain.shared.DomainException;
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
import java.util.UUID;

@Component
public class AutoReceiveProcessor {

    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);

    private final CommerceMapper mapper;

    public AutoReceiveProcessor(CommerceMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public boolean processNext() {
        OrderRow row = mapper.lockDueAutoReceive();
        if (row == null) {
            return false;
        }
        timer(row);
        if (mapper.countBlockingAfterSales(row.id) > 0) {
            throw new DomainException("AFTERSALE_BLOCKS_RECEIVE", "订单存在进行中或已完成的售后，不能确认收货");
        }
        var lines = mapper.orderItems(row.id).stream()
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
        order.receive(Instant.now());
        int updated = mapper.updateTransition(
                order.id(),
                order.status().name(),
                local(order.superiorConfirmedAt()),
                local(order.adminReviewedAt()),
                local(order.shippedAt()),
                local(order.autoReceiveAt()),
                local(order.completedAt()),
                null,
                order.reason(),
                order.version(),
                expectedVersion
        );
        if (updated != 1) {
            throw new DomainException("AUTO_RECEIVE_CONFLICT", "自动收货并发冲突");
        }
        mapper.snapshotApplicableRules(order.id());
        if (mapper.orderRuleSnapshotComplete(order.id()) != 1) {
            throw new DomainException("ORDER_RULE_SNAPSHOT_MISSING", "订单完成时缺少必需的生效规则版本");
        }
        mapper.insertCompletedOutbox(UUID.randomUUID().toString(), order.id(), "AUTO_RECEIVE");
        return true;
    }

    private static OrderTimerParameters timer(OrderRow row) {
        if (row == null || row.timerRuleCode == null || row.timerRuleType == null
                || row.timerParametersJson == null) {
            throw RuleRuntimeResolver.invalidOrderTimer();
        }
        return RuleRuntimeResolver.orderTimer(
                row.timerRuleCode, row.timerRuleType, row.timerParametersJson
        );
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

    private static LocalDateTime local(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, BUSINESS_ZONE);
    }
}

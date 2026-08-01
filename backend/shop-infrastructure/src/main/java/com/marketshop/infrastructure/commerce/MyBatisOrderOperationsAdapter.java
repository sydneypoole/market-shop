package com.marketshop.infrastructure.commerce;

import com.marketshop.application.commerce.CommerceUseCase.OrderView;
import com.marketshop.application.commerce.OrderOperationsPort;
import com.marketshop.application.commerce.OrderOperationsUseCase.DashboardView;
import com.marketshop.application.commerce.OrderOperationsUseCase.OrderNoteView;
import com.marketshop.application.commerce.OrderOperationsUseCase.OrderPage;
import com.marketshop.application.commerce.OrderOperationsUseCase.OrderSearchQuery;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.domain.trade.OrderStatus;
import com.marketshop.infrastructure.persistence.mapper.CommerceMapper;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class MyBatisOrderOperationsAdapter implements OrderOperationsPort {

    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);
    private final CommerceMapper mapper;

    public MyBatisOrderOperationsAdapter(CommerceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public OrderPage search(OrderSearchQuery query) {
        int offset = Math.multiplyExact(query.page() - 1, query.size());
        List<OrderView> items = mapper.searchAdminOrders(
                query.orderNo(), query.buyerUserId(), query.superiorUserId(), query.status(),
                local(query.from()), local(query.to()), offset, query.size()
        ).stream().map(MyBatisOrderOperationsAdapter::order).toList();
        return new OrderPage(
                items,
                mapper.countAdminOrders(
                        query.orderNo(), query.buyerUserId(), query.superiorUserId(), query.status(),
                        local(query.from()), local(query.to())
                ),
                query.page(),
                query.size()
        );
    }

    @Override
    public List<OrderNoteView> notes(long orderId) {
        return mapper.orderNotes(orderId).stream().map(row ->
                new OrderNoteView(row.id, row.adminId, row.note, instant(row.createdAt))
        ).toList();
    }

    @Override
    public void addNote(long adminId, long orderId, String note) {
        if (mapper.insertOrderNote(adminId, orderId, note) != 1) {
            throw new DomainException("ORDER_NOT_FOUND", "订单不存在");
        }
    }

    @Override
    public DashboardView dashboard(int lowInventoryThreshold) {
        return new DashboardView(
                mapper.dashboardMemberCount(),
                mapper.dashboardTodayOrderCount(),
                mapper.dashboardTodayCompletedAmount(),
                mapper.dashboardOrderStatusCount(OrderStatus.PENDING_SUPERIOR.name()),
                mapper.dashboardOrderStatusCount("PENDING_ADMIN_REVIEW"),
                mapper.dashboardOrderStatusCount("PENDING_SHIPMENT"),
                mapper.dashboardActiveAfterSaleCount(),
                mapper.dashboardOnSaleProductCount(),
                mapper.dashboardLowInventoryCount(lowInventoryThreshold)
        );
    }

    private static OrderView order(OrderRow row) {
        return new OrderView(
                row.id, row.orderNo, row.buyerUserId, row.superiorUserId,
                row.totalAmountFen, row.status, row.reason, instant(row.createdAt)
        );
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(BUSINESS_ZONE);
    }

    private static LocalDateTime local(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, BUSINESS_ZONE);
    }
}

package com.marketshop.application.commerce;

import com.marketshop.application.commerce.OrderOperationsUseCase.BatchResult;
import com.marketshop.application.operation.OperationSettingsPort;
import com.marketshop.domain.shared.DomainException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class OrderOperationsApplicationService implements OrderOperationsUseCase {

    private final OrderOperationsPort port;
    private final CommerceUseCase commerce;
    private final OperationSettingsPort settings;

    public OrderOperationsApplicationService(
            OrderOperationsPort port,
            CommerceUseCase commerce,
            OperationSettingsPort settings
    ) {
        this.port = port;
        this.commerce = commerce;
        this.settings = settings;
    }

    @Override
    public OrderPage search(OrderSearchQuery query) {
        int page = Math.max(1, query.page());
        int size = Math.max(1, Math.min(query.size(), 500));
        return port.search(new OrderSearchQuery(
                trim(query.orderNo()), query.buyerUserId(), query.superiorUserId(), upper(query.status()),
                query.from(), query.to(), page, size
        ));
    }

    @Override
    public String exportCsv(OrderSearchQuery query) {
        OrderPage page = port.search(new OrderSearchQuery(
                trim(query.orderNo()), query.buyerUserId(), query.superiorUserId(), upper(query.status()),
                query.from(), query.to(), 1, 10_000
        ));
        StringBuilder csv = new StringBuilder("\uFEFF订单号,买家ID,直属上级ID,应收金额(分),状态,原因,创建时间\r\n");
        page.items().forEach(order -> csv
                .append(cell(order.orderNo())).append(',')
                .append(order.buyerUserId()).append(',')
                .append(order.superiorUserId()).append(',')
                .append(order.totalAmountFen()).append(',')
                .append(cell(order.status())).append(',')
                .append(cell(order.reason())).append(',')
                .append(cell(order.createdAt() == null ? "" : order.createdAt().toString()))
                .append("\r\n"));
        if (csv.toString().getBytes(StandardCharsets.UTF_8).length > 20 * 1024 * 1024) {
            throw new DomainException("ORDER_EXPORT_TOO_LARGE", "导出结果过大，请缩小筛选范围");
        }
        return csv.toString();
    }

    @Override
    public List<OrderNoteView> notes(long orderId) {
        return port.notes(orderId);
    }

    @Override
    public void addNote(long adminId, long orderId, String note) {
        if (note == null || note.isBlank() || note.trim().length() > 1000) {
            throw new DomainException("ORDER_NOTE_INVALID", "订单备注必须在 1 到 1000 字之间");
        }
        port.addNote(adminId, orderId, note.trim());
    }

    @Override
    public List<BatchResult> batchShip(long adminId, List<BatchShipment> shipments) {
        if (shipments == null || shipments.isEmpty() || shipments.size() > 100) {
            throw new DomainException("BATCH_SHIPMENT_INVALID", "批量发货数量必须在 1 到 100 之间");
        }
        List<BatchResult> result = new ArrayList<>();
        for (BatchShipment item : shipments) {
            try {
                commerce.ship(adminId, item.orderId(), item.shipment());
                result.add(new BatchResult(item.orderId(), true, "发货成功"));
            } catch (RuntimeException exception) {
                result.add(new BatchResult(item.orderId(), false, exception.getMessage()));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public DashboardView dashboard() {
        return port.dashboard(settings.lowInventoryThreshold());
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String upper(String value) {
        String trimmed = trim(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static String cell(String value) {
        String safe = value == null ? "" : value;
        if (safe.startsWith("=") || safe.startsWith("+") || safe.startsWith("-") || safe.startsWith("@")) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}

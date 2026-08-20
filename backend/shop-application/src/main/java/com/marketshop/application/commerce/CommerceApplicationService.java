package com.marketshop.application.commerce;

import com.marketshop.application.commerce.CommercePort.CheckoutContext;
import com.marketshop.application.commerce.CommercePort.CheckoutSku;
import com.marketshop.application.commerce.CommercePort.ItemQuantity;
import com.marketshop.application.commerce.CommercePort.OrderAggregate;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.domain.shared.Money;
import com.marketshop.domain.trade.Order;
import com.marketshop.domain.trade.OrderLine;
import com.marketshop.domain.trade.OrderStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CommerceApplicationService implements CommerceUseCase {

    private final CommercePort port;

    public CommerceApplicationService(CommercePort port) {
        this.port = port;
    }

    @Override
    public List<ProductView> products() {
        return port.products();
    }

    @Override
    public ProductDetail product(long productId) {
        return port.product(productId)
                .orElseThrow(() -> new DomainException("PRODUCT_NOT_FOUND", "商品不存在或已下架"));
    }

    @Override
    public List<CategoryView> categories() {
        return port.categories();
    }

    @Override
    public void updateProduct(long adminId, UpdateProductCommand command) {
        requireText(command.name(), "PRODUCT_NAME_REQUIRED", "商品名称不能为空");
        requireText(command.salesScene(), "SALES_SCENE_REQUIRED", "销售场景不能为空");
        if (!List.of("UPGRADE", "REPURCHASE").contains(command.salesScene().toUpperCase(Locale.ROOT))) {
            throw new DomainException("SALES_SCENE_INVALID", "销售场景仅支持升级或复购");
        }
        if (command.priceFen() < 0 || command.inventory() < 0) {
            throw new DomainException("PRODUCT_VALUE_INVALID", "商品价格或库存无效");
        }
        if (!List.of("ON_SALE", "OFF_SALE").contains(command.status())) {
            throw new DomainException("PRODUCT_STATUS_INVALID", "商品状态无效");
        }
        port.updateProduct(command);
    }

    @Override
    public List<ContentView> contents() {
        return port.contents();
    }

    @Override
    public ContentView content(long contentId) {
        return port.content(contentId)
                .orElseThrow(() -> new DomainException("CONTENT_NOT_FOUND", "内容不存在或未发布"));
    }

    @Override
    public List<CartItemView> cart(long userId) {
        return port.cart(userId);
    }

    @Override
    public void setCartItem(long userId, long skuId, int quantity, boolean selected) {
        if (quantity < 0 || quantity > 99) {
            throw new DomainException("QUANTITY_INVALID", "购物车数量必须在 0 到 99 之间");
        }
        port.setCartItem(userId, skuId, quantity, selected);
    }

    @Override
    public OrderView submit(long userId, SubmitOrderCommand command) {
        validateSubmit(command);
        String source = normalizeSource(command.source());
        String clientRequestId = command.clientRequestId().trim();
        var existing = port.findByClientRequest(userId, clientRequestId);
        if (existing.isPresent()) {
            return existing.get();
        }
        List<ItemQuantity> requested = command.items().stream()
                .map(item -> new ItemQuantity(item.skuId(), item.quantity()))
                .toList();
        CheckoutContext context = port.checkoutContext(userId, requested);
        Map<Long, CheckoutSku> priced = context.skus().stream()
                .collect(Collectors.toMap(CheckoutSku::skuId, Function.identity()));
        for (OrderItemCommand item : command.items()) {
            CheckoutSku sku = priced.get(item.skuId());
            if (sku == null || sku.unitPriceFen() != item.unitPriceFen()) {
                throw new DomainException("PRICE_CHANGED", "商品价格已变更，请重新提交");
            }
        }
        List<OrderLine> lines = context.skus().stream()
                .map(sku -> new OrderLine(
                        sku.skuId(),
                        sku.skuName(),
                        new Money(sku.unitPriceFen()),
                        sku.requestedQuantity(),
                        sku.salesScene()
                ))
                .toList();
        Order order = Order.submit(
                orderNo(),
                userId,
                context.superiorUserId(),
                lines,
                command.buyerNote()
        );
        return port.saveSubmitted(
                order,
                command.address(),
                source,
                clientRequestId,
                context.skus()
        );
    }

    @Override
    public List<OrderView> buyerOrders(long userId) {
        return port.buyerOrders(userId);
    }

    @Override
    public List<OrderView> superiorOrders(long userId) {
        return port.superiorOrders(userId);
    }

    @Override
    public List<OrderView> adminOrders(String status) {
        return port.adminOrders(status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT));
    }

    @Override
    public OrderDetail order(long userId, long orderId) {
        OrderDetail detail = port.order(orderId);
        if (detail.order().buyerUserId() != userId && detail.order().superiorUserId() != userId) {
            throw new DomainException("ORDER_ACCESS_DENIED", "无权查看此订单");
        }
        return withActorCapabilities(detail, userId);
    }

    @Override
    public OrderDetail adminOrder(long orderId) {
        return withActorCapabilities(port.order(orderId), null);
    }

    @Override
    public void superiorDecision(long superiorUserId, long orderId, boolean approve, String reason) {
        Order order = loadDomainOrder(orderId);
        if (order.superiorId() != superiorUserId) {
            throw new DomainException("ORDER_ACTOR_INVALID", "仅订单直属上级可以确认线下收款");
        }
        int expectedVersion = order.version();
        if (approve) {
            order.superiorConfirm(Instant.now());
        } else {
            order.superiorReject(reason);
        }
        port.persistTransition(order, expectedVersion, "ORDER_SUPERIOR_DECIDED");
    }

    @Override
    public void adminDecision(long adminId, long orderId, boolean approve, String reason) {
        Order order = loadDomainOrder(orderId);
        int expectedVersion = order.version();
        if (approve) {
            order.adminApprove(Instant.now());
        } else {
            order.adminReject(reason, Instant.now());
        }
        port.persistTransition(order, expectedVersion, "ORDER_ADMIN_DECIDED");
    }

    @Override
    public void ship(long adminId, long orderId, ShipmentCommand command) {
        requireText(command.carrierCode(), "CARRIER_REQUIRED", "物流公司编码不能为空");
        requireText(command.carrierName(), "CARRIER_REQUIRED", "物流公司名称不能为空");
        requireText(command.trackingNo(), "TRACKING_REQUIRED", "物流单号不能为空");
        Order order = loadDomainOrder(orderId);
        int expectedVersion = order.version();
        Instant now = Instant.now();
        order.ship(now, now.plus(port.autoReceiveDays(orderId), ChronoUnit.DAYS));
        port.persistShipment(order, expectedVersion, adminId, command);
    }

    @Override
    public void receive(long buyerUserId, long orderId) {
        Order order = loadDomainOrder(orderId);
        if (order.buyerId() != buyerUserId) {
            throw new DomainException("ORDER_ACTOR_INVALID", "仅订单买家可以确认收货");
        }
        if (port.hasBlockingAfterSale(orderId)) {
            throw new DomainException("AFTERSALE_BLOCKS_RECEIVE", "订单存在进行中或已完成的售后，不能确认收货");
        }
        int expectedVersion = order.version();
        order.receive(Instant.now());
        port.persistTransition(order, expectedVersion, "ORDER_COMPLETED");
    }

    @Override
    public void cancel(long buyerUserId, long orderId, String reason) {
        Order order = loadDomainOrder(orderId);
        if (order.buyerId() != buyerUserId) {
            throw new DomainException("ORDER_ACTOR_INVALID", "仅订单买家可以取消订单");
        }
        requireText(reason, "ORDER_CANCEL_REASON_REQUIRED", "取消订单必须填写原因");
        int expectedVersion = order.version();
        order.cancel(reason.trim());
        port.persistTransition(order, expectedVersion, "ORDER_CANCELLED");
    }

    private Order loadDomainOrder(long orderId) {
        OrderAggregate aggregate = port.loadOrder(orderId)
                .orElseThrow(() -> new DomainException("ORDER_NOT_FOUND", "订单不存在"));
        List<OrderLine> lines = aggregate.lines().stream()
                .map(line -> new OrderLine(
                        line.skuId(),
                        line.skuName(),
                        new Money(line.unitPriceFen()),
                        line.quantity(),
                        line.salesScene()
                ))
                .toList();
        OrderStatus status;
        try {
            status = OrderStatus.valueOf(aggregate.status());
        } catch (IllegalArgumentException | NullPointerException exception) {
            // A forward-compatible/read-only status must not escape as a
            // framework 500 when an old writer attempts a state transition.
            throw new DomainException("ORDER_STATUS_UNSUPPORTED", "订单状态暂不支持此操作");
        }
        return Order.rehydrate(
                aggregate.id(),
                aggregate.orderNo(),
                aggregate.buyerUserId(),
                aggregate.superiorUserId(),
                lines,
                new Money(aggregate.totalAmountFen()),
                status,
                aggregate.superiorConfirmedAt(),
                aggregate.adminReviewedAt(),
                aggregate.shippedAt(),
                aggregate.autoReceiveAt(),
                aggregate.completedAt(),
                aggregate.reason(),
                aggregate.version(),
                aggregate.buyerNote()
        );
    }

    private OrderDetail withActorCapabilities(OrderDetail detail, Long actorUserId) {
        boolean buyer = actorUserId != null && detail.order().buyerUserId() == actorUserId;
        boolean superior = actorUserId != null && detail.order().superiorUserId() == actorUserId;
        String status = detail.order().status();
        boolean pendingSuperior = "PENDING_SUPERIOR".equals(status);
        boolean blocked = port.hasBlockingAfterSale(detail.order().id());
        return new OrderDetail(
                detail.order(),
                detail.addressJson(),
                detail.buyerNote(),
                detail.items(),
                detail.shipment(),
                detail.superiorConfirmedAt(),
                detail.adminReviewedAt(),
                detail.autoReceiveAt(),
                detail.completedAt(),
                new OrderActorCapabilities(
                        buyer && "SHIPPED".equals(status) && !blocked,
                        buyer && pendingSuperior,
                        buyer && pendingSuperior,
                        superior && pendingSuperior
                )
        );
    }

    private static void validateSubmit(SubmitOrderCommand command) {
        String clientRequestId = requireText(
                command.clientRequestId(),
                "CLIENT_REQUEST_REQUIRED",
                "客户端请求号不能为空"
        );
        if (clientRequestId.length() > 80) {
            throw new DomainException("CLIENT_REQUEST_INVALID", "客户端请求号过长");
        }
        Order.validateBuyerNote(command.buyerNote());
        if (command.items() == null || command.items().isEmpty() || command.items().size() > 20) {
            throw new DomainException("ORDER_LINE_REQUIRED", "订单商品数量必须在 1 到 20 种之间");
        }
        for (OrderItemCommand item : command.items()) {
            if (item.skuId() <= 0 || item.quantity() <= 0 || item.quantity() > 99) {
                throw new DomainException("QUANTITY_INVALID", "订单商品数量无效");
            }
            if (item.unitPriceFen() < 0) {
                throw new DomainException("PRICE_INVALID", "提交价格无效");
            }
        }
        if (new HashSet<>(command.items().stream().map(OrderItemCommand::skuId).toList()).size()
                != command.items().size()) {
            throw new DomainException("DUPLICATE_SKU", "同一商品规格不能重复提交");
        }
        AddressSnapshot address = command.address();
        if (address == null) {
            throw new DomainException("ADDRESS_REQUIRED", "收货地址不能为空");
        }
        requireText(address.recipientName(), "ADDRESS_REQUIRED", "收货人不能为空");
        requireText(address.phone(), "ADDRESS_REQUIRED", "联系电话不能为空");
        requireText(address.province(), "ADDRESS_REQUIRED", "省份不能为空");
        requireText(address.city(), "ADDRESS_REQUIRED", "城市不能为空");
        requireText(address.district(), "ADDRESS_REQUIRED", "区县不能为空");
        requireText(address.detailAddress(), "ADDRESS_REQUIRED", "详细地址不能为空");
    }

    private static String normalizeSource(String source) {
        String normalized = source == null ? "H5" : source.trim().toUpperCase(Locale.ROOT);
        if (!List.of("H5", "WEB", "MINIPROGRAM").contains(normalized)) {
            throw new DomainException("ORDER_SOURCE_INVALID", "订单来源仅支持 H5、WEB 或 MINIPROGRAM");
        }
        return normalized;
    }

    private static String orderNo() {
        return "MS" + System.currentTimeMillis()
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private static String requireText(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new DomainException(code, message);
        }
        return value.trim();
    }
}

package com.marketshop.infrastructure.commerce;

import com.marketshop.application.commerce.CommercePort;
import com.marketshop.application.commerce.CommerceUseCase.AddressSnapshot;
import com.marketshop.application.commerce.CommerceUseCase.CartItemView;
import com.marketshop.application.commerce.CommerceUseCase.ContentView;
import com.marketshop.application.commerce.CommerceUseCase.OrderDetail;
import com.marketshop.application.commerce.CommerceUseCase.OrderItemView;
import com.marketshop.application.commerce.CommerceUseCase.OrderView;
import com.marketshop.application.commerce.CommerceUseCase.ProductDetail;
import com.marketshop.application.commerce.CommerceUseCase.ProductView;
import com.marketshop.application.commerce.CommerceUseCase.ShipmentCommand;
import com.marketshop.application.commerce.CommerceUseCase.ShipmentView;
import com.marketshop.application.commerce.CommerceUseCase.UpdateProductCommand;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.domain.trade.Order;
import com.marketshop.infrastructure.persistence.mapper.CommerceMapper;
import com.marketshop.infrastructure.persistence.mapper.NotificationMapper;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.CartRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.ContentRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderItemRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderPo;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.ProductRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.ShipmentRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.SkuRow;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisCommerceAdapter implements CommercePort {

    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);

    private final CommerceMapper mapper;
    private final NotificationMapper notifications;

    public MyBatisCommerceAdapter(CommerceMapper mapper, NotificationMapper notifications) {
        this.mapper = mapper;
        this.notifications = notifications;
    }

    @Override
    public List<ProductView> products() {
        return mapper.products().stream().map(MyBatisCommerceAdapter::productView).toList();
    }

    @Override
    public Optional<ProductDetail> product(long productId) {
        ProductRow row = mapper.product(productId);
        return row == null ? Optional.empty() : Optional.of(new ProductDetail(
                productView(row),
                row.descriptionHtml,
                row.attributesJson
        ));
    }

    @Override
    @Transactional
    public void updateProduct(UpdateProductCommand command) {
        int productUpdated = mapper.updateProduct(
                command.productId(),
                command.name().trim(),
                command.subtitle(),
                command.salesScene().toUpperCase(),
                command.status()
        );
        int skuUpdated = mapper.updateSku(
                command.productId(),
                command.skuId(),
                command.priceFen(),
                command.status()
        );
        int inventoryUpdated = mapper.updateInventory(command.skuId(), command.inventory());
        if (productUpdated != 1 || skuUpdated != 1 || inventoryUpdated != 1) {
            throw new DomainException("PRODUCT_NOT_FOUND", "商品或规格不存在");
        }
    }

    @Override
    public List<ContentView> contents() {
        return mapper.contents().stream().map(MyBatisCommerceAdapter::contentView).toList();
    }

    @Override
    public List<CartItemView> cart(long userId) {
        return mapper.cart(userId).stream().map(MyBatisCommerceAdapter::cartView).toList();
    }

    @Override
    public void setCartItem(long userId, long skuId, int quantity, boolean selected) {
        if (quantity == 0) {
            mapper.deleteCart(userId, skuId);
            return;
        }
        if (mapper.productBySkuExists(skuId) == 0) {
            throw new DomainException("SKU_NOT_FOUND", "商品规格不存在或已下架");
        }
        mapper.upsertCart(userId, skuId, quantity, selected);
    }

    @Override
    @Transactional
    public CheckoutContext checkoutContext(long userId, List<ItemQuantity> items) {
        Long superiorId = mapper.findSuperiorId(userId);
        if (superiorId == null) {
            throw new DomainException("SUPERIOR_NOT_BOUND", "当前账号未绑定直属上级，不能提交订单");
        }
        List<CheckoutSku> result = new ArrayList<>();
        for (ItemQuantity item : items) {
            SkuRow row = mapper.lockSku(item.skuId());
            if (row == null) {
                throw new DomainException("SKU_NOT_FOUND", "商品规格不存在或已下架");
            }
            if (row.availableQuantity < item.quantity()) {
                throw new DomainException("INVENTORY_NOT_ENOUGH", row.skuName + " 库存不足");
            }
            result.add(new CheckoutSku(
                    row.productId,
                    row.skuId,
                    row.productName,
                    row.skuName,
                    row.coverUrl,
                    row.salesScene,
                    row.unitPriceFen,
                    item.quantity(),
                    row.availableQuantity
            ));
        }
        return new CheckoutContext(superiorId, List.copyOf(result));
    }

    @Override
    public Optional<OrderView> findByClientRequest(long userId, String clientRequestId) {
        return Optional.ofNullable(mapper.findByClientRequest(userId, clientRequestId))
                .map(MyBatisCommerceAdapter::orderView);
    }

    @Override
    @Transactional
    public OrderView saveSubmitted(Order order, AddressSnapshot address, String source, String clientRequestId,
                                   List<CheckoutSku> checkoutSkus) {
        OrderPo row = new OrderPo();
        row.orderNo = order.orderNo();
        row.buyerUserId = order.buyerId();
        row.superiorUserId = order.superiorId();
        row.addressSnapshotJson = addressJson(address);
        row.totalAmountFen = order.totalAmount().fen();
        row.status = order.status().name();
        row.source = source;
        row.clientRequestId = clientRequestId;
        row.version = order.version();
        mapper.insertOrder(row);
        for (CheckoutSku sku : checkoutSkus) {
            if (mapper.reserveInventory(sku.skuId(), sku.requestedQuantity()) != 1) {
                throw new DomainException("INVENTORY_NOT_ENOUGH", sku.skuName() + " 库存不足");
            }
            mapper.insertOrderItem(
                    row.id,
                    sku.productId(),
                    sku.skuId(),
                    sku.productName(),
                    sku.skuName(),
                    sku.coverUrl(),
                    sku.salesScene(),
                    sku.unitPriceFen(),
                    sku.requestedQuantity(),
                    Math.multiplyExact(sku.unitPriceFen(), sku.requestedQuantity())
            );
            mapper.clearPurchasedCart(order.buyerId(), sku.skuId());
        }
        insertOutbox(row.id, "ORDER_SUBMITTED", order.status().name());
        notifyUser(
                row.superiorUserId,
                "ORDER_AWAITING_SUPERIOR_CONFIRMATION",
                "有新的线下收款订单待确认",
                "订单 " + row.orderNo + " 已提交，请确认线下收款情况。",
                row.id,
                row.status
        );
        return new OrderView(
                row.id,
                row.orderNo,
                row.buyerUserId,
                row.superiorUserId,
                row.totalAmountFen,
                row.status,
                null,
                Instant.now()
        );
    }

    @Override
    public List<OrderView> buyerOrders(long userId) {
        return mapper.buyerOrders(userId).stream().map(MyBatisCommerceAdapter::orderView).toList();
    }

    @Override
    public List<OrderView> superiorOrders(long userId) {
        return mapper.superiorOrders(userId).stream().map(MyBatisCommerceAdapter::orderView).toList();
    }

    @Override
    public List<OrderView> adminOrders(String status) {
        return mapper.adminOrders(status).stream().map(MyBatisCommerceAdapter::orderView).toList();
    }

    @Override
    public Optional<OrderAggregate> loadOrder(long orderId) {
        OrderRow row = mapper.order(orderId);
        if (row == null) {
            return Optional.empty();
        }
        List<AggregateLine> lines = mapper.orderItems(orderId).stream()
                .map(item -> new AggregateLine(
                        item.skuId,
                        item.skuName,
                        item.unitPriceFen,
                        item.quantity,
                        item.salesScene
                ))
                .toList();
        return Optional.of(new OrderAggregate(
                row.id,
                row.orderNo,
                row.buyerUserId,
                row.superiorUserId,
                row.totalAmountFen,
                row.status,
                instant(row.superiorConfirmedAt),
                instant(row.adminReviewedAt),
                instant(row.shippedAt),
                instant(row.autoReceiveAt),
                instant(row.completedAt),
                row.reason,
                row.version,
                lines
        ));
    }

    @Override
    public OrderDetail order(long orderId) {
        OrderRow row = mapper.order(orderId);
        if (row == null) {
            throw new DomainException("ORDER_NOT_FOUND", "订单不存在");
        }
        List<OrderItemView> items = mapper.orderItems(orderId).stream()
                .map(MyBatisCommerceAdapter::orderItemView)
                .toList();
        ShipmentRow shipment = mapper.shipment(orderId);
        ShipmentView shipmentView = shipment == null ? null : new ShipmentView(
                shipment.carrierCode,
                shipment.carrierName,
                shipment.trackingNo,
                instant(shipment.shippedAt)
        );
        return new OrderDetail(
                orderView(row),
                row.addressSnapshotJson,
                items,
                shipmentView,
                instant(row.superiorConfirmedAt),
                instant(row.adminReviewedAt),
                instant(row.autoReceiveAt),
                instant(row.completedAt)
        );
    }

    @Override
    @Transactional
    public void persistTransition(Order order, int expectedVersion, String eventType) {
        updateOrder(order, expectedVersion);
        if ("SUPERIOR_REJECTED".equals(order.status().name()) || "ADMIN_REJECTED".equals(order.status().name())
                || "CANCELLED".equals(order.status().name())) {
            for (OrderItemRow item : mapper.orderItems(order.id())) {
                if (mapper.releaseReservedInventory(item.skuId, item.quantity) != 1) {
                    throw new DomainException("INVENTORY_STATE_CONFLICT", "订单库存状态冲突");
                }
            }
        }
        insertOutbox(order.id(), eventType, order.status().name());
        notifyTransition(order);
    }

    @Override
    @Transactional
    public void persistShipment(Order order, int expectedVersion, long adminId, ShipmentCommand shipment) {
        updateOrder(order, expectedVersion);
        mapper.insertShipment(
                order.id(),
                shipment.carrierCode().trim(),
                shipment.carrierName().trim(),
                shipment.trackingNo().trim(),
                adminId,
                local(order.shippedAt())
        );
        for (OrderItemRow item : mapper.orderItems(order.id())) {
            if (mapper.consumeReservedInventory(item.skuId, item.quantity) != 1) {
                throw new DomainException("INVENTORY_STATE_CONFLICT", "订单库存状态冲突");
            }
        }
        insertOutbox(order.id(), "ORDER_SHIPPED", order.status().name());
        notifyUser(
                order.buyerId(),
                "ORDER_SHIPPED",
                "订单已发货",
                "订单 " + order.orderNo() + " 已发货，请关注物流并及时确认收货。",
                order.id(),
                order.status().name()
        );
    }

    @Override
    public int autoReceiveDays() {
        Integer days = mapper.autoReceiveDays();
        return days == null || days <= 0 ? 7 : days;
    }

    private void updateOrder(Order order, int expectedVersion) {
        int updated = mapper.updateTransition(
                order.id(),
                order.status().name(),
                local(order.superiorConfirmedAt()),
                local(order.adminReviewedAt()),
                local(order.shippedAt()),
                local(order.autoReceiveAt()),
                local(order.completedAt()),
                order.reason(),
                order.version(),
                expectedVersion
        );
        if (updated != 1) {
            throw new DomainException("ORDER_CONCURRENT_MODIFICATION", "订单已被其他操作更新，请刷新后重试");
        }
    }

    private void insertOutbox(long orderId, String eventType, String status) {
        mapper.insertOutbox(
                UUID.randomUUID().toString(),
                String.valueOf(orderId),
                eventType,
                "{\"orderId\":" + orderId + ",\"status\":\"" + escape(status) + "\"}"
        );
    }

    private void notifyTransition(Order order) {
        switch (order.status()) {
            case PENDING_ADMIN_REVIEW -> notifyUser(
                    order.buyerId(), "ORDER_SUPERIOR_CONFIRMED", "上级已确认线下收款",
                    "订单 " + order.orderNo() + " 已由直属上级确认，等待后台审核。",
                    order.id(), order.status().name()
            );
            case SUPERIOR_REJECTED -> notifyUser(
                    order.buyerId(), "ORDER_SUPERIOR_REJECTED", "订单被直属上级驳回",
                    "订单 " + order.orderNo() + " 未通过直属上级确认，请查看原因。",
                    order.id(), order.status().name()
            );
            case PENDING_SHIPMENT -> {
                notifyUser(
                        order.buyerId(), "ORDER_ADMIN_APPROVED", "订单审核通过",
                        "订单 " + order.orderNo() + " 已通过后台审核，等待发货。",
                        order.id(), order.status().name()
                );
                notifyUser(
                        order.superiorId(), "ORDER_ADMIN_APPROVED", "下级订单审核通过",
                        "订单 " + order.orderNo() + " 已通过后台审核。",
                        order.id(), order.status().name()
                );
            }
            case ADMIN_REJECTED -> {
                notifyUser(
                        order.buyerId(), "ORDER_ADMIN_REJECTED", "订单后台审核未通过",
                        "订单 " + order.orderNo() + " 未通过后台审核，请查看原因。",
                        order.id(), order.status().name()
                );
                notifyUser(
                        order.superiorId(), "ORDER_ADMIN_REJECTED", "下级订单后台审核未通过",
                        "订单 " + order.orderNo() + " 未通过后台审核。",
                        order.id(), order.status().name()
                );
            }
            case COMPLETED -> {
                notifyUser(
                        order.buyerId(), "ORDER_COMPLETED", "订单已完成",
                        "订单 " + order.orderNo() + " 已完成，会员任务将在后台异步核算。",
                        order.id(), order.status().name()
                );
                notifyUser(
                        order.superiorId(), "DIRECT_ORDER_COMPLETED", "直属下级订单已完成",
                        "订单 " + order.orderNo() + " 已完成，符合条件时将计入演示积分与资格。",
                        order.id(), order.status().name()
                );
            }
            case CANCELLED -> notifyUser(
                    order.superiorId(), "ORDER_CANCELLED", "下级已取消订单",
                    "订单 " + order.orderNo() + " 已由买家取消。",
                    order.id(), order.status().name()
            );
            default -> {
                // Notifications are only emitted for user-visible lifecycle milestones.
            }
        }
    }

    private void notifyUser(long userId, String template, String title, String content,
                            long orderId, String state) {
        notifications.insertUser(
                userId,
                template,
                title,
                content,
                "ORDER",
                Long.toString(orderId),
                "order-notification:" + orderId + ":" + state + ":" + userId
        );
    }

    private static ProductView productView(ProductRow row) {
        return new ProductView(
                row.productId,
                row.name,
                row.subtitle,
                row.coverUrl,
                row.salesScene,
                row.skuId,
                row.skuName,
                row.priceFen,
                row.marketPriceFen,
                row.inventory
        );
    }

    private static ContentView contentView(ContentRow row) {
        return new ContentView(row.id, row.contentType, row.title, row.summary, row.targetUrl, row.bodyHtml);
    }

    private static CartItemView cartView(CartRow row) {
        return new CartItemView(
                row.id,
                row.skuId,
                row.productName,
                row.skuName,
                row.coverUrl,
                row.priceFen,
                row.quantity,
                Boolean.TRUE.equals(row.selected),
                row.inventory
        );
    }

    private static OrderView orderView(OrderRow row) {
        return new OrderView(
                row.id,
                row.orderNo,
                row.buyerUserId,
                row.superiorUserId,
                row.totalAmountFen,
                row.status,
                row.reason,
                instant(row.createdAt)
        );
    }

    private static OrderItemView orderItemView(OrderItemRow item) {
        return new OrderItemView(
                item.skuId,
                item.productName,
                item.skuName,
                item.coverUrl,
                item.salesScene,
                item.unitPriceFen,
                item.quantity,
                item.subtotalFen
        );
    }

    private static String addressJson(AddressSnapshot value) {
        return "{"
                + "\"recipientName\":\"" + escape(value.recipientName()) + "\","
                + "\"phone\":\"" + escape(value.phone()) + "\","
                + "\"province\":\"" + escape(value.province()) + "\","
                + "\"city\":\"" + escape(value.city()) + "\","
                + "\"district\":\"" + escape(value.district()) + "\","
                + "\"detailAddress\":\"" + escape(value.detailAddress()) + "\","
                + "\"postalCode\":\"" + escape(value.postalCode()) + "\""
                + "}";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(BUSINESS_ZONE);
    }

    private static LocalDateTime local(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, BUSINESS_ZONE);
    }
}

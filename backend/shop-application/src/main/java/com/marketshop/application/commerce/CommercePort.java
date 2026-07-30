package com.marketshop.application.commerce;

import com.marketshop.application.commerce.CommerceUseCase.AddressSnapshot;
import com.marketshop.application.commerce.CommerceUseCase.CartItemView;
import com.marketshop.application.commerce.CommerceUseCase.CategoryView;
import com.marketshop.application.commerce.CommerceUseCase.ContentView;
import com.marketshop.application.commerce.CommerceUseCase.OrderDetail;
import com.marketshop.application.commerce.CommerceUseCase.OrderView;
import com.marketshop.application.commerce.CommerceUseCase.ProductDetail;
import com.marketshop.application.commerce.CommerceUseCase.ProductView;
import com.marketshop.application.commerce.CommerceUseCase.ShipmentCommand;
import com.marketshop.application.commerce.CommerceUseCase.UpdateProductCommand;
import com.marketshop.domain.trade.Order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CommercePort {

    List<ProductView> products();

    Optional<ProductDetail> product(long productId);

    List<CategoryView> categories();

    void updateProduct(UpdateProductCommand command);

    List<ContentView> contents();

    Optional<ContentView> content(long contentId);

    List<CartItemView> cart(long userId);

    void setCartItem(long userId, long skuId, int quantity, boolean selected);

    CheckoutContext checkoutContext(long userId, List<ItemQuantity> items);

    Optional<OrderView> findByClientRequest(long userId, String clientRequestId);

    OrderView saveSubmitted(Order order, AddressSnapshot address, String source, String clientRequestId,
                            List<CheckoutSku> checkoutSkus);

    List<OrderView> buyerOrders(long userId);

    List<OrderView> superiorOrders(long userId);

    List<OrderView> adminOrders(String status);

    Optional<OrderAggregate> loadOrder(long orderId);

    OrderDetail order(long orderId);

    void persistTransition(Order order, int expectedVersion, String eventType);

    void persistShipment(Order order, int expectedVersion, long adminId, ShipmentCommand shipment);

    int autoReceiveDays();

    record ItemQuantity(long skuId, int quantity) {
    }

    record CheckoutSku(long productId, long skuId, String productName, String skuName, String coverUrl,
                       String salesScene, long unitPriceFen, int requestedQuantity, int availableQuantity) {
    }

    record CheckoutContext(long superiorUserId, List<CheckoutSku> skus) {
    }

    record AggregateLine(long skuId, String skuName, long unitPriceFen, int quantity, String salesScene) {
    }

    record OrderAggregate(long id, String orderNo, long buyerUserId, long superiorUserId,
                          long totalAmountFen, String status, Instant superiorConfirmedAt,
                          Instant adminReviewedAt, Instant shippedAt, Instant autoReceiveAt,
                          Instant completedAt, String reason, int version, List<AggregateLine> lines) {
    }
}

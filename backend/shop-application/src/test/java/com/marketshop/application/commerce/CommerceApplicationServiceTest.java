package com.marketshop.application.commerce;

import com.marketshop.application.commerce.CommercePort.CheckoutContext;
import com.marketshop.application.commerce.CommercePort.ItemQuantity;
import com.marketshop.application.commerce.CommercePort.OrderAggregate;
import com.marketshop.application.commerce.CommerceUseCase.AddressSnapshot;
import com.marketshop.application.commerce.CommerceUseCase.CartItemView;
import com.marketshop.application.commerce.CommerceUseCase.CategoryView;
import com.marketshop.application.commerce.CommerceUseCase.ContentView;
import com.marketshop.application.commerce.CommerceUseCase.OrderDetail;
import com.marketshop.application.commerce.CommerceUseCase.OrderItemView;
import com.marketshop.application.commerce.CommerceUseCase.OrderView;
import com.marketshop.application.commerce.CommerceUseCase.ProductDetail;
import com.marketshop.application.commerce.CommerceUseCase.ProductView;
import com.marketshop.application.commerce.CommerceUseCase.ShipmentCommand;
import com.marketshop.application.commerce.CommerceUseCase.UpdateProductCommand;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.domain.trade.Order;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommerceApplicationServiceTest {

    @Test
    void allowsOnlyBuyerOrDirectSuperiorToReadMemberOrderDetail() {
        var service = new CommerceApplicationService(new CommercePortFake(detail()));

        assertThat(service.order(10, 100).order().id()).isEqualTo(100);
        assertThat(service.order(20, 100).order().id()).isEqualTo(100);
        assertThatThrownBy(() -> service.order(30, 100))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("无权");
    }

    @Test
    void keepsAdministrativeOrderReadAsAnExplicitApplicationPath() {
        var service = new CommerceApplicationService(new CommercePortFake(detail()));

        assertThat(service.adminOrder(100).order().id()).isEqualTo(100);
    }

    private static OrderDetail detail() {
        Instant now = Instant.now();
        return new OrderDetail(
                new OrderView(100, "MS100", 10, 20, 2_980, "SHIPPED", null, now),
                "{\"recipientName\":\"张三\"}",
                List.of(new OrderItemView(1, "体验商品", "默认规格", null, "UPGRADE", 2_980, 1, 2_980)),
                null,
                now,
                now,
                now.plusSeconds(86_400),
                null
        );
    }

    private static final class CommercePortFake implements CommercePort {
        private final OrderDetail detail;

        private CommercePortFake(OrderDetail detail) {
            this.detail = detail;
        }

        @Override
        public List<ProductView> products() {
            return List.of();
        }

        @Override
        public Optional<ProductDetail> product(long productId) {
            return Optional.empty();
        }

        @Override
        public List<CategoryView> categories() {
            return List.of();
        }

        @Override
        public void updateProduct(UpdateProductCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ContentView> contents() {
            return List.of();
        }

        @Override
        public Optional<ContentView> content(long contentId) {
            return Optional.empty();
        }

        @Override
        public List<CartItemView> cart(long userId) {
            return List.of();
        }

        @Override
        public void setCartItem(long userId, long skuId, int quantity, boolean selected) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CheckoutContext checkoutContext(long userId, List<ItemQuantity> items) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<OrderView> findByClientRequest(long userId, String clientRequestId) {
            return Optional.empty();
        }

        @Override
        public OrderView saveSubmitted(
                Order order,
                AddressSnapshot address,
                String source,
                String clientRequestId,
                List<CheckoutSku> checkoutSkus
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<OrderView> buyerOrders(long userId) {
            return List.of();
        }

        @Override
        public List<OrderView> superiorOrders(long userId) {
            return List.of();
        }

        @Override
        public List<OrderView> adminOrders(String status) {
            return List.of();
        }

        @Override
        public Optional<OrderAggregate> loadOrder(long orderId) {
            return Optional.empty();
        }

        @Override
        public OrderDetail order(long orderId) {
            return detail;
        }

        @Override
        public void persistTransition(Order order, int expectedVersion, String eventType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void persistShipment(
                Order order,
                int expectedVersion,
                long adminId,
                ShipmentCommand shipment
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int autoReceiveDays() {
            return 7;
        }
    }
}

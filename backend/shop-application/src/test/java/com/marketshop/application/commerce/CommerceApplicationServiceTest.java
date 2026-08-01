package com.marketshop.application.commerce;

import com.marketshop.application.commerce.CommercePort.CheckoutContext;
import com.marketshop.application.commerce.CommercePort.ItemQuantity;
import com.marketshop.application.commerce.CommercePort.OrderAggregate;
import com.marketshop.application.commerce.CommerceUseCase.AddressSnapshot;
import com.marketshop.application.commerce.CommerceUseCase.CartItemView;
import com.marketshop.application.commerce.CommerceUseCase.CategoryView;
import com.marketshop.application.commerce.CommerceUseCase.ContentView;
import com.marketshop.application.commerce.CommerceUseCase.OrderDetail;
import com.marketshop.application.commerce.CommerceUseCase.OrderActorCapabilities;
import com.marketshop.application.commerce.CommerceUseCase.OrderItemView;
import com.marketshop.application.commerce.CommerceUseCase.OrderView;
import com.marketshop.application.commerce.CommerceUseCase.ProductDetail;
import com.marketshop.application.commerce.CommerceUseCase.ProductView;
import com.marketshop.application.commerce.CommerceUseCase.ShipmentCommand;
import com.marketshop.application.commerce.CommerceUseCase.UpdateProductCommand;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.domain.trade.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommerceApplicationServiceTest {

    @Test
    void allowsOnlyBuyerOrDirectSuperiorToReadMemberOrderDetail() {
        var service = new CommerceApplicationService(new CommercePortFake(detail("SHIPPED")));

        assertThat(service.order(10, 100).order().id()).isEqualTo(100);
        assertThat(service.order(20, 100).order().id()).isEqualTo(100);
        assertThatThrownBy(() -> service.order(30, 100))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("无权");
    }

    @ParameterizedTest(name = "{0} sees authoritative actions in {2}")
    @MethodSource("memberCapabilityMatrix")
    void computesBuyerAndSuperiorCapabilitiesForEveryOrderState(
            String actor,
            long actorUserId,
            String status,
            OrderActorCapabilities expected
    ) {
        var service = new CommerceApplicationService(new CommercePortFake(detail(status)));

        assertThat(service.order(actorUserId, 100).actorCapabilities())
                .as("%s capabilities in %s", actor, status)
                .isEqualTo(expected);
    }

    @Test
    void keepsAdministrativeOrderReadAsAnExplicitApplicationPath() {
        var service = new CommerceApplicationService(new CommercePortFake(detail("SHIPPED")));

        assertThat(service.adminOrder(100).order().id()).isEqualTo(100);
        assertThat(service.adminOrder(100).actorCapabilities()).isEqualTo(OrderActorCapabilities.none());
    }

    @Test
    void unknownPersistedStatusBecomesStableDomainErrorForWriteActions() {
        CommercePortFake port = new CommercePortFake(detail("SHIPPED"));
        Instant now = Instant.now();
        port.aggregate = Optional.of(new OrderAggregate(
                100,
                "MS100",
                10,
                20,
                2_980,
                "FUTURE_STATUS",
                null,
                null,
                now,
                now.plusSeconds(86_400),
                null,
                null,
                0,
                List.of(new CommercePort.AggregateLine(1, "体验商品", 2_980, 1, "UPGRADE"))
        ));

        assertThatThrownBy(() -> new CommerceApplicationService(port)
                .receive(10, 100))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ORDER_STATUS_UNSUPPORTED"));
    }

    private static Stream<Arguments> memberCapabilityMatrix() {
        return Stream.of(
                "PENDING_SUPERIOR",
                "SUPERIOR_REJECTED",
                "PENDING_ADMIN_REVIEW",
                "ADMIN_REJECTED",
                "PENDING_SHIPMENT",
                "SHIPPED",
                "COMPLETED",
                "CANCELLED"
        ).flatMap(status -> Stream.of(
                Arguments.of(
                        "buyer",
                        10L,
                        status,
                        new OrderActorCapabilities(
                                "SHIPPED".equals(status),
                                "PENDING_SUPERIOR".equals(status),
                                "PENDING_SUPERIOR".equals(status),
                                false
                        )
                ),
                Arguments.of(
                        "superior",
                        20L,
                        status,
                        new OrderActorCapabilities(
                                false,
                                false,
                                false,
                                "PENDING_SUPERIOR".equals(status)
                        )
                )
        ));
    }

    private static OrderDetail detail(String status) {
        Instant now = Instant.now();
        return new OrderDetail(
                new OrderView(100, "MS100", 10, 20, 2_980, status, null, now),
                "{\"recipientName\":\"张三\"}",
                List.of(new OrderItemView(1, "体验商品", "默认规格", null, "UPGRADE", 2_980, 1, 2_980)),
                null,
                now,
                now,
                now.plusSeconds(86_400),
                null,
                OrderActorCapabilities.none()
        );
    }

    private static final class CommercePortFake implements CommercePort {
        private final OrderDetail detail;
        private Optional<OrderAggregate> aggregate = Optional.empty();

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
            return aggregate;
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

package com.marketshop.domain.trade;

import com.marketshop.domain.shared.DomainException;
import com.marketshop.domain.shared.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private final Instant now = Instant.parse("2026-07-26T10:00:00Z");

    @Test
    void completesOnlyThroughTheApprovedOfflineFlow() {
        Order order = newOrder();

        order.superiorConfirm(now);
        order.adminApprove(now.plus(1, ChronoUnit.HOURS));
        order.ship(now.plus(2, ChronoUnit.HOURS), now.plus(7, ChronoUnit.DAYS));
        order.receive(now.plus(3, ChronoUnit.DAYS));

        assertThat(order.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.version()).isEqualTo(4);
        assertThat(order.totalAmount()).isEqualTo(new Money(199_800));
    }

    @Test
    void rejectsSkippingSuperiorConfirmation() {
        Order order = newOrder();

        assertThatThrownBy(() -> order.adminApprove(now))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("ORDER_STATUS_CONFLICT");
    }

    @Test
    void cancelledOrderIsTerminal() {
        Order order = newOrder();
        order.cancel("用户不再需要");

        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThatThrownBy(() -> order.superiorConfirm(now))
                .isInstanceOf(DomainException.class);
    }

    private Order newOrder() {
        return Order.submit(
                "MS202607260001",
                101,
                100,
                List.of(new OrderLine(1, "超级会员任务商品", new Money(199_800), 1, "UPGRADE"))
        );
    }
}

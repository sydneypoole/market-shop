package com.marketshop.application.aftersale;

import com.marketshop.application.aftersale.AfterSalePort.OrderEligibility;
import com.marketshop.application.aftersale.AfterSalePort.TransitionData;
import com.marketshop.application.aftersale.AfterSaleUseCase.ApplyCommand;
import com.marketshop.application.aftersale.AfterSaleUseCase.View;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AfterSaleApplicationServiceTest {

    @Test
    void normalizesClientRequestIdBeforeBothIdempotencyLookupAndPersistence() {
        AfterSalePortFake port = new AfterSalePortFake();

        View result = new AfterSaleApplicationService(port).apply(
                10,
                new ApplyCommand(8, "  aftersale-request-8  ", "refund_only", "  商品破损  ", null)
        );

        assertThat(result.id()).isEqualTo(21L);
        assertThat(port.lookedUpClientRequestId).isEqualTo("aftersale-request-8");
        assertThat(port.createdCommand.clientRequestId()).isEqualTo("aftersale-request-8");
        assertThat(port.createdCommand.type()).isEqualTo("REFUND_ONLY");
        assertThat(port.createdCommand.reason()).isEqualTo("商品破损");
    }

    @Test
    void rejectsClientRequestIdAboveTheDatabaseContractBeforeReadingTheOrder() {
        AfterSalePortFake port = new AfterSalePortFake();

        assertThatThrownBy(() -> new AfterSaleApplicationService(port).apply(
                10,
                new ApplyCommand(8, "r".repeat(81), "REFUND_ONLY", "商品破损", null)
        ))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CLIENT_REQUEST_INVALID"));
        assertThat(port.orderEligibilityCalls).isZero();
    }

    @Test
    void applyRejectsWhenACompletedAftersaleAlreadyExists() {
        AfterSalePortFake port = new AfterSalePortFake();
        port.completedAfterSaleCount = 1;

        assertThatThrownBy(() -> new AfterSaleApplicationService(port).apply(
                10,
                new ApplyCommand(8, "aftersale-request-9", "REFUND_ONLY", "商品破损", null)
        ))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AFTERSALE_ALREADY_COMPLETED"));
        assertThat(port.createdCommand).isNull();
    }

    private static final class AfterSalePortFake implements AfterSalePort {
        private String lookedUpClientRequestId;
        private ApplyCommand createdCommand;
        private int orderEligibilityCalls;
        private int completedAfterSaleCount;

        @Override
        public Optional<OrderEligibility> orderEligibility(long orderId) {
            orderEligibilityCalls++;
            return Optional.of(new OrderEligibility(orderId, 10, "SHIPPED", null, 0, completedAfterSaleCount));
        }

        @Override
        public Optional<View> findByClientRequest(long userId, String clientRequestId) {
            lookedUpClientRequestId = clientRequestId;
            return Optional.empty();
        }

        @Override
        public View create(long userId, String afterSaleNo, ApplyCommand command) {
            createdCommand = command;
            return view();
        }

        @Override
        public List<View> userAfterSales(long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<View> superiorAfterSales(long superiorUserId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<View> adminAfterSales(String status) {
            throw new UnsupportedOperationException();
        }

        @Override
        public View load(long afterSaleId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void transition(long afterSaleId, String expectedStatus, String targetStatus, TransitionData data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int afterSaleWindowDays() {
            return 7;
        }

        private static View view() {
            return new View(
                    21,
                    "AS21",
                    8,
                    10,
                    20,
                    "REFUND_ONLY",
                    "PENDING_ADMIN_REVIEW",
                    "商品破损",
                    null,
                    null,
                    null,
                    null,
                    Instant.EPOCH,
                    null
            );
        }
    }
}

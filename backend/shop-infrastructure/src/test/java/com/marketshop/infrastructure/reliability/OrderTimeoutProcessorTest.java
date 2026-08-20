package com.marketshop.infrastructure.reliability;

import com.marketshop.application.commerce.CommercePort;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.domain.trade.Order;
import com.marketshop.domain.trade.OrderStatus;
import com.marketshop.infrastructure.persistence.mapper.CommerceMapper;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderItemRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderTimeoutProcessorTest {

    @Test
    void pendingSuperiorTimeoutCancelsThroughPersistTransition() {
        CommerceMapper mapper = mock(CommerceMapper.class);
        CommercePort port = mock(CommercePort.class);
        when(mapper.lockDueOrderTimeout()).thenReturn(order("PENDING_SUPERIOR", 2));
        when(mapper.orderItems(80L)).thenReturn(List.of(orderItem()));

        boolean processed = new OrderTimeoutProcessor(mapper, port).processNext();

        assertThat(processed).isTrue();
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(port).persistTransition(captor.capture(), eq(2), eq("ORDER_CANCELLED"));
        assertThat(captor.getValue().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(captor.getValue().reason()).contains("超时");
    }

    @Test
    void pendingAdminReviewTimeoutRejectsThroughPersistTransition() {
        CommerceMapper mapper = mock(CommerceMapper.class);
        CommercePort port = mock(CommercePort.class);
        OrderRow row = order("PENDING_ADMIN_REVIEW", 3);
        row.superiorConfirmedAt = LocalDateTime.now().minusDays(8);
        when(mapper.lockDueOrderTimeout()).thenReturn(row);
        when(mapper.orderItems(80L)).thenReturn(List.of(orderItem()));

        boolean processed = new OrderTimeoutProcessor(mapper, port).processNext();

        assertThat(processed).isTrue();
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(port).persistTransition(captor.capture(), eq(3), eq("ORDER_ADMIN_DECIDED"));
        assertThat(captor.getValue().status()).isEqualTo(OrderStatus.ADMIN_REJECTED);
        assertThat(captor.getValue().reason()).contains("超时");
    }

    @Test
    void pendingShipmentTimeoutClosesThroughPersistTransition() {
        CommerceMapper mapper = mock(CommerceMapper.class);
        CommercePort port = mock(CommercePort.class);
        OrderRow row = order("PENDING_SHIPMENT", 4);
        row.adminReviewedAt = LocalDateTime.now().minusDays(8);
        when(mapper.lockDueOrderTimeout()).thenReturn(row);
        when(mapper.orderItems(80L)).thenReturn(List.of(orderItem()));

        boolean processed = new OrderTimeoutProcessor(mapper, port).processNext();

        assertThat(processed).isTrue();
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(port).persistTransition(captor.capture(), eq(4), eq("ORDER_CANCELLED"));
        assertThat(captor.getValue().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(captor.getValue().reason()).contains("超时");
    }

    @Test
    void missingDueOrderStopsTheBatch() {
        CommerceMapper mapper = mock(CommerceMapper.class);
        CommercePort port = mock(CommercePort.class);
        when(mapper.lockDueOrderTimeout()).thenReturn(null);

        assertThat(new OrderTimeoutProcessor(mapper, port).processNext()).isFalse();
        verify(port, never()).persistTransition(any(), anyInt(), anyString());
    }

    @Test
    void missingOrMalformedOrderTimerSnapshotFailsClosed() {
        CommerceMapper mapper = mock(CommerceMapper.class);
        CommercePort port = mock(CommercePort.class);
        OrderRow row = order("PENDING_SUPERIOR", 2);
        row.timerRuleCode = null;
        when(mapper.lockDueOrderTimeout()).thenReturn(row);

        assertThatThrownBy(() -> new OrderTimeoutProcessor(mapper, port).processNext())
                .isInstanceOfSatisfying(DomainException.class, exception ->
                        assertThat(exception.code()).isEqualTo("ORDER_TIMER_SETTINGS_INVALID"));
        verify(port, never()).persistTransition(any(), anyInt(), anyString());
    }

    private static OrderRow order(String status, int version) {
        OrderRow row = new OrderRow();
        row.id = 80L;
        row.orderNo = "MS80";
        row.buyerUserId = 42L;
        row.superiorUserId = 7L;
        row.totalAmountFen = 199_800L;
        row.status = status;
        row.version = version;
        row.timerRuleCode = "ORDER_TIMERS";
        row.timerRuleType = "ORDER_TIMER";
        row.timerParametersJson = timerParameters();
        return row;
    }

    private static String timerParameters() {
        return "{\"autoReceiveDays\":7,\"afterSaleDaysAfterCompletion\":7,"
                + "\"pendingSuperiorTimeoutDays\":7,\"pendingAdminReviewTimeoutDays\":7,"
                + "\"pendingShipmentTimeoutDays\":7,\"awaitingReturnTimeoutDays\":15,"
                + "\"returnShippedTimeoutDays\":15,\"offlineRefundTimeoutDays\":7,"
                + "\"buyerRefundConfirmTimeoutDays\":7,\"proofRetentionDays\":180,"
                + "\"maxProofFiles\":3,\"maxProofSizeBytes\":8388608}";
    }

    private static OrderItemRow orderItem() {
        OrderItemRow row = new OrderItemRow();
        row.skuId = 11L;
        row.skuName = "升级规格";
        row.unitPriceFen = 199_800L;
        row.quantity = 1;
        row.salesScene = "UPGRADE";
        return row;
    }
}

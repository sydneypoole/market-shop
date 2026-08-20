package com.marketshop.infrastructure.reliability;

import com.marketshop.application.aftersale.AfterSalePort;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.AfterSaleMapper;
import com.marketshop.infrastructure.persistence.model.AfterSalePersistenceModels.AfterSaleRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AftersaleTimeoutProcessorTest {

    @Test
    void awaitingReturnUsesTheOrderSnapshotBeforeCancelling() {
        AfterSaleMapper mapper = mock(AfterSaleMapper.class);
        AfterSalePort port = mock(AfterSalePort.class);
        when(mapper.lockDueAftersaleTimeout()).thenReturn(row("AWAITING_RETURN", 16));

        assertThat(new AftersaleTimeoutProcessor(mapper, port).processNext()).isTrue();

        ArgumentCaptor<AfterSalePort.TransitionData> data =
                ArgumentCaptor.forClass(AfterSalePort.TransitionData.class);
        verify(port).transition(eq(21L), eq("AWAITING_RETURN"), eq("CANCELLED"), data.capture());
        assertThat(data.getValue().adminReason()).contains("超时");
    }

    @Test
    void buyerRefundConfirmationUsesTheSnapshotAndCompletesWithAnEvent() {
        AfterSaleMapper mapper = mock(AfterSaleMapper.class);
        AfterSalePort port = mock(AfterSalePort.class);
        when(mapper.lockDueAftersaleTimeout()).thenReturn(row("PENDING_BUYER_REFUND_CONFIRMATION", 8));

        assertThat(new AftersaleTimeoutProcessor(mapper, port).processNext()).isTrue();

        ArgumentCaptor<AfterSalePort.TransitionData> data =
                ArgumentCaptor.forClass(AfterSalePort.TransitionData.class);
        verify(port).transition(
                eq(21L), eq("PENDING_BUYER_REFUND_CONFIRMATION"), eq("COMPLETED"), data.capture());
        assertThat(data.getValue().emitCompletedEvent()).isTrue();
        assertThat(data.getValue().completedAt()).isNotNull();
    }

    @Test
    void everyAfterSaleTimeoutStateUsesItsPersistedDueRow() {
        String[][] transitions = {
                {"AWAITING_RETURN", "CANCELLED"},
                {"RETURN_SHIPPED", "PENDING_OFFLINE_REFUND"},
                {"PENDING_OFFLINE_REFUND", "PENDING_BUYER_REFUND_CONFIRMATION"},
                {"PENDING_BUYER_REFUND_CONFIRMATION", "COMPLETED"}
        };
        for (String[] transition : transitions) {
            AfterSaleMapper mapper = mock(AfterSaleMapper.class);
            AfterSalePort port = mock(AfterSalePort.class);
            when(mapper.lockDueAftersaleTimeout()).thenReturn(row(transition[0], 16));

            assertThat(new AftersaleTimeoutProcessor(mapper, port).processNext()).isTrue();
            verify(port).transition(eq(21L), eq(transition[0]), eq(transition[1]),
                    org.mockito.ArgumentMatchers.any(AfterSalePort.TransitionData.class));
        }
    }

    @Test
    void missingSnapshotFailsClosedWithoutTransition() {
        AfterSaleMapper mapper = mock(AfterSaleMapper.class);
        AfterSalePort port = mock(AfterSalePort.class);
        AfterSaleRow row = row("RETURN_SHIPPED", 16);
        row.timerRuleCode = null;
        when(mapper.lockDueAftersaleTimeout()).thenReturn(row);

        assertThatThrownBy(() -> new AftersaleTimeoutProcessor(mapper, port).processNext())
                .isInstanceOfSatisfying(DomainException.class, exception ->
                        assertThat(exception.code()).isEqualTo("ORDER_TIMER_SETTINGS_INVALID"));
        verify(port, never()).transition(anyLong(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(AfterSalePort.TransitionData.class));
    }

    private static AfterSaleRow row(String status, int daysAgo) {
        AfterSaleRow row = new AfterSaleRow();
        row.id = 21L;
        row.status = status;
        row.stateEnteredAt = LocalDateTime.now().minusDays(daysAgo);
        row.stateDueAt = LocalDateTime.now().minusDays(1);
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
}

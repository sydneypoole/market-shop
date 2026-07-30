package com.marketshop.application.commerce;

import com.marketshop.application.commerce.CommerceUseCase.OrderView;
import com.marketshop.application.commerce.CommerceUseCase.ShipmentCommand;
import com.marketshop.application.commerce.OrderOperationsUseCase.BatchShipment;
import com.marketshop.application.commerce.OrderOperationsUseCase.OrderPage;
import com.marketshop.application.commerce.OrderOperationsUseCase.OrderSearchQuery;
import com.marketshop.application.operation.OperationSettingsPort;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderOperationsApplicationServiceTest {

    @Mock
    private OrderOperationsPort port;

    @Mock
    private CommerceUseCase commerce;

    @Mock
    private OperationSettingsPort settings;

    @InjectMocks
    private OrderOperationsApplicationService service;

    @Test
    void normalizesSearchAndCapsPageSize() {
        OrderSearchQuery query = new OrderSearchQuery(
                "  MS2026  ", 1L, 2L, " shipped ", null, null, -3, 5_000
        );
        when(port.search(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new OrderPage(List.of(), 0, 1, 500));

        service.search(query);

        ArgumentCaptor<OrderSearchQuery> captor = ArgumentCaptor.forClass(OrderSearchQuery.class);
        verify(port).search(captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(new OrderSearchQuery("MS2026", 1L, 2L, "SHIPPED", null, null, 1, 500));
    }

    @Test
    void csvExportNeutralizesSpreadsheetFormulaInjection() {
        OrderView order = new OrderView(
                9, "=HYPERLINK(\"https://invalid\")", 1, 2, 19_980,
                "COMPLETED", "+cmd", Instant.parse("2026-07-30T00:00:00Z")
        );
        when(port.search(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new OrderPage(List.of(order), 1, 1, 10_000));

        String csv = service.exportCsv(new OrderSearchQuery(null, null, null, null, null, null, 1, 20));

        assertThat(csv).startsWith("\uFEFF");
        assertThat(csv).contains("\"'=HYPERLINK(\"\"https://invalid\"\")\"");
        assertThat(csv).contains("\"'+cmd\"");
    }

    @Test
    void batchShipmentReturnsPerOrderPartialSuccess() {
        ShipmentCommand shipment = new ShipmentCommand("SF", "顺丰速运", "SF100");
        doAnswer(invocation -> {
            if (invocation.getArgument(1, Long.class) == 2L) {
                throw new DomainException("ORDER_STATE_CONFLICT", "订单状态不允许发货");
            }
            return null;
        }).when(commerce).ship(eq(8L), anyLong(), eq(shipment));

        var result = service.batchShip(8, List.of(
                new BatchShipment(1, shipment),
                new BatchShipment(2, shipment)
        ));

        assertThat(result).extracting(item -> item.orderId() + ":" + item.success())
                .containsExactly("1:true", "2:false");
        assertThat(result.get(1).message()).contains("状态不允许");
    }

    @Test
    void rejectsBlankOrderNote() {
        assertThatThrownBy(() -> service.addNote(8, 1, "  "))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("1 到 1000");
    }
}

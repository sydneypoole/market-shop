package com.marketshop.infrastructure.reliability;

import com.marketshop.infrastructure.persistence.mapper.CommerceMapper;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderItemRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoReceiveProcessorTest {

    @Test
    void automaticReceiptCommitsCompletionSnapshotBeforeTheCompletedEvent() {
        CommerceMapper mapper = mock(CommerceMapper.class);
        OrderRow order = shippedOrder();
        when(mapper.lockDueAutoReceive()).thenReturn(order);
        when(mapper.orderItems(900L)).thenReturn(List.of(orderItem()));
        when(mapper.updateTransition(
                eq(900L), eq("COMPLETED"), nullable(LocalDateTime.class), nullable(LocalDateTime.class),
                nullable(LocalDateTime.class), nullable(LocalDateTime.class),
                any(LocalDateTime.class), eq(null), eq(5), eq(4)
        )).thenReturn(1);
        when(mapper.orderRuleSnapshotComplete(900L)).thenReturn(1);

        boolean processed = new AutoReceiveProcessor(mapper).processNext();

        assertThat(processed).isTrue();
        var sequence = inOrder(mapper);
        sequence.verify(mapper).updateTransition(
                eq(900L), eq("COMPLETED"), nullable(LocalDateTime.class), nullable(LocalDateTime.class),
                nullable(LocalDateTime.class), nullable(LocalDateTime.class),
                any(LocalDateTime.class), eq(null), eq(5), eq(4)
        );
        sequence.verify(mapper).snapshotApplicableRules(900L);
        sequence.verify(mapper).orderRuleSnapshotComplete(900L);
        sequence.verify(mapper).insertCompletedOutbox(anyString(), eq(900L), eq("AUTO_RECEIVE"));
    }

    private static OrderRow shippedOrder() {
        OrderRow row = new OrderRow();
        row.id = 900L;
        row.orderNo = "MS900";
        row.buyerUserId = 42L;
        row.superiorUserId = 7L;
        row.totalAmountFen = 199_800L;
        row.status = "SHIPPED";
        row.shippedAt = LocalDateTime.now().minusDays(8);
        row.autoReceiveAt = LocalDateTime.now().minusDays(1);
        row.version = 4;
        return row;
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

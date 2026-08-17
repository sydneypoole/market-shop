package com.marketshop.infrastructure.aftersale;

import com.marketshop.application.aftersale.AfterSalePort.TransitionData;
import com.marketshop.application.aftersale.AfterSaleUseCase.ApplyCommand;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.AfterSaleMapper;
import com.marketshop.infrastructure.persistence.mapper.AfterSaleMapper.InsertRow;
import com.marketshop.infrastructure.persistence.mapper.CommerceMapper;
import com.marketshop.infrastructure.persistence.mapper.NotificationMapper;
import com.marketshop.infrastructure.persistence.model.AfterSalePersistenceModels.AfterSaleRow;
import com.marketshop.infrastructure.persistence.model.AfterSalePersistenceModels.EligibilityRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderItemRow;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisAfterSaleAdapterTest {

    @Test
    void concurrentIdempotentInsertReturnsTheExistingAfterSaleWithoutNotifyingAgain() {
        AfterSaleMapper mapper = mock(AfterSaleMapper.class);
        NotificationMapper notifications = mock(NotificationMapper.class);
        CommerceMapper commerce = mock(CommerceMapper.class);
        AfterSaleRow existing = existingAfterSale();
        when(mapper.orderEligibility(8L)).thenReturn(eligibility(0, 0));
        when(mapper.insertAfterSale(
                any(), anyString(), anyLong(), anyLong(), anyString(), anyString(), any(), anyString()
        )).thenThrow(new DuplicateKeyException("idempotency race"));
        when(mapper.findByClientRequest(10L, "aftersale-request-8")).thenReturn(existing);

        var result = new MyBatisAfterSaleAdapter(mapper, notifications, commerce).create(
                10,
                "AS-NEW",
                new ApplyCommand(8, "aftersale-request-8", "REFUND_ONLY", "商品破损", null)
        );

        assertThat(result.id()).isEqualTo(21L);
        verify(mapper, never()).afterSale(anyLong());
        verify(notifications, never()).insertUser(
                anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()
        );
    }

    @Test
    void createLocksTheOrderBeforeInserting() {
        AfterSaleMapper mapper = mock(AfterSaleMapper.class);
        NotificationMapper notifications = mock(NotificationMapper.class);
        CommerceMapper commerce = mock(CommerceMapper.class);
        when(mapper.orderEligibility(8L)).thenReturn(eligibility(0, 0));
        when(mapper.insertAfterSale(
                any(), anyString(), anyLong(), anyLong(), anyString(), anyString(), any(), anyString()
        )).thenAnswer(invocation -> {
            InsertRow row = invocation.getArgument(0);
            row.id = 21L;
            return 1;
        });
        when(mapper.afterSale(21L)).thenReturn(existingAfterSale());

        new MyBatisAfterSaleAdapter(mapper, notifications, commerce).create(
                10,
                "AS-NEW",
                new ApplyCommand(8, "aftersale-request-8", "REFUND_ONLY", "商品破损", null)
        );

        InOrder order = inOrder(commerce, mapper);
        order.verify(commerce).lockOrderForUpdate(8L);
        order.verify(mapper).orderEligibility(8L);
        order.verify(mapper).insertAfterSale(
                any(), eq("AS-NEW"), eq(8L), eq(10L), eq("REFUND_ONLY"), eq("商品破损"), isNull(),
                eq("aftersale-request-8")
        );
    }

    @Test
    void createRejectsCompletedAfterSaleAfterLockingTheOrder() {
        AfterSaleMapper mapper = mock(AfterSaleMapper.class);
        NotificationMapper notifications = mock(NotificationMapper.class);
        CommerceMapper commerce = mock(CommerceMapper.class);
        when(mapper.orderEligibility(8L)).thenReturn(eligibility(0, 1));

        assertThatThrownBy(() -> new MyBatisAfterSaleAdapter(mapper, notifications, commerce).create(
                10,
                "AS-NEW",
                new ApplyCommand(8, "aftersale-request-10", "REFUND_ONLY", "商品破损", null)
        ))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AFTERSALE_ALREADY_COMPLETED"));
        verify(commerce).lockOrderForUpdate(8L);
        verify(mapper, never()).insertAfterSale(
                any(), anyString(), anyLong(), anyLong(), anyString(), anyString(), any(), anyString()
        );
        verify(notifications, never()).insertUser(
                anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()
        );
    }

    @Test
    void createRejectsActiveAfterSaleAfterLockingTheOrder() {
        AfterSaleMapper mapper = mock(AfterSaleMapper.class);
        NotificationMapper notifications = mock(NotificationMapper.class);
        CommerceMapper commerce = mock(CommerceMapper.class);
        when(mapper.orderEligibility(8L)).thenReturn(eligibility(1, 0));

        assertThatThrownBy(() -> new MyBatisAfterSaleAdapter(mapper, notifications, commerce).create(
                10,
                "AS-NEW",
                new ApplyCommand(8, "aftersale-request-11", "REFUND_ONLY", "商品破损", null)
        ))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AFTERSALE_ALREADY_EXISTS"));
        verify(commerce).lockOrderForUpdate(8L);
        verify(mapper, never()).insertAfterSale(
                any(), anyString(), anyLong(), anyLong(), anyString(), anyString(), any(), anyString()
        );
    }

    @Test
    void completedOrderUniqueCollisionRejectsASecondAftersale() {
        AfterSaleMapper mapper = mock(AfterSaleMapper.class);
        NotificationMapper notifications = mock(NotificationMapper.class);
        CommerceMapper commerce = mock(CommerceMapper.class);
        when(mapper.orderEligibility(8L)).thenReturn(eligibility(0, 0));
        when(mapper.insertAfterSale(
                any(), anyString(), anyLong(), anyLong(), anyString(), anyString(), any(), anyString()
        )).thenThrow(new DuplicateKeyException(
                "Duplicate entry '8' for key 'trade_after_sale.uk_after_sale_completed_order'"
        ));
        when(mapper.findByClientRequest(10L, "aftersale-request-9")).thenReturn(null);

        assertThatThrownBy(() -> new MyBatisAfterSaleAdapter(mapper, notifications, commerce).create(
                10,
                "AS-NEW",
                new ApplyCommand(8, "aftersale-request-9", "REFUND_ONLY", "商品破损", null)
        ))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AFTERSALE_ALREADY_COMPLETED"));
        verify(notifications, never()).insertUser(
                anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()
        );
    }

    @Test
    void completedTransitionLocksTheOrderBeforeUpdating() {
        AfterSaleMapper mapper = mock(AfterSaleMapper.class);
        NotificationMapper notifications = mock(NotificationMapper.class);
        CommerceMapper commerce = mock(CommerceMapper.class);
        AfterSaleRow current = existingAfterSale();
        current.status = "PENDING_BUYER_REFUND_CONFIRMATION";
        when(mapper.afterSale(21L)).thenReturn(current);
        when(mapper.transition(
                eq(21L), eq("PENDING_BUYER_REFUND_CONFIRMATION"), eq("COMPLETED"),
                any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(1);

        new MyBatisAfterSaleAdapter(mapper, notifications, commerce).transition(
                21L,
                "PENDING_BUYER_REFUND_CONFIRMATION",
                "COMPLETED",
                completedTransition()
        );

        InOrder order = inOrder(commerce, mapper);
        order.verify(commerce).lockOrderForUpdate(8L);
        order.verify(mapper).transition(
                eq(21L), eq("PENDING_BUYER_REFUND_CONFIRMATION"), eq("COMPLETED"),
                any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void completedReturnRefundRestocksEveryOrderItem() {
        AfterSaleMapper mapper = mock(AfterSaleMapper.class);
        NotificationMapper notifications = mock(NotificationMapper.class);
        CommerceMapper commerce = mock(CommerceMapper.class);
        AfterSaleRow current = existingAfterSale();
        current.type = "RETURN_REFUND";
        current.status = "PENDING_BUYER_REFUND_CONFIRMATION";
        when(mapper.afterSale(21L)).thenReturn(current);
        when(mapper.transition(
                eq(21L), eq("PENDING_BUYER_REFUND_CONFIRMATION"), eq("COMPLETED"),
                any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(1);
        when(commerce.orderItems(8L)).thenReturn(List.of(item(3L, 2), item(4L, 1)));
        when(commerce.restockAvailableInventory(anyLong(), anyInt())).thenReturn(1);

        new MyBatisAfterSaleAdapter(mapper, notifications, commerce).transition(
                21L,
                "PENDING_BUYER_REFUND_CONFIRMATION",
                "COMPLETED",
                completedTransition()
        );

        verify(commerce).restockAvailableInventory(3L, 2);
        verify(commerce).restockAvailableInventory(4L, 1);
    }

    @Test
    void completedRefundOnlyDoesNotRestock() {
        AfterSaleMapper mapper = mock(AfterSaleMapper.class);
        NotificationMapper notifications = mock(NotificationMapper.class);
        CommerceMapper commerce = mock(CommerceMapper.class);
        AfterSaleRow current = existingAfterSale();
        current.status = "PENDING_BUYER_REFUND_CONFIRMATION";
        when(mapper.afterSale(21L)).thenReturn(current);
        when(mapper.transition(
                eq(21L), eq("PENDING_BUYER_REFUND_CONFIRMATION"), eq("COMPLETED"),
                any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(1);

        new MyBatisAfterSaleAdapter(mapper, notifications, commerce).transition(
                21L,
                "PENDING_BUYER_REFUND_CONFIRMATION",
                "COMPLETED",
                completedTransition()
        );

        verify(commerce, never()).orderItems(anyLong());
        verify(commerce, never()).restockAvailableInventory(anyLong(), anyInt());
    }

    @Test
    void afterSaleWindowUsesOnlyAValidPublishedTimerRule() {
        AfterSaleMapper mapper = mock(AfterSaleMapper.class);
        MyBatisAfterSaleAdapter adapter = new MyBatisAfterSaleAdapter(
                mapper,
                mock(NotificationMapper.class),
                mock(CommerceMapper.class)
        );

        when(mapper.afterSaleWindowDays()).thenReturn(7);
        assertThat(adapter.afterSaleWindowDays()).isEqualTo(7);

        when(mapper.afterSaleWindowDays()).thenReturn(null, 0, 366);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(adapter::afterSaleWindowDays)
                    .isInstanceOf(DomainException.class)
                    .extracting("code")
                    .isEqualTo("ORDER_TIMER_SETTINGS_INVALID");
        }
    }

    private static TransitionData completedTransition() {
        return new TransitionData(null, null, null, null, null, null, Instant.parse("2026-08-18T00:00:00Z"), true);
    }

    private static OrderItemRow item(long skuId, int quantity) {
        OrderItemRow row = new OrderItemRow();
        row.skuId = skuId;
        row.quantity = quantity;
        return row;
    }

    private static EligibilityRow eligibility(int activeCount, int completedCount) {
        EligibilityRow row = new EligibilityRow();
        row.orderId = 8L;
        row.buyerUserId = 10L;
        row.status = "SHIPPED";
        row.activeAfterSaleCount = activeCount;
        row.completedAfterSaleCount = completedCount;
        return row;
    }

    private static AfterSaleRow existingAfterSale() {
        AfterSaleRow row = new AfterSaleRow();
        row.id = 21L;
        row.afterSaleNo = "AS21";
        row.orderId = 8L;
        row.applicantUserId = 10L;
        row.superiorUserId = 20L;
        row.type = "REFUND_ONLY";
        row.status = "PENDING_ADMIN_REVIEW";
        row.reason = "商品破损";
        row.createdAt = LocalDateTime.of(2026, 8, 9, 12, 0);
        return row;
    }
}

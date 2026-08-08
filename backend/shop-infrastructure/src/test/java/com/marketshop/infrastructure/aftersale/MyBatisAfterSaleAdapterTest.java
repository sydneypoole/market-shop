package com.marketshop.infrastructure.aftersale;

import com.marketshop.application.aftersale.AfterSaleUseCase.ApplyCommand;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.AfterSaleMapper;
import com.marketshop.infrastructure.persistence.mapper.NotificationMapper;
import com.marketshop.infrastructure.persistence.model.AfterSalePersistenceModels.AfterSaleRow;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisAfterSaleAdapterTest {

    @Test
    void concurrentIdempotentInsertReturnsTheExistingAfterSaleWithoutNotifyingAgain() {
        AfterSaleMapper mapper = mock(AfterSaleMapper.class);
        NotificationMapper notifications = mock(NotificationMapper.class);
        AfterSaleRow existing = existingAfterSale();
        when(mapper.insertAfterSale(
                any(), anyString(), anyLong(), anyLong(), anyString(), anyString(), any(), anyString()
        )).thenThrow(new DuplicateKeyException("idempotency race"));
        when(mapper.findByClientRequest(10L, "aftersale-request-8")).thenReturn(existing);

        var result = new MyBatisAfterSaleAdapter(mapper, notifications).create(
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
    void afterSaleWindowUsesOnlyAValidPublishedTimerRule() {
        AfterSaleMapper mapper = mock(AfterSaleMapper.class);
        MyBatisAfterSaleAdapter adapter = new MyBatisAfterSaleAdapter(
                mapper,
                mock(NotificationMapper.class)
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

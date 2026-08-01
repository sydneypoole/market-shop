package com.marketshop.infrastructure.aftersale;

import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.AfterSaleMapper;
import com.marketshop.infrastructure.persistence.mapper.NotificationMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MyBatisAfterSaleAdapterTest {

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
}

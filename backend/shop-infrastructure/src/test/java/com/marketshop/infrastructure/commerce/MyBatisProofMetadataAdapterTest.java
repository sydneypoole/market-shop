package com.marketshop.infrastructure.commerce;

import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.CommerceMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MyBatisProofMetadataAdapterTest {

    @Test
    void proofUploadLimitsFailClosedWhenThePublishedTimerRuleIsMissingOrInvalid() {
        CommerceMapper mapper = mock(CommerceMapper.class);
        MyBatisProofMetadataAdapter adapter = new MyBatisProofMetadataAdapter(mapper);

        when(mapper.maxProofFiles()).thenReturn(3);
        when(mapper.maxProofSizeBytes()).thenReturn(8L * 1024 * 1024);
        assertThat(adapter.maxFiles()).isEqualTo(3);
        assertThat(adapter.maxSizeBytes()).isEqualTo(8L * 1024 * 1024);

        when(mapper.maxProofFiles()).thenReturn(null, 0, 21);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertInvalidRule(adapter::maxFiles);
        }

        when(mapper.maxProofSizeBytes()).thenReturn(null, 1023L, 20L * 1024 * 1024 + 1);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertInvalidRule(adapter::maxSizeBytes);
        }
    }

    private static void assertInvalidRule(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("ORDER_TIMER_SETTINGS_INVALID");
    }
}

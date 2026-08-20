package com.marketshop.infrastructure.commerce;

import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.CommerceMapper;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.RuleRow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MyBatisProofMetadataAdapterTest {

    @Test
    void persistedRetentionFallbackKeepsProofLimitsAvailable() {
        CommerceMapper mapper = mock(CommerceMapper.class);
        MyBatisProofMetadataAdapter adapter = new MyBatisProofMetadataAdapter(mapper);
        String common = "{\"autoReceiveDaysAfterShipment\":7,\"afterSaleDaysAfterCompletion\":7,"
                + "\"pendingSuperiorTimeoutDays\":7,\"pendingAdminReviewTimeoutDays\":7,"
                + "\"pendingShipmentTimeoutDays\":7,";
        String[] persistedPayloads = {
                common + "\"maxProofFiles\":3,\"maxProofSizeBytes\":8388608}",
                common + "\"proofRetentionDays\":0,\"maxProofFiles\":3,\"maxProofSizeBytes\":8388608}",
                common + "\"proofRetentionDays\":3651,\"maxProofFiles\":3,\"maxProofSizeBytes\":8388608}"
        };

        for (String payload : persistedPayloads) {
            when(mapper.activeOrderTimerRule()).thenReturn(timer(payload));
            assertThat(adapter.retentionDays()).isEqualTo(180);
            assertThat(adapter.maxFiles()).isEqualTo(3);
            assertThat(adapter.maxSizeBytes()).isEqualTo(8L * 1024 * 1024);
        }
    }

    @Test
    void proofUploadLimitsFailClosedWhenThePublishedTimerRuleIsMissingOrInvalid() {
        CommerceMapper mapper = mock(CommerceMapper.class);
        MyBatisProofMetadataAdapter adapter = new MyBatisProofMetadataAdapter(mapper);

        when(mapper.activeOrderTimerRule()).thenReturn(timer(validParameters()));
        assertThat(adapter.maxFiles()).isEqualTo(3);
        when(mapper.activeOrderTimerRule()).thenReturn(timer(validParameters()));
        assertThat(adapter.maxSizeBytes()).isEqualTo(8L * 1024 * 1024);

        when(mapper.activeOrderTimerRule()).thenReturn(null, timer("{}"), timer("{\"maxProofFiles\":21}"));
        for (int attempt = 0; attempt < 3; attempt++) {
            assertInvalidRule(adapter::maxFiles);
        }

        when(mapper.activeOrderTimerRule()).thenReturn(null, timer("{}"), timer("{\"maxProofSizeBytes\":1023}"));
        for (int attempt = 0; attempt < 3; attempt++) {
            assertInvalidRule(adapter::maxSizeBytes);
        }
    }

    private static RuleRow timer(String parametersJson) {
        RuleRow row = new RuleRow();
        row.ruleCode = "ORDER_TIMERS";
        row.ruleType = "ORDER_TIMER";
        row.parametersJson = parametersJson;
        return row;
    }

    private static String validParameters() {
        return "{\"autoReceiveDaysAfterShipment\":7,\"afterSaleDaysAfterCompletion\":7,"
                + "\"pendingSuperiorTimeoutDays\":7,\"pendingAdminReviewTimeoutDays\":7,"
                + "\"pendingShipmentTimeoutDays\":7,\"proofRetentionDays\":180,"
                + "\"maxProofFiles\":3,\"maxProofSizeBytes\":8388608}";
    }

    private static void assertInvalidRule(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("ORDER_TIMER_SETTINGS_INVALID");
    }
}

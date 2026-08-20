package com.marketshop.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AfterSaleMapperContractTest {

    @Test
    void proofUploadLocksTheOwningAfterSaleRowBeforeTheCountCheck() throws Exception {
        String sql = String.join("\n", AfterSaleMapper.class
                .getMethod("lockAfterSaleForProofUpload", long.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("trade_after_sale a")
                .contains("a.applicant_user_id")
                .contains("a.status")
                .contains("WHERE a.id")
                .contains("FOR UPDATE");
    }

    @Test
    void aftersaleTimeoutSelectorUsesPersistedStateDueAtAndSnapshot() throws Exception {
        String sql = String.join("\n", AfterSaleMapper.class
                .getMethod("lockDueAftersaleTimeout")
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("state_due_at <= CURRENT_TIMESTAMP(3)")
                .contains("trade_order_rule_snapshot")
                .contains("operation_rule_version")
                .contains("state_entered_at")
                .contains("AWAITING_RETURN")
                .contains("RETURN_SHIPPED")
                .contains("PENDING_OFFLINE_REFUND")
                .contains("PENDING_BUYER_REFUND_CONFIRMATION")
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("LIMIT 1")
                .doesNotContain("JSON_EXTRACT");
    }

    @Test
    void aftersaleTransitionAdvancesOrClearsStateDueAtAtomically() throws Exception {
        String sql = String.join("\n", AfterSaleMapper.class
                .getMethod("transition", long.class, String.class, String.class, String.class, String.class,
                        String.class, String.class, Long.class, java.time.LocalDateTime.class,
                        java.time.LocalDateTime.class, Integer.class)
                .getAnnotation(Update.class)
                .value());
        assertThat(sql)
                .contains("state_entered_at = CURRENT_TIMESTAMP(3)")
                .contains("state_due_at")
                .contains("stateDueDays");
    }

    @Test
    void eligibilityCountsCompletedAfterSalesSeparatelyFromActiveOnes() throws Exception {
        String sql = String.join("\n", AfterSaleMapper.class
                .getMethod("orderEligibility", long.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("completed_after_sale_count")
                .contains("status = 'COMPLETED'")
                .contains("active_after_sale_count");
    }
}

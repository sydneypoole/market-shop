package com.marketshop.infrastructure.persistence.mapper;

import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderPo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceMapperContractTest {

    @Test
    void orderInsertAndDetailReadRoundTripTheBuyerNoteColumn() throws Exception {
        String insert = String.join("\n", CommerceMapper.class
                .getMethod("insertOrder", OrderPo.class)
                .getAnnotation(Insert.class)
                .value());
        String detail = String.join("\n", CommerceMapper.class
                .getMethod("order", long.class)
                .getAnnotation(Select.class)
                .value());
        String autoReceive = String.join("\n", CommerceMapper.class
                .getMethod("lockDueAutoReceive")
                .getAnnotation(Select.class)
                .value());

        assertThat(insert)
                .contains("buyer_note")
                .contains("#{buyerNote}");
        assertThat(detail).contains("buyer_note");
        assertThat(autoReceive).contains("buyer_note");
    }

    @Test
    void autoReceiveLockSkipsOrdersWithABlockingAftersale() throws Exception {
        String autoReceive = String.join("\n", CommerceMapper.class
                .getMethod("lockDueAutoReceive")
                .getAnnotation(Select.class)
                .value());

        assertThat(autoReceive)
                .contains("NOT EXISTS")
                .contains("trade_after_sale")
                .contains("REJECTED")
                .contains("CANCELLED")
                .doesNotContain("COMPLETED");
    }

    @Test
    void activeTimerProjectionReturnsTheWholeTypedPayload() throws Exception {
        String sql = String.join("\n", CommerceMapper.class
                .getMethod("activeOrderTimerRule")
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("CAST(parameters_json AS CHAR)")
                .contains("rule_code = 'ORDER_TIMERS'")
                .doesNotContain("rule_type = 'ORDER_TIMER'")
                .doesNotContain("JSON_EXTRACT");
    }

    @Test
    void parameterizedOrderTimeoutQueryDoesNotExtractRuleJsonInSql() throws Exception {
        String sql = String.join("\n", CommerceMapper.class
                .getMethod("lockDueOrderTimeoutWithPolicy", int.class, int.class, int.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("pendingSuperiorTimeoutDays")
                .contains("pendingAdminReviewTimeoutDays")
                .contains("pendingShipmentTimeoutDays")
                .doesNotContain("JSON_EXTRACT");
    }

    @Test
    void orderTimeoutLockUsesTypedPolicyArgumentsAndStatusAnchors() throws Exception {
        String sql = String.join("\n", CommerceMapper.class
                .getMethod("lockDueOrderTimeoutWithPolicy", int.class, int.class, int.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("PENDING_SUPERIOR")
                .contains("created_at")
                .contains("pendingSuperiorTimeoutDays")
                .contains("PENDING_ADMIN_REVIEW")
                .contains("superior_confirmed_at")
                .contains("pendingAdminReviewTimeoutDays")
                .contains("PENDING_SHIPMENT")
                .contains("admin_reviewed_at")
                .contains("pendingShipmentTimeoutDays")
                .contains("TIMESTAMPADD")
                .contains("FOR UPDATE SKIP LOCKED")
                .doesNotContain("JSON_EXTRACT");
    }
}

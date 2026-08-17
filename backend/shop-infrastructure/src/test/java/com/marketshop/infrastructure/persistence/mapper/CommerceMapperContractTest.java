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
    void orderTimeoutLockUsesStatusAnchorsAndFailsClosedWithoutTimerKeys() throws Exception {
        String sql = String.join("\n", CommerceMapper.class
                .getMethod("lockDueOrderTimeout")
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
                .contains("BETWEEN 1 AND 365")
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("ORDER_TIMERS");
    }
}

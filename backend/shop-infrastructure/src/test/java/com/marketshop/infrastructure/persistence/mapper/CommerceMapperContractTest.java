package com.marketshop.infrastructure.persistence.mapper;

import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderPo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
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

        assertThat(insert)
                .contains("buyer_note")
                .contains("#{buyerNote}");
        assertThat(detail).contains("buyer_note");
    }

    @Test
    void submissionInsertsOneTimerSnapshotAndInitializesPendingDueAt() throws Exception {
        String snapshot = String.join("\n", CommerceMapper.class
                .getMethod("snapshotOrderTimer", long.class, long.class)
                .getAnnotation(Insert.class)
                .value());
        String due = String.join("\n", CommerceMapper.class
                .getMethod("initializeOrderStatusDueAt", long.class, int.class)
                .getAnnotation(Update.class)
                .value());

        assertThat(snapshot)
                .contains("trade_order_rule_snapshot")
                .contains("ORDER_TIMERS")
                .contains("#{orderId}")
                .contains("#{ruleVersionId}");
        assertThat(due)
                .contains("status_due_at")
                .contains("pendingSuperiorTimeoutDays")
                .contains("created_at")
                .contains("PENDING_SUPERIOR");
    }

    @Test
    void autoReceiveSelectorUsesPersistedShipmentDueAtAndSnapshot() throws Exception {
        String sql = String.join("\n", CommerceMapper.class
                .getMethod("lockDueAutoReceive")
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("auto_receive_at <= CURRENT_TIMESTAMP(3)")
                .contains("trade_order_rule_snapshot")
                .contains("operation_rule_version")
                .contains("NOT EXISTS")
                .contains("trade_after_sale")
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("LIMIT 1")
                .doesNotContain("JSON_EXTRACT");
    }

    @Test
    void pendingTimeoutSelectorUsesPersistedDueAtAndCannotStarveShorterPolicies() throws Exception {
        String sql = String.join("\n", CommerceMapper.class
                .getMethod("lockDueOrderTimeout")
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("status_due_at <= CURRENT_TIMESTAMP(3)")
                .contains("trade_order_rule_snapshot")
                .contains("operation_rule_version")
                .contains("PENDING_SUPERIOR")
                .contains("PENDING_ADMIN_REVIEW")
                .contains("PENDING_SHIPMENT")
                .contains("ORDER BY o.status_due_at")
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("LIMIT 1")
                .doesNotContain("JSON_EXTRACT");
    }

    @Test
    void orderTransitionAdvancesOrClearsThePersistedDueAt() throws Exception {
        String sql = String.join("\n", CommerceMapper.class
                .getMethod("updateTransition", long.class, String.class, java.time.LocalDateTime.class,
                        java.time.LocalDateTime.class, java.time.LocalDateTime.class,
                        java.time.LocalDateTime.class, java.time.LocalDateTime.class,
                        java.time.LocalDateTime.class, String.class, int.class, int.class)
                .getAnnotation(Update.class)
                .value());
        assertThat(sql).contains("status_due_at = #{statusDueAt}");
    }

    @Test
    void snapshottedTimerLookupDoesNotRebindToCurrentEffectiveRule() throws Exception {
        String sql = String.join("\n", CommerceMapper.class
                .getMethod("snapshottedOrderTimerRule", long.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("trade_order_rule_snapshot")
                .contains("snapshot.rule_version_id")
                .doesNotContain("effective_from <= CURRENT_TIMESTAMP")
                .doesNotContain("status = 'ACTIVE'");
    }
}

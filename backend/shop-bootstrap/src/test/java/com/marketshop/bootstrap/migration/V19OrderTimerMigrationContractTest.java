package com.marketshop.bootstrap.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class V19OrderTimerMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V19__snapshot_order_timers_and_invitation_guards.sql";

    @Test
    void normalizesLegacyStageTimeoutFieldsWithoutTouchingEarlierMigrations() throws IOException {
        String sql = migration();

        assertThat(sql)
                .contains("UPDATE operation_rule_version")
                .contains("awaitingReturnTimeoutDays")
                .contains("returnShippedTimeoutDays")
                .contains("offlineRefundTimeoutDays")
                .contains("buyerRefundConfirmTimeoutDays")
                .contains("autoReceiveDays")
                .contains("JSON_REMOVE(parameters_json, '$.autoReceiveDaysAfterShipment')")
                .contains("COALESCE(JSON_EXTRACT");
    }

    @Test
    void backfillsOneTimerSnapshotAtOrderCreationUsingDeterministicEffectiveVersion() throws IOException {
        String sql = migration();
        int start = sql.indexOf("INSERT IGNORE INTO trade_order_rule_snapshot");
        int end = sql.indexOf(';', start);
        String backfill = sql.substring(start, end < 0 ? sql.length() : end);

        assertThat(backfill)
                .contains("INSERT IGNORE INTO trade_order_rule_snapshot")
                .contains("orders.created_at")
                .contains("rules.effective_from <= orders.created_at")
                .contains("rules.effective_to IS NULL OR rules.effective_to > orders.created_at")
                .contains("newer.version_no > rules.version_no")
                .contains("newer.id > rules.id")
                .doesNotContain("orders.completed_at")
                .doesNotContain("CURRENT_TIMESTAMP");
    }

    @Test
    void backfillsAndSelectorsUsePersistedDueTimestampsIdempotently() throws IOException {
        String sql = migration();

        assertThat(sql)
                .contains("ADD COLUMN status_due_at TIMESTAMP(3)")
                .contains("ADD COLUMN state_due_at TIMESTAMP(3)")
                .contains("SET orders.auto_receive_at = TIMESTAMPADD")
                .contains("SET orders.status_due_at = CASE")
                .contains("SET sales.state_due_at = CASE")
                .contains("JSON_TYPE(JSON_EXTRACT")
                .contains("BETWEEN 1 AND 365")
                .contains("AND orders.status_due_at IS NULL")
                .contains("AND sales.state_due_at IS NULL")
                .contains("TIMESTAMPADD")
                .doesNotContain("CAST(JSON_UNQUOTE(JSON_EXTRACT");
    }

    private static String migration() throws IOException {
        try (InputStream stream = V19OrderTimerMigrationContractTest.class
                .getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as(MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

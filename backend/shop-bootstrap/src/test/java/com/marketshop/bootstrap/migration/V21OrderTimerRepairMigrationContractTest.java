package com.marketshop.bootstrap.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class V21OrderTimerRepairMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V21__repair_order_timer_settings.sql";

    @Test
    void repairsOnlyTheKnownV19StageFieldsAsBoundedJsonIntegers() throws IOException {
        String sql = migration();

        assertThat(sql)
                .contains("UPDATE operation_rule_version")
                .contains("WHERE rule_code = 'ORDER_TIMERS'")
                .contains("AND rule_type = 'ORDER_TIMER'")
                .contains("'$.awaitingReturnTimeoutDays'")
                .contains("'$.returnShippedTimeoutDays'")
                .contains("'$.offlineRefundTimeoutDays'")
                .contains("'$.buyerRefundConfirmTimeoutDays'")
                .contains("JSON_TYPE(JSON_EXTRACT")
                .contains("REGEXP '^0*[1-9][0-9]{0,2}$'")
                .contains("END AS SIGNED)")
                .doesNotContain("'$.pendingSuperiorTimeoutDays',\n            CAST(CASE")
                .doesNotContain("UPDATE operation_rule_version\nSET status");
    }

    @Test
    void backfillsOnlyNullDeadlinesFromImmutableOrderSnapshots() throws IOException {
        String sql = migration();

        assertThat(sql)
                .contains("JOIN trade_order_rule_snapshot snapshot")
                .contains("snapshot.rule_code = 'ORDER_TIMERS'")
                .contains("rules.id = snapshot.rule_version_id")
                .contains("AND orders.status_due_at IS NULL")
                .contains("AND orders.auto_receive_at IS NULL")
                .contains("AND sales.state_due_at IS NULL")
                .contains("AND JSON_TYPE(rules.parameters_json) = 'OBJECT'")
                .contains("AND JSON_LENGTH(rules.parameters_json) = 12")
                .contains("SET orders.status_due_at = CASE")
                .contains("SET orders.auto_receive_at = TIMESTAMPADD")
                .contains("SET sales.state_due_at = CASE")
                .contains("JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.proofRetentionDays')) = 'INTEGER'")
                .doesNotContain("SET orders.auto_receive_at = NULL")
                .doesNotContain("SET orders.status_due_at = NULL")
                .doesNotContain("SET sales.state_due_at = NULL");
    }

    private static String migration() throws IOException {
        try (InputStream stream = V21OrderTimerRepairMigrationContractTest.class
                .getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as(MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

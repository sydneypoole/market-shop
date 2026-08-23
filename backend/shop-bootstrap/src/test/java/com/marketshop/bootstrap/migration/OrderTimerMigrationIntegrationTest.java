package com.marketshop.bootstrap.migration;

import com.marketshop.application.membership.OrderTimerParameters;
import com.marketshop.application.membership.RuleParameterCodec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class OrderTimerMigrationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("market_shop_v19_timer")
            .withUsername("market_shop")
            .withPassword("market_shop");

    @BeforeEach
    void cleanDatabase() {
        flyway().clean();
    }

    @Test
    void freshSchemaHasCanonicalLegacyTimerDefaultsAfterV19() {
        Flyway flyway = flyway();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("19");
        Map<String, Object> timer = jdbc().queryForMap("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.autoReceiveDays'))
                           AS auto_receive,
                       JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.autoReceiveDaysAfterShipment'))
                           AS legacy_auto_receive,
                       JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.awaitingReturnTimeoutDays'))
                           AS awaiting_return,
                       JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.returnShippedTimeoutDays'))
                           AS return_shipped,
                       JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.offlineRefundTimeoutDays'))
                           AS offline_refund,
                       JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.buyerRefundConfirmTimeoutDays'))
                           AS buyer_confirm
                FROM operation_rule_version
                WHERE id = 7
                """);

        assertThat(timer)
                .containsEntry("auto_receive", "7")
                .containsEntry("legacy_auto_receive", null)
                .containsEntry("awaiting_return", "15")
                .containsEntry("return_shipped", "15")
                .containsEntry("offline_refund", "7")
                .containsEntry("buyer_confirm", "7");
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM trade_order_rule_snapshot", Integer.class)).isZero();
    }

    @Test
    void legacyOrdersBackfillTheRuleEffectiveAtCreatedAtDeterministically() {
        flyway("18").migrate();
        JdbcTemplate jdbc = jdbc();
        seedUsers(jdbc);
        jdbc.update("""
                INSERT INTO operation_rule_version
                    (id, rule_code, version_no, rule_type, parameters_json, status, effective_from)
                VALUES
                    (80, 'ORDER_TIMERS', 2, 'ORDER_TIMER',
                     JSON_OBJECT(
                         'autoReceiveDaysAfterShipment', 30,
                         'afterSaleDaysAfterCompletion', 30,
                         'pendingSuperiorTimeoutDays', 30,
                         'pendingAdminReviewTimeoutDays', 30,
                         'pendingShipmentTimeoutDays', 30,
                         'awaitingReturnTimeoutDays', 30,
                         'returnShippedTimeoutDays', 30,
                         'offlineRefundTimeoutDays', 30,
                         'buyerRefundConfirmTimeoutDays', 30,
                         'proofRetentionDays', 180,
                         'maxProofFiles', 3,
                         'maxProofSizeBytes', 8388608),
                     'ACTIVE', '2026-06-01 00:00:00.000')
                """);
        insertTimerRule(jdbc, 81L, 3, "2026-08-01 00:00:00.000",
                timerPayload("7", "\"7\"", "7", "7", "15", "15", "7", "7"));
        insertTimerRule(jdbc, 82L, 4, "2026-08-02 00:00:00.000",
                timerPayload("7.5", "7", "7", "7", "15", "15", "7", "7"));
        insertTimerRule(jdbc, 83L, 5, "2026-08-03 00:00:00.000",
                timerPayload("7", "7", "7", "7", "0", "15", "7", "7"));
        insertTimerRule(jdbc, 84L, 6, "2026-08-04 00:00:00.000",
                timerPayload("7", "7", "7", "366", "15", "15", "7", "7"));
        Map<String, Object> stringNumber = jdbc.queryForMap("""
                SELECT JSON_VALID(parameters_json) AS valid_json,
                       JSON_TYPE(JSON_EXTRACT(parameters_json, '$.pendingSuperiorTimeoutDays')) AS value_type,
                       JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.pendingSuperiorTimeoutDays')) AS value
                FROM operation_rule_version
                WHERE id = 81
                """);
        assertThat(stringNumber)
                .containsEntry("valid_json", 1L)
                .containsEntry("value_type", "STRING")
                .containsEntry("value", "7");
        insertOrder(jdbc, 501L, "2026-03-01 00:00:00.000", "legacy-before-v2");
        insertOrder(jdbc, 502L, "2026-07-01 00:00:00.000", "legacy-after-v2");
        insertOrder(jdbc, 503L, "2020-01-01 00:00:00.000", "legacy-without-effective-rule");
        insertOrder(jdbc, 504L, "2026-07-01 00:00:00.000", "legacy-with-aftersale", "SHIPPED");
        insertOrder(jdbc, 510L, "2026-08-01 12:00:00.000", "invalid-string-pending");
        insertOrder(jdbc, 511L, "2026-08-02 12:00:00.000", "invalid-fraction-auto", "SHIPPED");
        insertOrder(jdbc, 512L, "2026-08-03 12:00:00.000", "invalid-zero-aftersale", "SHIPPED");
        insertOrder(jdbc, 513L, "2026-08-04 12:00:00.000", "invalid-range-pending", "PENDING_SHIPMENT");
        jdbc.update("""
                UPDATE trade_order
                SET shipped_at = ?, auto_receive_at = ?
                WHERE id = 504
                """, "2026-07-01 00:00:00.000", "2026-07-08 00:00:00.000");
        jdbc.update("""
                UPDATE trade_order
                SET shipped_at = ?, auto_receive_at = ?
                WHERE id = 511
                """, "2026-08-02 12:00:00.000", "2026-08-03 12:00:00.000");
        jdbc.update("UPDATE trade_order SET shipped_at = ? WHERE id = 512",
                "2026-08-03 12:00:00.000");
        jdbc.update("UPDATE trade_order SET admin_reviewed_at = ? WHERE id = 513",
                "2026-08-04 12:00:00.000");
        insertAftersale(jdbc, 601L, 504L, "2026-07-01 00:00:00.000");
        insertAftersale(jdbc, 612L, 512L, "2026-08-03 12:00:00.000");

        flyway("21").migrate();

        assertThat(snapshotVersion(jdbc, 501L)).isEqualTo(7L);
        assertThat(snapshotVersion(jdbc, 502L)).isEqualTo(80L);
        assertThat(snapshotVersion(jdbc, 504L)).isEqualTo(80L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM trade_order_rule_snapshot
                WHERE rule_code = 'ORDER_TIMERS'
                """, Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                SELECT snapshotted_at
                FROM trade_order_rule_snapshot
                WHERE order_id = 501
                """, LocalDateTime.class)).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
        assertThat(jdbc.queryForObject("""
                SELECT status_due_at
                FROM trade_order
                WHERE id = 501
                """, LocalDateTime.class)).isEqualTo(LocalDateTime.of(2026, 3, 8, 0, 0));
        assertThat(jdbc.queryForObject("""
                SELECT status_due_at
                FROM trade_order
                WHERE id = 502
                """, LocalDateTime.class)).isEqualTo(LocalDateTime.of(2026, 7, 31, 0, 0));
        assertThat(snapshotCount(jdbc, 503L)).isZero();
        assertThat(jdbc.queryForObject("SELECT status_due_at FROM trade_order WHERE id = 503",
                LocalDateTime.class)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT auto_receive_at
                FROM trade_order
                WHERE id = 504
                """, LocalDateTime.class)).isEqualTo(LocalDateTime.of(2026, 7, 31, 0, 0));
        assertThat(jdbc.queryForObject("""
                SELECT state_due_at
                FROM trade_after_sale
                WHERE id = 601
                """, LocalDateTime.class)).isEqualTo(LocalDateTime.of(2026, 7, 31, 0, 0));
        assertThat(jdbc.queryForObject("SELECT status_due_at FROM trade_order WHERE id = 510",
                LocalDateTime.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT auto_receive_at FROM trade_order WHERE id = 511",
                LocalDateTime.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT state_due_at FROM trade_after_sale WHERE id = 612",
                LocalDateTime.class)).isEqualTo(LocalDateTime.of(2026, 8, 18, 12, 0));
        assertThat(jdbc.queryForObject("SELECT auto_receive_at FROM trade_order WHERE id = 512",
                LocalDateTime.class)).isEqualTo(LocalDateTime.of(2026, 8, 10, 12, 0));
        assertThat(jdbc.queryForObject("SELECT status_due_at FROM trade_order WHERE id = 513",
                LocalDateTime.class)).isNull();

        // A second Flyway call is a no-op and preserves both the snapshot and
        // the persisted due timestamps.
        flyway("21").migrate();
        assertThat(snapshotVersion(jdbc, 501L)).isEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT status_due_at FROM trade_order WHERE id = 501",
                LocalDateTime.class)).isEqualTo(LocalDateTime.of(2026, 3, 8, 0, 0));
    }

    @Test
    void dueSelectorsSkipOlderNotDueRowsAndReturnLaterDueRows() {
        flyway().migrate();
        JdbcTemplate jdbc = jdbc();
        seedUsers(jdbc);

        insertOrder(jdbc, 505L, "2026-08-21 00:00:00.000", "selector-pending-future");
        insertOrder(jdbc, 506L, "2026-08-21 00:00:00.000", "selector-pending-due");
        insertOrder(jdbc, 507L, "2026-08-21 00:00:00.000", "selector-auto-future", "SHIPPED");
        insertOrder(jdbc, 508L, "2026-08-21 00:00:00.000", "selector-auto-due", "SHIPPED");
        insertOrder(jdbc, 509L, "2026-08-21 00:00:00.000", "selector-aftersale", "SHIPPED");
        jdbc.update("""
                INSERT INTO trade_order_rule_snapshot (order_id, rule_code, rule_version_id)
                VALUES (505, 'ORDER_TIMERS', 7), (506, 'ORDER_TIMERS', 7),
                       (507, 'ORDER_TIMERS', 7), (508, 'ORDER_TIMERS', 7),
                       (509, 'ORDER_TIMERS', 7)
                """);
        jdbc.update("UPDATE trade_order SET status_due_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY) WHERE id = 505");
        jdbc.update("UPDATE trade_order SET status_due_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id = 506");
        jdbc.update("""
                UPDATE trade_order
                SET shipped_at = CURRENT_TIMESTAMP(3),
                    auto_receive_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)
                WHERE id = 507
                """);
        jdbc.update("""
                UPDATE trade_order
                SET shipped_at = CURRENT_TIMESTAMP(3),
                    auto_receive_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
                WHERE id = 508
                """);
        insertAftersale(jdbc, 602L, 509L, "2026-08-21 00:00:00.000");
        insertAftersale(jdbc, 603L, 509L, "2026-08-21 00:00:00.000");
        jdbc.update("UPDATE trade_after_sale SET state_due_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY) WHERE id = 602");
        jdbc.update("UPDATE trade_after_sale SET state_due_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id = 603");

        assertThat(jdbc.queryForObject("""
                SELECT o.id
                FROM trade_order o
                WHERE o.status IN ('PENDING_SUPERIOR', 'PENDING_ADMIN_REVIEW', 'PENDING_SHIPMENT')
                  AND o.status_due_at <= CURRENT_TIMESTAMP(3)
                ORDER BY o.status_due_at, o.id
                LIMIT 1
                """, Long.class)).isEqualTo(506L);
        assertThat(jdbc.queryForObject("""
                SELECT o.id
                FROM trade_order o
                WHERE o.status = 'SHIPPED'
                  AND o.auto_receive_at <= CURRENT_TIMESTAMP(3)
                ORDER BY o.auto_receive_at, o.id
                LIMIT 1
                """, Long.class)).isEqualTo(508L);
        assertThat(jdbc.queryForObject("""
                SELECT a.id
                FROM trade_after_sale a
                WHERE a.status = 'AWAITING_RETURN'
                  AND a.state_due_at <= CURRENT_TIMESTAMP(3)
                ORDER BY a.state_due_at, a.id
                LIMIT 1
                """, Long.class)).isEqualTo(603L);
    }

    @Test
    void v21RepairsKnownTimerTypesAndOnlyBackfillsMissingSnapshotDeadlines() {
        flyway("20").migrate();
        JdbcTemplate jdbc = jdbc();
        seedUsers(jdbc);

        jdbc.update("""
                UPDATE operation_rule_version
                SET parameters_json = JSON_REMOVE(
                        JSON_SET(
                            parameters_json,
                            '$.awaitingReturnTimeoutDays', CAST(22 AS SIGNED),
                            '$.returnShippedTimeoutDays', '23',
                            '$.buyerRefundConfirmTimeoutDays', 'invalid'
                        ),
                        '$.offlineRefundTimeoutDays'
                    )
                WHERE id = 7
                """);
        assertThat(jdbc.queryForMap("""
                SELECT JSON_TYPE(JSON_EXTRACT(parameters_json, '$.awaitingReturnTimeoutDays')) AS awaiting_type,
                       JSON_TYPE(JSON_EXTRACT(parameters_json, '$.returnShippedTimeoutDays')) AS shipped_type,
                       JSON_TYPE(JSON_EXTRACT(parameters_json, '$.offlineRefundTimeoutDays')) AS offline_type,
                       JSON_TYPE(JSON_EXTRACT(parameters_json, '$.buyerRefundConfirmTimeoutDays')) AS buyer_type
                FROM operation_rule_version
                WHERE id = 7
                """))
                .containsEntry("awaiting_type", "INTEGER")
                .containsEntry("shipped_type", "STRING")
                .containsEntry("offline_type", null)
                .containsEntry("buyer_type", "STRING");

        insertOrder(jdbc, 701L, "2026-08-01 00:00:00.000", "v21-pending-superior");
        insertOrder(jdbc, 702L, "2026-08-02 00:00:00.000", "v21-pending-admin", "PENDING_ADMIN_REVIEW");
        insertOrder(jdbc, 703L, "2026-08-03 00:00:00.000", "v21-pending-shipment", "PENDING_SHIPMENT");
        insertOrder(jdbc, 704L, "2026-08-04 00:00:00.000", "v21-shipped", "SHIPPED");
        insertOrder(jdbc, 705L, "2026-08-05 00:00:00.000", "v21-shipped-preserved", "SHIPPED");
        insertOrder(jdbc, 706L, "2026-08-06 00:00:00.000", "v21-pending-preserved", "PENDING_SHIPMENT");
        jdbc.update("UPDATE trade_order SET superior_confirmed_at = ? WHERE id = 702",
                "2026-08-02 10:00:00.000");
        jdbc.update("UPDATE trade_order SET admin_reviewed_at = ? WHERE id = 703",
                "2026-08-03 11:00:00.000");
        jdbc.update("UPDATE trade_order SET shipped_at = ? WHERE id = 704",
                "2026-08-04 12:00:00.000");
        jdbc.update("""
                UPDATE trade_order
                SET shipped_at = ?, auto_receive_at = ?
                WHERE id = 705
                """, "2026-08-05 12:00:00.000", "2027-01-01 00:00:00.000");
        jdbc.update("""
                UPDATE trade_order
                SET admin_reviewed_at = ?, status_due_at = ?
                WHERE id = 706
                """, "2026-08-06 12:00:00.000", "2027-02-01 00:00:00.000");
        for (long orderId = 701L; orderId <= 706L; orderId++) {
            jdbc.update("""
                    INSERT INTO trade_order_rule_snapshot (order_id, rule_code, rule_version_id)
                    VALUES (?, 'ORDER_TIMERS', 7)
                    """, orderId);
        }
        jdbc.update("""
                INSERT INTO operation_rule_version
                    (id, rule_code, version_no, rule_type, parameters_json, status, effective_from)
                SELECT 90, rule_code, 90, rule_type,
                       JSON_SET(parameters_json, '$.unexpectedTimerField', CAST(1 AS SIGNED)),
                       'CANCELLED', '2037-01-01 00:00:00.000'
                FROM operation_rule_version
                WHERE id = 7
                """);
        insertOrder(jdbc, 707L, "2026-08-07 00:00:00.000", "v21-unknown-field");
        jdbc.update("""
                INSERT INTO trade_order_rule_snapshot (order_id, rule_code, rule_version_id)
                VALUES (707, 'ORDER_TIMERS', 90)
                """);

        insertAftersale(jdbc, 801L, 704L, "AWAITING_RETURN", "2026-08-06 09:00:00.000");
        insertAftersale(jdbc, 802L, 704L, "RETURN_SHIPPED", "2026-08-06 09:00:00.000");
        insertAftersale(jdbc, 803L, 704L, "PENDING_OFFLINE_REFUND", "2026-08-06 09:00:00.000");
        insertAftersale(jdbc, 804L, 704L, "PENDING_BUYER_REFUND_CONFIRMATION", "2026-08-06 09:00:00.000");
        insertAftersale(jdbc, 805L, 704L, "AWAITING_RETURN", "2026-08-06 09:00:00.000");
        jdbc.update("UPDATE trade_after_sale SET state_due_at = ? WHERE id = 805",
                "2027-03-01 00:00:00.000");

        Flyway repaired = flyway("21");
        repaired.migrate();
        assertThat(repaired.info().current().getVersion().getVersion()).isEqualTo("21");

        Map<String, Object> repairedTimer = jdbc.queryForMap("""
                SELECT JSON_TYPE(JSON_EXTRACT(parameters_json, '$.awaitingReturnTimeoutDays')) AS awaiting_type,
                       JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.awaitingReturnTimeoutDays')) AS awaiting_value,
                       JSON_TYPE(JSON_EXTRACT(parameters_json, '$.returnShippedTimeoutDays')) AS shipped_type,
                       JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.returnShippedTimeoutDays')) AS shipped_value,
                       JSON_TYPE(JSON_EXTRACT(parameters_json, '$.offlineRefundTimeoutDays')) AS offline_type,
                       JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.offlineRefundTimeoutDays')) AS offline_value,
                       JSON_TYPE(JSON_EXTRACT(parameters_json, '$.buyerRefundConfirmTimeoutDays')) AS buyer_type,
                       JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.buyerRefundConfirmTimeoutDays')) AS buyer_value,
                       parameters_json
                FROM operation_rule_version
                WHERE id = 7
                """);
        assertThat(repairedTimer)
                .containsEntry("awaiting_type", "INTEGER")
                .containsEntry("awaiting_value", "22")
                .containsEntry("shipped_type", "INTEGER")
                .containsEntry("shipped_value", "23")
                .containsEntry("offline_type", "INTEGER")
                .containsEntry("offline_value", "7")
                .containsEntry("buyer_type", "INTEGER")
                .containsEntry("buyer_value", "7");
        OrderTimerParameters decoded = (OrderTimerParameters) RuleParameterCodec.decodePersisted(
                "ORDER_TIMERS", "ORDER_TIMER", String.valueOf(repairedTimer.get("parameters_json"))
        ).parameters();
        assertThat(decoded.awaitingReturnTimeoutDays()).isEqualTo(22);
        assertThat(decoded.returnShippedTimeoutDays()).isEqualTo(23);
        assertThat(decoded.offlineRefundTimeoutDays()).isEqualTo(7);
        assertThat(decoded.buyerRefundConfirmTimeoutDays()).isEqualTo(7);

        assertThat(timestamp(jdbc, "trade_order", "status_due_at", 701L))
                .isEqualTo(LocalDateTime.of(2026, 8, 8, 0, 0));
        assertThat(timestamp(jdbc, "trade_order", "status_due_at", 702L))
                .isEqualTo(LocalDateTime.of(2026, 8, 9, 10, 0));
        assertThat(timestamp(jdbc, "trade_order", "status_due_at", 703L))
                .isEqualTo(LocalDateTime.of(2026, 8, 10, 11, 0));
        assertThat(timestamp(jdbc, "trade_order", "auto_receive_at", 704L))
                .isEqualTo(LocalDateTime.of(2026, 8, 11, 12, 0));
        assertThat(timestamp(jdbc, "trade_order", "auto_receive_at", 705L))
                .isEqualTo(LocalDateTime.of(2027, 1, 1, 0, 0));
        assertThat(timestamp(jdbc, "trade_order", "status_due_at", 706L))
                .isEqualTo(LocalDateTime.of(2027, 2, 1, 0, 0));
        assertThat(timestamp(jdbc, "trade_order", "status_due_at", 707L)).isNull();

        assertThat(timestamp(jdbc, "trade_after_sale", "state_due_at", 801L))
                .isEqualTo(LocalDateTime.of(2026, 8, 28, 9, 0));
        assertThat(timestamp(jdbc, "trade_after_sale", "state_due_at", 802L))
                .isEqualTo(LocalDateTime.of(2026, 8, 29, 9, 0));
        assertThat(timestamp(jdbc, "trade_after_sale", "state_due_at", 803L))
                .isEqualTo(LocalDateTime.of(2026, 8, 13, 9, 0));
        assertThat(timestamp(jdbc, "trade_after_sale", "state_due_at", 804L))
                .isEqualTo(LocalDateTime.of(2026, 8, 13, 9, 0));
        assertThat(timestamp(jdbc, "trade_after_sale", "state_due_at", 805L))
                .isEqualTo(LocalDateTime.of(2027, 3, 1, 0, 0));
    }

    private static LocalDateTime timestamp(JdbcTemplate jdbc, String table, String column, long id) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?",
                LocalDateTime.class, id);
    }

    private static int snapshotCount(JdbcTemplate jdbc, long orderId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM trade_order_rule_snapshot
                WHERE order_id = ? AND rule_code = 'ORDER_TIMERS'
                """, Integer.class, orderId);
    }

    private static long snapshotVersion(JdbcTemplate jdbc, long orderId) {
        return jdbc.queryForObject("""
                SELECT rule_version_id
                FROM trade_order_rule_snapshot
                WHERE order_id = ? AND rule_code = 'ORDER_TIMERS'
                """, Long.class, orderId);
    }

    private static void seedUsers(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO iam_user_account (id, public_id, status, nickname)
                VALUES (101, 'USER0000000000000000000001', 'ACTIVE', '买家'),
                       (102, 'USER0000000000000000000002', 'ACTIVE', '上级')
                """);
    }

    private static void insertTimerRule(
            JdbcTemplate jdbc, long id, int version, String effectiveFrom, String parametersJson
    ) {
        jdbc.update("""
                INSERT INTO operation_rule_version
                    (id, rule_code, version_no, rule_type, parameters_json, status, effective_from)
                VALUES (?, 'ORDER_TIMERS', ?, 'ORDER_TIMER', CAST(? AS JSON), 'ACTIVE', ?)
                """, id, version, parametersJson, effectiveFrom);
    }

    private static String timerPayload(
            String autoReceiveDays,
            String pendingSuperiorTimeoutDays,
            String pendingAdminReviewTimeoutDays,
            String pendingShipmentTimeoutDays,
            String awaitingReturnTimeoutDays,
            String returnShippedTimeoutDays,
            String offlineRefundTimeoutDays,
            String buyerRefundConfirmTimeoutDays
    ) {
        return "{\"autoReceiveDays\":" + autoReceiveDays
                + ",\"afterSaleDaysAfterCompletion\":7"
                + ",\"pendingSuperiorTimeoutDays\":" + pendingSuperiorTimeoutDays
                + ",\"pendingAdminReviewTimeoutDays\":" + pendingAdminReviewTimeoutDays
                + ",\"pendingShipmentTimeoutDays\":" + pendingShipmentTimeoutDays
                + ",\"awaitingReturnTimeoutDays\":" + awaitingReturnTimeoutDays
                + ",\"returnShippedTimeoutDays\":" + returnShippedTimeoutDays
                + ",\"offlineRefundTimeoutDays\":" + offlineRefundTimeoutDays
                + ",\"buyerRefundConfirmTimeoutDays\":" + buyerRefundConfirmTimeoutDays
                + ",\"proofRetentionDays\":180,\"maxProofFiles\":3,"
                + "\"maxProofSizeBytes\":8388608}";
    }

    private static void insertOrder(JdbcTemplate jdbc, long id, String createdAt, String requestId) {
        insertOrder(jdbc, id, createdAt, requestId, "PENDING_SUPERIOR");
    }

    private static void insertOrder(
            JdbcTemplate jdbc, long id, String createdAt, String requestId, String status
    ) {
        jdbc.update("""
                INSERT INTO trade_order
                    (id, order_no, buyer_user_id, superior_user_id, address_snapshot_json,
                     total_amount_fen, status, source, client_request_id, created_at, version)
                VALUES (?, ?, 101, 102, JSON_OBJECT('recipientName', '买家'),
                        29800, ?, 'MINIPROGRAM', ?, ?, 0)
                """, id, "MS" + id, status, requestId, createdAt);
    }

    private static void insertAftersale(JdbcTemplate jdbc, long id, long orderId, String enteredAt) {
        insertAftersale(jdbc, id, orderId, "AWAITING_RETURN", enteredAt);
    }

    private static void insertAftersale(
            JdbcTemplate jdbc, long id, long orderId, String status, String enteredAt
    ) {
        jdbc.update("""
                INSERT INTO trade_after_sale
                    (id, after_sale_no, order_id, applicant_user_id, type, status, reason,
                     client_request_id, state_entered_at, created_at)
                VALUES (?, ?, ?, 101, 'REFUND_ONLY', ?, '商品破损', ?, ?, ?)
                """, id, "AS" + id, orderId, status, "after-sale-" + id, enteredAt, enteredAt);
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(dataSource())
                .target("19")
                .cleanDisabled(false)
                .load();
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource())
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    private DataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(MYSQL.getJdbcUrl());
        source.setUsername(MYSQL.getUsername());
        source.setPassword(MYSQL.getPassword());
        return source;
    }
}

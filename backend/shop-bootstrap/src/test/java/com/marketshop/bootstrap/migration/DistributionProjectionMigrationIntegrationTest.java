package com.marketshop.bootstrap.migration;

import db.migration.V18__repair_distribution_projections;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class DistributionProjectionMigrationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("market_shop_v18_projection")
            .withUsername("market_shop")
            .withPassword("market_shop");

    @BeforeEach
    void cleanDatabase() {
        flyway().clean();
    }

    @Test
    void freshDatabaseAppliesV18WithoutCreatingLedgerFacts() {
        Flyway flyway = flyway();

        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("18");
        JdbcTemplate jdbc = jdbc();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_frozen_batch", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_frozen_release_item", Integer.class)).isZero();
    }

    @Test
    void V18RepairsV9ProjectionWithoutChangingImmutableLedgerFacts() {
        Flyway legacy = flyway("8");
        legacy.migrate();
        JdbcTemplate jdbc = jdbc();
        seedV9Fixture(jdbc);
        flyway("9").migrate();
        List<Map<String, Object>> before = ledgerSnapshot(jdbc);

        flyway().migrate();

        assertThat(ledgerSnapshot(jdbc)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT frozen_points FROM ledger_account WHERE id = 1", Long.class))
                .isEqualTo(200L);
        assertThat(jdbc.queryForObject("""
                SELECT remaining_points
                FROM ledger_frozen_batch
                WHERE source_ledger_entry_id = 101
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT status
                FROM ledger_frozen_batch
                WHERE source_ledger_entry_id = 101
                """, String.class)).isEqualTo("REVERSED");
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(remaining_points), 0)
                FROM ledger_frozen_batch
                WHERE account_id = 1 AND status = 'ACTIVE'
                """, Long.class)).isEqualTo(200L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_frozen_release_item item
                JOIN ledger_entry release_entry ON release_entry.id = item.release_ledger_entry_id
                WHERE release_entry.id = 201
                  AND item.frozen_batch_id = (
                      SELECT id FROM ledger_frozen_batch WHERE source_ledger_entry_id = 101
                  )
                  AND item.points = 50
                """, Integer.class)).isEqualTo(1);

        List<Map<String, Object>> batchesBeforeRerun = batchSnapshot(jdbc);
        List<Map<String, Object>> itemsBeforeRerun = releaseItemSnapshot(jdbc);
        jdbc.update("UPDATE ledger_frozen_release_item SET created_at = '1999-01-01 00:00:00.000'");
        runV18Directly();
        assertThat(ledgerSnapshot(jdbc)).isEqualTo(before);
        assertThat(batchSnapshot(jdbc)).isEqualTo(batchesBeforeRerun);
        assertThat(releaseItemSnapshot(jdbc)).isEqualTo(itemsBeforeRerun);

        flyway().migrate();
        assertThat(ledgerSnapshot(jdbc)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_frozen_release_item", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void V18RepairsHistoricalDuplicateDirectPerformanceWithAnAppendOnlyReversal() {
        flyway("8").migrate();
        JdbcTemplate jdbc = jdbc();
        seedDuplicateFixture(jdbc);
        flyway("9").migrate();

        flyway().migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry", Integer.class)).isEqualTo(2);
        Map<String, Object> original = jdbc.queryForMap("""
                SELECT id, available_delta, frozen_delta, entry_type, original_entry_id
                FROM ledger_entry WHERE id = 201
                """);
        assertThat(original)
                .containsEntry("id", 201L)
                .containsEntry("available_delta", 160L)
                .containsEntry("frozen_delta", 160L)
                .containsEntry("entry_type", "DIRECT_REFERRAL_AWARD")
                .containsEntry("original_entry_id", null);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_entry
                WHERE entry_type = 'REVERSAL' AND source_type = 'MIGRATION'
                  AND original_entry_id = 201 AND available_delta = -160 AND frozen_delta = -160
                """, Integer.class)).isEqualTo(1);
        Timestamp expectedRepairTimestamp = Timestamp.valueOf("2099-01-02 00:00:00.001");
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM ledger_entry "
                + "WHERE idempotency_key = 'migration-v18-direct-duplicate:201'", Timestamp.class))
                .isEqualTo(expectedRepairTimestamp);
        assertThat(jdbc.queryForObject("SELECT reversed_at FROM distribution_direct_performance WHERE id = 22",
                Timestamp.class)).isEqualTo(expectedRepairTimestamp);
        assertThat(jdbc.queryForObject("SELECT available_points FROM ledger_account WHERE id = 2", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT frozen_points FROM ledger_account WHERE id = 2", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM distribution_direct_performance WHERE id = 22",
                String.class)).isEqualTo("REVERSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT completed_ordinal) "
                + "FROM distribution_direct_performance WHERE beneficiary_user_id = 200", Integer.class))
                .isEqualTo(2);
        List<Map<String, Object>> duplicateLedger = ledgerSnapshot(jdbc);
        List<Map<String, Object>> duplicatePerformance = jdbc.queryForList("""
                SELECT id, status, completed_ordinal, reversed_at
                FROM distribution_direct_performance
                WHERE beneficiary_user_id = 200 ORDER BY id
                """);
        runV18Directly();
        assertThat(ledgerSnapshot(jdbc)).isEqualTo(duplicateLedger);
        assertThat(jdbc.queryForList("""
                SELECT id, status, completed_ordinal, reversed_at
                FROM distribution_direct_performance
                WHERE beneficiary_user_id = 200 ORDER BY id
                """)).isEqualTo(duplicatePerformance);
    }

    @Test
    void V18AbortsExplicitSourceConflictWithoutMutatingLedgerOrAccount() {
        flyway("8").migrate();
        JdbcTemplate jdbc = jdbc();
        seedExplicitSourceConflictFixture(jdbc);
        flyway("9").migrate();
        List<Map<String, Object>> before = ledgerSnapshot(jdbc);
        long frozenBefore = jdbc.queryForObject("SELECT frozen_points FROM ledger_account WHERE id = 3", Long.class);

        assertThatThrownBy(() -> flyway().migrate())
                .hasStackTraceContaining("FROZEN_BATCH_BALANCE_CONFLICT");
        assertThat(ledgerSnapshot(jdbc)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT frozen_points FROM ledger_account WHERE id = 3", Long.class))
                .isEqualTo(frozenBefore);
    }

    @Test
    void V18RejectsNonZeroFrozenBalanceWithNoLedgerFacts() {
        flyway("8").migrate();
        JdbcTemplate jdbc = jdbc();
        jdbc.update("INSERT INTO iam_user_account (id, public_id, status, nickname) "
                + "VALUES (300, '01JV18EMPTYACCOUNT000000000', 'ACTIVE', 'empty-account')");
        jdbc.update("INSERT INTO ledger_account "
                + "(id, user_id, account_type, available_points, frozen_points) "
                + "VALUES (3, 300, 'DEMO_POINTS', 0, 9)");

        assertThatThrownBy(() -> flyway().migrate())
                .hasStackTraceContaining("FROZEN_BATCH_BALANCE_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT frozen_points FROM ledger_account WHERE id = 3", Long.class))
                .isEqualTo(9L);
    }

    private void seedExplicitSourceConflictFixture(JdbcTemplate jdbc) {
        jdbc.batchUpdate("""
                INSERT INTO iam_user_account (id, public_id, status, nickname)
                VALUES (?, ?, 'ACTIVE', ?)
                """, List.of(
                new Object[]{400L, "01JV18EXPLICITSUPERIOR000000", "explicit-superior"},
                new Object[]{401L, "01JV18EXPLICITBUYER00000000", "explicit-buyer"}
        ));
        for (long orderId = 4001; orderId <= 4003; orderId++) {
            jdbc.update("""
                    INSERT INTO trade_order
                        (id, order_no, buyer_user_id, superior_user_id, address_snapshot_json,
                         total_amount_fen, status, source, client_request_id, completed_at)
                    VALUES (?, ?, 401, 400, JSON_OBJECT(), 199800, 'COMPLETED', 'H5', ?, ?)
                    """, orderId, "V18-EXPLICIT-" + orderId, "v18-explicit-request-" + orderId,
                    "2026-02-0" + (orderId - 4000) + " 00:00:00.000");
        }
        jdbc.update("INSERT INTO ledger_account "
                + "(id, user_id, account_type, available_points, frozen_points) "
                + "VALUES (3, 400, 'DEMO_POINTS', 250, 150)");
        jdbc.update("""
                INSERT INTO ledger_entry
                    (id, account_id, entry_type, available_delta, frozen_delta, source_type, source_id,
                     source_order_id, rule_version_id, idempotency_key, occurred_at)
                VALUES (401, 3, 'DIRECT_REFERRAL_AWARD', 100, 100, 'DIRECT_PERFORMANCE', 4001, 4001, 4,
                        'v18-explicit-award-401', '2026-02-01 00:00:00.000'),
                       (402, 3, 'DIRECT_REFERRAL_AWARD', 100, 100, 'DIRECT_PERFORMANCE', 4002, 4002, 4,
                        'v18-explicit-award-402', '2026-02-02 00:00:00.000'),
                       (403, 3, 'FROZEN_POINTS_RELEASED', 50, -50, 'FROZEN_BATCH', 0, 4003, 5,
                        'v18-explicit-release-403', '2026-02-03 00:00:00.000')
                """);
        jdbc.update("""
                UPDATE ledger_entry
                SET original_entry_id = 402
                WHERE id = 403
                """);
    }

    private void seedDuplicateFixture(JdbcTemplate jdbc) {
        jdbc.batchUpdate("""
                INSERT INTO iam_user_account (id, public_id, status, nickname)
                VALUES (?, ?, 'ACTIVE', ?)
                """, List.of(
                new Object[]{200L, "01JV18DUPSUPERIOR0000000000", "duplicate-superior"},
                new Object[]{201L, "01JV18DUPBUYER0000000000000", "duplicate-buyer"}
        ));
        jdbc.batchUpdate("""
                INSERT INTO trade_order
                    (id, order_no, buyer_user_id, superior_user_id, address_snapshot_json,
                     total_amount_fen, status, source, client_request_id, completed_at)
                VALUES (?, ?, 201, 200, JSON_OBJECT(), 199800, 'COMPLETED', 'H5', ?, ?)
                """, List.of(
                new Object[]{2001L, "V18-DUP-2001", "v18-dup-request-2001", "2099-01-01 00:00:00.000"},
                new Object[]{2002L, "V18-DUP-2002", "v18-dup-request-2002", "2099-01-02 00:00:00.000"}
        ));
        jdbc.batchUpdate("""
                INSERT INTO distribution_direct_performance
                    (id, beneficiary_user_id, referred_user_id, source_order_id, rule_version_id,
                     completed_ordinal, performance_fen, status, created_at)
                VALUES (?, 200, 201, ?, 3, ?, 199800, 'ACTIVE', ?)
                """, List.of(
                new Object[]{21L, 2001L, 5, "2099-01-01 00:00:00.000"},
                new Object[]{22L, 2002L, 6, "2099-01-02 00:00:00.000"}
        ));
        jdbc.update("""
                INSERT INTO ledger_account
                    (id, user_id, account_type, available_points, frozen_points)
                VALUES (2, 200, 'DEMO_POINTS', 160, 160)
                """);
        jdbc.update("""
                INSERT INTO ledger_entry
                    (id, account_id, entry_type, available_delta, frozen_delta, source_type, source_id,
                     source_order_id, rule_version_id, idempotency_key, occurred_at)
                VALUES (201, 2, 'DIRECT_REFERRAL_AWARD', 160, 160, 'DIRECT_PERFORMANCE', 2002, 2002, 4,
                        'v18-duplicate-award', '2099-01-02 00:00:00.000')
                """);
    }

    private void seedV9Fixture(JdbcTemplate jdbc) {
        jdbc.batchUpdate("""
                INSERT INTO iam_user_account (id, public_id, status, nickname)
                VALUES (?, ?, 'ACTIVE', ?)
                """, List.of(
                new Object[]{100L, "01JV18SUPERIOR000000000000", "superior"},
                new Object[]{101L, "01JV18BUYER00000000000000", "buyer"}
        ));
        for (long orderId = 1001; orderId <= 1004; orderId++) {
            jdbc.update("""
                    INSERT INTO trade_order
                        (id, order_no, buyer_user_id, superior_user_id, address_snapshot_json,
                         total_amount_fen, status, source, client_request_id, completed_at)
                    VALUES (?, ?, 101, 100, JSON_OBJECT(), 199800, 'COMPLETED', 'H5', ?, ?)
                    """, orderId, "V18-ORDER-" + orderId, "v18-request-" + orderId,
                    "2026-01-0" + (orderId - 1000) + " 00:00:00.000");
        }
        jdbc.update("""
                INSERT INTO ledger_account
                    (id, user_id, account_type, available_points, frozen_points)
                VALUES (1, 100, 'DEMO_POINTS', 250, 200)
                """);
        jdbc.batchUpdate("""
                INSERT INTO ledger_entry
                    (id, account_id, entry_type, available_delta, frozen_delta, source_type, source_id,
                     source_order_id, rule_version_id, idempotency_key, occurred_at)
                VALUES (?, 1, 'DIRECT_REFERRAL_AWARD', 100, 100, 'DIRECT_PERFORMANCE', ?, ?, 4, ?, ?)
                """, List.of(
                new Object[]{101L, 1001L, 1001L, "v18-award-101", "2026-01-01 00:00:00.000"},
                new Object[]{102L, 1002L, 1002L, "v18-award-102", "2026-01-02 00:00:00.000"},
                new Object[]{103L, 1003L, 1003L, "v18-award-103", "2026-01-03 00:00:00.000"}
        ));
        jdbc.update("""
                INSERT INTO ledger_entry
                    (id, account_id, entry_type, available_delta, frozen_delta, source_type, source_id,
                     source_order_id, rule_version_id, idempotency_key, occurred_at)
                VALUES (201, 1, 'FROZEN_POINTS_RELEASED', 50, -50, 'FROZEN_BATCH', 0, 1004, 5,
                        'v18-release-201', '2026-01-04 00:00:00.000')
                """);
        jdbc.update("""
                INSERT INTO ledger_entry
                    (id, account_id, entry_type, available_delta, frozen_delta, source_type, source_id,
                     source_order_id, rule_version_id, original_entry_id, idempotency_key, occurred_at)
                VALUES (301, 1, 'REVERSAL', -100, -50, 'AFTERSALE', 900, 1001, 4, 101,
                        'v18-reversal-301', '2026-01-05 00:00:00.000')
                """);
    }

    private List<Map<String, Object>> ledgerSnapshot(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT id, account_id, entry_type, available_delta, frozen_delta, source_type, source_id,
                       source_order_id, rule_version_id, original_entry_id, idempotency_key, occurred_at,
                       created_at
                FROM ledger_entry
                ORDER BY occurred_at, id
                """);
    }

    private List<Map<String, Object>> batchSnapshot(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT id, account_id, source_ledger_entry_id, source_order_id, rule_version_id,
                       original_points, remaining_points, status, created_at, updated_at
                FROM ledger_frozen_batch
                ORDER BY id
                """);
    }

    private List<Map<String, Object>> releaseItemSnapshot(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT id, release_ledger_entry_id, frozen_batch_id, points, created_at
                FROM ledger_frozen_release_item
                ORDER BY id
                """);
    }

    private void runV18Directly() {
        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            new V18__repair_distribution_projections().migrate(new Context() {
                @Override
                public org.flywaydb.core.api.configuration.Configuration getConfiguration() {
                    return null;
                }

                @Override
                public Connection getConnection() {
                    return connection;
                }
            });
            connection.commit();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(dataSource())
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

    private DataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(MYSQL.getJdbcUrl());
        source.setUsername(MYSQL.getUsername());
        source.setPassword(MYSQL.getPassword());
        return source;
    }
}

package com.marketshop.bootstrap.migration;

import com.marketshop.bootstrap.config.LegacyAfterSaleMigrationPreflight;
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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class LegacyAfterSaleMigrationPreflightTest {

    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.4");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("market_shop_v17_preflight")
            .withUsername("market_shop")
            .withPassword("market_shop");

    @BeforeEach
    void cleanDatabase() {
        Flyway.configure()
                .dataSource(dataSource())
                .cleanDisabled(false)
                .load()
                .clean();
    }

    @Test
    void freshDatabaseIsAcknowledgeableAndNormalMigrationSucceeds() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway flyway = flyway(dataSource);

        new LegacyAfterSaleMigrationPreflight(dataSource).migrate(flyway);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = 'trade_after_sale'", Integer.class))
                .isEqualTo(1);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("17");
    }

    @Test
    void duplicateCompletedRowsAreRepairedByCanonicalOrderAndPreserveChildren() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway legacy = flyway(dataSource, "16");
        legacy.migrate();
        long orderId = insertOrder(jdbc);
        long applicantId = insertUser(jdbc);
        long canonicalId = insertCompletedAfterSale(jdbc, orderId, applicantId,
                "2026-01-01 00:00:00.000", "2026-01-02 00:00:00.000", "canonical");
        long duplicateId = insertCompletedAfterSale(jdbc, orderId, applicantId,
                "2026-01-02 00:00:00.000", "2026-01-01 00:00:00.000", "duplicate");
        jdbc.update("INSERT INTO trade_after_sale_proof "
                + "(after_sale_id, proof_type, object_key, sha256, media_type, size_bytes) "
                + "VALUES (?, 'APPLICATION', ?, ?, 'image/png', 1)", duplicateId,
                "preflight/" + duplicateId, "a".repeat(64));

        Flyway latest = flyway(dataSource);
        new LegacyAfterSaleMigrationPreflight(dataSource).migrate(latest);
        latest.migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_after_sale "
                + "WHERE order_id = ? AND status = 'COMPLETED'", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM trade_after_sale WHERE id = ?", String.class, canonicalId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM trade_after_sale WHERE id = ?", String.class, duplicateId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT admin_reason FROM trade_after_sale WHERE id = ?", String.class, duplicateId))
                .isEqualTo(LegacyAfterSaleMigrationPreflight.REPAIR_REASON);
        assertThat(jdbc.queryForObject("SELECT version FROM trade_after_sale WHERE id = ?", Integer.class, duplicateId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state_entered_at FROM trade_after_sale WHERE id = ?",
                java.sql.Timestamp.class, duplicateId)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_after_sale_proof WHERE after_sale_id = ?",
                Integer.class, duplicateId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log "
                + "WHERE actor_type = 'SYSTEM' AND action = 'LEGACY_AFTERSALE_V17_REPAIR'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void alreadySuccessfulV17DoesNotMutateDataAndKeepsTheInvariant() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway flyway = flyway(dataSource);
        LegacyAfterSaleMigrationPreflight preflight = new LegacyAfterSaleMigrationPreflight(dataSource);
        preflight.migrate(flyway);

        long orderId = insertOrder(jdbc);
        long applicantId = insertUser(jdbc);
        long afterSaleId = insertCompletedAfterSale(jdbc, orderId, applicantId,
                "2026-01-01 00:00:00.000", "2026-01-01 00:00:00.000", "successful");
        int version = jdbc.queryForObject("SELECT version FROM trade_after_sale WHERE id = ?", Integer.class, afterSaleId);
        int auditCount = jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log", Integer.class);

        preflight.migrate(flyway);

        assertThat(jdbc.queryForObject("SELECT status FROM trade_after_sale WHERE id = ?", String.class, afterSaleId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT version FROM trade_after_sale WHERE id = ?", Integer.class, afterSaleId))
                .isEqualTo(version);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log", Integer.class))
                .isEqualTo(auditCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'trade_after_sale' "
                + "AND index_name = 'uk_after_sale_completed_order' AND non_unique = 0", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void failedV17WithoutArtifactsIsRepairedAndRerun() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway legacy = flyway(dataSource, "16");
        legacy.migrate();
        long orderId = insertOrder(jdbc);
        long applicantId = insertUser(jdbc);
        long firstId = insertAfterSale(jdbc, orderId, applicantId,
                "2026-01-03 00:00:00.000", "2026-01-01 00:00:00.000",
                "2026-01-01 00:00:00.000", "failed-v17-first", true);
        long nullCompletedId = insertAfterSale(jdbc, orderId, applicantId,
                null, "2026-01-01 00:00:00.000", "2026-01-01 00:00:00.000",
                "failed-v17-null", true);
        long tiedLaterStateId = insertAfterSale(jdbc, orderId, applicantId,
                "2026-01-01 00:00:00.000", "2026-01-03 00:00:00.000",
                "2026-01-03 00:00:00.000", "failed-v17-tied-later", true);
        long canonicalId = insertAfterSale(jdbc, orderId, applicantId,
                "2026-01-01 00:00:00.000", "2026-01-02 00:00:00.000",
                "2026-01-04 00:00:00.000", "failed-v17-canonical", true);
        insertProof(jdbc, firstId);
        insertProof(jdbc, nullCompletedId);
        insertProof(jdbc, tiedLaterStateId);
        insertProof(jdbc, canonicalId);
        long applicantLedgerAccountId = insertLedgerAccount(jdbc, applicantId);
        insertOutboxEvent(jdbc, orderId);
        long inventoryBefore = scalar(jdbc, "SELECT COALESCE(SUM(available_quantity + reserved_quantity + version), 0) FROM catalog_inventory");
        long ledgerBefore = scalar(jdbc, "SELECT COALESCE(SUM(available_points + frozen_points + version), 0) FROM ledger_account");
        long outboxBefore = scalar(jdbc, "SELECT COALESCE(SUM(attempt_count), 0) FROM sys_outbox_event");
        insertV17HistoryRow(jdbc, v17Checksum(dataSource), 17, false);

        Flyway latest = flyway(dataSource);
        new LegacyAfterSaleMigrationPreflight(dataSource).migrate(latest);

        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("17");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version = '17'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_after_sale "
                + "WHERE order_id = ? AND status = 'COMPLETED'", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM trade_after_sale WHERE id = ?", String.class, canonicalId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM trade_after_sale WHERE id = ?", String.class, firstId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM trade_after_sale WHERE id = ?", String.class, nullCompletedId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM trade_after_sale WHERE id = ?", String.class, tiedLaterStateId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_after_sale_proof WHERE after_sale_id = ?",
                Integer.class, canonicalId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_after_sale_proof WHERE after_sale_id IN (?, ?, ?, ?)",
                Integer.class, firstId, nullCompletedId, tiedLaterStateId, canonicalId)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log "
                + "WHERE actor_type = 'SYSTEM' AND action = 'LEGACY_AFTERSALE_V17_REPAIR'", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_account WHERE id = ?", Integer.class,
                applicantLedgerAccountId)).isEqualTo(1);
        assertThat(scalar(jdbc, "SELECT COALESCE(SUM(available_quantity + reserved_quantity + version), 0) FROM catalog_inventory"))
                .isEqualTo(inventoryBefore);
        assertThat(scalar(jdbc, "SELECT COALESCE(SUM(available_points + frozen_points + version), 0) FROM ledger_account"))
                .isEqualTo(ledgerBefore);
        assertThat(scalar(jdbc, "SELECT COALESCE(SUM(attempt_count), 0) FROM sys_outbox_event"))
                .isEqualTo(outboxBefore);

        new LegacyAfterSaleMigrationPreflight(dataSource).migrate(latest);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log "
                + "WHERE actor_type = 'SYSTEM' AND action = 'LEGACY_AFTERSALE_V17_REPAIR'", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void partialV17ArtifactsFailClosed() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway legacy = flyway(dataSource, "16");
        legacy.migrate();
        jdbc.execute("ALTER TABLE trade_after_sale ADD COLUMN completed_order_id BIGINT");
        jdbc.update("INSERT INTO flyway_schema_history "
                + "(installed_rank, version, description, type, script, checksum, installed_by, "
                + "installed_on, execution_time, success) VALUES "
                + "(17, '17', 'aftersale completed unique and order timeouts', 'SQL', "
                + "'V17__aftersale_completed_unique_and_order_timeouts.sql', ?, 'test', "
                + "CURRENT_TIMESTAMP, 1, 0)", v17Checksum(dataSource));

        Flyway latest = flyway(dataSource);
        assertThatThrownBy(() -> new LegacyAfterSaleMigrationPreflight(dataSource).migrate(latest))
                .hasMessageContaining("V17")
                .hasMessageContaining("partial");
    }

    @Test
    void checksumMismatchFailsClosed() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway legacy = flyway(dataSource, "16");
        legacy.migrate();
        jdbc.update("INSERT INTO flyway_schema_history "
                + "(installed_rank, version, description, type, script, checksum, installed_by, "
                + "installed_on, execution_time, success) VALUES "
                + "(17, '17', 'aftersale completed unique and order timeouts', 'SQL', "
                + "'V17__aftersale_completed_unique_and_order_timeouts.sql', ?, 'test', "
                + "CURRENT_TIMESTAMP, 1, 0)", v17Checksum(dataSource) + 1);

        Flyway latest = flyway(dataSource);
        assertThatThrownBy(() -> new LegacyAfterSaleMigrationPreflight(dataSource).migrate(latest))
                .hasMessageContaining("checksum");
    }

    @Test
    void wrongGeneratedExpressionFailsClosedEvenWhenHistoryClaimsSuccess() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway legacy = flyway(dataSource, "16");
        legacy.migrate();
        jdbc.execute("ALTER TABLE trade_after_sale ADD COLUMN completed_order_id BIGINT "
                + "GENERATED ALWAYS AS (CASE WHEN status = 'COMPLETED' THEN order_id + 1 ELSE NULL END) STORED");
        jdbc.execute("ALTER TABLE trade_after_sale ADD UNIQUE KEY uk_after_sale_completed_order (completed_order_id)");
        insertV17HistoryRow(jdbc, v17Checksum(dataSource), 17, true);

        assertThatThrownBy(() -> new LegacyAfterSaleMigrationPreflight(dataSource).migrate(flyway(dataSource)))
                .hasMessageContaining("generated column")
                .hasMessageContaining("invalid");
    }

    @Test
    void wrongGeneratedColumnMetadataFailsClosed() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway legacy = flyway(dataSource, "16");
        legacy.migrate();
        jdbc.execute("ALTER TABLE trade_after_sale ADD COLUMN completed_order_id BIGINT UNSIGNED "
                + "GENERATED ALWAYS AS (CASE WHEN status = 'COMPLETED' THEN order_id ELSE NULL END) VIRTUAL");
        jdbc.execute("ALTER TABLE trade_after_sale ADD UNIQUE KEY uk_after_sale_completed_order (completed_order_id)");
        insertV17HistoryRow(jdbc, v17Checksum(dataSource), 17, true);

        assertThatThrownBy(() -> new LegacyAfterSaleMigrationPreflight(dataSource).migrate(flyway(dataSource)))
                .hasMessageContaining("generated column")
                .hasMessageContaining("invalid");
    }

    @Test
    void duplicateV17HistoryRowsFailClosedBeforeAnyRepair() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway legacy = flyway(dataSource, "16");
        legacy.migrate();
        int checksum = v17Checksum(dataSource);
        insertV17HistoryRow(jdbc, checksum, 17, true);
        insertV17HistoryRow(jdbc, checksum, 18, false);

        assertThatThrownBy(() -> new LegacyAfterSaleMigrationPreflight(dataSource).migrate(flyway(dataSource)))
                .hasMessageContaining("exactly one V17 row");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '17'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void duplicateRepairWorksWithoutStateEnteredAtOrAuditTable() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway legacy = flyway(dataSource, "15");
        legacy.migrate();
        jdbc.execute("DROP TABLE operation_audit_log");
        long orderId = insertOrder(jdbc);
        long applicantId = insertUser(jdbc);
        long noncanonicalId = insertAfterSale(jdbc, orderId, applicantId,
                null, null, "2026-01-01 00:00:00.000", "old-schema-null", false);
        long canonicalId = insertAfterSale(jdbc, orderId, applicantId,
                "2026-01-01 00:00:00.000", null, "2026-01-02 00:00:00.000", "old-schema-canonical", false);
        insertProof(jdbc, noncanonicalId);

        new LegacyAfterSaleMigrationPreflight(dataSource).migrate(flyway(dataSource));

        assertThat(jdbc.queryForObject("SELECT status FROM trade_after_sale WHERE id = ?", String.class, canonicalId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM trade_after_sale WHERE id = ?", String.class, noncanonicalId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT admin_reason FROM trade_after_sale WHERE id = ?", String.class, noncanonicalId))
                .isEqualTo(LegacyAfterSaleMigrationPreflight.REPAIR_REASON);
        assertThat(jdbc.queryForObject("SELECT version FROM trade_after_sale WHERE id = ?", Integer.class, noncanonicalId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = 'operation_audit_log'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_after_sale_proof WHERE after_sale_id = ?",
                Integer.class, noncanonicalId)).isEqualTo(1);
    }

    @Test
    void independentAdvisoryLockHolderBlocksPreflightAndRepairsOnce() throws Exception {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway legacy = flyway(dataSource, "16");
        legacy.migrate();
        long orderId = insertOrder(jdbc);
        long applicantId = insertUser(jdbc);
        insertAfterSale(jdbc, orderId, applicantId,
                "2026-01-02 00:00:00.000", "2026-01-02 00:00:00.000",
                "2026-01-02 00:00:00.000", "lock-first", true);
        insertAfterSale(jdbc, orderId, applicantId,
                "2026-01-01 00:00:00.000", "2026-01-01 00:00:00.000",
                "2026-01-01 00:00:00.000", "lock-canonical", true);
        Flyway latest = flyway(dataSource);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            boolean lockHeld = acquireAdvisoryLock(blocker);
            try {
                Future<?> future = executor.submit(() -> new LegacyAfterSaleMigrationPreflight(dataSource).migrate(latest));
                Thread.sleep(300);
                assertThat(future).isNotNull();
                assertThat(future.isDone()).as("preflight must block on the independent advisory lock").isFalse();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_after_sale "
                        + "WHERE order_id = ? AND status = 'COMPLETED'", Integer.class, orderId)).isEqualTo(2);
                assertThat(releaseAdvisoryLock(blocker)).isEqualTo(1);
                lockHeld = false;
                future.get(15, TimeUnit.SECONDS);
            } finally {
                if (lockHeld) {
                    releaseAdvisoryLock(blocker);
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_after_sale "
                + "WHERE order_id = ? AND status = 'COMPLETED'", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log "
                + "WHERE actor_type = 'SYSTEM' AND action = 'LEGACY_AFTERSALE_V17_REPAIR'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void twoPreflightsSerializeOnTheNamedAdvisoryLock() throws Exception {
        DataSource dataSource = dataSource();
        Flyway legacy = flyway(dataSource, "16");
        legacy.migrate();
        LegacyAfterSaleMigrationPreflight preflight = new LegacyAfterSaleMigrationPreflight(dataSource);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> runPreflight(preflight, dataSource, start));
            Future<?> second = executor.submit(() -> runPreflight(preflight, dataSource, start));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT IS_FREE_LOCK('market-shop:legacy-aftersale-v17')", Integer.class)).isEqualTo(1);
    }

    private void runPreflight(LegacyAfterSaleMigrationPreflight preflight, DataSource dataSource,
                              CountDownLatch start) {
        try {
            start.await(5, TimeUnit.SECONDS);
            preflight.migrate(flyway(dataSource));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .cleanDisabled(false)
                .load();
    }

    private Flyway flyway(DataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
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

    private long insertOrder(JdbcTemplate jdbc) {
        long userId = insertUser(jdbc);
        String orderNo = "ORDER" + System.nanoTime();
        jdbc.update("INSERT INTO trade_order (order_no, buyer_user_id, superior_user_id, status, "
                + "total_amount_fen, address_snapshot_json, source, client_request_id) "
                + "VALUES (?, ?, ?, 'PENDING_SUPERIOR', 1, JSON_OBJECT(), 'MINIPROGRAM', ?)",
                orderNo, userId, userId, "request-" + System.nanoTime());
        return jdbc.queryForObject("SELECT id FROM trade_order WHERE order_no = ?", Long.class, orderNo);
    }

    private long insertUser(JdbcTemplate jdbc) {
        String publicId = "01JPRE" + System.nanoTime();
        jdbc.update("INSERT INTO iam_user_account (public_id, status, nickname) VALUES (?, 'ACTIVE', ?)",
                publicId, "测试会员");
        return jdbc.queryForObject("SELECT id FROM iam_user_account WHERE public_id = ?", Long.class, publicId);
    }

    private long insertCompletedAfterSale(JdbcTemplate jdbc, long orderId, long applicantId,
                                          String completedAt, String createdAt, String requestSuffix) {
        return insertAfterSale(jdbc, orderId, applicantId, completedAt, createdAt, createdAt, requestSuffix, true);
    }

    private long insertAfterSale(JdbcTemplate jdbc, long orderId, long applicantId,
                                 String completedAt, String stateEnteredAt, String createdAt,
                                 String requestSuffix, boolean hasStateEnteredAt) {
        String afterSaleNo = "AS" + System.nanoTime();
        String completedValue = completedAt == null ? "NULL" : "?";
        String stateColumn = hasStateEnteredAt ? "state_entered_at, " : "";
        String stateValue = hasStateEnteredAt
                ? (stateEnteredAt == null ? "NULL, " : "?, ")
                : "";
        String sql = "INSERT INTO trade_after_sale "
                + "(after_sale_no, order_id, applicant_user_id, type, status, reason, completed_at, "
                + stateColumn + "client_request_id, created_at, updated_at) VALUES "
                + "(?, ?, ?, 'REFUND_ONLY', 'COMPLETED', '测试', " + completedValue + ", "
                + stateValue + "?, ?, ?)";
        List<Object> arguments = new ArrayList<>();
        arguments.add(afterSaleNo);
        arguments.add(orderId);
        arguments.add(applicantId);
        if (completedAt != null) {
            arguments.add(completedAt);
        }
        if (hasStateEnteredAt && stateEnteredAt != null) {
            arguments.add(stateEnteredAt);
        }
        arguments.add("request-" + requestSuffix + System.nanoTime());
        arguments.add(createdAt);
        arguments.add(createdAt);
        jdbc.update(sql, arguments.toArray());
        return jdbc.queryForObject("SELECT id FROM trade_after_sale WHERE after_sale_no = ?", Long.class, afterSaleNo);
    }

    private void insertProof(JdbcTemplate jdbc, long afterSaleId) {
        jdbc.update("INSERT INTO trade_after_sale_proof "
                + "(after_sale_id, proof_type, object_key, sha256, media_type, size_bytes) "
                + "VALUES (?, 'APPLICATION', ?, ?, 'image/png', 1)", afterSaleId,
                "preflight/proof/" + afterSaleId, "b".repeat(64));
    }

    private long insertLedgerAccount(JdbcTemplate jdbc, long userId) {
        jdbc.update("INSERT INTO ledger_account "
                + "(user_id, account_type, available_points, frozen_points) VALUES (?, 'POINTS', 10, 4)", userId);
        return jdbc.queryForObject("SELECT id FROM ledger_account WHERE user_id = ? AND account_type = 'POINTS'",
                Long.class, userId);
    }

    private void insertOutboxEvent(JdbcTemplate jdbc, long orderId) {
        String eventId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sys_outbox_event "
                + "(event_id, aggregate_type, aggregate_id, event_type, payload_json, occurred_at, status, next_attempt_at) "
                + "VALUES (?, 'ORDER', ?, 'TEST_EVENT', JSON_OBJECT(), CURRENT_TIMESTAMP(3), 'PENDING', CURRENT_TIMESTAMP(3))",
                eventId, Long.toString(orderId));
    }

    private long scalar(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private void insertV17HistoryRow(JdbcTemplate jdbc, int checksum, int installedRank, boolean success) {
        jdbc.update("INSERT INTO flyway_schema_history "
                + "(installed_rank, version, description, type, script, checksum, installed_by, "
                + "installed_on, execution_time, success) VALUES (?, '17', "
                + "'aftersale completed unique and order timeouts', 'SQL', "
                + "'V17__aftersale_completed_unique_and_order_timeouts.sql', ?, 'test', "
                + "CURRENT_TIMESTAMP, 1, ?)", installedRank, checksum, success);
    }

    private boolean acquireAdvisoryLock(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT GET_LOCK('market-shop:legacy-aftersale-v17', 5)")) {
            rows.next();
            return rows.getInt(1) == 1;
        }
    }

    private int releaseAdvisoryLock(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT RELEASE_LOCK('market-shop:legacy-aftersale-v17')")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private int v17Checksum(DataSource dataSource) {
        return java.util.Arrays.stream(flyway(dataSource).info().all())
                .filter(info -> "V17__aftersale_completed_unique_and_order_timeouts.sql".equals(info.getScript()))
                .findFirst()
                .orElseThrow()
                .getResolvedChecksum();
    }
}

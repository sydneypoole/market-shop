package com.marketshop.bootstrap.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

@Component
public final class LegacyAfterSaleMigrationPreflight implements FlywayMigrationStrategy {

    static final String ADVISORY_LOCK_NAME = "market-shop:legacy-aftersale-v17";
    static final String V17_SCRIPT = "V17__aftersale_completed_unique_and_order_timeouts.sql";
    static final String V17_DESCRIPTION = "aftersale completed unique and order timeouts";
    static final String V18_SCRIPT = "db.migration.V18__repair_distribution_projections";
    static final String V18_DESCRIPTION = "repair distribution projections";
    public static final String REPAIR_ACTION = "LEGACY_AFTERSALE_V17_REPAIR";
    public static final String REPAIR_REASON = "系统迁移修复：V17重复完成售后";

    private static final Logger log = LoggerFactory.getLogger(LegacyAfterSaleMigrationPreflight.class);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_$]+");
    private static final int V17_VERSION = 17;
    private static final int V18_VERSION = 18;
    private static final int LOCK_TIMEOUT_SECONDS = 30;
    private static final String EXPECTED_GENERATED_EXPRESSION =
            "CASEWHENSTATUS=COMPLETEDTHENORDER_IDELSENULLEND";

    private final DataSource dataSource;

    public LegacyAfterSaleMigrationPreflight(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void migrate(Flyway flyway) {
        Objects.requireNonNull(flyway, "flyway");
        try (Connection connection = dataSource.getConnection()) {
            acquireLock(connection);
            try {
                PreflightResult result = inspectAndRepair(connection, flyway);
                if (result.repairFailedMigration()) {
                    flyway.repair();
                }
                flyway.migrate();
            } finally {
                releaseLockQuietly(connection);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Legacy after-sale migration preflight database access failed", exception);
        }
    }

    private PreflightResult inspectAndRepair(Connection connection, Flyway flyway) {
        try {
            boolean historyExists = tableExists(connection, flyway.getConfiguration().getTable());
            boolean afterSaleExists = tableExists(connection, "trade_after_sale");
            if (!historyExists && !afterSaleExists) {
                log.info("Legacy after-sale migration preflight version={} artifacts=none duplicateGroups=0 duplicateRows=0",
                        V17_VERSION);
                return PreflightResult.NOOP;
            }
            if (!historyExists) {
                throw new IllegalStateException("Legacy after-sale preflight found trade_after_sale without Flyway history");
            }
            if (!afterSaleExists) {
                if (tableRowCount(connection, flyway.getConfiguration().getTable()) == 0) {
                    log.info("Legacy after-sale migration preflight version={} artifacts=none duplicateGroups=0 duplicateRows=0",
                            V17_VERSION);
                    return PreflightResult.NOOP;
                }
                throw new IllegalStateException("Legacy after-sale preflight found Flyway history without trade_after_sale");
            }

            String historyTable = flyway.getConfiguration().getTable();
            int v17HistoryRows = historyRowCount(connection, historyTable, "17");
            if (v17HistoryRows > 1) {
                throw new IllegalStateException("Flyway history must contain exactly one V17 row, found "
                        + v17HistoryRows);
            }
            int v18HistoryRows = historyRowCount(connection, historyTable, "18");
            if (v18HistoryRows > 1) {
                throw new IllegalStateException("Flyway history must contain at most one V18 row, found "
                        + v18HistoryRows);
            }
            MigrationInfo[] migrationInfos = flyway.info().all();
            HistoryValidation history = validateHistory(migrationInfos, v17HistoryRows, v18HistoryRows);
            MigrationInfo v17 = history.v17();
            MigrationState state = v17.getState();
            ArtifactState artifacts = artifactState(connection);
            if (state == MigrationState.SUCCESS) {
                verifySuccessfulV17(connection, artifacts);
                log.info("Legacy after-sale migration preflight version={} artifacts=complete duplicateGroups=0 duplicateRows=0",
                        V17_VERSION);
                if (history.repairFailedV18()) {
                    log.info("Flyway protected recovery version={} script={} state=FAILED action=repair-and-rerun",
                            V18_VERSION, V18_SCRIPT);
                }
                return new PreflightResult(history.repairFailedV18());
            }
            if (state == MigrationState.FAILED) {
                validateFailedV17(v17);
                if (artifacts.any()) {
                    throw new IllegalStateException("V17 failed with partial or ambiguous artifacts; automatic repair refused");
                }
                RepairCounts counts = repairDuplicates(connection);
                log.info("Legacy after-sale migration preflight version={} artifacts=none duplicateGroups={} duplicateRows={}",
                        V17_VERSION, counts.groups(), counts.rows());
                return new PreflightResult(true);
            }
            if (state != MigrationState.PENDING) {
                throw new IllegalStateException("Legacy after-sale preflight cannot safely handle V17 state " + state);
            }
            if (artifacts.any()) {
                throw new IllegalStateException("V17 has partial or ambiguous artifacts before migration; automatic repair refused");
            }
            RepairCounts counts = repairDuplicates(connection);
            log.info("Legacy after-sale migration preflight version={} artifacts=none duplicateGroups={} duplicateRows={}",
                    V17_VERSION, counts.groups(), counts.rows());
            return PreflightResult.NOOP;
        } catch (SQLException exception) {
            throw new IllegalStateException("Legacy after-sale migration preflight inspection failed", exception);
        }
    }

    private HistoryValidation validateHistory(MigrationInfo[] migrationInfos, int v17HistoryRows,
                                                int v18HistoryRows) {
        List<MigrationInfo> v17Records = new ArrayList<>();
        List<MigrationInfo> failedRecords = new ArrayList<>();
        for (MigrationInfo info : migrationInfos) {
            if (isVersion17(info)) {
                v17Records.add(info);
            }
            if (info.getState().isFailed()) {
                failedRecords.add(info);
            }
        }
        if (v17Records.size() != 1) {
            throw new IllegalStateException("Flyway history must expose exactly one V17 migration record, found "
                    + v17Records.size());
        }

        MigrationInfo v17 = v17Records.get(0);
        if (v17.isApplied() && v17HistoryRows != 1) {
            throw new IllegalStateException("Flyway history must contain exactly one applied V17 row, found "
                    + v17HistoryRows);
        }
        if (!v17.isApplied() && v17HistoryRows != 0) {
            throw new IllegalStateException("Flyway history contains V17 rows for a non-applied V17 state");
        }

        if (failedRecords.size() > 1) {
            throw new IllegalStateException("Flyway history contains multiple failed migrations: "
                    + failedRecords.stream().map(LegacyAfterSaleMigrationPreflight::migrationDiagnostic)
                    .toList());
        }

        boolean repairFailedV18 = false;
        if (!failedRecords.isEmpty()) {
            MigrationInfo failed = failedRecords.getFirst();
            if (failed == v17 && failed.getState() != MigrationState.FAILED) {
                throw unsupportedFailedMigration(failed);
            }
            if (failed != v17) {
                if (!isVersion18(failed)) {
                    throw unsupportedFailedMigration(failed);
                }
                validateFailedV18(failed, v18HistoryRows);
                if (v17.getState() != MigrationState.SUCCESS) {
                    throw new IllegalStateException("V18 recovery requires successful V17; failed migration "
                            + migrationDiagnostic(failed));
                }
                repairFailedV18 = true;
            }
        }

        for (MigrationInfo info : migrationInfos) {
            if (info.getState() == MigrationState.MISSING_SUCCESS
                    || info.getState() == MigrationState.MISSING_FAILED
                    || info.getState() == MigrationState.FUTURE_SUCCESS
                    || info.getState() == MigrationState.FUTURE_FAILED
                    || info.getState() == MigrationState.DELETED) {
                throw new IllegalStateException("Flyway history contains an unresolved migration state "
                        + migrationDiagnostic(info));
            }
            if (info.isApplied() && info.getState() != MigrationState.FAILED
                    && (!Objects.equals(info.getAppliedChecksum(), info.getResolvedChecksum())
                    || !info.isDescriptionMatching() || !info.isTypeMatching())) {
                throw new IllegalStateException("Flyway checksum or metadata mismatch for migration "
                        + migrationDiagnostic(info));
            }
        }
        return new HistoryValidation(v17, repairFailedV18);
    }

    private void validateFailedV17(MigrationInfo info) {
        if (!isExpectedV17(info) || info.getState() != MigrationState.FAILED
                || !info.isChecksumMatching() || !info.isDescriptionMatching() || !info.isTypeMatching()
                || !Objects.equals(info.getAppliedChecksum(), info.getResolvedChecksum())) {
            throw new IllegalStateException("V17 failed migration checksum or script does not match the expected "
                    + "artifact; " + migrationDiagnostic(info));
        }
    }

    private void validateFailedV18(MigrationInfo info, int v18HistoryRows) {
        if (v18HistoryRows != 1 || !isExpectedV18(info) || info.getState() != MigrationState.FAILED
                || !info.isChecksumMatching() || !info.isDescriptionMatching() || !info.isTypeMatching()
                || !Objects.equals(info.getAppliedChecksum(), info.getResolvedChecksum())) {
            throw new IllegalStateException("V18 failed migration does not match the expected JDBC artifact; "
                    + migrationDiagnostic(info));
        }
    }

    private void verifySuccessfulV17(Connection connection, ArtifactState artifacts) throws SQLException {
        if (!artifacts.complete()) {
            throw new IllegalStateException("V17 is successful but its generated column or unique index is missing or invalid");
        }
        if (duplicateGroupCount(connection) != 0) {
            throw new IllegalStateException("V17 is successful but the completed after-sale uniqueness invariant is violated");
        }
    }

    private RepairCounts repairDuplicates(Connection connection) throws SQLException {
        boolean hasStateEnteredAt = columnExists(connection, "trade_after_sale", "state_entered_at");
        boolean hasAdminReason = columnExists(connection, "trade_after_sale", "admin_reason");
        boolean hasAudit = tableExists(connection, "operation_audit_log");
        List<Long> orderIds = duplicateOrderIds(connection);
        if (orderIds.isEmpty()) {
            return RepairCounts.NONE;
        }

        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            int repairedRows = 0;
            for (Long orderId : orderIds) {
                List<AfterSaleRow> rows = completedRows(connection, orderId, hasStateEnteredAt);
                if (rows.size() < 2) {
                    continue;
                }
                for (int index = 1; index < rows.size(); index++) {
                    AfterSaleRow row = rows.get(index);
                    updateDuplicate(connection, row.id(), hasStateEnteredAt, hasAdminReason);
                    if (hasAudit) {
                        insertAudit(connection, row, orderId);
                    }
                    repairedRows++;
                }
            }
            connection.commit();
            connection.setAutoCommit(originalAutoCommit);
            return new RepairCounts(orderIds.size(), repairedRows);
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly(connection, true);
            restoreAutoCommitQuietly(connection, originalAutoCommit);
            throw exception;
        }
    }

    private void updateDuplicate(Connection connection, long afterSaleId, boolean hasStateEnteredAt,
                                 boolean hasAdminReason) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE trade_after_sale SET status = 'CANCELLED', version = version + 1");
        if (hasStateEnteredAt) {
            sql.append(", state_entered_at = CURRENT_TIMESTAMP(3)");
        }
        if (hasAdminReason) {
            sql.append(", admin_reason = ?");
        }
        sql.append(" WHERE id = ? AND status = 'COMPLETED'");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            if (hasAdminReason) {
                statement.setString(parameter++, REPAIR_REASON);
            }
            statement.setLong(parameter, afterSaleId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Legacy after-sale duplicate changed during migration preflight");
            }
        }
    }

    private void insertAudit(Connection connection, AfterSaleRow row, long orderId) throws SQLException {
        String beforeJson = "{\"status\":\"COMPLETED\",\"orderId\":" + orderId + "}";
        String afterJson = "{\"status\":\"CANCELLED\",\"orderId\":" + orderId + "}";
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO operation_audit_log
                    (actor_type, actor_id, action, resource_type, resource_id,
                     before_json, after_json, reason, request_id, occurred_at)
                VALUES ('SYSTEM', 'migration-v17', ?, 'TRADE_AFTER_SALE', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3))
                """)) {
            statement.setString(1, REPAIR_ACTION);
            statement.setString(2, Long.toString(row.id()));
            statement.setString(3, beforeJson);
            statement.setString(4, afterJson);
            statement.setString(5, REPAIR_REASON);
            statement.setString(6, "legacy-after-sale-v17-" + row.id());
            statement.executeUpdate();
        }
    }

    private List<Long> duplicateOrderIds(Connection connection) throws SQLException {
        List<Long> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT order_id
                FROM trade_after_sale
                WHERE status = 'COMPLETED'
                GROUP BY order_id
                HAVING COUNT(*) > 1
                ORDER BY order_id
                """)) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(rows.getLong(1));
                }
            }
        }
        return result;
    }

    private List<AfterSaleRow> completedRows(Connection connection, long orderId, boolean hasStateEnteredAt)
            throws SQLException {
        String orderBy = "completed_at IS NULL, completed_at, "
                + (hasStateEnteredAt ? "state_entered_at, " : "")
                + "created_at, id";
        String sql = "SELECT id FROM trade_after_sale WHERE order_id = ? AND status = 'COMPLETED' ORDER BY "
                + orderBy;
        List<AfterSaleRow> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new AfterSaleRow(rows.getLong(1)));
                }
            }
        }
        return result;
    }

    private ArtifactState artifactState(Connection connection) throws SQLException {
        boolean column = columnExists(connection, "trade_after_sale", "completed_order_id");
        boolean index = indexExists(connection, "trade_after_sale", "uk_after_sale_completed_order");
        if (!column && !index) {
            return ArtifactState.NONE;
        }
        if (!column || !index || !generatedCompletedOrderColumn(connection) || !uniqueCompletedOrderIndex(connection)) {
            return ArtifactState.PARTIAL;
        }
        return ArtifactState.COMPLETE;
    }

    private boolean generatedCompletedOrderColumn(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT data_type, column_type, is_nullable, extra, generation_expression
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'trade_after_sale'
                  AND column_name = 'completed_order_id'
                """)) {
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return false;
                }
                String dataType = rows.getString(1);
                String columnType = rows.getString(2);
                String nullable = rows.getString(3);
                String extra = rows.getString(4);
                String expression = rows.getString(5);
                return "bigint".equalsIgnoreCase(dataType)
                        && "bigint".equalsIgnoreCase(columnType)
                        && "YES".equalsIgnoreCase(nullable)
                        && "STORED GENERATED".equalsIgnoreCase(extra == null ? "" : extra.trim())
                        && EXPECTED_GENERATED_EXPRESSION.equals(normalizeGeneratedExpression(expression));
            }
        }
    }

    private static String normalizeGeneratedExpression(String expression) {
        if (expression == null) {
            return "";
        }
        return expression.toUpperCase(Locale.ROOT)
                .replace("_UTF8MB4", "")
                .replace("`", "")
                .replace("'", "")
                .replace("\"", "")
                .replace("\\", "")
                .replace("(", "")
                .replace(")", "")
                .replaceAll("\\s+", "");
    }

    private boolean uniqueCompletedOrderIndex(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT non_unique, COUNT(*), MIN(column_name), MAX(column_name), MIN(seq_in_index), MAX(seq_in_index)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'trade_after_sale'
                  AND index_name = 'uk_after_sale_completed_order'
                GROUP BY non_unique
                """)) {
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        && rows.getInt(1) == 0
                        && rows.getInt(2) == 1
                        && "completed_order_id".equals(rows.getString(3))
                        && "completed_order_id".equals(rows.getString(4))
                        && rows.getInt(5) == 1
                        && rows.getInt(6) == 1;
            }
        }
    }

    private int duplicateGroupCount(Connection connection) throws SQLException {
        return duplicateOrderIds(connection).size();
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        String identifier = safeIdentifier(tableName);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """)) {
            statement.setString(1, identifier);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1) == 1;
            }
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        safeIdentifier(tableName);
        safeIdentifier(columnName);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1) == 1;
            }
        }
    }

    private boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        safeIdentifier(tableName);
        safeIdentifier(indexName);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, indexName);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1) > 0;
            }
        }
    }

    private int historyRowCount(Connection connection, String tableName, String version) throws SQLException {
        String quotedTable = quoteIdentifier(tableName);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + quotedTable + " WHERE version = ?")) {
            statement.setString(1, version);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private int tableRowCount(Connection connection, String tableName) throws SQLException {
        String quotedTable = quoteIdentifier(tableName);
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + quotedTable)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private void acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, ADVISORY_LOCK_NAME);
            statement.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 1) {
                    throw new IllegalStateException("Could not acquire MySQL advisory lock for legacy V17 preflight");
                }
            }
        }
    }

    private void releaseLockQuietly(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, ADVISORY_LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("Legacy after-sale migration preflight could not release advisory lock");
        }
    }

    private static boolean isVersion17(MigrationInfo info) {
        return info.getVersion() != null && "17".equals(info.getVersion().toString());
    }

    private static boolean isExpectedV17(MigrationInfo info) {
        return isVersion17(info)
                && V17_SCRIPT.equals(info.getScript())
                && V17_DESCRIPTION.equals(info.getDescription());
    }

    private static boolean isVersion18(MigrationInfo info) {
        return info.getVersion() != null && "18".equals(info.getVersion().toString());
    }

    private static boolean isExpectedV18(MigrationInfo info) {
        return isVersion18(info)
                && V18_SCRIPT.equals(info.getScript())
                && V18_DESCRIPTION.equals(info.getDescription());
    }

    private static IllegalStateException unsupportedFailedMigration(MigrationInfo info) {
        return new IllegalStateException("Flyway history contains an unsupported failed migration: "
                + migrationDiagnostic(info));
    }

    private static String migrationDiagnostic(MigrationInfo info) {
        String version = info.getVersion() == null ? "<none>" : info.getVersion().toString();
        return "version=" + safeDiagnosticValue(version)
                + ", script=" + safeDiagnosticValue(info.getScript())
                + ", state=" + info.getState();
    }

    private static String safeDiagnosticValue(String value) {
        if (value == null) {
            return "<none>";
        }
        String sanitized = value.replaceAll("[\\r\\n\\t\\p{Cntrl}]", "?");
        return sanitized.length() <= 200 ? sanitized : sanitized.substring(0, 200);
    }

    private static String safeIdentifier(String identifier) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid Flyway table identifier");
        }
        return identifier;
    }

    private static String quoteIdentifier(String identifier) {
        return "`" + safeIdentifier(identifier) + "`";
    }

    private static void rollbackQuietly(Connection connection, boolean transactionStarted) {
        if (!transactionStarted) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException exception) {
            log.warn("Legacy after-sale migration preflight rollback failed");
        }
    }

    private static void restoreAutoCommitQuietly(Connection connection, boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException exception) {
            log.warn("Legacy after-sale migration preflight could not restore transaction mode");
        }
    }

    private record PreflightResult(boolean repairFailedMigration) {
        private static final PreflightResult NOOP = new PreflightResult(false);
    }

    private record HistoryValidation(MigrationInfo v17, boolean repairFailedV18) {
    }

    private record RepairCounts(int groups, int rows) {
        private static final RepairCounts NONE = new RepairCounts(0, 0);
    }

    private record AfterSaleRow(long id) {
    }

    private enum ArtifactState {
        NONE,
        PARTIAL,
        COMPLETE;

        private boolean any() {
            return this != NONE;
        }

        private boolean complete() {
            return this == COMPLETE;
        }
    }
}

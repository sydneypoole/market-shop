package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rebuilds only the derived frozen-point projections from the immutable ledger.
 */
public class V18__repair_distribution_projections extends BaseJavaMigration {

    private static final String CONFLICT = "FROZEN_BATCH_BALANCE_CONFLICT";

    @Override
    public void migrate(Context context) throws Exception {
        new Repair(context.getConnection()).run();
    }

    private static final class Repair {

        private final Connection connection;

        private Repair(Connection connection) {
            this.connection = connection;
        }

        private void run() throws SQLException {
            List<LedgerEntry> initialEntries = loadLedgerEntries();
            Map<Long, List<ExistingReleaseItem>> existingReleaseItems = loadExistingReleaseItems();
            Timestamp repairTimestamp = deterministicRepairTimestamp(initialEntries);
            Replay initialReplay = replay(initialEntries, existingReleaseItems);
            repairDuplicateDirectPerformances(initialEntries, initialReplay, repairTimestamp);

            List<LedgerEntry> entries = loadLedgerEntries();
            Replay replay = replay(entries, existingReleaseItems);
            validateAccountBalances(entries, replay);
            applyDerivedProjection(replay);
        }

        private List<LedgerEntry> loadLedgerEntries() throws SQLException {
            List<LedgerEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, account_id, entry_type, available_delta, frozen_delta,
                           source_type, source_id, source_order_id, rule_version_id,
                           original_entry_id, occurred_at
                    FROM ledger_entry
                    ORDER BY occurred_at, id
                    """)) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        entries.add(new LedgerEntry(
                                rows.getLong("id"),
                                rows.getLong("account_id"),
                                rows.getString("entry_type"),
                                rows.getLong("available_delta"),
                                rows.getLong("frozen_delta"),
                                rows.getString("source_type"),
                                nullableLong(rows, "source_id"),
                                nullableLong(rows, "source_order_id"),
                                nullableLong(rows, "rule_version_id"),
                                nullableLong(rows, "original_entry_id"),
                                rows.getTimestamp("occurred_at")
                        ));
                    }
                }
            }
            return entries;
        }

        private Map<Long, List<ExistingReleaseItem>> loadExistingReleaseItems() throws SQLException {
            Map<Long, List<ExistingReleaseItem>> result = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT item.release_ledger_entry_id, batch.source_ledger_entry_id, item.points,
                           item.created_at
                    FROM ledger_frozen_release_item item
                    JOIN ledger_frozen_batch batch ON batch.id = item.frozen_batch_id
                    ORDER BY item.release_ledger_entry_id, item.id
                    """)) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.computeIfAbsent(rows.getLong(1), ignored -> new ArrayList<>())
                                .add(new ExistingReleaseItem(
                                        rows.getLong(2), rows.getLong(3), rows.getTimestamp(4)
                                ));
                    }
                }
            }
            return result;
        }

        private Timestamp deterministicRepairTimestamp(List<LedgerEntry> entries) {
            Timestamp latest = null;
            for (LedgerEntry entry : entries) {
                if (entry.occurredAt == null) {
                    throw conflict("ledger entry has no occurred_at");
                }
                if (latest == null || entry.occurredAt.after(latest)) {
                    latest = entry.occurredAt;
                }
            }
            return latest == null ? null : addMillis(latest, 1L);
        }

        private static Timestamp addMillis(Timestamp base, long millis) {
            try {
                return new Timestamp(Math.addExact(base.getTime(), millis));
            } catch (ArithmeticException exception) {
                throw conflict("deterministic repair timestamp overflow");
            }
        }

        private Replay replay(List<LedgerEntry> entries,
                              Map<Long, List<ExistingReleaseItem>> existingReleaseItems) throws SQLException {
            Replay replay = new Replay(existingReleaseItems);
            for (LedgerEntry entry : entries) {
                replay.byId.put(entry.id, entry);
                switch (entry.entryType) {
                    case "DIRECT_REFERRAL_AWARD" -> replayAward(entry, replay);
                    case "FROZEN_POINTS_RELEASED" -> replayRelease(entry, replay);
                    case "REVERSAL" -> replayReversal(entry, replay);
                    default -> {
                        if (entry.entryType != null && entry.frozenDelta != 0
                                && entry.originalEntryId != null) {
                            throw conflict("unsupported frozen ledger reversal");
                        }
                    }
                }
            }
            return replay;
        }

        private void replayAward(LedgerEntry entry, Replay replay) {
            if (entry.availableDelta < 0 || entry.frozenDelta <= 0
                    || !"DIRECT_PERFORMANCE".equals(entry.sourceType)
                    || entry.sourceId == null || entry.sourceOrderId == null
                    || !entry.sourceId.equals(entry.sourceOrderId)
                    || entry.ruleVersionId == null) {
                throw conflict("invalid direct award source facts");
            }
            BatchState existing = replay.batchesBySourceEntry.get(entry.id);
            if (existing != null) {
                throw conflict("duplicate source ledger entry");
            }
            BatchState batch = new BatchState(
                    entry.accountId,
                    entry.id,
                    entry.sourceOrderId,
                    entry.ruleVersionId,
                    entry.frozenDelta,
                    entry.frozenDelta,
                    false,
                    entry.occurredAt
            );
            replay.batchesBySourceEntry.put(entry.id, batch);
            replay.batchesByAccount.computeIfAbsent(entry.accountId, ignored -> new ArrayList<>()).add(batch);
        }

        private void replayRelease(LedgerEntry entry, Replay replay) {
            if (entry.frozenDelta >= 0
                    || entry.availableDelta <= 0
                    || !"FROZEN_BATCH".equals(entry.sourceType)
                    || entry.sourceOrderId == null || entry.ruleVersionId == null
                    || entry.availableDelta != positiveMagnitude(entry.frozenDelta)) {
                throw conflict("frozen release has inconsistent source facts");
            }
            long requested = positiveMagnitude(entry.frozenDelta);
            if (entry.originalEntryId != null) {
                replayExplicitRelease(entry, requested, replay);
                return;
            }
            replayLegacyFifoRelease(entry, requested, replay);
        }

        private void replayExplicitRelease(LedgerEntry entry, long requested, Replay replay) {
            LedgerEntry source = replay.byId.get(entry.originalEntryId);
            BatchState batch = replay.batchesBySourceEntry.get(entry.originalEntryId);
            if (source == null || batch == null
                    || !"DIRECT_REFERRAL_AWARD".equals(source.entryType)
                    || source.accountId != entry.accountId
                    || source.sourceOrderId == null || source.ruleVersionId == null
                    || source.frozenDelta <= 0 || batch.reversed
                    || batch.remainingPoints < requested
                    || batch.originalPoints != source.frozenDelta
                    || batch.sourceOrderId != source.sourceOrderId
                    || batch.ruleVersionId != source.ruleVersionId) {
                throw conflict("explicit frozen release source is inconsistent");
            }
            validateExistingExplicitMapping(entry.id, entry.originalEntryId, requested, replay);
            batch.remainingPoints -= requested;
            replay.releaseAllocations.put(entry.id, List.of(new AllocationState(batch, requested)));
        }

        private void replayLegacyFifoRelease(LedgerEntry entry, long requested, Replay replay) {
            long remaining = requested;
            List<AllocationState> allocations = new ArrayList<>();
            List<BatchState> batches = replay.batchesByAccount.getOrDefault(entry.accountId, List.of());
            for (BatchState batch : batches) {
                if (remaining == 0) {
                    break;
                }
                if (batch.reversed || batch.remainingPoints == 0) {
                    continue;
                }
                long points = Math.min(remaining, batch.remainingPoints);
                batch.remainingPoints -= points;
                allocations.add(new AllocationState(batch, points));
                remaining -= points;
            }
            if (remaining != 0) {
                throw conflict("frozen release cannot be mapped to source batches");
            }
            replay.releaseAllocations.put(entry.id, allocations);
        }

        private void validateExistingExplicitMapping(long releaseEntryId, long sourceEntryId,
                                                     long points, Replay replay) {
            List<ExistingReleaseItem> existing = replay.existingReleaseItems.getOrDefault(
                    releaseEntryId, List.of());
            if (existing.isEmpty()) {
                return;
            }
            if (existing.size() != 1
                    || existing.getFirst().sourceLedgerEntryId != sourceEntryId
                    || existing.getFirst().points != points) {
                throw conflict("explicit release provenance conflicts with existing mapping");
            }
        }

        private void replayReversal(LedgerEntry entry, Replay replay) {
            if (entry.originalEntryId == null) {
                if (entry.frozenDelta != 0) {
                    throw conflict("frozen reversal has no source entry");
                }
                return;
            }
            LedgerEntry original = replay.byId.get(entry.originalEntryId);
            if (original == null) {
                if (entry.frozenDelta != 0) {
                    throw conflict("frozen reversal source entry is missing");
                }
                return;
            }
            if ("DIRECT_REFERRAL_AWARD".equals(original.entryType) && original.frozenDelta > 0) {
                replayDirectAwardReversal(entry, original, replay);
            } else if ("FROZEN_POINTS_RELEASED".equals(original.entryType) && original.frozenDelta < 0) {
                replayReleaseReversal(entry, original, replay);
            } else if (entry.frozenDelta != 0) {
                throw conflict("frozen reversal points to an ineligible source entry");
            }
        }

        private void replayDirectAwardReversal(LedgerEntry reversal, LedgerEntry original, Replay replay) {
            BatchState batch = replay.batchesBySourceEntry.get(original.id);
            if (batch == null || batch.reversed
                    || reversal.availableDelta != negateExact(original.availableDelta)
                    || reversal.frozenDelta != negateExact(batch.remainingPoints)) {
                throw conflict("direct award reversal does not match source batch");
            }
            batch.remainingPoints = 0;
            batch.reversed = true;
        }

        private void replayReleaseReversal(LedgerEntry reversal, LedgerEntry original, Replay replay) {
            List<AllocationState> allocations = replay.releaseAllocations.get(original.id);
            if (allocations == null) {
                throw conflict("release reversal has no release-item provenance");
            }
            long restored = 0;
            for (AllocationState allocation : allocations) {
                BatchState batch = allocation.batch;
                if (batch.reversed) {
                    continue;
                }
                if (batch.remainingPoints > batch.originalPoints - allocation.points) {
                    throw conflict("release reversal exceeds source batch");
                }
                batch.remainingPoints = addExact(batch.remainingPoints, allocation.points);
                restored = addExact(restored, allocation.points);
            }
            if (reversal.availableDelta != negateExact(restored) || reversal.frozenDelta != restored) {
                throw conflict("release reversal does not match release-item provenance");
            }
        }

        private void repairDuplicateDirectPerformances(List<LedgerEntry> entries, Replay replay,
                                                       Timestamp repairTimestamp)
                throws SQLException {
            List<DirectPerformance> activeRows = loadActiveDirectPerformances();
            Map<Long, Long> accountUsers = loadLedgerAccountUsers();
            Set<Pair> retained = new HashSet<>();
            long repairSequence = 0;
            for (DirectPerformance performance : activeRows) {
                Pair pair = new Pair(performance.beneficiaryUserId, performance.referredUserId);
                if (retained.add(pair)) {
                    continue;
                }
                if (repairTimestamp == null) {
                    throw conflict("duplicate direct performance has no deterministic timestamp");
                }
                Timestamp lastRepairTimestamp = null;
                for (LedgerEntry award : entries) {
                    if (!"DIRECT_REFERRAL_AWARD".equals(award.entryType)
                            || !Long.valueOf(performance.beneficiaryUserId).equals(accountUsers.get(award.accountId))
                            || !Long.valueOf(performance.sourceOrderId).equals(award.sourceOrderId)
                            || award.frozenDelta <= 0
                            || hasReversal(entries, award.id)) {
                        continue;
                    }
                    BatchState batch = replay.batchesBySourceEntry.get(award.id);
                    if (batch == null || repairTimestamp == null) {
                        throw conflict("duplicate direct award has no deterministic source batch");
                    }
                    long remainingPoints = batch.remainingPoints;
                    Timestamp factTimestamp = addMillis(repairTimestamp, repairSequence++);
                    appendMigrationReversal(award, performance.id, remainingPoints, factTimestamp);
                    batch.remainingPoints = 0;
                    batch.reversed = true;
                    lastRepairTimestamp = factTimestamp;
                }
                if (lastRepairTimestamp == null) {
                    lastRepairTimestamp = addMillis(repairTimestamp, repairSequence++);
                }
                markDuplicatePerformance(performance.id, lastRepairTimestamp);
            }
        }

        private List<DirectPerformance> loadActiveDirectPerformances() throws SQLException {
            List<DirectPerformance> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, beneficiary_user_id, referred_user_id, source_order_id
                    FROM distribution_direct_performance
                    WHERE status = 'ACTIVE'
                    ORDER BY beneficiary_user_id, referred_user_id, created_at, id
                    """)) {
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows.add(new DirectPerformance(
                                result.getLong("id"),
                                result.getLong("beneficiary_user_id"),
                                result.getLong("referred_user_id"),
                                result.getLong("source_order_id")
                        ));
                    }
                }
            }
            return rows;
        }

        private Map<Long, Long> loadLedgerAccountUsers() throws SQLException {
            Map<Long, Long> users = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, user_id FROM ledger_account
                    """)) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        users.put(rows.getLong("id"), rows.getLong("user_id"));
                    }
                }
            }
            return users;
        }

        private void appendMigrationReversal(LedgerEntry award, long performanceId, long remainingPoints,
                                              Timestamp repairTimestamp) throws SQLException {
            String idempotencyKey = "migration-v18-direct-duplicate:" + award.id;
            if (ledgerEntryExists(idempotencyKey)) {
                return;
            }
            long frozenDelta = -remainingPoints;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO ledger_entry
                        (account_id, entry_type, available_delta, frozen_delta, source_type, source_id,
                         source_order_id, rule_version_id, original_entry_id, idempotency_key,
                         occurred_at, created_at)
                    VALUES (?, 'REVERSAL', ?, ?, 'MIGRATION', ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setLong(1, award.accountId);
                statement.setLong(2, -award.availableDelta);
                statement.setLong(3, frozenDelta);
                statement.setLong(4, performanceId);
                setNullableLong(statement, 5, award.sourceOrderId);
                setNullableLong(statement, 6, award.ruleVersionId);
                statement.setLong(7, award.id);
                statement.setString(8, idempotencyKey);
                statement.setTimestamp(9, repairTimestamp);
                statement.setTimestamp(10, repairTimestamp);
                if (statement.executeUpdate() != 1) {
                    throw conflict("migration reversal was not appended");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ledger_account
                    SET available_points = available_points + ?,
                        frozen_points = frozen_points + ?,
                        version = version + 1
                    WHERE id = ?
                      AND available_points + ? >= 0
                      AND frozen_points + ? >= 0
                    """)) {
                statement.setLong(1, -award.availableDelta);
                statement.setLong(2, frozenDelta);
                statement.setLong(3, award.accountId);
                statement.setLong(4, -award.availableDelta);
                statement.setLong(5, frozenDelta);
                if (statement.executeUpdate() != 1) {
                    throw conflict("migration reversal cannot preserve account balance");
                }
            }
        }

        private boolean ledgerEntryExists(String idempotencyKey) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*) FROM ledger_entry WHERE idempotency_key = ?
                    """)) {
                statement.setString(1, idempotencyKey);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getInt(1) > 0;
                }
            }
        }

        private void markDuplicatePerformance(long performanceId, Timestamp repairTimestamp) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE distribution_direct_performance
                    SET status = 'REVERSED', reversed_at = ?
                    WHERE id = ? AND status = 'ACTIVE'
                    """)) {
                statement.setTimestamp(1, repairTimestamp);
                statement.setLong(2, performanceId);
                if (statement.executeUpdate() != 1) {
                    throw conflict("duplicate direct performance changed during repair");
                }
            }
        }

        private boolean hasReversal(List<LedgerEntry> entries, long originalEntryId) {
            return entries.stream().anyMatch(entry -> "REVERSAL".equals(entry.entryType)
                    && Long.valueOf(originalEntryId).equals(entry.originalEntryId));
        }

        private void validateAccountBalances(List<LedgerEntry> entries, Replay replay) throws SQLException {
            Map<Long, Long> ledgerFrozenTotals = new HashMap<>();
            for (LedgerEntry entry : entries) {
                ledgerFrozenTotals.merge(entry.accountId, entry.frozenDelta, Repair::addExact);
            }
            Map<Long, Long> accountFrozenBalances = loadDemoPointsAccounts();
            Set<Long> accountIds = new HashSet<>(ledgerFrozenTotals.keySet());
            accountIds.addAll(replay.batchesByAccount.keySet());
            accountIds.addAll(accountFrozenBalances.keySet());
            for (Long accountId : accountIds) {
                long activeBatchBalance = 0;
                for (BatchState batch : replay.batchesByAccount.getOrDefault(accountId, List.of())) {
                    if (!batch.reversed && batch.remainingPoints > 0) {
                        activeBatchBalance = addExact(activeBatchBalance, batch.remainingPoints);
                    }
                    if (batch.remainingPoints < 0 || batch.remainingPoints > batch.originalPoints) {
                        throw conflict("derived source batch has an invalid remaining amount");
                    }
                }
                long ledgerBalance = ledgerFrozenTotals.getOrDefault(accountId, 0L);
                if (activeBatchBalance != ledgerBalance || ledgerBalance < 0) {
                    throw conflict("ledger and frozen-batch balances differ");
                }
                Long accountBalance = accountFrozenBalances.get(accountId);
                if (accountBalance == null || accountBalance != ledgerBalance) {
                    throw conflict("ledger account and immutable ledger balances differ");
                }
            }
        }

        private Map<Long, Long> loadDemoPointsAccounts() throws SQLException {
            Map<Long, Long> balances = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, frozen_points
                    FROM ledger_account
                    WHERE account_type = 'DEMO_POINTS'
                    FOR UPDATE
                    """)) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        balances.put(rows.getLong(1), rows.getLong(2));
                    }
                }
            }
            return balances;
        }

        private void applyDerivedProjection(Replay replay) throws SQLException {
            validateExistingBatchSources(replay);
            Map<Long, Long> databaseBatchIds = new HashMap<>();
            for (BatchState batch : replay.batchesBySourceEntry.values()) {
                long batchId = upsertBatch(batch);
                databaseBatchIds.put(batch.sourceLedgerEntryId, batchId);
            }

            Map<ReleaseBatchKey, ExpectedReleaseItem> expected = new LinkedHashMap<>();
            for (Map.Entry<Long, List<AllocationState>> release : replay.releaseAllocations.entrySet()) {
                LedgerEntry releaseEntry = replay.byId.get(release.getKey());
                for (AllocationState allocation : release.getValue()) {
                    Long batchId = databaseBatchIds.get(allocation.batch.sourceLedgerEntryId);
                    if (batchId == null || releaseEntry == null || releaseEntry.occurredAt == null) {
                        throw conflict("release allocation source batch is missing");
                    }
                    expected.put(
                            new ReleaseBatchKey(release.getKey(), batchId),
                            new ExpectedReleaseItem(
                                    allocation.batch.sourceLedgerEntryId,
                                    allocation.points,
                                    releaseEntry.occurredAt
                            )
                    );
                }
            }
            reconcileReleaseItems(expected);
        }

        private void reconcileReleaseItems(Map<ReleaseBatchKey, ExpectedReleaseItem> expected)
                throws SQLException {
            Map<ReleaseBatchKey, ExistingReleaseItem> existing = loadReleaseItems();
            for (Map.Entry<ReleaseBatchKey, ExistingReleaseItem> item : existing.entrySet()) {
                ExpectedReleaseItem expectedItem = expected.get(item.getKey());
                if (expectedItem == null
                        || expectedItem.sourceLedgerEntryId() != item.getValue().sourceLedgerEntryId()
                        || expectedItem.points() != item.getValue().points()) {
                    deleteReleaseItem(item.getKey());
                }
            }
            for (Map.Entry<ReleaseBatchKey, ExpectedReleaseItem> item : expected.entrySet()) {
                ExistingReleaseItem existingItem = existing.get(item.getKey());
                if (existingItem == null
                        || existingItem.sourceLedgerEntryId() != item.getValue().sourceLedgerEntryId()
                        || existingItem.points() != item.getValue().points()) {
                    if (existingItem != null) {
                        deleteReleaseItem(item.getKey());
                    }
                    insertReleaseItem(item.getKey(), item.getValue());
                } else if (!sameTimestamp(existingItem.createdAt(), item.getValue().createdAt())) {
                    updateReleaseItemTimestamp(item.getKey(), item.getValue().createdAt());
                }
            }
        }

        private Map<ReleaseBatchKey, ExistingReleaseItem> loadReleaseItems() throws SQLException {
            Map<ReleaseBatchKey, ExistingReleaseItem> result = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT item.release_ledger_entry_id, item.frozen_batch_id, item.points,
                           item.created_at, batch.source_ledger_entry_id
                    FROM ledger_frozen_release_item item
                    JOIN ledger_frozen_batch batch ON batch.id = item.frozen_batch_id
                    ORDER BY item.id
                    """)) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.put(
                                new ReleaseBatchKey(rows.getLong(1), rows.getLong(2)),
                                new ExistingReleaseItem(rows.getLong(5), rows.getLong(3), rows.getTimestamp(4))
                        );
                    }
                }
            }
            return result;
        }

        private void deleteReleaseItem(ReleaseBatchKey key) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM ledger_frozen_release_item
                    WHERE release_ledger_entry_id = ? AND frozen_batch_id = ?
                    """)) {
                statement.setLong(1, key.releaseLedgerEntryId);
                statement.setLong(2, key.frozenBatchId);
                if (statement.executeUpdate() > 1) {
                    throw conflict("duplicate frozen release item provenance");
                }
            }
        }

        private void updateReleaseItemTimestamp(ReleaseBatchKey key, Timestamp createdAt)
                throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ledger_frozen_release_item
                    SET created_at = ?
                    WHERE release_ledger_entry_id = ? AND frozen_batch_id = ?
                    """)) {
                statement.setTimestamp(1, createdAt);
                statement.setLong(2, key.releaseLedgerEntryId);
                statement.setLong(3, key.frozenBatchId);
                if (statement.executeUpdate() != 1) {
                    throw conflict("release-item timestamp could not be repaired");
                }
            }
        }

        private static boolean sameTimestamp(Timestamp left, Timestamp right) {
            return left != null && right != null && left.getTime() == right.getTime();
        }

        private void validateExistingBatchSources(Replay replay) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT source_ledger_entry_id FROM ledger_frozen_batch
                    """)) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        if (!replay.batchesBySourceEntry.containsKey(rows.getLong(1))) {
                            throw conflict("existing frozen batch has no immutable source entry");
                        }
                    }
                }
            }
        }

        private long upsertBatch(BatchState batch) throws SQLException {
            Long existingId = existingBatchId(batch.sourceLedgerEntryId);
            String status = batch.reversed ? "REVERSED" : batch.remainingPoints == 0 ? "CONSUMED" : "ACTIVE";
            if (existingId != null) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE ledger_frozen_batch
                        SET account_id = ?, source_order_id = ?, rule_version_id = ?,
                            original_points = ?, remaining_points = ?, status = ?,
                            created_at = ?, updated_at = ?
                        WHERE id = ?
                        """)) {
                    statement.setLong(1, batch.accountId);
                    statement.setLong(2, batch.sourceOrderId);
                    statement.setLong(3, batch.ruleVersionId);
                    statement.setLong(4, batch.originalPoints);
                    statement.setLong(5, batch.remainingPoints);
                    statement.setString(6, status);
                    statement.setTimestamp(7, batch.createdAt);
                    statement.setTimestamp(8, batch.createdAt);
                    statement.setLong(9, existingId);
                    if (statement.executeUpdate() != 1) {
                        throw conflict("existing frozen batch could not be repaired");
                    }
                }
                return existingId;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO ledger_frozen_batch
                        (account_id, source_ledger_entry_id, source_order_id, rule_version_id,
                         original_points, remaining_points, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, batch.accountId);
                statement.setLong(2, batch.sourceLedgerEntryId);
                statement.setLong(3, batch.sourceOrderId);
                statement.setLong(4, batch.ruleVersionId);
                statement.setLong(5, batch.originalPoints);
                statement.setLong(6, batch.remainingPoints);
                statement.setString(7, status);
                statement.setTimestamp(8, batch.createdAt);
                statement.setTimestamp(9, batch.createdAt);
                if (statement.executeUpdate() != 1) {
                    throw conflict("frozen batch could not be created");
                }
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw conflict("frozen batch id was not generated");
                    }
                    return keys.getLong(1);
                }
            }
        }

        private Long existingBatchId(long sourceLedgerEntryId) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id FROM ledger_frozen_batch
                    WHERE source_ledger_entry_id = ?
                    LIMIT 1 FOR UPDATE
                    """)) {
                statement.setLong(1, sourceLedgerEntryId);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? rows.getLong(1) : null;
                }
            }
        }

        private void insertReleaseItem(ReleaseBatchKey key, ExpectedReleaseItem item) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO ledger_frozen_release_item
                        (release_ledger_entry_id, frozen_batch_id, points, created_at)
                    VALUES (?, ?, ?, ?)
                    """)) {
                statement.setLong(1, key.releaseLedgerEntryId);
                statement.setLong(2, key.frozenBatchId);
                statement.setLong(3, item.points);
                statement.setTimestamp(4, item.createdAt);
                if (statement.executeUpdate() != 1) {
                    throw conflict("frozen release item could not be rebuilt");
                }
            }
        }

        private static Long nullableLong(ResultSet rows, String column) throws SQLException {
            long value = rows.getLong(column);
            return rows.wasNull() ? null : value;
        }

        private static void setNullableLong(PreparedStatement statement, int index, Long value)
                throws SQLException {
            if (value == null) {
                statement.setNull(index, Types.BIGINT);
            } else {
                statement.setLong(index, value);
            }
        }

        private static long positiveMagnitude(long value) {
            if (value >= 0 || value == Long.MIN_VALUE) {
                throw conflict("point amount cannot be represented as a positive value");
            }
            return -value;
        }

        private static long negateExact(long value) {
            if (value == Long.MIN_VALUE) {
                throw conflict("point amount cannot be negated safely");
            }
            return -value;
        }

        private static long addExact(long left, long right) {
            try {
                return Math.addExact(left, right);
            } catch (ArithmeticException exception) {
                throw conflict("point amount overflow");
            }
        }

        private static IllegalStateException conflict(String detail) {
            return new IllegalStateException(CONFLICT + ": " + detail);
        }
    }

    private record LedgerEntry(long id, long accountId, String entryType, long availableDelta,
                               long frozenDelta, String sourceType, Long sourceId, Long sourceOrderId,
                               Long ruleVersionId, Long originalEntryId, Timestamp occurredAt) {
    }

    private record DirectPerformance(long id, long beneficiaryUserId, long referredUserId,
                                     long sourceOrderId) {
    }

    private record Pair(long beneficiaryUserId, long referredUserId) {
    }

    private record ReleaseBatchKey(long releaseLedgerEntryId, long frozenBatchId) {
    }

    private record ExistingReleaseItem(long sourceLedgerEntryId, long points, Timestamp createdAt) {
    }

    private record ExpectedReleaseItem(long sourceLedgerEntryId, long points, Timestamp createdAt) {
    }

    private record AllocationState(BatchState batch, long points) {
    }

    private static final class BatchState {
        private final long accountId;
        private final long sourceLedgerEntryId;
        private final long sourceOrderId;
        private final long ruleVersionId;
        private final long originalPoints;
        private long remainingPoints;
        private boolean reversed;
        private final Timestamp createdAt;

        private BatchState(long accountId, long sourceLedgerEntryId, long sourceOrderId,
                           long ruleVersionId, long originalPoints, long remainingPoints,
                           boolean reversed, Timestamp createdAt) {
            this.accountId = accountId;
            this.sourceLedgerEntryId = sourceLedgerEntryId;
            this.sourceOrderId = sourceOrderId;
            this.ruleVersionId = ruleVersionId;
            this.originalPoints = originalPoints;
            this.remainingPoints = remainingPoints;
            this.reversed = reversed;
            this.createdAt = createdAt;
        }
    }

    private static final class Replay {
        private final Map<Long, LedgerEntry> byId = new LinkedHashMap<>();
        private final Map<Long, BatchState> batchesBySourceEntry = new LinkedHashMap<>();
        private final Map<Long, List<BatchState>> batchesByAccount = new LinkedHashMap<>();
        private final Map<Long, List<AllocationState>> releaseAllocations = new LinkedHashMap<>();
        private final Map<Long, List<ExistingReleaseItem>> existingReleaseItems;

        private Replay(Map<Long, List<ExistingReleaseItem>> existingReleaseItems) {
            this.existingReleaseItems = existingReleaseItems;
        }
    }
}

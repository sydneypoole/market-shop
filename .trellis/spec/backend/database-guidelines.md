# Database Guidelines

## Scope / Trigger

Apply this specification to every MySQL write, Flyway migration, order transition, inventory operation, membership projection, point release, and aftersale reversal. The project uses MySQL 8.4, MyBatis-Flex 1.11.8, and Flyway 12 through Spring Boot 4's independent `spring-boot-starter-flyway`.

## Signatures

- Money is stored as integer fen in `BIGINT`; Java signatures use `long`, never floating point.
- Aggregate versions use a non-null `version` column and compare-and-set updates.
- External retries use a caller-owned idempotency key such as `client_request_id`.
- Integration events use `event_outbox(aggregate_type, aggregate_id, event_type, payload_json, status)`.
- Consumers record `event_inbox(consumer_name, event_id)` before applying a projection.
- Time values are database timestamps and are interpreted in the configured application zone.
- Aftersale timeout configuration under `market-shop.jobs.*` contains only `aftersale-timeout-delay-ms` (worker fixed delay). The four stage deadlines are read from each order's immutable `ORDER_TIMERS` snapshot: `awaitingReturnTimeoutDays`, `returnShippedTimeoutDays`, `offlineRefundTimeoutDays`, and `buyerRefundConfirmTimeoutDays`. State machine: `AWAITING_RETURN → CANCELLED`, `RETURN_SHIPPED → PENDING_OFFLINE_REFUND`, `PENDING_OFFLINE_REFUND → PENDING_BUYER_REFUND_CONFIRMATION` (sets `offlineRefundConfirmedAt`), `PENDING_BUYER_REFUND_CONFIRMATION → COMPLETED` (sets `completedAt`, emits completed event).
- Order pending-timeout configuration: `market-shop.jobs.order-timeout-delay-ms` (worker fixed delay, default 300000). Day thresholds live in the immutable `ORDER_TIMERS` snapshot attached at order submission: canonical `autoReceiveDays`, `pendingSuperiorTimeoutDays`, `pendingAdminReviewTimeoutDays`, `pendingShipmentTimeoutDays`, and the aftersale fields (each timing field 1–365). `trade_order.status_due_at` is the persisted due timestamp for reservation-holding pending states; `trade_order.auto_receive_at` remains the shipment due timestamp and V19 recomputes it from the historical snapshot even when a legacy value already exists (no grandfathering). Job name `order-timeout`, lease 120s, batch 50. V17 adds `trade_after_sale.completed_order_id` (generated, unique `uk_after_sale_completed_order`) and the three pending keys to legacy ACTIVE rows. V19 adds `trade_order.status_due_at`, `trade_after_sale.state_due_at`, the four aftersale-stage keys, and backfills snapshots/due timestamps from the rule effective at `created_at`.

## Contracts

1. An order write and its outbox event commit in the same transaction.
2. Inventory follows reserve → consume or reserve → release; available stock must never become negative. `RETURN_REFUND` completion is the only after-sale path that restocks (`available_quantity += qty` with no reserved check). `REFUND_ONLY` never restocks. Cancel / superior-reject / admin-reject / pending-order timeout release reserved stock through `persistTransition`.
3. The direct superior is immutable after first registration.
4. Distribution depth is exactly one. The first five qualified direct referrals contribute only qualification evidence. The sixth and later referrals allocate configurable A-available and B-frozen points.
5. Ledgers are append-only. Corrections create reversal entries; they never update historical amounts in place.
6. Aftersale completion invalidates evidence and appends reversals before recomputing levels. Completing an after-sale locks `trade_order` `FOR UPDATE`, does not change `trade_order.status`, and emits only `AFTERSALE_COMPLETED` — never `ORDER_COMPLETED`. A blocking after-sale is `EXISTS trade_after_sale WHERE order_id = ? AND status NOT IN ('REJECTED', 'CANCELLED')`. Receive, auto-receive SQL, `ORDER_COMPLETED` persist, and `projectCompletedOrder` all honor that set. `create` locks the order and re-reads eligibility before insert so a just-completed or in-progress after-sale cannot be raced. At most one `COMPLETED` after-sale per order is also enforced by `completed_order_id` + `uk_after_sale_completed_order`.
7. Rule versions are immutable after publication; orders and evidence retain the applied rule version.
8. Worker queries use bounded batches and `FOR UPDATE SKIP LOCKED`. Singleton jobs also acquire `sys_job_lease`.
9. `trade_after_sale.state_entered_at` records when the row entered its current `status`, and `state_due_at` records the snapshot-derived deadline. Every status transition UPDATE sets both atomically; inserts rely on the column default for `state_entered_at`. Due workers select exactly one row with `FOR UPDATE SKIP LOCKED`, ordered by `status_due_at, id`, `auto_receive_at, id`, or `state_due_at, id` according to the job.
10. `OrderTimeoutProcessor` must go through `persistTransition` (not AutoReceive's raw `updateTransition`) so reserved inventory is released. `PENDING_SUPERIOR` cancels, `PENDING_ADMIN_REVIEW` admin-rejects, `PENDING_SHIPMENT` `timeoutClose`s to `CANCELLED` with reason `超时未发货，系统自动关闭`. Selectors lock only rows whose persisted due timestamp is at or before the database clock; missing or malformed snapshots fail closed before mutation.
11. Admin manual membership assign (`ADMIN_ADJUST`) may set `membership_account.current_level_id` to any ACTIVE `membership_level` by code, both directions. SQL joins the target by `code` + `ACTIVE` and does not write `last_performance_at`. Same-level is a 200 no-op (no account write, no `membership_level_change` row). History uses trigger `ADMIN_ADJUST` and idempotency `manual-level:{requestId}`. Do not reuse `promoteMember` (upgrade-only, stamps performance) or `resetMemberToBasic` (hardcodes level id 1). No new Flyway; seeded codes remain `BASIC` / `EXPERIENCE_OFFICER` / `SUPER_MEMBER` / `DIVIDEND_MEMBER`.

## Validation & Error Matrix

| Condition | Database/application response |
|-----------|-------------------------------|
| Duplicate order idempotency key for same user | Return the original order |
| Duplicate inbox event | Skip projection and commit safely |
| Insufficient inventory | Roll back and report a business conflict |
| Optimistic version mismatch | Roll back and report concurrent modification |
| Illegal order state transition | Do not execute SQL; return a domain conflict |
| Missing published rule | Reject the qualifying operation |
| Duplicate point source key | Unique constraint prevents double reward |
| Flyway checksum mismatch | Fail startup; never repair automatically in production |
| Aftersale row is due in a timed state (`AWAITING_RETURN`, `RETURN_SHIPPED`, `PENDING_OFFLINE_REFUND`, `PENDING_BUYER_REFUND_CONFIRMATION`) | Timeout worker locks it `FOR UPDATE SKIP LOCKED`, resolves the owning order's immutable timer snapshot, and transitions it to the next state; missing or malformed snapshots fail closed |
| Second after-sale create after one is `COMPLETED` or still active | `create` re-reads eligibility under the order lock; `AFTERSALE_ALREADY_COMPLETED` or `AFTERSALE_ALREADY_EXISTS` |
| Second `COMPLETED` after-sale for the same order | Unique `uk_after_sale_completed_order` rejects the `COMPLETED` write; map to `AFTERSALE_ALREADY_COMPLETED` |
| `ORDER_COMPLETED` persist or auto-receive while a blocking after-sale exists | Do not update `trade_order`; raise `AFTERSALE_BLOCKS_RECEIVE` |
| `RETURN_REFUND` reaches `COMPLETED` | Restock each order line's `available_quantity`; emit `AFTERSALE_COMPLETED` only |
| `REFUND_ONLY` reaches `COMPLETED` | Do not restock; emit `AFTERSALE_COMPLETED` only |
| Pending order exceeds its snapshotted `ORDER_TIMERS` day window | `status_due_at <= CURRENT_TIMESTAMP(3)` selector + typed snapshot resolution + `persistTransition` closes it and releases reserved inventory |
| `ORDER_TIMERS` is missing any required timing key or a timing key is outside 1–365 | Publication rejects it; a missing or malformed order snapshot fails the worker/action closed with `ORDER_TIMER_SETTINGS_INVALID` |
| Admin assigns an unknown or inactive level code | `assignMemberLevel` updates 0 rows → `MEMBER_LEVEL_INVALID` |
| Admin assigns the member's current level | Return 200 no-op; do not write `membership_account` or `membership_level_change` |
| Admin assigns a different ACTIVE level | Update `current_level_id` + `qualified_at` + `version`; insert `ADMIN_ADJUST` history; do not touch `last_performance_at`, points, or superior |

## Good / Base / Bad Cases

- Good: lock the relevant SKU rows, validate all quantities, update inventory, insert order lines, then append `ORDER_SUBMITTED`.
- Base: read-only catalog queries may use direct mapper projections without aggregate reconstruction.
- Bad: publish to Redis before committing MySQL; retrying can produce a reward without an order.
- Bad: update an old ledger row to simulate a refund.
- Bad: use `double` for `1998.00`.

## Tests Required

- Domain tests cover every legal and illegal order transition.
- Application tests cover idempotency, inventory rollback, first-five/sixth referral boundaries, repurchase release, and aftersale reversal.
- Integration smoke tests must run Flyway from an empty schema and verify all migrations apply.
- Concurrency-sensitive changes require a test for duplicate delivery or optimistic conflict.
- Aftersale timeout tests cover each timed status reaching its order-snapshot threshold, a non-due row being skipped, missing/malformed snapshots failing closed, `FOR UPDATE SKIP LOCKED` excluding rows locked by another worker, the `sys_job_lease` preventing two nodes processing the same batch, and `state_entered_at` advancing on every transition UPDATE.
- Order-timeout mapper/processor tests cover the three due statuses, per-order snapshot selection, fail-closed missing timer snapshots, and `persistTransition` (not raw `updateTransition`) so reserved inventory is released.
- After-sale adapter tests cover create lock-then-recheck, `uk_after_sale_completed_order` mapping, `RETURN_REFUND` restock, and `REFUND_ONLY` no-restock.

## Wrong vs Correct

```java
// Wrong: bypasses the aggregate and can race.
orderMapper.updateStatus(orderId, "COMPLETED");

// Correct: aggregate validates, adapter persists with expected version,
// and the same application transaction appends the event.
order.confirmReceived(actorId, clock.instant());
commercePort.save(order);
commercePort.appendOutbox(order.completedEvent());
```

## Migrations and Naming

Tables and columns use lowercase `snake_case`; primary keys are `id`; foreign keys end in `_id`; unique constraints and indexes have descriptive `uk_` and `idx_` prefixes. Add forward-only `V{n}__description.sql` migrations. Never edit an applied migration or depend on Hibernate schema generation. Local Docker exposes MySQL on host port 3308 to avoid colliding with developer installations; the container still uses 3306.

## Scenario: FIFO Frozen-Point Batches

### 1. Scope / Trigger

Apply this contract whenever direct-referral rewards add B-pool points, a repurchase order releases frozen points, an aftersale reverses either order, or an API exposes point-ledger traceability.

### 2. Signatures

- `ledger_frozen_batch(source_ledger_entry_id UNIQUE, source_order_id, rule_version_id, original_points, remaining_points, status)`
- `ledger_frozen_release(source_order_id UNIQUE, rule_version_id, requested_points, released_points, status)`
- `ledger_frozen_release_item(release_ledger_entry_id, frozen_batch_id, points)` preserves one-to-many consumption provenance, including migration backfill.
- `ledger_entry.original_entry_id` links each `FROZEN_POINTS_RELEASED` entry to its source `DIRECT_REFERRAL_AWARD` entry.
- Ledger API views expose `sourceOrderId`, `ruleVersionId`, `originalEntryId`, `frozenBatchId`, `frozenBatchOriginalPoints`, `frozenBatchRemainingPoints`, and `frozenBatchStatus`.

### 3. Contracts

1. A positive B-pool award and its frozen batch commit in the same outbox projection transaction.
2. Release locks the point account, claims the repurchase order through unique `source_order_id`, then locks active batches ordered by `created_at, id`.
3. One repurchase order may create multiple release entries when its configured amount crosses batch boundaries.
4. The sum of active batch `remaining_points` must equal the account's `frozen_points`; a mismatch rolls back the entire release.
5. Reversing a repurchase release restores the exact source batches through release items. Reversing a direct award closes its remaining batch.
6. If the source award was already reversed, a later release reversal appends a zero-delta reversal marker instead of recreating orphan frozen points.
7. Historical ledger rows remain immutable. Flyway may derive batch projection rows from existing non-reversed entries but must not rewrite the source amounts.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Duplicate completed-order event | Existing inbox or `ledger_frozen_release.source_order_id` prevents another release |
| No active release rule / amount below threshold | Create no release or batch mutation |
| No frozen balance | Create no release |
| Batch sum is smaller than account frozen balance | Throw `FROZEN_BATCH_BALANCE_CONFLICT` and roll back |
| Batch compare-and-set update loses a race | Throw `FROZEN_BATCH_BALANCE_CONFLICT` and roll back |
| Source award reversal | Set remaining batch points to zero and status to `REVERSED` |
| Repurchase reversal with active source batch | Restore points without exceeding `original_points` |
| Repurchase reversal after source award reversal | Append a zero-delta reversal marker |

### 5. Good / Base / Bad Cases

- Good: release 160 points by consuming 100 from the oldest batch and 60 from the next, creating two source-linked ledger entries.
- Base: a qualified repurchase with only one batch creates one release entry and marks the batch consumed when its remaining value reaches zero.
- Bad: subtract 160 only from `ledger_account.frozen_points`; this loses FIFO provenance and cannot restore the correct batch after an aftersale.

### 6. Tests Required

- Projection unit: sixth referral creates one frozen batch.
- Projection unit: release crosses two batches in FIFO order and preserves source links.
- Projection unit: duplicate order projection does not touch another batch.
- Projection unit: repurchase aftersale restores the consumed source batch.
- Migration/runtime: empty MySQL 8.4 applies through V12 and creates all three frozen-point tables plus
  the completion snapshots, outbox recovery columns, auth epochs, sponsor-claim table, and ordinal key.
- Cross-layer: member and admin ledger views expose rule, order, source entry and batch remaining fields.

### 7. Wrong vs Correct

```java
// Wrong: aggregate-only release has no batch provenance.
long release = Math.min(rule.releasePoints, account.frozenPoints);
updateLedger(account.id, release, -release);

// Correct: claim the order, lock FIFO batches, and append one linked entry per batch.
for (FrozenBatchRow batch : mapper.lockFrozenBatches(account.id)) {
    long points = Math.min(remaining, batch.remainingPoints);
    appendRelease(order.id, rule.id, batch.sourceLedgerEntryId, points);
    mapper.consumeFrozenBatch(batch.id, points);
}
```

## Scenario: V18 Frozen-Point Provenance Repair

### 1. Scope / Trigger

Apply this contract when upgrading a V9–V17 database, rebuilding frozen B-point projections, correcting historical duplicate direct contributions, or reversing a direct award or frozen release after an after-sale.

### 2. Signatures

- Flyway artifact: version `18`, description `repair distribution projections`, type `JDBC`, script `db.migration.V18__repair_distribution_projections`, normally with a null Java-migration checksum.
- Repair lock: `market-shop:legacy-aftersale-v17` serializes the protected V17/V18 preflight and its single `repair()` plus normal `migrate()` retry.
- Deterministic time boundary: `max(ledger_entry.occurred_at, ACTIVE distribution_direct_performance.created_at) + 1ms`, followed by a stable sequence for multiple repairs.
- Safe diagnostics expose sanitized `version`, `script`, and `state`; they never expose row payloads or credentials.

### 3. Contracts

- `ledger_entry` is immutable. V18 replays relevant facts in `(occurred_at, id)` order and changes only derived frozen-batch/release-item rows; historical corrections append `REVERSAL` facts.
- Every positive `DIRECT_REFERRAL_AWARD` with a frozen delta has exactly one source batch, including a zero-remaining `REVERSED` batch when V9 omitted a batch for an already-reversed source.
- Release items are deterministic source mappings. A non-null `ledger_entry.original_entry_id` is authoritative and maps only to that exact direct-award source batch; only legacy null values may use deterministic FIFO. Existing explicit mappings that disagree with the immutable source fail with `FROZEN_BATCH_BALANCE_CONFLICT` rather than being overwritten.
- A release reversal validates the complete mapping and exact source ledger/batch identity, including source account, direct-award type, source order/rule, original frozen amount, and available/frozen delta symmetry before it appends a ledger reversal or changes an account.
- `ledger_account.frozen_points` equals the sum of `remaining_points` for `ACTIVE` batches for every `DEMO_POINTS` account, including accounts with no ledger facts. A missing, conflicting, or aggregate-only provenance shortcut fails with `FROZEN_BATCH_BALANCE_CONFLICT`.
- A direct-award reversal deducts only the source batch amount still remaining. A release reversal restores only mapped source portions that are not already `REVERSED`; an already-reversed source produces an explicit zero-delta reversal marker.
- Duplicate ACTIVE direct performances are retained as historical rows but marked `REVERSED`; their invalid rewards are corrected by idempotent append-only reversal facts, and their historical ordinals are never reused.
- V18 is deterministic and rerunnable: existing derived rows are repaired in place where their source identity matches, stale release-item mappings are replaced, and no immutable ledger ID, amount, or timestamp is rewritten. Migration-generated reversal and performance-repair timestamps are derived from the maximum persisted `ledger_entry.occurred_at` and ACTIVE `distribution_direct_performance.created_at`, plus a fixed millisecond tie boundary; release-item and batch timestamps are explicitly sourced from their immutable facts. An awardless duplicate is marked `REVERSED` without fabricating a ledger fact.
- Startup may run `Flyway.repair()` for V18 only when history contains exactly one failed migration, that row exactly names version 18 / `repair distribution projections` / JDBC / `db.migration.V18__repair_distribution_projections`, the applied and resolved checksum are both null or equal, and V17 is successful with its generated column, unique index, and completed-after-sale invariant intact. Additional failed rows and every missing, deleted, or future applied history state fail closed before repair.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Source award has no derived batch | Create the exact source-linked batch during V18; use `REVERSED` and zero remaining when its immutable reversal already exists |
| Direct award has been partially or fully released | Reverse only the batch remainder; never deduct released points from the frozen account again |
| Release mapping is missing, incomplete, or points/source identity conflict | Abort with `FROZEN_BATCH_BALANCE_CONFLICT`; do not apply an aggregate correction |
| Active-batch sum differs from the account or immutable ledger total | Abort with `FROZEN_BATCH_BALANCE_CONFLICT` |
| Duplicate direct performance or reward | Preserve the row/ledger fact and append one idempotent migration reversal; do not reuse its ordinal |
| Duplicate direct performance has no reward ledger | Mark only the duplicate performance `REVERSED` at the persisted deterministic time boundary; append no ledger entry |
| Exact failed V18 JDBC row after complete successful V17 | Run one protected repair and rerun V18 under the migration advisory lock |
| V18 metadata/checksum mismatch, multiple failures, or missing/future history | Fail startup with sanitized version/script/state diagnostics; retain history unchanged |
| V18 is rerun | Keep immutable ledger rows unchanged and converge derived projections to the same source mappings |

### 5. Good/Base/Bad Cases

- Good: one exact failed V18 JDBC row follows a complete successful V17; startup removes only that failure marker, reruns V18, reverses the awardless duplicate performance, and writes no ledger fact.
- Base: a fresh or already-successful schema runs normally; rerunning V18 converges projections without changing immutable ledger rows or prior repair timestamps.
- Bad: manually delete migration history, accept a non-null checksum for the Java migration, call generic `repair()` with multiple failures, or fabricate a reward/reversal solely to make an awardless duplicate pass.

### 6. Tests Required

- Runtime tests cover unconsumed, partially consumed, and fully consumed source batches, source/release reversal order, zero-delta markers, exact source IDs, and missing/conflicting release mappings before any ledger/account mutation.
- Migration tests cover fresh and V9-upgraded MySQL 8.4 fixtures, exact immutable ledger snapshots, missing historical batches, deterministic release-item rebuilds, rewarded and awardless duplicate direct-performance repair, protected failed-V18 recovery, null-checksum acceptance, metadata/checksum/history-state rejection, and rerun idempotence.

### 7. Wrong vs Correct

```java
// Wrong: the retry either stays permanently blocked or repairs unknown history.
if (flyway.info().all().hasFailure()) {
    flyway.repair();
}

// Correct: validate the one exact failed JDBC artifact and the complete V17
// invariant under the advisory lock, then repair once and use normal migration.
HistoryValidation history = validateHistory(migrationInfos, v17Rows, v18Rows);
verifySuccessfulV17(connection, artifactState(connection));
if (history.repairFailedV18()) {
    flyway.repair();
}
flyway.migrate();
```

## Scenario: Optimistic Locking with a Mutually Exclusive Default Flag

### 1. Scope / Trigger

Apply this contract when one row is selected as the default and the write also uses a `version` compare-and-set, such as `customer_address.is_default`.

### 2. Signatures

- `clearDefault(long ownerId, Long excludeRowId)` clears other default rows.
- `update(long ownerId, Row row)` must compare `WHERE id = row.id AND version = row.version`.

### 3. Contracts

The bulk clear must exclude the row that the same command will update. Rows actually cleared increment their own versions. The target row increments exactly once in its compare-and-set update.

### 4. Validation & Error Matrix

| Condition | Response |
|-----------|----------|
| Target version matches | Clear other defaults, update target, increment target version once |
| Target version is stale | Roll back all clears and return the stable concurrency conflict |
| Creating the first row | Make it default without excluding an existing row |

### 5. Good / Base / Bad Cases

- Good: `clearDefault(userId, addressId)` followed by the target compare-and-set in one transaction.
- Base: a new row passes `null` as the excluded ID.
- Bad: clear every default row first; this increments the target version and makes its own subsequent compare-and-set fail.

### 6. Tests Required

- Updating the current default row succeeds and changes version `n` to `n + 1`.
- A stale target version rolls back changes to every other default row.
- Promoting a different row leaves exactly one active default row.

### 7. Wrong vs Correct

```java
// Wrong: invalidates the target's expected version.
mapper.clearDefault(userId);
mapper.update(userId, row);

// Correct: preserves the target version until its CAS update.
mapper.clearDefault(userId, row.id);
mapper.update(userId, row);
```

## Scenario: Completion-Time Rule Snapshots and Recoverable Outbox

### 1. Scope / Trigger

Apply this contract whenever an order becomes `COMPLETED`, a distribution projection fails,
an operator inspects/replays a dead letter, or an operational metric reports outbox health.

### 2. Signatures

- `trade_order_rule_snapshot(order_id, rule_code, rule_version_id)` is immutable and unique by
  order plus rule code.
- An `ORDER_COMPLETED` payload includes a `ruleVersionIds` object matching those relational
  snapshots.
- Outbox delivery states are `PENDING`, `PUBLISHED`, and `DEAD`; failed attempts update
  `attempt_count`, `next_attempt_at`, and a bounded `last_error`.
- Operations use `outbox:read` for dead-letter/summary reads and `outbox:replay` for a reasoned
  replay mutation.
- Metrics expose pending count, dead count, and oldest pending age in seconds.
- `distribution_direct_performance(beneficiary_user_id, completed_ordinal)` is unique; allocation
  is stable-owner lock, then locking `MAX(completed_ordinal) + 1` over all historical rows.

### 3. Contracts

1. The order transition, applicable rule snapshots, and completed outbox event commit in one
   transaction. Manual and automatic receipt use the same sequence.
2. Projection queries join `trade_order_rule_snapshot`; they never choose a newer rule by worker
   execution time.
3. Projection business work rolls back before a separate bean records failure with
   `REQUIRES_NEW`.
4. Retry delay grows exponentially from the configured base and is capped. The configured
   maximum attempt moves the event to `DEAD`.
5. After failure is recorded, the batch continues so one poison event cannot block a later valid
   event.
6. Only a current `DEAD` row can be replayed. Replay resets delivery attempts, retains a replay
   counter/actor timestamp, requires a non-blank reason, and appends an immutable admin audit.
7. Dead-letter APIs exclude `payload_json`; logs identify an event by ID without printing payloads
   or raw infrastructure errors.
8. Direct-performance ordinal allocation locks the beneficiary's stable `membership_account` row
   before reading the latest ordinal. The latest-ordinal read is a locking current read, and
   `(beneficiary_user_id, completed_ordinal)` is unique, so parallel projector instances serialize
   without losing the sixth-referral reward boundary. An active qualification is distinct by
   `(beneficiary_user_id, referred_user_id)`: the atomic performance insert rejects an existing
   ACTIVE pair, and all active qualification counts use `COUNT(DISTINCT referred_user_id)`. The
   ordinal is an all-history completion sequence: aftersale marks its source performance
   `REVERSED` but never releases or reuses that ordinal; a later qualification may create a new
   active row with the next historical ordinal. Profile/admin qualified counts and direct-member
   projections use the same distinct ACTIVE semantics without deleting historical rows. Replaying
   a source order inserts no new row.
9. Proof deletion and retention use a locking proof lookup (`FOR UPDATE`) joined to its order
   before object deletion; a non-locking eligibility read is never reused for a destructive action.
10. Runtime consumers of the current `ORDER_TIMERS` version fail closed when auto-receive days,
    after-sale days, pending-superior / pending-admin-review / pending-shipment timeout days,
    proof count, or proof size is missing or outside the publisher's documented
    bounds. They never fabricate a local business-policy default. The separately documented
    180-day proof-retention safety fallback remains the only retention exception.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Two qualified orders complete for one superior concurrently | Lock the same superior membership row, allocate two consecutive historical ordinals, and commit both |
| Source order is replayed | Existing `(beneficiary_user_id, source_order_id)` wins idempotently; do not allocate a row or award again |
| Legacy rows contain duplicate/gapped ordinals | Migration reconstructs the per-beneficiary sequence by `created_at, id` before adding the unique key |
| A source order is reversed by aftersale | Mark its performance `REVERSED`; retain its historical ordinal permanently and recompute qualification from ACTIVE rows |
| Superior membership row is missing | Roll back projection with `PROJECTION_SUPERIOR_MEMBERSHIP_INVALID` |
| Current `ORDER_TIMERS` row is missing or malformed | Reject shipping/after-sale/proof qualification with a stable rule-settings error; do not apply local timer or upload limits |

### 5. Good / Base / Bad Cases

- Good: lock the superior's stable membership row, perform a locking current read of the latest
  all-history ordinal, insert idempotently, then decide the reward boundary from the allocated value.
- Base: the first qualifying direct completion has no prior row and receives ordinal 1.
- Bad: lock only each buyer and use ACTIVE `COUNT(*) + 1`; different projector instances can both
  allocate 5, causing the real sixth completion to miss its reward.
- Bad: reuse an ordinal after aftersale; the ordinal records historical completion order, not the
  current ACTIVE qualification count.

### 6. Tests Required

- Completion adapter/SQL contract: snapshot insertion precedes completed-event insertion and the
  event payload carries the version map.
- Projection unit: a rule published after completion does not replace snapshotted self, direct,
  points, or FIFO-release versions.
- Runtime rule unit: missing and out-of-range timer/proof fields reject the operation rather than
  falling back to unpublished local values.
- Reliability unit: a poison event is retried/dead-lettered while the next valid event is still
  processed.
- Operations unit: protected replay is compare-and-set, reasoned, and audited.
- Migration smoke: an empty MySQL 8.4 schema applies through V12 and contains snapshot, replay,
  dead-letter, index, permission, auth-epoch, sponsor-claim, and ordinal additions.
- Migration upgrade: V12 deterministically repairs duplicate legacy ordinals, preserves rows and
  ledger history, and installs the beneficiary/ordinal unique key.
- MySQL concurrency: two transactions projecting the fifth and sixth qualifying referrals for the
  same beneficiary produce ordinals 5 and 6, exactly one sixth-referral award, and idempotent replay.

### 7. Wrong vs Correct

```java
// Wrong: buyer locks do not serialize two orders for the same superior.
mapper.lockMemberLevel(order.buyerUserId);
int ordinal = mapper.activeDirectCount(order.superiorUserId) + 1;

// Correct: the superior row is the shared mutex and the latest-ordinal query is a current read.
mapper.lockDirectPerformanceOwner(order.superiorUserId);
Integer next = mapper.nextDirectOrdinal(order.superiorUserId); // MAX(history) + 1, FOR UPDATE
int ordinal = next == null ? 1 : next;
```

## Scenario: V17 legacy after-sale migration preflight

Before normal Flyway migration, `LegacyAfterSaleMigrationPreflight` acquires the MySQL advisory lock `market-shop:legacy-aftersale-v17`. It repairs only duplicate `COMPLETED` rows when V17 is pending, selecting the canonical row by non-null `completed_at`, `state_entered_at` when present, `created_at`, and `id`. Retained rows are changed to terminal `CANCELLED` rows with an incremented version, a bounded system repair reason, and a system audit record when `operation_audit_log` exists. All rows and foreign keys remain intact.

An exact failed V17 entry is eligible for `Flyway.repair()` and one normal rerun only when the generated-column and unique-index artifacts are both absent and the applied checksum matches the resolved V17 source. Any other failed migration, checksum mismatch, missing history, or partial/ambiguous V17 artifact fails startup closed. A successful V17 is verified without data mutation. The advisory lock is always released, and logs contain only the migration version, artifact state, and duplicate counts.

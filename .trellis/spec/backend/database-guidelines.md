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
- Aftersale timeout configuration under `market-shop.jobs.*`: `aftersale-timeout-delay-ms` (worker fixed delay), `aftersale-awaiting-return-timeout-days`, `aftersale-return-shipped-timeout-days`, `aftersale-offline-refund-timeout-days`, `aftersale-buyer-confirm-timeout-days`. State machine: `AWAITING_RETURN → CANCELLED`, `RETURN_SHIPPED → PENDING_OFFLINE_REFUND`, `PENDING_OFFLINE_REFUND → PENDING_BUYER_REFUND_CONFIRMATION` (sets `offlineRefundConfirmedAt`), `PENDING_BUYER_REFUND_CONFIRMATION → COMPLETED` (sets `completedAt`, emits completed event).

## Contracts

1. An order write and its outbox event commit in the same transaction.
2. Inventory follows reserve → consume or reserve → release; available stock must never become negative.
3. The direct superior is immutable after first registration.
4. Distribution depth is exactly one. The first five qualified direct referrals contribute only qualification evidence. The sixth and later referrals allocate configurable A-available and B-frozen points.
5. Ledgers are append-only. Corrections create reversal entries; they never update historical amounts in place.
6. Aftersale completion invalidates evidence and appends reversals before recomputing levels.
7. Rule versions are immutable after publication; orders and evidence retain the applied rule version.
8. Worker queries use bounded batches and `FOR UPDATE SKIP LOCKED`. Singleton jobs also acquire `sys_job_lease`.
9. `trade_after_sale.state_entered_at` records when the row entered its current `status`. Every status transition UPDATE sets `state_entered_at = CURRENT_TIMESTAMP(3)`; inserts rely on the column default. The timeout worker selects the single oldest due row with `FOR UPDATE SKIP LOCKED` ordered by `state_entered_at, id`.

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
| Aftersale row is due in a timed state (`AWAITING_RETURN`, `RETURN_SHIPPED`, `PENDING_OFFLINE_REFUND`, `PENDING_BUYER_REFUND_CONFIRMATION`) | Timeout worker locks it `FOR UPDATE SKIP LOCKED` and transitions it to the next state; per-status day thresholds are configurable |

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
- Aftersale timeout tests cover each timed status reaching its day threshold, a non-due row being skipped, `FOR UPDATE SKIP LOCKED` excluding rows locked by another worker, the `sys_job_lease` preventing two nodes processing the same batch, and `state_entered_at` advancing on every transition UPDATE.

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
   without losing the sixth-referral reward boundary. The ordinal is an all-history completion
   sequence: aftersale marks its source performance `REVERSED` but never releases or reuses that
   ordinal. Replaying a source order inserts no new row.
9. Proof deletion and retention use a locking proof lookup (`FOR UPDATE`) joined to its order
   before object deletion; a non-locking eligibility read is never reused for a destructive action.
10. Runtime consumers of the current `ORDER_TIMERS` version fail closed when auto-receive days,
    after-sale days, proof count, or proof size is missing or outside the publisher's documented
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

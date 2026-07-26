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

## Contracts

1. An order write and its outbox event commit in the same transaction.
2. Inventory follows reserve → consume or reserve → release; available stock must never become negative.
3. The direct superior is immutable after first registration.
4. Distribution depth is exactly one. The first five qualified direct referrals contribute only qualification evidence. The sixth and later referrals allocate configurable A-available and B-frozen points.
5. Ledgers are append-only. Corrections create reversal entries; they never update historical amounts in place.
6. Aftersale completion invalidates evidence and appends reversals before recomputing levels.
7. Rule versions are immutable after publication; orders and evidence retain the applied rule version.
8. Worker queries use bounded batches and `FOR UPDATE SKIP LOCKED`. Singleton jobs also acquire `sys_job_lease`.

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

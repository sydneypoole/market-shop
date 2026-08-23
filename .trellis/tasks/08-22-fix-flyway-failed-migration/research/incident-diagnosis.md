# Flyway startup incident diagnosis

## Evidence

The supplied stack trace is a dependency-creation cascade. Its deepest cause is:

```text
java.lang.IllegalStateException: Flyway history contains a failed migration other than V17
    at com.marketshop.bootstrap.config.LegacyAfterSaleMigrationPreflight.validateHistory(...:148)
```

`LegacyAfterSaleMigrationPreflight` calls `flyway.info().all()` before normal migration. It permits an automatic `repair()` only for the exact V17 artifact after validating the history checksum and confirming that neither protected V17 DDL artifact exists. Every other failed migration is intentionally rejected.

## Current migration candidates

* V18 is a Java migration that reconstructs derived distribution/frozen-point projections from immutable ledger facts. It intentionally throws `FROZEN_BATCH_BALANCE_CONFLICT` when source facts or balances are inconsistent.
* V19 is a MySQL SQL migration that snapshots order/after-sale timers and introduces invitation-related guards.
* V19.1 is a MySQL SQL migration that adds single-use bootstrap invitation metadata and repair guards.

The database history now identifies the failed candidate as V18:

```text
18 | 18 | repair distribution projections | JDBC |
db.migration.V18__repair_distribution_projections | execution_time=24 | success=0
```

V18 contains only transactional DML and reads; it performs no DDL. Its deliberate validation failures use the `FROZEN_BATCH_BALANCE_CONFLICT` prefix. The fastest broad discriminator is whether each `DEMO_POINTS` account's stored frozen balance equals the sum of immutable ledger frozen deltas. A mismatch is explicitly rejected before derived projection rows are reconciled.

## Confirmed production trigger

Read-only diagnostics found:

* `ledger_account.frozen_points` equals the immutable ledger aggregate for every `DEMO_POINTS` account.
* Basic direct-award, frozen-release, reversal-source and existing-batch validation returned no conflicts.
* There is one duplicate ACTIVE direct performance: `performance_id=2`, beneficiary `9`, referred user `14`, source order `3`, duplicate rank `2`.
* The duplicate has no matching `DIRECT_REFERRAL_AWARD`; its account has zero available and frozen points.

`V18__repair_distribution_projections` currently derives one global repair timestamp only from `ledger_entry.occurred_at`. It checks that timestamp before scanning for matching award rows. With an awardless duplicate and no ledger timestamp, it throws `FROZEN_BATCH_BALANCE_CONFLICT: duplicate direct performance has no deterministic timestamp` even though the safe repair needs only to mark the duplicate projection row `REVERSED`.

`distribution_direct_performance.created_at` is non-null and persisted from V1. Including that timestamp in the deterministic maximum preserves a source-fact-derived timestamp, produces no ledger fact for an awardless duplicate, and remains stable across reruns.

## Implemented recovery

The startup preflight now accepts only one exact failed V18 JDBC record after verifying successful V17 artifacts and all other history metadata. It runs one protected Flyway repair and normal migration retry under the existing advisory lock. Missing, deleted, future, multiple, checksum-mismatched, or unknown failed history remains unchanged and fails with sanitized version/script/state diagnostics.

V18 now uses the maximum persisted ledger and ACTIVE direct-performance timestamp plus one millisecond. The confirmed production row therefore receives a deterministic `reversed_at` without a fabricated reward or reversal ledger entry.

## Operational constraint

`docs/production-operations.md` explicitly forbids manually running generic `flyway repair` or editing `flyway_schema_history`. MySQL DDL can survive a failed SQL migration because DDL statements implicitly commit. A safe repair therefore needs both the failed history record and an artifact audit.

## Local limitation

The local Docker client resolves to the OrbStack socket, but the daemon is not running. The attached log appears to come from a separate deployment host, so the live database state is unavailable in this workspace.

## Required read-only evidence

Run against the deployment Compose project:

```sh
docker compose exec -T mysql sh -lc 'mysql --protocol=tcp -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" --batch --raw -e "SELECT installed_rank, version, description, type, script, checksum, installed_on, execution_time, success FROM flyway_schema_history WHERE success = 0 ORDER BY installed_rank"'
```

Also retain the earliest app log containing the migration's original exception. Neither command mutates the database.

## Decision boundary

* Failed V18: determine the exact data conflict and transaction rollback state; repair source/projection consistency under the immutable-ledger contract, then use a version-specific retry procedure. A version-specific failed-V18 preflight may safely remove only the exact failed JDBC history row and rerun because V18 contains no DDL, but it must not rewrite or fabricate immutable ledger facts to silence a conflict.
* Failed V19/V19.1: compare information-schema objects to every statement in that script; introduce a version-specific guarded reconciliation or restore the pre-deploy backup.
* Any checksum/script mismatch: fail closed and restore/roll back the matching artifact rather than accepting altered history.

Regardless of version, the preflight exception should expose safe diagnostic fields (`version`, `script`, `state`) so a restart does not erase the actionable identity of the failure.

## Review findings

* Spring Boot 4.1 currently resolves Flyway Core 12.4.0 in this workspace. Its `MigrationInfo.isChecksumMatching()` deliberately returns true when either checksum is null, and the corresponding description/type helpers also treat missing resolved metadata as a match. Protected repair therefore uses explicit applied/resolved checksum equality in addition to the Flyway helpers; this accepts the normal null/null checksum of the V18 Java JDBC migration but rejects a non-null/null pair and prevents `repair()` from silently realigning another successful SQL migration.
* A history row newer than all resolved migrations is exposed as `FUTURE_SUCCESS` with no resolved metadata. It must be rejected by state before metadata matching; otherwise the null-tolerant Flyway helpers can let it through.
* MySQL 8.4 returns a zero timestamp for the old V9 aggregate over an unmatched outer-joined reversal in the synthetic legacy-release fixture. Because V9 is already immutable and applied in the upgrade scenario, V18 tests now migrate an empty schema through V9 first, then seed the historical V9-shaped facts/projections. The applied V9 script was not edited.

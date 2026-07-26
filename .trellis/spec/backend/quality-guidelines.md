# Backend Quality Guidelines

## Required Patterns

- Java 21 records for immutable request/result data where appropriate.
- Constructor injection and final dependencies.
- Framework-free domain model with explicit state transitions.
- Separate Sa-Token logic and token names for member and admin identities.
- RBAC checks on every administrative mutation.
- Private proof objects with authorization checks and short-lived presigned URLs.
- Transactional outbox/inbox for cross-context effects.
- Flyway as the only schema mutation mechanism.

## Forbidden Patterns

- Online payment, withdrawal, point-to-cash conversion, or reward depth above one.
- Mutable superior relationships after registration.
- Controller-to-mapper calls or business policy inside SQL.
- Raw/unchecked casts, `@SuppressWarnings` used to hide type defects, empty catches, or `printStackTrace`.
- Updating or deleting historical ledger rows to correct business data.
- Public object-storage buckets or permanent proof URLs.
- Hard-coded production credentials or enabling local mock login outside the `local` profile.

## Testing Requirements

Run `mvn test` before handoff. Every new aggregate transition needs positive and negative domain tests. Authentication changes need invalid credential, lockout, and inactive-account cases. Projection changes need duplicate-event tests. Rule changes need boundary tests for direct referrals 5 and 6. Storage changes must follow [Private Proof Object Storage](./object-storage.md) and need file type, size, ownership, URL-expiry, audit-actor, cleanup, and real S3 integration tests.

## Review Checklist

Confirm dependency direction, authorization, transaction boundary, idempotency, concurrency behavior, event emission, PII handling, migration compatibility, and test coverage. Search the source tree for TODOs, suppressions, debug output, and generated artifacts.

## Scenario: Single Bootstrap Administrator

### 1. Scope / Trigger

- Trigger: any change to bootstrap administrator environment variables, identity initialization, default operator accounts, or migrations that change administrator status.

### 2. Signatures

```text
MARKET_SHOP_BOOTSTRAP_ADMIN_ENABLED=false
MARKET_SHOP_BOOTSTRAP_ADMIN_USERNAME=admin
MARKET_SHOP_BOOTSTRAP_ADMIN_PASSWORD=<runtime secret, at least 12 characters>
```

```sql
SELECT username, status
FROM iam_admin_account
ORDER BY id;
```

### 3. Contracts

- A new empty environment bootstraps exactly one administrator: the configured username, normally `admin`, with role `SUPER_ADMIN`.
- No password is committed, seeded by Flyway, or defaulted by application code. The runtime secret is BCrypt-hashed and the account must change it at first login.
- Built-in roles remain available for accounts created later by the super administrator, but bootstrap never creates `ops-order`, `ops-fulfillment`, or `ops-catalog`.
- Upgrade migrations disable former `ops-*` bootstrap identities instead of deleting them, preserving foreign keys and historical audit attribution.
- Bootstrap is idempotent: if any administrator exists, restarting with the flag enabled creates no additional account and changes no existing password.

### 4. Validation & Error Matrix

| Condition | Required result |
|---|---|
| Bootstrap disabled | Create no administrator |
| Enabled with password shorter than 12 characters | Fail startup visibly |
| Enabled on an empty administrator table | Create one configured `SUPER_ADMIN` with `must_change_password = 1` |
| Enabled when an administrator already exists | No insert and no password reset |
| Upgrade finds a former default `ops-*` account | Set status to `DISABLED`; retain the identity row and audit references |
| Repository or migration contains a plaintext login password | Quality gate fails |

### 5. Good/Base/Bad Cases

- Good: an operator injects a strong temporary secret once, starts the empty database, verifies only `admin`, then disables bootstrap.
- Base: a later restart leaves the existing administrator unchanged.
- Bad: seed multiple active operator accounts, hard-code a shared password, delete identities referenced by shipment/audit rows, or use bootstrap as a password-reset mechanism.

### 6. Tests Required

- Unit: enabled empty-state initialization inserts exactly one configured admin, assigns only `SUPER_ADMIN`, hashes the supplied password, and requires password change.
- Unit: disabled and existing-admin paths insert nothing.
- Migration/runtime smoke: Flyway applies from an empty MySQL 8.4 schema; query returns one active `admin` and schema version 7 or later.
- Upgrade smoke: legacy `ops-*` rows become disabled without deleting referenced historical data.

### 7. Wrong vs Correct

#### Wrong

```java
for (String username : List.of("admin", "ops-order", "ops-fulfillment", "ops-catalog")) {
    createAdmin(username, "shared-default-password");
}
```

#### Correct

```java
AdminAccountPo admin = new AdminAccountPo();
admin.username = configuredUsername;
admin.passwordHash = BCrypt.hashpw(runtimeSecret, BCrypt.gensalt(12));
admin.mustChangePassword = true;
mapper.insertAdmin(admin);
mapper.assignRole(admin.id, "SUPER_ADMIN");
```

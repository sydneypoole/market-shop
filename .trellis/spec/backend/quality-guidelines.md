# Backend Quality Guidelines

## Required Patterns

- Java 21 records for immutable request/result data where appropriate.
- Constructor injection and final dependencies.
- A Spring bean with more than one constructor explicitly marks exactly one injection constructor. A repository-wide contract test enforces the rule, and wiring changes add a focused context test that proves Spring can instantiate the affected bean.
- Framework-free domain model with explicit state transitions.
- Separate Sa-Token logic and token names for member and admin identities.
- Authentication is cookie-only for browsers: login bodies and response headers never expose token material; both cookies are `HttpOnly`, `SameSite=Lax`, and `Secure` in production.
- Credentialed CORS mappings replace Spring's permit-all origin default with an exact configured allowlist (or an empty list for the normal single-origin deployment); they never combine `allowCredentials=true` with `*`.
- Account lifecycle mutations call an application-owned session-control port. Member status changes and administrator lock, disable, password reset, role assignment, or effective role-permission changes invalidate every affected Sa-Token session immediately.
- An administrator's active self password change also calls `invalidateAdminSessions(adminId)` and then re-logs in the same administrator so the current operator keeps working. The re-login runs after the transactional password update commits, re-reads the new `authEpoch`, and repopulates the current token-session with the preserved `username`, `displayName`, `roles`, and `permissions` alongside `mustChangePassword=false` and the new epoch. Other sessions are rejected by the epoch bump; only the current session is replaced.
- Administrator login re-reads the credential after resetting failure counters and re-validates the submitted password against the refreshed password hash before creating a session; copying only the refreshed epoch would let a concurrent password reset authorize the old password at the new epoch.
- Browser `POST`/`PUT`/`PATCH`/`DELETE` requests with an `Origin` header must match the framework-reconstructed public origin. Originless trusted non-browser requests remain supported.
- RBAC checks on every administrative mutation.
- Private proof objects with authorization checks and short-lived presigned URLs.
- Transactional outbox/inbox for cross-context effects.
- Flyway as the only schema mutation mechanism.
- Catalog admin `coverUrl` and content `targetUrl` inputs pass a `validateUrl` guard that rejects any value not starting with `http://`, `https://`, or `/` (application-relative media paths). Blank input is normalized to `null`; the guard runs before persistence so neither raw external protocols nor `javascript:`/`data:` payloads reach the database or rich-text sanitizer.

## Forbidden Patterns

- Online payment, withdrawal, point-to-cash conversion, or reward depth above one.
- Mutable superior relationships after registration.
- Controller-to-mapper calls or business policy inside SQL.
- Raw/unchecked casts, `@SuppressWarnings` used to hide type defects, empty catches, or `printStackTrace`.
- Updating or deleting historical ledger rows to correct business data.
- Public object-storage buckets or permanent proof URLs.
- Hard-coded production credentials or enabling local mock login outside the `local` profile.

## Testing Requirements

Run `mvn test` before handoff. Every new aggregate transition needs positive and negative domain tests. Authentication changes need invalid credential, lockout, inactive-account, target-session invalidation, cookie-attribute, token-non-disclosure, same-origin, credentialed-CORS allowlist, cross-origin, originless-client, and concurrent-password-reset cases. Projection changes need duplicate-event tests. Rule changes need boundary tests for direct referrals 5 and 6. Storage changes must follow [Private Proof Object Storage](./object-storage.md) and need file type, size, ownership, URL-expiry, audit-actor, cleanup, and real S3 integration tests. Testcontainers integration contexts should scan MyBatis mapper packages explicitly with `basePackages` so the mapper proxy is present when Docker is available, not only when the test is skipped by `disabledWithoutDocker`.

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
MARKET_SHOP_BOOTSTRAP_INVITE_CODE=<one-time bootstrap invitation code>
MARKET_SHOP_BOOTSTRAP_SPONSOR_CLAIM_SECRET=<independent runtime secret, at least 32 characters>
```

```sql
SELECT username, status
FROM iam_admin_account
ORDER BY id;
```

### 3. Contracts

- A new empty environment bootstraps exactly one administrator: the configured username, normally `admin`, with role `SUPER_ADMIN`.
- No password or claim secret is committed, seeded by Flyway, or defaulted by application code. The runtime password is BCrypt-hashed and the account must change it at first login; the independent claim secret is stored only as a SHA-256 hash.
- Built-in roles remain available for accounts created later by the super administrator, but bootstrap never creates `ops-order`, `ops-fulfillment`, or `ops-catalog`.
- Upgrade migrations disable former `ops-*` bootstrap identities instead of deleting them, preserving foreign keys and historical audit attribution.
- Bootstrap is idempotent: if any administrator exists, restarting with the flag enabled creates no additional account and changes no existing password.

### 4. Validation & Error Matrix

| Condition | Required result |
|---|---|
| Bootstrap disabled | Create no administrator |
| Enabled with password shorter than 12 characters | Fail startup visibly |
| Enabled with an invalid administrator username or invitation code | Fail startup before identity writes |
| Enabled without an independent sponsor claim secret | Fail startup visibly; never derive it from the invitation code |
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

## Scenario: Cold-cache Mockito instrumentation

### 1. Scope / Trigger

- Trigger: changing Mockito, Java, Maven, Surefire, backend parent dependencies, or test JVM arguments.
- Mockito remains a test-only dependency; no domain production class may import it.

### 2. Signatures

```text
mvn -B -ntp -f backend/pom.xml test
maven-dependency-plugin:properties -> org.mockito:mockito-core:jar
Surefire argLine -> @{argLine} -Xshare:off -javaagent:@{org.mockito:mockito-core:jar}
```

### 3. Contracts

- The backend parent declares `mockito-core:${mockito.version}` with `test` scope because every child Surefire fork uses that JAR as a startup agent.
- `maven-dependency-plugin:properties` is registered under `build/plugins`, not only `pluginManagement`, and bound to `initialize` so it resolves the agent and publishes its actual artifact path before the test phase.
- Surefire uses late `@{...}` replacement for both the predeclared empty `argLine` and the resolved Mockito artifact path. It never constructs a repository path manually.
- A clean runner and an isolated Maven local repository must behave the same as a warm developer cache.

### 4. Validation & Error Matrix

| Condition | Required result |
|---|---|
| Maven cache has no `mockito-core` JAR | Resolve it before the first child test JVM starts |
| Existing tooling supplies another `argLine` fragment | Preserve it through `@{argLine}` |
| Agent artifact cannot be resolved | Fail dependency resolution before Surefire, with the missing coordinate visible |
| Backend production artifact is inspected | Mockito is absent because its inherited scope is `test` |

### 5. Good/Base/Bad Cases

- Good: `shop-domain` is the first reactor module on an empty runner; Maven resolves the declared agent and all tests start.
- Base: a warm local repository follows the same dependency and late-property path.
- Bad: point `-javaagent` directly at `${settings.localRepository}/org/mockito/...`; that string does not resolve the artifact and fails only on cold runners.
- Bad: disable forking or enable dynamic agent loading to hide a missing startup agent.

### 6. Tests Required

- Run the complete backend reactor on Java 21.
- Reproduce the first-module build with an isolated Maven local repository that initially lacks the Mockito JAR.
- Keep a static build-contract test asserting the dependency goal, late Surefire properties, inherited test dependency, and absence of a manually assembled local-repository path.

### 7. Wrong vs Correct

```xml
<!-- Wrong: a path expression does not download the agent. -->
<argLine>-javaagent:${settings.localRepository}/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar</argLine>

<!-- Correct: resolve the declared test artifact, then inject its late-bound path. -->
<goal>properties</goal>
<argLine>@{argLine} -Xshare:off -javaagent:@{org.mockito:mockito-core:jar}</argLine>
```

## Scenario: Spring bean constructor selection

### 1. Scope / Trigger

- Trigger: adding a secondary constructor to a Spring-managed component for tests, fixtures, clocks, clients, or migration compatibility.

### 2. Signatures

```java
@Component
final class Adapter {
    @Autowired
    public Adapter(@Value("${adapter.enabled:false}") boolean enabled) { ... }

    Adapter(boolean enabled, Client client) { ... }
}
```

### 3. Contracts

- A component with one constructor may rely on implicit constructor injection.
- As soon as a second constructor exists, exactly one runtime injection constructor is explicitly marked with `@Autowired`; fixture constructors remain unannotated.
- Tests that call constructors directly do not prove Spring wiring. The repository-wide constructor contract catches ambiguous components, and a component whose wiring is added or repaired has a minimal application-context test with representative configuration.

### 4. Validation & Error Matrix

| Condition | Required result |
|---|---|
| One constructor | Spring may inject it implicitly |
| Multiple constructors and one `@Autowired` constructor | Spring selects that constructor and creates the bean |
| Multiple constructors without an injection marker or default constructor | Context test fails with the constructor-selection error before delivery |
| More than one required injection constructor | Context test fails; retain one runtime constructor |

### 5. Good/Base/Bad Cases

- Good: the public configuration constructor is annotated and delegates to a package-private fixture constructor.
- Base: a component has exactly one constructor and needs no marker.
- Bad: direct unit tests instantiate both constructors successfully while the packaged application fails because Spring searches for a default constructor.

### 6. Tests Required

- Keep focused unit tests for adapter behavior and injected test doubles.
- Add an `ApplicationContextRunner` or equivalent context test for the changed component, supply its required properties, and assert one bean with no startup failure.
- Keep a repository-wide classpath-scanning contract test that rejects every Spring stereotype with multiple constructors unless exactly one constructor has `@Autowired`.

### 7. Wrong vs Correct

```java
// Wrong: two constructors, neither selected for dependency injection.
public Adapter(boolean enabled) { ... }
Adapter(boolean enabled, Client client) { ... }

// Correct: runtime wiring is unambiguous and the fixture seam remains available.
@Autowired
public Adapter(@Value("${adapter.enabled:false}") boolean enabled) { ... }
Adapter(boolean enabled, Client client) { ... }
```

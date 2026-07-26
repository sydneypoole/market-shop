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

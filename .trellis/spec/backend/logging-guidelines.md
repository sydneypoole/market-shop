# Logging and Audit Guidelines

## Operational Logging

Use SLF4J parameterized logging. Include stable identifiers such as `requestId`, `orderId`, `eventId`, `jobName`, and `adminId`. Log lifecycle milestones at `INFO`, recoverable retry conditions at `WARN`, diagnostic details at `DEBUG`, and unexpected failures at `ERROR`.

Never log passwords, Sa-Token values, OAuth authorization codes, Redis OAuth state, complete phone numbers, addresses, proof contents, presigned URLs, MySQL credentials, or RustFS secrets. Do not use `System.out`, `printStackTrace`, or string-concatenated secrets.

## Immutable Admin Audit

Successful mutating `/api/v1/admin/**` requests, except login, must append `operation_audit_log` through `AdminAuditPort`. The record contains request ID, admin actor, HTTP method/path, resource category, result, masked IP, and a bounded user-agent summary. Request bodies are deliberately excluded because they may contain credentials or personal data.

An audit write must never block the business response solely because a non-critical user-agent field cannot be parsed. Audit storage failures, however, must be visible in operational logs.

## Examples

Good:

```java
log.info("Outbox event published eventId={} eventType={}", eventId, eventType);
```

Bad:

```java
log.info("Admin login password={} token={}", password, token);
```

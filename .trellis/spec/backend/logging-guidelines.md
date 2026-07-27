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

## Scenario: Colorized Application Logs and Quiet Proxy Output

### 1. Scope / Trigger

- Trigger: changing Spring Boot console formatting, container log environment variables, or Nginx access logging.
- The goal is human-readable Java application output without noisy per-request proxy records or leaked query-string secrets.

### 2. Signatures

- Spring configuration: `spring.output.ansi.enabled`, `logging.level.root`, `logging.level.com.marketshop`, `logging.pattern.console`.
- Container environment: `MARKET_SHOP_LOG_ANSI`, `MARKET_SHOP_LOG_LEVEL`, `MARKET_SHOP_APP_LOG_LEVEL`.
- Nginx must forward `X-Request-Id: $request_id` to `/api/`.

### 3. Contracts

- `MARKET_SHOP_LOG_ANSI` is optional and defaults to `ALWAYS`; accepted values are `DETECT`, `ALWAYS`, and `NEVER`.
- `MARKET_SHOP_LOG_LEVEL` and `MARKET_SHOP_APP_LOG_LEVEL` are optional and default to `INFO`.
- Spring console output includes timestamp, severity, application name, thread, logger, message, and stack trace. Severity must be colorized through Spring Boot `%clr`.
- Nginx access logging is disabled globally so normal container output is reserved for Spring Boot.
- Nginx sends only critical runtime errors to stderr. Configuration failures must remain visible during image validation and startup.
- Request IDs are still generated and forwarded to Spring even though Nginx does not emit an access record.
- Log collectors that do not strip ANSI must deploy with `MARKET_SHOP_LOG_ANSI=NEVER`.

### 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Logging environment variables are absent | Use `ALWAYS`, `INFO`, and `INFO` defaults |
| `MARKET_SHOP_LOG_ANSI` is outside the accepted enum | Reject the invalid application configuration at startup |
| A log collector receives ANSI control bytes | Set `MARKET_SHOP_LOG_ANSI=NEVER`; do not fork a second pattern |
| A normal HTTP request reaches Nginx | Emit no Nginx access-log line |
| Nginx encounters a non-critical warning | Keep container output quiet |
| Nginx encounters a critical runtime failure | Emit the failure to stderr |
| Nginx configuration is invalid | Fail `nginx -t` or image/container validation |

### 5. Good / Base / Bad Cases

- Good: `ALWAYS` gives operators colored Spring severity levels while Nginx stays silent for normal requests.
- Base: no environment overrides still yields readable `INFO` logs.
- Bad: enabling per-request Nginx access logs in the combined container or logging OAuth callbacks, presigned URLs, passwords, tokens, or credentials.

### 6. Tests Required

- Container package test asserts the ANSI and level environment wiring, `%clr` pattern, disabled Nginx access logging, critical-only Nginx errors, and forwarded request ID.
- `docker compose --env-file .env.example config --quiet` must pass with and without the `rustfs` profile.
- Nginx configuration or the built image must pass `nginx -t`.
- A runtime smoke test should verify ANSI bytes are present for `ALWAYS` and absent for `NEVER`.

### 7. Wrong vs Correct

Wrong:

```nginx
access_log /dev/stdout combined;
# Per-request proxy output hides the more useful Java application logs.
```

Correct:

```nginx
error_log /dev/stderr crit;
access_log off;
proxy_set_header X-Request-Id $request_id;
```

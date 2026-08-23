# Error Handling

## Model

Expected business failures use `DomainException` or an application-level argument/state exception with a stable message. Controllers do not catch these individually. `GlobalExceptionHandler` translates them into the common `ApiResponse` envelope.

Validation failures return HTTP 400. Missing authentication returns 401, insufficient permission returns 403, missing resources return 404, state/idempotency/concurrency conflicts return 409, and unexpected failures return 500 with a generic public message.

## API Contract

Every response uses the same top-level shape:

```json
{"success": false, "code": "ORDER_STATE_CONFLICT", "message": "Order cannot be shipped", "data": null}
```

Error codes are stable machine-readable identifiers. Messages may be localized and must not contain SQL, stack traces, secret values, object-storage keys, OAuth codes, or internal class names.

## Propagation

- Domain code throws only when an invariant is violated.
- Application services add operation context only when it changes the client-facing meaning.
- Infrastructure adapters translate vendor exceptions at the port boundary.
- Controllers rely on Bean Validation for request shape and do not swallow failures.
- Scheduled workers log the event/job identifier and leave failed work retryable.

## Common Mistakes

Do not return HTTP 200 with an ambiguous string error, catch `Exception` and continue a partially completed transaction, expose a raw MySQL exception, or convert a concurrency conflict into “not found.”

Fixed ordinary invitation mutations use HTTP 409 `INVITATION_IMMUTABLE`. Both the legacy revoke and regenerate routes return this exact code before any persistence call. Exhausting bounded unique-code insertion retries returns `INVITATION_CREATE_FAILED` and rolls back the complete registration or ensure transaction.

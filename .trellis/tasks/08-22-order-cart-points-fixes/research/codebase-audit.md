# Codebase Audit

## Scope

The reported issues span the native WeChat mini-program and the Java backend:

- order completion and direct-referral point projection
- order cancellation request handling
- address create/update request normalization
- tab page refresh behavior
- cart stepper hit target and mutation flow
- invitation-code clipboard handling
- direct-referral point-pool split

## Existing Data Flow

### Completed order to points

1. `CommerceApplicationService.receive` moves an order from `SHIPPED` to `COMPLETED`.
2. `MyBatisCommerceAdapter.persistTransition` records an `ORDER_COMPLETED` outbox event with rule snapshots.
3. `OutboxProjectionJob` invokes `OutboxProjectionProcessor.projectCompletedOrder` asynchronously.
4. Upgrade orders project membership and direct performance; repurchase orders release frozen points.
5. Direct-referral points are credited to the superior and a frozen batch is created.

Current rule seed is `320 = 160 available + 160 frozen`. The reported rule is `320 = 300 dividend pool + 20 repurchase-dividend pool`, starting from the sixth qualifying direct referral.

### Mini-program cart

`pages/cart/index.js` reloads the complete cart after every quantity, selection, and deletion mutation. The page also uses a full-shell loading state during each mutation. `components/stepper` wraps the vendored FirstUI input-number component with a narrow sign hit target.

### Mini-program navigation

The main tab pages perform a visible loading refresh from `onShow`, so every tab switch appears to reload the page even when usable data is already rendered.

## Root Causes / Fix Direction

- Award eligibility must use the locked historical direct ordinal, not the mutable active-direct count.
- A completed-order view should trigger/observe projection without requiring the user to leave and manually reload.
- Cancellation needs a valid default reason when editable-modal content is unavailable on a device/base-library version.
- Address create payloads should omit update-only fields and optional empty values.
- Page refreshes should retain rendered data and refresh in the background after the initial load.
- Cart mutations should patch the server-confirmed result into local state and only fall back to a full reload on conflict/failure.
- The shared stepper needs independent 88rpx-class minus/plus hit areas without editing vendored FirstUI files.
- Clipboard handling needs explicit success/failure feedback and a larger tappable surface.

## Open Business Rule

The phrase “按月平均分配后清零” does not define the monthly recipient set for either pool. The existing implementation stores points per superior member and has no platform-wide monthly pool/distribution table. Recipient eligibility and month-close timing must be confirmed before a destructive month-end clearing job is introduced.

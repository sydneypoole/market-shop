# P1 gap audit

## Existing capabilities

- Admin catalog covers categories, products, multiple SKUs, assets, content and inventory adjustments.
- Admin commerce covers paged order search, time filters, CSV export, notes, batch shipment and dashboard metrics.
- Member operations cover paged search, detail, status updates, manual recomputation and audit.
- Storefront covers order/after-sale detail, proof delivery, notifications and responsive PC/H5 flows.
- Rules expose direct-referral points, frozen-point release and inactivity downgrade configuration.

## Material gap

`ledger_account.frozen_points` is currently the only persisted remaining balance used by repurchase release. The original requirement calls for versioned FIFO batches. Aggregate-only release cannot answer:

- which award batch was consumed first;
- how much remains in each award batch;
- which source frozen award a release entry consumed;
- how a repurchase after-sale restores the exact consumed batches.

## Recommended model

- Add an immutable-origin/mutable-remaining `ledger_frozen_batch` row per frozen award entry.
- Uniquely key each batch by `source_ledger_entry_id`.
- Lock active rows by `(account_id, created_at, id)` for FIFO release.
- Split one order's release into one ledger entry per consumed batch.
- Associate each release with its source award via `ledger_entry.original_entry_id`.
- Persist `ledger_frozen_release_item` mappings so legacy release rows and cross-batch aftersales restore exact batches without rewriting immutable ledger history.
- Restore consumed batches when the repurchase release is reversed.
- Close remaining batches when their originating award is reversed.

## Validation priorities

- Empty MySQL migration from V1 through V9.
- Event and ledger idempotency under replay.
- Cross-batch FIFO release.
- Direct-award and repurchase-order after-sale symmetry.
- Inactivity downgrade idempotency.
- Application validation and authorization for P1 admin flows.

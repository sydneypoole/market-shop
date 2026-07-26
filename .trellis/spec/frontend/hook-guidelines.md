# Composable and Data-Fetching Guidelines

The current applications keep simple route-specific data loading inside their views. Extract a Vue composable only when stateful behavior is reused by at least two components or has an independently testable lifecycle.

Composable names start with `use`, return typed refs/computed values, and expose explicit actions. They must not read a different application's storage key or hide irreversible admin mutations.

All HTTP calls go through the application `api.ts` client. A view owns loading/error presentation and reloads authoritative server state after a successful mutation. Cancel or ignore stale responses when route parameters change.

Do not add a general cache until invalidation rules are explicit; order, membership, inventory, and aftersale states are server-authoritative.

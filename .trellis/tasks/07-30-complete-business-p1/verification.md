# P1 Verification

Verified on 2026-07-30 in the project workspace.

## Business and persistence

- Flyway V9 creates `ledger_frozen_batch`, `ledger_frozen_release`, and `ledger_frozen_release_item`.
- Empty MySQL 8.4 applied V1 through V9 successfully.
- Runtime health returned `{"status":"UP"}`.
- Empty-environment bootstrap contained one active `admin` and three storefront templates.
- FIFO projection tests cover cross-batch release, duplicate order protection, ordinary and below-threshold orders, aggregate/batch mismatch rollback, sixth-referral batch creation, mapped aftersale restoration, direct-award closure, and already-reversed source batches.
- Mapper contract test proves source-linked `FROZEN_POINTS_RELEASED` entries remain eligible for aftersale reversal while reversal entries are excluded.

## Automated quality gates

- `mvn -f backend/pom.xml clean test package`
  - domain: 7 passed
  - application: 41 passed
  - infrastructure: 31 passed plus 1 S3 integration test skipped without external service credentials
  - interfaces: 5 passed
  - executable Spring Boot JAR packaged successfully
- `pnpm test`
  - storefront: 7 passed
  - admin: 9 passed
  - container/workflow contracts: 6 passed
- `pnpm build:web`
  - storefront typecheck and production build passed
  - admin typecheck and production build passed
- `docker compose --env-file .env.example config --quiet` passed.
- `docker compose --env-file .env.example --profile rustfs config --quiet` passed.
- `git diff --check` passed.

## Runtime cleanup

The isolated MySQL/Redis containers, Docker network, local application process, and temporary upload directory used for verification were removed. No existing project volume was touched.

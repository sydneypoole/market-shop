# Miniprogram interface gap audit (2026-08-09)

## Confirmed blockers

1. The client submits order source `MINIPROGRAM`; `CommerceApplicationService.normalizeSource` accepts only `H5` and `WEB`, so miniprogram order creation returns `ORDER_SOURCE_INVALID`.
2. `superiorConfirmOfflineRefund` sends an empty POST while `AfterSaleController` requires a JSON `ConfirmRefundRequest`, so the transition cannot complete.

## Cross-layer gaps

- The production client still uses `http://localhost:8080` and has no env-version/ext-config resolver.
- Local private storage signs application-relative `/api/v1/storage/private/...` URLs; proof pages pass these directly to image/preview APIs rather than resolving them against the API origin.
- Proof pages hard-code three files while backend capabilities/rules are dynamic.
- After-sale uploads omit `proofType`, causing every upload to default to `APPLICATION`.
- Miniprogram login omits the backend-supported `sponsorClaimSecret` bootstrap claim.
- Checkout renders a remark field but does not submit or persist it.
- `auth.logout` and `system.capabilities` wrappers exist but are not consumed; the superior order page has no normal navigation entry.
- The backend exposes content detail but the miniprogram only consumes content lists.
- Customer-service actions are placeholders rather than native WeChat contact buttons.
- Several core requests swallow errors and present fallback/empty data as success.
- Product add-to-cart calls set semantics and can overwrite an existing quantity rather than incrementing it.

## Verification gaps

- The root quality scripts and GitHub Actions do not run a miniprogram build/static/consumer-contract gate.
- Runtime smoke obtains a miniprogram token but does not use the token header on a protected API.
- Business E2E relies on dev-login cookie sessions and does not prove the miniprogram Header transport.
- Real WeChat code2session is unit-tested with an HTTP mock; real credentials and Developer Tools/device validation remain deployment checks.

## Existing positive coverage

- 47 API wrappers exist and 45 are referenced by pages.
- Login/token injection, catalog, cart, address CRUD, order operations/proofs, member/invitation/points, notifications, rules, and after-sale pages are implemented.
- The miniprogram token name matches `StpUserKit`: `market-shop-user-token`.
- All current miniprogram JavaScript and tracked JSON files pass syntax/parse checks.

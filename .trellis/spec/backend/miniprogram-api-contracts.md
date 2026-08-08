# Miniprogram Public API Contracts

## Scenario: Authenticated member queries and public catalog projection

### 1. Scope / Trigger

- Trigger: any change to miniprogram login, catalog/cart/order/address/proof public APIs, invitation lookup, or active distribution-rule projection.
- Controllers translate the authenticated member ID into an application use-case call. They must not query MyBatis mappers or reconstruct authorization rules.
- Public rule output is a read-only projection. It must not expose draft, disabled, future, expired, or superseded rule versions.
- There is no Web storefront SPA and no storefront template CMS endpoint.

### 2. Signatures

```java
AuthUseCase.LoginResult miniprogramLogin(MiniprogramLoginCommand command);

CommerceUseCase.OrderView submit(long userId, SubmitOrderCommand command);
CommerceUseCase.OrderDetail order(long userId, long orderId);
OrderProofUseCase.UploadLimits uploadLimits();
List<OrderProofUseCase.ProofView> listUser(long userId, long orderId);

AfterSaleUseCase.View afterSale(long userId, long afterSaleId);
List<AfterSaleProofUseCase.ProofView> listUser(long userId, long afterSaleId);

MembershipUseCase.InvitationView currentInvitation(long userId);
List<MembershipUseCase.RuleView> activeRules();
```

```http
POST /api/v1/auth/wechat/miniprogram/login
Content-Type: application/json

{
  "code": "wx.login js_code",
  "inviteCode": "optional for existing identity",
  "sponsorClaimSecret": "optional one-time bootstrap claim"
}

→ ApiResponse<{
  "token": "Sa-Token user token value",
  "publicId": "member public id",
  "nickname": "display name",
  "newlyRegistered": true
}>

# Subsequent member requests:
# Header: market-shop-user-token: <token>
# Cookie transport remains enabled as a fallback.

GET /api/v1/catalog/products
GET /api/v1/catalog/products/{productId}
GET /api/v1/catalog/categories
GET /api/v1/content

GET|POST|PUT|DELETE /api/v1/cart...
GET|POST|PUT|DELETE /api/v1/addresses...
GET|POST /api/v1/orders...
GET /api/v1/orders/{orderId}
GET /api/v1/orders/{orderId}/proofs
GET /api/v1/order-proofs/{proofId}/download

GET /api/v1/after-sales/{afterSaleId}
GET /api/v1/after-sales/{afterSaleId}/proofs
GET /api/v1/after-sale-proofs/{proofId}/download

GET /api/v1/membership/invitation
GET /api/v1/rules/active

POST /api/v1/orders
{
  "clientRequestId": "stable retry key, max 80",
  "source": "MINIPROGRAM",
  "buyerNote": "optional, max 500 Unicode code points",
  "address": { "...": "persisted delivery snapshot" },
  "items": [{ "skuId": 1, "quantity": 1 }]
}

GET /api/v1/system/capabilities
→ ApiResponse<{
  "devLoginEnabled": false,
  "wechatLoginEnabled": true,
  "maxProofFiles": 3,
  "maxProofSizeBytes": 8388608
}>
```

```sql
-- Flyway V14
ALTER TABLE trade_order
    ADD COLUMN buyer_note VARCHAR(500) NULL AFTER address_snapshot_json;
```

### 3. Contracts

- Miniprogram login exchanges `code` via code2session (`WECHAT_MP` provider). Mock mode (`market-shop.wechat.mock-enabled=true`) treats `code` as openId and does not call WeChat.
- Login response returns the Sa-Token value in `token`; clients must send it as header `market-shop-user-token` on protected member APIs.
- Native miniprogram checkout sends `source=MINIPROGRAM`. `H5` and `WEB` remain accepted for historical compatibility, but new member UI is not reintroduced for them.
- `buyerNote` is trimmed once, persisted as `trade_order.buyer_note`, and returned as `OrderDetail.buyerNote`; blank input becomes `null`. It is member-authored text and must be rendered as text rather than trusted HTML.
- `system/capabilities` exposes the same authoritative proof file-count and byte-size configuration used by upload services. Miniprogram pages must not hard-code these limits.
- Order detail and proof access are allowed only to the buyer, the immutable direct superior, or an explicitly permitted admin path.
- After-sale detail and proof access are allowed only to the applicant, the order's immutable direct superior, or an explicitly permitted admin path.
- Authorization is checked before proof metadata is returned and again before a short-lived download URL is signed.
- Every proof list, preview, or download action appends an attributable audit record using the real member/admin actor.
- `currentInvitation` is read-only and returns no invitation when one does not exist. Page loading must never create or regenerate a code.
- `activeRules` returns only `ACTIVE` rules whose effective window includes the current time. When multiple rows share a `ruleCode`, only the latest effective/versioned row is projected.
- The public rules endpoint exposes displayable configuration only. Rule evaluation, qualification, points, and commission remain server-authoritative.
- All endpoints use the common `ApiResponse` envelope and integer-fen money fields.
- Order and after-sale mutation idempotency keys are trimmed exactly once, and the same normalized value is used for both lookup and persistence. A concurrent duplicate-key insert must return the already-created aggregate without repeating inventory, notification, or other side effects.
- Template endpoints (`/api/v1/storefront/template`, `/api/v1/admin/storefront/templates*`) are removed.

### 4. Validation & Error Matrix

| Condition | Required result |
|---|---|
| Missing member session on protected query | HTTP 401; no data or signed URL |
| Authenticated but unrelated member | HTTP 403 with stable access-denied code |
| Order, after-sale, proof, or invitation is absent | HTTP 404 or nullable invitation result according to the use-case contract |
| Order/after-sale relationship changed in request data | Ignore client claims; resolve ownership from persisted aggregate data |
| Proof storage signing fails | Stable signing failure; no object key or vendor detail |
| Rule is draft, disabled, future, or expired | Excluded from the public projection |
| Multiple active versions overlap | Return only the latest applicable version per `ruleCode` |
| WeChat disabled and mock off | HTTP 409 `WECHAT_DISABLED` on miniprogram login |
| WeChat code exchange fails | Domain code `WECHAT_CODE_EXCHANGE_FAILED` |
| New identity without invite code | Domain code starts with `INVITE_CODE` |
| Order source is not `H5`, `WEB`, or `MINIPROGRAM` | `ORDER_SOURCE_INVALID`; reject before checkout/inventory work |
| `buyerNote` exceeds 500 Unicode code points | `ORDER_BUYER_NOTE_INVALID`; reject before checkout/inventory work |
| `buyerNote` is absent or blank | Persist and return `null` |
| Proof capability configuration is absent or outside backend bounds | Fail closed with the stable rule/settings error; never fabricate a client default |
| Retried order/after-sale mutation with surrounding whitespace in its idempotency key | Resolve the same normalized key and return the original aggregate |
| Concurrent duplicate order/after-sale insert | Return the winning aggregate; do not repeat inventory, notification, or outbox side effects |

### 5. Good/Base/Bad Cases

- Good: miniprogram obtains `code` via `wx.login`, posts login, stores `token`, and calls catalog/cart/orders with the header.
- Good: an order buyer opens the detail page, sees persisted line snapshots and logistics, lists proof metadata, and requests a fresh five-minute preview URL.
- Good: miniprogram checkout submits `MINIPROGRAM` plus an optional `buyerNote`; detail reads the same normalized note after a Flyway-upgraded restart.
- Good: proof pages obtain `maxProofFiles` and `maxProofSizeBytes` from capabilities and resolve application-relative signed URLs against the configured HTTPS API origin.
- Good: the direct superior reviews an after-sale detail while an unrelated member receives 403.
- Base: a member has no invitation; the client displays an explicit empty state and creates one only after a user action.
- Base: no active distribution rule exists; the client shows a retryable/empty configuration state rather than fabricated thresholds.
- Base: checkout omits `buyerNote`; the database stores `NULL` and detail omits the note UI.
- Bad: create an invitation during a GET/page-mount flow, return every historical rule version, trust a `userId` query parameter, or return a permanent RustFS URL.
- Bad: reintroduce Web OAuth authorize/callback or a storefront template CMS.
- Bad: send `source=MINIPROGRAM` while the backend whitelist still accepts only Web sources, hard-code three proof files, or display an application-relative signed URL without adding the API origin.

### 6. Tests Required

- Application tests cover miniprogram login (new user + invite, missing invite, existing identity), buyer/applicant access, direct-superior access, unrelated-member denial, absent resources, and proof-list audit actors.
- Rule projection tests cover inactive, future, expired, overlapping, and latest-version selection.
- Invitation tests prove lookup is read-only and absence does not call a create/regenerate port.
- Interface/contract tests verify protected routes require a member session while `/api/v1/rules/active` and catalog/content remain public as designed.
- Login response contract tests require non-empty `token` on miniprogram login.
- Order tests cover all three accepted source values, reject unknown sources before checkout, round-trip `buyerNote` through controller/application/domain/MyBatis/detail/auto-receive, and apply V14 on an upgraded schema.
- Capability tests prove the public response reads `maxProofFiles` and `maxProofSizeBytes` from the same application port used by uploads.
- Miniprogram consumer tests cover request path/method/body, Header token transport, 401 cleanup, 409 refresh metadata, relative signed/rich-text media URLs, dynamic proof limits/types, stable retry keys, WXML handlers, and release-origin validation.
- Order and after-sale tests cover whitespace-normalized idempotency keys and duplicate-key races, including assertions that side effects run only for the winning insert.

### 7. Wrong vs Correct

#### Wrong

```java
@GetMapping("/orders/{id}/proofs")
List<OrderProofRow> proofs(@PathVariable long id) {
    return proofMapper.selectByOrderId(id);
}
```

```java
@PostMapping("/wechat/authorize")
ResponseEntity<?> authorizeH5(...) { /* removed OAuth flow */ }
```

#### Correct

```java
@GetMapping("/orders/{id}/proofs")
ApiResponse<List<OrderProofView>> proofs(@PathVariable long id) {
    return ApiResponse.ok(orderProofUseCase.listUser(currentUserId(), id));
}
```

```java
@PostMapping("/wechat/miniprogram/login")
ApiResponse<MiniprogramLoginView> miniprogramLogin(@RequestBody MiniprogramLoginRequest body) {
    LoginResult result = authUseCase.miniprogramLogin(...);
    // establish StpUserKit session, return token + public profile
}
```

This keeps query authorization and version selection in the application layer, preserves read-only HTTP semantics for queries, and makes the miniprogram header token the primary user-session transport.

# Miniprogram Public API Contracts

## Scenario: Authenticated member queries and public catalog projection

### 1. Scope / Trigger

- Trigger: any change to miniprogram login, member profile, catalog/cart/order/address/proof public APIs, invitation lookup, invitation wxacode, or active distribution-rule projection.
- Controllers translate the authenticated member ID into an application use-case call. They must not query MyBatis mappers or reconstruct authorization rules.
- Public rule output is a read-only projection. It must not expose draft, disabled, future, expired, or superseded rule versions.
- There is no Web storefront SPA and no storefront template CMS endpoint.

### 2. Signatures

```java
AuthUseCase.LoginResult miniprogramLogin(MiniprogramLoginCommand command);
MemberProfileUseCase.ProfileView updateWechatProfile(long userId, UpdateWechatProfileCommand command);
MemberProfileUseCase.ProfileView updateNickname(long userId, UpdateNicknameCommand command);
MemberProfileUseCase.ProfileView uploadAvatar(long userId, UploadAvatarCommand command);

CommerceUseCase.OrderView submit(long userId, SubmitOrderCommand command);
CommerceUseCase.OrderDetail order(long userId, long orderId);
OrderProofUseCase.UploadLimits uploadLimits();
List<OrderProofUseCase.ProofView> listUser(long userId, long orderId);

AfterSaleUseCase.View afterSale(long userId, long afterSaleId);
List<AfterSaleProofUseCase.ProofView> listUser(long userId, long afterSaleId);

MembershipUseCase.InvitationView currentInvitation(long userId);
MembershipUseCase.WxacodeView invitationWxacode(long userId);
List<MembershipUseCase.RuleView> activeRules();
```

```http
POST /api/v1/auth/wechat/miniprogram/login
Content-Type: application/json

{
  "code": "wx.login js_code"
}

→ ApiResponse<{
  "token": "Sa-Token user token value",
  "publicId": "member public id",
  "nickname": "display name",
  "newlyRegistered": true
}>

# Atomic public registration; use sponsorClaimSecret instead of inviteCode only
# in the explicit bootstrap-sponsor flow:
POST /api/v1/auth/wechat/miniprogram/register
Content-Type: application/json

{
  "code": "fresh wx.login js_code",
  "inviteCode": "required public invitation credential"
}

# Optional profile editing initiated from the member profile page:
POST /api/v1/membership/avatar
Header: market-shop-user-token: <token>
Content-Type: multipart/form-data
file=<chooseAvatar temporary file>

PUT /api/v1/membership/nickname
Header: market-shop-user-token: <token>
Content-Type: application/json

{
  "nickname": "user-confirmed nickname"
}

→ ApiResponse<{
  "userId": 42,
  "nickname": "会员昵称",
  "avatarUrl": "/api/v1/member-avatars/42",
  "phoneMasked": "138****8000",
  "phoneVerifiedAt": "2026-08-12T12:00:00Z"
}>

GET /api/v1/member-avatars/{userId}
# Stable same-origin sanitized image response; never an object-storage URL/key.

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
GET /api/v1/membership/invitation/wxacode
→ ApiResponse<{
  "contentType": "image/png",
  "imageBase64": "base64 PNG bytes"
}>

GET /api/v1/rules/active

POST /api/v1/orders
{
  "clientRequestId": "stable retry key, max 80",
  "source": "MINIPROGRAM",
  "buyerNote": "optional, max 500 Unicode code points",
  "address": { "...": "persisted delivery snapshot" },
  "items": [{ "skuId": 1, "quantity": 1, "unitPriceFen": 2980 }]
}

GET /api/v1/system/capabilities
→ ApiResponse<{
  "devLoginEnabled": false,
  "wechatLoginEnabled": true,
  "maxProofFiles": 3,
  "maxProofSizeBytes": 8388608
}>

GET /api/v1/system/about
→ ApiResponse<{
  "name": "宏杉生物",
  "onlinePaymentEnabled": false,
  "cashWithdrawalEnabled": false,
  "pointsCashEquivalent": false,
  "rewardDepth": 1
}>
```

```sql
-- Flyway V14
ALTER TABLE trade_order
    ADD COLUMN buyer_note VARCHAR(500) NULL AFTER address_snapshot_json;
```

### 3. Contracts

- Miniprogram login exchanges `code` via code2session (`WECHAT_MP` provider). Mock mode (`market-shop.wechat.mock-enabled=true`) treats `code` as openId and does not call WeChat.
- The code2session adapter reads raw response bytes and parses JSON independently of the upstream `Content-Type`; WeChat responses labelled `text/plain` are valid and do not depend on an incorrect default charset. The response must be one JSON object with no duplicate fields or trailing tokens; `openid` is a non-blank string and, when present, `unionid` is a string rather than `null` or another JSON type. Malformed JSON, invalid field types, and transport/HTTP failures become `WECHAT_CODE_EXCHANGE_FAILED` without exposing the request URI, AppSecret, or upstream response body.
- Login response returns the Sa-Token value in `token`; clients must send it as header `market-shop-user-token` on protected member APIs.
- The normal login DTO accepts exactly `{code}`. Public registration uses the separate `/register` DTO `{code, inviteCode}`; bootstrap sponsor claim substitutes `sponsorClaimSecret` for `inviteCode`, and the credentials are mutually exclusive. Unknown fields, malformed JSON, raw-phone, nickname and avatar fields fail closed as HTTP 400 `REQUEST_BODY_INVALID`.
- Registration calls `wx.login` exactly once after a local non-empty invitation check; invitation validity is decided only by the backend registration request. Code exchange stays outside the local database transaction. The local transaction atomically creates/binds the identity, generated platform profile, immutable superior relation, member/ledger accounts, invitation usage, login timestamp and sponsor audit. Sa-Token creation happens only after the transaction returns successfully; retry obtains a new code rather than replaying it.
- A new account receives the unique platform-generated nickname `宏杉会员-{publicId}` and a null `avatarUrl`. Neither is represented as a verified WeChat nickname/avatar; clients render the nickname initial until the member explicitly chooses an avatar later.
- Registration does not request phone, nickname or avatar data. Existing identity registration is idempotent: it returns `newlyRegistered=false` without changing the immutable superior or overwriting existing profile fields. A concurrent identity binding rolls back the entire losing local transaction and returns `MEMBER_REGISTRATION_CONFLICT`.
- Fresh existing-member login remains code-only and goes directly to the public home tab. Profile editing is optional and reachable only from the member profile page; it preloads `/membership/me`, updates a changed nickname through `PUT /membership/nickname`, and reuses the multipart avatar endpoint without requesting a phone code or calling a legacy profile API.
- The nickname-only use case reads the current profile version after validation. A normalized same-value nickname is a no-op and does not increment `version`; an actual change uses column-level CAS that updates only `nickname` and `version`. It never invokes the WeChat phone exchange and never changes `phone_masked`, `phone_verified_at`, avatar metadata, or the legacy optional `/wechat-profile` compatibility contract; registration never calls it.
- The nickname-only JSON DTO accepts exactly `nickname`; unknown fields such as `phoneCode` or `avatarUrl`, an empty body, and malformed JSON fail closed as HTTP 400 `REQUEST_BODY_INVALID` before the member-profile use case runs.
- A lost nickname CAS returns `MEMBER_PROFILE_CONFLICT` (HTTP 409). After success, the interface synchronizes the current token-session nickname so `/auth/me` and `/membership/me` do not diverge.
- Avatar upload accepts only the native `chooseAvatar` temporary file through multipart. The backend sanitizes the actual image bytes, stores an identity-owned object reference, and persists only the stable same-origin `/api/v1/member-avatars/{userId}` URL; `wxfile://`, arbitrary HTTP URLs and provider keys are never accepted as profile fields.
- `GET /api/v1/membership/me`, admin member list/detail, and profile mutation responses expose the same authoritative `nickname`, `avatarUrl`, `phoneMasked`, and `phoneVerifiedAt` fields. Nullable avatar and legacy profile fields remain valid.
- Native miniprogram checkout sends `source=MINIPROGRAM`. `H5` and `WEB` remain accepted for historical compatibility, but new member UI is not reintroduced for them.
- `buyerNote` is trimmed once, persisted as `trade_order.buyer_note`, and returned as `OrderDetail.buyerNote`; blank input becomes `null`. It is member-authored text and must be rendered as text rather than trusted HTML.
- Each checkout line carries the displayed `unitPriceFen`. The confirm page maps `goods.priceFen` into that field. A negative display price is `PRICE_INVALID` and is rejected before `checkoutContext`.
- Checkout uses two price guards. The application layer compares each command `unitPriceFen` with the matching `checkoutContext` SKU and rejects a mismatch or missing SKU with `PRICE_CHANGED` before `saveSubmitted`. The adapter then locks each SKU `FOR UPDATE` and compares the locked `price_fen` with the checkout-context SKU (not the raw command) before reserve. The check-then-reserve ordering from the submit race fix (commit `abdb812`) is preserved: lock-and-compare and reserve stay in one transaction and never run on a stale unlocked read.
- Buyer receive is blocked when a non-terminal after-sale exists (`trade_after_sale.status NOT IN ('REJECTED', 'CANCELLED')`, including `COMPLETED`). The use case throws `AFTERSALE_BLOCKS_RECEIVE`, `canReceive` is false, auto-receive SQL excludes those orders, and `persistTransition` re-checks after locking the order when the event is `ORDER_COMPLETED`. After-sale `COMPLETED` locks the order, does not mutate `trade_order.status`, and emits only `AFTERSALE_COMPLETED`.
- A second after-sale is refused once any after-sale on that order is `COMPLETED`. `apply` throws `AFTERSALE_ALREADY_COMPLETED` from the completed-count check. `create` locks the order, re-reads eligibility, and throws `AFTERSALE_ALREADY_COMPLETED` or `AFTERSALE_ALREADY_EXISTS` before insert. Inserts are `PENDING_ADMIN_REVIEW`, so `uk_after_sale_completed_order` does not fire on create; it is the last defense if a second row becomes `COMPLETED`. A client-request unique still returns the existing row. A `DuplicateKeyException` on `uk_after_sale_completed_order` still maps to `AFTERSALE_ALREADY_COMPLETED`.
- `RETURN_REFUND` completion restocks `available_quantity` for every order line. `REFUND_ONLY` never restocks.
- Pending-order timeouts are owned by `ORDER_TIMERS` plus `OrderTimeoutJob` (`market-shop.jobs.order-timeout-delay-ms`, lease `order-timeout`, 120s, batch 50). Due rows come from `lockDueOrderTimeout` using `pendingSuperiorTimeoutDays` / `pendingAdminReviewTimeoutDays` / `pendingShipmentTimeoutDays` (1–365; missing or out-of-range keys select nothing). The processor cancels `PENDING_SUPERIOR`, `adminReject`s `PENDING_ADMIN_REVIEW`, and `timeoutClose`s `PENDING_SHIPMENT`, then always goes through `persistTransition` so reserved inventory is released. There is no `OrderStatus.REFUNDED`; money already collected on the last two states is an offline ops refund.
- The public catalog product list excludes any product whose only SKU has `available_quantity = 0`. Cart views return a derived `skuStatus` (`ON_SALE` only when both `catalog_sku.status = 'ON_SALE'` and `catalog_product.status = 'ON_SALE'`, otherwise `OFF_SALE`) so the client can disable checkout for stale items rather than overwriting their quantity.
- `system/capabilities` exposes the same authoritative proof file-count and byte-size configuration used by upload services. Miniprogram pages must not hard-code these limits.
- The public member-client and admin-console brand name is `宏杉生物`. `system/about.name`, miniprogram fallbacks/navigation metadata, and admin identity surfaces must stay aligned. The miniprogram bundles `assets/brand/logo.png`; the admin bundle uses `public/logo.png` for its login, sidebar, and favicon identity slots.
- Brand changes are display contracts only. Java packages, Maven/npm artifacts, `market-shop.*` configuration keys, Sa-Token names, Docker resources, storage buckets, and repository identifiers remain stable technical identifiers.
- WeChat account name and avatar are deployment-console metadata rather than `app.json` fields. Release verification must compare the public account name and avatar against the bundled brand before submission.
- Order detail and proof access are allowed only to the buyer, the immutable direct superior, or an explicitly permitted admin path.
- After-sale detail and proof access are allowed only to the applicant, the order's immutable direct superior, or an explicitly permitted admin path.
- Authorization is checked before proof metadata is returned and again before a short-lived download URL is signed.
- Every proof list, preview, or download action appends an attributable audit record using the real member/admin actor.
- `currentInvitation` is read-only and returns no invitation when one does not exist. Page loading must never create or regenerate a code.
- `invitationWxacode` is a protected member GET. It reads `currentInvitation` only: missing, blank, or non-`ACTIVE` code is `INVITATION_NOT_FOUND` (`当前没有可用的邀请码`) and must not call `ensureInvitation` or WeChat. The PNG is returned as JSON `{contentType, imageBase64}` because `<image src>` cannot send `market-shop-user-token`.
- Official WeChat 小程序码, not a decorative client QR: scene-safe codes (`[0-9A-Za-z!#$&'()*+,/:;=?@._~-]{1,32}`, no space) use `wxa/getwxacodeunlimit` with page `pages/register/register` and scene = the invitation code; otherwise a non-blank path of at most 128 characters (no leading slash) uses `wxa/getwxacode`; otherwise `INVITATION_WXACODE_UNSUPPORTED` (`当前邀请码无法生成小程序码`). Generated `MS` + 10 hex codes are scene-safe.
- Wxacode JSON/transport failures are `WECHAT_WXACODE_FAILED` (`邀请二维码生成失败，请稍后重试`) with no cause and no URI/secret/body leak. Access-token `40001`/`40014`/`42001` may retry once after invalidating the cache, matching the phone path, whether WeChat returned HTTP 200 or a non-2xx JSON body. Non-JSON HTTP errors stay `WECHAT_WXACODE_FAILED` and must not be returned as an image. This is not a `jscode2session` retry. Server logs may record HTTP status and numeric `errcode` only.
- Mock, disabled, or unconfigured WeChat returns a decorative PNG so the invite card still renders locally. Login/register stay fail-closed (`WECHAT_DISABLED` / `WECHAT_NOT_CONFIGURED`). Camera scan of the decorative PNG does not open the miniprogram; native share still lands on register with autofill.
- Wxacode page, scene, path, share URL, and QR must never include `sponsorClaimSecret` or `mode=sponsor`.
- `activeRules` returns only `ACTIVE` rules whose effective window includes the current time. When multiple rows share a `ruleCode`, only the latest effective/versioned row is projected.
- Versioned rule parameters use one typed contract shared by publication and runtime: `SELF_ORDER_TASK` accepts `minimumCompletedOrderAmountFen`, `["UPGRADE"]`, and an active `targetLevel`; `DIRECT_REFERRAL_TASK` accepts `requiredCompletedDirectReferrals`, `minimumReferralOrderAmountFen`, `["UPGRADE"]`, `requiredReferralLevel`, and active `targetLevel`; `DIRECT_REFERRAL_POINTS` accepts `qualificationCount`, `pointsStartOrdinal`, `totalPoints`, `availableAPoints`, `frozenBPoints`, `maxRewardDepth=1`, and `["UPGRADE"]`; `FROZEN_POINTS_RELEASE` accepts `["REPURCHASE"]`, `minimumCompletedOrderAmountFen`, `releaseMode=FIXED`, `releasePointsPerOrder`, and `batchOrder=FIFO`; `INACTIVITY_DOWNGRADE` accepts `inactiveMonths`, active `sourceLevel`, and active `targetLevel`; `ORDER_TIMERS` accepts the eight bounded timer/proof fields. Code/type pairs are exact and unknown fields are rejected for new publication.
- `DIRECT_REFERRAL_POINTS` requires `pointsStartOrdinal > qualificationCount` and `totalPoints = availableAPoints + frozenBPoints` within the JavaScript/Java safe-integer bound. Runtime projections resolve the whole immutable JSON payload through the typed resolver rather than extracting individual JSON paths; one-level reward depth remains server-enforced.
- Existing ACTIVE rows are forward-compatible only through in-memory repair of documented V2 omissions (legacy sales-scene defaults, legacy point totals/depth, and the three pending-timeout defaults). The three-field `DIRECT_REFERRAL_POINTS` shape is persisted-read compatibility only: publication rejects it, and persisted reads expose the canonical encoded fields after derivation. No applied migration or immutable rule row is edited. A malformed or unrepairable active version fails closed with a stable settings/runtime error and is never replaced by a fabricated default.
- Active and snapshotted runtime queries select by canonical `rule_code` and status/time or snapshot relationship, without filtering by expected `rule_type`; the complete row reaches the resolver so a code/type mismatch is an error rather than a missing rule. Unknown ACTIVE codes and known code/type mismatches are excluded from `/api/v1/rules/active` by `RULE_CURRENT_INVALID`. A malformed current `ORDER_TIMERS` version returns `ORDER_TIMER_SETTINGS_INVALID`.
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
| Wxacode GET with no ACTIVE invitation | HTTP 404 `INVITATION_NOT_FOUND`; do not create a code or call WeChat |
| Invitation code is not scene-safe and path exceeds 128 characters | HTTP 400 `INVITATION_WXACODE_UNSUPPORTED` |
| Official wxacode JSON/transport failure after any allowed access-token retry | HTTP 502 `WECHAT_WXACODE_FAILED`; no URI, AppSecret, token, or body leak. Non-2xx JSON `40001`/`40014`/`42001` retries once like HTTP 200 JSON; other non-2xx JSON/HTML stays 502 |
| Wxacode while WeChat is mock, disabled, or unconfigured | Decorative PNG JSON; do not fail closed and do not call WeChat |
| Order/after-sale relationship changed in request data | Ignore client claims; resolve ownership from persisted aggregate data |
| Proof storage signing fails | Stable signing failure; no object key or vendor detail |
| Rule is draft, disabled, future, or expired | Excluded from the public projection |
| Multiple active versions overlap | Return only the latest applicable version per `ruleCode` |
| WeChat disabled and mock off | HTTP 409 `WECHAT_DISABLED` on miniprogram login |
| WeChat code exchange fails | HTTP 502 with domain code `WECHAT_CODE_EXCHANGE_FAILED` |
| WeChat returns JSON as `text/plain` | Parse it exactly like `application/json` |
| WeChat returns an empty/malformed body or an HTTP failure | HTTP 502 `WECHAT_CODE_EXCHANGE_FAILED`; never `INTERNAL_ERROR`, raw cause, URI, AppSecret, code, or response body |
| Login/register body is empty, malformed, contains unknown fields, or submits profile/phone fields | HTTP 400 `REQUEST_BODY_INVALID`; do not invoke the use case |
| Login code fails during registration | Do not start local writes; preserve invitation and obtain a fresh code on retry |
| Local registration write or identity race fails after code exchange | Roll back every local row and invitation increment; return a stable error and require a fresh code |
| Existing identity uses the registration button | Return its existing account with `newlyRegistered=false`; do not mutate superior, invitation usage, nickname or avatar |
| Profile endpoint has no member session | HTTP 401 `NOT_LOGGED_IN`; do not call WeChat or storage |
| Nickname is empty, oversized, or contains forbidden control characters | HTTP 400 stable member-nickname error; do not consume the phone code |
| Nickname-only body is empty, malformed, or contains an unknown field | HTTP 400 `REQUEST_BODY_INVALID`; do not invoke the profile use case |
| Nickname-only request normalizes to the stored nickname | Return the authoritative profile with zero write, zero version increment, and zero phone exchange |
| Nickname-only CAS loses to a concurrent profile/avatar write | HTTP 409 `MEMBER_PROFILE_CONFLICT`; preserve the winning nickname, phone and avatar fields |
| Phone code is empty, expired/invalid, or already consumed | Stable phone-code error; client obtains a fresh authorization code rather than replaying it |
| WeChat access-token/phone exchange transport or payload fails | HTTP 502 `WECHAT_PHONE_EXCHANGE_FAILED`; no code, AppSecret, token, upstream body, or raw phone leaks |
| Avatar is empty, oversized, renamed non-image, or corrupt | Stable 400/413/415 member-avatar error; no profile metadata changes |
| Local/S3 avatar write fails | HTTP 503 stable avatar-storage error; no provider key or partial profile reference is returned |
| Member has no stored avatar | HTTP 404 `MEMBER_AVATAR_NOT_FOUND`; clients render the nickname initial |
| New identity without invite code | Domain code starts with `INVITE_CODE` |
| Order source is not `H5`, `WEB`, or `MINIPROGRAM` | `ORDER_SOURCE_INVALID`; reject before checkout/inventory work |
| `buyerNote` exceeds 500 Unicode code points | `ORDER_BUYER_NOTE_INVALID`; reject before checkout/inventory work |
| `buyerNote` is absent or blank | Persist and return `null` |
| Submitted `unitPriceFen` is negative | HTTP 400 `PRICE_INVALID`; do not call `checkoutContext` or persist |
| Command `unitPriceFen` differs from the `checkoutContext` SKU, or the SKU is missing | HTTP 409 `PRICE_CHANGED`; reject before `saveSubmitted` |
| Locked SKU `price_fen` differs from the checkout-context SKU | HTTP 409 `PRICE_CHANGED`; reject before any inventory reservation and let the client refresh |
| Buyer receive or auto-receive while a non-terminal after-sale exists | HTTP 409 `AFTERSALE_BLOCKS_RECEIVE`; hide `canReceive`; do not emit `ORDER_COMPLETED` |
| Apply after-sale when the order already has a `COMPLETED` after-sale | HTTP 409 `AFTERSALE_ALREADY_COMPLETED`; do not insert another row |
| Concurrent second after-sale create after one is `COMPLETED` | `create` re-reads eligibility under the order lock and returns HTTP 409 `AFTERSALE_ALREADY_COMPLETED` |
| Concurrent second `COMPLETED` after-sale | Unique `uk_after_sale_completed_order` wins; map to `AFTERSALE_ALREADY_COMPLETED` |
| Proof capability configuration is absent or outside backend bounds | Fail closed with the stable rule/settings error; never fabricate a client default |
| Retried order/after-sale mutation with surrounding whitespace in its idempotency key | Resolve the same normalized key and return the original aggregate |
| Concurrent duplicate order/after-sale insert | Return the winning aggregate; do not repeat inventory, notification, or outbox side effects |
| Public about name, client metadata, or admin title uses a legacy/demo brand | Fail the branding contract test; do not ship mixed user-visible identities |
| A required local brand asset is missing, not a PNG, or differs from the approved checksum | Fail the static/build gate before packaging |

### 5. Good/Base/Bad Cases

- Good: miniprogram obtains `code` via `wx.login`, posts login, stores `token`, and calls catalog/cart/orders with the header.
- Good: a new member enters only an invitation, clicks one registration button, receives a unique generated platform nickname and reaches home with a nickname-initial avatar fallback.
- Good: an existing member logs in with `{code}` and reaches home; later, from the explicit profile entry, the member may change nickname/avatar and retry only avatar when the multipart phase fails.
- Good: an order buyer opens the detail page, sees persisted line snapshots and logistics, lists proof metadata, and requests a fresh five-minute preview URL.
- Good: miniprogram checkout submits `MINIPROGRAM` plus an optional `buyerNote` and each line's displayed `unitPriceFen`; detail reads the same normalized note after a Flyway-upgraded restart.
- Good: a buyer cannot confirm receipt, and auto-receive skips the order, while an in-progress or completed after-sale exists; completing that after-sale emits only `AFTERSALE_COMPLETED`.
- Good: a `RETURN_REFUND` completion increments `available_quantity`; a `REFUND_ONLY` completion does not.
- Base: a pending-superior / pending-admin-review / pending-shipment order past its `ORDER_TIMERS` day window is closed by `OrderTimeoutJob` and releases reserved inventory.
- Good: proof pages obtain `maxProofFiles` and `maxProofSizeBytes` from capabilities and resolve application-relative signed URLs against the configured HTTPS API origin.
- Good: navigation, login, profile identity surfaces, admin login/sidebar/title, and `system/about.name` all show `宏杉生物`, while both clients package the approved local PNG.
- Good: the direct superior reviews an after-sale detail while an unrelated member receives 403.
- Good: a member with an ACTIVE invitation opens the invite page, receives `{contentType, imageBase64}`, and scanning the official 小程序码 opens `pages/register/register` with the invite code auto-filled from `inviteCode` or `scene`.
- Base: a member has no invitation; the client displays an explicit empty state and creates one only after a user action. Wxacode is not requested.
- Base: no active distribution rule exists; the client shows a retryable/empty configuration state rather than fabricated thresholds.
- Base: checkout omits `buyerNote`; the database stores `NULL` and detail omits the note UI.
- Bad: create an invitation during a GET/page-mount flow, return every historical rule version, trust a `userId` query parameter, or return a permanent RustFS URL.
- Bad: put `sponsorClaimSecret` or `mode=sponsor` in a QR, wxacode scene/path, or share URL; serve a token-protected PNG as `<image src>`; generate a decorative client QR as the camera-scan path; or retry `jscode2session`.
- Bad: reintroduce Web OAuth authorize/callback or a storefront template CMS.
- Bad: send `source=MINIPROGRAM` while the backend whitelist still accepts only Web sources, hard-code three proof files, or display an application-relative signed URL without adding the API origin.
- Bad: omit `unitPriceFen` on submit, compare only the locked SKU with the command and skip the application-layer display-price check, emit `ORDER_COMPLETED` from after-sale complete, restock `REFUND_ONLY`, add `OrderStatus.REFUNDED`, or close timed-out pending orders without `persistTransition`.
- Bad: rename `market-shop-user-token` or deployment resources as part of a visual rebrand, load a Logo from an external URL, or leave a legacy/demo name on one public surface.

### 6. Tests Required

- Application tests cover code-only miniprogram login plus atomic miniprogram registration: missing/ambiguous credentials, external exchange outside the local transaction, inactive identities, sponsor audit and no writes after invalid credentials.
- Rule projection tests cover inactive, future, expired, overlapping, and latest-version selection.
- Invitation tests prove lookup is read-only and absence does not call a create/regenerate port.
- Invitation wxacode tests prove ACTIVE codes call `createWxaCode` with page `pages/register/register`, scene = the code, and path without a leading slash; missing/REVOKED/blank codes throw `INVITATION_NOT_FOUND` with zero `ensureInvitation` and zero WeChat calls.
- Adapter wxacode tests prove mock/disabled/unconfigured decorative PNG, scene-safe `getwxacodeunlimit`, scene-unsafe path `getwxacode` (matcher must not also match `getwxacodeunlimit`), access-token one-retry on HTTP 200 JSON and on non-2xx JSON, stable `WECHAT_WXACODE_FAILED` with no cause for HTTP 200 JSON / non-2xx JSON / non-2xx HTML, and `INVITATION_WXACODE_UNSUPPORTED`.
- Interface tests prove `GET /invitation/wxacode` requires a member session and returns `WxacodeView`.
- Miniprogram invite-card tests prove brand `宏杉生物`, `data:` URI image, native share path without a leading slash, save-to-album of the PNG bytes, no wxacode/share when invitation is missing, and no `sponsorClaimSecret` in invite/register share surfaces. Register tests prove `inviteCode` wins over `scene`, URI-decoded scene autofill, and sponsor mode ignores both.
- Interface/contract tests verify protected routes require a member session while `/api/v1/rules/active` and catalog/content remain public as designed.
- Login response contract tests require non-empty `token` on miniprogram login.
- Registration adapter/mapper tests cover the full-publicId generated platform nickname, null avatar, existing-identity idempotency, sponsor claim, local transaction annotation and stable duplicate-identity conflict before relation/invitation side effects.
- Profile application/interface tests cover optional member editing, nickname validation, access-token caching, authoritative reads, avatar size/type/sanitization/storage failures, compensation and stable URL delivery.
- Nickname-only application/mapper/interface tests cover Unicode boundary trim, code-point bounds, control characters, same-value no-op, zero phone exchange, phone/avatar preservation, expected-version CAS, strict JSON shape, stable 400/409, current-member attribution and token-session synchronization.
- Consumer tests prove login stays code-only and goes home; registration has only the invitation input and one button, obtains a fresh login code, sends strict credential-only JSON, preserves invitations on failure, prevents duplicate submit, and never persists/replays login codes or profile fields.
- Optional-profile consumer tests prove authoritative pre-read/retry, privacy authorization without phone access, no-change zero writes, nickname-before-avatar ordering, duplicate-submit protection and avatar-only retry after nickname success.
- Admin projection tests require only `avatarUrl`, `nickname`, `phoneMasked`, and `phoneVerifiedAt`, including nullable legacy-member behavior and nickname-initial fallback after image failure.
- Order tests cover all three accepted source values, reject unknown sources before checkout, round-trip `buyerNote` through controller/application/domain/MyBatis/detail/auto-receive, apply V14 on an upgraded schema, reject a negative display price before checkout, reject a command/display mismatch with `PRICE_CHANGED` before `saveSubmitted`, and reject a lock-vs-checkout-context mismatch with `PRICE_CHANGED` before any inventory row is reserved.
- Receive and after-sale tests cover `AFTERSALE_BLOCKS_RECEIVE` on buyer receive, auto-receive, and `ORDER_COMPLETED` persist; `AFTERSALE_ALREADY_COMPLETED` on apply and on create's lock-then-recheck; `AFTERSALE_ALREADY_EXISTS` on a concurrent in-progress create; the completed-order unique mapping; `RETURN_REFUND` restock versus `REFUND_ONLY` no-restock; and `projectCompletedOrder` no-op when a completed after-sale already exists.
- Timeout tests cover `Order.timeoutClose` only from `PENDING_SHIPMENT`, `lockDueOrderTimeout` joining current `ORDER_TIMERS` with the three 1–365 day keys, and `OrderTimeoutProcessor` routing all three due statuses through `persistTransition` so reserved inventory is released.
- Cart tests assert `skuStatus` is `ON_SALE` only when both SKU and product are on sale, `OFF_SALE` otherwise, and that the catalog list excludes products whose sole SKU has zero available inventory.
- Capability tests prove the public response reads `maxProofFiles` and `maxProofSizeBytes` from the same application port used by uploads.
- Miniprogram consumer tests cover request path/method/body, Header token transport, 401 cleanup, 409 refresh metadata, relative signed/rich-text media URLs, dynamic proof limits/types, stable retry keys, WXML handlers, and release-origin validation.
- Branding tests assert `宏杉生物` in miniprogram navigation/project metadata, admin login/sidebar/document metadata, and `system/about`; they also require the referenced bundled files to have a valid PNG signature, match the approved SHA-256, and reject legacy public names.
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

```java
@PostMapping("/wechat/miniprogram/register")
ApiResponse<MiniprogramLoginView> miniprogramRegister(
        @RequestBody MiniprogramRegistrationRequest body) {
    // exchange one-time credentials before the local transaction; establish
    // the Sa-Token session only after the transaction commits
}
```

```text
Wrong: rename market-shop-user-token and market-shop.* while changing the UI name.
Correct: change only public brand text/assets; preserve stable technical identifiers.
```

This keeps query authorization and version selection in the application layer, preserves read-only HTTP semantics for queries, and makes the miniprogram header token the primary user-session transport.

# Miniprogram Public API Contracts

## Scenario: Authenticated member queries and public catalog projection

### 1. Scope / Trigger

- Trigger: any change to miniprogram login, member profile, catalog/cart/order/address/proof public APIs, invitation lookup, or active distribution-rule projection.
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

# Profile completion after a newly registered member token exists:
PUT /api/v1/membership/wechat-profile
Header: market-shop-user-token: <token>
Content-Type: application/json

{
  "nickname": "user-selected WeChat nickname",
  "phoneCode": "one-time getPhoneNumber dynamic code"
}

POST /api/v1/membership/avatar
Header: market-shop-user-token: <token>
Content-Type: multipart/form-data
file=<chooseAvatar temporary file>

# Optional confirmation after an existing member obtains a fresh login token:
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
- The normal login request remains code-only. Invitation/sponsor credentials are consumed only by account registration; nickname, phone and avatar completion happens afterward with the newly issued member token and must never replay the registration credential.
- `phoneCode` is the one-time dynamic code from native `getPhoneNumber`, not a login code or a raw phone number. Only the backend calls WeChat to consume it. The unmasked value exists only at that adapter/application boundary; persistence, session data, admin/member projections, URLs and logs contain only backend-generated `phoneMasked` plus `phoneVerifiedAt`.
- The miniprogram registration page completes profile JSON before avatar upload. A failed phase retries only that unfinished phase: a consumed phone code is cleared, a completed profile is not posted again, and an avatar retry never replays phone/invite/sponsor data.
- Nickname boundary whitespace is trimmed with Unicode-aware rules, length is validated by Unicode code points, and the value is stored as user-selected profile text. It is not represented as a verified real name.
- A fresh existing-member login remains code-only and then opens the independent profile-confirmation page. That page preloads `/membership/me`, may skip with zero writes, updates a changed nickname through `PUT /membership/nickname`, and reuses the existing multipart avatar endpoint; it never requests a phone code or calls a legacy profile API.
- The nickname-only use case reads the current profile version after validation. A normalized same-value nickname is a no-op and does not increment `version`; an actual change uses column-level CAS that updates only `nickname` and `version`. It never invokes the WeChat phone exchange and never changes `phone_masked`, `phone_verified_at`, avatar metadata, or the registration-only `/wechat-profile` contract.
- The nickname-only JSON DTO accepts exactly `nickname`; unknown fields such as `phoneCode` or `avatarUrl`, an empty body, and malformed JSON fail closed as HTTP 400 `REQUEST_BODY_INVALID` before the member-profile use case runs.
- A lost nickname CAS returns `MEMBER_PROFILE_CONFLICT` (HTTP 409). After success, the interface synchronizes the current token-session nickname so `/auth/me` and `/membership/me` do not diverge.
- Avatar upload accepts only the native `chooseAvatar` temporary file through multipart. The backend sanitizes the actual image bytes, stores an identity-owned object reference, and persists only the stable same-origin `/api/v1/member-avatars/{userId}` URL; `wxfile://`, arbitrary HTTP URLs and provider keys are never accepted as profile fields.
- `GET /api/v1/membership/me`, admin member list/detail, and both profile mutation responses expose the same authoritative `nickname`, `avatarUrl`, `phoneMasked`, and `phoneVerifiedAt` fields. Nullable fields remain valid for upgraded members who have not completed the new profile flow.
- Native miniprogram checkout sends `source=MINIPROGRAM`. `H5` and `WEB` remain accepted for historical compatibility, but new member UI is not reintroduced for them.
- `buyerNote` is trimmed once, persisted as `trade_order.buyer_note`, and returned as `OrderDetail.buyerNote`; blank input becomes `null`. It is member-authored text and must be rendered as text rather than trusted HTML.
- `system/capabilities` exposes the same authoritative proof file-count and byte-size configuration used by upload services. Miniprogram pages must not hard-code these limits.
- The public member-client and admin-console brand name is `宏杉生物`. `system/about.name`, miniprogram fallbacks/navigation metadata, and admin identity surfaces must stay aligned. The miniprogram bundles `assets/brand/logo.png`; the admin bundle uses `public/logo.png` for its login, sidebar, and favicon identity slots.
- Brand changes are display contracts only. Java packages, Maven/npm artifacts, `market-shop.*` configuration keys, Sa-Token names, Docker resources, storage buckets, and repository identifiers remain stable technical identifiers.
- WeChat account name and avatar are deployment-console metadata rather than `app.json` fields. Release verification must compare the public account name and avatar against the bundled brand before submission.
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
| WeChat code exchange fails | HTTP 502 with domain code `WECHAT_CODE_EXCHANGE_FAILED` |
| WeChat returns JSON as `text/plain` | Parse it exactly like `application/json` |
| WeChat returns an empty/malformed body or an HTTP failure | HTTP 502 `WECHAT_CODE_EXCHANGE_FAILED`; never `INTERNAL_ERROR`, raw cause, URI, AppSecret, code, or response body |
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
| Proof capability configuration is absent or outside backend bounds | Fail closed with the stable rule/settings error; never fabricate a client default |
| Retried order/after-sale mutation with surrounding whitespace in its idempotency key | Resolve the same normalized key and return the original aggregate |
| Concurrent duplicate order/after-sale insert | Return the winning aggregate; do not repeat inventory, notification, or outbox side effects |
| Public about name, client metadata, or admin title uses a legacy/demo brand | Fail the branding contract test; do not ship mixed user-visible identities |
| A required local brand asset is missing, not a PNG, or differs from the approved checksum | Fail the static/build gate before packaging |

### 5. Good/Base/Bad Cases

- Good: miniprogram obtains `code` via `wx.login`, posts login, stores `token`, and calls catalog/cart/orders with the header.
- Good: after account registration the same page uses `input type=nickname`, `getPhoneNumber`, and `chooseAvatar`; it saves masked profile metadata, uploads sanitized bytes, then renders the returned stable avatar URL.
- Good: an existing member logs in with `{code}`, reviews the authoritative profile, changes nickname and avatar, and retries only the avatar when the later multipart phase fails.
- Good: profile save succeeds and avatar upload fails; retry calls only the avatar endpoint and never reuses the phone code or invitation.
- Good: an order buyer opens the detail page, sees persisted line snapshots and logistics, lists proof metadata, and requests a fresh five-minute preview URL.
- Good: miniprogram checkout submits `MINIPROGRAM` plus an optional `buyerNote`; detail reads the same normalized note after a Flyway-upgraded restart.
- Good: proof pages obtain `maxProofFiles` and `maxProofSizeBytes` from capabilities and resolve application-relative signed URLs against the configured HTTPS API origin.
- Good: navigation, login, profile/about, admin login/sidebar/title, and `system/about.name` all show `宏杉生物`, while both clients package the approved local PNG.
- Good: the direct superior reviews an after-sale detail while an unrelated member receives 403.
- Base: a member has no invitation; the client displays an explicit empty state and creates one only after a user action.
- Base: no active distribution rule exists; the client shows a retryable/empty configuration state rather than fabricated thresholds.
- Base: checkout omits `buyerNote`; the database stores `NULL` and detail omits the note UI.
- Bad: create an invitation during a GET/page-mount flow, return every historical rule version, trust a `userId` query parameter, or return a permanent RustFS URL.
- Bad: reintroduce Web OAuth authorize/callback or a storefront template CMS.
- Bad: send `source=MINIPROGRAM` while the backend whitelist still accepts only Web sources, hard-code three proof files, or display an application-relative signed URL without adding the API origin.
- Bad: rename `market-shop-user-token` or deployment resources as part of a visual rebrand, load a Logo from an external URL, or leave a legacy/demo name on one public surface.

### 6. Tests Required

- Application tests cover miniprogram login (new user + invite, missing invite, existing identity), buyer/applicant access, direct-superior access, unrelated-member denial, absent resources, and proof-list audit actors.
- Rule projection tests cover inactive, future, expired, overlapping, and latest-version selection.
- Invitation tests prove lookup is read-only and absence does not call a create/regenerate port.
- Interface/contract tests verify protected routes require a member session while `/api/v1/rules/active` and catalog/content remain public as designed.
- Login response contract tests require non-empty `token` on miniprogram login.
- Profile application/interface tests cover member authentication, nickname validation, phone masking, expired/invalid/upstream phone errors, access-token caching, authoritative reads, avatar size/type/sanitization/storage failures, compensation and stable URL delivery.
- Nickname-only application/mapper/interface tests cover Unicode boundary trim, code-point bounds, control characters, same-value no-op, zero phone exchange, phone/avatar preservation, expected-version CAS, strict JSON shape, stable 400/409, current-member attribution and token-session synchronization.
- Consumer tests prove login stays code-only; privacy rejection is visible; native nickname/avatar/phone events are used; staged retry never replays a phone or registration credential; no raw phone or temporary avatar path enters storage, URLs or JSON payloads.
- Login-profile consumer tests prove fresh-login routing, authoritative pre-read/retry, privacy authorization without phone access, skip/no-change zero writes, nickname-before-avatar ordering, duplicate-submit protection and avatar-only retry after nickname success.
- Admin projection tests require only `avatarUrl`, `nickname`, `phoneMasked`, and `phoneVerifiedAt`, including nullable legacy-member behavior and nickname-initial fallback after image failure.
- Order tests cover all three accepted source values, reject unknown sources before checkout, round-trip `buyerNote` through controller/application/domain/MyBatis/detail/auto-receive, and apply V14 on an upgraded schema.
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

```text
Wrong: rename market-shop-user-token and market-shop.* while changing the UI name.
Correct: change only public brand text/assets; preserve stable technical identifiers.
```

This keeps query authorization and version selection in the application layer, preserves read-only HTTP semantics for queries, and makes the miniprogram header token the primary user-session transport.

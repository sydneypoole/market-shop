# Admin Console Cross-Layer Contract

## Scenario: Complete operational pages

### 1. Scope / Trigger

- Trigger: any change to admin routes, permissions, order/aftersale detail, catalog media or SKU management, rule forms, account/role management, audit search/export, or global settings.
- The admin console is an operational client over authoritative backend use cases. A visible button never replaces backend permission or state validation.

### 2. Signatures

| Route | Required permission | Primary APIs |
|---|---|---|
| `/orders` | `order:read` | `GET /api/v1/admin/orders/search`, `GET /orders/{id}`, `GET /orders/{id}/notes`, `GET /orders/{id}/proofs` |
| `/after-sales` | `aftersale:review` | `GET /api/v1/admin/after-sales`, `GET /after-sales/{id}/proofs`, `GET /settings` |
| `/catalog` | `catalog:read` | catalog categories/products, SKU inventory adjustments, `/catalog/assets` |
| `/rules` | `rule:publish` | `GET/POST /api/v1/admin/rules`, `POST /rules/validate` |
| `/accounts` | `admin:account:manage` | accounts, roles, permissions, unlock/reset/assignment |
| `/audit` | `audit:read` | `GET /api/v1/admin/audit`, `GET /audit/export` |
| `/settings` | `system:setting:manage` | `GET/PUT /api/v1/admin/settings` |
| `/members` | `member:read` | `GET /api/v1/admin/members`, `GET /members/{id}`; writes (`member:write`): `PUT /members/{id}/status`, `PUT /members/{id}/level`, `POST /members/{id}/recompute` |

Shared client signatures:

```ts
async function adminApi<T>(path: string, init?: RequestInit): Promise<T>
function can(permission?: string): boolean
function queryString(values: Record<string, string | number | undefined | null>): string
```

Persistent additions:

```sql
operation_setting(setting_key PK, setting_value, updated_by_admin_id, version, created_at, updated_at)
catalog_media_asset(id PK, object_key UK, sha256, original_filename, media_type,
                    size_bytes, uploaded_by_admin_id, status, created_at, deleted_at)
```

### 3. Contracts

- Route metadata and sidebar visibility use the same permission code; the backend repeats authorization.
- List pages use backend totals when the endpoint provides pagination. Mutations reload authoritative server state.
- Order detail loads snapshot, items, shipment, timeline, notes, and proof metadata together. Proof bytes are opened only through a newly issued short-lived URL.
- Aftersale approval obtains return receiver, phone, and address from `/admin/settings`; addresses are never hard-coded in the view.
- Product/content images use `FormData` to `/admin/catalog/assets`. Catalog writers and content writers may manage these shared assets. The stored URL is the stable public endpoint `/api/v1/catalog/assets/{id}`; payment and aftersale proofs remain private.
- Member list/detail projections expose only the member-owned stable `avatarUrl`, registration `nickname`, backend-produced `phoneMasked`, and optional `phoneVerifiedAt`. They never expose or reconstruct a raw phone number, WeChat phone code, temporary avatar path, storage key, or vendor URL.
- Member avatar rendering uses the shared `MemberAvatar` component in both list and detail views. It lazy-loads the stable same-origin image and falls back to the first nickname character after an empty or failed image; the corporate Logo is never used as a member-avatar fallback.
- Member write actions (`member:write`) use `BusinessActionDialog` with a required reason and `requestId`. Status uses a `memberStatusOptions` dropdown against `PUT /members/{userId}/status`. Level uses a `memberLevelOptions` dropdown against `PUT /members/{userId}/level` (`{ levelCode, reason, requestId }`). Same-level retry is 200 no-op. Level change does not invalidate member sessions, mutate points, or rewrite the immutable superior. Later recompute or inactivity downgrade may overwrite a manual assign.
- Product descriptions and content bodies use the shared `RichTextEditor` backed by `@vueup/vue-quill` in HTML mode. Its curated toolbar excludes inline styling. The image action uploads only through the managed asset API and inserts the returned stable `/api/v1/catalog/assets/{id}` URL at the saved cursor position; it never persists base64, blob, local-storage, or private-object URLs. While an image upload is pending, preview, close, and parent-form submission remain locked.
- A managed rich-text image may persist only the standard `width="NN%"` attribute, bounded to an integer from 10 through 100. The editor exposes 25/50/75/100 percent presets plus a bounded custom value for uploaded and existing images, keeps `height` automatic, and sends every change through Quill history so undo/redo works. The server allow-list preserves the bounded `width` only on same-origin `/api/v1/catalog/assets/{id}` images and removes external/data/blob images, event handlers, inline styles, scripts, and invalid sizes before persistence.
- The public console identity is `宏杉生物`. Login, sidebar, document title/description, and favicon use that name and the bundled `frontend/admin/public/logo.png`; do not load branding from a runtime object-storage URL.
- A visual rebrand must not rename the `@market-shop/admin` package, `/admin/` route base, `market-shop-admin-token`, API paths, Docker resources, or other compatibility identifiers.
- HTML product/content previews use `<iframe sandbox="">`; never bind stored HTML through `v-html` in the admin shell.
- Role create/update/delete, account unlock/reset/status/role assignment, and account linking require current-password reauthentication plus a non-blank reason. The account page loads role APIs only with `admin:role:manage`. Built-in roles are immutable; an assigned custom role cannot be deleted.
- Administrator status changes, password resets, role assignments, and effective custom-role permission changes invalidate all affected administrator sessions. The next API request must receive 401 instead of continuing with cached permissions.
- Rules use typed business forms by default and preserve an advanced JSON mode. Hard safety boundaries (no online payment/cash withdrawal, reward depth one) are not configurable.
- `GET/PUT /admin/settings` owns return details and the low-inventory threshold. Timer/proof settings remain immutable `ORDER_TIMERS` rule versions and are loaded only when the session also has `rule:publish`.
- `ORDER_TIMERS` has one server-owned publisher: the settings workbench uses `/admin/settings/order-timers` and `/admin/settings/order-timers/validate`; the generic `/admin/rules` publish/validate endpoints reject this rule type even when the request uses padded or mixed identifiers.
- The settings timer parser is fail-closed and mirrors the backend contract: auto-receive, after-sale, pending-superior, pending-admin-review, and pending-shipment windows are 1–365 days, proof retention is 1–3650 days, proof count is 1–20, and each proof is 1024–20 971 520 bytes. A five-key legacy payload is invalid. JSON arrays, malformed JSON, non-finite/fractional numbers, missing fields, and out-of-range values keep the editor locked. The settings form publishes all eight numeric keys together so a new version cannot wipe the three pending-timeout fields.
- `DIRECT_REFERRAL_POINTS` also rejects an A/B point total outside JavaScript's safe-integer range, matching the backend's overflow-safe `Math.addExact` check instead of allowing a rounded payload through the editor.
- The admin rule codec shares the backend code/type registry and canonical field shapes. It rejects unknown fields, unsupported sales scenes/modes, `pointsStartOrdinal <= qualificationCount`, and `maxRewardDepth !== 1`; it rejects the three-field legacy direct-points shape for publication and only hydrates it through persisted-read repair, emitting canonical JSON. Numeric tokens with fractions/exponents and duplicate object keys fail before parsing, matching Jackson strict duplicate/integer handling. Level names are structure-checked only; active/inactive/custom-level authority remains backend-owned because no static frontend list is authoritative.

### 4. Validation & Error Matrix

| Condition | Required UI/API result |
|---|---|
| Session missing | Route to `/login`; clear only admin session state |
| Permission absent | Hide route/action and reject API with 403 |
| Invalid/failed JSON envelope | Show a safe retryable error; preserve current business state |
| Proof access requested | Fetch a fresh signed URL; never cache it |
| Catalog file missing/empty, over 10 MB, or not a valid image | Reject with stable 400/413/415 catalog asset error |
| Return address or change reason blank | Reject settings save |
| Built-in role edit/delete | `ADMIN_BUILTIN_ROLE_IMMUTABLE` |
| Custom role still assigned | `ADMIN_ROLE_IN_USE` |
| Rule form violates bounds | Validate before publishing; do not create a version |
| Existing `ORDER_TIMERS` JSON is malformed or outside bounds | Keep the settings editor and both timer endpoints locked until the authoritative version is repaired |
| Generic rules endpoint receives `ORDER_TIMERS`/`ORDER_TIMER` | Return `ORDER_TIMER_SETTINGS_ONLY`; do not create or validate a generic rule version |
| HTML preview requested | Render only inside a sandboxed iframe |
| Admin title/sidebar/login contains a legacy or demo brand | Fail the admin branding source contract |
| Bundled Logo is missing, invalid, or differs from the approved checksum | Fail tests/build before container packaging |
| Member avatar is empty or fails to load | Render the nickname initial with an accessible label; keep the member row/detail usable |
| Member has not authorized a phone number | Render `未授权手机号`; never infer or request a raw number from the admin client |
| Unknown or inactive membership level | Keep the dialog open; API returns `MEMBER_LEVEL_INVALID` (400) |
| Blank reason or requestId on member write | Keep the dialog open; API returns `MEMBER_COMMAND_INVALID` (400) |
| Member missing on write | Keep the dialog open; API returns `MEMBER_NOT_FOUND` (404) |
| Same membership level submitted again | 200 no-op; toast still reports success; no session invalidation |

### 5. Good/Base/Bad Cases

- Good: an order reviewer filters a server-paginated list, opens one detail, views an authorized short-lived proof, adds a note, and reloads the order.
- Good: a catalog operator sanitizes an image into the configured shared storage, selects the stable public URL, adds a second SKU, and later inspects inventory adjustment history.
- Base: a cash order or aftersale has no proof; the detail page renders a clear empty state.
- Base: a brand asset changes; every actual Logo slot and the favicon reference the new bundled file while technical identifiers remain unchanged.
- Bad: hard-coded demo return address, permanent proof URL, raw `v-html`, client-only permission checks, role deletion with no reauthentication, or replacing a published rule row.
- Bad: mix old and new names across login/sidebar/title, use product/proof storage as a Logo host, or replace business icons and empty states with the corporate Logo.

### 6. Tests Required

- `pnpm test:web`: route permission, page/API workflow, shared rich-text editor, sandbox, and multipart source contracts.
- The admin branding test reads `index.html`, `App.vue`, `LoginView.vue`, `styles.css`, and `public/logo.png`; it asserts the public name, every actual Logo slot, favicon, PNG signature, approved SHA-256, and absence of legacy names.
- Member page tests require `avatarUrl`, `phoneMasked`, and `phoneVerifiedAt` in the typed projection, verify both list/detail use the shared accessible avatar fallback, and reject a brand-Logo fallback or raw-phone field.
- `pnpm typecheck:web` and `pnpm build:web`: admin Vue application only.
- `mvn -f backend/pom.xml test`: settings validation/audit, catalog asset compensation/audit, proof authorization, and existing domain/application suites.
- Empty MySQL integration: Flyway V1–V15 applies; V8 historically created template tables, V13 drops them and removes `storefront:template:manage`, and V15 adds nullable member-profile metadata while permitting `WECHAT_MP` sponsor claims.
- Runtime smoke: admin login/forced password change, settings read/write, role create/edit/delete, configured asset storage upload/public read/delete, miniprogram login probe, and audit rows for every mutation.

### 7. Wrong vs Correct

#### Wrong

```vue
<div v-html="product.descriptionHtml" />
```

```ts
const returnAddress = "演示退货地址"
if (session.roles.includes("SUPER_ADMIN")) showSettings()
```

#### Correct

```vue
<iframe sandbox="" :srcdoc="product.descriptionHtml || '<p>暂无详情</p>'" />
```

```ts
const settings = await adminApi<Settings>('/settings')
if (can('system:setting:manage')) showSettings()
```

```vue
<!-- Wrong: external or business-storage Logo URL -->
<img src="https://storage.example.test/private/logo.png" />

<!-- Correct: bundled, base-path-aware public asset -->
<img src="/logo.png" alt="宏杉生物 Logo" />
```

This keeps content isolated, configuration server-owned, and UI permissions aligned with backend RBAC.

## Scenario: Operator workbench interaction and lifecycle

### 1. Scope / Trigger

- Trigger: changing the admin shell, route metadata, list filters, detail views, dialogs/drawers, exports, sensitive actions, draft publication, or any mutation loading state.
- The console is an operator workbench. Shared components own interaction mechanics; route views retain domain requests, status policy, and server refresh orchestration.

### 2. Signatures

```ts
type AdminNavigationItem = Readonly<{
  name: string
  path: string
  label: string
  title: string
  description: string
  group: 'workbench' | 'fulfillment' | 'merchandising' | 'growth' | 'governance'
  permission: string
  component: Component
}>

type LoadState = 'unloaded' | 'loading' | 'loaded' | 'error'

type AdminApiErrorKind = 'network' | 'invalid-response' | 'http' | 'business'

class AdminApiError extends Error {
  readonly status: number
  readonly code: string
  readonly kind: AdminApiErrorKind
}
```

Shared overlay contract:

```ts
type OverlayProps = {
  modelValue: boolean
  title: string
  description?: string
  submitting?: boolean
  dirty?: boolean
  persistent?: boolean
}
```

### 3. Contracts

- `admin-navigation.ts` is the single source for protected routes, grouped sidebar entries, breadcrumbs, titles, permissions, and the first allowed landing page. Do not repeat route order in `App.vue` or `session.ts`.
- List pages keep `draftFilters` separate from `appliedFilters`. Only an explicit query copies draft to applied, writes the URL query, resets the page, and reloads. Export uses the applied snapshot, never unsubmitted input.
- Page load, detail load, and action submission are separate states. Switching a detail object clears dependent proof/history data immediately; an incrementing request sequence rejects late responses for the previous object.
- `BaseDialog` and `DetailDrawer` provide Teleport, dialog semantics, initial focus, Tab containment, Escape handling, focus restoration, body scroll locking, dirty-close blocking, and submission-close blocking. Domain views do not recreate overlay mechanics.
- Browser `prompt`, `confirm`, and `alert` are forbidden. A business action shows target, current/next state, impact, reason, optional password reauthentication, inline error, and submitting state.
- Passwords and temporary passwords use masked inputs and are cleared on close, success, and target change. They are never persisted or placed in toasts/logs.
- Product data save and inventory adjustment are separate mutations. An inventory request keeps one stable `requestId` throughout retries of the same operator action.
- Published content must be explicitly taken offline before it can be edited as a draft. A normal draft save cannot silently change a published row to `DRAFT`.
- `ORDER_TIMERS` is edited only from system settings. The rules page may display or compare its versions but does not offer a second publisher.
- A versioned setting/rule editor unlocks only after the current effective version loads successfully. After publication, it reloads the authoritative version; failed readback locks another submit until a successful refresh.
- Mutations reload authoritative server state. Partial batch success removes only successful rows and preserves failed rows for correction and retry.
- Admin CSV/blob downloads use the shared authenticated download client so 401/403 and malformed responses follow the same error contract as JSON APIs.
- At narrow widths, filters become a single-column form, overlays become full-screen, and data rows expose field labels as readable cards while retaining every core action.
- The visual system is a professional high-density operator workbench: shared semantic tokens own the cold-neutral surfaces and single plum interaction accent, system sans-serif typography, focus rings, disabled/error/loading states, and reduced-motion behavior. Route views do not introduce a second component or color system.
- `AdminIcon` backed by `@phosphor-icons/vue` is the only interface icon family. Navigation, status feedback, dialogs, and directional actions never fall back to text arrows, decorative letter badges, emoji, or per-view inline SVG glyphs.
- Missing user-visible values use explicit Simplified-Chinese labels such as `未记录`, `未填写`, or a domain-specific empty state. A dash glyph is not an accessible substitute for business meaning.

### 4. Validation & Error Matrix

| Condition | Required UI result |
|---|---|
| Dialog is initially mounted open | Lock body scroll and move focus inside immediately |
| Dirty editor requests close/route leave | Keep the editor open and ask whether to discard local changes |
| Mutation is submitting | Disable repeated submit and prevent overlay dismissal |
| API returns 401 | Clear only admin session, preserve a safe same-origin redirect, then show login |
| API returns 403 | Preserve session and show an action-local permission error |
| API returns 409 | Keep inputs, reload authoritative state, and explain the conflict |
| Detail B opens while detail A request is pending | Clear A attachments and ignore A's late response |
| Current settings/rule version fails to load or parse | Lock editing/publication; never expose submit-ready defaults |
| Publish succeeds but authoritative readback fails | Mark result as pending verification and keep publication locked |
| Operator tries to edit published content | Require explicit offline confirmation first |
| Batch action partially fails | Remove successes only; keep failures editable and retryable |

### 5. Good/Base/Bad Cases

- Good: an order deep link restores applied filters, opens one drawer, reviews its proofs, submits one valid action, and refreshes both drawer and list.
- Good: a settings operator sees the current `ORDER_TIMERS` version and field diff before publication; the new version number appears only after server readback.
- Base: a read-only account can open its permitted page but sees no unsupported mutation controls.
- Bad: an old proof appears under a newly selected order, an unsubmitted filter changes export scope, a published content edit silently unpublishes it, or a password remains in reactive form state after closing.

### 6. Tests Required

- Source/contract tests reject browser-native dialogs, repeated navigation registries, legacy modal shells, direct enum display, and raw admin export fetches.
- Component tests cover initial-open focus, Tab containment, Escape, dirty/submitting close protection, focus restoration, and secret clearing on close/target change.
- Workflow tests cover draft/applied URL filters, export snapshots, stale detail response rejection, partial batch retry, explicit content offline-before-edit, single `ORDER_TIMERS` publisher, and publish readback locking.
- Responsive verification at 1440, 1024, 768, and 390 pixels ensures all filters, data labels, drawers, and row actions remain reachable without hover.
- `frontend/admin/tests/admin-design-system.test.mjs` locks the shared plum token system, Phosphor icon boundary, focus/reduced-motion behavior, mobile login containment, Chinese missing-value copy, and absence of legacy serif/text-glyph styling.
- Run `pnpm test:web`, `pnpm typecheck:web`, `pnpm build:web`, and `git diff --check`.

### 7. Wrong vs Correct

#### Wrong

```ts
const reason = prompt('修改原因') || ''
const result = await api(`/resource?${queryString(draftFilters)}`)
```

```ts
detail.value = row
proofs.value = await adminApi(`/orders/${row.id}/proofs`)
```

#### Correct

```ts
Object.assign(appliedFilters, draftFilters)
await router.push({ query: appliedFilters })
const result = await adminApi(`/resource?${queryString(appliedFilters)}`)
```

```ts
const request = ++detailRequestSequence
proofs.value = []
const next = await adminApi(`/orders/${row.id}/proofs`)
if (request === detailRequestSequence && detail.value?.id === row.id) proofs.value = next
```

## Scenario: Simplified-Chinese enum presentation

### 1. Scope / Trigger

- Trigger: adding or changing an admin list, detail, filter, form, status, role, permission, audit action, or other backend-owned enum.
- The admin console is Simplified Chinese only. API values, database enums, permission codes, and immutable business identifiers remain unchanged.

### 2. Signatures

```ts
type SelectOption = Readonly<{ value: string; label: string }>

function labelOf(
  labels: Readonly<Record<string, string>>,
  value: string | null | undefined,
  unknownLabel: string
): string

const orderStatusOptions: readonly SelectOption[]
const memberLevelOptions: readonly SelectOption[]
```

Select controls bind `option.value` to the request model and render `option.label` to the operator.

### 3. Contracts

- User-visible headings, buttons, empty states, validation messages, filters, status tags, timeline entries, and decorative copy use Simplified Chinese.
- Backend enums are localized through `frontend/admin/src/localization.ts`; views must not render `row.status`, `row.type`, or equivalent enum fields directly.
- Known values use domain-specific dictionaries because `ACTIVE` can mean “正常”, “启用”, or “生效中” depending on context.
- Unknown values return a contextual Chinese label such as “未知订单状态”; they never become blank and never enable an unsupported mutation.
- Select labels are Chinese while submitted values remain the original English enum. Rule codes, SKU codes, request IDs, and advanced raw structured data stay source-identical where operationally necessary.
- Built-in roles and permissions use Chinese names. A technical code may appear as secondary text only where operators need it to configure or audit access.

### 4. Validation & Error Matrix

| Condition | Required UI result |
|---|---|
| Known enum | Render the domain-specific Chinese label |
| Unknown enum | Render a contextual Chinese fallback and expose no inferred action |
| Enum filter submitted | Send the original backend value, never the Chinese label |
| Status prompt receives an unknown label | Show a Chinese validation error and do not call the API |
| Advanced rule parameters opened | Preserve the original structured payload for editing |
| Role or permission code unknown | Show “自定义角色” or “其他权限”; keep the value unchanged |

### 5. Good/Base/Bad Cases

- Good: an order filter displays “待后台审核” while submitting `PENDING_ADMIN_REVIEW`.
- Good: a member detail displays “直属推荐积分奖励” while the ledger still stores `DIRECT_REFERRAL_AWARD`.
- Base: a new backend status appears before the frontend dictionary is updated; the UI displays “未知状态” and offers no invalid transition.
- Bad: `<option>ACTIVE</option>`, `{{ row.status }}`, translating the request payload to `已完成`, or scattering conflicting labels across views.

### 6. Tests Required

- `pnpm --filter @market-shop/admin test` asserts dictionary coverage, value/label separation, and absence of direct enum interpolation.
- `pnpm --filter @market-shop/admin typecheck` verifies readonly option and dictionary types.
- `pnpm --filter @market-shop/admin build` verifies all Vue templates compile with the localization helpers.
- Source regression checks reject the known English decorative headings and raw enum option labels.

### 7. Wrong vs Correct

#### Wrong

```vue
<select v-model="filters.status">
  <option>ACTIVE</option>
</select>
<span>{{ row.status }}</span>
```

#### Correct

```vue
<select v-model="filters.status">
  <option v-for="option in memberStatusOptions" :key="option.value" :value="option.value">
    {{ option.label }}
  </option>
</select>
<span>{{ memberStatusLabel(row.status) }}</span>
```

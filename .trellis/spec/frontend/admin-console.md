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
- Product descriptions and content bodies use the shared `RichTextEditor` backed by `@vueup/vue-quill` in HTML mode. Its curated toolbar excludes inline styling and embedded uploads; images continue to use the managed asset library.
- HTML product/content previews use `<iframe sandbox="">`; never bind stored HTML through `v-html` in the admin shell.
- Role create/update/delete, account unlock/reset/status/role assignment, and account linking require current-password reauthentication plus a non-blank reason. The account page loads role APIs only with `admin:role:manage`. Built-in roles are immutable; an assigned custom role cannot be deleted.
- Rules use typed business forms by default and preserve an advanced JSON mode. Hard safety boundaries (no online payment/cash withdrawal, reward depth one) are not configurable.
- `GET/PUT /admin/settings` owns return details and the low-inventory threshold. Timer/proof settings remain immutable `ORDER_TIMERS` rule versions and are loaded only when the session also has `rule:publish`.

### 4. Validation & Error Matrix

| Condition | Required UI/API result |
|---|---|
| Session missing | Route to `/login`; clear only admin session state |
| Permission absent | Hide route/action and reject API with 403 |
| Invalid/failed JSON envelope | Show a safe retryable error; preserve current business state |
| Proof access requested | Fetch a fresh signed URL; never cache it |
| Catalog file empty, non-image, or over 10 MB | Reject with stable catalog asset error |
| Return address or change reason blank | Reject settings save |
| Built-in role edit/delete | `ADMIN_BUILTIN_ROLE_IMMUTABLE` |
| Custom role still assigned | `ADMIN_ROLE_IN_USE` |
| Rule form violates bounds | Validate before publishing; do not create a version |
| HTML preview requested | Render only inside a sandboxed iframe |

### 5. Good/Base/Bad Cases

- Good: an order reviewer filters a server-paginated list, opens one detail, views an authorized short-lived proof, adds a note, and reloads the order.
- Good: a catalog operator sanitizes an image into the configured shared storage, selects the stable public URL, adds a second SKU, and later inspects inventory adjustment history.
- Base: a cash order or aftersale has no proof; the detail page renders a clear empty state.
- Bad: hard-coded demo return address, permanent proof URL, raw `v-html`, client-only permission checks, role deletion with no reauthentication, or replacing a published rule row.

### 6. Tests Required

- `pnpm test:web`: route permission, page/API workflow, shared rich-text editor, sandbox, and multipart source contracts.
- `pnpm typecheck:web` and `pnpm build:web`: both Vue applications.
- `mvn -f backend/pom.xml test`: settings validation/audit, catalog asset compensation/audit, proof authorization, and existing domain/application suites.
- Empty MySQL integration: Flyway V1–V6 applies and creates both persistent additions.
- Runtime smoke: admin login/forced password change, settings read/write, role create/edit/delete, configured asset storage upload/public read/delete, and audit rows for every mutation.

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

This keeps content isolated, configuration server-owned, and UI permissions aligned with backend RBAC.

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

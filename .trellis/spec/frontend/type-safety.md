# Frontend Type Safety and API Contract

## Scope / Trigger

Apply this contract whenever an endpoint, request body, response view, token field, order status, rule field, proof, or aftersale field changes.

## Signatures

The API client unwraps a typed envelope:

```ts
interface ApiResponse<T> {
  success: boolean
  code: string
  message: string
  data: T
}

async function api<T>(path: string, init?: RequestInit): Promise<T>
```

Money fields use integer fen (`number`) and UI formatting is explicit. Status fields use string-literal unions where the action set depends on the status.

## Contracts

- User and admin tokens have different names and storage keys.
- The client sends the backend-returned Sa-Token header name; it does not assume a shared bearer token.
- API failure envelopes reject the call even when a proxy returns a parseable body.
- Proof URLs are short-lived and fetched only immediately before access.
- Server state remains authoritative after every mutation.
- Protected routes carry `requiresAuth` metadata and preserve only a same-origin application path as the post-login redirect.
- A 401 clears only the storefront session, records a login-expired reason, and redirects to login without creating a redirect loop.
- Product rich text is sanitized through the shared DOMPurify wrapper before `v-html`; views must not bypass that wrapper.
- Storefront distribution-rule copy is rendered from `/api/v1/rules/active`; configured money, counts, time windows, points, and versions must not be duplicated as static business constants.

## Validation & Error Matrix

| Boundary condition | UI behavior |
|--------------------|-------------|
| Network or invalid JSON | Show a retryable generic error |
| `success: false` | Show the backend-safe message; do not mutate local business state |
| 401 | Clear only the current application's token and route to its login |
| 403 | Keep session, hide/disable unauthorized action, show denial |
| 409 | Reload authoritative order/rule state |
| Unknown status value | Render a safe label and expose no mutation action |
| Unsafe redirect target | Fall back to `/`; never navigate to an external origin |
| Unsafe product HTML | Remove scripts, event handlers, embedded frames, forms, and inline styles before rendering |
| Missing active rule | Render an empty/unavailable state; do not invent a threshold |

## Good / Base / Bad Cases

- Good: `api<OrderView>("/api/v1/orders/1")` returns a typed server view.
- Good: a detail page fetches a proof download descriptor only when the user opens a preview and clears the signed URL when the preview closes.
- Base: a display-only content block may use a small local interface.
- Bad: `(await response.json()) as OrderView` in every view.
- Bad: `const points = order.amount / 100` as the authoritative reward.
- Bad: `v-html="product.description"` or a hard-coded `1998` qualification threshold.

## Tests Required

- Typecheck both applications.
- Add component or API-client tests when envelope, authentication, or status-action mapping changes.
- Exercise H5 and desktop login, order submission, proof access, and post-mutation reload paths.
- Search the storefront source for raw `v-html`, browser `alert`/`confirm`/`prompt`, and business thresholds duplicated outside typed rule projections.

## Wrong vs Correct

```ts
// Wrong
const body: any = await fetch(url).then(r => r.json())
order.value.status = "SHIPPED"

// Correct
await api<void>(`/api/v1/admin/orders/${id}/ship`, request)
order.value = await api<OrderView>(`/api/v1/admin/orders/${id}`)
```

```vue
<!-- Wrong -->
<article v-html="product.description" />

<!-- Correct -->
<article v-html="safeDescription" />
```

```ts
const safeDescription = computed(() => sanitizeProductHtml(product.value?.description ?? ""))
```

## Type Organization

Types used by one view stay near that view. Shared API-envelope, session, and broadly reused resource types stay in `api.ts` or a future dedicated `types/` module. Prefer type guards at untrusted boundaries over assertions.

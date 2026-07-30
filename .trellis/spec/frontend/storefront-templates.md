# Storefront Template UI Contract

## Scenario: Responsive template renderer and admin studio

### 1. Scope / Trigger

Apply this contract whenever a storefront section, preset stylesheet, template token, admin design control, preview device, or template API type changes.

### 2. Signatures

```ts
type StorefrontPreset = 'EDITORIAL' | 'VIBRANT' | 'MINIMAL'
type StorefrontSectionType =
  | 'ANNOUNCEMENT'
  | 'HERO'
  | 'CATEGORY_NAV'
  | 'PRODUCT_COLLECTION'
  | 'CONTENT_STORY'
  | 'SERVICE_BENEFITS'
  | 'QUICK_LINKS'

type StorefrontTemplate = {
  id: number
  code: string
  name: string
  presetType: StorefrontPreset
  designTokensJson: string
  layoutJson: string
  version: number
}
```

The storefront reads `GET /api/v1/storefront/template`. The admin studio uses the versioned endpoints documented in the backend storefront-template contract and sends the unchanged backend enum values.

### 3. Contracts

- `StorefrontRenderer.vue` owns section dispatch; route views fetch business data and never make configured copy authoritative.
- The three presets must remain visually distinct while consuming the same typed sections and server-owned product/content/category data.
- Design tokens become bounded CSS custom properties only after runtime parsing with safe defaults.
- Unknown, malformed, or disabled sections render nothing and must not break the rest of the page.
- Product collections retain search, scene filtering, loading, empty, and load-more states; product price, SKU count, and stock stay server-owned.
- The admin studio edits only a draft, prevents duplicate submissions, previews desktop/H5 from the same in-memory configuration, and reloads server state after save/publish/archive.
- Production changes require an explicit custom confirmation modal; browser `alert`, `confirm`, and `prompt` are forbidden.
- Global admin shell styles must be scoped to direct shell children. Bare `aside` or descendant `.workspace main` selectors are forbidden because the template editor and preview intentionally contain semantic `aside` and `main` elements.

### 4. Validation & Error Matrix

| Condition | Required UI behavior |
|---|---|
| Public template request fails | Render a retryable error; do not invent business thresholds |
| Template JSON is malformed | Use safe visual defaults and skip invalid sections |
| Admin version conflict | Show the backend message and retain the draft for correction/reload |
| Active template selected for edit | Open duplicate flow instead of mutating production |
| Publish succeeds | Close confirmation, reload list, and show exactly one “当前生效” row |
| 390-pixel storefront viewport | No document overflow; mobile navigation and two-column/one-column section variants remain usable |
| Admin global CSS matches preview `aside`/`main` | Quality test fails |

### 5. Good / Base / Bad Cases

- Good: one renderer maps a typed `PRODUCT_COLLECTION` to authoritative product data and applies a preset-specific class.
- Base: a section with no matching content displays its empty state or is omitted without shifting unrelated sections.
- Bad: three copied home pages, direct `JSON.parse(...) as StorefrontTemplate`, raw configured HTML, fixed desktop-only widths, or global semantic-element CSS.

### 6. Tests Required

- `pnpm test`: assert preset names, section types, admin CRUD endpoints, version fields, safe confirmation, CSS shell scoping, multi-SKU rendering, and sanitized content.
- `pnpm typecheck:web`: no `any`, unchecked template assertion, or invalid section union.
- `pnpm build:web`: both applications compile and Vite emits both production bundles.
- Browser visual: editorial, vibrant, and minimal at 1440×1000 and 390×844; document overflow must be false.
- Admin browser visual: design sidebar is 360 pixels at desktop, preview begins after it, and H5 preview page is 390 pixels without document overflow.

### 7. Wrong vs Correct

#### Wrong

```css
aside { position: fixed; width: 250px; }
.workspace main { padding: 28px; }
```

These selectors also capture the template editor sidebar and nested preview main.

#### Correct

```css
.admin-shell > aside { position: fixed; width: 250px; }
.workspace > main { padding: 28px; }
```

Feature components then keep their own scoped layout rules without shell-level collisions.

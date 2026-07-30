# Storefront Template Contract

## Scenario: Versioned multi-template storefront

### 1. Scope / Trigger

Apply this contract whenever the active storefront layout, template persistence, template administration API, preset catalog, or public template projection changes. The template capability crosses domain, application, persistence, HTTP, RBAC, audit, and both Vue applications.

### 2. Signatures

Public read:

```http
GET /api/v1/storefront/template
```

Administrative writes require `storefront:template:manage`:

```http
GET    /api/v1/admin/storefront/templates
POST   /api/v1/admin/storefront/templates
PUT    /api/v1/admin/storefront/templates/{templateId}
POST   /api/v1/admin/storefront/templates/{templateId}/duplicate
POST   /api/v1/admin/storefront/templates/{templateId}/publish
DELETE /api/v1/admin/storefront/templates/{templateId}
```

Update, publish, and archive commands carry `expectedVersion`. Persistent rows use:

```sql
operation_storefront_template(
  id, template_code, template_name, preset_type, status, is_active,
  active_guard, design_tokens_json, layout_json, version,
  created_by_admin_id, updated_by_admin_id, published_at,
  created_at, updated_at
)
```

`active_guard` is generated from `is_active` and has a unique constraint, so MySQL enforces at most one active template.

### 3. Contracts

- Presets are `EDITORIAL`, `VIBRANT`, and `MINIMAL`; a new template or duplicate always starts as `DRAFT`.
- Status values are `DRAFT`, `PUBLISHED`, and `ARCHIVED`.
- The public endpoint returns the unique active row. If no active row exists during recovery, it returns the application-owned editorial fallback and never exposes a partial admin draft.
- Publishing is transactional: deactivate the old active template, publish the selected template, set it active, and compare its version.
- An active template cannot be edited or archived. Operators duplicate it to a draft before changing production design.
- `designTokensJson` is a bounded JSON object containing six `#RRGGBB` colors, `radius` from `0px` through `60px`, and `headingFont` equal to `serif` or `sans`.
- `layoutJson` uses `schemaVersion: 1`, contains 1–24 uniquely identified sections, and accepts only `ANNOUNCEMENT`, `HERO`, `CATEGORY_NAV`, `PRODUCT_COLLECTION`, `CONTENT_STORY`, `SERVICE_BENEFITS`, and `QUICK_LINKS`.
- Nested configuration depth is at most five, arrays contain at most 24 items, individual text is at most 500 characters, and `javascript:` values are rejected.
- Every successful admin mutation is written to immutable audit with the request ID. Request bodies and template JSON are not copied into the generic HTTP audit record.

### 4. Validation & Error Matrix

| Condition | Required result |
|---|---|
| Unknown template ID | `STOREFRONT_TEMPLATE_NOT_FOUND`, HTTP 404 |
| Stale `expectedVersion` | `STOREFRONT_TEMPLATE_CONCURRENT_MODIFICATION`, HTTP 409 |
| Edit archived or active template | `STOREFRONT_TEMPLATE_STATE_CONFLICT`, HTTP 409 |
| Archive active template | `STOREFRONT_TEMPLATE_STATE_CONFLICT`, HTTP 409 |
| Invalid preset, colors, section, depth, link, or JSON | Stable `STOREFRONT_TEMPLATE_*_INVALID`, HTTP 400 |
| Missing admin permission | HTTP 403 and no mutation |
| Concurrent attempt to activate two rows | Unique constraint/transaction rollback; one active row remains |
| No active row during public read | Return the editorial fallback without failing the storefront |

### 5. Good / Base / Bad Cases

- Good: duplicate the current production template, edit the draft with version 0, preview PC and H5, save as version 1, then publish version 1.
- Base: a clean V8 database serves the seeded editorial template while the other two presets remain drafts.
- Bad: write template JSON directly from a controller, accept arbitrary component names or HTML, update without a version, or let the browser decide which template is production.

### 6. Tests Required

- Domain: active/archived edit and archive invariants, publish transition, and field validation.
- Application: three-preset creation, configuration whitelist, unsafe protocol rejection, fallback read, and stale-version rejection.
- Frontend contract: all section renderers and the three visual presets remain wired to the public response.
- Empty MySQL runtime: Flyway V1–V8 succeeds, three templates exist, and exactly one row is active.
- Admin runtime: save increments the version; publish switches the unique active row; storefront read changes immediately; both mutations create request-correlated audit rows.
- Responsive visual: each preset has no document-level horizontal overflow at desktop and 390-pixel H5 widths.

### 7. Wrong vs Correct

#### Wrong

```java
mapper.updateActive(templateId, true);
```

This bypasses domain state, optimistic locking, single-active replacement, validation, and audit.

#### Correct

```java
TemplateRecord record = port.find(templateId).orElseThrow(...);
requireVersion(record, expectedVersion);
validateConfiguration(record.template().designTokensJson(), record.template().layoutJson());
record.template().publish(clock.instant());
port.publish(adminId, record.template(), expectedVersion);
```

The adapter performs the deactivate-and-activate sequence in the application transaction, with the database unique guard as the final concurrency boundary.

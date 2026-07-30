# Section-based storefront template architecture

## Sources reviewed

- Shopify Online Store 2.0 JSON templates and sections:
  - https://shopify.dev/docs/storefronts/themes/architecture/templates/json-templates
  - https://shopify.dev/docs/storefronts/themes/architecture/sections
  - https://shopify.dev/docs/storefronts/themes/architecture/section-schema

## Comparable patterns

### Shopify JSON templates

- A page template stores ordered section instances and per-section settings.
- Merchants can add, remove, disable, and reorder sections.
- Rendering code remains owned by the platform/theme; persisted data only selects a whitelisted section type and its settings.
- Presets provide safe starting configurations instead of requiring a merchant to build a page from an empty canvas.

### General headless CMS block composition

- Page content is represented as a discriminated list of typed blocks.
- Each block has a stable identifier, a type, enabled state, and validated settings.
- The frontend maps block types to registered components and ignores unknown types safely.

## Constraints in market-shop

- Vue storefront and admin console are compiled into the same container image; runtime arbitrary Vue/Liquid code is not viable.
- The backend follows DDD/Phoenix dependency direction and Flyway owns schema changes.
- Existing catalog, content, asset, rule, and session APIs must remain the sources of business data.
- Templates must work in PC and H5 without separate content copies.
- Stored settings are untrusted input and must not become executable HTML, CSS, JavaScript, or arbitrary component imports.

## Feasible approaches

### A. Controlled typed sections (selected)

- Persist a template aggregate with design tokens plus an ordered list of typed section settings.
- Validate types, counts, lengths, colors, links, and product limits in the application/domain boundary.
- Render only a fixed Vue component registry.
- Seed three built-in presets and allow administrators to create additional templates from any preset.

Advantages: safe, testable, responsive by construction, extensible through migrations and component registry updates.

Trade-off: administrators can compose supported layouts but cannot upload arbitrary theme code.

### B. Store raw HTML/CSS

Advantages: maximum visual freedom.

Trade-off: XSS/CSP risk, weak mobile guarantees, hard preview isolation, and poor maintainability. Rejected.

### C. Separate compiled frontend per template

Advantages: complete visual isolation.

Trade-off: every template change requires a build/deploy and cannot provide SaaS-style runtime editing. Rejected.

## Resulting contract

- One active published template is served publicly.
- Draft editing never affects the live storefront until publish.
- Publishing is transactional and deactivates the previous template.
- Templates may be created from three presets, edited, previewed in PC/H5 frames, duplicated, published, and archived.
- Supported home sections for this version: announcement, hero, category navigation, product collection, story/content feature, service benefits, and quick links.
- Header/footer behavior and global colors/typography are driven by validated design tokens.

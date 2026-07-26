# Vue Component Guidelines

Use Vue Single File Components with `<script setup lang="ts">`. Keep route orchestration in views and move reusable presentation into focused components. Props and emitted events must be typed. Template state derives from `computed` values instead of duplicated mutable fields.

Layouts must work at desktop and H5 widths. Interactive controls require visible labels, keyboard focus, disabled/loading states, and readable error text. Use semantic buttons for actions and links for navigation. Images require meaningful alt text unless decorative.

Keep styles scoped when they are feature-specific. Shared design tokens use CSS custom properties. Avoid fixed desktop widths, hover-only affordances, and content that depends on color alone.

Components must not construct authorization headers, parse the common API envelope independently, or implement order-state policy. Those responsibilities belong to the API client and backend.

## Storefront Product Media

Commerce surfaces use the shared `ProductMedia.vue` component for backend `coverUrl` values. The homepage, product detail, cart, checkout, and order detail must not create independent colored initials or duplicate image-error logic.

- Pass a meaningful product-name `alt`; decorative campaign images use an empty alt.
- The first visible campaign/product image may be eager; list images remain lazy.
- Missing and failed URLs render the shared accessible fallback without hiding product name, price, inventory, or actions.
- Media motion is subtle and disabled through `prefers-reduced-motion`.
- Backend-provided catalog URLs remain authoritative. Do not hard-code RustFS URLs, local filesystem paths, or proof URLs into product cards.

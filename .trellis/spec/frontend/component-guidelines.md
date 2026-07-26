# Vue Component Guidelines

Use Vue Single File Components with `<script setup lang="ts">`. Keep route orchestration in views and move reusable presentation into focused components. Props and emitted events must be typed. Template state derives from `computed` values instead of duplicated mutable fields.

Layouts must work at desktop and H5 widths. Interactive controls require visible labels, keyboard focus, disabled/loading states, and readable error text. Use semantic buttons for actions and links for navigation. Images require meaningful alt text unless decorative.

Keep styles scoped when they are feature-specific. Shared design tokens use CSS custom properties. Avoid fixed desktop widths, hover-only affordances, and content that depends on color alone.

Components must not construct authorization headers, parse the common API envelope independently, or implement order-state policy. Those responsibilities belong to the API client and backend.

# Frontend Directory Structure

## Applications

```text
frontend/
├── storefront/src
│   ├── views
│   ├── stores
│   ├── api.ts
│   ├── main.ts
│   └── App.vue
└── admin/src
    ├── views
    ├── api.ts
    ├── main.ts
    └── App.vue
```

Route-level components belong in `views/`. Shared session/cart state belongs in `stores/` only when it is reused across routes. HTTP envelope handling and token injection stay in `api.ts`. Router construction and application bootstrap stay in `main.ts`.

Use PascalCase for Vue components and views, camelCase for TypeScript functions, and lowercase route paths. Do not commit emitted `.js` beside `.ts` or `.vue`; `tsconfig.json` must keep `noEmit: true`.

When a view becomes too large, extract a feature component beside the owning feature before introducing a global component directory. The storefront and admin must not import source files from one another.

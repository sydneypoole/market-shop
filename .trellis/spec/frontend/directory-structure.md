# Frontend Directory Structure

## Applications

```text
frontend/
└── admin/src
    ├── views
    ├── components
    ├── api.ts
    ├── main.ts
    └── App.vue
```

The member-facing client is a native WeChat miniprogram under `miniprogram/` (outside the pnpm workspace). Do not reintroduce a web storefront package under `frontend/`.

Route-level components belong in `views/`. HTTP envelope handling and admin session injection stay in `api.ts`. Router construction and application bootstrap stay in `main.ts`.

Use PascalCase for Vue components and views, camelCase for TypeScript functions, and lowercase route paths. Do not commit emitted `.js` beside `.ts` or `.vue`; `tsconfig.json` must keep `noEmit: true`.

When a view becomes too large, extract a feature component beside the owning feature before introducing a global component directory.

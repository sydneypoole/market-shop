# Frontend Development Guidelines

> Conventions for the Vue 3 admin console and the member-facing native WeChat miniprogram, which remains outside the web monorepo and its npm build.

## Stack

The admin console uses Vue 3, TypeScript, Vite, and Vue Router. It consumes the versioned Spring Boot API with an admin-only Sa-Token cookie session, isolated from the miniprogram header token (`market-shop-user-token`).

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | Application and feature layout | Filled |
| [Component Guidelines](./component-guidelines.md) | Vue SFC and responsive UI conventions | Filled |
| [Hook Guidelines](./hook-guidelines.md) | Composables and data-fetching boundaries | Filled |
| [State Management](./state-management.md) | Local, URL, session, and server state | Filled |
| [Quality Guidelines](./quality-guidelines.md) | Build and review requirements | Filled |
| [Type Safety](./type-safety.md) | API envelope and runtime boundary rules | Filled |
| [Admin Console Contract](./admin-console.md) | Admin routes, permissions, operational pages, settings, and RustFS media | Filled |
| [Native Miniprogram FirstUI](./miniprogram-firstui.md) | FirstUI source pin, brand wrappers, native capability and event-contract rules | Filled |

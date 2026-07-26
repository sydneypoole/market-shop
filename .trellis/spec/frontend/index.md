# Frontend Development Guidelines

> Conventions shared by the responsive Vue storefront and admin console.

## Stack

Both applications use Vue 3, TypeScript, Vite, and Vue Router. The storefront also uses Pinia for cart and session state. They consume the same versioned Spring Boot API but keep user and admin tokens isolated.

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

# Backend Development Guidelines

> Executable conventions for the Market Shop modular monolith.

## Architecture

The backend follows DDD and Phoenix Architecture constraints as a five-module Maven reactor. Dependencies point inward: interfaces and infrastructure depend on application contracts, application depends on domain, and domain is framework-free. Business capabilities are separated by bounded context rather than by technical CRUD layer.

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | Maven modules, bounded contexts, dependency direction | Filled |
| [Database Guidelines](./database-guidelines.md) | MyBatis-Flex, transactions, migrations, concurrency | Filled |
| [Error Handling](./error-handling.md) | Domain failures and stable API responses | Filled |
| [Quality Guidelines](./quality-guidelines.md) | Required tests and forbidden business shortcuts | Filled |
| [Logging Guidelines](./logging-guidelines.md) | Operational logs and immutable admin audit | Filled |
| [Private Proof Object Storage](./object-storage.md) | RustFS/S3 contracts, proof security, retention, and tests | Filled |
| [Storefront Query Contracts](./storefront-query-contracts.md) | Authorized detail reads, proof access, invitations, and active rule projection | Filled |
| [Storefront Templates](./storefront-templates.md) | Versioned templates, single-active publication, configuration validation, RBAC, and audit | Filled |
| [Container Delivery and GHCR](./container-delivery.md) | Single-image Nginx/Spring Boot packaging, routing, health, and publication | Filled |

All specification documents are written in English. Update them whenever a new pattern or cross-layer contract is introduced.

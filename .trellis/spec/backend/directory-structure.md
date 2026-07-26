# Backend Directory Structure

## Dependency Direction

```text
shop-bootstrap
├── shop-interfaces ──> shop-application ──> shop-domain
└── shop-infrastructure ────────────────┘
```

`shop-domain` must not import Spring, MyBatis-Flex, Sa-Token, Redis, RustFS/AWS S3 SDK, or HTTP types. `shop-application` defines use cases and outbound ports. `shop-infrastructure` implements persistence, OAuth, Redis, object storage, scheduling, and projection ports. `shop-interfaces` translates HTTP and authentication state into use-case commands. `shop-bootstrap` owns runtime configuration and Flyway resources.

## Layout

```text
backend/
├── shop-domain/src/{main,test}/java/com/marketshop/domain
├── shop-application/src/{main,test}/java/com/marketshop/application
├── shop-infrastructure/src/main/java/com/marketshop/infrastructure
├── shop-interfaces/src/main/java/com/marketshop/interfaces
└── shop-bootstrap/src/main
    ├── java/com/marketshop/bootstrap
    └── resources/db/migration
```

Within each module, package first by bounded context: `identity`, `commerce`, `membership`, `aftersale`, `proof`, `audit`, or `reliability`. Shared primitives are allowed only in a narrowly named `shared` package.

## Placement Rules

- Aggregate invariants and state transitions belong in domain objects such as `Order`.
- Transaction orchestration belongs in an application service.
- Repository-facing records and port interfaces belong in application.
- SQL mappers, Redis keys, WeChat protocol handling, and RustFS/S3 calls belong in infrastructure.
- Controllers, filters, API records, validation annotations, and Sa-Token request integration belong in interfaces.
- Environment defaults, scheduled-job enablement, and Flyway scripts belong in bootstrap.

## Naming

Use `*UseCase` for inbound contracts, `*Port` for outbound contracts, `*ApplicationService` for orchestration, and `*Adapter` for infrastructure implementations. Controllers must not call mappers. Adapters must not decide membership or order policy.

## Good and Bad

Good: `Order.confirmBySuperior()` validates the current state, while `CommerceApplicationService` saves it and appends an outbox event in one transaction.

Bad: an `AdminOrderController` directly executing an update statement or calculating commission.

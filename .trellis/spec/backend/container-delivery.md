# Container Delivery and GHCR

## Scenario: Single application image with Nginx and Spring Boot

### 1. Scope / Trigger

- Trigger: any change to `Dockerfile`, `.dockerignore`, `deploy/`, the admin build base, application ports, health endpoints, or `.github/workflows/docker-image.yml`.
- The delivery artifact is one OCI image containing the executable Spring Boot JAR, storefront assets, and admin assets. Compose runs that image alongside separate MySQL, Redis, and optional RustFS services.
- This contract covers packaging and routing only; it does not move HTTP or authorization policy into Nginx.

### 2. Signatures

```text
External container port: 8080 (Nginx)
Internal backend port:    8081 (Spring Boot)

/                         -> storefront SPA
/admin/                   -> admin SPA
/api/**                   -> Spring Boot
/actuator/**              -> Spring Boot
/docs                     -> Spring Boot
/api-docs/**              -> Spring Boot
/swagger-ui/**            -> Spring Boot
```

```bash
# Default complete stack: app + MySQL + Redis + persistent local uploads
docker compose --env-file .env up -d --build

# Optional S3-compatible stack: also start RustFS
docker compose --env-file .env --profile rustfs up -d --build
```

```json
{
  "scripts": {
    "build:container:web": "build storefront at / and admin at /admin/",
    "test:container": "verify image, proxy, and workflow contracts"
  }
}
```

```yaml
permissions:
  contents: read
  packages: write
```

### 3. Contracts

- `Dockerfile` uses independent Maven and pnpm builder stages and copies only the executable JAR and compiled web assets into the runtime stage.
- The runtime process user is `marketshop` with UID/GID `10001`; Nginx listens on the unprivileged port `8080`.
- Tini is PID 1. Supervisor manages Nginx and Java with TERM/QUIT propagation and bounded shutdown waits.
- `MARKET_SHOP_SERVER_PORT=8081` is an image default. Every database, Redis, RustFS, WeChat, and bootstrap credential remains runtime environment input and must never be an image layer or build argument.
- The admin build uses Vite base `/admin/`, and Vue Router uses `createWebHistory(import.meta.env.BASE_URL)`. Local non-container builds keep the default `/` base.
- Nginx serves SPA History fallbacks separately: storefront routes fall back to `/index.html`, while admin routes fall back to `/admin/index.html`.
- Production image assets do not contain `.map` source maps. Static fingerprinted assets may be cached as immutable.
- The container health check reaches Spring Boot readiness through Nginx at `/actuator/health/readiness`; a static-only Nginx process is not healthy.
- The default Compose model starts `app`, `mysql`, and `redis`. The app waits for healthy dependencies, uses `MARKET_SHOP_STORAGE_PROVIDER=local` by default, and mounts `market-shop-uploads` at `/opt/market-shop/data/uploads`.
- Compose publishes the application on `${MARKET_SHOP_HTTP_BIND_HOST:-127.0.0.1}:${MARKET_SHOP_HTTP_PORT:-8080}`. Public exposure must be an explicit operator decision.
- RustFS and its volume-permission initializer use the `rustfs` profile. It is an optional health-gated app dependency: local mode starts without it, while the enabled profile delays the app until RustFS is healthy. The local endpoint `http://rustfs.localhost:9000` is both a Docker network alias and a browser loopback origin so presigned URLs keep one reachable host.
- Compose passes secrets only as runtime environment values from `.env`; `.env` remains ignored and `.env.example` contains replaceable development placeholders. Local signing-secret length is still enforced by the selected adapter at startup.
- GitHub Actions runs backend tests, web tests, container contract tests, and type checking before the image job. Pull requests build without publishing. Pushes publish `linux/amd64` and `linux/arm64` to GHCR with branch, SHA, semantic-version, and default-branch `latest` tags.
- `GITHUB_TOKEN` is the only registry credential and the workflow grants the minimal package-write permission.
- `MARKET_SHOP_RUSTFS_ENDPOINT` must be an HTTPS origin reachable by both the backend and browsers because presigned proof URLs retain that origin.
- The infrastructure module keeps MyBatis-Flex annotation processing disabled while persistence POs remain nested classes and no generated `TableDef` is consumed:

```xml
<configuration>
    <proc>none</proc>
</configuration>
```

Runtime MyBatis-Flex annotations remain active; only compile-time `TableDef` generation is disabled.

### 4. Validation & Error Matrix

| Condition | Required result |
|---|---|
| Storefront deep link such as `/orders/1` | HTTP 200 storefront `index.html` |
| Admin deep link such as `/admin/orders` | HTTP 200 admin `index.html`; assets remain under `/admin/assets/` |
| `/api/v1/**` request | Proxied unchanged to `127.0.0.1:8081` with forwarded host/protocol headers |
| Java is down while Nginx is up | Readiness fails; container becomes unhealthy |
| MySQL or Redis is still starting | Compose keeps `app` blocked until its dependency health check succeeds |
| Default Compose start without the `rustfs` profile | App uses the persistent local upload volume; no RustFS container consumes resources |
| `MARKET_SHOP_STORAGE_PROVIDER=s3` without `--profile rustfs` or another reachable S3 endpoint | Storage operations fail visibly; documentation must pair the provider with its service/profile |
| Local signing secret is missing or shorter than 32 characters | Backend startup fails visibly; Compose never injects a hard-coded production fallback |
| Required DB/Redis/RustFS secret is absent or invalid | Backend fails/retries visibly; never fall back to a production hard-coded credential |
| Pull request workflow | Tests and multi-platform build run; GHCR login/push is skipped |
| Default-branch push | Publish branch, `sha-*`, and `latest` tags |
| `v1.2.3` tag push | Publish semantic-version tags |
| Runtime process UID is 0 | Delivery check fails |
| `.map` exists in final web directories | Delivery check fails |
| Nested PO annotation processor is re-enabled | Clean Maven build fails on class/package name clash; restore `<proc>none</proc>` or refactor POs into top-level classes first |

### 5. Good/Base/Bad Cases

- Good: a default-branch push passes all checks and publishes one signed/provenanced multi-platform GHCR image; `/`, `/admin/`, and `/api/` work from one origin.
- Good: `docker compose up -d --build` migrates an empty database, becomes healthy, serves both SPAs, and retains uploads across container recreation.
- Base: a pull request performs the same compilation and image build without registry credentials or package mutation.
- Base: an operator enables the `rustfs` profile and switches the provider to `s3`; the same application image is reused.
- Base: operators override `JAVA_TOOL_OPTIONS` or runtime service endpoints without rebuilding the image.
- Bad: mount uploads into a container-only anonymous path, start the app before dependency health, expose every port on `0.0.0.0` by default, or start unused RustFS in local mode.
- Bad: copy `node_modules`, Maven caches, `.env`, source maps, or frontend source into the runtime image.
- Bad: run Nginx as root on port 80, bake production secrets into `ARG`/`ENV`, expose Java directly, or mark the container healthy using only a static Nginx page.
- Bad: build the admin with base `/` and rely on Nginx rewrites to repair absolute asset URLs.

### 6. Tests Required

- Project gate: `mvn -f backend/pom.xml clean test package`, `pnpm test`, `pnpm typecheck:web`, and `pnpm build:web`.
- Container contract tests assert the three artifacts, non-root `USER`, readiness health check, `/api/` proxy, both SPA fallbacks, GHCR permissions, multi-platform list, and pull-request no-push condition.
- Compose contract tests assert the `app` build, service-name database/Redis wiring, health-gated dependencies, local upload volume, loopback HTTP binding, and optional RustFS profile/network alias.
- Compose config gates run both `docker compose --env-file .env.example config --quiet` and the same command with `--profile rustfs`.
- Image build: `docker build -t market-shop:test .` succeeds from a context with ignored local `target`, `dist`, and `node_modules`.
- Runtime smoke uses an isolated Compose project and ports. On an empty MySQL database it asserts Flyway startup, readiness `UP`, storefront and admin deep-link titles, a public rules API response through Nginx, and cleanup of test-only volumes.
- Image inspection asserts UID/GID `10001`, JAR/storefront/admin files exist, and no `*.map` exists under `/opt/market-shop/web`.
- Nginx gate: `nginx -t -c /opt/market-shop/nginx.conf` succeeds as the non-root image user. `/var/log/nginx` must be writable because Ubuntu's Nginx 1.18 opens its compiled default error log before parsing the custom stderr destination.

### 7. Wrong vs Correct

#### Wrong

```dockerfile
FROM eclipse-temurin:21
COPY . /app
ENV MARKET_SHOP_DB_PASSWORD=production-password
USER root
CMD ["java", "-jar", "/app/backend.jar"]
```

```ts
createWebHistory()
```

```yaml
app:
  environment:
    MARKET_SHOP_DB_URL: jdbc:mysql://127.0.0.1:3306/market_shop
  volumes:
    - /tmp/uploads
```

#### Correct

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS backend-builder
# build in an isolated stage

FROM eclipse-temurin:21-jre-jammy
COPY --from=backend-builder /workspace/backend/shop-bootstrap/target/shop-bootstrap-*.jar /opt/market-shop/app.jar
USER marketshop
HEALTHCHECK CMD curl -fsS http://127.0.0.1:8080/actuator/health/readiness || exit 1
```

```ts
createWebHistory(import.meta.env.BASE_URL)
```

```yaml
app:
  depends_on:
    mysql:
      condition: service_healthy
    redis:
      condition: service_healthy
  environment:
    MARKET_SHOP_DB_URL: jdbc:mysql://mysql:3306/market_shop
    MARKET_SHOP_STORAGE_PROVIDER: ${MARKET_SHOP_STORAGE_PROVIDER:-local}
  volumes:
    - market-shop-uploads:/opt/market-shop/data/uploads
```

This keeps one-origin deployment convenient without weakening secret separation, health semantics, least privilege, or reproducible clean builds.

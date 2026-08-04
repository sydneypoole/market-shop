# Container Delivery and GHCR

## Scenario: Single application image with Nginx and Spring Boot

### 1. Scope / Trigger

- Trigger: any change to `Dockerfile`, `.dockerignore`, `deploy/`, the admin build base, application ports, health endpoints, or `.github/workflows/docker-image.yml`.
- The delivery artifact is one OCI image containing the executable Spring Boot JAR and admin assets. Compose runs that image alongside separate MySQL, Redis, and optional RustFS services. The member client is a WeChat miniprogram outside the image.
- This contract covers packaging and routing only; it does not move HTTP or authorization policy into Nginx.

### 2. Signatures

```text
External container port: 8080 (Nginx)
Internal backend port:    8081 (Spring Boot)

/                         -> 404 (no public web storefront)
/admin/                   -> admin SPA
/api/**                   -> Spring Boot
/healthz                  -> Spring Boot readiness, without details
/actuator/**              -> 404 at public Nginx
/docs                     -> 404 at public Nginx
/api-docs/**              -> 404 at public Nginx
/swagger-ui/**            -> 404 at public Nginx
```

```text
Trusted outer TLS proxy -> container Nginx:
CF-Visitor: {"scheme":"http|https"} (Cloudflare)
X-Forwarded-Proto: http | https
X-Forwarded-Port:  1-5 decimal digits (optional)

Container Nginx -> Spring Boot:
Forwarded: proto=<sanitized>;host="<host>:<sanitized-port>"
```

```bash
# Production stack: immutable app digest + MySQL + Redis + persistent local uploads
docker compose --env-file .env up -d --wait

# Explicit local build/mock stack; only this overlay publishes DB/Redis on loopback
docker compose -f docker-compose.yml -f docker-compose.local.yml \
  --env-file .env.local.example up -d --build --wait
```

```json
{
  "scripts": {
    "build:container:web": "build admin at /admin/",
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
- Nginx serves the admin SPA History fallback at `/admin/` → `/admin/index.html`. The root path `/` returns 404; there is no web storefront SPA.
- All proxied backend locations inherit one shared set of `proxy_set_header` directives. A location must not redefine only part of that set because Nginx then stops inheriting the remaining headers.
- For a trusted outer TLS proxy, container Nginx accepts `X-Forwarded-Proto` only when it is exactly `http` or `https` (case-insensitive), and accepts `X-Forwarded-Port` only when it is 1-5 decimal digits. Invalid protocol values fall back to `$scheme`; invalid or absent port values fall back to the inferred standard port for a valid forwarded protocol, or `$server_port` when no valid forwarded protocol exists.
- When Cloudflare's `CF-Visitor` is exactly a JSON object with `scheme=http|https`, its scheme takes priority over `X-Forwarded-Proto`. This handles an additional HTTP proxy hop that overwrites Cloudflare's public HTTPS metadata. A Cloudflare-derived scheme always uses standard port `80` or `443` rather than a later hop's forwarded port.
- Container Nginx must overwrite any inbound RFC `Forwarded` header with a new value derived only from the sanitized scheme, host, and port. Spring's framework forwarded-header strategy trusts `Forwarded` before the `X-Forwarded-*` family, so passing a client-provided value creates both a CORS bypass and inconsistent origin reconstruction.
- Direct local HTTP remains `scheme=http` with the container server port. External HTTPS with no forwarded port becomes `scheme=https, port=443`. This lets Spring's framework forwarded-header strategy compare the browser Origin against the real public origin.
- Do not work around an origin mismatch by allowing every CORS origin. First correct the trusted proxy's `Host`, `X-Forwarded-Proto`, and `X-Forwarded-Port` chain.
- Production image assets do not contain `.map` source maps. Static fingerprinted assets may be cached as immutable.
- The container health check reaches Spring Boot readiness through Nginx at public no-detail `/healthz`; a static-only Nginx process is not healthy.
- The default production Compose model starts `app`, `mysql`, and `redis`, requires an immutable `MARKET_SHOP_IMAGE`, does not publish MySQL/Redis ports, waits for healthy dependencies, uses `MARKET_SHOP_STORAGE_PROVIDER=local` by default, and mounts `market-shop-uploads` at `/opt/market-shop/data/uploads`.
- Compose publishes the application on `${MARKET_SHOP_HTTP_BIND_HOST:-127.0.0.1}:${MARKET_SHOP_HTTP_PORT:-8080}`. Public exposure must be an explicit operator decision.
- RustFS and its volume-permission initializer use the `rustfs` profile. It is an optional health-gated app dependency: local mode starts without it, while the enabled profile delays the app until RustFS is healthy. The local endpoint `http://rustfs.localhost:9000` is both a Docker network alias and a browser loopback origin so presigned URLs keep one reachable host; isolated Docker Desktop drills may use `host.docker.internal` with an explicit loopback `--resolve` mapping in `business-e2e.sh`. `MARKET_SHOP_S3_BACKEND_MODE=bundled` is reserved for local/e2e profiles; the production `prod` profile (including the release candidate) rejects it before startup and requires `external` object snapshots.
- Compose passes secrets only as runtime environment values from `.env`; `.env` remains ignored and `.env.example` is a production template whose placeholders are deliberately rejected at startup. `.env.local.example` is used only with the explicit local overlay. Local signing-secret length is still enforced by the selected adapter at startup.
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
| Root path `/` or former storefront deep link such as `/orders/1` | HTTP 404 |
| Admin deep link such as `/admin/orders` | HTTP 200 admin `index.html`; assets remain under `/admin/assets/` |
| `/api/v1/**` request | Proxied unchanged to `127.0.0.1:8081` with forwarded host/protocol headers |
| Outer proxy sends `X-Forwarded-Proto: https` and `X-Forwarded-Port: 443` | Spring reconstructs `https://<host>` and same-origin admin login reaches application authentication |
| Outer proxy sends `X-Forwarded-Proto: https` without a port | Container Nginx supplies forwarded port `443` |
| Cloudflare sends `CF-Visitor: {"scheme":"https"}` but a later proxy sends `X-Forwarded-Proto: http` | Cloudflare scheme wins; Spring reconstructs HTTPS port `443` |
| Client supplies `Forwarded: proto=https` while trusted proxy metadata resolves to HTTP | Container Nginx replaces it with sanitized HTTP metadata; mismatched HTTPS Origin remains rejected |
| Request has no forwarded protocol or port | Container Nginx uses its local `$scheme` and `$server_port` |
| Forwarded protocol is not exactly `http` or `https` | Ignore it and use `$scheme`; a mismatched browser Origin remains rejected by CORS |
| Forwarded port is non-numeric or longer than 5 digits | Ignore it and use the protocol-derived/default local port |
| Java is down while Nginx is up | Readiness fails; container becomes unhealthy |
| MySQL or Redis is still starting | Compose keeps `app` blocked until its dependency health check succeeds |
| Default Compose start without the `rustfs` profile | App uses the persistent local upload volume; no RustFS container consumes resources |
| `MARKET_SHOP_STORAGE_PROVIDER=s3` without `--profile rustfs` or another reachable S3 endpoint | Storage operations fail visibly; documentation must pair the provider with its service/profile |
| Production/release starts with `MARKET_SHOP_S3_BACKEND_MODE=bundled` | Environment post-processing fails before the application context starts; use `external` and an object snapshot hook |
| Local/e2e fixture starts with `MARKET_SHOP_S3_BACKEND_MODE=bundled` | Allowed only with the explicit RustFS profile and endpoint/volume checks in the backup/restore scripts |
| Local signing secret is missing or shorter than 32 characters | Backend startup fails visibly; Compose never injects a hard-coded production fallback |
| Required DB/Redis/RustFS secret is absent or invalid | Backend fails/retries visibly; never fall back to a production hard-coded credential |
| Pull request workflow | Tests and multi-platform build run; GHCR login/push is skipped |
| Default-branch push | Publish branch, `sha-*`, and `latest` tags |
| `v1.2.3` tag push | Publish semantic-version tags |
| Runtime process UID is 0 | Delivery check fails |
| `.map` exists in final web directories | Delivery check fails |
| Nested PO annotation processor is re-enabled | Clean Maven build fails on class/package name clash; restore `<proc>none</proc>` or refactor POs into top-level classes first |

### 5. Good/Base/Bad Cases

- Good: a default-branch push passes all checks and publishes one signed/provenanced multi-platform GHCR image; `/admin/` and `/api/` work from one origin while `/` returns 404.
- Good: an outer TLS proxy passes `Host`, `X-Forwarded-Proto: https`, and optionally `X-Forwarded-Port: 443`; an admin login POST is evaluated as same-origin.
- Good: a Cloudflare request retains `CF-Visitor`; container Nginx selects HTTPS even if an internal reverse proxy reports its own HTTP hop.
- Good: a digest-pinned `docker compose up -d --wait` migrates an empty database, becomes healthy, serves the admin SPA and API, and retains uploads across container recreation.
- Base: a pull request performs the same compilation and image build without registry credentials or package mutation.
- Base: an operator enables the `rustfs` profile and switches the provider to `s3`; the same application image is reused.
- Base: operators override `JAVA_TOOL_OPTIONS` or runtime service endpoints without rebuilding the image.
- Bad: mount uploads into a container-only anonymous path, start the app before dependency health, expose every port on `0.0.0.0` by default, or start unused RustFS in local mode.
- Bad: copy `node_modules`, Maven caches, `.env`, source maps, or frontend source into the runtime image.
- Bad: run Nginx as root on port 80, bake production secrets into `ARG`/`ENV`, expose Java directly, or mark the container healthy using only a static Nginx page.
- Bad: overwrite an outer proxy's HTTPS metadata with the container's internal `$scheme`/`$server_port`, causing Spring to return `Invalid CORS request`.
- Bad: pass `$http_forwarded` to Spring or leave `Forwarded` unset at the inner trust boundary, allowing a client-provided value to outrank sanitized proxy headers.
- Bad: build the admin with base `/` and rely on Nginx rewrites to repair absolute asset URLs.

### 6. Tests Required

- Project gate: `mvn -f backend/pom.xml clean test package`, `pnpm test`, `pnpm typecheck:web`, and `pnpm build:web`.
- Container contract tests assert the JAR + admin artifacts, non-root `USER`, readiness health check, `/api/` proxy, admin SPA fallback, root 404, GHCR permissions, multi-platform list, and pull-request no-push condition.
- Proxy-header contract tests assert Cloudflare precedence, sanitized protocol/port maps, inferred HTTPS port `443`, local fallbacks, one shared `X-Forwarded-Proto` directive, a synthesized `Forwarded` header, and absence of direct `$scheme`/`$server_port` overwrites.
- Runtime proxy smoke asserts `CF-Visitor=https + X-Forwarded-Proto=http` reaches application authentication, while a client-only `Forwarded: proto=https` cannot bypass a sanitized HTTP origin.
- Compose contract tests assert that the production base has no `app` build and no MySQL/Redis host ports, while the explicit local overlay adds the build and loopback-only dependency ports. They also verify service-name database/Redis wiring, health-gated dependencies, the local upload volume, loopback HTTP binding, and the optional RustFS profile/network alias.
- Compose config gates run the production base plus explicit local, E2E, RustFS and release overlay combinations.
- Image build: `docker build -t market-shop:test .` succeeds from a context with ignored local `target`, `dist`, and `node_modules`.
- Runtime smoke uses an isolated Compose project and ports. On an empty MySQL database it asserts Flyway startup, readiness `UP`, admin deep-link title, root 404, miniprogram login probe, a public rules API response through Nginx, and cleanup of test-only volumes.
- Image inspection asserts UID/GID `10001`, JAR/admin files exist, and no `*.map` exists under `/opt/market-shop/web`.
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
HEALTHCHECK CMD curl -fsS http://127.0.0.1:8080/healthz || exit 1
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

```nginx
# Wrong: discards public HTTPS metadata at the inner HTTP hop.
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Port $server_port;
proxy_set_header Forwarded $http_forwarded;

# Correct: derive every forwarded family header from one sanitized source.
proxy_set_header X-Forwarded-Proto $market_shop_forwarded_proto;
proxy_set_header X-Forwarded-Port $market_shop_forwarded_port;
proxy_set_header Forwarded "proto=$market_shop_forwarded_proto;host=\"$host:$market_shop_forwarded_port\"";
```

This keeps one-origin deployment convenient without weakening secret separation, health semantics, least privilege, or reproducible clean builds.

## Scenario: Production-safe configuration, health isolation and recoverable digest releases

### 1. Scope / Trigger

- Trigger: changing Compose defaults, production secrets, health contributors, public proxy locations, backup/restore, image promotion, or rollback.
- The production base, explicit local overlay, application profile, Nginx boundary, and operator scripts form one release contract.

### 2. Signatures

```text
docker-compose.yml                 -> prod, digest image, no MySQL/Redis host ports
docker-compose.local.yml           -> explicit local/mock build, DB/Redis on 127.0.0.1 only
docker-compose.release.yml         -> isolated candidate, scheduling disabled, loopback port
GET /healthz                       -> readiness status only
scripts/{backup,restore}.sh        -> consistent DB + current object provider recovery set
scripts/{deploy,rollback}-digest.sh -> repository@sha256:<64 hex>
```

### 3. Contracts

- The base Compose profile is production. Local, mock login, bootstrap identities, bucket creation, and host dependency ports require an explicit overlay or environment switch.
- Production startup rejects placeholder/short database, Redis and selected-storage credentials; mock login; insecure cookies; mixed local/test profiles; `MARKET_SHOP_S3_BACKEND_MODE=bundled`; and non-HTTPS S3 origins when those integrations are enabled. Local/e2e is the only profile where bundled RustFS is allowed.
- Liveness includes only `livenessState`. Readiness includes `readinessState`, `db`, `redis`, and the selected object-storage probe. S3 health performs a read-only bucket probe; optional local RustFS bucket creation is a separate, explicitly enabled startup action.
- Object-storage readiness timeout is configured by `MARKET_SHOP_OBJECT_STORAGE_HEALTH_TIMEOUT_SECONDS` and clamped to 2–10 seconds; S3 operation calls use `MARKET_SHOP_OBJECT_STORAGE_API_TIMEOUT_SECONDS` clamped to 5–60 seconds. These limits keep a black-holed dependency from holding the public health request indefinitely.
- Production WeChat configuration binds `market-shop.production.wechat.miniprogram-app-id` / `miniprogram-secret` and `market-shop.wechat.miniprogram-app-id` / `miniprogram-secret` from `MARKET_SHOP_WECHAT_MINIPROGRAM_APP_ID` / `MARKET_SHOP_WECHAT_MINIPROGRAM_SECRET`. When WeChat is enabled in prod, both AppID and Secret must be non-placeholder values; mock login must remain false.
- Nginx exposes only `/healthz` without component details. Actuator, Swagger and OpenAPI paths return 404 publicly; complete diagnostics remain reachable only on the backend loopback port.
- A backup gracefully stops the app, and bundled RustFS for local/e2e fixtures when applicable, before the MySQL dump and object-volume snapshot. It always restores service via traps, emits SHA-256 and object-tree manifests, supports retention/age/offsite hooks, and requires an external snapshot hook for production/external S3.
- `backup.meta.object_snapshot_mode` is authoritative provenance: `local-volume`, `bundled-rustfs`, or `external-hook`. An S3 backup without an explicit mode must not be restored by guessing a local RustFS volume; external storage always uses the restore hook.
- Restore verifies the manifest before mutation, rejects non-empty or provider-mismatched targets by default, restores DB and objects, clears the selected Redis DB, runs Flyway forward validation, compares object digests, and finishes with production smoke checks.
- Deployment accepts an immutable digest, creates a pre-deploy backup, restores that backup into an isolated migration-preflight database, starts a scheduling-disabled candidate, and switches the app only after readiness/smoke pass. Migration failure never changes the active image. Rollback uses the recorded previous digest and never runs down migrations.
- Backup, restore, deploy, rollback, and standalone migration preflight compete for one fail-fast project maintenance lock. The outer owner records a random capability and PID. Only explicit `deploy -> backup`, `deploy -> preflight`, and `rollback -> backup` direct-child delegation may inherit it; a token without matching owner, operation, and delegated PPID is rejected. Every normal exit, error, INT, and TERM path releases an owned lock.
- Operational objectives are RPO at most 15 minutes and RTO at most 2 hours, verified by a quarterly empty-environment drill.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| MySQL, Redis, or selected object store is unavailable | Readiness is DOWN/503 while JVM liveness remains UP |
| Object-storage endpoint is black-holed | The bounded readiness probe returns DOWN within the configured 2–10 second window; JVM liveness remains UP |
| Public request targets Actuator, Swagger, OpenAPI, or docs | Nginx returns 404 without forwarding diagnostics |
| Production WeChat miniprogram AppID/Secret missing or placeholder while WeChat is enabled | Startup validation fails; runtime must never silently fall back to mock or localhost |
| Backup manifest or restored object digest is invalid | Stop before accepting the restored application |
| Backup metadata names an unknown/missing S3 object provenance | Stop before mutating the target unless an explicit external restore hook handles it |
| Candidate migration/readiness/smoke fails | Keep the current digest active and remove the isolated candidate |
| Any standalone mutating operation starts while another holds the maintenance lock | Fail immediately before DB, volume, image, backup, or release-state mutation |
| Deploy/rollback invokes its required backup, or deploy invokes preflight | Validate the random capability, live outer PID, exact operation pair, and direct delegated PPID; retain the outer lock |
| Caller supplies a copied/guessed capability or unsupported nesting pair | Reject it and leave the current owner's lock untouched |
| Outer owner receives INT/TERM or exits with an error | Run service recovery where applicable, then release only its own lock |

### 5. Good/Base/Bad Cases

- Good: deploy owns the project lock, delegates a pre-deploy backup and isolated preflight, verifies the candidate, then performs one digest cutover while every standalone restore fails fast.
- Good: a local/e2e RustFS-backed backup stops app and RustFS, captures one consistent DB/object set, restarts both through traps, and publishes verified manifests; a production S3 backup stays in external-hook mode even if a stray RustFS container is running.
- Base: a standalone backup owns and releases the same lock without receiving any inherited capability.
- Bad: independent `.backup.lock`, `.restore.lock`, and `.deploy.lock` directories allow restore and deploy to mutate the same project concurrently.
- Bad: trust an environment flag such as `MAINTENANCE_NESTED=true`, wait indefinitely for a lock, accept a tag instead of a digest, or cut traffic before candidate smoke.

### 6. Tests Required

- `docker compose --env-file .env.example config --quiet` and the explicit local/release variants pass.
- Parsed base config has no MySQL/Redis `ports`; parsed local config binds every such port to `127.0.0.1`.
- Backend tests cover typed production rejection (including bundled-mode rejection), capability flags, local/S3 readiness behavior, and explicit bucket initialization.
- `bash -n`, ShellCheck, executable-mode checks, operational contract tests, runtime smoke and both local/RustFS controller E2E gates pass before image publication.
- An actual concurrent shell gate holds the project lock, proves a second operation fails immediately without its mutation marker, sends TERM to the owner, and proves the lock is released. A nested gate proves only the documented parent-child pairs retain the outer lock.
- RustFS E2E reads one signed URL until it expires with 403, obtains and reads a fresh URL, deletes the object, then proves that same still-unexpired URL returns object absence.
- CI runs `git diff --check` against the pull-request range or delivered commit before compilation.
- The image job records and uploads the exact build digest used by the deployment scripts.

### 7. Wrong vs Correct

```bash
# Wrong: independent locks do not exclude a restore from a deploy.
mkdir .backup.lock
scripts/backup.sh

# Correct: every mutating entry point acquires the same project capability lock;
# the owner delegates only a named, direct child operation.
ops_acquire_maintenance_lock deploy
ops_run_maintenance_child backup scripts/backup.sh
ops_run_maintenance_child preflight scripts/migration-preflight.sh "$image" "$backup"
```

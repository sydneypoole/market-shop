import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = (path) => readFile(new URL(`../${path}`, import.meta.url), 'utf8')

test('single image contains backend and admin artifacts', async () => {
  const [dockerfile, infrastructurePom] = await Promise.all([
    source('Dockerfile'),
    source('backend/shop-infrastructure/pom.xml')
  ])

  assert.match(dockerfile, /FROM maven:3\.9-eclipse-temurin-21 AS backend-builder/)
  assert.match(dockerfile, /FROM node:22-alpine AS web-builder/)
  assert.match(dockerfile, /shop-bootstrap-0\.1\.0-SNAPSHOT\.jar/)
  assert.doesNotMatch(dockerfile, /frontend\/storefront/)
  assert.match(dockerfile, /frontend\/admin\/dist/)
  assert.match(dockerfile, /-name '\*\.map' -delete/)
  assert.match(dockerfile, /\/opt\/market-shop\/data\/uploads/)
  assert.match(dockerfile, /MARKET_SHOP_LOCAL_STORAGE_ROOT=\/opt\/market-shop\/data\/uploads/)
  assert.match(dockerfile, /USER marketshop/)
  assert.match(
    dockerfile,
    /USER marketshop\s+RUN nginx -t -c \/opt\/market-shop\/nginx\.conf/
  )
  assert.match(dockerfile, /HEALTHCHECK/)
  assert.match(infrastructurePom, /<proc>none<\/proc>/)
})

test('Mockito agent is resolved before every Surefire fork', async () => {
  const [parentPom, backendPom] = await Promise.all([
    source('pom.xml'),
    source('backend/pom.xml')
  ])
  const buildStart = parentPom.indexOf('<build>')
  const pluginManagementStart = parentPom.indexOf('<pluginManagement>', buildStart)

  assert.notEqual(buildStart, -1)
  assert.notEqual(pluginManagementStart, -1)
  const activeBuildPlugins = parentPom.slice(buildStart, pluginManagementStart)

  assert.match(parentPom, /<argLine><\/argLine>/)
  assert.match(
    activeBuildPlugins,
    /<artifactId>maven-dependency-plugin<\/artifactId>[\s\S]*<id>resolve-test-agent-paths<\/id>\s*<phase>initialize<\/phase>\s*<goals>\s*<goal>properties<\/goal>/
  )
  assert.match(
    parentPom,
    /<argLine>@\{argLine\} -Xshare:off -javaagent:@\{org\.mockito:mockito-core:jar\}<\/argLine>/
  )
  assert.doesNotMatch(parentPom, /-javaagent:\$\{settings\.localRepository\}/)

  const mockitoDependency = backendPom.match(
    /<dependency>\s*<groupId>org\.mockito<\/groupId>\s*<artifactId>mockito-core<\/artifactId>[\s\S]*?<\/dependency>/
  )
  assert.ok(mockitoDependency, 'backend parent must directly declare the Mockito agent')
  assert.match(mockitoDependency[0], /<version>\$\{mockito\.version\}<\/version>/)
  assert.match(mockitoDependency[0], /<scope>test<\/scope>/)
})

test('nginx serves admin SPA and proxies backend routes', async () => {
  const nginx = await source('deploy/nginx.conf')

  assert.match(nginx, /error_log \/dev\/stderr crit;/)
  assert.match(nginx, /access_log off;/)
  assert.doesNotMatch(nginx, /log_format/)
  assert.doesNotMatch(nginx, /access_log \/dev\/stdout/)
  assert.match(nginx, /proxy_set_header X-Request-Id \$request_id/)
  assert.match(
    nginx,
    /map \$http_cf_visitor \$market_shop_cloudflare_proto \{\s+default '';\s+'~\*\^\\\{\\s\*"scheme"\\s\*:\\s\*"https"\\s\*\\\}\$' https;\s+'~\*\^\\\{\\s\*"scheme"\\s\*:\\s\*"http"\\s\*\\\}\$' http;\s+\}/
  )
  assert.match(
    nginx,
    /map \$http_x_forwarded_proto \$market_shop_proxy_proto \{\s+default '';\s+~\*\^https\$ https;\s+~\*\^http\$ http;\s+\}/
  )
  assert.match(
    nginx,
    /map \$market_shop_cloudflare_proto \$market_shop_public_proto \{\s+default \$market_shop_proxy_proto;\s+https https;\s+http http;\s+\}/
  )
  assert.match(
    nginx,
    /map \$market_shop_cloudflare_proto \$market_shop_public_port \{\s+default \$market_shop_proxy_port;\s+https 443;\s+http 80;\s+\}/
  )
  assert.match(
    nginx,
    /map \$market_shop_public_port \$market_shop_forwarded_port \{\s+default \$market_shop_forwarded_port_default;\s+"~\^\[0-9\]\{1,5\}\$" \$market_shop_public_port;\s+\}/
  )
  assert.match(nginx, /proxy_set_header X-Forwarded-Proto \$market_shop_forwarded_proto/)
  assert.match(nginx, /proxy_set_header X-Forwarded-Port \$market_shop_forwarded_port/)
  assert.match(
    nginx,
    /proxy_set_header Forwarded "proto=\$market_shop_forwarded_proto;host=\\"\$host:\$market_shop_forwarded_port\\""/
  )
  assert.doesNotMatch(nginx, /proxy_set_header X-Forwarded-Proto \$scheme/)
  assert.doesNotMatch(nginx, /proxy_set_header X-Forwarded-Port \$server_port/)
  assert.doesNotMatch(nginx, /proxy_set_header Forwarded \$http_forwarded/)
  assert.equal(nginx.match(/proxy_set_header X-Forwarded-Proto/g)?.length, 1)
  assert.equal(nginx.match(/proxy_set_header Forwarded/g)?.length, 1)
  assert.match(nginx, /location \/api\//)
  assert.match(nginx, /proxy_pass http:\/\/market_shop_backend/)
  assert.match(nginx, /location = \/healthz/)
  assert.match(nginx, /location \^~ \/actuator\/\s*\{\s*return 404;/)
  assert.match(nginx, /location \^~ \/swagger-ui\/\s*\{\s*return 404;/)
  assert.match(nginx, /location \^~ \/admin\//)
  assert.match(nginx, /\/admin\/index\.html/)
  assert.match(nginx, /location \/\s*\{\s*return 404;/)
  assert.doesNotMatch(nginx, /try_files \$uri \$uri\/ \/index\.html/)
})

test('compose defaults to production and keeps local build explicit', async () => {
  const [compose, local] = await Promise.all([
    source('docker-compose.yml'),
    source('docker-compose.local.yml')
  ])

  assert.match(compose, /^\s{2}app:\n/m)
  assert.doesNotMatch(compose, /build:\n\s+context: \.\n\s+dockerfile: Dockerfile/)
  assert.match(compose, /SPRING_PROFILES_ACTIVE: \$\{SPRING_PROFILES_ACTIVE:-prod\}/)
  assert.match(compose, /MARKET_SHOP_IMAGE:\?MARKET_SHOP_IMAGE must be an immutable image digest/)
  assert.match(compose, /MARKET_SHOP_DB_URL: jdbc:mysql:\/\/mysql:3306\//)
  assert.match(compose, /MARKET_SHOP_REDIS_HOST: redis/)
  assert.match(compose, /MARKET_SHOP_LOG_ANSI: \$\{MARKET_SHOP_LOG_ANSI:-ALWAYS\}/)
  assert.match(compose, /MARKET_SHOP_LOG_LEVEL: \$\{MARKET_SHOP_LOG_LEVEL:-INFO\}/)
  assert.match(compose, /MARKET_SHOP_APP_LOG_LEVEL: \$\{MARKET_SHOP_APP_LOG_LEVEL:-INFO\}/)
  assert.match(compose, /MARKET_SHOP_STORAGE_PROVIDER: \$\{MARKET_SHOP_STORAGE_PROVIDER:-local\}/)
  assert.match(compose, /market-shop-uploads:\/opt\/market-shop\/data\/uploads/)
  assert.match(compose, /condition: service_healthy/)
  assert.match(compose, /rustfs:\n\s+condition: service_healthy\n\s+required: false/)
  assert.match(compose, /profiles: \["rustfs"\]/)
  assert.match(compose, /rustfs\.localhost/)
  // Compose comments are part of the operator-facing contract; allow the
  // local overlay to document each build key without weakening the assertion.
  assert.match(local, /build:\n(?:\s+#.*\n)*\s+context: \.\n(?:\s+#.*\n)*\s+dockerfile: Dockerfile/)
  assert.match(local, /SPRING_PROFILES_ACTIVE: local/)
  assert.match(local, /127\.0\.0\.1:\$\{MARKET_SHOP_DB_PORT:-3308\}:3306/)
  assert.match(local, /127\.0\.0\.1:\$\{MARKET_SHOP_REDIS_PORT:-6380\}:6379/)
})

test('spring console logs are readable, colorful and configurable', async () => {
  const application = await source('backend/shop-bootstrap/src/main/resources/application.yml')

  assert.match(application, /enabled: \$\{MARKET_SHOP_LOG_ANSI:ALWAYS\}/)
  assert.match(application, /root: \$\{MARKET_SHOP_LOG_LEVEL:INFO\}/)
  assert.match(application, /com\.marketshop: \$\{MARKET_SHOP_APP_LOG_LEVEL:INFO\}/)
  assert.match(application, /console: "%clr\(%d\{yyyy-MM-dd HH:mm:ss\.SSS\}\)/)
  assert.match(application, /%clr\(%-5level\)/)
})

test('workflow tests and publishes multi-platform images to GHCR', async () => {
  const [workflow, packageJsonSource] = await Promise.all([
    source('.github/workflows/docker-image.yml'),
    source('package.json')
  ])
  const packageJson = JSON.parse(packageJsonSource)

  assert.match(workflow, /packages: write/)
  assert.match(workflow, /REGISTRY: ghcr\.io/)
  assert.match(workflow, /docker\/build-push-action@v7/)
  assert.match(workflow, /platforms: linux\/amd64,linux\/arm64/)
  assert.match(workflow, /push: \$\{\{ github\.event_name != 'pull_request' \}\}/)
  assert.match(workflow, /password: \$\{\{ secrets\.GITHUB_TOKEN \}\}/)
  assert.match(workflow, /runtime-smoke:/)
  assert.match(workflow, /docker-compose\.local\.yml/)
  assert.match(workflow, /--env-file \.env\.local\.example/)
  assert.match(workflow, /bash scripts\/runtime-smoke\.sh/)
  assert.match(workflow, /Test WeChat miniprogram static and consumer contracts/)
  assert.match(workflow, /pnpm test:miniprogram/)
  assert.match(workflow, /MARKET_SHOP_EXPECT_MINIPROGRAM_MOCK_LOGIN: "true"/)
  assert.match(workflow, /bash scripts\/business-e2e\.sh/)
  assert.match(workflow, /shellcheck scripts\/\*\.sh scripts\/ops\/\*\.sh/)
  assert.match(workflow, /rustfs-integration:/)
  assert.match(workflow, /RustFS controller E2E/)
  assert.match(workflow, /image:\n[\s\S]*?needs:\n\s+- quality\n/)
  assert.match(workflow, /- runtime-smoke/)
  assert.match(workflow, /- rustfs-integration/)
  assert.match(workflow, /steps\.build\.outputs\.digest/)
  assert.match(workflow, /name: image-digest/)
  assert.equal(packageJson.scripts['test:miniprogram'], 'node --test miniprogram/tests/*.test.mjs')
  assert.match(packageJson.scripts.test, /pnpm test:miniprogram/)
})

test('runtime smoke verifies empty-database startup and critical public contracts', async () => {
  const smoke = await source('scripts/runtime-smoke.sh')

  assert.match(smoke, /\/healthz/)
  assert.match(smoke, /\/admin\//)
  assert.doesNotMatch(smoke, /assert_get "\/"/)
  assert.match(smoke, /api\/v1\/auth\/wechat\/miniprogram\/login/)
  assert.match(smoke, /"market-shop-user-token: \$\{miniprogram_token\}"/)
  assert.match(smoke, /api\/v1\/auth\/me/)
  assert.match(smoke, /payload\.get\("data"\).*\.get\("token"\)/s)
  assert.match(smoke, /Do not use a cookie jar here/)
  assert.match(smoke, /api\/v1\/catalog\/products\/1/)
  assert.match(smoke, /"skus":\[/)
  assert.match(smoke, /x-request-id/i)
  assert.match(smoke, /WECHAT_DISABLED/)
})

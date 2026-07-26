import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = (path) => readFile(new URL(`../${path}`, import.meta.url), 'utf8')

test('single image contains backend, storefront and admin artifacts', async () => {
  const [dockerfile, infrastructurePom] = await Promise.all([
    source('Dockerfile'),
    source('backend/shop-infrastructure/pom.xml')
  ])

  assert.match(dockerfile, /FROM maven:3\.9-eclipse-temurin-21 AS backend-builder/)
  assert.match(dockerfile, /FROM node:22-alpine AS web-builder/)
  assert.match(dockerfile, /shop-bootstrap-0\.1\.0-SNAPSHOT\.jar/)
  assert.match(dockerfile, /frontend\/storefront\/dist/)
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

test('nginx serves both SPAs and proxies backend routes', async () => {
  const nginx = await source('deploy/nginx.conf')

  assert.match(nginx, /log_format market_shop_json escape=json/)
  assert.match(nginx, /"request_id":"\$request_id"/)
  assert.match(nginx, /"uri":"\$uri"/)
  assert.match(nginx, /"request_time":\$request_time/)
  assert.match(nginx, /"upstream_response_time":"\$upstream_response_time"/)
  assert.match(nginx, /proxy_set_header X-Request-Id \$request_id/)
  assert.match(
    nginx,
    /map \$http_x_forwarded_proto \$market_shop_forwarded_proto \{\s+default \$scheme;\s+~\*\^https\$ https;\s+~\*\^http\$ http;\s+\}/
  )
  assert.match(
    nginx,
    /map \$http_x_forwarded_proto \$market_shop_forwarded_port_default \{\s+default \$server_port;\s+~\*\^https\$ 443;\s+~\*\^http\$ 80;\s+\}/
  )
  assert.match(
    nginx,
    /map \$http_x_forwarded_port \$market_shop_forwarded_port \{\s+default \$market_shop_forwarded_port_default;\s+"~\^\[0-9\]\{1,5\}\$" \$http_x_forwarded_port;\s+\}/
  )
  assert.match(nginx, /proxy_set_header X-Forwarded-Proto \$market_shop_forwarded_proto/)
  assert.match(nginx, /proxy_set_header X-Forwarded-Port \$market_shop_forwarded_port/)
  assert.doesNotMatch(nginx, /proxy_set_header X-Forwarded-Proto \$scheme/)
  assert.doesNotMatch(nginx, /proxy_set_header X-Forwarded-Port \$server_port/)
  assert.equal(nginx.match(/proxy_set_header X-Forwarded-Proto/g)?.length, 1)
  assert.match(nginx, /location \/api\//)
  assert.match(nginx, /proxy_pass http:\/\/market_shop_backend/)
  assert.match(nginx, /location \^~ \/admin\//)
  assert.match(nginx, /\/admin\/index\.html/)
  assert.match(nginx, /try_files \$uri \$uri\/ \/index\.html/)
})

test('compose starts the complete local-storage stack and keeps RustFS optional', async () => {
  const compose = await source('docker-compose.yml')

  assert.match(compose, /^\s{2}app:\n/m)
  assert.match(compose, /build:\n\s+context: \.\n\s+dockerfile: Dockerfile/)
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
  const workflow = await source('.github/workflows/docker-image.yml')

  assert.match(workflow, /packages: write/)
  assert.match(workflow, /REGISTRY: ghcr\.io/)
  assert.match(workflow, /docker\/build-push-action@v7/)
  assert.match(workflow, /platforms: linux\/amd64,linux\/arm64/)
  assert.match(workflow, /push: \$\{\{ github\.event_name != 'pull_request' \}\}/)
  assert.match(workflow, /password: \$\{\{ secrets\.GITHUB_TOKEN \}\}/)
})

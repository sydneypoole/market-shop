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
  assert.match(dockerfile, /USER marketshop/)
  assert.match(dockerfile, /HEALTHCHECK/)
  assert.match(infrastructurePom, /<proc>none<\/proc>/)
})

test('nginx serves both SPAs and proxies backend routes', async () => {
  const nginx = await source('deploy/nginx.conf')

  assert.match(nginx, /location \/api\//)
  assert.match(nginx, /proxy_pass http:\/\/market_shop_backend/)
  assert.match(nginx, /location \^~ \/admin\//)
  assert.match(nginx, /\/admin\/index\.html/)
  assert.match(nginx, /try_files \$uri \$uri\/ \/index\.html/)
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

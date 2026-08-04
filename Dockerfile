# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-21 AS backend-builder
WORKDIR /workspace

COPY pom.xml ./
COPY backend ./backend
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -f backend/pom.xml -DskipTests package

FROM node:22-alpine AS web-builder
WORKDIR /workspace

RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY frontend/admin/package.json frontend/admin/package.json
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile

COPY frontend ./frontend
RUN pnpm build:container:web \
    && find frontend/admin/dist -type f -name '*.map' -delete

FROM eclipse-temurin:21-jre-jammy AS runtime

ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl nginx supervisor tini \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 marketshop \
    && useradd --system --uid 10001 --gid 10001 --home-dir /opt/market-shop marketshop \
    && mkdir -p /opt/market-shop/web/admin /opt/market-shop/data/uploads /tmp/nginx \
    && chown -R marketshop:marketshop /opt/market-shop /tmp/nginx
RUN chown -R marketshop:marketshop /var/log/nginx

WORKDIR /opt/market-shop

COPY --from=backend-builder --chown=marketshop:marketshop \
    /workspace/backend/shop-bootstrap/target/shop-bootstrap-0.1.0-SNAPSHOT.jar \
    /opt/market-shop/app.jar
COPY --from=web-builder --chown=marketshop:marketshop \
    /workspace/frontend/admin/dist/ \
    /opt/market-shop/web/admin/
COPY --chown=marketshop:marketshop deploy/nginx.conf /opt/market-shop/nginx.conf
COPY --chown=marketshop:marketshop deploy/supervisord.conf /opt/market-shop/supervisord.conf
USER marketshop
RUN nginx -t -c /opt/market-shop/nginx.conf

ENV MARKET_SHOP_SERVER_PORT=8081 \
    MARKET_SHOP_LOCAL_STORAGE_ROOT=/opt/market-shop/data/uploads \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/healthz || exit 1

STOPSIGNAL SIGTERM
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["/usr/bin/supervisord", "-c", "/opt/market-shop/supervisord.conf"]

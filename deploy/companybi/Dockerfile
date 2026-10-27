# Use Docker's bundled BuildKit frontend (Docker >= 24); no external syntax image is needed.
#
# Company BI single-node image (DataEase standalone profile + company extensions).
#
# Build context MUST be the repository root and MUST come from a clean checkout:
#   docker build -f deploy/companybi/Dockerfile .
# Use deploy/companybi/scripts/build.sh, which also stamps the release metadata.
#
# Everything (npm, Maven) runs inside the build stages, so the build host needs
# only Docker. Nothing from the developer's node_modules / target / ~/.m2 is used.

ARG NODE_IMAGE=node:22-bookworm
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG RUNTIME_BASE=eclipse-temurin:21-jre-noble

# --------------------------------------------------------------------------
# Stage 1: Vue frontend. Standalone build is `build:base`, NOT `build:distributed`
# (core-frontend/pom.xml hard-wires `build:distributed`; we never run that pom).
# --------------------------------------------------------------------------
FROM ${NODE_IMAGE} AS frontend
ARG NPM_REGISTRY=
WORKDIR /build/core/core-frontend
COPY core/core-frontend/package.json core/core-frontend/package-lock.json core/core-frontend/.npmrc ./
RUN --mount=type=cache,target=/root/.npm \
    if [ -n "$NPM_REGISTRY" ]; then npm config set registry "$NPM_REGISTRY"; fi; \
    npm ci --no-audit --no-fund
COPY core/core-frontend/ ./
RUN npm run build:base \
 && test -s dist/index.html \
 && test -s dist/panel.html

# --------------------------------------------------------------------------
# Stage 2: Spring Boot jar (default `standalone` profile) with the frontend inside.
# --------------------------------------------------------------------------
FROM ${MAVEN_IMAGE} AS backend
ARG MAVEN_CENTRAL_MIRROR=https://repo.maven.apache.org/maven2
WORKDIR /build
COPY pom.xml ./
COPY sdk sdk
COPY core/pom.xml core/pom.xml
COPY deploy/companybi/image /build/image-tools

# sdk modules (+ root pom) into the local repo, like the official `mvn clean install`.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -s /build/image-tools/maven-settings.xml -f pom.xml install -DskipTests -Dmaven.test.skip=true

# Backend edits do not invalidate the SDK installation layer.
COPY core/core-backend core/core-backend
# Replace the development datasource before compiling any backend resources.
RUN sh /build/image-tools/sanitize-standalone-yml.sh \
        core/core-backend/src/main/resources/application-standalone.yml

# core/pom.xml lists core-frontend as a module, so its pom must exist for the reactor to load
# (it is NOT built here: `-pl core-backend`; the frontend was built in stage 1).
COPY core/core-frontend/pom.xml core/core-frontend/pom.xml
# antrun copies ../core-frontend/dist into src/main/resources/static (standalone profile).
COPY --from=frontend /build/core/core-frontend/dist core/core-frontend/dist
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -s /build/image-tools/maven-settings.xml -f core/pom.xml -pl core-backend -P standalone package \
        -DskipTests -Dmaven.test.skip=true

# Hard gate: fail the image build if the jar is missing frontend / company code /
# migrations, or still carries the datasource secret. Also emits SCHEMA + JAR.SHA256.
RUN sh /build/image-tools/verify-jar.sh \
        core/core-backend/target/CoreApplication.jar \
        core/core-frontend/dist \
        /build/release-info

# --------------------------------------------------------------------------
# Stage 3: bundled internal executor. No .env, local tmp, or development scripts.
# --------------------------------------------------------------------------
FROM ${NODE_IMAGE} AS integration
WORKDIR /worker
COPY feishu-integration/package.json feishu-integration/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --omit=dev --ignore-scripts --no-audit --no-fund
COPY feishu-integration/src/capture.mjs feishu-integration/src/config.mjs \
     feishu-integration/src/contact.mjs feishu-integration/src/db.mjs \
     feishu-integration/src/delivery-lane.mjs feishu-integration/src/etl-status.mjs \
     feishu-integration/src/execution-mode.mjs feishu-integration/src/feishu.mjs \
     feishu-integration/src/run-subscription.mjs feishu-integration/src/schedule.mjs \
     feishu-integration/src/scheduler.mjs feishu-integration/src/service-log.mjs \
     feishu-integration/src/session.mjs feishu-integration/src/store.mjs \
     feishu-integration/src/managed-connection.mjs feishu-integration/src/embedded-worker.mjs ./src/

# --------------------------------------------------------------------------
# Stage 4: runtime (Java owns the internal executor lifecycle)
# --------------------------------------------------------------------------
FROM ${RUNTIME_BASE} AS local-runtime

COPY --from=integration /usr/local/bin/node /usr/local/bin/node
COPY --from=integration /worker/node_modules /opt/apps/integration-worker/node_modules
COPY --from=integration /worker/package.json /opt/apps/integration-worker/package.json
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/apps/integration-browsers
RUN apt-get update && apt-get install -y --no-install-recommends wget ca-certificates fonts-noto-cjk \
 && node /opt/apps/integration-worker/node_modules/playwright/cli.js install --with-deps chromium \
 && rm -rf /var/lib/apt/lists/*
COPY --from=integration /worker/src /opt/apps/integration-worker/src

# The local development launcher mounts its separately compiled Java jar.
# Production continues below and includes the frontend and backend in this same runtime.
FROM local-runtime AS runtime

ARG COMPANYBI_VERSION=unversioned
ARG GIT_SHA=unknown
ARG GIT_BRANCH=unknown
ARG GIT_DIRTY=unknown
ARG BUILD_TIME=unknown
ARG DATAEASE_VERSION=unknown

STOPSIGNAL SIGTERM

# Image-owned static content lives under seed/ so that the persistent data volume
# can be mounted over /opt/dataease2.0/data without hiding it. The entrypoint
# syncs seed/ -> data/ on every start.
RUN mkdir -p /opt/dataease2.0/drivers \
             /opt/dataease2.0/seed/map-origin \
             /opt/dataease2.0/seed/static-resource \
             /opt/apps/release
COPY drivers/ /opt/dataease2.0/drivers/
COPY staticResource/ /opt/dataease2.0/seed/static-resource/
COPY mapFiles/ /opt/dataease2.0/seed/map-origin/

WORKDIR /opt/apps
COPY --from=backend /build/core/core-backend/target/CoreApplication.jar /opt/apps/app.jar
COPY --from=backend /build/release-info/ /opt/apps/release/
COPY deploy/companybi/schema-compat.txt /opt/apps/release/COMPAT
COPY deploy/companybi/image/docker-entrypoint.sh /opt/apps/docker-entrypoint.sh
RUN chmod 0755 /opt/apps/docker-entrypoint.sh \
 && { echo "version=${COMPANYBI_VERSION}"; \
      echo "dataease_version=${DATAEASE_VERSION}"; \
      echo "git_sha=${GIT_SHA}"; \
      echo "git_branch=${GIT_BRANCH}"; \
      echo "git_dirty=${GIT_DIRTY}"; \
      echo "build_time=${BUILD_TIME}"; \
      echo "jar_sha256=$(cut -d' ' -f1 /opt/apps/release/JAR.SHA256)"; \
      echo "schema_fingerprint=$(sed -n 's/^schema.fingerprint=//p' /opt/apps/release/SCHEMA)"; \
    } > /opt/apps/release/RELEASE

LABEL org.opencontainers.image.title="companybi" \
      org.opencontainers.image.version="${COMPANYBI_VERSION}" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.created="${BUILD_TIME}" \
      com.companybi.version="${COMPANYBI_VERSION}" \
      com.companybi.git-sha="${GIT_SHA}" \
      com.companybi.git-dirty="${GIT_DIRTY}" \
      com.companybi.dataease-version="${DATAEASE_VERSION}"

ENV JAVA_APP_JAR=/opt/apps/app.jar \
    RUNNING_PORT=8100 \
    JAVA_OPTIONS="-Dfile.encoding=utf-8 -Dloader.path=/opt/apps"

EXPOSE 8100

# Cheap liveness only; the real readiness gate is scripts/health-check.sh.
HEALTHCHECK --interval=15s --timeout=5s --retries=20 --start-period=120s \
    CMD wget -q -T 4 -O /dev/null http://127.0.0.1:8100/ || exit 1

ENTRYPOINT ["/opt/apps/docker-entrypoint.sh"]

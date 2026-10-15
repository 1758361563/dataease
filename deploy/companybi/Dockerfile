# syntax=docker/dockerfile:1.7
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
ARG RUNTIME_BASE=registry.cn-qingdao.aliyuncs.com/dataease/alpine-openjdk21-jre

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
WORKDIR /build
COPY pom.xml ./
COPY sdk sdk
COPY core/pom.xml core/pom.xml
COPY core/core-backend core/core-backend
COPY deploy/companybi/image /build/image-tools

# The committed application-standalone.yml contains a real remote datasource
# (host + root credentials). Replace it with required placeholders before
# compiling; the script fails the build if the file shape ever changes.
RUN sh /build/image-tools/sanitize-standalone-yml.sh \
        core/core-backend/src/main/resources/application-standalone.yml

# sdk modules (+ root pom) into the local repo, like the official `mvn clean install`.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -f pom.xml install -DskipTests -Dmaven.test.skip=true

# core/pom.xml lists core-frontend as a module, so its pom must exist for the reactor to load
# (it is NOT built here: `-pl core-backend`; the frontend was built in stage 1).
COPY core/core-frontend/pom.xml core/core-frontend/pom.xml
# antrun copies ../core-frontend/dist into src/main/resources/static (standalone profile).
COPY --from=frontend /build/core/core-frontend/dist core/core-frontend/dist
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -f core/pom.xml -pl core-backend -P standalone package \
        -DskipTests -Dmaven.test.skip=true

# Hard gate: fail the image build if the jar is missing frontend / company code /
# migrations, or still carries the datasource secret. Also emits SCHEMA + JAR.SHA256.
RUN sh /build/image-tools/verify-jar.sh \
        core/core-backend/target/CoreApplication.jar \
        core/core-frontend/dist \
        /build/release-info

# --------------------------------------------------------------------------
# Stage 3: runtime
# --------------------------------------------------------------------------
FROM ${RUNTIME_BASE} AS runtime

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

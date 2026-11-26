# hugmesh-tuwunel-web: the front door of a standalone Tuwunel. One image holds the Spring Boot service and
# the built Mastodon web UI. Build context is this directory (web/):
#   docker buildx build -t hugmesh-tuwunel-web:local web
#
# The UI is built here from hugmesh-matrix-ui at a PINNED COMMIT (UI_REF), the same way Tuwunel is built
# from a pinned upstream commit: the build fails if the fetched commit is not the pinned one.
#
# syntax=docker/dockerfile:1

ARG NODE_IMAGE=node:24-alpine@sha256:116b800c3754b440ee0aa3ed2f20f5bf3d99a554bfd9adeea09ea8017f6e85f5
ARG JDK_IMAGE=eclipse-temurin:25-jdk-noble@sha256:8599ea2d8f71696f00dcdf46213a4b65fa97ceeaa318671fccba6b0af2727792
ARG JRE_IMAGE=eclipse-temurin:25-jre-noble@sha256:3c6d7b40c2e78d4a8f4fe00af8165c706b34faf672dc21ff0924e583559ec6c2

# ---- the web UI -------------------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM ${NODE_IMAGE} AS ui
ARG UI_REPO=https://github.com/hugmesh/hugmesh-matrix-ui.git
ARG UI_REF=cfce86d6a894b26937d86e3e0a617b6fafc0cc5c
RUN apk add --no-cache git
WORKDIR /ui
RUN git init -q . \
 && git fetch -q --depth 1 "$UI_REPO" "$UI_REF" \
 && git checkout -q FETCH_HEAD \
 && test "$(git rev-parse HEAD)" = "$UI_REF" \
 || { echo "the fetched UI commit is not the pinned $UI_REF"; exit 1; }
RUN npm ci --no-audit --no-fund
RUN npm run build:standalone \
 && test -f dist/standalone/browser/index.html

# ---- the service ------------------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM ${JDK_IMAGE} AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
# Maven Central is fetched over the network on a CI runner and rate-limits (HTTP 429) shared runner addresses, so retry with a
# growing wait (up to ~7 minutes in all), and keep the output.
RUN for wait in 0 20 60 120 240; do sleep "$wait"; ./mvnw -B -ntp dependency:go-offline && exit 0; echo "dependency fetch failed; backing off"; done; exit 1
COPY src src
RUN for wait in 0 20 60 120; do sleep "$wait"; ./mvnw -B -ntp -DskipTests package && break; echo "package failed; backing off"; done \
 && mv target/hugmesh-tuwunel-web-*.jar /app.jar

# ---- runtime ----------------------------------------------------------------------------------
FROM ${JRE_IMAGE}
ARG VERSION=0.5.1-beta.1
LABEL org.opencontainers.image.title="hugmesh-tuwunel-web" \
      org.opencontainers.image.description="Front door for a standalone Tuwunel: Matrix web UI, sign-in with a Matrix account, Matrix API proxy" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.version="${VERSION}"
RUN apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && install -d -o 1000 -g 1000 /var/lib/hugmesh-web   # uid 1000 is the base image's own unprivileged user
COPY --from=build /app.jar /app/app.jar
COPY --from=ui /ui/dist/standalone/browser /app/ui
ENV HUGMESH_WEB_UI_DIR=/app/ui \
    HUGMESH_WEB_STATE_DIR=/var/lib/hugmesh-web \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
USER 1000:1000
EXPOSE 8080
VOLUME ["/var/lib/hugmesh-web"]
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

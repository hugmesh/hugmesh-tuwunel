# hugmesh-tuwunel: Tuwunel (a Matrix homeserver written in Rust) built from upstream source at a PINNED COMMIT.
# Upstream publishes images, but a HugMesh deployment wants one built, pinned, signed and attested by us
# (so what it runs is what the release workflow built from a named commit, and the image can carry our hardening).
#
#   docker buildx build -t hugmesh-tuwunel:local .
#
# The build fetches upstream by commit id and FAILS if the fetched commit is not the pinned one, or if the
# workspace version is not TUWUNEL_VERSION. check-upstream-pin.sh refuses a build whose upstream tag has moved.
# syntax=docker/dockerfile:1

ARG RUST_IMAGE=rust:1.94.0-trixie@sha256:f17e723020f87c1b4ac4ff6d73c9dfbb7d5cb978754c76641e47337d65f61e12
ARG RUNTIME_IMAGE=debian:trixie-slim@sha256:8aa5a7bf5a1955bddbe7d74fcdd0b91ab30e74362a8b73af672b0481b35b44a0

# ---- build --------------------------------------------------------------------------------------------
FROM ${RUST_IMAGE} AS build
ARG TUWUNEL_GIT_URL=https://github.com/matrix-construct/tuwunel.git
ARG TUWUNEL_VERSION=1.7.0
ARG TUWUNEL_COMMIT=01f56a9d2a7d2a3cdf4d32609edb1963cd72410e
# Parallel compile jobs; empty = all cores. RocksDB and the final link are memory hungry: lower this on a small host.
ARG CARGO_JOBS=

# clang/libclang: bindgen; cmake, g++: RocksDB and aws-lc; liburing-dev: RocksDB's io_uring
RUN apt-get update \
 && apt-get install -y --no-install-recommends clang libclang-dev cmake pkg-config g++ make git ca-certificates liburing-dev \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /src
RUN git init -q . \
 && git fetch -q --depth 1 "$TUWUNEL_GIT_URL" "$TUWUNEL_COMMIT" \
 && git checkout -q FETCH_HEAD \
 && test "$(git rev-parse HEAD)" = "$TUWUNEL_COMMIT" \
 && grep -qx "version = \"$TUWUNEL_VERSION\"" Cargo.toml \
 || { echo "the fetched upstream is not the pinned commit $TUWUNEL_COMMIT at version $TUWUNEL_VERSION"; exit 1; }

# The default feature set is what upstream ships (jemalloc, io_uring, zstd/brotli/gzip, url previews, element hacks).
# --locked: Cargo.lock is upstream's, so every dependency is the one upstream tested.
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/usr/local/cargo/git \
    if [ -n "$CARGO_JOBS" ]; then export CARGO_BUILD_JOBS="$CARGO_JOBS"; fi; \
    cargo build --release --locked -p tuwunel \
 && install -D target/release/tuwunel /out/tuwunel \
 && /out/tuwunel --version

# ---- runtime ------------------------------------------------------------------------------------------
FROM ${RUNTIME_IMAGE}
ARG TUWUNEL_VERSION=1.7.0
ARG TUWUNEL_COMMIT=01f56a9d2a7d2a3cdf4d32609edb1963cd72410e
ARG TUWUNEL_GIT_URL=https://github.com/matrix-construct/tuwunel.git
LABEL org.opencontainers.image.title="hugmesh-tuwunel" \
      org.opencontainers.image.description="Tuwunel (Matrix homeserver) built from upstream source at a pinned commit" \
      org.opencontainers.image.licenses="Apache-2.0" \
      net.hugmesh.tuwunel.upstream-version="${TUWUNEL_VERSION}" \
      net.hugmesh.tuwunel.upstream-commit="${TUWUNEL_COMMIT}" \
      net.hugmesh.tuwunel.upstream-source="${TUWUNEL_GIT_URL}"

# liburing2: the io_uring feature; curl: the HEALTHCHECK; ca-certificates: federation and URL previews over TLS
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl liburing2 \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --gid 1000 tuwunel && useradd --uid 1000 --gid 1000 --no-create-home --home-dir /var/lib/tuwunel --shell /usr/sbin/nologin tuwunel \
 && install -d -o 1000 -g 1000 -m 0750 /var/lib/tuwunel

COPY --from=build /out/tuwunel /usr/local/bin/tuwunel

# Tuwunel reads TUWUNEL_* environment variables as its configuration (see tuwunel-example.toml in upstream), and an
# optional file named by TUWUNEL_CONFIG. The listen port matches what HugMesh's cluster manifests use (8008).
ENV TUWUNEL_DATABASE_PATH=/var/lib/tuwunel \
    TUWUNEL_ADDRESS=0.0.0.0 \
    TUWUNEL_PORT=8008
USER 1000:1000
WORKDIR /var/lib/tuwunel
VOLUME ["/var/lib/tuwunel"]
EXPOSE 8008
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8008/_matrix/client/versions || exit 1
ENTRYPOINT ["/usr/local/bin/tuwunel"]

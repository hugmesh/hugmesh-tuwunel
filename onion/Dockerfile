# hugmesh-onion: a Tor daemon that does exactly one thing for a standalone HugMesh stack: mint a v3 onion service and
# forward it to one backend. Built by compose (compose/docker-compose.onion.yml) from the pinned Debian below.
# syntax=docker/dockerfile:1
FROM debian:trixie-slim@sha256:8aa5a7bf5a1955bddbe7d74fcdd0b91ab30e74362a8b73af672b0481b35b44a0
RUN apt-get update \
 && apt-get install -y --no-install-recommends tor curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --gid 1000 onion && useradd --uid 1000 --gid 1000 --no-create-home --shell /usr/sbin/nologin onion \
 && install -d -o 1000 -g 1000 -m 0700 /var/lib/tor-hs
COPY --chmod=0755 entrypoint.sh /usr/local/bin/onion-entrypoint
USER 1000:1000
ENTRYPOINT ["/usr/local/bin/onion-entrypoint"]

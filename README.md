# hugmesh-tuwunel

[Tuwunel](https://github.com/matrix-construct/tuwunel) (a Matrix homeserver written in Rust) built from upstream source at a pinned
commit and published as `ghcr.io/hugmesh/hugmesh-tuwunel` (signed, attested, linux/amd64 and linux/arm64), together with a small
web front door, `ghcr.io/hugmesh/hugmesh-tuwunel-web`, that serves a Matrix web UI. It runs **on its own** (see
[Run it standalone](#run-it-standalone)), **as a Tor onion service only** (see [below](#run-it-as-a-tor-onion-service-only)), and
**inside HugMesh**, from the same images.

## Run it standalone

```
cd compose
cp .env.example .env            # the Matrix server name and a registration token
docker compose up -d
./register-user.sh alice         # creates an account with the registration token
```

Then open `http://localhost:8080` (or your host behind a TLS reverse proxy) and sign in. Three things run:

- **`ghcr.io/hugmesh/hugmesh-tuwunel`** is the homeserver (this repository's root).
- **`ghcr.io/hugmesh/hugmesh-tuwunel-web`** is the front door ([`web/`](web/)): a Spring Boot service that serves the Matrix web UI,
  signs people in with their Matrix username and password, keeps each access token on the server (the browser holds only an opaque
  HttpOnly cookie), and proxies the Matrix client API. It also answers for the homeserver on the public host, so one
  reverse-proxy rule, everything to the web service, is all a public server needs: other Matrix clients (Element and the like),
  federation and the well-known files still reach Tuwunel.
- **No database to run:** Tuwunel keeps its data in an embedded RocksDB, in the `data` volume.

The Matrix **server name** (the part after the colon in `@user:server`) cannot be changed once the server has data. Choose it first.
`compose/docker-compose.yml` starts everything with the hardening the HugMesh cluster applies: read-only root filesystems, no
capabilities, `no-new-privileges`. Only the web service is published, on `127.0.0.1`.

The web UI is [hugmesh-matrix-ui](https://github.com/hugmesh/hugmesh-matrix-ui)'s standalone build (`npm run build:standalone`),
built into the web image from a pinned commit (`UI_REF` in `web/Dockerfile`).

## Run it as a Tor onion service only

For a server reachable **only** through Tor, with an address it creates for itself:

```
cd compose
cp .env.onion.example .env      # a registration token; there is no host name to choose
docker compose -f docker-compose.onion.yml up -d --build
./onion-address.sh              # prints http://<56 characters>.onion (open it in Tor Browser)
./register-user.sh alice docker-compose.onion.yml
```

A small `tor` service (built from `onion/`, Debian's Tor) mints a v3 onion key on first start, keeps it in the `tor-private` volume,
and forwards the onion's port 80 to the web service. Only the public hostname is shared with the other services; the secret key is
mounted nowhere else. **The Matrix server name is the onion address**, so accounts look like `@alice:<address>.onion`, and losing the
key volume loses the names with it: back `tor-private` up.

Checked on a real run (a separate Tor client fetched `/_matrix/client/versions` through the Tor network, and a browser through it
saw the sign-in form, was refused with a wrong password, signed in, loaded rooms and synced): nothing is published on the host; the
application network is `internal`, so the homeserver and web service have no route to the internet; federation is off. The cost is
deliberate: an onion has no TLS or DNS, so a Matrix server cannot federate over it, and there are no URL previews. It is a private
server for the people who have its address.

## Run it inside HugMesh

HugMesh's cluster manifests run Tuwunel from `specs/cluster/manifests/tuwunel` in
[hugmesh-generator](https://github.com/hugmesh/hugmesh-generator) and currently pin the upstream image. This image keeps the contract
they rely on (`TUWUNEL_*` environment configuration, port 8008, data in `/var/lib/tuwunel`, uid 1000), so moving a mesh to it is a
change of image reference.

## What the image is

- **Built from source** at the commit named in the Dockerfile (`TUWUNEL_COMMIT`, which is tag `v1.7.0`), with upstream's own
  `Cargo.lock` (`--locked`) and default feature set. The build fails if the fetched commit is not the pinned one or if the workspace
  version is not `TUWUNEL_VERSION`; `check-upstream-pin.sh` refuses a build whose upstream tag has moved off the pinned commit.
  Toolchain and runtime bases are pinned by digest.
- **Debian-based, dynamically linked.** Upstream's own image is a static build; this one trades that for a stock, auditable base.
- **Unprivileged:** runs as uid 1000, no capabilities needed; the root filesystem can be read-only (data lives in `/var/lib/tuwunel`).
- **Configured by environment** (`TUWUNEL_SERVER_NAME`, `TUWUNEL_ALLOW_REGISTRATION`, ...; see
  [upstream's configuration docs](https://tuwunel.chat/configuration.html)) or by a file named by `TUWUNEL_CONFIG`.
- **HEALTHCHECK:** `curl` on `/_matrix/client/versions`.

## Testing locally

```
docker buildx build -t hugmesh-tuwunel:local .            # Rust + RocksDB: tens of minutes; --build-arg CARGO_JOBS=4 limits memory
bash smoke-test.sh hugmesh-tuwunel:local 1.7.0
docker buildx build -t hugmesh-tuwunel-web:local web
(cd web && ./mvnw verify) && bash web/smoke-test.sh hugmesh-tuwunel-web:local
bash scripts/test-release-channel.sh
WORKFLOW=.github/workflows/release-web.yml VFILE=web/VERSION TAG_PREFIX=web-v bash scripts/test-release-channel.sh
```

`smoke-test.sh` uses throwaway Docker resources only. It checks that the image runs as uid 1000, reports the expected version,
serves the client API with a read-only root and no capabilities, turns healthy, registers a user with a registration token, logs
in, and that the access token identifies the user.

## Releasing

Tags are `vX.Y.Z-beta.N` (beta, from `main`) for the homeserver image and `web-vX.Y.Z-beta.N` for the web image; `VERSION` and
`web/VERSION` are the images' versions, not Tuwunel's. The channel comes from the tag
([`scripts/release-channel.sh`](scripts/release-channel.sh)). [`release.yml`](.github/workflows/release.yml) builds each
architecture natively (an arm64 runner for arm64: compiling RocksDB under emulation would exceed a job's six hours), pushes each by
digest, boot-tests that exact image on the machine that built it, merges the two into one tagged multi-arch image, then signs it
(cosign keyless) and attests it (SLSA provenance and an SPDX SBOM). Pull requests build and boot-test and push nothing.

```
cosign verify ghcr.io/hugmesh/hugmesh-tuwunel@sha256:<digest> \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github.com/hugmesh/hugmesh-tuwunel/\.github/workflows/release\.yml@refs/tags/v'
gh attestation verify oci://ghcr.io/hugmesh/hugmesh-tuwunel@sha256:<digest> --repo hugmesh/hugmesh-tuwunel
```

## License

Tuwunel is Apache-2.0, and so is this repository: the `LICENSE` file is upstream's. The images carry the labels
`net.hugmesh.tuwunel.upstream-*` naming the exact upstream version, commit and source.

#!/usr/bin/env bash
# Boots a built hugmesh-tuwunel image the way compose runs it (read-only root, tmpfs /tmp, no capabilities, uid 1000)
# and checks it does what a homeserver must: serves the client API, lets a user register with a token, log in, and
# be recognised by its access token. Throwaway Docker resources only, removed on exit.
#   bash smoke-test.sh <image> [expected-tuwunel-version]
set -euo pipefail

image="${1:?usage: smoke-test.sh <image> [expected-tuwunel-version]}"
want_version="${2:-}"
name="hm-tuwunel-smoke-$$"
vol="$name-data"
pass=0; fail=0
ok()  { echo "  PASS $*"; pass=$((pass + 1)); }
bad() { echo "  FAIL $*"; fail=$((fail + 1)); }
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; docker volume rm "$vol" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "== image $image"
uid="$(docker run --rm --entrypoint id "$image" -u)"
[ "$uid" = 1000 ] && ok "runs as uid 1000" || bad "runs as uid '$uid', not 1000"

if [ -n "$want_version" ]; then
  v="$(docker run --rm "$image" --version 2>&1 | head -n 1)"
  case "$v" in *"$want_version"*) ok "reports version $want_version ($v)" ;; *) bad "version '$v' does not name $want_version" ;; esac
fi

token="smoke-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
docker volume create "$vol" >/dev/null
# the volume is created root-owned; hand it to uid 1000 the way compose's init step does
docker run --rm --user 0:0 --entrypoint chown -v "$vol:/var/lib/tuwunel" "$image" 1000:1000 /var/lib/tuwunel
docker run -d --name "$name" --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges:true \
  -v "$vol:/var/lib/tuwunel" -p 127.0.0.1::8008 \
  -e TUWUNEL_SERVER_NAME=smoke.invalid -e TUWUNEL_ALLOW_REGISTRATION=true -e TUWUNEL_REGISTRATION_TOKEN="$token" \
  -e TUWUNEL_ALLOW_FEDERATION=false "$image" >/dev/null
port="$(docker port "$name" 8008/tcp | head -n 1 | sed 's/.*://')"
base="http://127.0.0.1:$port"

up=no
for _ in $(seq 1 90); do
  if curl -fsS -m 2 "$base/_matrix/client/versions" 2>/dev/null | grep -q '"versions"'; then up=yes; break; fi
  [ "$(docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null)" = true ] || break
  sleep 2
done
if [ "$up" = yes ]; then ok "serves /_matrix/client/versions"; else bad "never served the client API: $(docker logs --tail 15 "$name" 2>&1 | tr '\n' ' ')"; fi

for _ in $(seq 1 30); do [ "$(docker inspect -f '{{.State.Health.Status}}' "$name" 2>/dev/null)" = healthy ] && break; sleep 2; done
[ "$(docker inspect -f '{{.State.Health.Status}}' "$name" 2>/dev/null)" = healthy ] && ok "the HEALTHCHECK turns healthy" || bad "the HEALTHCHECK never turned healthy"

# register with the token (user-interactive auth: a first call returns the session, a second completes it)
sess="$(curl -sS -m 10 -X POST "$base/_matrix/client/v3/register" -H 'Content-Type: application/json' -d '{"username":"smokeuser","password":"Smoke-pass-0123456789"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin).get("session",""))' 2>/dev/null || true)"
reg="$(curl -sS -m 10 -X POST "$base/_matrix/client/v3/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"smokeuser\",\"password\":\"Smoke-pass-0123456789\",\"auth\":{\"type\":\"m.login.registration_token\",\"token\":\"$token\",\"session\":\"$sess\"}}")"
case "$reg" in *'"user_id":"@smokeuser:smoke.invalid"'*) ok "registers a user with the registration token" ;; *) bad "registration failed: $reg" ;; esac

login="$(curl -sS -m 10 -X POST "$base/_matrix/client/v3/login" -H 'Content-Type: application/json' \
  -d '{"type":"m.login.password","identifier":{"type":"m.id.user","user":"smokeuser"},"password":"Smoke-pass-0123456789"}')"
at="$(printf '%s' "$login" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token",""))' 2>/dev/null || true)"
[ -n "$at" ] && ok "logs in with the password and gets an access token" || bad "login failed: $login"

who="$(curl -sS -m 10 "$base/_matrix/client/v3/account/whoami" -H "Authorization: Bearer $at")"
case "$who" in *'"@smokeuser:smoke.invalid"'*) ok "the access token identifies the user (whoami)" ;; *) bad "whoami said: $who" ;; esac

[ "$(curl -s -m 10 -o /dev/null -w '%{http_code}' "$base/_matrix/client/v3/account/whoami")" = 401 ] && ok "no token is 401" || bad "whoami without a token is not 401"

echo "== smoke-test: $pass passed, $fail failed"
[ "$fail" = 0 ]

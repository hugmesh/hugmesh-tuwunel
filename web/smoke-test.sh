#!/usr/bin/env bash
# Boots a built hugmesh-tuwunel-web image the way compose runs it (read-only root, no capabilities, uid 1000) and
# checks what must hold with no homeserver behind it: the service is healthy, the UI is served, an unauthenticated
# request is refused, and a path the homeserver should answer fails with a gateway error rather than a hang or a page.
# Throwaway Docker resources only; removed on exit.
set -euo pipefail

image="${1:?usage: smoke-test.sh <image>}"
name="hm-web-smoke-$$"
pass=0; fail=0
ok()  { echo "  PASS $*"; pass=$((pass + 1)); }
bad() { echo "  FAIL $*"; fail=$((fail + 1)); }
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "== image $image"
uid="$(docker run --rm --entrypoint id "$image" -u)"
[ "$uid" = 1000 ] && ok "runs as uid 1000" || bad "runs as uid '$uid', not 1000"

docker run -d --name "$name" --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges:true \
  -v "$name-state:/var/lib/hugmesh-web" -p 127.0.0.1::8080 \
  -e HUGMESH_WEB_HOMESERVER_URL=http://127.0.0.1:9 -e HUGMESH_WEB_PUBLIC_URL=http://localhost:8080 "$image" >/dev/null
port="$(docker port "$name" 8080/tcp | head -n 1 | sed 's/.*://')"
base="http://127.0.0.1:$port"

up=no
for _ in $(seq 1 60); do
  if curl -fsS -m 2 "$base/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then up=yes; break; fi
  [ "$(docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null)" = true ] || break
  sleep 2
done
if [ "$up" = yes ]; then ok "health is UP with no homeserver behind it"; else bad "never became healthy: $(docker logs --tail 15 "$name" 2>&1 | tr '\n' ' ')"; fi

curl -fsS -m 5 "$base/" | grep -q 'hug-standalone-root' && ok "serves the web UI at /" || bad "/ does not serve the UI"
curl -fsS -m 5 "$base/some/app/route" | grep -q 'hug-standalone-root' && ok "an app route gets the UI shell" || bad "SPA fallback missing"
[ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$base/assets/missing.js")" = 404 ] && ok "a missing asset is a 404, not the shell" || bad "missing asset is not 404"
[ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$base/bff/user/session")" = 401 ] && ok "no session is 401" || bad "session without a cookie is not 401"
[ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$base/bff/user/matrix/_matrix/client/v3/sync")" = 401 ] && ok "the signed-in API refuses without a session" || bad "signed-in API is open"
code="$(curl -s -m 10 -o /dev/null -w '%{http_code}' "$base/_matrix/client/versions")"
case "$code" in 502|503|504) ok "an unreachable homeserver is a gateway error ($code)" ;; *) bad "unreachable the homeserver answered $code" ;; esac
docker volume rm "$name-state" >/dev/null 2>&1 || true

echo "== smoke-test: $pass passed, $fail failed"
[ "$fail" = 0 ]

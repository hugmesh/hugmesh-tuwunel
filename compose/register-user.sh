#!/usr/bin/env bash
# Creates a user on a running stack using the registration token (Matrix "user-interactive" registration), from inside the
# web container so it works even when nothing is published (the onion stack).
#   ./register-user.sh <username> [compose file] [project name]     (password is read from the terminal, or $MATRIX_PASSWORD)
set -euo pipefail
user="${1:?usage: register-user.sh <username> [compose-file] [project]}"
file="${2:-$(dirname "${BASH_SOURCE[0]}")/docker-compose.yml}"
args=(-f "$file"); [ -n "${3:-}" ] && args=(-p "$3" "${args[@]}")
if [ -z "${MATRIX_PASSWORD:-}" ]; then read -rsp "Password for $user: " MATRIX_PASSWORD; echo; fi
token="$(grep -E '^REGISTRATION_TOKEN=' "$(dirname "$file")/.env" 2>/dev/null | cut -d= -f2- || true)"
[ -n "$token" ] || token="${REGISTRATION_TOKEN:?set REGISTRATION_TOKEN in .env}"
docker compose "${args[@]}" exec -T -e U="$user" -e P="$MATRIX_PASSWORD" -e T="$token" web sh -c '
  base=http://tuwunel:8008/_matrix/client/v3/register
  sess=$(curl -sS -X POST "$base" -H "Content-Type: application/json" -d "{\"username\":\"$U\",\"password\":\"$P\"}" | sed -n "s/.*\"session\":\"\([^\"]*\)\".*/\1/p")
  curl -sS -X POST "$base" -H "Content-Type: application/json" \
    -d "{\"username\":\"$U\",\"password\":\"$P\",\"auth\":{\"type\":\"m.login.registration_token\",\"token\":\"$T\",\"session\":\"$sess\"}}"' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("registered", d["user_id"]) if "user_id" in d else (print("failed:", d, file=sys.stderr) or sys.exit(1))' 2>/dev/null \
  || { echo "registration failed (is REGISTRATION_TOKEN right, and is the stack up?)" >&2; exit 1; }

#!/usr/bin/env bash
# Prints the onion address of a running onion stack, and fails if it has none yet.
#   ./onion-address.sh [compose project name]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
args=(-f "$here/docker-compose.onion.yml")
[ -n "${1:-}" ] && args=(-p "$1" "${args[@]}")
host="$(docker compose "${args[@]}" exec -T tor cat /onion-public/hostname 2>/dev/null | tr -d '\r\n')" || true
case "$host" in
  [a-z2-7]*.onion) [ "${#host}" = 62 ] && { echo "http://$host"; exit 0; } ;;
esac
echo "no onion address yet: is the stack up? (docker compose -f docker-compose.onion.yml up -d)" >&2
exit 1

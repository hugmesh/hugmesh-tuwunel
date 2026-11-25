#!/usr/bin/env bash
# The Dockerfile fetches upstream Tuwunel by commit id. Refuse if the upstream tag it names has moved off that commit:
# a moved tag is a question for a human, never something to build past. Read-only; needs network.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
arg() { sed -n "s/^ARG $1=//p" "$here/Dockerfile" | head -n 1; }
version="$(arg TUWUNEL_VERSION)"; commit="$(arg TUWUNEL_COMMIT)"; url="$(arg TUWUNEL_GIT_URL)"
[ -n "$version" ] && [ -n "$commit" ] && [ -n "$url" ] || { echo "FAIL: the Dockerfile has no TUWUNEL_VERSION/COMMIT/GIT_URL" >&2; exit 1; }
# an annotated tag lists as <tag> and <tag>^{} (the commit); a lightweight one only as <tag>
now="$(git ls-remote --tags "$url" "refs/tags/v$version" "refs/tags/v$version^{}" | awk '{print $1}' | tail -n 1)"
[ -n "$now" ] || { echo "FAIL: upstream has no tag v$version" >&2; exit 1; }
if [ "$now" != "$commit" ]; then
  echo "FAIL: upstream tag v$version is now $now, not the pinned $commit" >&2
  exit 1
fi
echo "upstream v$version -> $now (pinned commit, $url)"

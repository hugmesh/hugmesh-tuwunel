#!/usr/bin/env bash
# web/VERSION is what the release workflow reads; web/pom.xml is what the jar is built as. They must agree,
# or the image would carry one version and the jar another.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
file="$(tr -d '[:space:]' < "$here/web/VERSION")"
pom="$(python3 -I - "$here/web/pom.xml" <<'PY'
import re, sys
s = open(sys.argv[1], encoding="utf-8").read()
i = s.find("</parent>")
m = re.search(r"<version>([^<]+)</version>", s[i:] if i >= 0 else s)
print(m.group(1) if m else "")
PY
)"
if [ -z "$pom" ] || [ "$file" != "$pom" ]; then
  echo "FAIL: web/VERSION is '$file' but web/pom.xml says '$pom'" >&2
  exit 1
fi
echo "ok: web/VERSION and web/pom.xml are both $file"

#!/usr/bin/env bash
# Vendors the Scalar API reference bundle into node/src/main/resources/api-docs/standalone.js.
#
# The upstream bundle talks to Scalar's hosted services (agent registry, CORS proxy, webfonts).
# A node must never phone home, so every such origin is rewritten to an unresolvable one here
# rather than being switched off through runtime config, which an upgrade could silently reset.
#
# Usage:
#   ./vendor-scalar.sh            rebuild the vendored bundle in place
#   ./vendor-scalar.sh --verify   rebuild into a temp file and diff, leaving the tree untouched
#
# To upgrade: bump VERSION, run without arguments, take the new checksum from the mismatch
# report into UPSTREAM_SHA256, rerun, then commit the regenerated bundle.

set -euo pipefail

VERSION=1.67.0
UPSTREAM_SHA256=d150e6d9ec333062cb15870704bb9eb6ec6fa99ce3fe5b164a53bc0470e838ee
URL="https://cdn.jsdelivr.net/npm/@scalar/api-reference@${VERSION}/dist/browser/standalone.js"

# Origins the bundle actually fetches from, and the minimum number of times each must appear.
# A count of zero means upstream restructured the code and the cut below silently missed it.
CUT_HOSTS=(
  "https://api.scalar.com:1"
  "https://proxy.scalar.com:1"
  "https://fonts.scalar.com:1"
  "https://registry.scalar.com:1"
  # OAuth token-refresh fallback for specs whose flow omits tokenUrl; would send credentials.
  "https://galaxy.scalar.com:1"
)
DEAD_ORIGIN="https://scalar-services-disabled.invalid"

target="$(dirname "$0")/node/src/main/resources/api-docs/standalone.js"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "Downloading @scalar/api-reference@${VERSION}"
curl -fsSL "$URL" -o "$work/upstream.js"

actual_sha="$(sha256sum "$work/upstream.js" | cut -d' ' -f1)"
if [ "$actual_sha" != "$UPSTREAM_SHA256" ]; then
  echo "ERROR: checksum mismatch for $URL" >&2
  echo "  expected: $UPSTREAM_SHA256" >&2
  echo "  actual:   $actual_sha" >&2
  exit 1
fi

cp "$work/upstream.js" "$work/patched.js"
for entry in "${CUT_HOSTS[@]}"; do
  host="${entry%:*}"
  min="${entry##*:}"
  found="$(grep -o -F "$host" "$work/patched.js" | wc -l | tr -d ' ')"
  if [ "$found" -lt "$min" ]; then
    echo "ERROR: expected at least $min occurrence(s) of $host, found $found." >&2
    echo "Upstream layout changed; re-audit the external calls before vendoring." >&2
    exit 1
  fi
  sed -i "s|$host|$DEAD_ORIGIN|g" "$work/patched.js"
  echo "  cut $host ($found occurrence(s))"
done

for entry in "${CUT_HOSTS[@]}"; do
  host="${entry%:*}"
  if grep -q -F "$host" "$work/patched.js"; then
    echo "ERROR: $host survived the cut." >&2
    exit 1
  fi
done

if [ "${1:-}" = "--verify" ]; then
  if diff -q "$work/patched.js" "$target" >/dev/null; then
    echo "OK: $target matches a fresh build of ${VERSION}."
  else
    echo "ERROR: $target differs from a fresh build of ${VERSION}." >&2
    exit 1
  fi
else
  cp "$work/patched.js" "$target"
  echo "Wrote $target"
fi

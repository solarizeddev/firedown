#!/bin/sh
# Drives the REAL FaviconStore (Firedown's own favicon bytes store) on a temp
# directory. Checks: a stored icon is found by URL; a non-image body (an HTML
# login wall, JSON, an empty 200), an HTML page with an inline <svg> and an
# oversized blob are refused and never replace good bytes; identical bytes
# only reset the staleness clock (no rewrite, no cache-version bump) while
# changed bytes bump the cache version; needsRefresh follows the 7-day clock;
# the visit-refresh claim is single-flight; a range delete removes exactly the
# icons fetched inside the range, clear() removes all, and both kill every
# cache version minted before; the count cap prunes oldest-fetched first.
#
#   sh scripts/favicon-harness/run.sh
#
# The class under test is COPIED from app/src at run time — never
# re-implemented. android.content.Context and the Hilt annotations the
# class's injected constructor names are stubs. JDK only.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/src/com/solarized/firedown/data"
cp "$ROOT/app/src/main/java/com/solarized/firedown/data/FaviconStore.java" \
   "$OUT/src/com/solarized/firedown/data/"
javac -nowarn -d "$OUT/classes" \
      $(find "$HERE/stub" "$OUT/src" "$HERE/src" -name '*.java')
java -cp "$OUT/classes" com.solarized.firedown.harness.FaviconHarness "$OUT/store"

#!/bin/sh
# Visit harness: drives the REAL GeckoState visit-identity code (updateVisit,
# pageIdentityKey, isQueryRefinement and helpers — spliced out of app/src by
# splice.py, never copied by hand) through URL sequences and checks when the
# visit id moves. A moved id sends every capture stamped before it behind
# "Earlier in this tab" in the Captured sheet. JDK + python3 only; android.net.Uri
# and DebugLog are stubs.
#
# What it pins: a YouTube Mix moving index=1 -> index=2 on the SAME video keeps
# the visit, and so do an Instagram carousel swiped (img_index) and a map
# site's place panned, zoomed or browsed photo by photo
# (the @camera and data= path segments), while a position-only query, another
# video, another list, another post or place, a hub -> item navigation and a
# v=A -> v=B change still move it, and the Instagram ?l=1 refinement still
# keeps it. Then GeckoState.originNamesPage, the sheet's second test for "this
# page": a capture made for the page on screen (a feed item, opened) belongs
# to it. Five MUTANTS prove the checks bite: no position params, no img_index,
# no "something still names the item" guard, no map-camera filter, and an
# origin test that never matches must each fail.
#
# usage: sh scripts/visit-harness/run.sh [-v]
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
BUILD="$HERE/build"

run_one() {
    mutation="$1"
    shift
    out="$BUILD/$mutation"
    rm -rf "$out"
    mkdir -p "$out/gen" "$out/classes"
    if [ "$mutation" = real ]; then
        python3 "$HERE/splice.py" "$ROOT" "$out/gen"
    else
        python3 "$HERE/splice.py" "$ROOT" "$out/gen" "--mutate=$mutation"
    fi
    javac -nowarn -d "$out/classes" $(find "$HERE/stub" "$HERE/src" "$out/gen" -name '*.java')
    java -cp "$out/classes" com.solarized.firedown.harness.VisitHarness "$@"
}

echo "== real =="
run_one real "$@"
status=0

for m in noposition noimgindex noguard nomapcamera noorigin; do
    echo
    echo "== mutant: $m (must FAIL) =="
    if run_one "$m" > "$BUILD/$m.log" 2>&1; then
        echo "mutant $m PASSED the harness — the checks have no teeth" >&2
        status=1
    else
        grep '^FAIL' "$BUILD/$m.log" | sed 's/^/  /'
        echo "  (failed as expected)"
    fi
done
exit $status

#!/bin/sh
# App-links harness: the REAL AppLinkPolicy and AppLinkMemory (copied from
# app/src, never re-typed) — a tap or a typed link asks, a link the page
# fired by itself is blocked (once per tab per hour), a link nothing opens
# loads its web version, the store push is blocked, Stay quiets page-fired
# links for the hour, the page firing the same app twice in 2 s gets one
# answer (a tap is always answered), and the memory is capped. JDK only.
# Then MUTANTS that must fail: no gesture split, Stay ignored, a page-fired
# store link loaded, no 2 s dedupe, a tap swallowed by the dedupe, mailto
# blocked, no cap.
#
# usage: sh scripts/applinks-harness/run.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
PKG="app/src/main/java/com/solarized/firedown/applinks"
POLICY="$ROOT/$PKG/AppLinkPolicy.java"
MEMORY="$ROOT/$PKG/AppLinkMemory.java"
BUILD="$HERE/build"

# run_one NAME FILE SEDEXPR: FILE (policy|memory) gets SEDEXPR, the other is copied.
run_one() {
    name="$1"
    which="$2"
    sedexpr="$3"
    out="$BUILD/$name"
    gen="$out/gen/com/solarized/firedown/applinks"
    rm -rf "$out"
    mkdir -p "$gen" "$out/classes"
    cp "$POLICY" "$MEMORY" "$gen/"
    if [ -n "$sedexpr" ]; then
        if [ "$which" = policy ]; then
            target="$gen/AppLinkPolicy.java"
            src="$POLICY"
        else
            target="$gen/AppLinkMemory.java"
            src="$MEMORY"
        fi
        sed "$sedexpr" "$src" > "$target"
        if cmp -s "$src" "$target"; then
            echo "mutant $name did not change the source" >&2
            exit 2
        fi
    fi
    javac -nowarn -encoding UTF-8 -d "$out/classes" $(find "$HERE/src" "$out/gen" -name '*.java')
    java -cp "$out/classes" com.solarized.firedown.harness.AppLinksHarness
}

echo "== real =="
run_one real "" ""
status=0

mutant() {
    echo
    echo "== mutant: $1 (must FAIL) =="
    if run_one "$1" "$2" "$3" > "$BUILD/$1.log" 2>&1; then
        echo "mutant $1 PASSED the harness — the checks have no teeth" >&2
        status=1
    else
        grep '^FAIL' "$BUILD/$1.log" | sed 's/^/  /'
        echo "  (failed as expected)"
    fi
}

mutant nogesture policy 's/if (f.pageFired) {/if (true) {/'
mutant nostay policy 's/if (f.stayRemembered) {/if (false) {/'
mutant storeloads policy 's/if (f.toStore \&\& f.pageFired \&\& f.blockRedirects) {/if (false) {/'
mutant nodedupe policy 's/if (f.justHandled) {/if (false) {/'
mutant tapdeduped policy 's/^        if (f.pageFired) {$/        if (f.justHandled) { return Outcome.NOTHING; }\n        if (f.pageFired) {/'
mutant nocomms policy 's/ \&\& !f.commsScheme//'
mutant nocap memory 's/^            trim();$//'
exit $status

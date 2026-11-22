#!/bin/sh
# Donation prompt harness: the REAL DonationPromptPolicy (copied from
# app/src, never re-typed) — who may be asked (install age, finished
# downloads), when (right after a download finished, no failure since,
# nothing running), how often (half a year apart, three times in all), and
# that a Donate tap pauses it for a year; then five years of a user who
# always taps Not now. JDK only. Then MUTANTS that must fail: no install-age
# gate, no recent-download gate, no gap, no lifetime cap, a Donate tap
# ignored, a Donate tap that never expires, no failure gate, no running gate.
#
# usage: sh scripts/donation-harness/run.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
SRC="$ROOT/app/src/main/java/com/solarized/firedown/donation/DonationPromptPolicy.java"
BUILD="$HERE/build"

run_one() {
    name="$1"
    sedexpr="$2"
    out="$BUILD/$name"
    rm -rf "$out"
    mkdir -p "$out/gen/com/solarized/firedown/donation" "$out/classes"
    if [ -z "$sedexpr" ]; then
        cp "$SRC" "$out/gen/com/solarized/firedown/donation/"
    else
        sed "$sedexpr" "$SRC" > "$out/gen/com/solarized/firedown/donation/DonationPromptPolicy.java"
        if cmp -s "$SRC" "$out/gen/com/solarized/firedown/donation/DonationPromptPolicy.java"; then
            echo "mutant $name did not change the source" >&2
            exit 2
        fi
    fi
    javac -nowarn -encoding UTF-8 -d "$out/classes" $(find "$HERE/src" "$out/gen" -name '*.java')
    java -cp "$out/classes" com.solarized.firedown.harness.DonationPromptHarness
}

echo "== real =="
run_one real ""
status=0

mutant() {
    echo
    echo "== mutant: $1 (must FAIL) =="
    if run_one "$1" "$2" > "$BUILD/$1.log" 2>&1; then
        echo "mutant $1 PASSED the harness — the checks have no teeth" >&2
        status=1
    else
        grep '^FAIL' "$BUILD/$1.log" | sed 's/^/  /'
        echo "  (failed as expected)"
    fi
}

mutant noage 's/now - s.installedAt < MIN_INSTALL_AGE_MS/false/'
mutant norecent 's/sinceFinish > RECENT_FINISH_MS/false/'
mutant nogap 's/now - s.lastShownAt < MIN_GAP_MS/false/'
mutant nocap 's/if (s.timesShown >= MAX_TIMES_SHOWN) {/if (false) {/'
mutant nodonated 's/if (s.lastDonateTapAt > 0) {/if (false) {/'
mutant tapforever 's/sinceTap < 0 || sinceTap < DONATE_TAP_PAUSE_MS/true/'
mutant nofailgate 's/s.lastFailedAt > 0 \&\& (/false \&\& (/'
mutant norunninggate 's/if (s.downloadRunning) {/if (false) {/'
exit $status

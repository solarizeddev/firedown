#!/bin/sh
# Donation prompt harness: the REAL DonationPromptPolicy (copied from
# app/src, never re-typed) — who may be asked (install age, finished
# downloads), when (right after a download finished, no failure since,
# nothing running), how often (90 days to the second ask, a year after each
# later one, no cap), how (the sheet first, then a card that lasts 14 days),
# and that a Donate tap pauses it for a year; then five years of a user who
# always taps Not now. JDK only. Then MUTANTS that must fail: no install-age
# gate, no recent-download gate, no gap, a yearly gap from the start, a
# 90-day gap forever, the sheet every time, a card that never expires, a
# Donate tap ignored, a Donate tap that never expires, no failure gate, no
# running gate.
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
mutant nogap 's/sinceShown < 0 || sinceShown < gapAfter(s.timesShown)/false/'
mutant yearlyfromstart 's/timesShown <= 1 ? SECOND_ASK_GAP_MS : LATER_ASK_GAP_MS/LATER_ASK_GAP_MS/'
mutant ninetyforever 's/timesShown <= 1 ? SECOND_ASK_GAP_MS : LATER_ASK_GAP_MS/SECOND_ASK_GAP_MS/'
mutant sheetalways 's/timesShown <= 0 ? Surface.SHEET : Surface.CARD/Surface.SHEET/'
mutant cardforever 's/age >= 0 \&\& age < CARD_LIFETIME_MS/age >= 0/'
mutant nodonated 's/if (s.lastDonateTapAt > 0) {/if (false) {/'
mutant tapforever 's/sinceTap < 0 || sinceTap < DONATE_TAP_PAUSE_MS/true/'
mutant nofailgate 's/s.lastFailedAt > 0 \&\& (/false \&\& (/'
mutant norunninggate 's/if (s.downloadRunning) {/if (false) {/'
exit $status

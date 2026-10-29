#!/bin/sh
# Drives the REAL FilmstripTrimSlider (the GIF maker's trim strip) on a plain
# JVM against stubbed Android classes: a 330 dp strip at density 2.75, touch
# sequences, a hand-cranked clock and frame pump. JDK only, seconds.
#
#   sh scripts/filmstrip-harness/run.sh            # the suite, then the mutants
#   sh scripts/filmstrip-harness/run.sh <case>     # short | medium | long | edges | storm
#
# Cases: a video shown whole (20 s, 50 s); a 9-minute video shown as a 60 s
# window (the 30 s range is half the strip, the film scrolls and flings, a tap
# places the range, the slots asked for follow the window); the edge scroll
# (the range or a handle held at an edge rides through the video, a handle
# pinned against the other one doesn't scroll, a detach stops it); a storm of
# random gestures on eleven lengths from 150 ms to 2 h checking the range, the
# window, the cells and the slots asked for after every gesture.
# Mutants (the copied source edited) that must fail:
#   M1 no window (always the whole video)
#   M2 a pinned handle still edge-scrolls
#   M3 a dragged range not kept on screen
#   M4 the film scrolling the wrong way
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
SRC="$ROOT/app/src/main/java/com/solarized/firedown/ui/FilmstripTrimSlider.java"

build() {
    rm -rf "$OUT/classes"
    mkdir -p "$OUT/classes"
    find "$HERE/stubs" -name '*.java' > "$OUT/sources"
    echo "$1" >> "$OUT/sources"
    echo "$HERE/FilmstripHarness.java" >> "$OUT/sources"
    javac -nowarn -d "$OUT/classes" @"$OUT/sources" 2>&1
}

if ! build "$SRC"; then
    echo "filmstrip: build failed"
    exit 1
fi
java -cp "$OUT/classes" FilmstripHarness "${1:-}"
STATUS=$?
if [ -n "${1:-}" ] || [ "${MUTANTS:-1}" = 0 ]; then
    exit $STATUS
fi

CAUGHT=0
mutant() {
    name=$1
    old=$2
    new=$3
    cp "$SRC" "$OUT/FilmstripTrimSlider.java"
    if ! python3 - "$OUT/FilmstripTrimSlider.java" "$old" "$new" <<'PY'
import sys
p, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(p).read()
if old not in s:
    sys.exit(1)
open(p, 'w').write(s.replace(old, new, 1))
PY
    then
        echo "MUTANT $name: pattern not found"
        STATUS=1
        return
    fi
    if ! build "$OUT/FilmstripTrimSlider.java" > /dev/null; then
        echo "MUTANT $name: does not compile"
        STATUS=1
        return
    fi
    if java -cp "$OUT/classes" FilmstripHarness > "$OUT/mutant.log" 2>&1; then
        echo "MUTANT $name: NOT caught"
        STATUS=1
    else
        echo "mutant $name caught ($(grep -c '^FAIL' "$OUT/mutant.log") checks fail)"
        CAUGHT=$((CAUGHT + 1))
    fi
}

mutant "M1 no window" \
    "if (mMaxTrimMs > 0 && mDurationMs > WINDOW_MAX_RANGES * mMaxTrimMs) {" \
    "if (false) {"
mutant "M2 pinned handle scrolls" \
    "            return mStartMs >= mEndMs - mMinTrimMs;" \
    "            return false;"
mutant "M3 range not kept on screen" \
    "            long lowest = mViewStartMs;" \
    "            long lowest = 0L;"
mutant "M4 film scrolls the wrong way" \
    "setViewStart(mPanAnchorMs - (long) (x - mDownX) * windowMs() / w);" \
    "setViewStart(mPanAnchorMs + (long) (x - mDownX) * windowMs() / w);"
echo "filmstrip: $CAUGHT of 4 mutants caught"
exit $STATUS

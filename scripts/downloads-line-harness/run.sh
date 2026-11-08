#!/bin/sh
# The bottom bar Downloads button's progress line (DownloadProgressDrawable):
# the REAL DownloadsLineGeometry, copied from app/src, against the tray of
# Material's "download" glyph, the 24dp box, RTL and the sliding piece.
# JDK only, seconds. MUTANTS=0 skips the planted bugs.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
pkg=com/solarized/firedown/geckoview/toolbar
real="$root/app/src/main/java/$pkg/DownloadsLineGeometry.java"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

build() {
    rm -rf "$work/src" "$work/out"
    mkdir -p "$work/src/$pkg" "$work/out"
    cp "$1" "$work/src/$pkg/DownloadsLineGeometry.java"
    cp "$here/LineHarness.java" "$work/src/$pkg/"
    javac -d "$work/out" "$work/src/$pkg/"*.java
}

build "$real"
java -cp "$work/out" com.solarized.firedown.geckoview.toolbar.LineHarness

if [ "${MUTANTS:-1}" = "0" ]; then
    exit 0
fi

mutant() {
    name=$1
    from=$2
    to=$3
    cp "$real" "$work/mutant.java"
    python3 - "$work/mutant.java" "$from" "$to" <<'PY'
import sys
path, old, new = sys.argv[1:4]
text = open(path).read()
if old not in text:
    sys.exit("mutant anchor not found: " + old)
open(path, "w").write(text.replace(old, new, 1))
PY
    build "$work/mutant.java"
    if java -cp "$work/out" com.solarized.firedown.geckoview.toolbar.LineHarness >/dev/null; then
        echo "MUTANT SURVIVED: $name"
        exit 1
    fi
    echo "mutant killed: $name"
}

mutant "fill ignores RTL" "        if (rtl) {
            return new float[]{LINE_RIGHT - filled, LINE_RIGHT};" "        if (false) {
            return new float[]{LINE_RIGHT - filled, LINE_RIGHT};"
mutant "no minimum fill" "Math.max(MIN_FILL, width * clamp01(fraction))" "width * clamp01(fraction)"
mutant "line at the board-19 gap" "static final float LINE_TOP = 20f;" "static final float LINE_TOP = 18f;"
mutant "piece not clipped to the line" "        left = Math.max(left, LINE_LEFT);" "        left = left;"
echo "all mutants killed"

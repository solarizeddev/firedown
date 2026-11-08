#!/bin/sh
# The bottom bar's dots against Android's and Material's rounding at every
# density: the REAL IconBadgeOffsets, copied from app/src, driven by
# BadgeHarness.java (AOSP 14 + Material 1.14.0 placement code, copied).
# JDK only, seconds. MUTANTS=0 skips the planted bugs.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
pkg=com/solarized/firedown/geckoview/toolbar
real="$root/app/src/main/java/$pkg/IconBadgeOffsets.java"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

build() {
    rm -rf "$work/src" "$work/out"
    mkdir -p "$work/src/$pkg" "$work/out"
    cp "$1" "$work/src/$pkg/IconBadgeOffsets.java"
    cp "$here/BadgeHarness.java" "$work/src/$pkg/"
    javac -d "$work/out" "$work/src/$pkg/"*.java
}

build "$real"
java -cp "$work/out" com.solarized.firedown.geckoview.toolbar.BadgeHarness

if [ "${MUTANTS:-1}" = "0" ]; then
    exit 0
fi

# Each planted bug must fail the sweep.
mutant() {
    name=$1
    from=$2
    to=$3
    cp "$real" "$work/mutant.java"
    python3 - "$work/mutant.java" "$from" "$to" <<'EOF'
import sys
path, old, new = sys.argv[1:4]
text = open(path).read()
if old not in text:
    sys.exit("mutant anchor not found: " + old)
open(path, "w").write(text.replace(old, new, 1))
EOF
    build "$work/mutant.java"
    if java -cp "$work/out" com.solarized.firedown.geckoview.toolbar.BadgeHarness quiet >/dev/null; then
        echo "MUTANT SURVIVED: $name"
        exit 1
    fi
    echo "mutant killed: $name"
}

mutant "rtl ignored" "rtl ? iconLeft : buttonWidth - iconRight" "buttonWidth - iconRight"
mutant "rtl corner swapped" "rtl ? iconLeft : buttonWidth - iconRight" "rtl ? buttonWidth - iconRight : iconLeft"
mutant "dot inside the top edge" "return iconTop + dotHeight / 2;" "return iconTop + dotHeight;"
mutant "dot inside the end edge" "return fromEnd + dotWidth / 2;" "return fromEnd + dotWidth;"
mutant "offset from the button, not the icon" "int fromEnd = rtl ? iconLeft : buttonWidth - iconRight;" "int fromEnd = rtl ? iconLeft : buttonWidth / 4;"
echo "all mutants killed"

#!/bin/sh
# data: URL harness: the REAL DataUris (copied from app/src, never re-typed) —
# which URLs are data: URLs, the header a row or a Bundle keeps, the media
# type, and the payload decoded the way a browser decodes it (percent-decoded,
# then base64 when the URL says so; whitespace and missing padding forgiven).
# JDK only. Then MUTANTS that must fail: a strip that keeps the payload (the
# megabyte URL back in every Parcel and row), a decoder that skips the
# percent step (a percent-encoded base64 payload comes out as garbage), and a
# header with no cap (a data: URL with no comma kept whole).
#
# usage: sh scripts/datauri-harness/run.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
SRC="$ROOT/app/src/main/java/com/solarized/firedown/utils/DataUris.java"
BUILD="$HERE/build"

run_one() {
    name="$1"
    sedexpr="$2"
    out="$BUILD/$name"
    rm -rf "$out"
    mkdir -p "$out/gen/com/solarized/firedown/utils" "$out/classes"
    if [ -z "$sedexpr" ]; then
        cp "$SRC" "$out/gen/com/solarized/firedown/utils/"
    else
        sed "$sedexpr" "$SRC" > "$out/gen/com/solarized/firedown/utils/DataUris.java"
        if cmp -s "$SRC" "$out/gen/com/solarized/firedown/utils/DataUris.java"; then
            echo "mutant $name did not change the source" >&2
            exit 2
        fi
    fi
    javac -nowarn -encoding UTF-8 -d "$out/classes" $(find "$HERE/src" "$out/gen" -name '*.java')
    java -cp "$out/classes" com.solarized.firedown.harness.DataUrisHarness
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

mutant stripkeeps 's/        return header(url);/        return url;/'
mutant nopercent 's/byte\[\] bytes = percentDecode(url, comma + 1);/byte[] bytes = url.substring(comma + 1).getBytes(StandardCharsets.UTF_8);/'
mutant nocap 's/        if (end > MAX_HEADER_CHARS) {/        if (false) {/'
exit $status

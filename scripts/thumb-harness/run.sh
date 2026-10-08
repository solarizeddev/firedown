#!/bin/sh
# TabThumbnailStore harness: compiles the REAL class from app/src against
# stubs for the five Android types it touches (Bitmap with a salted
# compress, an access-ordered LruCache with the real eviction contract, Log,
# Build, Context) and drives it with a hand-cranked executor so the store
# can be examined BETWEEN a put and its write. JDK only, seconds.
#
# What it pins: the index of a previous process's files (optimistic until
# it lands; legacy <id>_<ts>.png and torn tmp files are not thumbnails), a
# put is a memory hit before the write and the write lands atomically as
# <id>.webp lossy, a recapture bumps the version and overwrites in place,
# an incognito put never writes, the byte budget evicts least recently USED
# and an evicted regular tab keeps its file, remove drops memory + index at
# once and the file on the executor (incl. a put whose write was still
# queued), the version is monotonic across remove/re-put, prune keeps
# referenced and YOUNG unreferenced files and deletes old unreferenced,
# legacy, torn and foreign ones, clear empties both tiers. Then a MUTANT
# that sizes entries as 1 byte must fail the budget checks.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
BUILD="$HERE/build"
REAL="$ROOT/app/src/main/java/com/solarized/firedown/data/TabThumbnailStore.java"

run_one() {
    mutation="$1"
    out="$BUILD/$mutation"
    rm -rf "$out"
    mkdir -p "$out/src/com/solarized/firedown/data" "$out/classes" "$out/tmp"
    cp "$REAL" "$out/src/com/solarized/firedown/data/"
    if [ "$mutation" = nobudget ]; then
        sed -i 's/return value.getByteCount();/return 1;/' "$out/src/com/solarized/firedown/data/TabThumbnailStore.java"
        grep -q 'return 1;' "$out/src/com/solarized/firedown/data/TabThumbnailStore.java" || { echo "mutation did not apply" >&2; exit 1; }
    fi
    javac -nowarn -d "$out/classes" $(find "$HERE/stub" "$HERE/src" "$out/src" -name '*.java')
    java -cp "$out/classes" com.solarized.firedown.harness.ThumbHarness "$out/tmp"
}

echo "== real =="
run_one real
status=0
echo
echo "== mutant: nobudget (must FAIL) =="
if run_one nobudget > "$BUILD/nobudget.log" 2>&1; then
    echo "mutant passed the harness — the budget checks have no teeth" >&2
    status=1
else
    grep '^FAIL' "$BUILD/nobudget.log" | sed 's/^/  /'
    echo "  (failed as expected)"
fi
exit $status

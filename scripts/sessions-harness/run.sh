#!/bin/sh
# Sessions-file harness: drives the REAL tab-persistence reader and writer
# (GeckoStateDataRepository.readDocumentStrict / readEntityStrict / readEntity,
# GeckoStateObserver.writeEntity, GeckoStateEntity.KEYS — spliced out of
# app/src by splice.py, never copied by hand) through every file version the
# strict reader accepts. JDK + python3 only; Gson stands in for
# android.util.Json{Reader,Writer} (the API they were derived from).
#
# What it pins: a v2/v3 file written before `icon_resolution` and
# `tracking_protection` were retired still loads with those keys present
# (skipped), a v4 file carrying either is rejected as corrupt like any
# unknown key, the writer emits neither and stamps v4, a written document
# reads back, and the version envelope (unsupported versions, tabs before
# the version). Then two MUTANTS prove the checks bite: with the version gate
# removed the v4 rejections fail; with the skip removed the v2/v3 loads fail.
#
# usage: sh scripts/sessions-harness/run.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
CACHE="$HERE/.cache"
BUILD="$HERE/build"
GSON_VERSION=2.11.0
GSON_JAR="${GSON_JAR:-$CACHE/gson-$GSON_VERSION.jar}"
GSON_SHA256=57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b

mkdir -p "$CACHE"
if [ ! -f "$GSON_JAR" ]; then
    echo "fetching gson $GSON_VERSION"
    curl -sSL --max-time 120 -o "$GSON_JAR" \
        "https://repo1.maven.org/maven2/com/google/code/gson/gson/$GSON_VERSION/gson-$GSON_VERSION.jar"
fi
have=$(sha256sum "$GSON_JAR" | cut -d' ' -f1)
if [ "$have" != "$GSON_SHA256" ]; then
    echo "gson jar sha256 mismatch: $have" >&2
    exit 1
fi

run_one() {
    mutation="$1"
    out="$BUILD/$mutation"
    rm -rf "$out"
    mkdir -p "$out/gen" "$out/classes"
    if [ "$mutation" = real ]; then
        python3 "$HERE/splice.py" "$ROOT" "$out/gen"
    else
        python3 "$HERE/splice.py" "$ROOT" "$out/gen" "--mutate=$mutation"
    fi
    javac -nowarn -d "$out/classes" -cp "$GSON_JAR" \
        $(find "$HERE/stub" "$HERE/src" "$out/gen" -name '*.java')
    java -cp "$out/classes:$GSON_JAR" com.solarized.firedown.harness.SessionsHarness
}

echo "== real =="
run_one real
status=0

for m in nogate noskip; do
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

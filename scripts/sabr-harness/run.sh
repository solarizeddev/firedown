#!/bin/sh
# Drives the REAL SabrDownloader against a scripted SABR server: the happy
# path, status-3 recovery, all three loop bounds, redirects, progress
# reporting and a mid-stream HTTP failure.
#
#   sh scripts/sabr-harness/run.sh
#
# It compiles app/src/main/java/com/solarized/firedown/sabr/*.java — the real
# downloader, UmpReader, SabrMessages, ProtobufWire and Fmp4Muxer — against
# stubs for the only five external types the package touches (okhttp3.*,
# android.util.Log/Base64, BuildConfig). Nothing is re-implemented.
#
# Needs only a JDK: no Android SDK, no OkHttp, no protobuf, no device.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/src/com/solarized/firedown/sabr"
cp "$ROOT"/app/src/main/java/com/solarized/firedown/sabr/*.java "$OUT/src/com/solarized/firedown/sabr/"
cp "$HERE"/src/com/solarized/firedown/sabr/*.java "$OUT/src/com/solarized/firedown/sabr/"
javac -nowarn -d "$OUT/classes" -cp "$HERE/stub" \
      $(find "$HERE/stub" "$OUT/src" -name '*.java')
java -cp "$OUT/classes" com.solarized.firedown.sabr.SabrHarness

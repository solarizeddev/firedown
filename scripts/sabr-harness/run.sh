#!/bin/sh
# Drives the REAL SabrDownloader against a scripted SABR server: the happy
# path, status-3 recovery, all three loop bounds, redirects, progress
# reporting and a mid-stream HTTP failure. Then the REAL Fmp4Muxer — the one
# copy a SABR download writes — over real ISO-BMFF fixtures (Fmp4Fixtures,
# built from the 14496-12 layouts): header, track-ID rewrite, the whole-file
# sidx, patched durations, media buffered before both inits, the sidx slot
# sized from the duration, every refusal, and the muxer fed by the real
# downloader end to end. Mutants that must fail it: a fixed 128 KB slot
# (M7), no track-ID rewrite (M2), the durations left unpatched (M4), the
# pending fragments dropped (M5), the 16-bit count unchecked (M7b).
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

#!/bin/sh
# Two checks on the REAL Deezer download code, JDK only.
#
# 1. TYPE-CHECK DeezerStrategy. The Android build can't run without an SDK, so
#    this compiles the real DeezerStrategy (+ the real DownloadStrategy /
#    DownloadCallback interfaces it implements) against small collaborator
#    stubs: the sabr harness's okhttp3 + android.util.Log stubs reused verbatim
#    (plus the one RequestBody(String, MediaType) overload it lacked), and
#    signature-only stubs for android.net.Uri / TextUtils, org.json, commons-io
#    FilenameUtils, and the app types the strategy touches (DownloadRequest,
#    DownloadContext, Download, NetworkModule, FileUriHelper, MessageHelper).
#    The stubs mirror the REAL signatures — including checked exceptions on
#    org.json — so a call that wouldn't compile against the real APIs fails
#    here too. javac -Xlint:all so an unused import or raw type is visible.
#
# 2. DRIVE DeezerCrypto (Android-free, no stubs needed): Blowfish key
#    derivation vs known-answer vectors from an INDEPENDENT implementation
#    (node crypto — a bug that made DeezerCrypto agree with itself can't pass a
#    vector from a different codebase); an encrypt→decryptStream round trip
#    (every-3rd-stripe rule, verbatim tail); the stripe-alignment invariant (a
#    1-byte-per-read stream must yield the identical file); the progress-hook
#    abort.
#
#   sh scripts/deezer-harness/run.sh
#
# Classes under test are COPIED from app/src at run time — never
# re-implemented. Needs only a JDK.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
MGR="$ROOT/app/src/main/java/com/solarized/firedown/manager"
mkdir -p "$OUT/src/com/solarized/firedown/manager"
cp "$MGR/DeezerCrypto.java" "$MGR/DeezerStrategy.java" \
   "$MGR/DownloadStrategy.java" "$MGR/DownloadCallback.java" \
   "$OUT/src/com/solarized/firedown/manager/"

echo "--- 1. type-check DeezerStrategy + DeezerCrypto against stubs ---"
javac -Xlint:all -d "$OUT/classes" -cp "$HERE/stub" \
      $(find "$HERE/stub" "$OUT/src" "$HERE/src" -name '*.java')
echo "javac: DeezerStrategy, DeezerCrypto, DeezerHarness compiled"

echo "--- 2. drive DeezerCrypto ---"
java -cp "$OUT/classes" com.solarized.firedown.manager.DeezerHarness

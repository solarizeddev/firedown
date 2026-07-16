#!/bin/sh
# Drives the REAL DeezerCrypto against known-answer vectors + a full
# encrypt-then-decrypt round trip. Checks: the Blowfish key derivation matches
# vectors produced by an INDEPENDENT implementation (node crypto, see the KATs
# in DeezerHarness — if both were wrong the same way this wouldn't catch it, so
# the vectors come from a different codebase, not from DeezerCrypto itself); the
# stripe cipher round-trips (decrypt(encrypt(x)) == x) for every 3rd full
# stripe while leaving the other stripes and the trailing short stripe
# verbatim; decryptStream reassembles a multi-stripe body BYTE-EXACT regardless
# of how the input stream chunks its reads (a 1-byte-at-a-time stream must
# produce the identical file as a whole-buffer one — the stripe-alignment
# invariant); and the progress hook's false return aborts mid-stream.
#
#   sh scripts/deezer-harness/run.sh
#
# The class under test is COPIED from app/src at run time — never
# re-implemented. DeezerCrypto is deliberately Android-free (JDK crypto only:
# javax.crypto Blowfish + java.security MD5), so it compiles and runs on a plain
# JDK with no stubs at all. Needs only a JDK.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/src/com/solarized/firedown/manager"
cp "$ROOT/app/src/main/java/com/solarized/firedown/manager/DeezerCrypto.java" \
   "$OUT/src/com/solarized/firedown/manager/"
javac -nowarn -d "$OUT/classes" \
      $(find "$OUT/src" "$HERE/src" -name '*.java')
java -cp "$OUT/classes" com.solarized.firedown.manager.DeezerHarness

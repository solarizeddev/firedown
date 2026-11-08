#!/bin/sh
# Drives the REAL P2P share LAN plumbing on a plain JVM:
#   - P2pHttp (the request-head parser both tiny servers share): the exact
#     CRLFCRLF stop (a body right behind the head arrives byte-exact — the
#     stray-\n bug that once corrupted deflate codes), query/header parse,
#     the head cap, Content-Length edge cases.
#   - P2pLanServer (the receiver's /offer + the sender's /answer): token gate
#     (403), method/route gate (404, and the route the owner does not serve),
#     body cap (400), handler verdicts (204 vs 409), the declined body, and
#     that a 409'd client can retry with a different route.
#     Plus the connection deadline (a trickling client is cut, not served
#     forever), the bounded worker pool (others served meanwhile; one past
#     the pool dropped at once), the exact-bytes token compare, and one
#     winner among simultaneous offers.
#   - RelayCredsPolicy: a cached TURN credential is reused only with enough
#     life for the offer window + a transfer, and the still-valid fallback;
#     the network gate (no network / captive portal skip, "no internet"
#     still tries).
#   - P2pFileNames: a received file's name, bounded and correct without an
#     extension (the old loop spun forever on the shared disk executor).
#   - P2pSessionPolicy: attach / start / busy when a share screen opens.
#   - P2pLanServer.reachableHost: nearby lists IPv4 only.
#   - P2pSendRows: the Send screen's one "Send to" list (no spinner at rest,
#     the asked phone waits, the link's countdown, connecting collapses to
#     the peer), plus six planted bugs that must each fail (MUTANTS=0 skips).
#
#   sh scripts/p2p-harness/run.sh
#
# Real classes are COPIED from app/src at run time — never re-implemented.
# android.util.Log / BuildConfig / the annotations are stubs. Needs a JDK.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/src/com/solarized/firedown/p2pshare"
for f in P2pHttp P2pLanServer RelayCredsPolicy P2pFileNames P2pSessionPolicy P2pSendRows; do
  cp "$ROOT/app/src/main/java/com/solarized/firedown/p2pshare/$f.java" "$OUT/src/com/solarized/firedown/p2pshare/"
done
javac -nowarn -d "$OUT/classes" -cp "$HERE/stub" \
      $(find "$HERE/stub" "$OUT/src" "$HERE/src" -name '*.java')
java -cp "$OUT/classes" com.solarized.firedown.p2pshare.P2pHarness

# Mutants: each planted bug in P2pSendRows must fail the harness (MUTANTS=0 skips).
if [ "${MUTANTS:-1}" != "0" ]; then
  SRC="$ROOT/app/src/main/java/com/solarized/firedown/p2pshare/P2pSendRows.java"
  run_mutant() {
    name=$1; from=$2; to=$3
    M=$(mktemp -d)
    cp -r "$OUT/src" "$M/src"
    python3 - "$SRC" "$M/src/com/solarized/firedown/p2pshare/P2pSendRows.java" "$from" "$to" <<'PY'
import sys
s = open(sys.argv[1]).read()
if sys.argv[3] not in s:
    sys.exit("mutant pattern not found: " + sys.argv[3])
open(sys.argv[2], "w").write(s.replace(sys.argv[3], sys.argv[4], 1))
PY
    javac -nowarn -d "$M/classes" -cp "$HERE/stub" $(find "$HERE/stub" "$M/src" "$HERE/src" -name '*.java')
    if java -cp "$M/classes" com.solarized.firedown.p2pshare.P2pHarness > "$M/log" 2>&1; then
      echo "MUTANT SURVIVED: $name"; rm -rf "$M"; exit 1
    fi
    echo "mutant killed: $name ($(grep -c '^FAIL' "$M/log") checks)"
    rm -rf "$M"
  }
  run_mutant "asked phone that left discovery dropped" "if (!pendingListed) {" "if (false) {"
  run_mutant "found phones hidden behind the fold" "} else if (!devices.isEmpty()) {" "} else if (false) {"
  run_mutant "connecting does not collapse" "if (phase == Phase.CONNECTING) {" "if (phase == Phase.CONNECTING && devices.isEmpty()) {"
  run_mutant "countdown rounds down" "(left + 59_999) / 60_000" "left / 60_000"
  run_mutant "asked phone tappable again" "!waiting, waiting, false, 0" "true, waiting, false, 0"
  run_mutant "preparing link tappable" "Sub.LINK_PREPARING, false, true" "Sub.LINK_PREPARING, true, true"
  run_mutant "Cancel at rest" "|| (phase == Phase.READY && (linkShared" "|| (phase == Phase.READY && (true"
fi

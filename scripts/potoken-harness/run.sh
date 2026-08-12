#!/bin/sh
# Two suites over the REAL PoTokenGenerator:
#   Harness      — every functional path: session lifecycle,
#                  token cache + TTL, forceFresh recovery, port reconnect/
#                  disconnect, wedged-page recycling, init/mint timeouts,
#                  concurrency and multiplexed replies.
#   LeakHarness  — resource lifetimes: every GeckoSession opened must end up
#                  closed (an open one holds a content process), neither map
#                  may grow without bound, and no path may strand an entry in
#                  `pending` with a future nobody completes.
#
#   sh scripts/potoken-harness/run.sh
#
# It COMPILES app/src/.../PoTokenGenerator.java itself against the stubs in
# stub/ — it never re-implements it, so the class under test is always the one
# that ships (a simplified copy is how the Threads depth-cap bug survived eight
# rounds; see CLAUDE.md). Android/GeckoView types are stubbed just enough to run:
# Handler.post executes on one "main" thread, and WebExtension.Port stands in for
# the content script, with knobs for never answering, answering with an error,
# and reconnecting.
#
# Takes ~85s — four cases deliberately wait out real timeouts (INIT 10s,
# MINT 15s, MINT_FRESH 30s, plus the grace window), because the constants'
# relationship to each other is part of what is being tested.
#
# Needs only a JDK. No Android SDK, no Gradle, no device.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/src/com/solarized/firedown/geckoview"
cp "$ROOT/app/src/main/java/com/solarized/firedown/geckoview/PoTokenGenerator.java" \
   "$OUT/src/com/solarized/firedown/geckoview/"
cp "$HERE/src/com/solarized/firedown/geckoview/Harness.java" \
   "$OUT/src/com/solarized/firedown/geckoview/"
cp "$HERE/src/com/solarized/firedown/geckoview/LeakHarness.java" \
   "$OUT/src/com/solarized/firedown/geckoview/"
javac -nowarn -d "$OUT/classes" -cp "$HERE/stub" \
      $(find "$HERE/stub" "$OUT/src" -name '*.java')
java -cp "$OUT/classes" com.solarized.firedown.geckoview.Harness
echo
java -cp "$OUT/classes" com.solarized.firedown.geckoview.LeakHarness

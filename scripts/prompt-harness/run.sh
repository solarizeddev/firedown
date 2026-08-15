#!/bin/sh
# Drives the REAL GeckoPromptManager + REAL PromptFloodGate with a fake
# malicious page that floods prompts. Checks: first prompt shows a dialog and
# every concurrent one is answered-dismissed (never queued, never a second
# dialog, page JS never blocked); a nagging answer-me loop gets exactly 3
# dialogs per 10s window per tab, then auto-dismissals until the window
# drains; flood state is per-tab; a non-browser nav destination (sheets are
# <dialog> destinations) gets no dialog; a tab switch dismisses and answers;
# and no GeckoResult is ever leaked or completed twice (the stub GeckoResult
# throws on double completion, like the real one).
#
#   sh scripts/prompt-harness/run.sh
#
# Real classes under test are COPIED from app/src at run time — never
# re-implemented. PromptViewFactory is a collaborator stub mirroring only its
# contract (dialogs answer on dismissal; buttons respond once); Android UI
# cannot exist on a plain JVM. Needs only a JDK.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/src/com/solarized/firedown/geckoview/prompt" "$OUT/src/com/solarized/firedown/geckoview"
cp "$ROOT/app/src/main/java/com/solarized/firedown/geckoview/prompt/GeckoPromptManager.java" \
   "$OUT/src/com/solarized/firedown/geckoview/prompt/"
cp "$ROOT/app/src/main/java/com/solarized/firedown/geckoview/PromptFloodGate.java" \
   "$OUT/src/com/solarized/firedown/geckoview/"
javac -nowarn -d "$OUT/classes" -cp "$HERE/stub" \
      $(find "$HERE/stub" "$OUT/src" "$HERE/src" -name '*.java')
java -cp "$OUT/classes" com.solarized.firedown.harness.PromptHarness

#!/bin/sh
# Drives the REAL DownloadEngine (the download queue extracted from the
# RunnableManager service) on a plain JVM: intents in, Host callbacks out.
# Checks the state machine end to end — start/queue/active promotion at the
# pool bound, natural finish → finished notification → idle, user Finish,
# delete of an active and of a queued task, restart of an errored row, a
# strategy error, the Android-15 FGS-timeout seal (ERROR + SYSTEM_TIMEOUT,
# and cancelAll must NOT stamp FINISHED over it), the vault/regular
# notification split, the filename-collision loop, the main-thread guard on
# filePathInTasks, and a final leak sweep (every started download thread
# unwound, no handler exception swallowed).
#
#   sh scripts/engine-harness/run.sh                  # the 44-assertion suite
#   sh scripts/engine-harness/run.sh stress [sec] [seed]   # randomized concurrency storm
#
# The stress mode (EngineStress) hammers the same real engine from several
# "UI" threads with a random mix of start / natural finish / user Finish /
# delete / restart / strategy error / FGS-timeout seal, with names chosen to
# collide, then drains and checks the invariants that must hold under any
# interleaving: no exception on the engine thread, no task in both lists or
# twice in one, every started thread unwound, every row terminal or deleted,
# no two live tasks on one path, path set empty at rest, idle reported.
#
# Real classes under test are COPIED from app/src at run time — never
# re-implemented. android.os.Handler/Looper/HandlerThread are a REAL
# queue-backed loop (the threading contract is part of what is tested), and
# DownloadTask/DownloadRunnable are collaborator stubs mirroring only the
# contract the engine relies on (the seal latch, the lifecycle messages) —
# the real ones drag in every strategy and Room. Needs only a JDK.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
SRC="$ROOT/app/src/main/java/com/solarized/firedown"
mkdir -p "$OUT/src/com/solarized/firedown/manager" "$OUT/src/com/solarized/firedown/data" \
         "$OUT/src/com/solarized/firedown/utils"
cp "$SRC/manager/DownloadEngine.java"  "$OUT/src/com/solarized/firedown/manager/"
cp "$SRC/manager/ServiceActions.java"  "$OUT/src/com/solarized/firedown/manager/"
cp "$SRC/IntentActions.java"           "$OUT/src/com/solarized/firedown/"
cp "$SRC/data/Download.java"           "$OUT/src/com/solarized/firedown/data/"
cp "$SRC/data/TaskEvent.java"          "$OUT/src/com/solarized/firedown/data/"
cp "$SRC/utils/DebugLog.java"          "$OUT/src/com/solarized/firedown/utils/"
javac -nowarn -d "$OUT/classes" -cp "$HERE/stub" \
      $(find "$HERE/stub" "$OUT/src" "$HERE/src" -name '*.java')
MODE="${1:-suite}"
if [ "$MODE" = "stress" ]; then
  shift
  # sh scripts/engine-harness/run.sh stress [seconds] [seed]
  java -cp "$OUT/classes" com.solarized.firedown.harness.EngineStress "$@"
else
  java -cp "$OUT/classes" com.solarized.firedown.harness.EngineHarness
fi

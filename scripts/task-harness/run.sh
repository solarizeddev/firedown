#!/bin/sh
# Drives the REAL TaskManager (the media/vault task service), TaskRunnable,
# TaskRepository and all seven tasks (GIF, audio, frame, compress, extract,
# vault lock, vault unlock) on a plain JVM. JDK only.
#
#   sh scripts/task-harness/run.sh                      # the suite + the mutants
#   sh scripts/task-harness/run.sh stress [sec] [seed]  # randomized storm (default 20 s, seed 1)
#   sh scripts/task-harness/run.sh group <n>            # one group of the suite
#   sh scripts/task-harness/run.sh mutants              # only the teeth checks
#
# Groups (one JVM each, so one process per group, as on a phone):
#   1  each task's happy path: Started -> Progress -> Finished(<action>) with
#      the produced entity, the output on disk, TaskRepository in flight
#      during and not after, foreground with the right title/icon/type and
#      a Cancel that starts TaskManager, left at the end, a partial wake lock
#      held during and released after, stopped by stopSelfResult(newest id)
#   2  each task cancelled mid-work, by its own cancel action and by the
#      notification's CANCEL_CURRENT: prompt, the cancel result, partial
#      output deleted, sources intact, no row, no fd left open, end state;
#      2e the ERROR result of a native failure / undecodable frame / bad zip
#   3  cancels in the windows before the work: before run() (as runJob takes
#      the wake lock), at Started (before the native object exists:
#      AudioEncodeTask.stop used to NPE), inside the native constructor;
#      3d an empty entity list (run()'s own stop() after an early return)
#   4  input snapshots: a second start while the first runs does not change
#      the first one's file list / GIF args / frame position; a cancel stops
#      the running task, not the queued one
#   5  the orphan: the system destroys the service mid-task; a SECOND
#      instance receives the cancel (the static current runnable); 5d a new
#      instance created while the old instance's task still unwinds
#   6  onTimeout(int,int) (SDK 35) and onTimeout(int) (SDK 34) mid-task
#   7  startForeground refused (always / at start), the app going to the
#      background mid-task and with a task queued
#   8  service lifetime: a start arriving while the last job finishes, a
#      cancel / null / unknown intent with nothing running, other intents
#      during a task, two starts and one stop, another kind of task's cancel
#   11 what the UI observer receives (every event, in order, with the main
#      thread busy), a null entity, a vault lock with a missing source, a
#      read error mid-copy
#   9  a short stress (3 s); `stress` runs it longer
# Then the MUTANTS (Prepare.java edits the copied source in the temp dir):
#   M1 the static current runnable as an instance field  -> 5b/5c must fail
#   M2 every getter reading the LATEST start's inputs    -> 4a/4b must fail
#   M3 TaskRunnable.run() resetting the stop flag         -> 3a must fail
#   M4 AudioEncodeTask.stop() without its null check      -> 3b/3d audio must fail
#
# The system side (android.app.Sys) models ActivityManager for one service:
# startIds handed out on startService, delivery later on a main looper,
# stopSelfResult only on the newest id, startForeground refused from the
# background, a parcelled copy of every intent. The JVM runs from / like an
# Android app process: Utils.changeExtension resolves FilenameUtils.getPath,
# which drops the leading '/', against the working directory.
# COMMONS_IO_JAR=<jar> picks the commons-io jar; COMMONS_IO_STUB=1 forces the
# fallback stub (stub-commons-io/).
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

find_commons_io() {
  if [ -n "${COMMONS_IO_STUB:-}" ]; then return; fi
  if [ -n "${COMMONS_IO_JAR:-}" ] && [ -f "$COMMONS_IO_JAR" ]; then echo "$COMMONS_IO_JAR"; return; fi
  for j in $(find "$HOME/.gradle/caches/modules-2/files-2.1/commons-io" -name 'commons-io-2*.jar' 2>/dev/null | grep -v -e sources -e javadoc | sort | tail -1) \
           $(ls /opt/gradle*/lib/commons-io-*.jar 2>/dev/null | tail -1) \
           $(ls "$HOME"/.m2/repository/commons-io/commons-io/*/commons-io-*.jar 2>/dev/null | grep -v sources | tail -1); do
    if [ -f "$j" ]; then echo "$j"; return; fi
  done
}
CIO=$(find_commons_io)
if [ -n "$CIO" ]; then
  echo "commons-io: $CIO"
else
  echo "commons-io: not found, using the stub (stub-commons-io/)"
fi
TIMEOUT=""
if command -v timeout >/dev/null 2>&1; then TIMEOUT="timeout 600"; fi

# build <name> [--mutate=x]
build() {
  name=$1; shift
  mkdir -p "$OUT/$name"
  if ! java "$HERE/tools/Prepare.java" "$ROOT" "$OUT/$name/gen" "$@" >"$OUT/$name.prep" 2>&1; then
    grep -v '^Picked up JAVA_TOOL_OPTIONS' "$OUT/$name.prep" >&2
    return 1
  fi
  grep -v '^Picked up JAVA_TOOL_OPTIONS' "$OUT/$name.prep" >&2
  if [ -n "$CIO" ]; then
    CP="$HERE/stub:$CIO"; EXTRA=""
  else
    CP="$HERE/stub"; EXTRA=$(find "$HERE/stub-commons-io" -name '*.java')
  fi
  # shellcheck disable=SC2046,SC2086
  if ! javac -nowarn -d "$OUT/$name/classes" -cp "$CP" $(find "$HERE/stub" "$OUT/$name/gen" "$HERE/src" -name '*.java') $EXTRA \
       >"$OUT/$name.javac" 2>&1; then
    grep -v '^Picked up JAVA_TOOL_OPTIONS' "$OUT/$name.javac" >&2
    return 1
  fi
}

# runjvm <build> <args...>, from / (an app process's working directory)
runjvm() {
  b=$1; shift
  CPR="$OUT/$b/classes"
  if [ -n "$CIO" ]; then CPR="$CPR:$CIO"; fi
  (cd / && $TIMEOUT java -cp "$CPR" com.solarized.firedown.harness.TaskHarness "$@")
}

FAILS=0
LOG="$OUT/all.log"
: > "$LOG"

suite_group() {
  echo
  echo "== group $1 =="
  runjvm real "$@" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' | tee -a "$LOG"
}

# mutant <label> <mutation> <groups> <must-fail-regex> <what>
mutant() {
  label=$1; mut=$2; groups=$3; must=$4; what=$5
  echo
  echo "== mutant $label: $what =="
  if ! build "m-$mut" "--mutate=$mut" 2>"$OUT/m-$mut.err"; then
    cat "$OUT/m-$mut.err"
    echo "FAIL  10.$label.builds  [the mutant did not build: the anchor moved?]" | tee -a "$LOG"
    return
  fi
  : > "$OUT/m-$mut.log"
  for g in $groups; do
    runjvm "m-$mut" "$g" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' >> "$OUT/m-$mut.log"
  done
  hits=$(grep -E "^FAIL  ($must)" "$OUT/m-$mut.log" | cut -c7- | cut -d' ' -f1 | tr '\n' ' ')
  if [ -n "$hits" ]; then
    echo "PASS  10.$label.has-teeth  [fails on the mutant: $hits]" | tee -a "$LOG"
  else
    echo "FAIL  10.$label.has-teeth  [no check matching /$must/ failed on the mutant]" | tee -a "$LOG"
  fi
  all=""
  for c in $(grep '^FAIL' "$OUT/m-$mut.log" | cut -c7- | cut -d' ' -f1); do
    if grep -q "^FAIL  $c " "$LOG"; then all="$all $c(also-real)"; else all="$all $c"; fi
  done
  echo "      every check failing on this mutant:$all"
}

run_mutants() {
  mutant M1 instance-runnable "5 4" "5b\.gif\.second-instance-cancel|5c\.gif\.second-instance-cancel" \
    "sCurrentRunnable as an instance field"
  echo "INFO  10.M1  group 4 is expected to PASS on M1: its starts all land on ONE instance, where an instance field and a static one are the same; M2 is group 4's mutant"
  mutant M2 shared-inputs "4" "4a\.|4b\." \
    "the getters read the LATEST start's inputs"
  mutant M3 reset-stop "3" "3a\." \
    "TaskRunnable.run() resets the stop flag"
  mutant M4 audio-stop-npe "3" "3b\.audio\.no-exception-on-stop|3d\.audio\.empty-list\.no-exception" \
    "AudioEncodeTask.stop() without the null check (the shipped NPE)"
}

summary() {
  p=$(grep -c '^PASS' "$LOG")
  f=$(grep -c '^FAIL' "$LOG")
  echo
  echo "== $p passed, $f failed =="
  if [ "$f" -gt 0 ]; then
    grep '^FAIL' "$LOG" | cut -c7- | cut -d' ' -f1 | sed 's/^/  failed: /'
    exit 1
  fi
  exit 0
}

MODE="${1:-suite}"
case "$MODE" in
  stress)
    build real || exit 2
    suite_group 9 "${2:-20}" "${3:-1}"
    summary
    ;;
  group)
    build real || exit 2
    suite_group "${2:-1}"
    summary
    ;;
  mutants)
    run_mutants
    summary
    ;;
  suite)
    build real || exit 2
    for g in 1 2 3 4 5 6 7 8 11; do suite_group "$g"; done
    suite_group 9 3 1
    run_mutants
    summary
    ;;
  *)
    echo "usage: run.sh [suite | stress [sec] [seed] | group <n> | mutants]"
    exit 2
    ;;
esac

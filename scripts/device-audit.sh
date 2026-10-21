#!/bin/sh
# device-audit.sh — the real app browses sites on the phone and reports what
# its Captured sheet got wrong: clips named after the page or by a file name,
# two tiles with one name, players it never captured. Builds and installs the
# debug app and its test APK, runs SiteAuditTest on the attached phone (it
# loads each site in a real GeckoView with the real extensions, agrees to the
# consent wall, scrolls, waits for captures), pulls what it recorded to
# build/device-audit/<run>/ and judges it (scripts/device-audit/judge.mjs).
#
#   scripts/device-audit.sh                         the bundled list (app/src/androidTest/assets/site-audit/sites.txt)
#   scripts/device-audit.sh https://news.example/   these URLs only
#   scripts/device-audit.sh --only TEXT             a subset of the list
#   scripts/device-audit.sh --sites my-sites.txt    another list (one URL per line, # comments)
#   scripts/device-audit.sh --compare build/device-audit/<older run>
#
# Options: --limit N, --wait MS (pause after consent and after the scroll,
# default 10000), --no-build (use what is installed), -s SERIAL (one of
# several phones), --compare DIR (say what changed against an older run),
# --keep-volume (leave the media volume alone; otherwise it is muted for the
# run, pages autoplay with sound).
#
# The phone must be unlocked or have no secure lock (the test shows over the
# keyguard and keeps the screen on); stay-awake is turned on for the run and
# back off after. A run of the whole list takes about a minute per site. The
# recordings stay on this machine under build/ (gitignored): they hold the
# pages' markup and request lists, though cookies and tokens are redacted.
#
# Judge a run again after changing a rule, without the phone:
#   node scripts/device-audit/judge.mjs build/device-audit/<run> [--verbose]

set -eu

PKG=com.solarized.firedown
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
CLASS=$PKG.siteaudit.SiteAuditTest

cd "$(dirname "$0")/.."

SERIAL=""
BUILD=1
ONLY=""
LIMIT=""
WAIT=""
SITES_FILE=""
COMPARE=""
KEEP_VOLUME=0
URLS=""
while [ $# -gt 0 ]; do
    case "$1" in
        -s) SERIAL="$2"; shift 2 ;;
        --no-build) BUILD=0; shift ;;
        --only) ONLY="$2"; shift 2 ;;
        --limit) LIMIT="$2"; shift 2 ;;
        --wait) WAIT="$2"; shift 2 ;;
        --sites) SITES_FILE="$2"; shift 2 ;;
        --compare) COMPARE="$2"; shift 2 ;;
        --keep-volume) KEEP_VOLUME=1; shift ;;
        -h|--help) sed -n '2,35p' "$0"; exit 0 ;;
        -*) echo "unknown option $1" >&2; exit 2 ;;
        *) URLS="$URLS $1"; shift ;;
    esac
done

adb_() {
    if [ -n "$SERIAL" ]; then
        adb -s "$SERIAL" "$@"
    else
        adb "$@"
    fi
}

if ! command -v adb >/dev/null 2>&1; then
    echo "adb not found (Android SDK platform-tools)" >&2
    exit 1
fi
if ! adb_ get-state >/dev/null 2>&1; then
    echo "no phone attached (adb devices), or several: pass -s SERIAL" >&2
    exit 1
fi

if [ -n "$SITES_FILE" ]; then
    LIST=$(sed -e 's/^[[:space:]]*//' -e '/^#/d' -e '/^$/d' "$SITES_FILE" | awk '{print $1}')
    URLS="$URLS $LIST"
fi
SITES=$(echo $URLS | tr ' ' ',')

if [ "$BUILD" = 1 ]; then
    ./gradlew --quiet :app:installDebug :app:installDebugAndroidTest
fi

STAMP=$(date +%Y-%m-%d_%H%M%S)
OUT=build/device-audit/$STAMP
mkdir -p "$OUT"

# Single-quote a value for the phone's shell.
q() {
    printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

EXTRA="-e class $CLASS -e out $STAMP"
[ -n "$SITES" ] && EXTRA="$EXTRA -e sites $(q "$SITES")"
[ -n "$ONLY" ] && EXTRA="$EXTRA -e only $(q "$ONLY")"
[ -n "$LIMIT" ] && EXTRA="$EXTRA -e limit $(q "$LIMIT")"
[ -n "$WAIT" ] && EXTRA="$EXTRA -e wait $(q "$WAIT")"

VOLUME=""
cleanup() {
    [ -n "${LOG_PID:-}" ] && kill "$LOG_PID" 2>/dev/null || true
    [ -n "${PROGRESS_PID:-}" ] && kill "$PROGRESS_PID" 2>/dev/null || true
    adb_ shell svc power stayon false </dev/null >/dev/null 2>&1 || true
    if [ -n "$VOLUME" ]; then
        adb_ shell cmd media_session volume --stream 3 --set "$VOLUME" </dev/null >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT INT TERM

adb_ shell input keyevent KEYCODE_WAKEUP </dev/null >/dev/null 2>&1 || true
adb_ shell wm dismiss-keyguard </dev/null >/dev/null 2>&1 || true
adb_ shell svc power stayon true </dev/null >/dev/null 2>&1 || true
if [ "$KEEP_VOLUME" = 0 ]; then
    VOLUME=$(adb_ shell cmd media_session volume --stream 3 --get </dev/null 2>/dev/null \
        | sed -n 's/.*volume is \([0-9][0-9]*\).*/\1/p' | head -n 1)
    adb_ shell cmd media_session volume --stream 3 --set 0 </dev/null >/dev/null 2>&1 || true
fi

# The whole log of the run (the extensions' debug lines among it: a capture's
# reject reason is there), and the audit's own progress lines on screen.
adb_ logcat -T 1 -v threadtime </dev/null >"$OUT/logcat.txt" 2>&1 &
LOG_PID=$!
adb_ logcat -T 1 -v brief SiteAudit:I '*:S' </dev/null 2>/dev/null | sed -u 's/^I\/SiteAudit([ 0-9]*): /  /' &
PROGRESS_PID=$!

echo "device audit $STAMP — results in $OUT"
set +e
set -f
# shellcheck disable=SC2086
adb_ shell am instrument -w -r $EXTRA $RUNNER </dev/null >"$OUT/instrument.txt" 2>&1
STATUS=$?
set +f
set -e
sleep 1

if grep -q "FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed" "$OUT/instrument.txt"; then
    echo "the test did not finish cleanly; the end of its output:" >&2
    grep -v "^INSTRUMENTATION_STATUS" "$OUT/instrument.txt" | tail -n 25 >&2
fi

adb_ pull "/sdcard/Android/data/$PKG/files/site-audit/$STAMP/." "$OUT" >/dev/null 2>&1 || {
    echo "nothing to pull from /sdcard/Android/data/$PKG/files/site-audit/$STAMP" >&2
    exit 1
}
adb_ shell rm -rf "/sdcard/Android/data/$PKG/files/site-audit/$STAMP" </dev/null >/dev/null 2>&1 || true

if command -v node >/dev/null 2>&1; then
    echo
    if [ -n "$COMPARE" ]; then
        node scripts/device-audit/judge.mjs "$OUT" --compare "$COMPARE"
    else
        node scripts/device-audit/judge.mjs "$OUT"
    fi
else
    echo "node not found: judge the run with node scripts/device-audit/judge.mjs $OUT"
fi
echo
echo "recorded: $OUT (per site: entities, emits, census, around, requests, screen.jpg)"
exit $STATUS

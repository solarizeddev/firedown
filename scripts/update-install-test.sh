#!/usr/bin/env bash
# Tests the in-app update INSTALL on a phone, from a laptop: the sheet's
# "Preparing update… N%" / "Installing…" footer, the notification route, the
# failure paths, and a real update to a newer version.
#
# It builds two debug APKs of this checkout (the current versionCode and the
# next one), installs the first, and fakes "an update is ready" by copying the
# second where the updater keeps a downloaded APK. The app side is the
# debug-only UpdateTestReceiver (app/src/debug), which the script talks to
# with `am broadcast`; the install itself is the real code, on the phone.
#
# Cases, run in this order (--only picks some):
#   sheet         Install from the sheet: the footer counts the copy up, then
#                 Android answers. On Android 12+ it installs the update
#                 without asking (Firedown requests that); otherwise its
#                 "update this app?" dialog appears, the sheet closes, and
#                 cancelling the dialog leaves no error.
#   refused       A large file that is not an APK: the footer keeps counting
#                 through a rotation, a sheet rebuilt mid-copy (a font size
#                 change) shows the copy, a second install started from the
#                 notification route while the first copies does nothing,
#                 Android refuses the file, the error line and the button
#                 come back, and the button works again.
#   missing       The APK disappears after the sheet showed: the error line,
#                 and the "update failed" notification.
#   notification  Install from the "Tap to update" notification with the app
#                 in the background: the same notification shows the
#                 progress, then Android installs or asks.
#   install       A real update: Install, confirm Android's dialog (tapped
#                 for you when the button can be found), the app becomes the
#                 next version and posts "Firedown updated". Skipped when an
#                 earlier case already updated without asking. The original
#                 version is put back at the end. --no-install skips it.
#
# The sheet's footer is read from the debug build's UpdateSheetTrace log
# lines, the phases from UpdateInstallTrace: a screen dump waits for the
# screen to go still, which it never does while the bar counts up.
#
# Usage:  scripts/update-install-test.sh [--skip-build] [--only a,b] [--no-install]
#   --skip-build  reuse the two APKs the last run built (build/update-install-test/apks)
#   --only        comma-separated cases
#   --no-install  skip the real update
# Output: build/update-install-test/<date>/ (override with OUT_DIR):
# results.txt, logcat.txt, a screenshot per step, the build logs.
# More than one device attached: set ANDROID_SERIAL.
#
# Runs on macOS (its bash 3.2 and awk are enough) and Linux. adb is taken
# from PATH, else from the SDK Android Studio uses (ADB=… overrides), and the
# build uses JAVA_HOME, else java on PATH, else Android Studio's JDK.
#
# What it changes on the phone, and puts back: the debug build of this
# checkout replaces the installed Firedown (data kept; the phone must already
# have a DEBUG build signed on this computer, or none), animations are turned
# off (an indeterminate progress bar never lets the screen be read), the
# screen stays on, rotation is fixed, the font size changes for a moment,
# Firedown may install apps, and it may post notifications (taken back only
# if it could not before). Each case force-stops Firedown first.

set -Euo pipefail

PKG=com.solarized.firedown
ACTIVITY="$PKG/$PKG.phone.BrowserActivity"
RX="$PKG/$PKG.updatetest.UpdateTestReceiver"
RXA="$PKG.updatetest"
STAGE=/data/local/tmp/fd-update-test.apk
# The checkout to build; the selftest points it at a scratch copy.
ROOT=${UPDATE_INSTALL_TEST_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
GRADLE_FILE="$ROOT/app/build.gradle"
CACHE="$ROOT/build/update-install-test"
ALL_CASES="sheet refused missing notification install"

ADB_BIN=adb
SKIP_BUILD=0
NO_INSTALL=0
ONLY=
OUT=
LOGCAT_PID=
GRADLE_BACKUP=
SDK=0
PHONE=
BASE_CODE=0
NEWER_CODE=0
BASE_APK=
NEWER_APK=
TEST_NAME=
PUT_MODE=
APK_PATH=
STEP=0
PASSES=0
FAILS=0
WARNS=0
LAST_STATE=
PHASE=
# Phone settings to put back: "name value" pairs, applied in cleanup.
RESTORE_GLOBAL=()
RESTORE_SYSTEM=()
RESTORE_APPOPS=
REVOKE_NOTIFICATIONS=0
INSTALLED_NEWER=0
PHONE_TOUCHED=0
SCREEN_READ=0
FONT_SCALE_SAVED=
OUTCOME=
SILENT_SEEN=0

say() { printf '%s\n' "$*"; }
die() { printf 'STOPPED: %s\n' "$*" >&2; exit 1; }

# has TEXT NEEDLE: TEXT contains NEEDLE. Never test `producer | grep -q` in a
# condition: under pipefail grep's early exit kills a long producer with
# SIGPIPE, and a match reads as a miss. A phone's dumpsys and logcat are
# megabytes.
has() {
    case $1 in
        *"$2"*) return 0 ;;
    esac
    return 1
}

# Every adb call reads from /dev/null: `adb shell` otherwise swallows the
# stdin of the loop it runs in.
adbq() {
    "$ADB_BIN" "$@" < /dev/null
}

# adb: $ADB, else the one on PATH, else the SDK's (sdk.dir in
# local.properties, which Android Studio writes, then $ANDROID_HOME,
# $ANDROID_SDK_ROOT and Android Studio's default SDK folders).
find_adb() {
    local sdk dir
    if [ -n "${ADB:-}" ]; then
        printf '%s' "$ADB"
        return
    fi
    if command -v adb >/dev/null 2>&1; then
        printf adb
        return
    fi
    sdk=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" 2>/dev/null | head -n 1)
    for dir in "$sdk" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
            "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [ -n "$dir" ] && [ -x "$dir/platform-tools/adb" ]; then
            printf '%s' "$dir/platform-tools/adb"
            return
        fi
    done
}

# Gradle needs a JDK. A terminal often has none (macOS answers `java` with a
# stub that only says so) while Android Studio carries its own: use that.
find_jdk() {
    local d
    if [ -n "${JAVA_HOME:-}" ] || java -version >/dev/null 2>&1; then
        return 0
    fi
    for d in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
            "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
            /opt/android-studio/jbr "$HOME/android-studio/jbr" \
            /snap/android-studio/current/jbr; do
        if [ -x "$d/bin/java" ]; then
            export JAVA_HOME="$d"
            say "No JDK on PATH: using Android Studio's ($d)"
            return 0
        fi
    done
    die "no JDK found for Gradle. Set JAVA_HOME (Android Studio's: Settings > Build Tools > Gradle > Gradle JDK shows its path)."
}

remote() {
    adbq shell "$@" 2>/dev/null | tr -d '\r'
}

# A value quoted for the phone's shell (adb joins the arguments into one
# command line).
q() {
    local v=$1
    v=${v//\'/\'\\\'\'}
    printf "'%s'" "$v"
}

# The value of key=... in a space-separated answer.
kv() {
    printf '%s\n' "$2" | tr ' ' '\n' | sed -n "s/^$1=//p" | head -n 1
}

name_for() {
    local c=$1
    printf '%d.%d.%d' $((c / 1000)) $((c / 100 % 10)) $((c % 100))
}

ask() {
    local answer=
    if [ ! -r /dev/tty ]; then
        return 0
    fi
    printf '%s ' "$1" > /dev/tty
    IFS= read -r answer < /dev/tty || answer=
    printf '%s' "$answer"
}

# ── results ──────────────────────────────────────────────────────────────

report() {
    printf '  %-4s %s\n' "$1" "$2" | tee -a "$OUT/results.txt"
}
pass() { PASSES=$((PASSES + 1)); report PASS "$*"; }
fail() { FAILS=$((FAILS + 1)); report FAIL "$*"; }
warn() { WARNS=$((WARNS + 1)); report WARN "$*"; }
info() { report INFO "$*"; }

# A line in the phone's log, so logcat.txt reads step by step.
mark() {
    local msg=$*
    msg=${msg//\'/}
    adbq shell log -t FDUPDTEST "'$msg'" >/dev/null 2>&1 || true
}

shot() {
    STEP=$((STEP + 1))
    local file
    file=$(printf '%s/%02d-%s.png' "$OUT" "$STEP" "$1")
    adbq exec-out screencap -p > "$file" 2>/dev/null || rm -f "$file"
}

# ── the app's test receiver ──────────────────────────────────────────────

# Sends one action to UpdateTestReceiver and prints its answer. -f 32 is
# FLAG_INCLUDE_STOPPED_PACKAGES: every case force-stops the app first.
rx() {
    local action=$1 out
    shift
    out=$(remote am broadcast -f 32 -n "$RX" -a "$RXA.$action" "$@")
    rx_data "$out"
}

# "Broadcast completed: result=1, data="…"" → the data.
rx_data() {
    case $1 in
        *'data="'*)
            local d=${1#*data=\"}
            printf '%s' "${d%\"*}"
            ;;
        *)
            printf 'error no answer: %s' "$(printf '%s' "$1" | tail -n 1)"
            ;;
    esac
}

# Reads the app's state into LAST_STATE and its phase into PHASE. Never call
# it inside $(…): a subshell's assignments are lost.
read_state() {
    LAST_STATE=$(rx STATE)
    PHASE=$(kv phase "$LAST_STATE")
}

# wait_state PHASE SECONDS [MIN_GEN]: true once the app reports PHASE.
wait_state() {
    local want=$1 secs=$2 mingen=${3:-0} end gen
    end=$(($(date +%s) + secs))
    while [ "$(date +%s)" -lt "$end" ]; do
        read_state
        if [ "$PHASE" = "$want" ]; then
            gen=$(kv gen "$LAST_STATE")
            if [ "${gen:-0}" -ge "$mingen" ]; then
                return 0
            fi
        fi
        sleep 0.5
    done
    return 1
}

# ── the screen ───────────────────────────────────────────────────────────

# ui_dump [TRIES] [all]: the screen into $OUT/ui.xml. uiautomator waits for
# the screen to go still and gives up after ~10 s, so it cannot read a screen
# whose progress bar is counting up: the footer checks read UpdateSheetTrace.
# It reads only the window Android counts as active, which on some phones
# (Samsung) stays the launcher while the notification shade is open; "all"
# reads every window (--windows, Android 11+; older versions ignore it).
# The file is removed first: an all-windows dump that finds no window says
# "dumped" without writing one.
ui_dump() {
    local i=0 tries=${1:-4} opt='' out
    if [ "${2:-}" = all ]; then
        opt=--windows
    fi
    while [ "$i" -lt "$tries" ]; do
        i=$((i + 1))
        remote rm -f /sdcard/fd-update-ui.xml >/dev/null
        out=$(remote uiautomator dump $opt /sdcard/fd-update-ui.xml)
        if has "$out" 'dumped to'; then
            if adbq exec-out cat /sdcard/fd-update-ui.xml > "$OUT/ui.xml" 2>/dev/null; then
                return 0
            fi
        fi
        sleep 0.5
    done
    : > "$OUT/ui.xml"
    return 1
}

# ui_find ATTR VALUE MODE [FILE]: the centre "x y" of the first node whose
# ATTR equals VALUE (MODE=exact) or contains it (MODE=contains).
ui_find() {
    # One record per <node: split here rather than with a multi-character
    # RS, which older BSD awk (macOS) does not support.
    awk -v attr="$1" -v want="$2" -v mode="$3" '
        { all = all $0 "\n" }
        END {
            key = " " attr "=\""
            n = split(all, nodes, "<node")
            for (r = 2; r <= n; r++) {
                node = nodes[r]
                i = index(node, key)
                if (i == 0) continue
                rest = substr(node, i + length(key))
                v = substr(rest, 1, index(rest, "\"") - 1)
                if (mode == "exact" && v != want) continue
                if (mode == "contains" && index(v, want) == 0) continue
                if (!match(node, /bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"/)) continue
                b = substr(node, RSTART + 8, RLENGTH - 9)
                gsub(/[^0-9]+/, " ", b)
                if (split(b, c, " ") < 4) continue
                if (c[3] + 0 <= c[1] + 0 || c[4] + 0 <= c[2] + 0) continue
                printf "%d %d\n", (c[1] + c[3]) / 2, (c[2] + c[4]) / 2
                exit
            }
        }' "${4:-$OUT/ui.xml}"
}

# ui_text ID [FILE]: the text of the node with this resource id.
ui_text() {
    awk -v want="$PKG:id/$1" '
        { all = all $0 "\n" }
        END {
            n = split(all, nodes, "<node")
            for (r = 2; r <= n; r++) {
                node = nodes[r]
                if (!index(node, " resource-id=\"" want "\"")) continue
                i = index(node, " text=\"")
                if (i == 0) exit
                rest = substr(node, i + 7)
                print substr(rest, 1, index(rest, "\"") - 1)
                exit
            }
        }' "${2:-$OUT/ui.xml}"
}

ui_has() {
    [ -n "$(ui_find resource-id "$PKG:id/$1" exact)" ]
}

tap_at() {
    remote input tap "$1" "$2" >/dev/null
}

tap_id() {
    local xy
    xy=$(ui_find resource-id "$PKG:id/$1" exact)
    [ -n "$xy" ] || return 1
    # shellcheck disable=SC2086
    tap_at $xy
}

# The focused window, lower-cased. awk reads to the end (see has()).
focus() {
    remote dumpsys window | awk '/mCurrentFocus=|mFocusedWindow=/ { if (!n++) print tolower($0) }'
}

installer_up() {
    has "$(focus)" packageinstaller
}

ours_up() {
    has "$(focus)" "$PKG"
}

shade_up() {
    local f
    f=$(focus)
    has "$f" notificationshade || has "$f" 'statusbar}'
}

wait_installer() {
    local end=$(($(date +%s) + $1))
    while [ "$(date +%s)" -lt "$end" ]; do
        if installer_up; then
            return 0
        fi
        sleep 0.5
    done
    return 1
}

close_installer() {
    local i
    for i in 1 2 3; do
        installer_up || return 0
        remote input keyevent KEYCODE_BACK >/dev/null
        sleep 1
    done
    installer_up && return 1
    return 0
}

installed_code() {
    remote dumpsys package "$PKG" | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -n 1
}

launch() {
    local end
    remote input keyevent KEYCODE_WAKEUP >/dev/null
    remote wm dismiss-keyguard >/dev/null
    remote am start -W -n "$ACTIVITY" >/dev/null
    end=$(($(date +%s) + 15))
    while [ "$(date +%s)" -lt "$end" ]; do
        ours_up && return 0
        sleep 0.5
    done
    return 1
}

wait_sheet() {
    local end=$(($(date +%s) + 25))
    SCREEN_READ=0
    while [ "$(date +%s)" -lt "$end" ]; do
        if ui_dump; then
            SCREEN_READ=1
            if ui_has update_sheet_install; then
                return 0
            fi
        fi
        sleep 1
    done
    return 1
}

# Why wait_sheet failed, for the report.
no_sheet() {
    if [ "$SCREEN_READ" = 1 ]; then
        fail "the update sheet did not appear (another sheet on top? see the screenshot)"
    else
        fail "the screen could not be read (uiautomator never saw it idle: a page animating in the open tab?). Open a still page in Firedown and run again"
    fi
    shot "$1"
}

rotate() {
    remote settings put system accelerometer_rotation 0
    remote settings put system user_rotation "$1"
}

# The browser handles a rotation itself, so the sheet lives through it. A
# font size change it does not handle rebuilds the activity and the sheet
# from saved state.
font_bump() {
    local to=1.15
    if [ "$FONT_SCALE_SAVED" = 1.15 ]; then
        to=1.3
    fi
    remote settings put system font_scale "$to" >/dev/null
}

font_restore() {
    restore_setting system font_scale "$FONT_SCALE_SAVED"
}

# wait_outcome SECONDS: what Android did with an install. OUTCOME is dialog
# (its "update this app?" dialog is up), updated (it installed the update
# without asking: from Android 12 an app may update itself, and Firedown
# asks to), failed (the app reports FAILED) or none.
wait_outcome() {
    local end=$(($(date +%s) + $1))
    OUTCOME=none
    while [ "$(date +%s)" -lt "$end" ]; do
        if installer_up; then
            OUTCOME=dialog
            return 0
        fi
        if [ "$(installed_code)" = "$NEWER_CODE" ]; then
            OUTCOME=updated
            INSTALLED_NEWER=1
            SILENT_SEEN=1
            return 0
        fi
        read_state
        if [ "$PHASE" = FAILED ]; then
            OUTCOME=failed
            return 0
        fi
        sleep 0.5
    done
    return 1
}

# After Android installed the update without asking: the app was replaced
# before Android's answer reached it (the trace ends at INSTALLING), then
# posted "Firedown updated".
check_updated() {
    local name=$1 end n668=
    if [ "$SDK" -ge 31 ]; then
        pass "Android installed the update without asking: Firedown is now $(name_for "$NEWER_CODE") (Android 12+ lets an app update itself)"
    else
        fail "Android $SDK installed without asking, which it should not do below Android 12"
    fi
    trace_ok "$name" 1 "PREPARING INSTALLING"
    end=$(($(date +%s) + 20))
    while [ "$(date +%s)" -lt "$end" ]; do
        LAST_STATE=$(rx STATE)
        n668=$(kv n668 "$LAST_STATE")
        if [ "$n668" = shown ]; then
            break
        fi
        sleep 1
    done
    if [ "$n668" = shown ]; then
        pass "the \"Firedown updated\" notification was posted"
    else
        fail "no \"Firedown updated\" notification: $LAST_STATE"
    fi
    if [ "$(kv n666 "$LAST_STATE")" = none ]; then
        pass "no progress notification left behind"
    else
        fail "a progress notification was left behind: $(kv n666 "$LAST_STATE")"
    fi
}

dialog_but_silent_requested() {
    if [ "$SDK" -ge 31 ]; then
        info "Android asked although Firedown requests an update without asking on Android 12+ (another installer may own its updates)"
    fi
}

# ── the logcat ───────────────────────────────────────────────────────────

# The phone's log from this case's last start marker to the next case's.
case_log() {
    awk -v m="case $1 start" '
        index($0, "FDUPDTEST") && index($0, ": case ") {
            if (index($0, m)) { buf = ""; on = 1 } else { on = 0 }
            next
        }
        on { buf = buf $0 "\n" }
        END { printf "%s", buf }' "$OUT/logcat.txt"
}

# check_trace NTH EXPECT: reads the UpdateInstallTrace lines of the NTH
# install (by generation) in a case log on stdin. EXPECT is the phases in
# order ("PREPARING INSTALLING IDLE"). Prints what it saw; false when the
# order differs, a percent went back, or a copy that ended in INSTALLING
# never reached 100%.
check_trace() {
    awk -v nth="$1" -v want="$2" '
        /UpdateInstallTrace: / {
            line = $0
            sub(/.*UpdateInstallTrace: /, "", line)
            n = split(line, f, " ")
            split("", v)
            for (i = 1; i <= n; i++) {
                k = index(f[i], "=")
                if (k) v[substr(f[i], 1, k - 1)] = substr(f[i], k + 1)
            }
            g = v["gen"]
            if (!(g in order)) order[g] = ++gens
            if (order[g] != nth) next
            ph = v["phase"]
            pc = v["percent"] + 0
            if (ph == "PREPARING") {
                steps++
                if (pc < last) bad = "the percent went back from " last " to " pc
                last = pc
            }
            if (ph != prev) {
                seq = seq (seq == "" ? "" : " ") ph
                prev = ph
            }
        }
        END {
            if (seq == "") { print "no trace for install " nth; exit 1 }
            printf "%s (%d percent steps, last %d%%)\n", seq, steps, last
            if (seq != want) exit 1
            if (bad != "") { print bad; exit 1 }
            if (index(want, "INSTALLING") && last != 100) { print "the copy never reached 100%"; exit 1 }
        }'
}

# Counts the installs (generations) in a case log on stdin.
count_installs() {
    awk '/UpdateInstallTrace: / { if (match($0, /gen=[0-9]+/)) g[substr($0, RSTART, RLENGTH)] = 1 }
         END { n = 0; for (k in g) n++; print n }'
}

# log_wait CASE TEXT [SECONDS]: the case's log holds TEXT, waiting for the
# log to catch up. grep -c reads to the end (see has()).
log_wait() {
    local end=$(($(date +%s) + ${3:-5})) n
    while :; do
        n=$(case_log "$1" | grep -cF -- "$2")
        if [ "${n:-0}" -gt 0 ]; then
            return 0
        fi
        if [ "$(date +%s)" -ge "$end" ]; then
            return 1
        fi
        sleep 0.5
    done
}

# footers CASE: the sheet's footer renders in a case's log (UpdateSheetTrace),
# one "SHEET FOOTER PERCENT" line each.
footers() {
    case_log "$1" | awk '/UpdateSheetTrace: / {
        line = $0
        sub(/.*UpdateSheetTrace: /, "", line)
        n = split(line, f, " ")
        sheet = ""; footer = ""; pct = ""
        for (i = 1; i <= n; i++) {
            k = index(f[i], "=")
            key = substr(f[i], 1, k - 1)
            v = substr(f[i], k + 1)
            if (key == "sheet") sheet = v
            else if (key == "footer") footer = v
            else if (key == "percent") pct = v
        }
        print sheet, footer, pct
    }'
}

# count_footers CASE FOOTER
count_footers() {
    footers "$1" | awk -v want="$2" '$2 == want { n++ } END { print n + 0 }'
}

trace_ok() {
    local name=$1 nth=$2 want=$3 out
    # The last trace line can still be on its way from the phone.
    sleep 1
    if out=$(case_log "$name" | check_trace "$nth" "$want"); then
        pass "phases in order: $out"
    else
        fail "phases: $out (wanted $want)"
    fi
}

# ── the ready APK ────────────────────────────────────────────────────────

# Puts the file at the updater's APK path and marks it ready. KIND is apk
# (the newer build) or junk (MB megabytes of zeros, not an APK).
prepare_ready() {
    local kind=$1 mb=${2:-0} ans
    ans=$(rx CLEAR)
    case $ans in
        cleared*) ;;
        *) die "the app's test receiver did not answer ($ans). Is this checkout's debug build installed?" ;;
    esac
    put_file "$kind" "$mb"
    ans=$(rx MARK_READY --ei code "$NEWER_CODE" --es name "$(q "$TEST_NAME")")
    if [ "$(kv readable "$ans")" != true ] && [ -z "$PUT_MODE" ]; then
        # The app could not read what the shell wrote: write it as the app.
        PUT_MODE=runas
        put_file "$kind" "$mb"
        ans=$(rx MARK_READY --ei code "$NEWER_CODE" --es name "$(q "$TEST_NAME")")
    fi
    if [ "$(kv readable "$ans")" != true ]; then
        die "Firedown cannot read the file at $APK_PATH ($ans)."
    fi
    PUT_MODE=${PUT_MODE:-shell}
}

put_file() {
    local kind=$1 mb=$2 cmd
    if [ "$kind" = apk ]; then
        cmd="cp $STAGE $APK_PATH"
    else
        cmd="dd if=/dev/zero of=$APK_PATH bs=1048576 count=$mb"
    fi
    if [ "$PUT_MODE" = runas ]; then
        remote run-as "$PKG" sh -c "$(q "$cmd")" >/dev/null
    else
        remote "$cmd" >/dev/null
    fi
}

remove_file() {
    if [ "$PUT_MODE" = runas ]; then
        remote run-as "$PKG" rm -f "$APK_PATH" >/dev/null
    else
        remote rm -f "$APK_PATH" >/dev/null
    fi
}

ensure_base() {
    if [ "$(installed_code)" = "$BASE_CODE" ]; then
        return 0
    fi
    install_base
}

install_base() {
    local out
    say "Installing $(name_for "$BASE_CODE") ($BASE_CODE)…"
    out=$(adbq install -r -d "$BASE_APK" 2>&1 | tr -d '\r')
    case $out in
        *Success*) INSTALLED_NEWER=0 ;;
        *UPDATE_INCOMPATIBLE*|*signatures*)
            die "the Firedown on the phone is signed with another key (a release build, or a debug build from another computer). Uninstall it first; that deletes its data." ;;
        *) die "adb install failed: $(printf '%s' "$out" | tail -n 1)" ;;
    esac
}

# ── cases ────────────────────────────────────────────────────────────────

begin_case() {
    say ""
    say "== $1: $2" | tee -a "$OUT/results.txt"
    if installer_up; then
        close_installer || true
    fi
    rotate 0
    ensure_base
    remote am force-stop "$PKG" >/dev/null
    mark "case $1 start"
}

# footer_ok CASE: the sheet's footer counted the copy up (UpdateSheetTrace).
footer_ok() {
    local name=$1 n first last back
    log_wait "$name" 'footer=progress' 3 || true
    n=$(count_footers "$name" progress)
    if [ "$n" -gt 0 ]; then
        first=$(footers "$name" | awk '$2 == "progress" { print $3; exit }')
        last=$(footers "$name" | awk '$2 == "progress" { p = $3 } END { print p }')
        back=$(footers "$name" | awk '$2 == "progress" { if ($3 + 0 < p + 0) b = p " to " $3; p = $3 } END { print b }')
        if [ -n "$back" ]; then
            fail "the footer's percent went back ($back)"
        else
            pass "the footer showed the progress: $n renders, $first% to $last%"
        fi
    else
        fail "the sheet never showed the progress (no footer=progress in UpdateSheetTrace)"
    fi
}

case_sheet() {
    begin_case sheet "install from the sheet, then Android's answer"
    prepare_ready apk
    launch || { fail "Firedown did not come to the front (is the phone locked?)"; shot sheet-no-app; return; }
    if ! wait_sheet; then
        no_sheet sheet-missing
        return
    fi
    pass "the update sheet appeared"
    shot sheet-ready
    tap_id update_sheet_install || { fail "could not find the Install button"; return; }
    shot sheet-progress
    wait_outcome 90 || true
    footer_ok sheet
    case $OUTCOME in
        updated)
            check_updated sheet
            info "the cancel path needs Android's dialog, which this phone skips; not tested"
            shot sheet-updated
            return
            ;;
        failed)
            fail "the install failed: $LAST_STATE"
            shot sheet-failed
            return
            ;;
        none)
            fail "neither Android's dialog nor the update within 90 s (state: $LAST_STATE)"
            shot sheet-nothing
            return
            ;;
    esac
    pass "Android's dialog appeared"
    dialog_but_silent_requested
    shot sheet-android-dialog
    read_state
    if [ "$PHASE" = IDLE ]; then
        pass "the install state is IDLE once Android has the update"
    else
        fail "the install state is not IDLE: $LAST_STATE"
    fi
    trace_ok sheet 1 "PREPARING INSTALLING IDLE"
    if [ "$(count_footers sheet closed)" -gt 0 ]; then
        pass "the sheet closed when Android answered"
    else
        fail "the sheet did not close when Android answered"
    fi
    close_installer || fail "could not close Android's dialog"
    sleep 2
    read_state
    if [ "$PHASE" = IDLE ]; then
        pass "cancelling Android's dialog leaves the state IDLE"
    else
        fail "after cancelling Android's dialog: $LAST_STATE"
    fi
    if ui_dump; then
        if ui_has update_sheet_install || ui_has update_sheet_error; then
            fail "the sheet is showing again after the cancel"
        else
            pass "the sheet stays closed after the cancel"
        fi
    fi
    if [ "$(kv n667 "$LAST_STATE")" != none ]; then
        warn "cancelling Android's dialog posted the \"update failed\" notification"
    fi
    shot sheet-after-cancel
    [ "$(installed_code)" = "$BASE_CODE" ] || fail "the version changed although the install was cancelled"
}

# renders_after CASE MARK [FOOTER]: how many FOOTER renders (any, when
# empty) the case's log holds after the MARK line, waiting up to 3 s for the log to catch up.
renders_after() {
    local end=$(($(date +%s) + 3)) n
    while :; do
        n=$(case_log "$1" | awk -v m="FDUPDTEST: $2" -v f="${3:-}" '
            index($0, m) { on = 1; next }
            on && index($0, "UpdateSheetTrace: ") && (f == "" || index($0, "footer=" f " ")) { n++ }
            END { print n + 0 }')
        if [ "$n" -gt 0 ] || [ "$(date +%s)" -ge "$end" ]; then
            printf '%s' "$n"
            return
        fi
        sleep 0.5
    done
}

# rebuilt_ok CASE: after the "rebuild" mark the sheet is a new instance (the
# activity was recreated), and its first render shows the copy, not the
# button, unless the install had already ended by then.
rebuilt_ok() {
    local out first ended
    renders_after "$1" rebuild >/dev/null
    out=$(case_log "$1" | awk '
        function sheet_of(line) {
            if (match(line, /sheet=[0-9a-f]+/)) return substr(line, RSTART + 6, RLENGTH - 6)
            return ""
        }
        /FDUPDTEST: rebuild/ { on = 1; next }
        !on && /UpdateSheetTrace: / { before = sheet_of($0) }
        on && /UpdateInstallTrace: phase=(FAILED|IDLE)/ { ended = 1 }
        on && /UpdateSheetTrace: / {
            s = sheet_of($0)
            if (s != before && match($0, /footer=[a-z]+/)) {
                print substr($0, RSTART + 7, RLENGTH - 7), ended + 0
                exit
            }
        }')
    if [ -z "$out" ]; then
        info "the font size change did not rebuild the sheet; the rebuilt sheet was not checked"
        return
    fi
    first=${out% *}
    ended=${out#* }
    case $first in
        progress|installing)
            pass "the sheet rebuilt by a font size change came back showing the copy ($first)"
            ;;
        *)
            if [ "$ended" = 1 ]; then
                info "the install ended before the rebuilt sheet appeared; it shows \"$first\""
            else
                fail "the sheet rebuilt mid-copy came back showing \"$first\" instead of the progress"
            fi
            ;;
    esac
}

case_refused() {
    local mb free ans gen installs pre n
    begin_case refused "a file that is not an APK: rotation, a rebuilt sheet, a second install, the error, a retry"
    free=$(remote df -k /data | awk 'NR == 2 { print $4 }')
    free=${free:-0}
    if [ "$free" -gt 6291456 ]; then
        mb=1536
    elif [ "$free" -gt 4194304 ]; then
        mb=1024
    elif [ "$free" -gt 1572864 ]; then
        mb=384
    else
        info "less than 1.5 GB free on the phone: skipped"
        return
    fi
    prepare_ready junk "$mb"
    launch || { fail "Firedown did not come to the front"; return; }
    wait_sheet || { no_sheet refused-missing; return; }
    tap_id update_sheet_install || { fail "could not find the Install button"; return; }
    sleep 0.3
    read_state
    pre=$PHASE
    ans=$(rx TAP_PROMPT --es name "$(q "$TEST_NAME")")
    mark "rotate"
    rotate 1
    sleep 1.5
    shot refused-rotated
    read_state
    if [ "$PHASE" = PREPARING ]; then
        n=$(renders_after refused rotate progress)
        if [ "$n" -gt 0 ]; then
            pass "the footer kept counting after the rotation ($n renders)"
        else
            fail "the footer stopped counting after the rotation"
        fi
        mark "rebuild"
        font_bump
        sleep 2.5
        shot refused-rebuilt
        rebuilt_ok refused
        font_restore
    else
        info "the ${mb} MB copy ended before the rotation landed ($LAST_STATE); the rebuilt sheet was not checked"
    fi
    rotate 0
    if wait_state FAILED 90; then
        pass "Android refused the file and the state is FAILED"
    else
        fail "no FAILED state within 90 s: $LAST_STATE"
    fi
    footer_ok refused
    trace_ok refused 1 "PREPARING INSTALLING FAILED"
    installs=$(case_log refused | count_installs)
    if log_wait refused 'install already running' 3; then
        if [ "$installs" = 1 ]; then
            pass "a second install requested while the first copied was turned away ($ans)"
        else
            fail "the second request was turned away, yet $installs installs ran"
        fi
    elif [ "$pre" = PREPARING ]; then
        fail "a second install ran while the first was still copying ($installs installs)"
    elif [ "$installs" = 2 ]; then
        info "the first install ended before the second request arrived; the one-at-a-time rule was not exercised"
    else
        fail "the second request was neither turned away nor run ($ans)"
    fi
    sleep 1
    if [ "$(footers refused | awk 'END { print $2 }')" = error ]; then
        pass "the sheet shows the error line and the Install button again"
    else
        fail "the sheet's last footer is \"$(footers refused | awk 'END { print $2 }')\", not the error line"
    fi
    if ui_dump && ui_has update_sheet_error && ui_has update_sheet_install; then
        info "the error line reads \"$(ui_text update_sheet_error)\""
    fi
    shot refused-error
    gen=$(kv gen "$LAST_STATE")
    tap_id update_sheet_install || { fail "could not tap Install again"; return; }
    if wait_state FAILED 90 $((${gen:-0} + 1)); then
        pass "Install works again after a failure"
        trace_ok refused "$(case_log refused | count_installs)" "PREPARING INSTALLING FAILED"
    else
        fail "the retry did not run: $LAST_STATE"
    fi
}

case_missing() {
    begin_case missing "the APK disappears after the sheet showed"
    prepare_ready apk
    launch || { fail "Firedown did not come to the front"; return; }
    wait_sheet || { no_sheet missing-no-sheet; return; }
    remove_file
    tap_id update_sheet_install || { fail "could not find the Install button"; return; }
    if wait_state FAILED 20; then
        pass "the state is FAILED"
    else
        fail "no FAILED state: $LAST_STATE"
    fi
    trace_ok missing 1 "PREPARING FAILED"
    sleep 1
    if ui_dump && ui_has update_sheet_error && ui_has update_sheet_install; then
        pass "the error line and the Install button are back"
    else
        fail "the sheet does not show the error line and the button"
    fi
    shot missing-error
    LAST_STATE=$(rx STATE)
    if [ "$(kv n667 "$LAST_STATE")" = shown ]; then
        pass "the \"update failed\" notification was posted"
    else
        info "no \"update failed\" notification (notifications off?): $(kv n667 "$LAST_STATE")"
    fi
}

# Opens the notification shade: the shell command first, then a swipe down
# from the top edge (some phones ignore the command).
open_shade() {
    local size w h
    remote cmd statusbar expand-notifications >/dev/null
    sleep 1.5
    if shade_up; then
        return 0
    fi
    size=$(remote wm size | sed -n 's/.*: *\([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1 \2/p' | tail -n 1)
    w=${size% *}
    h=${size#* }
    if [ -z "$size" ] || [ -z "$w" ] || [ -z "$h" ]; then
        return 1
    fi
    remote input swipe $((w / 2)) 5 $((w / 2)) $((h * 2 / 3)) 300 >/dev/null
    sleep 1.5
    shade_up
}

case_notification() {
    local ans xy seen=0 end n
    begin_case notification "install from the \"Tap to update\" notification"
    prepare_ready apk
    ans=$(rx POST_PROMPT --es name "$(q "$TEST_NAME")")
    if [ "$(kv notifications "$ans")" != true ]; then
        info "notifications are off for Firedown: skipped ($ans)"
        return
    fi
    remote input keyevent KEYCODE_HOME >/dev/null
    sleep 1
    LAST_STATE=$(rx STATE)
    if [ "$(kv n666 "$LAST_STATE")" = shown ]; then
        pass "the \"Tap to update\" notification is posted"
    else
        fail "no \"Tap to update\" notification: $LAST_STATE"
        return
    fi
    xy=
    if open_shade; then
        shot notification-shade
        if ui_dump 2 all; then
            xy=$(ui_find text "$TEST_NAME" contains)
        fi
    else
        shot notification-shade
    fi
    if [ -n "$xy" ]; then
        # shellcheck disable=SC2086
        tap_at $xy
        info "tapped the notification in the shade"
    else
        remote cmd statusbar collapse >/dev/null
        rx TAP_PROMPT --es name "$(q "$TEST_NAME")" >/dev/null
        info "the notification was not found in the shade; sent its tap through the test receiver"
    fi
    sleep 0.5
    remote cmd statusbar collapse >/dev/null
    end=$(($(date +%s) + 60))
    OUTCOME=none
    while [ "$(date +%s)" -lt "$end" ]; do
        read_state
        n=$(kv n666 "$LAST_STATE")
        case $n in
            progress:*|indeterminate)
                if [ $seen = 0 ]; then
                    pass "the notification shows the install: $n"
                    shot notification-progress
                fi
                seen=1
                ;;
        esac
        if installer_up; then
            OUTCOME=dialog
            break
        fi
        if [ "$(installed_code)" = "$NEWER_CODE" ]; then
            OUTCOME=updated
            INSTALLED_NEWER=1
            SILENT_SEEN=1
            break
        fi
        if [ "$PHASE" = FAILED ]; then
            OUTCOME=failed
            break
        fi
        sleep 0.3
    done
    if [ $seen = 0 ]; then
        info "the notification's progress was not caught between samples (the trace covers the phases)"
    fi
    if log_wait notification 'notif=true' 3; then
        pass "the install ran as started from the notification"
    else
        fail "no install started from the notification in the trace"
    fi
    case $OUTCOME in
        updated)
            check_updated notification
            return
            ;;
        failed)
            fail "the install failed: $LAST_STATE"
            return
            ;;
        none)
            fail "neither Android's dialog nor the update within 60 s: $LAST_STATE"
            return
            ;;
    esac
    pass "Android's dialog appeared"
    dialog_but_silent_requested
    shot notification-android-dialog
    sleep 1
    LAST_STATE=$(rx STATE)
    if [ "$(kv n666 "$LAST_STATE")" = none ]; then
        pass "the progress notification is gone once Android has the update"
    else
        fail "the progress notification is still there: $LAST_STATE"
    fi
    trace_ok notification 1 "PREPARING INSTALLING IDLE"
    close_installer || true
    sleep 2
    if [ "$(kv n667 "$(rx STATE)")" != none ]; then
        warn "cancelling Android's dialog posted the \"update failed\" notification"
    fi
}

case_install() {
    local xy answer end
    if [ "$SILENT_SEEN" = 1 ]; then
        say ""
        say "== install: a real update to $(name_for "$NEWER_CODE")" | tee -a "$OUT/results.txt"
        info "skipped: Android already installed the update without asking in an earlier case"
        return
    fi
    begin_case install "a real update to $(name_for "$NEWER_CODE")"
    prepare_ready apk
    launch || { fail "Firedown did not come to the front"; return; }
    wait_sheet || { no_sheet install-no-sheet; return; }
    tap_id update_sheet_install || { fail "could not find the Install button"; return; }
    wait_outcome 90 || true
    case $OUTCOME in
        updated)
            check_updated install
            shot install-done
            return
            ;;
        failed)
            fail "the install failed: $LAST_STATE"
            return
            ;;
        none)
            fail "neither Android's dialog nor the update within 90 s: $LAST_STATE"
            return
            ;;
    esac
    dialog_but_silent_requested
    shot install-android-dialog
    xy=
    if ui_dump; then
        xy=$(ui_find resource-id "android:id/button1" exact)
    fi
    if [ -n "$xy" ]; then
        # shellcheck disable=SC2086
        tap_at $xy
        info "tapped Android's Update button"
    else
        answer=$(ask "Tap Update in Android's dialog on the phone, then press Enter here.")
        : "$answer"
    fi
    end=$(($(date +%s) + 120))
    while [ "$(date +%s)" -lt "$end" ]; do
        if [ "$(installed_code)" = "$NEWER_CODE" ]; then
            break
        fi
        sleep 1
    done
    if [ "$(installed_code)" = "$NEWER_CODE" ]; then
        INSTALLED_NEWER=1
        pass "Firedown is now $(name_for "$NEWER_CODE") ($NEWER_CODE)"
    else
        fail "the version did not change within 2 minutes ($(installed_code))"
        return
    fi
    trace_ok install 1 "PREPARING INSTALLING IDLE"
    sleep 3
    LAST_STATE=$(rx STATE)
    if [ "$(kv n668 "$LAST_STATE")" = shown ]; then
        pass "the \"Firedown updated\" notification was posted"
    else
        fail "no \"Firedown updated\" notification: $LAST_STATE"
    fi
    if [ "$(kv n666 "$LAST_STATE")" = none ]; then
        pass "no progress notification left behind"
    else
        fail "a progress notification was left behind: $(kv n666 "$LAST_STATE")"
    fi
    shot install-done
}

# ── setup and teardown ───────────────────────────────────────────────────

read_version() {
    sed -n 's/^[[:space:]]*versionCode[[:space:]][[:space:]]*\([0-9][0-9]*\).*/\1/p' "$1" | head -n 1
}

# with_version FILE CODE: build.gradle with versionCode and versionName set
# to CODE and the name derived from it, on stdout.
with_version() {
    sed -e "s/^\([[:space:]]*versionCode[[:space:]][[:space:]]*\)[0-9][0-9]*/\1$2/" \
        -e "s/^\([[:space:]]*versionName[[:space:]][[:space:]]*\)\"[^\"]*\"/\1\"$(name_for "$2")\"/" "$1"
}

restore_gradle() {
    if [ -n "$GRADLE_BACKUP" ] && [ -f "$GRADLE_BACKUP" ]; then
        cp "$GRADLE_BACKUP" "$GRADLE_FILE"
        rm -f "$GRADLE_BACKUP"
        GRADLE_BACKUP=
    fi
}

build_apk() {
    local code=$1 log="$OUT/build-$1.log"
    say "Building the debug APK as $(name_for "$code") ($code)… (log: $log)"
    if ! (cd "$ROOT" && ./gradlew :app:assembleDebug) > "$log" 2>&1; then
        tail -n 30 "$log"
        restore_gradle
        die "the build failed"
    fi
    cp "$ROOT/app/build/outputs/apk/debug/app-debug.apk" "$CACHE/apks/firedown-$code.apk"
}

build_all() {
    BASE_CODE=$(read_version "$GRADLE_FILE")
    [ -n "$BASE_CODE" ] || die "no versionCode in $GRADLE_FILE"
    NEWER_CODE=$((BASE_CODE + 1))
    BASE_APK="$CACHE/apks/firedown-$BASE_CODE.apk"
    NEWER_APK="$CACHE/apks/firedown-$NEWER_CODE.apk"
    TEST_NAME="Firedown $(name_for "$NEWER_CODE") (test)"
    mkdir -p "$CACHE/apks"
    if [ "$SKIP_BUILD" = 1 ] && [ -f "$BASE_APK" ] && [ -f "$NEWER_APK" ]; then
        say "Reusing $BASE_APK and $NEWER_APK"
        return
    fi
    find_jdk
    build_apk "$BASE_CODE"
    GRADLE_BACKUP="$OUT/build.gradle.orig"
    cp "$GRADLE_FILE" "$GRADLE_BACKUP"
    with_version "$GRADLE_BACKUP" "$NEWER_CODE" > "$GRADLE_FILE"
    if [ "$(read_version "$GRADLE_FILE")" != "$NEWER_CODE" ]; then
        restore_gradle
        die "could not set versionCode $NEWER_CODE in $GRADLE_FILE"
    fi
    build_apk "$NEWER_CODE"
    restore_gradle
}

preflight() {
    ADB_BIN=$(find_adb)
    [ -n "$ADB_BIN" ] || die "adb not found: put the SDK's platform-tools on PATH, or set ADB=/path/to/adb."
    local st
    st=$(adbq get-state 2>&1 | tr -d '\r') || true
    case $st in
        device) ;;
        *more\ than\ one*) die "more than one device attached: set ANDROID_SERIAL (adb devices)." ;;
        *) die "no phone ready over adb (adb get-state said: $st). Plug it in and allow USB debugging." ;;
    esac
    SDK=$(remote getprop ro.build.version.sdk)
    SDK=${SDK:-0}
    PHONE="$(remote getprop ro.product.manufacturer) $(remote getprop ro.product.model), Android $(remote getprop ro.build.version.release) (SDK $SDK)"
}

save_setting() {
    local ns=$1 key=$2 value
    value=$(remote settings get "$ns" "$key")
    if [ "$ns" = global ]; then
        RESTORE_GLOBAL+=("$key" "$value")
    else
        RESTORE_SYSTEM+=("$key" "$value")
    fi
}

setup_phone() {
    local mode
    PHONE_TOUCHED=1
    save_setting global animator_duration_scale
    save_setting global transition_animation_scale
    save_setting global window_animation_scale
    save_setting global stay_on_while_plugged_in
    save_setting system accelerometer_rotation
    save_setting system user_rotation
    save_setting system font_scale
    FONT_SCALE_SAVED=${RESTORE_SYSTEM[$((${#RESTORE_SYSTEM[@]} - 1))]}
    remote settings put global animator_duration_scale 0
    remote settings put global transition_animation_scale 0
    remote settings put global window_animation_scale 0
    remote settings put global stay_on_while_plugged_in 7
    # Always: a debug build already on the phone can carry the same
    # versionCode without this checkout's test receiver.
    install_base
    adbq push "$NEWER_APK" "$STAGE" >/dev/null 2>&1 || die "could not copy the APK to the phone"
    mode=$(remote appops get "$PKG" REQUEST_INSTALL_PACKAGES | sed -n 's/.*REQUEST_INSTALL_PACKAGES: \([a-z]*\).*/\1/p' | head -n 1)
    RESTORE_APPOPS=${mode:-default}
    remote appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
    if [ "$SDK" -ge 33 ]; then
        if ! has "$(remote dumpsys package "$PKG")" 'android.permission.POST_NOTIFICATIONS: granted=true'; then
            REVOKE_NOTIFICATIONS=1
            remote pm grant "$PKG" android.permission.POST_NOTIFICATIONS
        fi
    fi
    local where
    where=$(rx WHERE)
    APK_PATH=$(kv path "$where")
    [ -n "$APK_PATH" ] || die "the app's test receiver did not answer ($where). Is this checkout's debug build installed?"
}

# A setting the phone did not have reads back as "null": delete it rather
# than store that word.
restore_setting() {
    if [ "$3" = null ] || [ -z "$3" ]; then
        remote settings delete "$1" "$2" >/dev/null 2>&1 || true
    else
        remote settings put "$1" "$2" "$3" >/dev/null 2>&1 || true
    fi
}

cleanup() {
    local i
    restore_gradle
    if [ "$PHONE_TOUCHED" = 1 ] && adbq get-state >/dev/null 2>&1; then
        if installer_up; then
            close_installer || true
        fi
        if [ "$INSTALLED_NEWER" = 1 ] || [ "$(installed_code)" = "$NEWER_CODE" ]; then
            ensure_base || true
        fi
        rx CLEAR >/dev/null 2>&1 || true
        remote rm -f "$STAGE" /sdcard/fd-update-ui.xml >/dev/null 2>&1 || true
        i=0
        while [ $i -lt ${#RESTORE_GLOBAL[@]} ]; do
            restore_setting global "${RESTORE_GLOBAL[$i]}" "${RESTORE_GLOBAL[$((i + 1))]}"
            i=$((i + 2))
        done
        i=0
        while [ $i -lt ${#RESTORE_SYSTEM[@]} ]; do
            restore_setting system "${RESTORE_SYSTEM[$i]}" "${RESTORE_SYSTEM[$((i + 1))]}"
            i=$((i + 2))
        done
        if [ -n "$RESTORE_APPOPS" ]; then
            remote appops set "$PKG" REQUEST_INSTALL_PACKAGES "$RESTORE_APPOPS" >/dev/null 2>&1 || true
        fi
        if [ "$REVOKE_NOTIFICATIONS" = 1 ]; then
            remote pm revoke "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
        fi
    fi
    if [ -n "$LOGCAT_PID" ]; then
        kill "$LOGCAT_PID" 2>/dev/null || true
        wait "$LOGCAT_PID" 2>/dev/null || true
    fi
    if [ -n "$OUT" ] && [ -d "$OUT" ]; then
        say ""
        say "Files: $OUT"
    fi
}

usage() {
    sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

main() {
    local c
    while [ $# -gt 0 ]; do
        case $1 in
            --skip-build) SKIP_BUILD=1 ;;
            --no-install) NO_INSTALL=1 ;;
            --only) shift; ONLY=${1:-} ;;
            --only=*) ONLY=${1#--only=} ;;
            -h|--help) usage; exit 0 ;;
            *) die "unknown option $1 (see --help)" ;;
        esac
        shift
    done
    if [ -n "$ONLY" ]; then
        for c in ${ONLY//,/ }; do
            case " $ALL_CASES " in
                *" $c "*) ;;
                *) die "unknown case $c (cases: $ALL_CASES)" ;;
            esac
        done
    fi

    OUT=${OUT_DIR:-$CACHE/$(date +%Y%m%d-%H%M%S)}
    mkdir -p "$OUT"
    : > "$OUT/results.txt"
    trap cleanup EXIT
    trap 'exit 130' INT TERM

    preflight
    build_all
    say "Phone: $PHONE" | tee -a "$OUT/results.txt"
    say "Testing on the phone: installs $(name_for "$BASE_CODE"), offers $TEST_NAME"
    adbq logcat -v threadtime > "$OUT/logcat.txt" 2>&1 &
    LOGCAT_PID=$!
    setup_phone

    for c in $ALL_CASES; do
        if [ -n "$ONLY" ]; then
            case ",$ONLY," in
                *",$c,"*) ;;
                *) continue ;;
            esac
        fi
        if [ "$c" = install ] && [ "$NO_INSTALL" = 1 ]; then
            continue
        fi
        "case_$c"
    done

    say ""
    say "$PASSES passed, $FAILS failed, $WARNS warnings" | tee -a "$OUT/results.txt"
    [ "$FAILS" = 0 ]
}

if [ "${UPDATE_INSTALL_TEST_LIB:-}" != 1 ]; then
    main "$@"
fi

#!/usr/bin/env bash
# Reproduce "every page stays loading after resuming Firedown", from a
# laptop, against the Firedown installed on the phone. Release builds work:
# it needs no debug build and no root, only `adb` and stock `am` commands.
#
# A page load in Firedown waits on two Gecko child processes besides the
# app's own: the tab's content process and the ONE extension process every
# built-in extension (uBlock, capture, YouTube) runs in. uBlock answers a
# blocking listener for every request, so if the extension process is
# frozen or hung, every load in every tab waits on it. Android freezes and
# kills cached processes while an app is in the background; these modes
# produce those states on demand:
#
#   freeze     Freeze ONE of Firedown's Gecko tab/extension processes at a
#              time (am freeze --sticky, Android 14+), open a page, ask
#              whether it loaded, unfreeze. The process whose freeze stalls
#              a load in a NEW tab is the one a resume can leave stuck.
#   crash      Send Firedown to the background, crash every Gecko tab and
#              extension process (am crash <pid>), bring it back, open a
#              page: what Android reclaiming them while you were away does.
#   crashloop  Crash them six times in ~25 s with Firedown on screen. Gecko
#              stops restarting the extension process after more than 5
#              crashes in 30 s; Firedown never re-enables it.
#   watch      No forcing. Records everything while YOU reproduce it by
#              hand, and snapshots the processes the moment you press
#              Enter, before you have to force-stop the app. Then tries two
#              ways out (Airplane mode, unfreezing) and records which works.
#   status     List Firedown's processes and which ones Android has frozen.
#
# Every mode streams the FULL logcat (all buffers, every process, starting
# from what the phone still holds) into the output folder for the whole run,
# with "FDSTUCK" marker lines at each step, so the evidence exists even if
# Firedown has to be killed afterwards.
#
# Usage:  scripts/stuck-repro.sh <mode> [--url URL] [--wait SECONDS] [--away SECONDS]
#   --url    page each probe opens (default https://example.com/)
#   --wait   seconds to give a load before asking (default 12)
#   --away   seconds Firedown stays in the background in `crash` (default 20)
# Output: ~/firedown-stuck-<date>/ (override with OUT_DIR): logcat.txt,
# results.txt, and a screenshot + process snapshot per probe.
# More than one device attached: set ANDROID_SERIAL.
#
# Each probe opens its page through a VIEW intent, which Firedown opens in a
# tab of its own, so a run leaves a few example.com tabs behind.

set -Euo pipefail

PKG=com.solarized.firedown
ACTIVITY="$PKG/$PKG.phone.BrowserActivity"
URL=https://example.com/
WAIT=12
AWAY=20
CRASH_ROUNDS=6
CRASH_GAP=4

MODE=${1:-}
[ $# -gt 0 ] && shift
while [ $# -gt 0 ]; do
    case $1 in
        --url) URL=${2:?--url needs a value}; shift 2 ;;
        --wait) WAIT=${2:?--wait needs a value}; shift 2 ;;
        --away) AWAY=${2:?--away needs a value}; shift 2 ;;
        *) printf 'unknown option: %s\n' "$1" >&2; exit 2 ;;
    esac
done

case $MODE in
    freeze|crash|crashloop|watch|status) ;;
    *) sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
case $URL in
    *\'*|*' '*) printf 'the URL may not contain quotes or spaces\n' >&2; exit 2 ;;
esac

OUT=${OUT_DIR:-$HOME/firedown-stuck-$(date +%Y%m%d-%H%M%S)}
LOGCAT_PID=
FROZEN=
PROBE=0
STALLED=

say() { printf '%s\n' "$*"; }
die() { printf 'STOPPED: %s\n' "$*" >&2; exit 1; }

# Prompts go to and come from the terminal, so the script still asks when
# its output is piped through tee.
ask() {
    local answer=
    printf '%s ' "$1" > /dev/tty
    IFS= read -r answer < /dev/tty || answer=
    printf '%s' "$answer"
}

is_yes() {
    case $1 in
        y|Y|yes|YES|Yes) return 0 ;;
        *) return 1 ;;
    esac
}

# Every adb call reads from /dev/null: `adb shell` otherwise swallows the
# stdin of the loop it runs in, and the loop silently ends after one turn.
adbq() {
    adb "$@" < /dev/null
}

remote() {
    adbq shell "$@" 2>/dev/null | tr -d '\r'
}

# A line in the phone's log, so logcat.txt can be read step by step.
mark() {
    local msg=$*
    msg=${msg//\'/}
    adbq shell log -t FDSTUCK "'$msg'" >/dev/null 2>&1 || true
    printf '%s  %s\n' "$(date +%H:%M:%S)" "$msg" >> "$OUT/results.txt"
}

# FROZEN holds the pids this run froze, space-separated, so the exit trap
# can thaw any the run did not get to.
unfrozen() {
    local keep='' p
    for p in $FROZEN; do
        [ "$p" = "$1" ] || keep="$keep $p"
    done
    FROZEN=$keep
}

cleanup() {
    local pid
    for pid in $FROZEN; do
        adbq shell am unfreeze --sticky "$pid" >/dev/null 2>&1 || true
    done
    if [ -n "$LOGCAT_PID" ]; then
        kill "$LOGCAT_PID" 2>/dev/null || true
        wait "$LOGCAT_PID" 2>/dev/null || true
    fi
    if [ -d "$OUT" ]; then
        say ""
        say "Files: $OUT"
    fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# One line per Firedown process: "<pid> <name> <frozen>", read from
# ActivityManager's own process records (`dumpsys activity processes`), so
# the names are complete and the frozen flag is Android's, not a guess.
# A record's details are indented four spaces under its "*APP*" header; any
# shallower line ends the record.
procs() {
    remote dumpsys activity processes "$PKG" | awk '
        /^  \*(APP|PERS)\* UID [0-9]+ ProcessRecord\{/ || /^    Isolated #[0-9]+: ProcessRecord\{/ {
            s = $0
            sub(/.*ProcessRecord\{[0-9a-f]+ /, "", s)
            sub(/[\/} ].*/, "", s)
            colon = index(s, ":")
            pid = substr(s, 1, colon - 1)
            name = substr(s, colon + 1)
            if (!(pid in seen)) {
                seen[pid] = 1
                order[++n] = pid
                names[pid] = name
                frozen[pid] = "?"
            }
            cur = ($0 ~ /\*(APP|PERS)\*/) ? pid : ""
            next
        }
        /^    / {
            if (cur != "" && $0 ~ /isFrozen=/) {
                v = $0
                sub(/.*isFrozen=/, "", v)
                sub(/[^a-z].*/, "", v)
                frozen[cur] = v
            }
            next
        }
        { cur = "" }
        END {
            for (i = 1; i <= n; i++) {
                print order[i], names[order[i]], frozen[order[i]]
            }
        }'
}

# Gecko's tab and extension processes: every child whose name says "tab"
# (":tab0", ":sandboxed_tab_…", ":isolatedTab…"), minus the zygote, which
# hosts no content.
content_procs() {
    procs | awk 'tolower($2) ~ /tab/ && tolower($2) !~ /zygote/'
}

frozen_state() {
    procs | awk -v p="$1" '$1 == p { print $3 }'
}

show_procs() {
    local lines
    lines=$(procs)
    if [ -z "$lines" ]; then
        say "  (no Firedown process is running)"
        return
    fi
    printf '%s\n' "$lines" | awk '{ printf "  %-7s frozen=%-6s %s\n", $1, $3, $2 }'
}

foreground() {
    adbq shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
        -n "$ACTIVITY" >/dev/null 2>&1 || true
}

snapshot() {
    local tag=$1
    adbq exec-out screencap -p > "$OUT/$tag.png" 2>/dev/null || true
    procs > "$OUT/$tag-procs.txt" || true
    adbq shell dumpsys activity processes "$PKG" > "$OUT/$tag-dumpsys.txt" 2>&1 || true
}

# Open the page in Firedown, give it WAIT seconds, ask. Sets LOADED to y/n.
LOADED=n
probe() {
    local label=$1 n page answer
    PROBE=$((PROBE + 1))
    n=$(printf '%02d' "$PROBE")
    case $URL in
        *\?*) page="$URL&fdprobe=$n" ;;
        *) page="$URL?fdprobe=$n" ;;
    esac
    mark "probe $n start: $label"
    say "  opening $page"
    adbq shell am start -a android.intent.action.VIEW -d "'$page'" -n "$ACTIVITY" >/dev/null 2>&1 \
        || say "  (could not start Firedown with the page)"
    sleep "$WAIT"
    snapshot "probe-$n"
    answer=$(ask "  [$label] Did the page finish loading? [y/n]")
    if is_yes "$answer"; then
        LOADED=y
        mark "probe $n: LOADED ($label)"
    else
        LOADED=n
        mark "probe $n: STUCK ($label)"
    fi
}

preflight() {
    command -v adb >/dev/null 2>&1 || die "adb is not installed on this computer."
    local state sdk
    state=$(adbq get-state 2>&1 | tr -d '\r') || true
    case $state in
        device) ;;
        *more\ than\ one*) die "more than one device attached: set ANDROID_SERIAL to the phone's serial (adb devices)." ;;
        *) die "no phone ready over adb (adb get-state said: $state). Plug it in and allow USB debugging." ;;
    esac
    local paths
    paths=$(remote pm path "$PKG")
    case $paths in
        package:*) ;;
        *) die "Firedown ($PKG) is not installed on this phone." ;;
    esac
    sdk=$(remote getprop ro.build.version.sdk)
    SDK=${sdk:-0}
    mkdir -p "$OUT"
    : > "$OUT/results.txt"
    # adb itself in the background (not the adbq function, whose subshell
    # would be what $! names): the exit trap must kill the logcat stream,
    # not leave it writing to the file after the run.
    adb logcat -b all -v threadtime < /dev/null > "$OUT/logcat.txt" 2>&1 &
    LOGCAT_PID=$!
    mark "run start: mode=$MODE url=$URL wait=$WAIT sdk=$SDK"
    say "Recording the full logcat to $OUT/logcat.txt"
}

need_freezer() {
    [ "$SDK" -ge 34 ] || die "freezing needs Android 14 or later (this phone reports SDK $SDK)."
}

baseline() {
    say ""
    say "Baseline: one load with nothing forced, to check the phone is online."
    probe "baseline, nothing forced"
    if [ "$LOADED" != y ]; then
        die "the page does not load even before anything is forced. Check the connection (or that Firedown is not already stuck) and run again."
    fi
}

mode_status() {
    say ""
    say "Firedown's processes:"
    show_procs
}

mode_freeze() {
    need_freezer
    foreground
    sleep 3
    baseline
    local list pid name state again
    list=$(content_procs)
    [ -n "$list" ] || die "no Gecko tab or extension process is running. Open a page in Firedown and run again."
    say ""
    say "Freezing each tab/extension process in turn:"
    printf '%s\n' "$list" | awk '{ printf "  %-7s %s\n", $1, $2 }'
    while read -r pid name _; do
        say ""
        say "Freezing $name ($pid)"
        adbq shell am freeze --sticky "$pid" >/dev/null 2>&1 || true
        FROZEN="$FROZEN $pid"
        sleep 2
        state=$(frozen_state "$pid")
        if [ "$state" != true ]; then
            say "  Android reports frozen=${state:-gone} for it, so this probe proves nothing about it."
        fi
        mark "frozen $pid $name (android says frozen=${state:-gone})"
        probe "frozen: $name"
        adbq shell am unfreeze --sticky "$pid" >/dev/null 2>&1 || true
        unfrozen "$pid"
        mark "unfrozen $pid $name"
        if [ "$LOADED" != y ]; then
            STALLED="$STALLED
  $name ($pid)"
            sleep 3
            again=$(ask "  Unfrozen again. Did the page finish within a few seconds? [y/n]")
            if is_yes "$again"; then
                mark "after unfreeze: LOADED ($name)"
            else
                mark "after unfreeze: STILL STUCK ($name)"
            fi
        fi
    done <<EOF
$list
EOF
    say ""
    if [ -n "$STALLED" ]; then
        say "Freezing these processes stalled a page load:$STALLED"
        say "If one of them stalled even the new tab, that is the process a resume can leave"
        say "stuck. Next: run 'watch', reproduce by hand, and check whether that process"
        say "shows frozen=true in the snapshot."
    else
        say "No single frozen process stalled a load. Next: try 'crash' and 'crashloop'."
    fi
}

crash_all() {
    local list pid name count=0
    list=$(content_procs)
    while read -r pid name _; do
        [ -n "$pid" ] || continue
        adbq shell am crash "$pid" >/dev/null 2>&1 || true
        mark "crashed $pid $name"
        count=$((count + 1))
    done <<EOF
$list
EOF
    say "  crashed $count process(es)"
}

mode_crash() {
    foreground
    sleep 3
    baseline
    say ""
    say "Sending Firedown to the background, then crashing its tab and extension processes."
    adbq shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
    sleep 3
    mark "app sent to background"
    crash_all
    say "  Firedown stays in the background for $AWAY s."
    sleep "$AWAY"
    say "Bringing it back with a page:"
    probe "after a background crash"
    if [ "$LOADED" = y ]; then
        local typed
        typed=$(ask "  Now type a site in the address bar yourself and press Go. Did that load? [y/n]")
        if is_yes "$typed"; then
            mark "typed load after background crash: LOADED"
        else
            mark "typed load after background crash: STUCK"
        fi
    fi
}

mode_crashloop() {
    foreground
    sleep 3
    baseline
    say ""
    say "Crashing the tab and extension processes $CRASH_ROUNDS times, $CRASH_GAP s apart, with Firedown on screen."
    local round=1
    while [ "$round" -le "$CRASH_ROUNDS" ]; do
        say " round $round"
        mark "crash round $round"
        crash_all
        sleep "$CRASH_GAP"
        round=$((round + 1))
    done
    sleep 3
    probe "after $CRASH_ROUNDS crashes in a row"
    say ""
    say "Gecko gives up restarting the extension process after more than 5 crashes in 30 s."
    say "If the page loaded, check whether ads are still blocked and media still shows up in"
    say "the Captured sheet: if not, extensions are off for the rest of this session."
}

mode_watch() {
    say ""
    say "Use the phone normally: leave Firedown in the background for a few minutes,"
    say "come back, open pages. Everything is being recorded."
    say "When a page is stuck, press Enter HERE first, before you force-stop Firedown."
    mark "watch: waiting for the user"
    ask "Press Enter when a page is stuck..." > /dev/null
    mark "watch: user reports a stuck page"
    snapshot "stuck"
    say ""
    say "Firedown's processes right now:"
    show_procs
    local answer pid name frozen
    answer=$(ask "Turn Airplane mode on, then off again, wait 10 s. Did the page load now? [y/n]")
    if is_yes "$answer"; then
        mark "watch: Airplane mode toggle UNSTUCK it (network layer)"
        say "That points at the network layer, not the app's processes."
        return
    fi
    mark "watch: Airplane mode toggle did not help"
    if [ "$SDK" -lt 34 ]; then
        return
    fi
    answer=$(ask "Unfreeze all of Firedown's processes and see if the page loads? [y/n]")
    if ! is_yes "$answer"; then
        return
    fi
    while read -r pid name frozen; do
        [ -n "$pid" ] || continue
        adbq shell am unfreeze "$pid" >/dev/null 2>&1 || true
        mark "watch: unfroze $pid $name (was frozen=$frozen)"
    done <<EOF
$(procs)
EOF
    sleep 5
    snapshot "after-unfreeze"
    answer=$(ask "Did the page load now? [y/n]")
    if is_yes "$answer" && grep -q ' true$' "$OUT/stuck-procs.txt"; then
        mark "watch: unfreezing UNSTUCK it"
        say "A frozen Firedown process was holding the loads; stuck-procs.txt shows which."
    elif is_yes "$answer"; then
        mark "watch: loaded after unfreezing, but nothing was frozen"
        say "It loaded, but Android had nothing of Firedown's frozen, so it recovered on its own."
    else
        mark "watch: unfreezing did not help"
        say "Not a frozen process. Keep the files; the logcat covers the whole episode."
    fi
}

preflight
case $MODE in
    status) mode_status ;;
    freeze) mode_freeze ;;
    crash) mode_crash ;;
    crashloop) mode_crashloop ;;
    watch) mode_watch ;;
esac
mark "run end"

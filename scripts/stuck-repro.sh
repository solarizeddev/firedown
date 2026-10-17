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
#              time (am freeze --sticky, Android 14+), open a site no tab
#              has loaded yet, ask whether it loaded, unfreeze. A process
#              that stalls a load is frozen a second time with yet another
#              new site: one that stalls BOTH is a process every load waits
#              on (the extension process), which is what a resume could leave
#              stuck. One that stalls only once was that page's own process,
#              or the spare one Gecko keeps ready for the next site.
#   crash      Send Firedown to the background, crash every Gecko tab and
#              extension process (am crash <pid>), bring it back, open a
#              page: what Android reclaiming them while you were away does.
#   crashloop  Crash them six times in ~25 s with Firedown on screen. Gecko
#              stops restarting the extension process after more than 5
#              crashes in 30 s; Firedown never re-enables it.
#   background What Android did in the session that got stuck: send
#              Firedown to the background, wait until Android itself
#              freezes it (every process, the main one included), keep it
#              frozen --away seconds, bring it back, then you type a site in
#              the address bar. Three rounds unless one gets stuck. If
#              Android never freezes it (a download or media playing keeps
#              it awake), the script freezes every process itself.
#   geckolog   on|off. Make Gecko write its network log (HTTP, sockets, DNS,
#              network changes) to logcat, in the release build: GeckoView
#              reads /data/local/tmp/<pkg>-geckoview-config.yaml when the
#              app is the Android "debug app". `on` sets both and restarts
#              Firedown; `off` removes both. Leave it on while running the
#              other modes, so a stuck load shows where Gecko waits.
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
# Usage:  scripts/stuck-repro.sh <mode> [--url URL] [--wait SECONDS] [--away SECONDS] [--rounds N]
#         scripts/stuck-repro.sh geckolog on|off
#   --url    open this one page in every probe (default: a different simple
#            public site each time, see SITES below)
#   --wait   seconds to give a load before asking (default 12)
#   --away   seconds Firedown stays in the background: `crash` (default 20),
#            `background`, counted from the freeze (default 120)
#   --rounds rounds of `background` (default 3)
# Output: ~/firedown-stuck-<date>/ (override with OUT_DIR): logcat.txt,
# results.txt, and a screenshot + process snapshot per probe.
# More than one device attached: set ANDROID_SERIAL.
#
# Why a different site per probe: with Fission every site gets its own
# content process, so a second probe of the same site runs in the process
# the first one started. Freezing that process stalls the probe trivially
# (it froze the page's own renderer) and says nothing about a process every
# load waits on. A fresh site never shares a process with an earlier probe.
#
# Each probe opens its page through a VIEW intent, which Firedown opens in a
# tab of its own, so a run leaves a few tabs behind.

set -Euo pipefail

PKG=com.solarized.firedown
ACTIVITY="$PKG/$PKG.phone.BrowserActivity"
URL=
# Small static pages, each on its own registrable domain, so each probe
# loads a site no earlier probe has a process for.
SITES=(
    https://example.com/
    https://example.org/
    https://example.net/
    https://www.iana.org/
    https://www.w3.org/
    https://www.ietf.org/
    https://www.rfc-editor.org/
    https://www.gnu.org/
    https://www.kernel.org/
    https://www.debian.org/
    https://www.sqlite.org/
    https://www.openbsd.org/
    https://www.gutenberg.org/
    https://text.npr.org/
    https://lite.duckduckgo.com/lite/
    https://www.wikipedia.org/
)
SITE_NEXT=0
SITE=
WAIT=12
AWAY=
ROUNDS=3
FREEZE_WAIT=${FREEZE_WAIT:-120}
GECKOLOG=
CONFIG_PATH=/data/local/tmp/com.solarized.firedown-geckoview-config.yaml
# Gecko's log modules for the network path a page load takes: HTTP, the
# socket thread, DNS, the document channel, the IO service's online state
# and Android's network-change feed.
MOZ_LOG_MODULES=timestamp,nsHttp:4,nsSocketTransport:4,nsHostResolver:4,DocumentChannel:4,nsIOService:4,nsAndroidNetworkLinkService:4
CRASH_ROUNDS=6
CRASH_GAP=4

MODE=${1:-}
[ $# -gt 0 ] && shift
while [ $# -gt 0 ]; do
    case $1 in
        --url) URL=${2:?--url needs a value}; shift 2 ;;
        --wait) WAIT=${2:?--wait needs a value}; shift 2 ;;
        --away) AWAY=${2:?--away needs a value}; shift 2 ;;
        --rounds) ROUNDS=${2:?--rounds needs a value}; shift 2 ;;
        on|off) GECKOLOG=$1; shift ;;
        *) printf 'unknown option: %s\n' "$1" >&2; exit 2 ;;
    esac
done

case $MODE in
    freeze|crash|crashloop|background|watch|status) ;;
    geckolog)
        [ -n "$GECKOLOG" ] || { printf 'geckolog needs on or off\n' >&2; exit 2; } ;;
    *) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2 ;;
esac
if [ -z "$AWAY" ]; then
    case $MODE in
        background) AWAY=120 ;;
        *) AWAY=20 ;;
    esac
fi
case $URL in
    *\'*|*' '*) printf 'the URL may not contain quotes or spaces\n' >&2; exit 2 ;;
esac

OUT=${OUT_DIR:-$HOME/firedown-stuck-$(date +%Y%m%d-%H%M%S)}
LOGCAT_PID=
FROZEN=
PROBE=0
STALLED=
SHARED=
# "pid:probe" for every process that started while a probe was loading.
STARTED_IN=

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
    msg=${msg//$'\n'/ |}
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
# A record's "isFrozen=" belongs to the last "*APP*" header above it; the
# record ends only at the next header or the isolated-process list, never at
# a blank or shallower line, which some sub-dumps print.
# Where the record carries no flag, the process's own cgroup answers
# (`frozen 1` in cgroup.events is the kernel's freezer state). "?" means
# neither could be read.
procs() {
    local lines unknown cg
    lines=$(remote dumpsys activity processes "$PKG" | awk '
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
        /Isolated process list/ { cur = ""; next }
        cur != "" && /isFrozen=/ && frozen[cur] == "?" {
            v = $0
            sub(/.*isFrozen=/, "", v)
            sub(/[^a-z].*/, "", v)
            if (v != "") {
                frozen[cur] = v
            }
        }
        END {
            for (i = 1; i <= n; i++) {
                print order[i], names[order[i]], frozen[order[i]]
            }
        }')
    unknown=$(printf '%s\n' "$lines" | awk '$3 == "?" { printf "%s ", $1 }')
    if [ -n "$unknown" ]; then
        cg=$(cgroup_frozen "$unknown")
        lines=$(printf '%s\n' "$lines" | awk -v cg="$cg" '
            BEGIN {
                n = split(cg, parts, " ")
                for (i = 1; i + 1 <= n; i += 2) {
                    state[parts[i]] = parts[i + 1]
                }
            }
            NF {
                if ($3 == "?" && ($1 in state)) {
                    $3 = state[$1]
                }
                print
            }')
    fi
    printf '%s\n' "$lines" | awk 'NF'
}

# "<pid> <true|false> ..." for the given pids, from each one's cgroup v2
# freezer, in one adb round trip. A pid whose cgroup can't be read is left
# out.
cgroup_frozen() {
    local p cmd=''
    for p in $1; do
        cmd="$cmd c=\$(sed -n 's/^0:://p' /proc/$p/cgroup 2>/dev/null);"
        cmd="$cmd f=\$(sed -n 's/^frozen //p' /sys/fs/cgroup\$c/cgroup.events 2>/dev/null);"
        cmd="$cmd [ \"\$f\" = 1 ] && echo $p true; [ \"\$f\" = 0 ] && echo $p false;"
    done
    remote "$cmd" | tr '\n' ' '
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

# The page the next probe opens: --url when given, else the next site no
# probe has opened yet (after the list runs out, sites repeat, and a repeat
# is marked in the results so a reader knows that probe shared a process).
# Sets SITE. Not called through $( ): the counter must survive the call.
next_site() {
    if [ -n "$URL" ]; then
        SITE=$URL
        return
    fi
    local i=$((SITE_NEXT % ${#SITES[@]}))
    SITE=${SITES[$i]}
    if [ "$SITE_NEXT" -ge "${#SITES[@]}" ]; then
        mark "site list exhausted: $SITE is opened a second time"
    fi
    SITE_NEXT=$((SITE_NEXT + 1))
}

# The process that started while probe <n> loaded, if any: "probe NN", for
# the summary. Gecko starts one for a new site and often a spare beside it.
started_in() {
    local entry
    for entry in $STARTED_IN; do
        if [ "${entry%%:*}" = "$1" ]; then
            printf 'probe %s' "${entry#*:}"
            return
        fi
    done
}

# Open the page in Firedown, give it WAIT seconds, ask. Sets LOADED to y/n.
LOADED=n
probe() {
    local label=$1 n page answer before new p
    PROBE=$((PROBE + 1))
    n=$(printf '%02d' "$PROBE")
    next_site
    case $SITE in
        *\?*) page="$SITE&fdprobe=$n" ;;
        *) page="$SITE?fdprobe=$n" ;;
    esac
    before=" $(procs | awk '{ printf "%s ", $1 }')"
    mark "probe $n start: $label ($page)"
    say "  opening $page"
    adbq shell am start -a android.intent.action.VIEW -d "'$page'" -n "$ACTIVITY" >/dev/null 2>&1 \
        || say "  (could not start Firedown with the page)"
    sleep "$WAIT"
    snapshot "probe-$n"
    new=$(awk -v before="$before" 'index(before, " " $1 " ") == 0 { printf "%s %s\n", $1, $2 }' \
        "$OUT/probe-$n-procs.txt")
    if [ -n "$new" ]; then
        say "  processes that started for it:"
        printf '%s\n' "$new" | awk '{ printf "    %-7s %s\n", $1, $2 }'
        while read -r p _; do
            STARTED_IN="$STARTED_IN $p:$n"
            mark "probe $n started process $p"
        done <<EOF
$new
EOF
    fi
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
    mark "run start: mode=$MODE url=${URL:-a new site per probe} wait=$WAIT sdk=$SDK"
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

# Freeze one process, probe a new site, thaw it. Sets LOADED. Returns 1,
# without probing, when the process is already gone.
freeze_probe() {
    local pid=$1 name=$2 label=$3 state
    adbq shell am freeze --sticky "$pid" >/dev/null 2>&1 || true
    FROZEN="$FROZEN $pid"
    sleep 2
    state=$(frozen_state "$pid")
    if [ -z "$state" ]; then
        say "  It exited before the probe; skipping it."
        mark "frozen $pid $name: exited, not probed"
        unfrozen "$pid"
        return 1
    fi
    case $state in
        true) ;;
        false) say "  Android says it is NOT frozen, so a load that works proves nothing about it." ;;
        *) say "  (Android's frozen flag can't be read here; a stalled load still counts.)" ;;
    esac
    mark "frozen $pid $name (frozen=$state)"
    probe "$label"
    adbq shell am unfreeze --sticky "$pid" >/dev/null 2>&1 || true
    unfrozen "$pid"
    mark "unfrozen $pid $name"
    return 0
}

after_unfreeze() {
    local again
    sleep 3
    again=$(ask "  Unfrozen again. Did the page finish within a few seconds? [y/n]")
    if is_yes "$again"; then
        mark "after unfreeze: LOADED ($1)"
    else
        mark "after unfreeze: STILL STUCK ($1)"
    fi
}

mode_freeze() {
    need_freezer
    foreground
    sleep 3
    baseline
    local list pid name from what
    list=$(content_procs)
    [ -n "$list" ] || die "no Gecko tab or extension process is running. Open a page in Firedown and run again."
    say ""
    say "Freezing each tab/extension process in turn, each time with a site no tab has opened:"
    printf '%s\n' "$list" | awk '{ printf "  %-7s %s\n", $1, $2 }'
    while read -r pid name _; do
        [ -n "$pid" ] || continue
        say ""
        say "Freezing $name ($pid)"
        freeze_probe "$pid" "$name" "frozen: $name" || continue
        [ "$LOADED" = y ] && continue
        after_unfreeze "$name"
        from=$(started_in "$pid")
        what="$name ($pid)${from:+, started during $from}"
        say ""
        say "That load stalled. Freezing $name again, with another new site,"
        say "to tell a process every load waits on from the one this page landed in."
        if ! freeze_probe "$pid" "$name" "frozen again: $name"; then
            STALLED="$STALLED
  $what"
            continue
        fi
        if [ "$LOADED" = y ]; then
            STALLED="$STALLED
  $what"
        else
            SHARED="$SHARED
  $what"
            after_unfreeze "$name, second time"
        fi
    done <<EOF
$list
EOF
    say ""
    if [ -n "$SHARED" ]; then
        mark "freeze verdict: every load waits on:$SHARED"
        say "Freezing these stalled TWO different new sites, so every load waits on them:$SHARED"
        say "That is the process a resume could leave stuck (most likely the extension"
        say "process). Next: run 'watch', reproduce by hand, and check whether it shows"
        say "frozen=true in stuck-procs.txt."
    fi
    if [ -n "$STALLED" ]; then
        mark "freeze verdict: stalled one load only:$STALLED"
        say "These stalled one load but not the next one:$STALLED"
        say "The new site had landed in them (the spare process Gecko keeps ready, or one"
        say "an earlier page uses). Freezing them stalls only the page inside them."
    fi
    if [ -z "$SHARED" ]; then
        mark "freeze verdict: no process every load waits on"
        say "No frozen process stalled every load, so a single frozen process does not"
        say "explain the bug. Next: try 'crash' and 'crashloop', then 'watch'."
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
    stuck_followup stuck
}

# A page is stuck right now: record the processes, then try the two ways
# out and record which one works. $1 names the snapshot.
stuck_followup() {
    local tag=$1 answer pid name frozen
    snapshot "$tag"
    say ""
    say "Firedown's processes right now:"
    show_procs
    answer=$(ask "Turn Airplane mode on, then off again, wait 10 s. Did the page load now? [y/n]")
    if is_yes "$answer"; then
        mark "stuck: Airplane mode toggle UNSTUCK it (network layer)"
        say "That points at the network layer, not the app's processes."
        return
    fi
    mark "stuck: Airplane mode toggle did not help"
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
        mark "stuck: unfroze $pid $name (was frozen=$frozen)"
    done <<EOF
$(procs)
EOF
    sleep 5
    snapshot "after-unfreeze"
    answer=$(ask "Did the page load now? [y/n]")
    if is_yes "$answer" && grep -q ' true$' "$OUT/$tag-procs.txt"; then
        mark "stuck: unfreezing UNSTUCK it"
        say "A frozen Firedown process was holding the loads; $tag-procs.txt shows which."
    elif is_yes "$answer"; then
        mark "stuck: loaded after unfreezing, but nothing was frozen"
        say "It loaded, but Android had nothing of Firedown's frozen, so it recovered on its own."
    else
        mark "stuck: unfreezing did not help"
        say "Not a frozen process. Keep the files; the logcat covers the whole episode."
    fi
}

# The main (browser) process's pid.
main_pid() {
    procs | awk -v p="$PKG" '$2 == p { print $1 }'
}

# Where the logcat file ends now, so later reads see only what came after.
log_offset() {
    wc -c < "$OUT/logcat.txt" | tr -d ' '
}

# Android's own freeze/unfreeze records for Firedown since a log offset,
# as "am_freeze pid name" lines (from the events buffer, which `logcat -b
# all` includes).
freeze_events() {
    tail -c +$(($1 + 1)) "$OUT/logcat.txt" | awk -v pkg="$PKG" '
        /am_(un)?freeze: \[/ && index($0, pkg) {
            match($0, /am_(un)?freeze:/)
            tag = substr($0, RSTART, RLENGTH - 1)
            v = $0
            sub(/.*\[/, "", v)
            sub(/\].*/, "", v)
            split(v, f, ",")
            print tag, f[1], f[2]
        }'
}

# Waits until Android freezes Firedown's main process (pid $1), looking at
# the log after offset $2. Returns 1 after FREEZE_WAIT seconds without it.
wait_for_freeze() {
    local waited=0
    while [ "$waited" -lt "$FREEZE_WAIT" ]; do
        if freeze_events "$2" | grep -q "^am_freeze $1 $PKG\$"; then
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done
    return 1
}

print_events() {
    local lines
    lines=$(freeze_events "$1" | grep "^$2 " || true)
    if [ -z "$lines" ]; then
        say "    (none recorded)"
        return
    fi
    printf '%s\n' "$lines" | awk '{ printf "    %-7s %s\n", $2, $3 }'
}

# The host part of a site, the way someone types it into the address bar.
typed_form() {
    local t=${1#https://}
    t=${t#http://}
    printf '%s' "${t%/}"
}

mode_background() {
    foreground
    sleep 3
    baseline
    local round=1 main off forced p typed stuck=''
    while [ "$round" -le "$ROUNDS" ]; do
        say ""
        say "Round $round of $ROUNDS: Firedown goes to the background."
        main=$(main_pid)
        [ -n "$main" ] || die "Firedown's main process is not running."
        off=$(log_offset)
        adbq shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
        mark "round $round: app sent to background (main pid $main)"
        say "  Waiting up to $FREEZE_WAIT s for Android to freeze it (Android decides when)..."
        forced=''
        if wait_for_freeze "$main" "$off"; then
            mark "round $round: Android froze it"
            say "  Android froze:"
            print_events "$off" am_freeze
        else
            say "  Android did not freeze it in $FREEZE_WAIT s (a download or media playing keeps"
            say "  an app awake). Freezing every Firedown process by hand instead."
            forced=$(procs | awk '{ print $1 }')
            for p in $forced; do
                adbq shell am freeze --sticky "$p" >/dev/null 2>&1 || true
                FROZEN="$FROZEN $p"
            done
            mark "round $round: froze by hand: $forced"
        fi
        say "  It stays frozen for $AWAY s."
        sleep "$AWAY"
        off=$(log_offset)
        if [ -n "$forced" ]; then
            # Main first, then the children: the order Android thaws them in.
            adbq shell am unfreeze --sticky "$main" >/dev/null 2>&1 || true
            unfrozen "$main"
            for p in $forced; do
                [ "$p" = "$main" ] && continue
                adbq shell am unfreeze --sticky "$p" >/dev/null 2>&1 || true
                unfrozen "$p"
            done
        fi
        foreground
        mark "round $round: brought back from the launcher"
        sleep 4
        if [ -z "$forced" ]; then
            say "  Back on screen. Android unfroze:"
            print_events "$off" am_unfreeze
        else
            say "  Back on screen."
        fi
        next_site
        typed=$(typed_form "$SITE")
        mark "round $round: user types $typed"
        if is_yes "$(ask "  In Firedown's address bar type $typed and press Go. Did it load within ~15 s? [y/n]")"; then
            mark "round $round: typed load LOADED"
        else
            mark "round $round: typed load STUCK"
            stuck="round $round, typed $typed"
            stuck_followup "stuck-round-$round"
            break
        fi
        probe "back from round $round"
        if [ "$LOADED" != y ]; then
            stuck="round $round, a new tab"
            stuck_followup "stuck-round-$round"
            break
        fi
        round=$((round + 1))
    done
    say ""
    if [ -n "$stuck" ]; then
        mark "background verdict: STUCK ($stuck)"
        say "Reproduced: a load got stuck after a background stay ($stuck)."
        say "Keep the folder; the logcat covers the whole episode."
    else
        mark "background verdict: no stuck load in $ROUNDS rounds"
        say "No load got stuck in $ROUNDS rounds. Try longer stays (--away 600), or 'watch'"
        say "while you use the phone normally."
    fi
}

geckolog_on() {
    local tmp
    tmp=$(mktemp)
    printf 'env:\n  MOZ_LOG: "%s"\n' "$MOZ_LOG_MODULES" > "$tmp"
    adbq push "$tmp" "$CONFIG_PATH" >/dev/null 2>&1 || { rm -f "$tmp"; die "could not copy the config to the phone."; }
    rm -f "$tmp"
    adbq shell chmod 644 "$CONFIG_PATH" >/dev/null 2>&1 || true
    adbq shell am set-debug-app --persistent "$PKG" >/dev/null 2>&1 || die "am set-debug-app failed."
    mark "geckolog on: $MOZ_LOG_MODULES"
    say "Gecko's network log is set up. Firedown must restart to read it:"
    say "it is force-stopped now and reopened (your tabs come back)."
    adbq shell am force-stop "$PKG" >/dev/null 2>&1 || true
    sleep 1
    local off
    off=$(log_offset)
    foreground
    sleep 5
    baseline
    if tail -c +$((off + 1)) "$OUT/logcat.txt" | grep -q -E '[DIVWE]/(nsHttp|nsSocketTransport|nsHostResolver|DocumentChannel)'; then
        mark "geckolog on: Gecko log lines seen"
        say ""
        say "Working: Gecko's network lines are in the log. Now run the other modes"
        say "(background, watch) as usual. Turn it off afterwards:"
        say "  scripts/stuck-repro.sh geckolog off"
    else
        mark "geckolog on: no Gecko log lines seen"
        say ""
        say "No Gecko network lines appeared, so this build does not read the config."
        say "Turning it off again."
        geckolog_off
    fi
}

geckolog_off() {
    adbq shell am clear-debug-app >/dev/null 2>&1 || true
    adbq shell rm -f "$CONFIG_PATH" >/dev/null 2>&1 || true
    mark "geckolog off"
    say "Gecko's network log is off from the next start of Firedown on."
    if is_yes "$(ask "Force-stop Firedown now so it stops logging? [y/n]")"; then
        adbq shell am force-stop "$PKG" >/dev/null 2>&1 || true
        say "Force-stopped."
    fi
}

mode_geckolog() {
    case $GECKOLOG in
        on) geckolog_on ;;
        off) geckolog_off ;;
    esac
}

preflight
case $MODE in
    status) mode_status ;;
    freeze) mode_freeze ;;
    crash) mode_crash ;;
    crashloop) mode_crashloop ;;
    background) mode_background ;;
    geckolog) mode_geckolog ;;
    watch) mode_watch ;;
esac
mark "run end"

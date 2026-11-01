#!/usr/bin/env bash
# Checks scripts/update-install-test.sh with no phone and no Android SDK:
# the parts that read what the phone says (screen dumps, the install trace,
# the test receiver's answers) and the build.gradle edit, on canned input;
# then the whole script against two simulated phones (fake_adb.py): one
# where Android 12+ installs the update without asking, as the first real
# run found, one that shows Android's dialog; and five faults planted in
# them, each of which it must report.
#   bash scripts/update-install-test/selftest.sh      (QUICK=1: canned input only)

set -Euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
export UPDATE_INSTALL_TEST_LIB=1
# shellcheck source-path=SCRIPTDIR/..
. "$HERE/../update-install-test.sh"

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
: > "$OUT/results.txt"

OK=0
BAD=0
check() {
    if [ "$2" = "$3" ]; then
        OK=$((OK + 1))
    else
        BAD=$((BAD + 1))
        printf 'FAIL %s\n  got:  %s\n  want: %s\n' "$1" "$2" "$3"
    fi
}

# ── quoting for the phone's shell ──
check "q: spaces and parentheses" "$(sh -c "printf %s $(q 'Firedown 1.2.6 (test)')")" "Firedown 1.2.6 (test)"
check "q: a single quote" "$(sh -c "printf %s $(q "it's")")" "it's"

# ── the receiver's answers ──
ans=$(rx_data 'Broadcast completed: result=1, data="phase=PREPARING percent=42 gen=3 notif=false installed=1205 n666=progress:42/100 n667=none n668=none"')
check "rx_data" "$ans" "phase=PREPARING percent=42 gen=3 notif=false installed=1205 n666=progress:42/100 n667=none n668=none"
check "kv phase" "$(kv phase "$ans")" "PREPARING"
check "kv n666" "$(kv n666 "$ans")" "progress:42/100"
check "kv gen not percent" "$(kv gen "$ans")" "3"
check "kv missing" "$(kv nothing "$ans")" ""
check "rx_data no receiver" "$(rx_data 'Broadcast completed: result=0')" "error no answer: Broadcast completed: result=0"

# ── versions ──
check "name 1205" "$(name_for 1205)" "1.2.5"
check "name 1210" "$(name_for 1210)" "1.2.10"
check "name 1300" "$(name_for 1300)" "1.3.0"
cat > "$OUT/build.gradle" <<'EOF'
// versionCode:
//     PUT https://api.firedown.app/v1/mapping/<versionCode>
android {
    defaultConfig {
        applicationId "com.solarized.firedown"
        versionCode 1205
        versionName "1.2.5"
    }
}
EOF
check "read_version" "$(read_version "$OUT/build.gradle")" "1205"
with_version "$OUT/build.gradle" 1206 > "$OUT/build.gradle.new"
check "with_version code" "$(read_version "$OUT/build.gradle.new")" "1206"
check "with_version name" "$(grep -c 'versionName "1.2.6"' "$OUT/build.gradle.new")" "1"
check "with_version leaves comments" "$(grep -c '^// versionCode:' "$OUT/build.gradle.new")" "1"
check "with_version changes two lines" "$(diff "$OUT/build.gradle" "$OUT/build.gradle.new" | grep -c '^>')" "2"

# ── screen dumps ──
cat > "$OUT/ui.xml" <<'EOF'
<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0"><node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.solarized.firedown" bounds="[0,0][1080,2400]"><node index="0" text="Preparing update…" resource-id="com.solarized.firedown:id/update_sheet_progress_label" class="android.widget.TextView" package="com.solarized.firedown" bounds="[48,2100][600,2160]" /><node index="1" text="42%" resource-id="com.solarized.firedown:id/update_sheet_progress_percent" class="android.widget.TextView" package="com.solarized.firedown" bounds="[900,2100][1032,2160]" /><node index="2" text="" resource-id="com.solarized.firedown:id/update_sheet_progress" class="android.widget.LinearLayout" package="com.solarized.firedown" bounds="[48,2090][1032,2200]" /><node index="3" text="" resource-id="com.solarized.firedown:id/update_sheet_install_gone" class="android.widget.Button" package="com.solarized.firedown" bounds="[0,0][0,0]" /></node></hierarchy>
EOF
check "ui_find progress" "$(ui_find resource-id "com.solarized.firedown:id/update_sheet_progress" exact)" "540 2145"
check "ui_has progress" "$(ui_has update_sheet_progress && echo yes || echo no)" "yes"
check "ui_has install (absent)" "$(ui_has update_sheet_install && echo yes || echo no)" "no"
check "ui_has zero-size node" "$(ui_has update_sheet_install_gone && echo yes || echo no)" "no"
check "ui_text percent" "$(ui_text update_sheet_progress_percent)" "42%"
check "ui_text label" "$(ui_text update_sheet_progress_label)" "Preparing update…"

cat > "$OUT/shade.xml" <<'EOF'
<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0"><node index="0" text="Update ready" resource-id="android:id/title" class="android.widget.TextView" package="com.android.systemui" bounds="[150,400][700,450]" /><node index="1" text="Firedown 1.2.6 (test) is ready. Tap to install" resource-id="android:id/text" class="android.widget.TextView" package="com.android.systemui" bounds="[150,450][930,500]" /><node index="2" text="Update" resource-id="android:id/button1" class="android.widget.Button" package="com.google.android.packageinstaller" bounds="[700,1500][1000,1600]" /></hierarchy>
EOF
check "ui_find contains" "$(ui_find text "Firedown 1.2.6 (test)" contains "$OUT/shade.xml")" "540 475"
check "ui_find exact misses a substring" "$(ui_find text "Firedown 1.2.6 (test)" exact "$OUT/shade.xml")" ""
check "ui_find installer button" "$(ui_find resource-id "android:id/button1" exact "$OUT/shade.xml")" "850 1550"

# A dump whose nodes run across lines (one node per line, an attribute
# broken off): records are nodes, not lines.
printf '%s\n' '<?xml version="1.0" ?><hierarchy rotation="0">' \
    '<node index="0" text="Install" resource-id="com.solarized.firedown:id/update_sheet_install"' \
    ' class="android.widget.Button" bounds="[100,2000][980,2140]" />' \
    '<node index="1" text="Later" resource-id="com.solarized.firedown:id/update_sheet_later" bounds="[100,2150][980,2290]" /></hierarchy>' \
    > "$OUT/lines.xml"
check "ui_find across lines" "$(ui_find resource-id "com.solarized.firedown:id/update_sheet_install" exact "$OUT/lines.xml")" "540 2070"
check "ui_text across lines" "$(ui_text update_sheet_later "$OUT/lines.xml")" "Later"

# ── finding adb and a JDK ──
tools=$OUT/tools
mkdir -p "$tools/bin" "$tools/sdk/platform-tools" "$tools/home/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin"
printf '#!/bin/sh\nexit 0\n' > "$tools/sdk/platform-tools/adb"
printf '#!/bin/sh\nexit 0\n' > "$tools/home/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java"
chmod +x "$tools/sdk/platform-tools/adb" "$tools/home/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java"
for t in sed head; do ln -sf "$(command -v "$t")" "$tools/bin/$t"; done
mkdir -p "$tools/root"
printf 'sdk.dir=%s\n' "$tools/sdk" > "$tools/root/local.properties"
got=$(ADB=/x/adb; find_adb)
check "find_adb: \$ADB first" "$got" "/x/adb"
# shellcheck disable=SC2030 # PATH is meant to stay in the subshell
got=$(unset ADB ANDROID_HOME ANDROID_SDK_ROOT; PATH=$tools/bin; ROOT=$tools/root; HOME=$tools/home; find_adb)
check "find_adb: sdk.dir from local.properties" "$got" "$tools/sdk/platform-tools/adb"
# shellcheck disable=SC2030 # PATH is meant to stay in the subshell
got=$(unset ADB ANDROID_SDK_ROOT; PATH=$tools/bin; ROOT=$tools/nowhere; HOME=$tools/home; ANDROID_HOME=$tools/sdk; find_adb)
check "find_adb: \$ANDROID_HOME" "$got" "$tools/sdk/platform-tools/adb"
# shellcheck disable=SC2030 # PATH is meant to stay in the subshell
got=$(unset ADB ANDROID_HOME ANDROID_SDK_ROOT; PATH=$tools/bin; ROOT=$tools/nowhere; HOME=$tools/nohome; find_adb)
check "find_adb: none" "$got" ""
# On a Mac with Android Studio installed the system-wide copy is found
# first; either way JAVA_HOME must end up at a JDK.
# shellcheck disable=SC2030 # PATH is meant to stay in the subshell
got=$(unset JAVA_HOME; PATH=$tools/bin; HOME=$tools/home; find_jdk >/dev/null; printf '%s' "$JAVA_HOME")
check "find_jdk: Android Studio's JDK" "$([ -n "$got" ] && [ -x "$got/bin/java" ] && echo found || echo "none ($got)")" "found"
# shellcheck disable=SC2030 # PATH is meant to stay in the subshell
got=$(JAVA_HOME=/my/jdk; PATH=$tools/bin; HOME=$tools/home; find_jdk >/dev/null; printf '%s' "$JAVA_HOME")
check "find_jdk: JAVA_HOME kept" "$got" "/my/jdk"

# ── the install trace ──
line() {
    printf '10-17 12:00:00.000  1234  1300 D UpdateInstallTrace: phase=%s percent=%s gen=%s notif=false\n' "$1" "$2" "$3"
}
{
    printf '10-17 11:59:00.000  1 1 I FDUPDTEST: case sheet start\n'
    line PREPARING 9 1
    line FAILED -1 1
    printf '10-17 12:00:00.000  1 1 I FDUPDTEST: case sheet start\n'
    line PREPARING 0 4
    line PREPARING 37 4
    line PREPARING 100 4
    line INSTALLING -1 4
    line IDLE -1 4
    printf '10-17 12:00:00.000  1 1 I FDUPDTEST: case refused start\n'
    line PREPARING 0 1
    printf '10-17 12:00:00.000  1 1 D com.solarized.firedown.UpdateInstaller: install already running\n'
    line PREPARING 50 1
    line PREPARING 100 1
    line INSTALLING -1 1
    line FAILED -1 1
    line PREPARING 0 2
    line PREPARING 100 2
    line INSTALLING -1 2
    line FAILED -1 2
} > "$OUT/logcat.txt"

out=$(case_log sheet | check_trace 1 "PREPARING INSTALLING IDLE") && r=0 || r=1
check "trace: the last marker's run passes" "$r $out" "0 PREPARING INSTALLING IDLE (3 percent steps, last 100%)"
case_log sheet | check_trace 1 "PREPARING INSTALLING FAILED" >/dev/null && r=0 || r=1
check "trace: a different ending fails" "$r" "1"
out=$(case_log refused | check_trace 2 "PREPARING INSTALLING FAILED") && r=0 || r=1
check "trace: the second install" "$r $out" "0 PREPARING INSTALLING FAILED (2 percent steps, last 100%)"
check "count_installs" "$(case_log refused | count_installs)" "2"
check "case_log keeps other cases out" "$(case_log sheet | grep -c 'install already running')" "0"

back=$( { line PREPARING 0 1; line PREPARING 60 1; line PREPARING 40 1; line PREPARING 100 1; line INSTALLING -1 1; line IDLE -1 1; } | check_trace 1 "PREPARING INSTALLING IDLE") && r=0 || r=1
check "trace: a percent going back fails" "$r $(printf '%s' "$back" | tail -n 1)" "1 the percent went back from 60 to 40"
short=$( { line PREPARING 0 1; line PREPARING 80 1; line INSTALLING -1 1; line IDLE -1 1; } | check_trace 1 "PREPARING INSTALLING IDLE") && r=0 || r=1
check "trace: a copy short of 100% fails" "$r $(printf '%s' "$short" | tail -n 1)" "1 the copy never reached 100%"
{ line PREPARING 0 1; line FAILED -1 1; } | check_trace 1 "PREPARING FAILED" >/dev/null && r=0 || r=1
check "trace: a missing file needs no 100%" "$r" "0"
none=$(printf 'nothing\n' | check_trace 1 "PREPARING FAILED") && r=0 || r=1
check "trace: no trace fails" "$r $none" "1 no trace for install 1"

# ── a phone's log is megabytes: tests must read to the end ──
{
    printf '10-17 12:00:00.000  1 1 I FDUPDTEST: case refused start\n'
    printf '10-17 12:00:00.000  1 1 D com.solarized.firedown.UpdateInstaller: install already running\n'
    i=0
    while [ $i -lt 3000 ]; do
        printf '10-17 12:00:00.000  1 1 D ActivityManager: background noise background noise background noise %d\n' $i
        i=$((i + 1))
    done
    printf '10-17 12:00:01.000  1 1 I FDUPDTEST: case missing start\n'
} > "$OUT/logcat.txt"
check "log_wait finds a line in a long log" "$(log_wait refused 'install already running' 0 && echo yes || echo no)" "yes"
check "log_wait: not in another case" "$(log_wait missing 'install already running' 0 && echo yes || echo no)" "no"
# The trap the first real run fell into, kept as a reason: grep -q stops
# reading, the writer dies of SIGPIPE, and pipefail reports a miss.
check "the old grep -q misses it" "$(case_log refused | grep -q 'install already running' && echo found || echo missed)" "missed"
big=$(i=0; while [ $i -lt 2000 ]; do printf 'padding %d xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\n' $i; i=$((i + 1)); done)
check "has: a match early in a long text" "$(has "granted=true
$big" 'granted=true' && echo yes || echo no)" "yes"
check "has: no match" "$(has "$big" 'granted=true' && echo yes || echo no)" "no"

# ── the sheet's footer trace ──
sline() {
    printf '10-17 12:00:00.000  1234  1234 D UpdateSheetTrace: sheet=%s footer=%s percent=%s gen=%s\n' "$1" "$2" "$3" "$4"
}
{
    printf '10-17 12:00:00.000  1 1 I FDUPDTEST: case sheet start\n'
    sline 1a button -1 0
    sline 1a progress 0 1
    sline 1a progress 40 1
    sline 1a progress 100 1
    sline 1a installing -1 1
    printf '10-17 12:00:00.000  1 1 I FDUPDTEST: case refused start\n'
    sline 2b button -1 0
    sline 2b progress 10 1
    printf '10-17 12:00:00.000  1 1 I FDUPDTEST: rebuild\n'
    sline 3c progress 30 1
    printf '10-17 12:00:00.000  1 1 I FDUPDTEST: case missing start\n'
    sline 4d button -1 0
    sline 4d progress 10 1
    printf '10-17 12:00:00.000  1 1 I FDUPDTEST: rebuild\n'
    line FAILED -1 1
    sline 5e error -1 1
    printf '10-17 12:00:00.000  1 1 I FDUPDTEST: case notification start\n'
    sline 6f progress 10 1
    printf '10-17 12:00:00.000  1 1 I FDUPDTEST: rebuild\n'
    sline 7a button -1 0
    sline 7a button -1 1
    printf '10-17 12:00:00.000  1 1 I FDUPDTEST: case install start\n'
    sline 8b progress 10 1
    printf '10-17 12:00:00.000  1 1 I FDUPDTEST: rebuild\n'
    sline 8b progress 20 1
} > "$OUT/logcat.txt"
check "footers" "$(footers sheet | tr '\n' '|')" "1a button -1|1a progress 0|1a progress 40|1a progress 100|1a installing -1|"
check "count_footers" "$(count_footers sheet progress)" "3"
check "rebuilt_ok: the rebuilt sheet shows the copy" "$(rebuilt_ok refused | tail -n 1)" "  PASS the sheet rebuilt by a font size change came back showing the copy (progress)"
check "rebuilt_ok: the copy ended first" "$(rebuilt_ok missing | tail -n 1)" "  INFO the install ended before the rebuilt sheet appeared; it shows \"error\""
check "rebuilt_ok: lost the copy" "$(rebuilt_ok notification | tail -n 1)" "  FAIL the sheet rebuilt mid-copy came back showing \"button\" instead of the progress"
check "rebuilt_ok: not rebuilt" "$(rebuilt_ok install | tail -n 1)" "  INFO the font size change did not rebuild the sheet; the rebuilt sheet was not checked"

# ── the whole script against a simulated phone (fake_adb.py) ──
# A clean run, from the build to the phone put back as it was found, then
# one run per planted fault, each of which the script must report.
# QUICK=1 skips this part (it takes about a minute and a half).
if [ "${QUICK:-0}" != 1 ]; then
    REPO=$(cd "$HERE/../.." && pwd)
    FAKE="$OUT/fake"
    mkdir -p "$FAKE/bin" "$FAKE/root/app"
    cp "$REPO/app/build.gradle" "$FAKE/root/app/build.gradle"
    cp "$REPO/app/build.gradle" "$FAKE/build.gradle.orig"
    cat > "$FAKE/root/gradlew" <<'GRADLEW'
#!/bin/sh
# Stands in for the build: an "APK" that only names its versionCode.
code=$(sed -n 's/^[[:space:]]*versionCode[[:space:]][[:space:]]*\([0-9][0-9]*\).*/\1/p' app/build.gradle | head -n 1)
mkdir -p app/build/outputs/apk/debug
printf 'fakeapk versionCode=%s\n' "$code" > app/build/outputs/apk/debug/app-debug.apk
GRADLEW
    chmod +x "$FAKE/root/gradlew"
    printf '#!/bin/sh\nexec python3 %s "$@"\n' "$HERE/fake_adb.py" > "$FAKE/bin/adb"
    chmod +x "$FAKE/bin/adb"
    base=$(read_version "$FAKE/root/app/build.gradle")

    # run_fake NAME BUG [KEY=VALUE… --] [options…]: the script's exit code;
    # its output in run-NAME.txt, the phone's state in phone-NAME.json.
    run_fake() {
        local name=$1 bug=$2 rc
        local -a envs=()
        shift 2
        while [ $# -gt 0 ] && [ "$1" != -- ] && has "$1" =; do
            envs+=("$1")
            shift
        done
        if [ "${1:-}" = -- ]; then
            shift
        fi
        rm -rf "$FAKE/phone"
        mkdir -p "$FAKE/phone"
        # shellcheck disable=SC2031 # the real PATH, untouched by the subshells above
        env PATH="$FAKE/bin:$PATH" FAKE_BUG="$bug" FAKE_PHONE_DIR="$FAKE/phone" FAKE_COPY_S=1.5 \
            UPDATE_INSTALL_TEST_ROOT="$FAKE/root" OUT_DIR="$FAKE/out-$name" UPDATE_INSTALL_TEST_LIB=0 \
            ${envs[@]+"${envs[@]}"} \
            bash "$HERE/../update-install-test.sh" "$@" < /dev/null > "$FAKE/run-$name.txt" 2>&1 && rc=0 || rc=$?
        cp "$FAKE/phone/phone.json" "$FAKE/phone-$name.json" 2>/dev/null || true
        printf '%s' "$rc"
    }
    said() {
        grep -qF -- "$2" "$FAKE/run-$1.txt" && echo yes || echo no
    }
    # What the run left on the phone: version, appops, settings, files,
    # notification permission, revokes.
    left() {
        python3 -c "
import json, sys
s = json.load(open(sys.argv[1]))
print(s['installed'], s['appops'], s['settings'], s['files'], s['notif_perm'], s['revokes'])" "$FAKE/phone-$1.json"
    }

    printf 'running the script against the simulated phones…\n'
    # Android 12+: the update installs without asking. The shade ignores the
    # shell command (a swipe opens it) and notifications were already
    # allowed, which the script must leave as it found them.
    check "silent: exit code" "$(run_fake clean '' FAKE_SHADE=stuck --)" "0"
    check "silent: nothing failed" "$(said clean ' 0 failed')" "yes"
    for want in 'Phone: samsung SM-FAKE, Android 14 (SDK 34)' \
            'PASS the footer showed the progress:' \
            'PASS Android installed the update without asking' \
            'PASS phases in order: PREPARING INSTALLING (' \
            'PASS the "Firedown updated" notification was posted' \
            'PASS no progress notification left behind' \
            "INFO the cancel path needs Android's dialog" \
            'PASS the footer kept counting after the rotation' \
            'PASS the sheet rebuilt by a font size change came back showing the copy' \
            'PASS a second install requested while the first copied was turned away' \
            'PASS the sheet shows the error line and the Install button again' \
            'PASS Install works again after a failure' \
            'PASS phases in order: PREPARING FAILED' \
            'INFO tapped the notification in the shade' \
            'PASS the install ran as started from the notification' \
            'INFO skipped: Android already installed the update'; do
        check "silent: $want" "$(said clean "$want")" "yes"
    done
    check "silent: every adb command understood" "$(cat "$FAKE/phone/unhandled.txt" 2>/dev/null)" ""
    check "silent: build.gradle put back" "$(cmp -s "$FAKE/build.gradle.orig" "$FAKE/root/app/build.gradle" && echo same)" "same"
    check "silent: the phone put back, notifications still allowed" "$(left clean)" "$base default {} {} True 0"

    # Android's dialog (an older phone, or another installer owning updates).
    check "dialog: exit code" "$(run_fake dialog '' FAKE_DIALOG=1 FAKE_NOTIF_GRANTED=0 -- --skip-build)" "0"
    for want in "PASS Android's dialog appeared" \
            "INFO Android asked although Firedown requests" \
            'PASS the sheet closed when Android answered' \
            "PASS cancelling Android's dialog leaves the state IDLE" \
            'PASS the sheet stays closed after the cancel' \
            'PASS the progress notification is gone' \
            "PASS Firedown is now $(name_for $((base + 1)))" \
            'PASS the "Firedown updated" notification was posted' \
            'WARN cancelling'; do
        check "dialog: $want" "$(said dialog "$want")" "yes"
    done
    check "dialog: the permission it granted is taken back" "$(left dialog)" "$base default {} {} False 1"
    check "android 11: exit code" "$(run_fake sdk30 '' FAKE_SDK=30 -- --skip-build --only sheet)" "0"
    check "android 11: no note about asking" "$(said sdk30 'Android asked although')" "no"
    # A finger closes the shade under the first read: read it again.
    check "touched shade: exit code" "$(run_fake touched '' FAKE_SHADE=touched -- --skip-build --only notification)" "0"
    check "touched shade: the touch named" "$(said touched 'the screen was touched while the shade was being read')" "yes"
    check "touched shade: read again and tapped" "$(said touched 'INFO tapped the notification in the shade')" "yes"
    # Android 10 has no all-windows dump, so a shade the plain dump cannot
    # see falls back to the test receiver.
    check "android 10: exit code" "$(run_fake sdk29 '' FAKE_SDK=29 -- --skip-build --only notification)" "0"
    check "android 10: the tap sent another way" "$(said sdk29 'sent its tap through the test receiver')" "yes"

    check "fault cancel-fails: fails" "$(run_fake cancel cancel-fails FAKE_DIALOG=1 -- --skip-build --only sheet)" "1"
    check "fault cancel-fails: named" "$(said cancel "FAIL after cancelling Android's dialog")" "yes"
    check "fault no-progress: fails" "$(run_fake progress no-progress --skip-build --only sheet)" "1"
    check "fault no-progress: named" "$(said progress 'FAIL the sheet never showed the progress')" "yes"
    check "fault double: fails" "$(run_fake double double --skip-build --only refused)" "1"
    check "fault double: named" "$(said double 'a second install ran while the first was still copying')" "yes"
    check "fault rotate-loses: fails" "$(run_fake rotate rotate-loses --skip-build --only refused)" "1"
    check "fault rotate-loses: named" "$(said rotate 'FAIL the sheet rebuilt mid-copy came back showing')" "yes"
    check "fault no-updated: fails" "$(run_fake updated no-updated --skip-build --only sheet)" "1"
    check "fault no-updated: named" "$(said updated 'FAIL no "Firedown updated" notification')" "yes"
fi

printf '\n%d passed, %d failed\n' "$OK" "$BAD"
[ "$BAD" = 0 ]

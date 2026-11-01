#!/usr/bin/env bash
# Checks scripts/update-install-test.sh with no phone and no Android SDK:
# the parts that read what the phone says (screen dumps, the install trace,
# the test receiver's answers) and the build.gradle edit, on canned input;
# then the whole script against a simulated phone (fake_adb.py), and three
# faults planted in that phone, each of which it must report.
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

    # run_fake NAME BUG [options…]: the script's exit code; its output in run-NAME.txt.
    run_fake() {
        local name=$1 bug=$2 rc
        shift 2
        rm -rf "$FAKE/phone"
        mkdir -p "$FAKE/phone"
        PATH="$FAKE/bin:$PATH" FAKE_BUG="$bug" FAKE_PHONE_DIR="$FAKE/phone" FAKE_COPY_S=1.5 \
            UPDATE_INSTALL_TEST_ROOT="$FAKE/root" OUT_DIR="$FAKE/out-$name" UPDATE_INSTALL_TEST_LIB=0 \
            bash "$HERE/../update-install-test.sh" "$@" < /dev/null > "$FAKE/run-$name.txt" 2>&1 && rc=0 || rc=$?
        printf '%s' "$rc"
    }
    has() {
        grep -qF -- "$2" "$FAKE/run-$1.txt" && echo yes || echo no
    }

    printf 'running the script against the simulated phone…\n'
    check "dry run: exit code" "$(run_fake clean '')" "0"
    check "dry run: nothing failed" "$(has clean ' 0 failed')" "yes"
    for want in 'the footer shows "Preparing update' \
            "PASS Android's dialog appeared" \
            "cancelling Android's dialog leaves the state IDLE" \
            'after rotating: the footer still shows' \
            'was turned away' \
            'Install works again after a failure' \
            'PASS phases in order: PREPARING FAILED' \
            'the notification shows the install: progress:' \
            'the progress notification is gone' \
            "PASS Firedown is now $(name_for $((base + 1)))" \
            'the "Firedown updated" notification was posted' \
            'WARN cancelling'; do
        check "dry run: $want" "$(has clean "$want")" "yes"
    done
    check "dry run: every adb command understood" "$(cat "$FAKE/phone/unhandled.txt" 2>/dev/null)" ""
    check "dry run: build.gradle put back" "$(cmp -s "$FAKE/build.gradle.orig" "$FAKE/root/app/build.gradle" && echo same)" "same"
    check "dry run: the phone put back" "$(python3 -c "
import json, sys
s = json.load(open(sys.argv[1]))
print(s['installed'], s['appops'], s['settings'], s['files'])" "$FAKE/phone/phone.json")" "$base default {} {}"

    check "fault cancel-fails: fails" "$(run_fake cancel cancel-fails --skip-build --only sheet)" "1"
    check "fault cancel-fails: named" "$(has cancel "FAIL after cancelling Android's dialog")" "yes"
    check "fault no-progress: fails" "$(run_fake progress no-progress --skip-build --only sheet)" "1"
    check "fault no-progress: named" "$(has progress 'but the sheet shows no progress')" "yes"
    check "fault double: fails" "$(run_fake double double --skip-build --only refused)" "1"
    check "fault double: named" "$(has double 'a second install ran while the first was still copying')" "yes"
fi

printf '\n%d passed, %d failed\n' "$OK" "$BAD"
[ "$BAD" = 0 ]

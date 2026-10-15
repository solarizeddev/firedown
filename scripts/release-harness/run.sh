#!/bin/sh
# scripts/release-harness/run.sh — drives the REAL app/release.gradle
# `bumpVersion` task under a scratch Gradle project (no Android plugin, no
# SDK: the task only needs a build.gradle with versionCode/versionName lines
# and a CHANGELOG.md) and pins the versionCode ↔ versionName mirror:
#
#   the versionName IS the versionCode written as major.minor.patch —
#   1202 ↔ "1.2.2", 1210 ↔ "1.2.10", 1299 + 1 → 1300 ↔ "1.3.0" — an
#   explicit -Pfiredown.versionName moves the CODE to match, and a
#   build.gradle whose two values disagree is refused, untouched. The
#   zero-padded name every release through 1.2.01 carried ("1.2.01") is
#   still accepted as the CURRENT name, so the first bump from it works.
#
# The derivation cases were written against the integer-increment task
# first, where the explicit name left the code at +1 (cases 5–6) and a
# minor rollover was never produced. `releaseStatus` carries the same
# agreement check but reads `android.defaultConfig`, so it is not driven
# here. Uses $GRADLE, else `gradle` on PATH, else the repo's wrapper.
set -u
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
release="$repo/app/release.gradle"
if [ -n "${GRADLE:-}" ]; then
    gradle=$GRADLE
elif command -v gradle >/dev/null 2>&1; then
    gradle=$(command -v gradle)
else
    gradle="$repo/gradlew"
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
pass=0
fail=0
proj="$work/p"

setup() { # $1 code, $2 name, $3 top CHANGELOG version
    rm -rf "$proj"
    mkdir -p "$proj"
    : > "$proj/settings.gradle"
    {
        printf '/*\n        versionCode %s\n        versionName "%s"\n*/\n' "$1" "$2"
        printf "apply from: '%s'\n" "$release"
    } > "$proj/build.gradle"
    printf '## [%s] - 2026-10-12\n\n- a bullet\n\n## [1.2.00] - 2026-10-08\n\n- older\n' "$3" > "$proj/CHANGELOG.md"
}

run() { # extra gradle args
    "$gradle" -p "$proj" --offline --no-daemon -q bumpVersion "$@" > "$work/out" 2>&1
    echo $? > "$work/status"
}

ok() {
    pass=$((pass + 1))
    printf 'ok   %s\n' "$1"
}
bad() {
    fail=$((fail + 1))
    printf 'FAIL %s\n' "$1"
    sed 's/^/     | /' "$work/out" | head -20
}

# expect_bump <label> <code> <name> <wantCode> <wantName> [gradle args…]
expect_bump() {
    label=$1; code=$2; name=$3; want_code=$4; want_name=$5
    shift 5
    setup "$code" "$name" "$name"
    run "$@"
    if [ "$(cat "$work/status")" != 0 ]; then bad "$label: task failed"; return; fi
    grep -q "^        versionCode ${want_code}\$" "$proj/build.gradle" || { bad "$label: versionCode not ${want_code}"; cat "$proj/build.gradle" | sed 's/^/     | /'; return; }
    grep -q "^        versionName \"${want_name}\"\$" "$proj/build.gradle" || { bad "$label: versionName not \"${want_name}\""; sed 's/^/     | /' "$proj/build.gradle"; return; }
    [ "$(head -1 "$proj/CHANGELOG.md")" = "## [${want_name}] - $(date +%Y-%m-%d)" ] || { bad "$label: CHANGELOG entry not opened for ${want_name}"; head -3 "$proj/CHANGELOG.md" | sed 's/^/     | /'; return; }
    ok "$label → ${want_code} / \"${want_name}\""
}

# expect_refusal <label> <code> <name> <needle> [gradle args…]
expect_refusal() {
    label=$1; code=$2; name=$3; needle=$4
    shift 4
    setup "$code" "$name" "$name"
    before=$(cat "$proj/build.gradle" "$proj/CHANGELOG.md")
    run "$@"
    if [ "$(cat "$work/status")" = 0 ]; then bad "$label: task succeeded"; return; fi
    grep -q -- "$needle" "$work/out" || { bad "$label: message lacks '${needle}'"; return; }
    [ "$(cat "$proj/build.gradle" "$proj/CHANGELOG.md")" = "$before" ] || { bad "$label: files were touched"; return; }
    ok "$label: refused, files untouched"
}

echo "release-harness: gradle = $gradle"
expect_bump    '1 the released 1.2.01 bumps to an unpadded patch' 1201 1.2.01 1202 1.2.2
expect_bump    '2 plain bump past nine keeps counting' 1209 1.2.9 1210 1.2.10
expect_bump    '3 the hundredth patch rolls into the next minor' 1299 1.2.99 1300 1.3.0
expect_bump    '4 …and the tenth minor into the next major' 1999 1.9.99 2000 2.0.0
expect_bump    '5 explicit name starts a minor, the code follows' 1202 1.2.2 1300 1.3.0 -Pfiredown.versionName=1.3.0
expect_bump    '6 explicit name starts a major, the code follows' 1202 1.2.2 2000 2.0.0 -Pfiredown.versionName=2.0.0
expect_refusal '7 explicit name with a padded patch' 1202 1.2.2 'leading zero' -Pfiredown.versionName=1.3.00
expect_refusal '8 explicit name off the scheme (two-digit minor)' 1202 1.2.2 'leading zero' -Pfiredown.versionName=1.10.0
expect_refusal '9 explicit name not above the current code' 1202 1.2.2 'not above' -Pfiredown.versionName=1.1.99
expect_refusal '10 explicit name equal to the current code' 1202 1.2.2 'not above' -Pfiredown.versionName=1.2.2
expect_refusal '11 a build.gradle whose code and name disagree' 1202 1.2.3 'disagree'
expect_refusal '12 …padded or not' 1202 1.2.03 'disagree'

# 13: an entry already opened for the new version is not duplicated.
setup 1201 1.2.01 1.2.2
run
if [ "$(cat "$work/status")" = 0 ] && [ "$(grep -c '^## \[1.2.2\]' "$proj/CHANGELOG.md")" = 1 ]; then
    ok '13 an existing CHANGELOG entry for the new version is kept, not duplicated'
else
    bad '13 CHANGELOG entry duplicated or task failed'
fi

echo "release-harness: ${pass} passed, ${fail} failed"
[ "$fail" = 0 ]

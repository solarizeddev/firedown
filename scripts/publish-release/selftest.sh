#!/usr/bin/env bash
# Self-test for scripts/publish-release.sh: runs it against a simulated
# release world (fake/): GitHub releases that are drafts until published,
# the server's web folder, the F-Droid folder's publish.sh, ssh and rsync,
# with real git remotes for firedown and firedown-website. Checks the whole
# release, every refusal (nothing announced before its files are online),
# resuming, and a run repeated after success. Then plants four faults in a
# copy of the script; each must be caught.
#
# Usage:  scripts/publish-release/selftest.sh     (needs bash, git, python3)

# The planted faults are literal text (SC2016), and each runs in its own
# subshell so its failures stay out of the totals (SC2030, SC2031).
# shellcheck disable=SC2016,SC2030,SC2031

set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REAL_SCRIPT=$HERE/../publish-release.sh
SCRIPT=$REAL_SCRIPT
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASS=0
FAIL=0
QUIET=0

pass() { PASS=$((PASS + 1)); [ "$QUIET" = 1 ] || printf '  PASS %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); [ "$QUIET" = 1 ] || printf '  FAIL %s\n' "$1"; }
# check DESCRIPTION COMMAND...: PASS when the command succeeds.
check() {
    local d=$1
    shift
    if "$@"; then pass "$d"; else fail "$d"; fi
}

export GIT_CONFIG_GLOBAL=$TMP/gitconfig GIT_CONFIG_NOSYSTEM=1
git config --file "$GIT_CONFIG_GLOBAL" user.name "Self Test"
git config --file "$GIT_CONFIG_GLOBAL" user.email "selftest@example.invalid"
git config --file "$GIT_CONFIG_GLOBAL" init.defaultBranch main
git config --file "$GIT_CONFIG_GLOBAL" commit.gpgsign false
git config --file "$GIT_CONFIG_GLOBAL" advice.detachedHead false

GITHUB=https://github.com/solarizeddev/firedown/releases/download/v1.2.6
SITE=https://www.firedown.app

# status_json OUT DIR DELTAS: the status.json releaseStatus writes for the
# files in DIR (with the .xz and a patch when DELTAS=1).
status_json() {
    python3 -I - "$1" "$2" "$3" "$GITHUB" "$SITE" <<'EOF'
import hashlib, json, os, sys
out, d, deltas, gh, site = sys.argv[1:]
def sha(p): return hashlib.sha256(open(p, "rb").read()).hexdigest()
s = {"versionCode": 1206, "updateUrl": gh + "/app-release.apk",
     "updateUrlFallbacks": [site + "/fdroid/repo/com.solarized.firedown_1206.apk"],
     "versionName": "Firedown 1.2.6", "sha256": sha(os.path.join(d, "app-release.apk")),
     "changelog": "• Something new"}
if deltas == "1":
    p = os.path.join(d, "patch-1205-1206.fdpatch")
    x = os.path.join(d, "firedown-1206.apk.xz")
    s["patches"] = [{"fromVersionCode": 1205, "fromSha256": "ab" * 32,
                     "urls": [gh + "/patch-1205-1206.fdpatch", site + "/updates/patch-1205-1206.fdpatch"],
                     "sha256": sha(p), "size": os.path.getsize(p)}]
    s["compressed"] = {"format": "xz", "urls": [gh + "/firedown-1206.apk.xz", site + "/updates/firedown-1206.apk.xz"],
                       "sha256": sha(x), "size": os.path.getsize(x)}
open(out, "w").write(json.dumps(s, ensure_ascii=False) + "\n")
EOF
}

# world NAME [DELTAS] [EXCLUDE]: a fresh release world under $TMP/NAME —
# firedown at 1.2.5 with 1.2.6 built (status.json and release-history.json
# changed, not committed), the website at 1.2.5, an old release's patch and a
# file that is not ours in the server's /updates/.
world() {
    local w=$TMP/$1 deltas=${2:-1} exclude=${3:-1} upd
    rm -rf "$w"
    mkdir -p "$w/bin" "$w/github" "$w/server/www/updates" "$w/fdroid"
    cp "$HERE/fake/curl" "$HERE/fake/rsync" "$HERE/fake/ssh" "$HERE/fake/ssh-add" \
        "$HERE/fake/ssh-agent" "$HERE/fake/gh" "$w/bin/"
    chmod +x "$w/bin/"*
    : > "$w/events"

    mkdir -p "$w/fd/app"
    printf '        versionCode 1206\n        versionName "1.2.6"\n' > "$w/fd/app/build.gradle"
    printf '{"versionCode":1205}\n' > "$w/fd/status.json"
    printf '[{"versionCode":1205}]\n' > "$w/fd/release-history.json"
    printf 'build/\n' > "$w/fd/.gitignore"
    cp "$HERE/fake/gradlew" "$w/fd/gradlew"
    chmod +x "$w/fd/gradlew"
    git -C "$w/fd" init -q
    git -C "$w/fd" add -A
    git -C "$w/fd" commit -qm "1.2.5"
    git init -q --bare "$w/fd-origin.git"
    git -C "$w/fd" remote add origin "$w/fd-origin.git"
    git -C "$w/fd" push -q origin main

    upd=$w/fd/app/build/outputs/update/v1.2.6
    mkdir -p "$upd"
    head -c 300000 /dev/urandom > "$upd/app-release.apk"
    if [ "$deltas" = 1 ]; then
        head -c 120000 /dev/urandom > "$upd/firedown-1206.apk.xz"
        head -c 9000 /dev/urandom > "$upd/patch-1205-1206.fdpatch"
    fi
    (cd "$upd" && sha256sum ./* | sed 's#\./##' > SHA256SUMS)
    status_json "$w/fd/status.json" "$upd" "$deltas"
    printf '[{"versionCode":1206},{"versionCode":1205}]\n' > "$w/fd/release-history.json"

    mkdir -p "$w/seed/.github/workflows"
    {
        echo "          rsync -avz --delete \\"
        echo "            --exclude='.git*' --exclude='.github' \\"
        if [ "$exclude" = 1 ]; then
            echo "            --exclude='/app-release.apk' --exclude='/fdroid/' --exclude='/updates/' \\"
        else
            echo "            --exclude='/app-release.apk' --exclude='/fdroid/' \\"
        fi
        echo "            ./ deploy@host:/var/www/firedown-website/"
    } > "$w/seed/.github/workflows/deploy.yml"
    printf '{"versionCode":1205}\n' > "$w/seed/status.json"
    git -C "$w/seed" init -q
    git -C "$w/seed" add -A
    git -C "$w/seed" commit -qm "site"
    git init -q --bare "$w/web-origin.git"
    git -C "$w/seed" push -q "$w/web-origin.git" main
    git clone -q "$w/web-origin.git" "$w/web"

    cp "$HERE/fake/publish.sh" "$w/fdroid/publish.sh"
    chmod +x "$w/fdroid/publish.sh"
    printf 'REMOTE_USER=deploy\nREMOTE_HOST=203.0.113.5\nREMOTE_PATH=/var/www/firedown-website/fdroid\n' > "$w/fdroid/.env"

    printf 'old' > "$w/server/www/updates/patch-1203-1205.fdpatch"
    printf 'not ours' > "$w/server/www/updates/README.txt"
}

EXTRA=()
# run NAME ANSWERS [ARGS...]: the script in world NAME, its prompts answered
# by ANSWERS (printf %b), extra environment from EXTRA. Output in NAME/out,
# exit status in NAME/rc.
run() {
    local w=$TMP/$1 answers=$2
    shift 2
    (
        cd "$w/fd" || exit 99
        printf '%b' "$answers" | env PATH="$w/bin:$PATH" FAKE_WORLD="$w" FAKE_CODE=1206 \
            PUBLISH_ROOT="$w/fd" FDROID_DIR="$w/fdroid" WEBSITE_DIR="$w/web" \
            SITE_PATH="$w/server/www" GRADLEW="$w/fd/gradlew" \
            RETRY_SLEEP=0 CHECK_TIMEOUT=2 CHECK_INTERVAL=1 ${EXTRA[@]+"${EXTRA[@]}"} \
            bash "$SCRIPT" "$@"
    ) > "$w/out" 2>&1
    echo $? > "$w/rc"
}

rc_is() { [ "$(cat "$TMP/$1/rc")" = "$2" ]; }
said() { grep -qF -- "$2" "$TMP/$1/out"; }
not_said() { ! grep -qF -- "$2" "$TMP/$1/out"; }
event() { grep -qF -- "$2" "$TMP/$1/events"; }
no_event() { ! grep -qF -- "$2" "$TMP/$1/events"; }
# on_origin NAME REPO PATH FILE: REPO's main holds FILE's bytes at PATH.
on_origin() { git --git-dir "$TMP/$1/$2" show "main:$3" 2>/dev/null | cmp -s - "$4"; }
website_old() { [ "$(git --git-dir "$TMP/$1/web-origin.git" show main:status.json)" = '{"versionCode":1205}' ]; }
firedown_old() { [ "$(git --git-dir "$TMP/$1/fd-origin.git" show main:status.json)" = '{"versionCode":1205}' ]; }
same() { cmp -s "$1" "$2"; }
draft() { [ -e "$TMP/$1/github/v1.2.6.draft" ]; }
# in_order NAME TEXT...: the events contain each TEXT, in this order.
in_order() {
    local n=$1 line=0 at
    shift
    for t in "$@"; do
        at=$(grep -nF -- "$t" "$TMP/$n/events" | awk -F: -v after="$line" '$1 > after { print $1; exit }')
        [ -n "$at" ] || return 1
        line=$at
    done
}
show_out() { [ "$QUIET" = 1 ] || sed 's/^/      | /' "$TMP/$1/out" | tail -n "${2:-8}"; }

UPD=app/build/outputs/update/v1.2.6

case_clean() {
    say "clean: the whole release"
    world clean
    run clean 'y\ny\ny\n'
    check "finishes" rc_is clean 0
    check "says it is done" said clean "Done: Firedown 1.2.6 is published"
    check "the GitHub release is published" draft_gone clean
    check "the F-Droid repo has the APK" same "$TMP/clean/server/www/fdroid/repo/com.solarized.firedown_1206.apk" "$TMP/clean/fd/$UPD/app-release.apk"
    check "the mirror has the .xz" same "$TMP/clean/server/www/updates/firedown-1206.apk.xz" "$TMP/clean/fd/$UPD/firedown-1206.apk.xz"
    check "the mirror has the patch" same "$TMP/clean/server/www/updates/patch-1205-1206.fdpatch" "$TMP/clean/fd/$UPD/patch-1205-1206.fdpatch"
    check "the mirror has no APK copy" test ! -e "$TMP/clean/server/www/updates/app-release.apk"
    check "the old release's patch is gone from the mirror" test ! -e "$TMP/clean/server/www/updates/patch-1203-1205.fdpatch"
    check "a file that is not ours is left alone" test -e "$TMP/clean/server/www/updates/README.txt"
    check "the server login comes from publish.sh's .env" event clean "ssh deploy@203.0.113.5 cd"
    check "firedown main has the new status.json" on_origin clean fd-origin.git status.json "$TMP/clean/fd/status.json"
    check "firedown main has the new release history" on_origin clean fd-origin.git release-history.json "$TMP/clean/fd/release-history.json"
    check "the website has the new status.json" on_origin clean web-origin.git status.json "$TMP/clean/fd/status.json"
    check "upload, publish, F-Droid, mirror, then the check, in order" in_order clean \
        "gradlew uploadUpdateRelease" "gh release edit v1.2.6" "publish.sh" "rsync deploy@203.0.113.5" \
        "ssh deploy@203.0.113.5 cd" "GET $SITE/status.json?release-check"
    check "every file is looked at once more before status.json" in_order clean \
        "rsync deploy@203.0.113.5" "HEAD $GITHUB/app-release.apk" "HEAD $SITE/fdroid/repo/com.solarized.firedown_1206.apk" \
        "HEAD $GITHUB/patch-1205-1206.fdpatch" "HEAD $SITE/updates/patch-1205-1206.fdpatch" "HEAD $SITE/updates/firedown-1206.apk.xz"
    show_out clean 4

    say "again: the same release run a second time"
    run clean 'y\ny\n'
    check "finishes" rc_is clean 0
    check "no second commit on firedown" test "$(git --git-dir "$TMP/clean/fd-origin.git" rev-list --count main)" = 2
    check "no second commit on the website" test "$(git --git-dir "$TMP/clean/web-origin.git" rev-list --count main)" = 2
    check "says both were already there" said clean "already committed"
}
draft_gone() { ! draft "$1" && [ -d "$TMP/$1/github/v1.2.6" ]; }

case_no_exclude() {
    say "the website's deploy would delete the mirror"
    world noexcl 1 0
    run noexcl 'y\ny\ny\n'
    check "stops" rc_is noexcl 1
    check "names the line to add" said noexcl "--exclude='/updates/'"
    check "uploads nothing" no_event noexcl "gradlew"
    check "website unchanged" website_old noexcl
    show_out noexcl 3
}

case_wrong_apk() {
    say "status.json does not name the built APK"
    world wrongapk
    python3 -I -c 'import json,sys; p=sys.argv[1]; s=json.load(open(p)); s["sha256"]="00"*32; open(p,"w").write(json.dumps(s))' "$TMP/wrongapk/fd/status.json"
    run wrongapk 'y\ny\ny\n'
    check "stops" rc_is wrongapk 1
    check "says the APK differs" said wrongapk "is not the APK status.json names"
    check "uploads nothing" no_event wrongapk "gradlew"
}

case_keep_draft() {
    say "the release is left a draft"
    world draft
    run draft 'n\n'
    check "stops" rc_is draft 1
    check "says how to go on" said draft "--from fdroid"
    check "the release is still a draft" draft draft
    check "F-Droid untouched" no_event draft "publish.sh"
    check "website unchanged" website_old draft
    check "firedown unchanged" firedown_old draft
}

case_manifest_on_draft() {
    say "--from manifest while the release is still a draft"
    world mdraft
    mkdir -p "$TMP/mdraft/github/v1.2.6"
    cp "$TMP/mdraft/fd/$UPD/"* "$TMP/mdraft/github/v1.2.6/"
    : > "$TMP/mdraft/github/v1.2.6.draft"
    run mdraft 'y\n' --from manifest
    check "stops" rc_is mdraft 1
    check "names the URL that does not answer" said mdraft "$GITHUB/app-release.apk answered '404"
    check "website unchanged" website_old mdraft
    check "firedown unchanged" firedown_old mdraft
    check "asked nothing" not_said mdraft "Offer Firedown"
}

case_corrupt_mirror() {
    say "a mirror file arrives damaged"
    world corrupt
    EXTRA=(FAKE_RSYNC_CORRUPT=patch-1205-1206.fdpatch)
    run corrupt 'y\ny\ny\n'
    EXTRA=()
    check "stops" rc_is corrupt 1
    check "names the file" said corrupt "patch-1205-1206.fdpatch on the server is not the file status.json names"
    check "website unchanged" website_old corrupt
}

case_decline_offer() {
    say "no at the last question, then --from manifest"
    world decline
    run decline 'y\ny\nn\n'
    check "stops" rc_is decline 1
    check "says how to go on" said decline "--from manifest"
    check "the mirror is up" test -e "$TMP/decline/server/www/updates/firedown-1206.apk.xz"
    check "website unchanged" website_old decline
    check "firedown unchanged" firedown_old decline
    : > "$TMP/decline/events"
    run decline 'y\n' --from manifest
    check "the resumed run finishes" rc_is decline 0
    check "uploads nothing again" no_event decline "gradlew"
    check "the website has the new status.json" on_origin decline web-origin.git status.json "$TMP/decline/fd/status.json"
}

case_no_deltas() {
    say "a release without .xz or patches (and a deploy with no exclude)"
    world nodelta 0 0
    run nodelta 'y\ny\ny\n'
    check "finishes" rc_is nodelta 0
    check "says there is no mirror" said nodelta "lists no mirror"
    check "never logs in to the server" no_event nodelta "ssh "
    check "the website has the new status.json" on_origin nodelta web-origin.git status.json "$TMP/nodelta/fd/status.json"
}

case_behind() {
    say "firedown's main moved on GitHub"
    world behind
    git clone -q "$TMP/behind/fd-origin.git" "$TMP/behind/other"
    git -C "$TMP/behind/other" commit -q --allow-empty -m "someone else"
    git -C "$TMP/behind/other" push -q origin main
    run behind 'y\ny\ny\n'
    check "stops" rc_is behind 1
    check "says to pull" said behind "behind GitHub"
    check "uploads nothing" no_event behind "gradlew"
}

case_no_agent() {
    say "no ssh agent: one is started, loaded once, stopped"
    world noagent
    EXTRA=(FAKE_AGENT=2)
    run noagent 'y\ny\ny\n'
    EXTRA=()
    check "publishes" rc_is noagent 0
    check "starts an agent before the first git fetch" in_order noagent "ssh-agent start" "ssh-add load" "ssh deploy@203.0.113.5 true"
    check "loads the key once" test "$(grep -c 'ssh-add load' "$TMP/noagent/events")" = 1
    check "stops the agent on exit" event noagent "ssh-agent stop"
}

case_agent_has_key() {
    say "an agent that holds a key is used as it is"
    world haskey
    run haskey 'y\ny\ny\n'
    check "publishes" rc_is haskey 0
    check "starts no agent" no_event haskey "ssh-agent start"
    check "asks for no passphrase" no_event haskey "ssh-add load"
}

case_ssh_down() {
    say "the server refuses the login"
    world sshdown
    EXTRA=(FAKE_SSH_DOWN=1)
    run sshdown 'y\ny\ny\n'
    EXTRA=()
    check "stops" rc_is sshdown 1
    check "names the login" said sshdown "cannot log in to deploy@203.0.113.5"
    check "uploads nothing" no_event sshdown "gradlew"
}

case_no_login() {
    say "no server login anywhere"
    world nologin
    printf 'REMOTE_PATH=/var/www/firedown-website/fdroid\n' > "$TMP/nologin/fdroid/.env"
    run nologin 'y\ny\ny\n'
    check "stops" rc_is nologin 1
    check "says where the login goes" said nologin "set REMOTE_USER and REMOTE_HOST"
    check "tries no server" no_event nologin "ssh "
    check "uploads nothing" no_event nologin "gradlew"
}

case_stale_asset() {
    say "a GitHub file of the right size but other bytes"
    world stale
    EXTRA=(FAKE_STALE_ASSET=patch-1205-1206.fdpatch)
    run stale 'y\ny\ny\n'
    EXTRA=()
    check "stops" rc_is stale 1
    check "names the file" said stale "patch-1205-1206.fdpatch on the release v1.2.6 is not the file status.json names"
    check "F-Droid untouched" no_event stale "publish.sh"
    check "website unchanged" website_old stale
    check "firedown unchanged" firedown_old stale
}

case_no_digest() {
    say "GitHub reports no SHA-256 (an older release): checked by size"
    world nodigest
    EXTRA=(FAKE_GH_NO_DIGEST=1)
    run nodigest 'y\ny\ny\n'
    EXTRA=()
    check "finishes" rc_is nodigest 0
    check "says so" said nodigest "GitHub reports no SHA-256 for app-release.apk"
}

say() { [ "$QUIET" = 1 ] || printf '%s\n' "$*"; }

case_clean
case_no_exclude
case_wrong_apk
case_keep_draft
case_manifest_on_draft
case_corrupt_mirror
case_decline_offer
case_no_deltas
case_behind
case_no_agent
case_agent_has_key
case_ssh_down
case_no_login
case_stale_asset
case_no_digest

# mutant NAME CASE FROM TO: a copy of the script with FROM replaced by TO;
# CASE must fail against it.
mutant() {
    local name=$1 case=$2 from=$3 to=$4 copy=$TMP/mutant-$1.sh caught
    python3 -I - "$REAL_SCRIPT" "$copy" "$from" "$to" <<'EOF' || { fail "mutant $name: could not be planted"; return; }
import sys
src, dst, a, b = sys.argv[1:]
s = open(src).read()
if s.count(a) != 1:
    sys.exit(1)
open(dst, "w").write(s.replace(a, b))
EOF
    caught=$(SCRIPT=$copy; QUIET=1; FAIL=0; "$case"; echo "$FAIL")
    if [ "$caught" -gt 0 ]; then
        pass "mutant $name is caught ($caught check(s) fail)"
    else
        fail "mutant $name is not caught"
    fi
}

say ""
say "planted faults"
mutant "no deploy check" case_no_exclude 'if ! grep -qF -- "$DEPLOY_EXCLUDE" "$deploy"; then' 'if false; then'
mutant "no last look before status.json" case_manifest_on_draft '    say "Every file status.json lists, once more:"' '    need_url() { :; }'
mutant "no checksum on the server" case_corrupt_mirror '[ "$got" = "${F_SHA[$i]}" ] ||' 'true ||'
mutant "rsync without --delete" case_clean 'rsync -e "ssh $SSH_OPTS" -rtpv --chmod=D755,F644 --delete' 'rsync -e "ssh $SSH_OPTS" -rtpv --chmod=D755,F644'
mutant "a login made up when none is set" case_no_login '[ -n "$REMOTE" ] || die "no server login' 'REMOTE=deploy@firedown.app; true || die "no server login'
mutant "no GitHub digest check" case_stale_asset '            die "$name on the release v$NAME is not the file' '            note "$name on the release v$NAME is not the file'
mutant "no agent started" case_no_agent 'if [ "$agent_state" = 2 ] && command -v ssh-agent' 'if false && command -v ssh-agent'

say ""
say "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]

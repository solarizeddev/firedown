#!/usr/bin/env bash
# Publishes a release that `./gradlew assembleRelease` has built, in the order
# phones need it: every file a phone may download is online and checked
# before status.json, which offers the update, goes out.
#
#   github    ./gradlew uploadUpdateRelease (the APK, the .xz, the patches,
#             SHA256SUMS), then publishes the draft release after asking:
#             a draft's files answer 404 to phones.
#   fdroid    the F-Droid repo: runs publish.sh from the firedown-fdroid
#             folder with the APK (it renames it, signs the index, syncs it
#             to the server and asks before syncing). status.json names that
#             copy as the APK's fallback.
#   mirror    the .xz and the patches to <site>/updates/ on the server, the
#             mirror status.json lists after GitHub; files of older releases
#             there are removed. Each file's SHA-256 is checked on the server.
#             Skipped when status.json lists no mirror.
#   manifest  checks that every URL in status.json answers with the right
#             size, asks, then commits and pushes status.json and
#             release-history.json to firedown's main and status.json to the
#             website's main (its push deploys firedown.app).
#   check     waits until https://www.firedown.app/status.json is the new one.
#
# Usage:  scripts/publish-release.sh [--from STEP]
#   --from STEP  start at that step (after fixing what stopped an earlier run;
#                every step can run again).
#
# Folders (siblings of this checkout by default):
#   FDROID_DIR   the F-Droid repo folder with publish.sh  (../firedown-fdroid)
#   WEBSITE_DIR  a clone of firedown-website, on main     (../firedown-website)
# Server: the login is REMOTE_USER and REMOTE_HOST, from the environment or
# FDROID_DIR/.env (the login publish.sh uses); there is no default, so the
# mirror step stops when neither names one.
#   SITE_PATH    the website's folder on the server (/var/www/firedown-website)
#
# The mirror needs the website's deploy to leave /updates/ alone: its rsync
# runs with --delete, so .github/workflows/deploy.yml must exclude it like
# /fdroid/. The script refuses to mirror anything until it does.
#
# Needs gh (signed in), git, curl, python3, ssh and rsync.

set -Euo pipefail

ROOT=${PUBLISH_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
GH_REPO=${GH_REPO:-solarizeddev/firedown}
FDROID_DIR=${FDROID_DIR:-$ROOT/../firedown-fdroid}
WEBSITE_DIR=${WEBSITE_DIR:-$ROOT/../firedown-website}
SITE_PATH=${SITE_PATH:-/var/www/firedown-website}
SITE_URL=${SITE_URL:-https://www.firedown.app}
GRADLEW=${GRADLEW:-$ROOT/gradlew}
RETRY_SLEEP=${RETRY_SLEEP:-5}
CHECK_TIMEOUT=${CHECK_TIMEOUT:-300}
CHECK_INTERVAL=${CHECK_INTERVAL:-10}
STEPS="github fdroid mirror manifest check"
DEPLOY_EXCLUDE="--exclude='/updates/'"

say() { printf '%s\n' "$*"; }
ok() { printf '  ok   %s\n' "$*"; }
note() { printf '  note %s\n' "$*"; }
die() { printf 'STOPPED: %s\n' "$*" >&2; exit 1; }
step() { printf '\n== %s\n' "$*"; }

# ask QUESTION: true on y/yes.
ask() {
    local answer=''
    printf '%s [y/N] ' "$1"
    read -r answer || answer=''
    case $answer in
        y|Y|yes|YES) return 0 ;;
    esac
    return 1
}

usage() { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

FROM=github
while [ $# -gt 0 ]; do
    case $1 in
        --from)
            [ $# -ge 2 ] || usage 2
            FROM=$2
            shift 2
            ;;
        -h|--help) usage 0 ;;
        *) usage 2 ;;
    esac
done
FROM_INDEX=0
i=0
for s in $STEPS; do
    i=$((i + 1))
    if [ "$s" = "$FROM" ]; then
        FROM_INDEX=$i
    fi
done
[ "$FROM_INDEX" -gt 0 ] || die "--from takes one of: $STEPS"

# runs STEP: true when STEP is at or after --from.
runs() {
    local s i=0
    for s in $STEPS; do
        i=$((i + 1))
        if [ "$s" = "$1" ]; then
            [ "$i" -ge "$FROM_INDEX" ]
            return
        fi
    done
    return 1
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

size_of() { wc -c < "$1" | tr -d ' '; }

# url_head URL: "<status> <content-length>" of the last response of a HEAD
# that follows redirects (GitHub sends release files on to its storage).
url_head() {
    curl -sSIL --max-time 60 "$1" 2>/dev/null | tr -d '\r' | awk '
        /^HTTP\// { code = $2; len = "" }
        tolower($1) == "content-length:" { len = $2 }
        END { print code " " len }'
}

# url_ok URL SIZE [TRIES]: the URL answers 200 with that length (a response
# with no length counts by its status alone). A CDN can take a moment after
# an upload, hence the tries.
LAST_HEAD=''
url_ok() {
    local url=$1 size=$2 tries=${3:-1} i=0 code len
    while :; do
        i=$((i + 1))
        LAST_HEAD=$(url_head "$url")
        code=${LAST_HEAD%% *}
        len=${LAST_HEAD#* }
        if [ "$code" = 200 ] && { [ -z "$len" ] || [ "$len" = "$size" ]; }; then
            return 0
        fi
        if [ "$i" -ge "$tries" ]; then
            return 1
        fi
        sleep "$RETRY_SLEEP"
    done
}

need_url() {
    if url_ok "$1" "$2" "${3:-1}"; then
        ok "$1"
    else
        die "$1 answered '${LAST_HEAD:-nothing}', expected 200 with $2 bytes"
    fi
}

# ---- what is being published ------------------------------------------------

[ -f "$ROOT/status.json" ] || die "no status.json in $ROOT: build the release first (./gradlew assembleRelease)"
GRADLE_CODE=$(awk '$1 == "versionCode" { print $2; exit }' "$ROOT/app/build.gradle")
GRADLE_NAME=$(awk '$1 == "versionName" { gsub(/"/, "", $2); print $2; exit }' "$ROOT/app/build.gradle")

MANIFEST=$(python3 -I - "$ROOT/status.json" <<'EOF'
import json, sys
s = json.load(open(sys.argv[1], encoding="utf-8"))
name = str(s["versionName"])
if name.startswith("Firedown "):
    name = name[len("Firedown "):]
print("code", s["versionCode"])
print("name", name)
print("apk", s["sha256"], s["updateUrl"], *s.get("updateUrlFallbacks", []))
for block in s.get("patches", []) + ([s["compressed"]] if s.get("compressed") else []):
    print("file", block["sha256"], block["size"], *block["urls"])
EOF
) || die "status.json could not be read"

CODE=''
NAME=''
APK_SHA=''
APK_GITHUB=''
APK_FALLBACKS=''
F_SHA=()
F_SIZE=()
F_GITHUB=()
F_MIRROR=()
while read -r kind a b c rest; do
    case $kind in
        code) CODE=$a ;;
        name) NAME=$a ;;
        apk)
            APK_SHA=$a
            APK_GITHUB=$b
            APK_FALLBACKS="$c${rest:+ $rest}"
            ;;
        file)
            F_SHA+=("$a")
            F_SIZE+=("$b")
            F_GITHUB+=("$c")
            F_MIRROR+=("${rest%% *}")
            ;;
    esac
done <<EOF
$MANIFEST
EOF

[ "$CODE" = "$GRADLE_CODE" ] || die "status.json is for versionCode $CODE, app/build.gradle says $GRADLE_CODE"
[ "$NAME" = "$GRADLE_NAME" ] || die "status.json is for $NAME, app/build.gradle says $GRADLE_NAME"
UPD=$ROOT/app/build/outputs/update/v$NAME
APK=$UPD/app-release.apk
RELEASE_BASE="https://github.com/$GH_REPO/releases/download/v$NAME"
MIRROR_BASE="$SITE_URL/updates"

say "Publishing Firedown $NAME ($CODE)"

step "Preflight"
[ -f "$APK" ] || die "no $APK: build the release first (releaseStatus writes that folder)"
[ "$(sha256_of "$APK")" = "$APK_SHA" ] || die "$APK is not the APK status.json names (SHA-256 differs): build again"
APK_SIZE=$(size_of "$APK")
[ "$APK_GITHUB" = "$RELEASE_BASE/app-release.apk" ] || die "status.json's APK URL is $APK_GITHUB, expected $RELEASE_BASE/app-release.apk"
ok "APK $APK_SIZE bytes, SHA-256 matches status.json"

MIRROR_NEEDED=0
i=0
while [ "$i" -lt "${#F_SHA[@]}" ]; do
    file=${F_GITHUB[$i]##*/}
    [ "${F_GITHUB[$i]}" = "$RELEASE_BASE/$file" ] || die "status.json lists ${F_GITHUB[$i]}, expected it under $RELEASE_BASE/"
    [ -f "$UPD/$file" ] || die "status.json lists $file, which is not in $UPD"
    [ "$(sha256_of "$UPD/$file")" = "${F_SHA[$i]}" ] || die "$UPD/$file is not the file status.json names (SHA-256 differs)"
    [ "$(size_of "$UPD/$file")" = "${F_SIZE[$i]}" ] || die "$UPD/$file is not ${F_SIZE[$i]} bytes as status.json says"
    if [ -n "${F_MIRROR[$i]}" ]; then
        [ "${F_MIRROR[$i]}" = "$MIRROR_BASE/$file" ] || die "status.json's mirror for $file is ${F_MIRROR[$i]}, expected $MIRROR_BASE/$file (SITE_URL)"
        MIRROR_NEEDED=1
    fi
    i=$((i + 1))
done
ok "${#F_SHA[@]} update file(s) match status.json"

for tool in git curl python3; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool is not installed"
done
if runs github; then
    command -v gh >/dev/null 2>&1 || die "gh is not installed (https://cli.github.com)"
    gh auth status >/dev/null 2>&1 || die "gh is not signed in: gh auth login"
    [ -x "$GRADLEW" ] || die "no $GRADLEW"
fi
if runs fdroid; then
    [ -x "$FDROID_DIR/publish.sh" ] || die "no publish.sh in $FDROID_DIR (set FDROID_DIR)"
fi

if [ -z "${REMOTE_USER:-}" ] || [ -z "${REMOTE_HOST:-}" ]; then
    if [ -f "$FDROID_DIR/.env" ]; then
        # shellcheck disable=SC1091 # the folder's own settings, read like publish.sh reads them
        env_login=$(set +u; set -a; . "$FDROID_DIR/.env" >/dev/null 2>&1; printf '%s %s' "${REMOTE_USER:-}" "${REMOTE_HOST:-}")
        REMOTE_USER=${REMOTE_USER:-${env_login%% *}}
        REMOTE_HOST=${REMOTE_HOST:-${env_login#* }}
    fi
fi
REMOTE=
if [ -n "${REMOTE_USER:-}" ] && [ -n "${REMOTE_HOST:-}" ]; then
    REMOTE="$REMOTE_USER@$REMOTE_HOST"
fi

if runs mirror && [ "$MIRROR_NEEDED" = 1 ]; then
    [ -n "$REMOTE" ] || die "no server login: set REMOTE_USER and REMOTE_HOST in $FDROID_DIR/.env (publish.sh's login) or in the environment"
    command -v rsync >/dev/null 2>&1 || die "rsync is not installed"
    ssh -o BatchMode=yes "$REMOTE" true </dev/null || die "cannot log in to $REMOTE with ssh (REMOTE_USER, REMOTE_HOST)"
    ok "ssh $REMOTE"
fi

if runs manifest; then
    [ "$(git -C "$ROOT" symbolic-ref --short -q HEAD)" = main ] || die "$ROOT is not on main"
    git -C "$ROOT" fetch --quiet origin main || die "git fetch failed in $ROOT"
    [ "$(git -C "$ROOT" rev-list --count HEAD..FETCH_HEAD)" = 0 ] || die "$ROOT's main is behind GitHub: pull first"
    ok "firedown main is up to date"

    [ -d "$WEBSITE_DIR/.git" ] || die "no firedown-website clone at $WEBSITE_DIR (set WEBSITE_DIR)"
    [ "$(git -C "$WEBSITE_DIR" symbolic-ref --short -q HEAD)" = main ] || die "$WEBSITE_DIR is not on main"
    git -C "$WEBSITE_DIR" diff --quiet HEAD || die "$WEBSITE_DIR has uncommitted changes"
    git -C "$WEBSITE_DIR" pull --quiet --ff-only origin main || die "could not update $WEBSITE_DIR from GitHub"
    ok "firedown-website main is up to date"
fi
if [ "$MIRROR_NEEDED" = 1 ] && { runs mirror || runs manifest; }; then
    deploy=$WEBSITE_DIR/.github/workflows/deploy.yml
    [ -f "$deploy" ] || die "no $deploy (set WEBSITE_DIR)"
    if ! grep -qF -- "$DEPLOY_EXCLUDE" "$deploy"; then
        die "the website's deploy would delete the mirror: its rsync runs with --delete. Add
    --exclude='/updates/' \\
next to --exclude='/fdroid/' in firedown-website/.github/workflows/deploy.yml, push it, then run this again."
    fi
    ok "the website's deploy leaves /updates/ alone"
fi

# ---- github ------------------------------------------------------------------

if runs github; then
    step "GitHub release v$NAME"
    (cd "$ROOT" && "$GRADLEW" uploadUpdateRelease) || die "./gradlew uploadUpdateRelease failed"
    draft=$(gh release view "v$NAME" --repo "$GH_REPO" --json isDraft --jq .isDraft) || die "gh cannot see the release v$NAME"
    if [ "$draft" = true ]; then
        if ask "The release v$NAME is a draft, whose files phones cannot download. Publish it now?"; then
            gh release edit "v$NAME" --repo "$GH_REPO" --draft=false >/dev/null || die "gh could not publish v$NAME"
            ok "published v$NAME"
        else
            die "v$NAME is still a draft. Publish it on GitHub, then run: $0 --from fdroid"
        fi
    fi
    need_url "$APK_GITHUB" "$APK_SIZE" 6
    i=0
    while [ "$i" -lt "${#F_SHA[@]}" ]; do
        need_url "${F_GITHUB[$i]}" "${F_SIZE[$i]}" 6
        i=$((i + 1))
    done
fi

# ---- fdroid ------------------------------------------------------------------

if runs fdroid; then
    step "F-Droid repo"
    (cd "$FDROID_DIR" && ./publish.sh "$APK") || die "publish.sh failed; after fixing it run: $0 --from fdroid"
    for url in $APK_FALLBACKS; do
        need_url "$url" "$APK_SIZE" 6
    done
fi

# ---- mirror ------------------------------------------------------------------

if runs mirror; then
    step "Update mirror ($MIRROR_BASE)"
    if [ "$MIRROR_NEEDED" = 0 ]; then
        note "status.json lists no mirror (no .xz or patches); nothing to copy"
    else
        dest=$SITE_PATH/updates
        # shellcheck disable=SC2029 # the path is meant to expand here
        ssh "$REMOTE" "mkdir -p '$dest'" </dev/null || die "could not make $dest on $REMOTE"
        rsync -rtpv --chmod=D755,F644 --delete \
            --include='*.xz' --include='*.fdpatch' --exclude='*' \
            "$UPD/" "$REMOTE:$dest/" </dev/null || die "rsync to $REMOTE:$dest failed"
        names=''
        i=0
        while [ "$i" -lt "${#F_SHA[@]}" ]; do
            names="$names ${F_GITHUB[$i]##*/}"
            i=$((i + 1))
        done
        # shellcheck disable=SC2029 # the path is meant to expand here
        sums=$(ssh "$REMOTE" "cd '$dest' && sha256sum$names" </dev/null) || die "could not read the mirror's checksums on $REMOTE"
        i=0
        while [ "$i" -lt "${#F_SHA[@]}" ]; do
            file=${F_GITHUB[$i]##*/}
            got=$(printf '%s\n' "$sums" | awk -v f="$file" '$2 == f { print $1 }')
            [ "$got" = "${F_SHA[$i]}" ] || die "$dest/$file on the server is not the file status.json names"
            need_url "${F_MIRROR[$i]}" "${F_SIZE[$i]}" 6
            i=$((i + 1))
        done
    fi
fi

# ---- manifest ----------------------------------------------------------------

if runs manifest; then
    step "status.json"
    say "Every file status.json lists, once more:"
    need_url "$APK_GITHUB" "$APK_SIZE"
    for url in $APK_FALLBACKS; do
        need_url "$url" "$APK_SIZE"
    done
    i=0
    while [ "$i" -lt "${#F_SHA[@]}" ]; do
        need_url "${F_GITHUB[$i]}" "${F_SIZE[$i]}"
        if [ -n "${F_MIRROR[$i]}" ]; then
            need_url "${F_MIRROR[$i]}" "${F_SIZE[$i]}"
        fi
        i=$((i + 1))
    done
    ask "Offer Firedown $NAME to every phone now?" || die "status.json not published. Run again with: $0 --from manifest"

    if git -C "$ROOT" diff --quiet HEAD -- status.json release-history.json; then
        note "firedown: status.json and release-history.json already committed"
    else
        git -C "$ROOT" commit --quiet -m "Release $NAME: status.json and release history" \
            -- status.json release-history.json || die "git commit failed in $ROOT"
    fi
    git -C "$ROOT" push --quiet origin main || die "git push failed in $ROOT"
    ok "firedown main has the new status.json"

    cp "$ROOT/status.json" "$WEBSITE_DIR/status.json" || die "could not copy status.json into $WEBSITE_DIR"
    if git -C "$WEBSITE_DIR" diff --quiet HEAD -- status.json; then
        note "firedown-website: status.json already the new one"
    else
        git -C "$WEBSITE_DIR" commit --quiet -m "Firedown $NAME" -- status.json || die "git commit failed in $WEBSITE_DIR"
    fi
    git -C "$WEBSITE_DIR" push --quiet origin main || die "git push failed in $WEBSITE_DIR"
    ok "firedown-website main has the new status.json (its deploy is running)"
fi

# ---- check -------------------------------------------------------------------

# served_code URL: the versionCode a status.json URL serves now.
served_code() {
    curl -fsS --max-time 30 "$1" 2>/dev/null | python3 -I -c 'import json, sys; print(json.load(sys.stdin)["versionCode"])' 2>/dev/null
}

if runs check; then
    step "firedown.app"
    waited=0
    while [ "$(served_code "$SITE_URL/status.json?release-check=$CODE-$waited")" != "$CODE" ]; do
        if [ "$waited" -ge "$CHECK_TIMEOUT" ]; then
            die "$SITE_URL/status.json still is not $CODE after ${CHECK_TIMEOUT}s: look at the website's deploy on GitHub (Actions)"
        fi
        sleep "$CHECK_INTERVAL"
        waited=$((waited + CHECK_INTERVAL))
    done
    ok "$SITE_URL/status.json is $CODE"
    if [ "$(served_code "$SITE_URL/status.json")" != "$CODE" ]; then
        note "without a query string it is still the old one: a cache in front of the site; it clears by itself, or purge status.json"
    fi
fi

say ""
say "Done: Firedown $NAME is published. Phones see it at their next daily check."

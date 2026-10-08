#!/usr/bin/env bash
# scripts/fetch-prebuilts.sh — get the two compiled dependencies this app links
# but does NOT build: the FFmpeg .so set (firedown-ffmpeg) and the patched
# GeckoView AAR (firedown-geckoview). Both are built on one machine, published
# as GitHub Release assets of their own repos, PINNED in gradle.properties and
# fetched here, so a fresh clone on any machine builds without either toolchain.
#
#   scripts/fetch-prebuilts.sh            # = ensure: fetch whatever is missing (what
#                                         #   settings.gradle runs on every sync)
#   scripts/fetch-prebuilts.sh ffmpeg     # just FFmpeg   → external/ffmpeg/
#   scripts/fetch-prebuilts.sh geckoview  # just GeckoView → ~/.m2/repository/org/mozilla/geckoview/
#   scripts/fetch-prebuilts.sh all --force   # re-download, replacing what is there
#
# Pins (gradle.properties; settings.gradle hands them in via the env):
#   firedown.ffmpegRelease     = tag of a firedown-ffmpeg release     (v9.0.2-1)
#   firedown.geckoviewVersion  = GeckoView Maven version = release tag geckoview-<it>
#
# Gates — the script is a no-op when the artifact is in place, so the gradle
# hook costs nothing in the steady state, and it NEVER clobbers a local build:
#   FFmpeg:    external/ffmpeg/lib exists → done. If its version.txt names a
#              different release (or a local scripts/sync-ffmpeg.sh copy), it is
#              LEFT ALONE with a warning: that is a developer's own build under
#              test. --force replaces it.
#   GeckoView: ~/.m2/…/geckoview/*/<version>/*.aar exists → done (a local
#              ./build.sh build lands in the exact same place under the same
#              version, so a machine that builds GeckoView never fetches it).
#
# Transport: `gh release download` (the repos are private; gh carries the login
# — `gh auth login` once per machine), falling back to the REST API with
# GITHUB_TOKEN when gh is absent (CI). Every asset is sha256-verified against
# the .sha256 the publish scripts upload beside it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OWNER="${FIREDOWN_GITHUB_OWNER:-solarizeddev}"
FFMPEG_REPO="firedown-ffmpeg"
GECKO_REPO="firedown-geckoview"
M2_GV="${HOME}/.m2/repository/org/mozilla/geckoview"

MODE="ensure"
FORCE=0
for arg in "$@"; do
  case "$arg" in
    ensure|ffmpeg|geckoview|all) MODE="$arg" ;;
    --force) FORCE=1 ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$arg' (see --help)" >&2; exit 2 ;;
  esac
done

# Pins: from the environment (settings.gradle) else gradle.properties.
prop() { sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$ROOT/gradle.properties" | head -1 | tr -d '[:space:]'; }
FFMPEG_TAG="${FIREDOWN_FFMPEG_RELEASE:-$(prop firedown.ffmpegRelease)}"
GECKO_VER="${FIREDOWN_GECKOVIEW_VERSION:-$(prop firedown.geckoviewVersion)}"
[[ -n "$FFMPEG_TAG" ]] || { echo "ERROR: firedown.ffmpegRelease is not set in gradle.properties" >&2; exit 1; }
[[ -n "$GECKO_VER" ]]  || { echo "ERROR: firedown.geckoviewVersion is not set in gradle.properties" >&2; exit 1; }

log() { echo "[prebuilts] $*"; }

# download <repo> <tag> <asset-name> <dest-dir>  — asset + its .sha256, verified.
download() {
  local repo="$1" tag="$2" asset="$3" dest="$4"
  mkdir -p "$dest"
  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    log "downloading $asset from $OWNER/$repo@$tag (gh)"
    if ! gh release download "$tag" -R "$OWNER/$repo" -p "$asset" -p "$asset.sha256" -D "$dest" --clobber; then
      echo "ERROR: gh could not download release $tag of $OWNER/$repo." >&2
      echo "       Does the release exist? (gh release list -R $OWNER/$repo)" >&2
      echo "       It is created on the machine that BUILT the library — see docs/NEW-MACHINE.md." >&2
      return 1
    fi
  elif [[ -n "${GITHUB_TOKEN:-}" ]]; then
    log "downloading $asset from $OWNER/$repo@$tag (REST API, GITHUB_TOKEN)"
    local api="https://api.github.com/repos/$OWNER/$repo/releases/tags/$tag"
    local json
    json="$(curl -fsSL -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" "$api")" \
      || { echo "ERROR: release $tag not found on $OWNER/$repo (or token lacks access)" >&2; return 1; }
    local name id
    for name in "$asset" "$asset.sha256"; do
      id="$(printf '%s' "$json" | python3 -I -c '
import json, sys
want = sys.argv[1]
for a in json.load(sys.stdin).get("assets", []):
    if a.get("name") == want:
        print(a["id"]); break
' "$name")"
      [[ -n "$id" ]] || { echo "ERROR: asset $name missing from release $tag" >&2; return 1; }
      curl -fsSL -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/octet-stream" \
           -o "$dest/$name" "https://api.github.com/repos/$OWNER/$repo/releases/assets/$id" \
        || { echo "ERROR: download of $name failed" >&2; return 1; }
    done
  else
    echo "ERROR: no way to reach the private release of $OWNER/$repo." >&2
    echo "       Either install the GitHub CLI and run \`gh auth login\` (once per machine)," >&2
    echo "       or export GITHUB_TOKEN with read access to $OWNER/$repo." >&2
    return 1
  fi
  # Verify. The .sha256 is "<hex>  <name>" as sha256sum writes it.
  local want have
  want="$(awk '{print $1}' "$dest/$asset.sha256")"
  have="$(sha256sum "$dest/$asset" | awk '{print $1}')"
  if [[ "$want" != "$have" ]]; then
    echo "ERROR: sha256 mismatch for $asset (want $want, got $have) — refusing to install" >&2
    rm -f "$dest/$asset"
    return 1
  fi
  log "verified sha256 $have"
}

fetch_ffmpeg() {
  local dest="$ROOT/external/ffmpeg"
  local asset="firedown-ffmpeg-$FFMPEG_TAG-android.tar.gz"
  if [[ -d "$dest/lib" && $FORCE -eq 0 ]]; then
    if grep -qs "^firedown-ffmpeg release $FFMPEG_TAG " "$dest/version.txt"; then
      return 0   # pinned release in place — the steady state, silent
    fi
    log "WARNING: external/ffmpeg holds '$(head -1 "$dest/version.txt" 2>/dev/null || echo "an unversioned copy")'"
    log "         but gradle.properties pins firedown.ffmpegRelease=$FFMPEG_TAG."
    log "         Leaving it (a local sync-ffmpeg.sh build under test?). To replace it:"
    log "           scripts/fetch-prebuilts.sh ffmpeg --force"
    return 0
  fi
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  download "$FFMPEG_REPO" "$FFMPEG_TAG" "$asset" "$tmp"
  # Replace atomically-enough: unpack beside, then swap, so a failed extract
  # can't leave a half-populated lib/ that CMake would link against.
  rm -rf "$dest.new"
  mkdir -p "$dest.new"
  tar -xzf "$tmp/$asset" -C "$dest.new"
  [[ -d "$dest.new/lib" && -d "$dest.new/include" ]] || { echo "ERROR: $asset has no lib/ + include/" >&2; rm -rf "$dest.new"; return 1; }
  rm -rf "$dest"
  mv "$dest.new" "$dest"
  log "installed FFmpeg $FFMPEG_TAG → external/ffmpeg ($(ls "$dest/lib" | tr '\n' ' '))"
}

fetch_geckoview() {
  local asset="geckoview-$GECKO_VER-m2.tar.gz"
  if [[ $FORCE -eq 0 ]] && compgen -G "$M2_GV/*/$GECKO_VER/*-$GECKO_VER.aar" >/dev/null; then
    return 0   # published locally (own build) or fetched before — silent
  fi
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  download "$GECKO_REPO" "geckoview-$GECKO_VER" "$asset" "$tmp"
  mkdir -p "$M2_GV"
  # The tarball's paths are <artifactId>/<version>/… relative to this dir (see
  # firedown-geckoview scripts/06-publish-release.sh); --force replaces files
  # in place, which is exactly what a re-fetch of the same coordinate wants.
  tar -xzf "$tmp/$asset" -C "$M2_GV"
  local aar
  aar="$(compgen -G "$M2_GV/*/$GECKO_VER/*-$GECKO_VER.aar" | head -1)"
  [[ -n "$aar" ]] || { echo "ERROR: $asset did not contain */$GECKO_VER/*.aar" >&2; return 1; }
  if ! grep -q '<packaging>aar</packaging>' "${aar%.aar}.pom"; then
    echo "ERROR: fetched POM is not packaging=aar — gradle would ignore the AAR" >&2
    return 1
  fi
  log "installed GeckoView $GECKO_VER → $aar"
}

case "$MODE" in
  ffmpeg)    fetch_ffmpeg ;;
  geckoview) fetch_geckoview ;;
  ensure|all) fetch_ffmpeg; fetch_geckoview ;;
esac

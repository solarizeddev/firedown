#!/bin/sh
# setup-playwright.sh — installs Playwright and its Chromium for the browser
# harnesses (frame-meta-harness.mjs, bridge-dom-harness.mjs, …), without root and
# without touching the repo or npm's global prefix. Everything goes in ONE
# folder, ${XDG_CACHE_HOME:-~/.cache}/firedown-playwright:
#   node_modules/   the npm package
#   browsers/       the browsers (Playwright's own cache is not used)
# scripts/load-playwright.mjs looks there. Run it again to update or repair
# (a browser already unpacked is skipped); delete the folder to remove
# everything.
#
#   sh scripts/setup-playwright.sh            Chromium (all most scripts need)
#   sh scripts/setup-playwright.sh firefox    and Firefox, for --browser firefox
#
# The browsers are NOT installed by `playwright install`: on Arch it
# downloaded Chromium and then hung at 100 % for over an hour while unpacking
# it, and on the next run deleted the half-unpacked copy and hung again.
# Playwright is only asked WHAT it needs (`install --dry-run`: each build's
# folder and download URLs); curl downloads it and unzip (or bsdtar)
# unpacks it, then Playwright's own completion marker is written. The
# browsers live beside the package rather than in ~/.cache/ms-playwright, so
# another Playwright on the machine can't garbage-collect them.
#
# PLAYWRIGHT_VERSION picks another Playwright (default: the one the scripts
# were last verified with); PLAYWRIGHT_DOWNLOAD_HOST, Playwright's own mirror
# setting, is honoured.
#
# On a Linux distribution Playwright builds nothing for (Arch, Fedora…) the
# plan names its Ubuntu build, which usually runs; if it will not start, point
# the scripts at the system Chromium instead:
#   FIREDOWN_CHROMIUM=/usr/bin/chromium node scripts/frame-meta-harness.mjs
set -eu

VERSION="${PLAYWRIGHT_VERSION:-1.56.1}"
DIR="${XDG_CACHE_HOME:-$HOME/.cache}/firedown-playwright"
BROWSERS="$DIR/browsers"
HERE=$(cd "$(dirname "$0")" && pwd)
TAB=$(printf '\t')

fail() {
  echo "setup-playwright: $*" >&2
  exit 1
}

command -v node >/dev/null 2>&1 || fail "node is needed (Node.js 18 or newer)"
command -v npm >/dev/null 2>&1 || fail "npm is needed (it comes with Node.js)"
command -v curl >/dev/null 2>&1 || fail "curl is needed to download the browsers"
MAJOR=$(node -p 'process.versions.node.split(".")[0]')
[ "$MAJOR" -ge 18 ] || fail "Node.js $(node -v) is too old, Playwright needs 18 or newer"
if command -v unzip >/dev/null 2>&1; then
  UNZIP=unzip
elif command -v bsdtar >/dev/null 2>&1; then
  UNZIP=bsdtar
else
  fail "unzip or bsdtar is needed to unpack the browsers (on Arch: pacman -S unzip)"
fi

mkdir -p "$DIR" "$BROWSERS"
echo "Installing playwright@$VERSION into $DIR"
npm install --prefix "$DIR" --no-audit --no-fund --loglevel=error "playwright@$VERSION"
PW="$DIR/node_modules/.bin/playwright"

# What Playwright would install, one line per build: folder <TAB> URLs (the
# download URL, then its fallbacks). ffmpeg is listed under every browser;
# keep its first line.
PLAN="$DIR/plan.txt"
for browser in chromium "$@"; do
  PLAYWRIGHT_BROWSERS_PATH="$BROWSERS" "$PW" install --dry-run "$browser"
done | awk -v tab="$TAB" '
  /Install location:/ {
    if (loc != "" && urls != "") print loc tab urls
    loc = $0
    sub(/^[ \t]*Install location:[ \t]*/, "", loc)
    urls = ""
    next
  }
  /Download (url|fallback [0-9]+):/ {
    urls = (urls == "" ? $NF : urls " " $NF)
    next
  }
  END { if (loc != "" && urls != "") print loc tab urls }
' | awk -F "$TAB" '!seen[$1]++' > "$PLAN"
[ -s "$PLAN" ] || fail "playwright install --dry-run named nothing to install"

unpack() {
  if [ "$UNZIP" = unzip ]; then
    unzip -q -o "$1" -d "$2" < /dev/null
  else
    bsdtar -xf "$1" -C "$2" < /dev/null
  fi
}

while IFS="$TAB" read -r loc urls; do
  name=$(basename "$loc")
  if [ -f "$loc/INSTALLATION_COMPLETE" ]; then
    echo "$name: already installed"
    continue
  fi
  part="$loc.zip.part"
  got=""
  for url in $urls; do
    echo "$name: downloading $url"
    if curl -fL --retry 2 --connect-timeout 30 --progress-bar -o "$part" "$url" < /dev/null; then
      got=1
      break
    fi
  done
  [ -n "$got" ] || fail "could not download $name"
  echo "$name: unpacking"
  rm -rf "$loc" "$loc.unpack"
  mkdir -p "$loc.unpack"
  unpack "$part" "$loc.unpack" || fail "could not unpack $name ($part)"
  # Playwright's own marker: `playwright install` treats the build as done.
  : > "$loc.unpack/INSTALLATION_COMPLETE"
  mv "$loc.unpack" "$loc"
  rm -f "$part"
done < "$PLAN"
rm -f "$PLAN"

echo
echo "Starting Chromium once to check it:"
node --input-type=module -e '
const { pathToFileURL } = await import("node:url");
const m = await import(pathToFileURL(process.argv[1]).href);
const pw = await m.loadPlaywright();
if (!pw) {
  console.log("  playwright did not load");
  process.exit(1);
}
let failed = 0;
for (const [label, options] of [["headless shell", {}], ["full Chromium", { channel: "chromium" }]]) {
  try {
    const browser = await pw.chromium.launch(options);
    console.log(`  ${label}: ${browser.version()}, ok`);
    await browser.close();
  } catch (e) {
    failed++;
    const lines = String(e?.message || e).split("\n").filter((l) => l.trim() && !/^\s*[║╔╚═]/.test(l));
    console.log(`  ${label}: will not start`);
    for (const l of lines.slice(0, 6)) console.log(`    ${l}`);
  }
}
console.log(`  (${m.playwrightSource()})`);
process.exit(failed === 2 ? 1 : 0);
' "$HERE/load-playwright.mjs" || {
  echo
  echo "Chromium will not start here. Use the system one instead:"
  echo "  FIREDOWN_CHROMIUM=/usr/bin/chromium node scripts/frame-meta-harness.mjs"
  exit 1
}

echo
echo "Done. Try: node scripts/frame-meta-harness.mjs"

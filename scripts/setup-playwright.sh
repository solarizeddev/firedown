#!/bin/sh
# setup-playwright.sh — installs Playwright and its Chromium for the browser
# harnesses and the site audit (scripts/site-audit.mjs), without root and
# without touching the repo or npm's global prefix:
#   the npm package → ${XDG_CACHE_HOME:-~/.cache}/firedown-playwright
#   the browsers    → Playwright's own cache (~/.cache/ms-playwright)
# scripts/load-playwright.mjs looks there. Run it again to update or repair;
# delete both folders to remove everything.
#
#   sh scripts/setup-playwright.sh            Chromium (all most scripts need)
#   sh scripts/setup-playwright.sh firefox    and Firefox, for --browser firefox
#
# PLAYWRIGHT_VERSION picks another Playwright (default: the one the scripts
# were last verified with).
#
# On a Linux distribution Playwright builds nothing for (Arch, Fedora…) it
# downloads its Ubuntu build, which usually runs; if it will not start, point
# the scripts at the system Chromium instead:
#   FIREDOWN_CHROMIUM=/usr/bin/chromium node scripts/site-audit.mjs …
#   (or --chromium /usr/bin/chromium on the site audit)
set -eu

VERSION="${PLAYWRIGHT_VERSION:-1.56.1}"
DIR="${XDG_CACHE_HOME:-$HOME/.cache}/firedown-playwright"

if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
  echo "setup-playwright: node and npm are needed (Node.js 18 or newer)" >&2
  exit 1
fi
MAJOR=$(node -p 'process.versions.node.split(".")[0]')
if [ "$MAJOR" -lt 18 ]; then
  echo "setup-playwright: Node.js $(node -v) is too old, Playwright needs 18 or newer" >&2
  exit 1
fi

mkdir -p "$DIR"
echo "Installing playwright@$VERSION into $DIR"
npm install --prefix "$DIR" --no-audit --no-fund --loglevel=error "playwright@$VERSION"

echo "Installing browsers: chromium $*"
"$DIR/node_modules/.bin/playwright" install chromium "$@"

echo
echo "Done. Try: node scripts/site-audit/selftest.mjs"

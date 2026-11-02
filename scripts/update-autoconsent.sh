#!/bin/sh
# Refreshes the cookie-banner extension's copy of DuckDuckGo's autoconsent
# (MPL-2.0) from npm: the package's Firefox add-on content script, the library
# module and the rules, VERBATIM — nothing in them is edited here.
#
# usage: sh scripts/update-autoconsent.sh [version]   (default: latest)
#
# Afterwards: bump app/src/main/assets/consent/manifest.json "version" (the
# ensureBuiltIn version-cache trap), then run
#   node scripts/consent-smoke.mjs && node scripts/consent-harness.mjs
# The rules change almost daily upstream; refreshing once per release is
# enough.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
DEST="$ROOT/app/src/main/assets/consent"
VERSION=${1:-latest}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

cd "$WORK"
TARBALL=$(npm pack -q "@duckduckgo/autoconsent@$VERSION")
mkdir pkg
tar xzf "$TARBALL" -C pkg
P="$WORK/pkg/package"
GOT=$(node -e "console.log(require('$P/package.json').version)")

cp "$P/dist/addon-firefox/content.bundle.js" "$DEST/js/autoconsent-content.js"
cp "$P/dist/autoconsent.esm.js" "$DEST/js/autoconsent.esm.js"
cp "$P/rules/compact-rules.json" "$DEST/rules/compact-rules.json"
cp "$P/LICENSE" "$DEST/LICENSE"
echo "$GOT" > "$DEST/AUTOCONSENT_VERSION"

# The background module needs these two names from the library.
grep -q "evalSnippets" "$DEST/js/autoconsent.esm.js" || { echo "evalSnippets is gone from the library" >&2; exit 1; }
grep -q "filterCompactRules" "$DEST/js/autoconsent.esm.js" || { echo "filterCompactRules is gone from the library" >&2; exit 1; }

echo "autoconsent $GOT copied into $DEST"
echo "Now bump the version in $DEST/manifest.json and run the consent smoke and harness."

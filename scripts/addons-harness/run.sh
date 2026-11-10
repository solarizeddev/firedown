#!/bin/sh
# Add-ons harness: the REAL pure add-on classes (AddonPolicy, AmoCatalog, AddonUpdateReviews,
# XpiManifest, AddonPermissions — copied from app/src, never re-typed) on a
# plain JVM with org.json from Maven Central (sha1-checked, cached). Then
# MUTANTS that must each fail it: the built-in ID check without our domain,
# the catalog keeping themes, an http download accepted, the site list not
# capped, comments in a manifest not read, the signature files ignored,
# uBlock Origin refused as built in, the blocker rule without its all-sites
# check, https alone taken for all sites, any add-on's "(off)" believed, a
# running blocker hiding another's "off", the user's shield switch ignored,
# any add-on that can block requests counted as an ad blocker, the known-ID
# list read case-sensitively, the shield switch offered to every add-on,
# uBlock's per-tab title echoed under its name, and the update reviews: a
# newer version not arming the mark again, a declined question still
# nagging, an old build's mark notified twice or re-armed, a waiting
# add-on rechecked (downloaded) every day, and Gecko's "no update" trusted
# offline (a failed check reads the same), and the site switches: only the
# declared sites listed (a site granted at run time has no switch), the
# all-sites switch missing, one switch per pattern, host "*" under any
# scheme taken for all sites, an IPv6 host cut at its first colon.
#
# usage: sh scripts/addons-harness/run.sh [mozilla-firefox checkout]
#   With a Firefox source checkout, Firefox's own AMO fixtures are parsed too
#   (android-components/.../feature/addons/src/test/resources).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
PKG="app/src/main/java/com/solarized/firedown/addons"
CLASSES="AddonPolicy AmoCatalog XpiManifest AddonPermissions AddonUpdateReviews"
BUILD="$HERE/build"
FIREFOX_SRC="$1"
CACHE=${FIREDOWN_HARNESS_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/firedown-harness/maven}
mkdir -p "$CACHE"

# group artifact version -> prints the cached jar's path
fetch() {
  g=$(echo "$1" | tr . /)
  f="$2-$3.jar"
  u="https://repo1.maven.org/maven2/$g/$2/$3/$f"
  if [ ! -f "$CACHE/$f" ]; then
    n=0
    until curl -fsS -o "$CACHE/$f.part" "$u"; do
      n=$((n + 1)); [ $n -ge 5 ] && { echo "download failed: $u" >&2; exit 1; }
      sleep $((n * 3))
    done
    want=""
    n=0
    until want=$(curl -fsS "$u.sha1"); do
      n=$((n + 1)); [ $n -ge 5 ] && { echo "download failed: $u.sha1" >&2; exit 1; }
      sleep $((n * 3))
    done
    want=$(echo "$want" | cut -c1-40)
    got=$(sha1sum "$CACHE/$f.part" | cut -c1-40)
    if [ "$want" != "$got" ]; then
      echo "sha1 mismatch for $f: $got, Maven says $want" >&2
      exit 1
    fi
    mv "$CACHE/$f.part" "$CACHE/$f"
  fi
  echo "$CACHE/$f"
}

JSON_JAR=$(fetch org.json json 20250517)

# run_one NAME CLASS SEDEXPR: CLASS gets SEDEXPR, the others are copied.
run_one() {
  name="$1"
  target="$2"
  sedexpr="$3"
  out="$BUILD/$name"
  gen="$out/gen/com/solarized/firedown/addons"
  rm -rf "$out"
  mkdir -p "$gen" "$out/classes"
  for c in $CLASSES; do
    cp "$ROOT/$PKG/$c.java" "$gen/"
  done
  if [ -n "$sedexpr" ]; then
    sed "$sedexpr" "$ROOT/$PKG/$target.java" > "$gen/$target.java"
    if cmp -s "$ROOT/$PKG/$target.java" "$gen/$target.java"; then
      echo "mutant $name did not change the source" >&2
      exit 2
    fi
  fi
  javac -nowarn -encoding UTF-8 -cp "$JSON_JAR" -d "$out/classes" \
    $(find "$HERE/src" "$out/gen" -name '*.java')
  java -cp "$out/classes:$JSON_JAR" com.solarized.firedown.harness.AddonsHarness $FIREFOX_SRC
}

echo "== real =="
run_one real "" ""
status=0

mutant() {
  echo
  echo "== mutant: $1 (must FAIL) =="
  if run_one "$1" "$2" "$3" > "$BUILD/$1.log" 2>&1; then
    echo "mutant $1 PASSED the harness — the checks have no teeth" >&2
    status=1
  else
    grep '^FAIL' "$BUILD/$1.log" | sed 's/^/  /'
    echo "  (failed as expected)"
  fi
}

mutant nodomain AddonPolicy 's/if (lower.endsWith(OWN_ID_SUFFIX)) {/if (false) {/'
mutant themes AmoCatalog 's/if (!type.isEmpty() \&\& !"extension".equals(type)) {/if (false) {/'
mutant httpfile AmoCatalog 's/if (!downloadUrl.startsWith("https:\/\/")) {/if (downloadUrl.isEmpty()) {/'
mutant nocap AddonPermissions 's/if (sites.size() > MAX_LISTED) {/if (false) {/'
mutant nocomments XpiManifest 's/manifest = new JSONObject(stripComments(json));/manifest = new JSONObject(json);/'
mutant nosig XpiManifest 's/|| zip.getEntry("META-INF\/cose.sig") != null;/;/'
mutant ublockbuiltin AddonPolicy 's/ids.add("icons@mozac.org");/ids.add("icons@mozac.org"); ids.add("ublock0@raymondhill.net");/'
mutant nohostcheck AddonPolicy 's/return reachesAllSites(permissions) || reachesAllSites(origins);/return true;/'
mutant httpsenough AddonPolicy 's/return http \&\& https;/return http || https;/'
mutant anyoff AddonPolicy 's/if (!isUblockId(id) || title == null) {/if (title == null) {/'
mutant oneonwins AddonPolicy 's/if (Boolean.TRUE.equals(off)) {/if (Boolean.FALSE.equals(off)) {/'
mutant nooverride AddonPolicy 's/if (override != null) {/if (false) {/'
mutant permsblock AddonPolicy 's/return isKnownBlocker(id);/return true;/'
mutant nocase AddonPolicy 's/KNOWN_BLOCKER_IDS.contains(id.trim().toLowerCase(Locale.ROOT))/KNOWN_BLOCKER_IDS.contains(id)/'
mutant anyswitch AddonPolicy 's/return override != null || isKnownBlocker(id) || blocksRequests;/return true;/'
mutant titleecho AddonPolicy 's/if (!n.isEmpty() \&\& !b.isEmpty() \&\& t.equals(n + " (" + b + ")")) {/if (false) {/'
mutant norearm AddonUpdateReviews 's/entry.shown = false;/entry.shown = entry.shown;/'
mutant nagdecline AddonUpdateReviews 's/entry.shown = true;/entry.shown = false;/'
mutant legacynotify AddonUpdateReviews 's/new Entry("", false, true)/new Entry("", false, false)/'
mutant legacyrearm AddonUpdateReviews 's/if (entry.version.isEmpty()) {/if (false) {/'
mutant dailyrecheck AddonUpdateReviews 's/return now < lastRecheck || now - lastRecheck >= RECHECK_INTERVAL_MS;/return true;/'
mutant trustnull AddonUpdateReviews 's/        if (validatedNetwork) {/        if (true) {/'
mutant asksapi AddonPermissions 's/return describe(permissions, origins).isEmpty() \&\& /return /'
mutant declaredonly AddonPermissions 's/        addTrimmed(patterns, granted);//'
mutant noallsites AddonPermissions 's/                allSites.add(pattern);/                allSites.isEmpty();/'
mutant nohostmerge AddonPermissions 's/String key = label.toLowerCase(Locale.ROOT);/String key = pattern;/'
mutant anyscheme AddonPermissions 's/return m.matches() \&\& "\*".equals(m.group(2)) \&\& WEB_SCHEMES.contains(m.group(1));/return m.matches() \&\& "*".equals(m.group(2));/'
mutant v6port AddonPermissions 's/        if (host.startsWith("\[")) {/        if (false) {/'
exit $status

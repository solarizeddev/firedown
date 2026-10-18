#!/bin/sh
# "Two phones, one place": several independent phones share one recovery
# code and ONE S3 bucket (scripts/s3-harness's FakeS3, which checks every
# SigV4 signature) or ONE WebDAV server (scripts/webdav-harness's FakeDav),
# and drive the REAL backup code concurrently and interleaved:
#   - every phone is its own class loader (child-first) holding its own copy
#     of the real classes, OkHttp and the stubs, so its own statics (the S3
#     clock, the BackupStores cache, the settings generations), its own prefs
#     (the real S3Settings / WebDavSettings + BackupStores.choose / open), its
#     own store and its own sidecar cache, exactly as two processes would;
#   - the driver sits in front of the fake servers (SimServers.Gate): it logs
#     which phone asked for what, holds one request (a sidecar PUT) until it
#     says so, and feeds a backup from a source that stops at a chosen byte
#     (an upload half done);
#   - scenarios: both phones backing up at once (different files, the same
#     file), a listing during another phone's half-done upload, a sidecar not
#     yet written vs the 24 h orphan sweep, a delete under another phone's
#     stream, the sidecar cache after another phone's backfill or delete, a
#     delete-all under another phone's upload, two recovery codes in one
#     bucket, conflict copies, the stale-upload sweep vs another phone's long
#     upload, one phone's clock wrong, many files over paginated listings, a
#     corrupted or spliced object, and backup -> list -> stream (seek) ->
#     restore byte-compared, for S3 and for WebDAV.
#   - a check marked BUG asserts what the docs promise and fails on today's
#     code (an app bug, reported, kept failing on purpose); the four it found
#     in the 2026-10 review are fixed and are plain checks now;
#   - planted bugs (mutants.py): each must produce at least one regression
#     (a failure that is not a known BUG). MUTANTS=0 skips them.
#
#   sh scripts/backup-sim-harness/run.sh
#
# Real classes are COPIED from app/src at run time, never re-implemented; the
# fakes are compiled from their own harnesses' source paths, untouched. JDK
# only; OkHttp, Okio, the Kotlin stdlib and org.json come from Maven Central
# into the shared harness cache, sha1-checked.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "${OUT:?}"' EXIT
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

CP="$(fetch com.squareup.okhttp3 okhttp-jvm 5.5.0)"
CP="$CP:$(fetch com.squareup.okio okio-jvm 3.18.1)"
CP="$CP:$(fetch org.jetbrains.kotlin kotlin-stdlib 2.1.21)"
CP="$CP:$(fetch org.json json 20250517)"

STUBS="$ROOT/scripts/webdav-harness/stub $ROOT/scripts/s3-harness/stub"
FAKES="$ROOT/scripts/s3-harness/src/com/solarized/firedown/sync/FakeS3.java
$ROOT/scripts/webdav-harness/src/com/solarized/firedown/sync/FakeDav.java"

# Copies the real classes into $1/src.
copy_sources() {
  SRC="$ROOT/app/src/main/java/com/solarized/firedown/sync"
  DST="$1/src/com/solarized/firedown/sync"
  mkdir -p "$DST/model" "$DST/crypto"
  for f in BackupStore BackupLayout BackupStores ServerTrust SidecarCache \
           S3Signer S3Config S3Client S3Store S3Check S3Settings \
           WebDavSettings WebDavConfig WebDavClient WebDavStore WebDavCheck \
           StallGuard VaultEngine VaultObjectReader; do
    cp "$SRC/$f.java" "$DST/"
  done
  cp "$SRC/model/VaultEntry.java" "$SRC/model/VaultManifest.java" "$DST/model/"
  mkdir -p "$1/src/com/solarized/firedown/okhttp"
  cp "$ROOT/app/src/main/java/com/solarized/firedown/okhttp/UserAgentInterceptor.java" \
     "$1/src/com/solarized/firedown/okhttp/"
  for f in BookmarkBlob Hex VaultCrypto SyncIdentity Hkdf Crockford; do
    cp "$SRC/crypto/$f.java" "$DST/crypto/"
  done
}

copy_sources "$OUT/real"
# shellcheck disable=SC2086
javac -nowarn -proc:none -d "$OUT/real/classes" -cp "$CP" \
      $(find $STUBS "$OUT/real/src" "$HERE/src" -name '*.java') $FAKES

status=0
mkdir -p "$OUT/work"
DO_MUTANTS=0
if [ "${MUTANTS:-1}" = "1" ] && command -v python3 >/dev/null 2>&1; then
  DO_MUTANTS=1
fi

# Applies one mutant to a fresh copy of the sources, recompiles only the file
# it changes (the rest comes from the real build) and runs the quick
# simulation; the verdict goes to $OUT/m-<name>/result.
run_mutant() {
  m=$1
  dir="$OUT/m-$m"
  mkdir -p "$dir/work" "$dir/classes"
  copy_sources "$dir"
  if ! what=$(python3 -I "$HERE/mutants.py" apply "$m" "$dir/src"); then
    echo "FAIL $m: $what" > "$dir/result"
    return
  fi
  file=$(python3 -I "$HERE/mutants.py" file "$m")
  if ! javac -nowarn -proc:none -d "$dir/classes" -cp "$OUT/real/classes:$CP" \
       "$dir/src/com/solarized/firedown/sync/$file" >"$dir/javac.log" 2>&1; then
    echo "FAIL $m: does not compile" > "$dir/result"
    return
  fi
  SIM_QUICK=1 java -Xmx2g -cp "$dir/classes:$OUT/real/classes:$CP" \
       com.solarized.firedown.sync.BackupSim "$dir/work" >"$dir/log" 2>&1 || true
  r=$(sed -n 's/^regressions: \([0-9]*\)$/\1/p' "$dir/log")
  if [ -n "$r" ] && [ "$r" -gt 0 ]; then
    first=$(grep -m1 '^  FAIL ' "$dir/log" | sed 's/^  FAIL //' | cut -c1-120)
    echo "ok   $m caught ($r regressions; first: $first): $what" > "$dir/result"
  else
    echo "FAIL $m survived (regressions: ${r:-none}): $what" > "$dir/result"
  fi
}

# The full run and the mutants share the cores: the full run is lane 0, the
# mutants are dealt round-robin over the other lanes, each lane sequential.
lanes=$(nproc 2>/dev/null || echo 2)
[ "$lanes" -gt 4 ] && lanes=4
[ "$lanes" -lt 2 ] && lanes=2
MUTANT_NAMES=""
if [ "$DO_MUTANTS" = "1" ]; then
  MUTANT_NAMES=$(python3 -I "$HERE/mutants.py" list)
  i=0
  for m in $MUTANT_NAMES; do
    lane=$((1 + i % (lanes - 1)))
    echo "$m" >> "$OUT/lane-$lane"
    i=$((i + 1))
  done
  for f in "$OUT"/lane-*; do
    ( while read -r m; do run_mutant "$m"; done < "$f" ) &
  done
fi

java -Xmx3g -cp "$OUT/real/classes:$CP" com.solarized.firedown.sync.BackupSim "$OUT/work" \
  >"$OUT/base.log" 2>&1 || status=1
cat "$OUT/base.log"
# A run that never reached its summary failed too.
grep -q '^regressions: ' "$OUT/base.log" || status=1

if [ "$DO_MUTANTS" = "1" ]; then
  wait
  echo
  echo "-- mutants: each planted bug must cause a regression (a failure that is not a known BUG)"
  survived=""
  total=0
  for m in $MUTANT_NAMES; do
    total=$((total + 1))
    line=$(cat "$OUT/m-$m/result" 2>/dev/null || echo "FAIL $m: no result")
    echo "  $line"
    case "$line" in
      ok*) ;;
      *) survived="$survived $m" ;;
    esac
  done
  if [ -n "$survived" ]; then
    echo "mutants: $total, survived:$survived"
    status=1
  else
    echo "mutants: all $total caught"
  fi
fi
exit $status

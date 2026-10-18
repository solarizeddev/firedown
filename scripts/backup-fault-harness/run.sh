#!/bin/sh
# Fault injection for the backup places: hostile and broken SERVERS, and
# misbehaving client conditions, driven at the REAL S3Client / S3Store /
# S3Check / WebDavClient / WebDavStore / WebDavCheck and the REAL
# VaultEngine / VaultObjectReader. The servers are a raw-socket interposer
# (src/Interposer.java) in front of the s3-harness's FakeS3 and the
# webdav-harness's FakeDav, compiled from their own source paths, plus
# standalone hostile servers made of the same class:
#   - truncated bodies and TCP resets at chosen byte offsets (listings,
#     sidecar GETs, ranged chunk GETs, the restore stream, mid-upload);
#   - a server that drips one byte at a time (budgeted: StallGuard, with its
#     window shortened to a second, must end the call);
#   - Content-Length larger and smaller than the body;
#   - garbage, enormous and DOCTYPE/entity-laden XML, and a 2-million-node
#     answer within the size cap, parsed in a child JVM with a phone-sized
#     heap;
#   - pagination that never ends (ListObjectsV2 and ListMultipartUploads);
#   - redirects (301/302/307) to a second host and to http, with what the
#     second host received recorded;
#   - 503 / 429 / SlowDown storms with Retry-After 0, negative, enormous,
#     far-future and past HTTP dates;
#   - a 206 for another range, with no Content-Range, and shorter than asked;
#   - a 200 carrying <Error> (listing, CreateMultipartUpload, DeleteObjects);
#   - a 404 on a sidecar and on an object mid-restore; an ETag that moves
#     between the listing and the GET; an upload accepted and then not listed;
#   - a pinned certificate that changes mid-session (S3 and WebDAV);
#   - a cancel at every chunk boundary of a multipart upload (the upload must
#     be aborted on the server, nothing committed);
#   - a sweep of every log line, exception message and toString of the whole
#     run for the secret key, the password, the Basic credential and any
#     signature.
#   - Planted bugs (mutants.py): each is applied to a copy of the sources and
#     must fail a check the real code PASSES (gated on the failing check
#     NAMES, so a check that fails on the real code too proves nothing).
#     MUTANTS=0 skips them.
#
#   sh scripts/backup-fault-harness/run.sh
#
# Real classes are COPIED from app/src at run time, never re-implemented
# (the s3-harness's copy_sources). The stubs are the webdav-harness's and the
# s3-harness's, plus a CAPTURING android.util.Log. OkHttp, Okio, the Kotlin
# stdlib and org.json come from Maven Central into a cache dir, sha1-checked.
# Needs a JDK (javac, java, keytool) and network the first time.
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

# Copies the real classes into $1/src (the s3-harness's list).
copy_sources() {
  SRC="$ROOT/app/src/main/java/com/solarized/firedown/sync"
  DST="$1/src/com/solarized/firedown/sync"
  mkdir -p "$DST/model" "$DST/crypto"
  for f in BackupStore BackupLayout BackupStores ServerTrust StallGuard SidecarCache \
           S3Signer S3Config S3Client S3Store S3Check S3Settings \
           WebDavSettings WebDavConfig WebDavClient WebDavStore WebDavCheck \
           VaultEngine VaultObjectReader; do
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

# Compiles $1/src with the stubs, the two fakes and this harness into
# $1/classes.
compile() {
  javac -nowarn -proc:none -d "$1/classes" -cp "$CP" \
        $(find "$ROOT/scripts/webdav-harness/stub" "$ROOT/scripts/s3-harness/stub" \
               "$HERE/stub" "$1/src" "$HERE/src" -name '*.java') \
        "$ROOT/scripts/s3-harness/src/com/solarized/firedown/sync/FakeS3.java" \
        "$ROOT/scripts/webdav-harness/src/com/solarized/firedown/sync/FakeDav.java"
}

copy_sources "$OUT/real"
compile "$OUT/real"

# Two self-made server certificates: one to pin, and a second that stands
# for "the server's certificate changed mid-session". Both name the fake
# hosts the harness resolves to the loopback.
for k in a b; do
  keytool -genkeypair -alias srv -keyalg RSA -keysize 2048 -validity 2 \
          -dname "CN=s3.fake" -ext "SAN=dns:s3.fake,dns:*.s3.fake,dns:nas.fake" \
          -keystore "$OUT/srv-$k.p12" -storetype PKCS12 \
          -storepass changeit -keypass changeit >/dev/null 2>&1
done

status=0
mkdir -p "$OUT/work"
# The harness also writes the NAMES of its failing checks to $FAULT_HARNESS_FAILS,
# so a mutant counts as caught only when it fails a check the unmutated run
# passed (a check failing on the real code would otherwise catch everything).
FAULT_HARNESS_FAILS="$OUT/real/fails" \
java -Dharness.cp="$OUT/real/classes:$CP" \
     -cp "$OUT/real/classes:$CP" com.solarized.firedown.sync.FaultHarness \
     "$OUT/work" "$OUT/srv-a.p12" "$OUT/srv-b.p12" || status=1
touch "$OUT/real/fails"

if [ "${MUTANTS:-1}" = "1" ] && command -v python3 >/dev/null 2>&1; then
  echo
  echo "-- mutants: each planted bug must fail a check the real code passes"
  # Compiled and run in parallel (the quick mode skips the budgeted waits),
  # each writing its verdict to its own file.
  for m in $(python3 -I "$HERE/mutants.py" list); do
    (
      d="$OUT/m-$m"
      mkdir -p "$d/work"
      copy_sources "$d"
      what=$(python3 -I "$HERE/mutants.py" apply "$m" "$d/src") || { echo "FAIL $m: $what" >"$d/verdict"; exit 0; }
      if ! compile "$d" >"$d/compile.log" 2>&1; then
        echo "FAIL $m: does not compile" >"$d/verdict"
        exit 0
      fi
      FAULT_HARNESS_QUICK=1 FAULT_HARNESS_FAILS="$d/fails" \
      java -Dharness.cp="$d/classes:$CP" -cp "$d/classes:$CP" \
           com.solarized.firedown.sync.FaultHarness "$d/work" "$OUT/srv-a.p12" "$OUT/srv-b.p12" \
           >"$d/log" 2>&1 || true
      touch "$d/fails"
      # Failing names the real run does not have.
      sort -u "$OUT/real/fails" >"$d/base"
      sort -u "$d/fails" >"$d/mine"
      new=$(comm -23 "$d/mine" "$d/base" | wc -l)
      if [ "$new" -gt 0 ]; then
        echo "ok   $m caught ($new new failing check(s)): $what" >"$d/verdict"
      else
        echo "FAIL $m survived: $what" >"$d/verdict"
      fi
    ) &
  done
  wait
  survived=""
  total=0
  for m in $(python3 -I "$HERE/mutants.py" list); do
    total=$((total + 1))
    v=$(cat "$OUT/m-$m/verdict" 2>/dev/null || echo "FAIL $m: no verdict")
    echo "  $v"
    case "$v" in
      FAIL*) survived="$survived $m" ;;
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

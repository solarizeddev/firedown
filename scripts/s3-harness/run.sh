#!/bin/sh
# Drives the REAL S3 backup code on a plain JVM with the REAL OkHttp:
#   - S3Signer: AWS's published S3 SigV4 examples (GET with Range, PUT with
#     a payload, GET ?lifecycle, a list, the presigned GET firedown-api's Go
#     presigner also pins) and 66 cases from botocore, an independent
#     implementation (fixtures/botocore-vectors.tsv, made by
#     gen-botocore-vectors.py: unicode and emoji keys, every reserved
#     character, empty values, an IPv6 host).
#   - BackupStores + S3Settings + WebDavSettings on stub prefs: the stored
#     kind of place, the migration (webdav when a server is saved, else
#     folder), choosing one place forgetting the others' keys, both S3 keys
#     sealed, a key that no longer unwraps reading as not set up.
#   - S3Client / S3Store / S3Check + the real VaultEngine / VaultObjectReader
#     against FakeS3 (an in-JVM S3 that verifies every SigV4 signature with
#     its own code and enforces R2's multipart rules): every check answer,
#     the reconnect rules, single PUT vs multipart on FDVC chunk boundaries,
#     ranged reads, the sidecar cache, the orphan and stale-upload sweeps,
#     pagination, delete-all with and without DeleteObjects, and every
#     failure class (wrong or rotated keys, a gone bucket, access denied, a
#     moved region, clock skew, 500 / SlowDown / 429 with Retry-After, a part
#     dropped mid-body, a cancelled or shrunk upload leaving nothing, an
#     <Error> inside a 200, quota, a vanished or unreadable sidecar), and a
#     self-made certificate trusted by its pin, then changed.
#   - The same round trip against moto (pip install 'moto[server]', or
#     MOTO=/path/to/moto_server) and a real MinIO (MINIO=/path/to/minio;
#     `go install github.com/minio/minio@latest` builds one) when available,
#     with what landed in the bucket listed by boto3, an independent client.
#   - Planted bugs (mutants.py): each is applied to a copy of the sources and
#     the harness must FAIL on it. MUTANTS=0 skips them.
#
#   sh scripts/s3-harness/run.sh
#
# Real classes are COPIED from app/src at run time, never re-implemented.
# android.util.Base64 / TextUtils / the annotations (shared with the WebDAV
# harness), android.content.Context / SharedPreferences, androidx.work,
# NetworkModule, SyncSecrets (a reversible stand-in for the keystore wrap)
# and FolderStore are stubs. OkHttp, Okio, the Kotlin stdlib and org.json
# come from Maven Central into a cache dir, sha1-checked. Needs a JDK
# (javac, java, keytool) and network the first time.
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

# Copies the real classes into $1/src.
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

# Compiles $1/src with the stubs and the harness into $1/classes.
compile() {
  javac -nowarn -proc:none -d "$1/classes" -cp "$CP" \
        $(find "$ROOT/scripts/webdav-harness/stub" "$HERE/stub" "$1/src" "$HERE/src" -name '*.java')
}

copy_sources "$OUT/real"
compile "$OUT/real"

# Two self-made server certificates: one to trust by its fingerprint, and a
# second that stands for "the server's certificate changed".
for k in a b; do
  keytool -genkeypair -alias s3 -keyalg RSA -keysize 2048 -validity 2 \
          -dname "CN=s3.fake" -ext "SAN=dns:s3.fake,dns:*.s3.fake" \
          -keystore "$OUT/s3-$k.p12" -storetype PKCS12 \
          -storepass changeit -keypass changeit >/dev/null 2>&1
done

# moto and boto3: from the environment, else on the PATH.
MOTO=${MOTO:-$(command -v moto_server || true)}
if [ -z "${BOTO_PYTHON:-}" ] && [ -n "$MOTO" ] && [ -x "$(dirname "$MOTO")/python" ]; then
  BOTO_PYTHON="$(dirname "$MOTO")/python"
fi
if [ -z "${BOTO_PYTHON:-}" ] && python3 -c "import boto3" >/dev/null 2>&1; then
  BOTO_PYTHON=python3
fi
MINIO=${MINIO:-$(command -v minio || true)}
export MOTO MINIO BOTO_PYTHON

status=0
mkdir -p "$OUT/work"
java -cp "$OUT/real/classes:$CP" com.solarized.firedown.sync.S3Harness \
     "$OUT/work" "$HERE/fixtures" "$OUT/s3-a.p12" "$OUT/s3-b.p12" || status=1

if [ "${MUTANTS:-1}" = "1" ] && command -v python3 >/dev/null 2>&1; then
  echo
  echo "-- mutants: each planted bug must make the harness fail"
  survived=""
  total=0
  for m in $(python3 -I "$HERE/mutants.py" list); do
    total=$((total + 1))
    rm -rf "$OUT/m"
    mkdir -p "$OUT/m/work"
    copy_sources "$OUT/m"
    what=$(python3 -I "$HERE/mutants.py" apply "$m" "$OUT/m/src") || { echo "  FAIL $m: $what"; survived="$survived $m"; continue; }
    if ! compile "$OUT/m" >/dev/null 2>&1; then
      echo "  FAIL $m: does not compile"
      survived="$survived $m"
      continue
    fi
    if S3_HARNESS_QUICK=1 java -cp "$OUT/m/classes:$CP" com.solarized.firedown.sync.S3Harness \
         "$OUT/m/work" "$HERE/fixtures" "$OUT/s3-a.p12" "$OUT/s3-b.p12" >"$OUT/m/log" 2>&1; then
      echo "  FAIL $m survived: $what"
      survived="$survived $m"
    else
      echo "  ok   $m caught ($(sed -n 's/^[0-9]* passed, \([0-9]*\) failed$/\1/p' "$OUT/m/log") checks): $what"
    fi
  done
  if [ -n "$survived" ]; then
    echo "mutants: $total, survived:$survived"
    status=1
  else
    echo "mutants: all $total caught"
  fi
fi
exit $status

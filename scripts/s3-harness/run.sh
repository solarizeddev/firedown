#!/bin/sh
# Drives the REAL S3 backup code on a plain JVM with the REAL OkHttp:
#   - S3Signer: AWS's published S3 SigV4 examples (GET with Range, PUT with
#     a payload, GET ?lifecycle, a list, the presigned GET firedown-api's Go
#     presigner also pins) and 66 cases from botocore, an independent
#     implementation (fixtures/botocore-vectors.tsv, made by
#     gen-botocore-vectors.py: unicode and emoji keys, every reserved
#     character, empty values, an IPv6 host).
#   - S3Client: URL building (path-style, virtual-hosted, a base path, IPv6),
#     error classification, Retry-After, XML answers (a DOCTYPE refused).
#
#   sh scripts/s3-harness/run.sh
#
# Real classes are COPIED from app/src at run time, never re-implemented.
# android.util.Base64 / TextUtils / the annotations are stubs (shared with
# the WebDAV harness). OkHttp, Okio, the Kotlin stdlib and org.json come from
# Maven Central into a cache dir, sha1-checked. Needs a JDK and network the
# first time.
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

SRC="$ROOT/app/src/main/java/com/solarized/firedown/sync"
DST="$OUT/src/com/solarized/firedown/sync"
mkdir -p "$DST/model" "$DST/crypto"
for f in BackupStore BackupLayout ServerTrust S3Signer S3Config S3Client; do
  cp "$SRC/$f.java" "$DST/"
done
cp "$SRC/model/VaultEntry.java" "$SRC/model/VaultManifest.java" "$DST/model/"
mkdir -p "$OUT/src/com/solarized/firedown/okhttp"
cp "$ROOT/app/src/main/java/com/solarized/firedown/okhttp/UserAgentInterceptor.java" \
   "$OUT/src/com/solarized/firedown/okhttp/"
for f in BookmarkBlob Hex; do
  cp "$SRC/crypto/$f.java" "$DST/crypto/"
done

javac -nowarn -proc:none -d "$OUT/classes" -cp "$CP" \
      $(find "$ROOT/scripts/webdav-harness/stub" "$OUT/src" "$HERE/src" -name '*.java')

java -cp "$OUT/classes:$CP" com.solarized.firedown.sync.S3Harness "$OUT" "$HERE/fixtures"

#!/bin/sh
# Drives the REAL WebDAV backup code on a plain JVM with the REAL OkHttp:
#   - WebDavClient: the multistatus parser (prefixes, absolute hrefs, a 404
#     propstat that must not blank the real props, RFC 1123 dates, a DOCTYPE
#     refused), the streamed PUT (exact length, a short or cancelled body
#     stores nothing), ranged reads (a server that ignores Range, a 206 for
#     the wrong range refused), preemptive Basic sign-in, no 100-continue,
#     certificate pinning (a self-made certificate shown with its
#     fingerprint, then trusted by it; a changed one refused).
#   - WebDavCheck: address normalisation, the Nextcloud endpoints under a
#     site root (and the legacy one for an email sign-in), the saved address
#     after a redirect, and every answer kind (OK, AUTH_FAILED, NOT_WEBDAV,
#     UNREACHABLE, BAD_ADDRESS, READ_ONLY, FULL, UNTRUSTED_CERT).
#   - WebDavStore + the real VaultEngine / VaultObjectReader: backup, list,
#     restore byte-identical, streaming reads across chunk boundaries, dedup,
#     the sidecar cache (an unchanged sidecar is not read twice, a rewritten
#     one is), the orphan sweep, delete / delete-all beside another code, and
#     the failure classes (a dropped upload, a full server, a 500 are
#     retryable; a changed password, a vanished collection, a changed
#     certificate need the user).
#   - Against scripts/webdav-server/DavServer.java, the test server a phone
#     is pointed at, started from source as a separate process: the backup
#     round trip checked on its disk (17 MB, 64 MB, an empty file), a
#     cancelled upload leaving no temporary file, the Nextcloud layout found
#     from host:port alone, read-only, a quota (at the check and mid-backup),
#     and TLS with its self-made certificate: the fingerprint the check shows
#     is the one the server printed, pinned, and the same after a restart.
#   - Against wsgidav, a second real WebDAV server, when installed (pip
#     install wsgidav cheroot, or WSGIDAV=/path/to/wsgidav): the same round
#     trip.
#   - The server itself against litmus, the WebDAV conformance suite, when
#     installed (apt install litmus): basic, copymove and http must pass.
#
#   sh scripts/webdav-harness/run.sh
#
# Real classes are COPIED from app/src at run time, never re-implemented.
# android.util.Base64 / TextUtils / the annotations and the BouncyCastle
# signer (bookmark sync only) are stubs. OkHttp, Okio, the Kotlin stdlib and
# org.json come from Maven Central into a cache dir, sha1-checked. Needs a
# JDK (javac, java, keytool) and network the first time.
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
for f in BackupStore BackupLayout ServerTrust WebDavConfig WebDavClient WebDavStore WebDavCheck \
         VaultEngine VaultObjectReader; do
  cp "$SRC/$f.java" "$DST/"
done
cp "$SRC/model/VaultEntry.java" "$SRC/model/VaultManifest.java" "$DST/model/"
mkdir -p "$OUT/src/com/solarized/firedown/okhttp"
cp "$ROOT/app/src/main/java/com/solarized/firedown/okhttp/UserAgentInterceptor.java" \
   "$OUT/src/com/solarized/firedown/okhttp/"
for f in BookmarkBlob Hex VaultCrypto SyncIdentity Hkdf Crockford; do
  cp "$SRC/crypto/$f.java" "$DST/crypto/"
done

# Two self-made server certificates: one to trust by its fingerprint, and a
# second that stands for "the server's certificate changed".
for k in a b; do
  keytool -genkeypair -alias dav -keyalg RSA -keysize 2048 -validity 2 \
          -dname "CN=nas.local" -ext "SAN=dns:nas.local" \
          -keystore "$OUT/dav-$k.p12" -storetype PKCS12 \
          -storepass changeit -keypass changeit >/dev/null 2>&1
done

javac -nowarn -proc:none -d "$OUT/classes" -cp "$CP" \
      $(find "$HERE/stub" "$OUT/src" "$HERE/src" -name '*.java')

WSGIDAV_BIN=${WSGIDAV:-$(command -v wsgidav || true)}
LITMUS=${LITMUS:-$(command -v litmus || true)} \
java -cp "$OUT/classes:$CP" com.solarized.firedown.sync.WebDavHarness \
     "$OUT" "$OUT/dav-a.p12" "$OUT/dav-b.p12" "$ROOT/scripts/webdav-server/DavServer.java" \
     "$WSGIDAV_BIN"

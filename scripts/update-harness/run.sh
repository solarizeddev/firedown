#!/bin/sh
# scripts/update-harness/run.sh — the update download path (delta updates),
# on a plain JVM, with the REAL classes copied out of app/src at run time:
#
#   1. JUnit: app/src/test's UpdatePatchTest, UpdatePlanTest and
#      UpdateManifestTest (the same tests ./gradlew testDebugUnitTest runs).
#   2. Fuzz (UpdateFuzz): 300 random edits round-tripped through the real
#      generator (app/src/updatetool) and the app's applier (UpdatePatch);
#      3000 random corruptions of a real patch and 500 random headers, every
#      one refused with a PatchException or (for the rare change that decodes
#      to the same data) still exactly the new file — never different bytes;
#      an APK-shaped file for size/speed;
#      the command line app/release.gradle runs.
#   3. Mutants: a planted bug per rule (no progress check, no old-file bound,
#      no seek bound, no end-of-stream checks, no output hash, no header length
#      check, no base-hash check, the 3-patch cap, https-only, the skipped
#      kinds, the mirror walk, the generator's no-progress folding) must each
#      fail the tests. MUTANTS=0 skips them.
#   4. Gradle: the REAL app/release.gradle `releaseStatus` in a scratch
#      project (no Android plugin: `android.defaultConfig` is a map) against
#      APK-shaped files served from file:// URLs — status.json's blocks,
#      release-history.json, the upload folder, SHA256SUMS, each patch
#      re-applied with the app's code, a history entry whose file doesn't
#      match skipped, the old-APK cache reused on a second run, and
#      -Pfiredown.updateDeltas=false; then uploadUpdateRelease against a fake
#      `gh` (draft created with the changelog, every file uploaded). Uses
#      $GRADLE, else `gradle` on PATH;
#      skipped when neither exists. Offline: XZ comes from a local repo.
#   5. Real APKs, opt-in: REAL_APKS="old.apk new.apk" diffs, applies and
#      times two real releases (~3 GB of heap, a few minutes).
#
#   sh scripts/update-harness/run.sh
#
# XZ for Java (the version in gradle.properties), org.json, JUnit and
# Hamcrest come from Maven Central into a cache dir, sha1-checked.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "${OUT:?}"' EXIT
CACHE=${FIREDOWN_HARNESS_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/firedown-harness/maven}
mkdir -p "$CACHE"
pass=0
fail=0
ok() {
    pass=$((pass + 1))
    printf 'ok   %s\n' "$1"
}
bad() {
    fail=$((fail + 1))
    printf 'FAIL %s\n' "$1"
}

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

XZV=$(sed -n 's/^firedown\.xzVersion=//p' "$ROOT/gradle.properties")
XZJAR=$(fetch org.tukaani xz "$XZV") || exit 1
CP="$XZJAR"
CP="$CP:$(fetch org.json json 20260814)" || exit 1
CP="$CP:$(fetch junit junit 4.13.2)" || exit 1
CP="$CP:$(fetch org.hamcrest hamcrest-core 1.3)" || exit 1

# The real sources, copied: main, the release tool, the JUnit tests, plus
# this harness's own checks and an androidx.annotation stub.
SRC="$OUT/src"
copy() { # path-under-app/src/<set>/java
    mkdir -p "$SRC/$(dirname "$2")"
    cp "$ROOT/app/src/$1/java/$2" "$SRC/$2"
}
copy main com/solarized/firedown/update/UpdatePatch.java
copy main com/solarized/firedown/UpdateManifest.java
copy main com/solarized/firedown/UpdatePlan.java
copy updatetool com/solarized/firedown/update/UpdateDiff.java
copy updatetool com/solarized/firedown/update/UpdateTool.java
copy test com/solarized/firedown/update/UpdatePatchTest.java
copy test com/solarized/firedown/UpdatePlanTest.java
copy test com/solarized/firedown/UpdateManifestTest.java
cp -r "$HERE/stub/." "$SRC/"
cp -r "$HERE/src/." "$SRC/"

compile() { # src-dir classes-dir
    rm -rf "$2"
    mkdir -p "$2"
    javac -nowarn -proc:none -encoding UTF-8 -d "$2" -cp "$CP" $(find "$1" -name '*.java') 2>&1 \
        | grep -v '^Picked up JAVA_TOOL_OPTIONS'
}
TESTS="com.solarized.firedown.update.UpdatePatchTest com.solarized.firedown.UpdatePlanTest com.solarized.firedown.UpdateManifestTest"
junit() { # classes-dir test-classes… ; output to $OUT/junit.log
    cls=$1
    shift
    java -cp "$cls:$CP" org.junit.runner.JUnitCore "$@" > "$OUT/junit.log" 2>&1
}

echo "== 1. JUnit"
compile "$SRC" "$OUT/classes"
if [ ! -f "$OUT/classes/com/solarized/firedown/update/UpdatePatch.class" ]; then
    echo "compile failed"
    exit 1
fi
if junit "$OUT/classes" $TESTS; then
    ok "JUnit: $(grep -o 'OK ([0-9]* tests)' "$OUT/junit.log")"
else
    bad "JUnit"
    grep -v '^\s*at org.junit\|^\s*at java\.\|JAVA_TOOL_OPTIONS' "$OUT/junit.log" | head -60 | sed 's/^/     | /'
fi

echo "== 2. Fuzz"
mkdir -p "$OUT/fuzz"
if java -Xmx1g -Xss512m -cp "$OUT/classes:$CP" com.solarized.firedown.update.UpdateFuzz "$OUT/fuzz" ${SEED:-} \
        2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' | tee "$OUT/fuzz.log" | sed 's/^/  /'; then :; fi
if grep -q '^update-fuzz: .* 0 failed$' "$OUT/fuzz.log"; then
    ok "fuzz"
else
    bad "fuzz"
fi

echo "== 3. Mutants"
mutant() { # label file-under-src perl-substitution test-classes…
    label=$1; rel=$2; expr=$3
    shift 3
    m="$OUT/mutant"
    rm -rf "$m"
    mkdir -p "$m"
    cp -r "$SRC" "$m/src"
    before=$(cksum < "$m/src/$rel")
    perl -0pi -e "$expr" "$m/src/$rel"
    if [ "$(cksum < "$m/src/$rel")" = "$before" ]; then
        bad "mutant '$label': the substitution matched nothing (update the harness)"
        return
    fi
    compile "$m/src" "$m/classes" > "$m/compile.log"
    if [ ! -d "$m/classes/com/solarized/firedown" ]; then
        bad "mutant '$label' does not compile"
        sed 's/^/     | /' "$m/compile.log" | head
        return
    fi
    if junit "$m/classes" "$@"; then
        bad "mutant survived: $label"
    else
        ok "mutant killed: $label"
    fi
}
if [ "${MUTANTS:-1}" != 0 ]; then
    P=com/solarized/firedown/update/UpdatePatch.java
    PT=com.solarized.firedown.update.UpdatePatchTest
    mutant 'no-progress control entries allowed' $P 's/if \(add == 0 && copy == 0 && !first\)/if (false)/' $PT
    mutant 'no old-file bound on reads' $P 's/if \(add > header\.oldSize - oldPos\)/if (false)/' $PT
    mutant 'no bound on seeks' $P 's/if \(seek < -oldPos \|\| seek > header\.oldSize - oldPos\)/if (false)/' $PT
    mutant 'no end-of-stream checks' $P 's/\n\s*expectEnd\((ctrlIn|diffIn|extraIn), "\w+"\);//g' $PT
    mutant 'no output hash check' $P 's/if \(!MessageDigest\.isEqual\(written, header\.newSha256\)\)/if (false)/' $PT
    mutant 'trailing bytes after the streams allowed' $P 's/extra != remaining - ctrl - diff/extra > remaining - ctrl - diff/' $PT
    mutant 'the base hash not checked' $P 's/if \(!baseSha\.equalsIgnoreCase\(header\.oldSha256Hex\(\)\)\)/if (false)/' $PT
    mutant 'no xz memory limit' $P 's/XZ_MEMORY_LIMIT_KIB = 64 \* 1024/XZ_MEMORY_LIMIT_KIB = -1/' $PT
    mutant 'generator does not fold no-progress entries' com/solarized/firedown/update/UpdateDiff.java \
        's/if \(add == 0 && copy == 0\) \{\n(\s*)if \(havePending\)/if (false) {\n$1if (havePending)/' $PT
    M=com/solarized/firedown/UpdateManifest.java
    mutant 'more than three patches read' $M 's/i < patchList\.length\(\) && i < MAX_PATCHES/i < patchList.length()/' \
        com.solarized.firedown.UpdateManifestTest
    mutant 'non-https URLs accepted' $M 's/u\.startsWith\("https:\/\/"\)/u.contains(":\/\/")/' \
        com.solarized.firedown.UpdateManifestTest
    mutant 'patch hash compared case-sensitively' $M 's/patch\.fromSha256\.equalsIgnoreCase\(installedSha256\)/patch.fromSha256.equals(installedSha256)/' \
        com.solarized.firedown.UpdatePlanTest
    L=com/solarized/firedown/UpdatePlan.java
    mutant 'a failed kind is tried again' $L 's/if \(!skip\.contains\(Kind\.PATCH\)\)/if (true)/' com.solarized.firedown.UpdatePlanTest
    mutant 'mirrors skipped' $L 's/if \(url \+ 1 < current\(\)\.urls\.size\(\)\)/if (false)/' com.solarized.firedown.UpdatePlanTest
    mutant 'the APK step can be left out' $L 's/steps\.add\(new Step\(Kind\.APK/if (manifest.compressed == null) steps.add(new Step(Kind.APK/' \
        com.solarized.firedown.UpdatePlanTest
fi

echo "== 4. Gradle: app/release.gradle releaseStatus"
GRADLE_BIN=${GRADLE:-$(command -v gradle || true)}
if [ -z "$GRADLE_BIN" ]; then
    echo "skip: no gradle (set GRADLE=)"
else
    PROJ="$OUT/proj"
    OLD="$OUT/old"
    mkdir -p "$PROJ/app/release"
    ln -s "$ROOT/app/src" "$PROJ/app/src"
    java -cp "$OUT/classes:$CP" com.solarized.firedown.update.ReleaseCheck make "$OLD" 2>/dev/null
    cp "$OLD/new-1206.apk" "$PROJ/app/release/app-release.apk"
    printf '{"elements":[{"versionCode":1206,"outputFile":"app-release.apk"}]}\n' > "$PROJ/app/release/output-metadata.json"
    # The app resolves XZ from mavenCentral(); here it comes from a local
    # Maven layout holding the jar fetch() already verified, so this part runs
    # offline (the same resolution path: a detached configuration under
    # FAIL_ON_PROJECT_REPOS settings repositories).
    M2="$OUT/m2/org/tukaani/xz/$XZV"
    mkdir -p "$M2"
    cp "$XZJAR" "$M2/xz-$XZV.jar"
    printf '<project><modelVersion>4.0.0</modelVersion><groupId>org.tukaani</groupId><artifactId>xz</artifactId><version>%s</version></project>\n' \
        "$XZV" > "$M2/xz-$XZV.pom"
    cat > "$PROJ/settings.gradle" <<EOF
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { maven { url = uri('file://$OUT/m2') } }
}
include ':app'
EOF
    echo "firedown.xzVersion=$XZV" > "$PROJ/gradle.properties"
    printf '## [1.2.6] - 2026-11-01\n\n- a bullet\n\n## [1.2.5] - 2026-10-28\n\n- older\n' > "$PROJ/CHANGELOG.md"
    printf '{"versionCode":1205,"updateUrl":"x","sha256":"y","keepMe":true,"patches":[{"stale":1}],"compressed":{"stale":1}}\n' \
        > "$PROJ/status.json"
    sha() { sha256sum "$1" | cut -c1-64; }
    {
        echo '['
        echo "{\"versionCode\":1205,\"versionName\":\"1.2.5\",\"apkUrl\":\"file://$OLD/old-1205.apk\",\"sha256\":\"$(sha "$OLD/old-1205.apk")\"},"
        # 1203's file doesn't hash to what the history says: no patch from it.
        echo "{\"versionCode\":1203,\"versionName\":\"1.2.3\",\"apkUrl\":\"file://$OLD/old-1203.apk\",\"sha256\":\"$(sha "$OLD/new-1206.apk")\"},"
        echo "{\"versionCode\":1204,\"versionName\":\"1.2.4\",\"apkUrl\":\"file://$OLD/old-1204.apk\",\"sha256\":\"$(sha "$OLD/old-1204.apk")\"},"
        echo "{\"versionCode\":1202,\"versionName\":\"1.2.2\",\"apkUrl\":\"file://$OLD/old-1202.apk\",\"sha256\":\"$(sha "$OLD/old-1202.apk")\"}"
        echo ']'
    } > "$PROJ/release-history.json"
    cat > "$PROJ/app/build.gradle" <<EOF
ext.android = [defaultConfig: [versionCode: 1206, versionName: '1.2.6']]
apply from: '$ROOT/app/release.gradle'
EOF
    rg() {
        "$GRADLE_BIN" -p "$PROJ" --offline --no-daemon --console=plain --warning-mode=all -g "$OUT/guh" :app:releaseStatus \
            -Pfiredown.updateMirror=https://mirror.test/updates -Pfiredown.patchJobs=2 "$@" > "$OUT/gradle.log" 2>&1
    }
    if rg; then
        ok "releaseStatus ran"
        if grep -A1 "release.gradle'" "$OUT/gradle.log" | grep -q 'deprecated'; then
            bad "app/release.gradle uses a deprecated Gradle API"
            grep -A1 "release.gradle'" "$OUT/gradle.log" | head -6 | sed 's/^/     | /'
        else
            ok "no deprecated Gradle API in app/release.gradle"
        fi
        grep -q 'hashes to .* release-history.json says' "$OUT/gradle.log" \
            && ok "a history entry whose file doesn't match is skipped with a warning" \
            || bad "no warning for the mismatched history entry"
        java -cp "$OUT/classes:$CP" com.solarized.firedown.update.ReleaseCheck full "$PROJ" "$OLD" 2>&1 \
            | grep -v JAVA_TOOL_OPTIONS | tee "$OUT/check.log" | sed 's/^/  /'
        if grep -q ' 0 failed$' "$OUT/check.log"; then ok "release outputs"; else bad "release outputs"; fi
        # 1203 never matched, so only it is tried again.
        if rg && ! grep -q '^Downloading 1\.2\.[45] ' "$OUT/gradle.log"; then
            ok "a second run reuses the cached old APKs"
        else
            bad "second run re-downloaded or failed"
            tail -20 "$OUT/gradle.log" | sed 's/^/     | /'
        fi
        # uploadUpdateRelease with a fake gh that records its calls: the
        # release doesn't exist, so it is created as a draft, then every file
        # of the folder is uploaded.
        mkdir -p "$OUT/bin"
        cat > "$OUT/bin/gh" <<EOF
#!/bin/sh
echo "\$*" >> "$OUT/gh.log"
case "\$*" in
  "release view"*) exit 1 ;;
  "release create"*) for a in "\$@"; do [ -f "\$a" ] && cp "\$a" "$OUT/gh-notes.md"; done ;;
esac
exit 0
EOF
        chmod +x "$OUT/bin/gh"
        if PATH="$OUT/bin:$PATH" "$GRADLE_BIN" -p "$PROJ" --offline --no-daemon --console=plain -g "$OUT/guh" \
                :app:uploadUpdateRelease -Pfiredown.updateMirror=https://mirror.test/updates > "$OUT/gradle.log" 2>&1 \
            && grep -q '^release view v1.2.6 --repo solarizeddev/firedown$' "$OUT/gh.log" \
            && grep -q '^release create v1.2.6 --repo solarizeddev/firedown --draft --title Firedown 1.2.6 --notes-file ' "$OUT/gh.log" \
            && grep -q '^- a bullet$' "$OUT/gh-notes.md" \
            && grep '^release upload v1.2.6 --repo solarizeddev/firedown --clobber ' "$OUT/gh.log" \
                | grep -q 'v1.2.6/SHA256SUMS .*v1.2.6/app-release.apk .*v1.2.6/firedown-1206.apk.xz .*v1.2.6/patch-1204-1206.fdpatch .*v1.2.6/patch-1205-1206.fdpatch' \
            && grep -q 'Also copy firedown-1206.apk.xz, patch-1204-1206.fdpatch, patch-1205-1206.fdpatch to https://mirror.test/updates/' "$OUT/gradle.log"; then
            ok "uploadUpdateRelease: draft created with the changelog, every file uploaded, mirror copy named"
        else
            bad "uploadUpdateRelease"
            cat "$OUT/gh.log" 2>/dev/null | sed 's/^/     | gh /'
            tail -15 "$OUT/gradle.log" | sed 's/^/     | /'
        fi
        if rg -Pfiredown.updateDeltas=false; then
            java -cp "$OUT/classes:$CP" com.solarized.firedown.update.ReleaseCheck bare "$PROJ" 2>&1 \
                | grep -v JAVA_TOOL_OPTIONS | tee "$OUT/check.log" | sed 's/^/  /'
            if grep -q ' 0 failed$' "$OUT/check.log"; then ok "deltas off"; else bad "deltas off"; fi
        else
            bad "releaseStatus with deltas off failed"
            tail -30 "$OUT/gradle.log" | sed 's/^/     | /'
        fi
    else
        bad "releaseStatus failed"
        tail -40 "$OUT/gradle.log" | sed 's/^/     | /'
    fi
fi

echo "== 5. Real APKs"
if [ -n "${REAL_APKS:-}" ]; then
    set -- $REAL_APKS
    start=$(date +%s)
    if java -Xmx3500m -cp "$OUT/classes:$CP" com.solarized.firedown.update.UpdateTool diff "$1" "$2" "$OUT/real.fdpatch" \
            > "$OUT/real.log" 2>&1; then
        t=$(( $(date +%s) - start ))
        ok "real: $(basename "$1") → $(basename "$2"): patch $(stat -c %s "$OUT/real.fdpatch") bytes of $(stat -c %s "$2"), made and checked in ${t}s"
        start=$(date +%s)
        java -Xmx256m -cp "$OUT/classes:$CP" com.solarized.firedown.update.UpdateTool apply "$1" "$OUT/real.fdpatch" "$OUT/real.apk" > "$OUT/real.log" 2>&1 \
            && [ "$(sha256sum < "$OUT/real.apk")" = "$(sha256sum < "$2")" ] \
            && ok "real: applied in $(( $(date +%s) - start ))s with a 256 MB heap, byte-identical" \
            || bad "real: apply"
    else
        bad "real: diff"
        tail -5 "$OUT/real.log" | sed 's/^/     | /'
    fi
else
    echo "skip: set REAL_APKS=\"old.apk new.apk\""
fi

echo "update-harness: ${pass} passed, ${fail} failed"
[ "$fail" = 0 ]

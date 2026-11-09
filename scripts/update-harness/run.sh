#!/bin/sh
# scripts/update-harness/run.sh — the update download path (delta updates),
# on a plain JVM, with the REAL classes copied out of app/src at run time:
#
#   1. JUnit: app/src/test's UpdatePatchTest, UpdatePlanTest,
#      UpdateManifestTest, UpdateInstallProgressTest (the install tap's
#      states) and UpdatePromptPolicyTest (when the sheet opens by
#      itself) — the same tests ./gradlew testDebugUnitTest runs.
#   1b. The download state machine (UpdateFlowHarness): the REAL
#      UpdateDownloader + UpdateFinalizeWorker against fakes of DownloadManager,
#      WorkManager and SharedPreferences — the patch / .xz / APK ladder,
#      mirrors, a patch for another base, device failures (retry once, then
#      skip), no room for a delta step, the finalize claim (live, stale,
#      unclaimed), a stopped worker, stale broadcasts, a record from before
#      plans, an unreadable record, clearPending, and the direct download
#      the Download Manager can't take (UpdateDirectDownload against a fake
#      server: resume, stalls, stale runs) — plus planted bugs.
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
#      kinds, the mirror walk, the generator's no-progress folding, and the
#      install tap's one slot, its generations, a cancelled Android dialog
#      and whole-percent publishing) must each fail the tests. MUTANTS=0
#      skips them.
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
# The app's OkHttp (app/build.gradle) and what it needs, for the direct
# download's real worker in the flow harness.
CP="$CP:$(fetch com.squareup.okhttp3 okhttp-jvm 5.5.1)" || exit 1
CP="$CP:$(fetch com.squareup.okio okio-jvm 3.18.1)" || exit 1
CP="$CP:$(fetch org.jetbrains.kotlin kotlin-stdlib 2.1.21)" || exit 1

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
copy main com/solarized/firedown/UpdateInstallProgress.java
copy main com/solarized/firedown/UpdatePromptPolicy.java
copy updatetool com/solarized/firedown/update/UpdateDiff.java
copy updatetool com/solarized/firedown/update/UpdateTool.java
copy test com/solarized/firedown/update/UpdatePatchTest.java
copy test com/solarized/firedown/UpdatePlanTest.java
copy test com/solarized/firedown/UpdateManifestTest.java
copy test com/solarized/firedown/UpdateInstallProgressTest.java
copy test com/solarized/firedown/UpdatePromptPolicyTest.java
cp -r "$HERE/stub/." "$SRC/"
cp -r "$HERE/src/." "$SRC/"

compile() { # src-dir classes-dir
    rm -rf "$2"
    mkdir -p "$2"
    javac -nowarn -proc:none -encoding UTF-8 -d "$2" -cp "$CP" $(find "$1" -name '*.java') 2>&1 \
        | grep -v '^Picked up JAVA_TOOL_OPTIONS'
}
TESTS="com.solarized.firedown.update.UpdatePatchTest com.solarized.firedown.UpdatePlanTest com.solarized.firedown.UpdateManifestTest com.solarized.firedown.UpdateInstallProgressTest com.solarized.firedown.UpdatePromptPolicyTest"
junit() { # classes-dir test-classes… ; output to $OUT/junit.log
    cls=$1
    shift
    java -cp "$cls:$CP" org.junit.runner.JUnitCore "$@" > "$OUT/junit.log" 2>&1
}

echo "== 0. Android compile check"
# The update classes that only run on a phone (the installer, its receiver
# and worker, the notifications, the check worker, the scheduler) compiled
# against the real Android framework (Robolectric's android-all, API 35) and
# signature stubs of the androidx / app classes they call (android-stub).
# Catches a missing import, a wrong framework call or a typo before a device
# build does. ANDROID_COMPILE=0 skips it (android-all is ~190 MB, cached).
if [ "${ANDROID_COMPILE:-1}" != 0 ]; then
    AALL=$(fetch org.robolectric android-all 15-robolectric-14663667) || exit 1
    AC="$OUT/android-compile"
    mkdir -p "$AC/src/com/solarized/firedown/utils" "$AC/src/com/solarized/firedown/update" "$AC/out"
    cp -r "$HERE/android-stub/." "$AC/src/"
    for f in Keys UpdateInstaller UpdateInstallReceiver UpdateNotification UpdateInstallWorker UpdateWorker \
             UpdateScheduler UpdateReadyLiveData UpdateDownloader UpdateInstallProgress UpdateFinalizeWorker \
             UpdateManifest UpdatePlan UpdateInstalledApk UpdateDirectDownload UpdateDirectDownloadWorker; do
        cp "$ROOT/app/src/main/java/com/solarized/firedown/$f.java" "$AC/src/com/solarized/firedown/"
    done
    cp "$ROOT/app/src/main/java/com/solarized/firedown/utils/NotificationID.java" "$AC/src/com/solarized/firedown/utils/"
    cp "$ROOT/app/src/main/java/com/solarized/firedown/update/UpdatePatch.java" "$AC/src/com/solarized/firedown/update/"
    if javac -nowarn -proc:none -encoding UTF-8 -d "$AC/out" -cp "$AALL:$XZJAR" $(find "$AC/src" -name '*.java') \
            > "$AC/log" 2>&1; then
        ok "the update classes compile against the Android framework"
    else
        bad "the update classes do not compile against the Android framework"
        grep -v JAVA_TOOL_OPTIONS "$AC/log" | head -30 | sed 's/^/     | /'
    fi
    # The device tests of the update path, against the same classes plus
    # JUnit and signature stubs of androidx.test (android-test-stub).
    AT="$AC/test"
    mkdir -p "$AT/src/com/solarized/firedown/update" "$AT/out"
    cp -r "$HERE/android-test-stub/." "$AT/src/"
    for f in UpdateDirectDownloadDeviceTest; do
        cp "$ROOT/app/src/androidTest/java/com/solarized/firedown/update/$f.java" "$AT/src/com/solarized/firedown/update/"
    done
    if javac -nowarn -proc:none -encoding UTF-8 -d "$AT/out" -cp "$AC/out:$AALL:$XZJAR:$CP" $(find "$AT/src" -name '*.java') \
            > "$AT/log" 2>&1; then
        ok "the update device tests compile against the Android framework"
    else
        bad "the update device tests do not compile against the Android framework"
        grep -v JAVA_TOOL_OPTIONS "$AT/log" | head -30 | sed 's/^/     | /'
    fi
else
    echo "skip: ANDROID_COMPILE=0"
fi

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

echo "== 1b. The download state machine (UpdateFlowHarness)"
# The real UpdateDownloader, UpdateFinalizeWorker, UpdatePlan, UpdateManifest,
# UpdateInstalledApk and UpdatePatch against fakes of DownloadManager,
# WorkManager and SharedPreferences (flow/stub; the android-all compile check
# of these classes is separate: they use only what the stubs declare).
FLOW="$OUT/flow-src"
mkdir -p "$FLOW/com/solarized/firedown/update"
cp -r "$HERE/flow/stub/." "$HERE/flow/src/." "$FLOW/"
for f in Keys UpdateManifest UpdatePlan UpdateDownloader UpdateFinalizeWorker UpdateInstalledApk UpdateDirectDownload \
         UpdateDirectDownloadWorker; do
    cp "$ROOT/app/src/main/java/com/solarized/firedown/$f.java" "$FLOW/com/solarized/firedown/"
done
cp "$ROOT/app/src/main/java/com/solarized/firedown/update/UpdatePatch.java" \
   "$ROOT/app/src/updatetool/java/com/solarized/firedown/update/UpdateDiff.java" "$FLOW/com/solarized/firedown/update/"
flow() { # src-dir classes-dir; output to $OUT/flow.log
    compile "$1" "$2" > "$OUT/flow-compile.log"
    if [ ! -f "$2/com/solarized/firedown/UpdateFlowHarness.class" ]; then
        sed 's/^/     | /' "$OUT/flow-compile.log" | head -20 > "$OUT/flow.log"
        return 2
    fi
    java -cp "$2:$CP" com.solarized.firedown.UpdateFlowHarness > "$OUT/flow.log" 2>&1
}
if flow "$FLOW" "$OUT/flow-classes"; then
    ok "flow: $(grep -o '[0-9]* passed, 0 failed' "$OUT/flow.log")"
else
    bad "flow"
    grep -v '^ok ' "$OUT/flow.log" | grep -v JAVA_TOOL_OPTIONS | head -30 | sed 's/^/     | /'
fi
fmutant() { # label file-under-flow perl-substitution
    m="$OUT/fmutant"
    rm -rf "$m"
    mkdir -p "$m"
    cp -r "$FLOW" "$m/src"
    before=$(cksum < "$m/src/$2")
    perl -0pi -e "$3" "$m/src/$2"
    if [ "$(cksum < "$m/src/$2")" = "$before" ]; then
        bad "flow mutant '$1': the substitution matched nothing (update the harness)"
        return
    fi
    flow "$m/src" "$m/classes"
    case $? in
        0) bad "flow mutant survived: $1" ;;
        2) bad "flow mutant '$1' does not compile"; cat "$OUT/flow.log" ;;
        *) ok "flow mutant killed: $1" ;;
    esac
}
if [ "${MUTANTS:-1}" != 0 ]; then
    D=com/solarized/firedown/UpdateDownloader.java
    fmutant 'a device failure never skips the step' $D 's/MAX_DEVICE_FAILURES = 2/MAX_DEVICE_FAILURES = 99/'
    fmutant 'a failed patch is not remembered' $D 's/prefs\(context\)\.edit\(\)\.putInt\(Keys\.UPDATE_PATCH_FAILED_VERSION, pending\.versionCode\)\.apply\(\);//'
    fmutant 'a new download inherits the old claim' $D 's/\.remove\(Keys\.UPDATE_DOWNLOAD_FINALIZING_AT\)\n(\s*)\.remove\(Keys\.UPDATE_DOWNLOAD_URLS\)/.remove(Keys.UPDATE_DOWNLOAD_URLS)/'
    fmutant 'a dead rebuild is downloaded again' $D 's/if \(pending\.plan\.current\(\)\.kind != UpdatePlan\.Kind\.APK\) \{\n(\s*)Log\.w\(TAG, "the "/if (false) {\n$1Log.w(TAG, "the "/'
    fmutant 'an unclaimed finished download is downloaded again' $D 's/UpdateFinalizeWorker\.enqueue\(context, previousId\);\n(\s*)return true;/return false;/'
    fmutant 'markReady removes the APK step'"'"'s row' $D 's/if \(pending\.plan\.current\(\)\.kind != UpdatePlan\.Kind\.APK\) \{\n(\s*)removeDownload\(context, downloadId\);/if (true) {\n$1removeDownload(context, downloadId);/'
    fmutant 'clearPending leaves the download' $D 's/removeDownload\(context, prefs\(context\)\.getLong\(Keys\.UPDATE_DOWNLOAD_ID, -1\)\);\n(\s*)removeOrphansLocked\(context\);\n(\s*)deleteArtifacts\(context\);\n(\s*)clearPendingLocked/removeOrphansLocked(context);\n$2deleteArtifacts(context);\n$3clearPendingLocked/'
    fmutant 'no space check before a delta step' $D 's/return dir\.getUsableSpace\(\) >= step\.size/return true || dir.getUsableSpace() >= step.size/'
    fmutant 'an unreadable record is kept' $D 's/if \(pending == null\) \{\n(\s*)\/\/ An id with no readable record/if (false) {\n$1\/\/ An id with no readable record/'
    fmutant 'a new download keeps the old ready record' $D 's/clearReadyRecordLocked\(context\);\n\n(\s*)\/\/ A patch or \.xz needs room/\n$1\/\/ A patch or .xz needs room/'
    fmutant 'ready trusted beside a recorded download' $D 's/if \(p\.getLong\(Keys\.UPDATE_DOWNLOAD_ID, -1\) != -1\) \{\n(\s*)return false;/if (false) {\n$1return false;/'
    fmutant 'a retry time a year away is believed' $D 's/return remaining > 0 && remaining <= BACKOFF_MAX_MS \+ BACKOFF_MAX_MS \/ 5;/return remaining > 0;/'
    fmutant 'a claim from the future is believed' $D 's/return age > -CLAIM_CLOCK_SLACK_MS && age < FINALIZE_CLAIM_MS;/return age < FINALIZE_CLAIM_MS;/'
    fmutant 'a cleared ready record leaves its notification' $D 's/\n(\s*)UpdateNotification\.cancelInstallProgress\(context\);\n/\n/'
    fmutant 'one sighting drops a pulled release' $D 's/if \(now - seenAt < PULLED_CONFIRM_MS\) \{/if (false) {/'
    fmutant 'a disabled DownloadManager throws out of clearPending' $D 's/\} catch \(RuntimeException e\) \{\n(\s*)\/\/ The Download Manager app disabled/} catch (IllegalStateException e) {\n$1\/\/ The Download Manager app disabled/'
    fmutant 'a refused Download Manager download is not downloaded directly' $D 's/if \(direct\) \{\n(\s*)downloadId = newDirectId\(\);/if (direct) {\n$1return;/'
    fmutant 'a direct download in flight is started again' $D 's/UpdateDirectDownloadWorker\.enqueue\(context, previousId\);\n(\s*)return true;/return false;/'
    fmutant 'a moved-on record still takes a finished direct file' $D 's/if \(pending == null \|\| pending\.downloadId != downloadId\) \{\n(\s*)part\.delete\(\);\n(\s*)return false;/if (pending == null) {\n$1part.delete();\n$2return false;/'
    fmutant 'a removed direct download keeps running' $D 's/\n(\s*)UpdateDirectDownloadWorker\.cancel\(context, id\);//'
    fmutant 'a disabled Download Manager throws out of a status query' $D 's/\} catch \(RuntimeException e\) \{\n(\s*)\/\/ A Download Manager disabled after/} catch (IllegalStateException e) {\n$1\/\/ A Download Manager disabled after/'
    fmutant 'room for a delta measured on external storage only' $D 's/File dir = Preferences\.getUpdateApkFile\(context\)\.getParentFile\(\);\n(\s*)if \(dir == null\) \{\n(\s*)return false;\n(\s*)\}\n(\s*)return dir\.getUsableSpace\(\) >= step\.size/File dir = context.getExternalFilesDir(null);\n$1if (dir == null) {\n$2return false;\n$3}\n$4return dir.getUsableSpace() >= step.size/'
    X=com/solarized/firedown/UpdateDirectDownload.java
    fmutant 'a resume overwrites what it kept' $X 's/new FileOutputStream\(part, have > 0\)/new FileOutputStream(part, false)/'
    fmutant 'a 200 to a resume is appended' $X 's/have = 0L;\n(\s*)total = answer\.total;/total = answer.total;/'
    fmutant 'a 206 for another range is appended' $X 's/if \(answer\.rangeStart != have\) \{/if (false) {/'
    fmutant 'a direct download never gives up' $X 's/MAX_STALLS = 6;/MAX_STALLS = 1000;/'
    fmutant 'progress does not reset the stall count' $X 's/if \(written > 0\) \{\n(\s*)UpdateDownloader\.resetDirectStalls/if (false) {\n$1UpdateDownloader.resetDirectStalls/'
    fmutant 'an attempt that keeps starting over never ends' $X 's/if \(UpdateDownloader\.directBytes\(context, downloadId\) > cap\) \{/if (false) {/'
    fmutant 'a complete part is fetched again on a 416' $X 's/\} else if \(answer\.code == 416 && have > 0 && answer\.total == have\) \{/} else if (false) {/'
    fmutant 'the bytes read after a stop are written' $X 's/\n\s*\/\/ Checked again: a read can block for long, and the system\n\s*\/\/ may already be running this download.s next run\.\n\s*if \(stopped\.getAsBoolean\(\)\) \{\n\s*return interrupted\(context, downloadId, written, true\);\n\s*\}//'
    fmutant 'another writer is not noticed' $X 's/if \(part\.length\(\) != have\) \{/if (false) {/'
    fmutant 'progress pays the stall backoff' $X 's/return written > 0 \? Outcome\.CONTINUE/return written > 0 ? Outcome.RETRY/'
    fmutant 'a run after the hand-over downloads again' $X 's/if \(UpdateDownloader\.isDownloadComplete\(context, downloadId\)\) \{/if (false) {/'
    fmutant 'orphaned Download Manager rows are never removed' $D 's/removeDownload\(context, p\.getLong\(Keys\.UPDATE_DOWNLOAD_ID, -1\)\);\n(\s*)removeOrphansLocked\(context\);/removeDownload(context, p.getLong(Keys.UPDATE_DOWNLOAD_ID, -1));/'
    fmutant 'orphans removed before the in-flight check' $D 's/synchronized \(LOCK\) \{\n(\s*)if \(isBackingOff\(context, versionCode\)\) \{/synchronized (LOCK) {\n$1removeOrphansLocked(context);\n$1if (isBackingOff(context, versionCode)) {/'
    fmutant 'clearPending leaves the orphans' $D 's/removeDownload\(context, prefs\(context\)\.getLong\(Keys\.UPDATE_DOWNLOAD_ID, -1\)\);\n(\s*)removeOrphansLocked\(context\);\n(\s*)deleteArtifacts\(context\);\n(\s*)clearPendingLocked/removeDownload(context, prefs(context).getLong(Keys.UPDATE_DOWNLOAD_ID, -1));\n$2deleteArtifacts(context);\n$3clearPendingLocked/'
    fmutant 'markReady forgets the digest' $D 's/\.putString\(Keys\.UPDATE_READY_SHA, pending\.apkSha256\)//'
    K=com/solarized/firedown/UpdateDirectDownloadWorker.java
    fmutant 'a progressed run is not continued' $K 's/\n(\s*)continueLater\(getApplicationContext\(\), downloadId\);\n/\n/'
    fmutant 'the worker accepts gzip' $K 's/\.addHeader\("Accept-Encoding", "identity"\)/.addHeader("X-No", "x")/'
    fmutant 'a 416'"'"'s Content-Range is not read' $K 's/response\.code\(\) == 206 \|\| response\.code\(\) == 416/response.code() == 206/'
    fmutant 'a run whose record left is continued' $X 's/if \(UpdateDownloader\.directPending\(context, downloadId\) == null\) \{\n(\s*)\/\/ The record left during the run/if (false) {\n$1\/\/ The record left during the run/'
    fmutant 'the continuation asks no Range' $K 's/if \(from > 0\) \{/if (false) {/'
    fmutant 'a 5xx is a refusal' $X 's/ \|\| answer\.code >= 500\)/)/'
    fmutant 'a Content-Length past status.json is read anyway' $X 's/if \(expected > 0 && total > 0 && total != expected\) \{/if (false) {/'
    W=com/solarized/firedown/UpdateFinalizeWorker.java
    fmutant 'a device failure walks the plan' $W 's/UpdateDownloader\.onDeviceFailed\(context, pending\.downloadId\);\n(\s*)return Result\.success\(\);\n(\s*)\}\n(\s*)\}/UpdateDownloader.onStepFailed(context, pending.downloadId);\n$1return Result.success();\n$2}\n$3}/'
    fmutant 'a finished direct download reads as failed' $W 's/return UpdateDownloader\.isDownloadComplete\(context, downloadId\);/return !UpdateDownloader.isDirect(downloadId) \&\& UpdateDownloader.isDownloadComplete(context, downloadId);/'
    fmutant 'a wrong download skips the whole step' $W 's/downloaded\.delete\(\);\n(\s*)UpdateDownloader\.onAttemptFailed\(context, downloadId\);/downloaded.delete();\n$1UpdateDownloader.onStepFailed(context, downloadId);/'
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
    mutant 'no xz memory limit' $P 's/XZ_MEMORY_LIMIT_KIB = 16 \* 1024/XZ_MEMORY_LIMIT_KIB = -1/' $PT
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
    I=com/solarized/firedown/UpdateInstallProgress.java
    IT=com.solarized.firedown.UpdateInstallProgressTest
    mutant 'two installs at once' $I 's/if \(busy\(\)\) \{\n(\s*)return null;/if (false) {\n$1return null;/' $IT
    mutant 'a late callback moves a newer install' $I 's/if \(generation != mGeneration \|\| mPhase != Phase\.INSTALLING\)/if (mPhase != Phase.INSTALLING)/' $IT
    mutant 'a cancelled dialog reads as a failure' $I 's/public synchronized Snapshot refused\(\) \{\n(\s*)if \(mPhase != Phase\.INSTALLING\)/public synchronized Snapshot refused() {\n$1if (false)/' $IT
    mutant 'every byte publishes' $I 's/if \(percent == mPercent\) \{/if (false) {/' $IT
    Q=com/solarized/firedown/UpdatePromptPolicy.java
    QT=com.solarized.firedown.UpdatePromptPolicyTest
    mutant 'the sheet opens over a page' $Q 's/return firstScreen;/return true;/' $QT
    mutant 'the sheet opens while something runs' $Q 's/if \(!ready \|\| snoozed \|\| busy != Busy\.NONE\)/if (!ready || snoozed)/' $QT
    mutant 'the sheet opens on the lock screen' $Q 's/default:\n(\s*)return false;/default:\n$1return firstScreen;/' $QT
    mutant 'reminders never end' $Q 's/count >= MAX_PROMPTS \? Long\.MAX_VALUE/false ? Long.MAX_VALUE/' $QT
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
    # The test APKs are numbered below the first app that reads patches
    # (1206), so the runs that build patches lower that bound; rg_default
    # keeps it.
    rg_default() {
        "$GRADLE_BIN" -p "$PROJ" --offline --no-daemon --console=plain --warning-mode=all -g "$OUT/guh" :app:releaseStatus \
            -Pfiredown.updateMirror=https://mirror.test/updates -Pfiredown.patchJobs=2 "$@" > "$OUT/gradle.log" 2>&1
    }
    rg() {
        rg_default -Pfiredown.patchMinFrom=0 "$@"
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
        # By default no patch is made from a release whose app can't apply
        # one (before 1206): every source here is older, so status.json lists
        # the .xz and no patches, and the upload folder holds none.
        if rg_default; then
            if ! grep -q '"patches"' "$PROJ/status.json" && grep -q '"compressed"' "$PROJ/status.json" \
                && ! ls "$PROJ/app/build/outputs/update/v1.2.6/" | grep -q fdpatch \
                && grep -q 'apps before 1206 read no patches' "$OUT/gradle.log"; then
                ok "no patch from a release whose app reads none (before 1206)"
            else
                bad "patches were made for releases that can't apply them"
                grep -o '"patches"[^]]*' "$PROJ/status.json" | head -3 | sed 's/^/     | /'
            fi
        else
            bad "releaseStatus with the default patch bound failed"
            tail -30 "$OUT/gradle.log" | sed 's/^/     | /'
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

package com.solarized.firedown;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;

import com.solarized.firedown.update.UpdateDiff;
import com.solarized.firedown.update.UpdatePatch;

import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * The update download state machine: the REAL UpdateDownloader,
 * UpdateFinalizeWorker, UpdatePlan, UpdateManifest, UpdateInstalledApk and
 * UpdatePatch, against fakes of DownloadManager (rows the harness finishes or
 * fails, remove() deleting the file), WorkManager (records unique work; the
 * harness runs workers) and SharedPreferences. Real patches and .xz files.
 *
 * Each scenario follows one update from UpdateWorker's call to start() to
 * the verified APK, or to the backoff, and checks the record, the files and
 * the DownloadManager rows at each step. Exits 1 on any failed check.
 */
public final class UpdateFlowHarness {

    private static int pass;
    private static int fail;

    // One phone, one release, per scenario.
    private static File root;
    private static Context ctx;
    private static DownloadManager dm;
    private static byte[] oldApk;
    private static byte[] newApk;
    private static byte[] patch;
    private static byte[] xz;
    private static final Map<String, byte[]> server = new HashMap<>();

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("update-flow").toFile();
        Random random = new Random(5);
        oldApk = new byte[400_000];
        random.nextBytes(oldApk);
        newApk = oldApk.clone();
        for (int i = 1000; i < 1200; i++) {
            newApk[i] ^= 0x33;
        }
        patch = UpdateDiff.diff(oldApk, newApk);
        xz = xz(newApk);

        patchHappyPath();
        aWrongFileTriesTheNextMirror();
        aPatchForAnotherBaseSkipsToTheXz();
        theApkStepAfterBothFail();
        aFailedDownloadTriesTheNextMirror();
        everythingFailingBacksOff();
        aDeviceFailureRetriesOnceThenSkips();
        notEnoughRoomForAPatchGoesStraightOn();
        theClaimProtocol();
        aStoppedWorkerKeepsItsClaim();
        aStaleOrDuplicateBroadcastChangesNothing();
        aRecordFromBeforePlansFinishes();
        anUnreadableRecordIsCleared();
        clearPendingRemovesTheDownload();
        noPatchForAnotherInstalledApk();
        aSignatureFailureOnTheApkTriesTheNextMirror();
        aNewerReleaseRetiresTheReadyOne();
        aClockSetBackNeverHoldsAVersion();
        aPulledReleaseStopsBeingOffered();
        aDisabledDownloadManagerNeverThrows();

        System.out.println("update-flow: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ── scenarios ────────────────────────────────────────────────────────

    private static void patchHappyPath() throws Exception {
        String s = "patch: ";
        phone();
        startUpdate(manifest());
        DownloadManager.Row row = current();
        check(row != null && row.url.equals("https://gh/p") && row.dest.getName().equals("firedown.apk.patch"),
                s + "the patch is downloaded first, to its own file");
        finish(row);
        ListenableWorker.Result r = runWorker(row.id);
        check("success".equals(r.toString()) && UpdateDownloader.isVerifiedReady(ctx, 1206),
                s + "rebuilt and verified");
        check(Arrays.equals(newApk, Files.readAllBytes(apk().toPath())), s + "the APK is the new release byte for byte");
        check(!row.dest.exists() && !UpdateDownloader.partFile(ctx).exists(), s + "the patch and the temporary file are gone");
        check(!dm.rows.containsKey(row.id), s + "its DownloadManager row is removed");
        check(UpdateNotification.prompts == 1, s + "the install prompt is posted");
        check(prefs().getLong(Keys.UPDATE_DOWNLOAD_ID, -1) == -1 && !prefs().contains(Keys.UPDATE_DOWNLOAD_PLAN)
                && !prefs().contains(Keys.UPDATE_DOWNLOAD_FINALIZING_AT), s + "the download record is cleared");
    }

    private static void aWrongFileTriesTheNextMirror() throws Exception {
        String s = "wrong file: ";
        phone();
        server.put("https://gh/p", "not the patch".getBytes());
        startUpdate(manifest());
        DownloadManager.Row first = current();
        finish(first);
        runWorker(first.id);
        DownloadManager.Row second = current();
        check(second != null && second.url.equals("https://m/p"), s + "the next mirror of the same patch");
        check(!dm.rows.containsKey(first.id), s + "the wrong file's row is removed");
        check(UpdateDownloader.failedKinds(ctx, 1206).isEmpty(), s + "a bad mirror is not a bad patch");
        // The new download is unclaimed: a check hands it over instead of downloading again.
        finish(second);
        WorkManager.INSTANCE.unique.clear();
        startUpdate(manifest());
        check(current().id == second.id && WorkManager.INSTANCE.unique.containsKey("update_finalize_" + second.id),
                s + "a finished, unclaimed download is handed to the worker, not downloaded again");
        runWorker(second.id);
        check(UpdateDownloader.isVerifiedReady(ctx, 1206), s + "then rebuilt and verified");
    }

    private static void aPatchForAnotherBaseSkipsToTheXz() throws Exception {
        String s = "wrong base: ";
        phone();
        // A patch whose own hash status.json names, but made from another APK.
        byte[] otherBase = oldApk.clone();
        otherBase[5] ^= 1;
        byte[] foreign = UpdateDiff.diff(otherBase, newApk);
        server.put("https://gh/p", foreign);
        server.put("https://m/p", foreign);
        startUpdate(manifest(sha(foreign), foreign.length));
        DownloadManager.Row row = current();
        finish(row);
        runWorker(row.id);
        DownloadManager.Row next = current();
        check(next != null && next.url.equals("https://gh/x"), s + "skips the other mirror and goes to the .xz");
        check(UpdateDownloader.failedKinds(ctx, 1206).contains(UpdatePlan.Kind.PATCH), s + "the patch is remembered as failed");
        finish(next);
        runWorker(next.id);
        check(UpdateDownloader.isVerifiedReady(ctx, 1206)
                && Arrays.equals(newApk, Files.readAllBytes(apk().toPath())), s + "the .xz gives the APK");
        // The next round for this version doesn't offer the patch again.
        UpdatePlan plan = UpdatePlan.build(UpdateManifest.parse(manifest(sha(foreign), foreign.length)),
                sha(oldApk), UpdateDownloader.failedKinds(ctx, 1206));
        check(plan.current().kind == UpdatePlan.Kind.XZ, s + "a new plan for this version starts at the .xz");
    }

    private static void theApkStepAfterBothFail() throws Exception {
        String s = "ladder: ";
        phone();
        server.put("https://gh/x", "junk".getBytes());
        server.put("https://m/x", "junk".getBytes());
        prefs().edit().putInt(Keys.UPDATE_PATCH_FAILED_VERSION, 1206).apply();
        startUpdate(manifest());
        for (int i = 0; i < 2; i++) {
            DownloadManager.Row row = current();
            check(row.url.equals(i == 0 ? "https://gh/x" : "https://m/x"), s + "xz mirror " + (i + 1));
            finish(row);
            runWorker(row.id);
        }
        DownloadManager.Row apkRow = current();
        check(apkRow != null && apkRow.url.equals("https://gh/app.apk") && apkRow.dest.getName().equals("firedown.apk"),
                s + "then the APK itself, into the APK's file");
        finish(apkRow);
        runWorker(apkRow.id);
        check(UpdateDownloader.isVerifiedReady(ctx, 1206), s + "the APK is verified");
        check(dm.rows.containsKey(apkRow.id) && apk().exists(),
                s + "an APK step's row is kept (removing it would delete the ready APK)");
    }

    private static void aFailedDownloadTriesTheNextMirror() throws Exception {
        String s = "failed download: ";
        phone();
        startUpdate(manifest());
        DownloadManager.Row row = current();
        row.status = DownloadManager.STATUS_FAILED;
        runWorker(row.id);
        check(current().url.equals("https://m/p"), s + "the next mirror");
    }

    private static void everythingFailingBacksOff() throws Exception {
        String s = "all fail: ";
        phone();
        startUpdate(manifest());
        int downloads = 0;
        while (prefs().getLong(Keys.UPDATE_DOWNLOAD_ID, -1) != -1 && downloads < 20) {
            DownloadManager.Row row = current();
            row.status = DownloadManager.STATUS_FAILED;
            runWorker(row.id);
            downloads++;
        }
        check(downloads == 6, s + "2 patch + 2 xz + 2 APK mirrors tried, then stopped (" + downloads + ")");
        check(UpdateDownloader.isBackingOff(ctx, 1206) && prefs().getInt(Keys.UPDATE_FAILED_COUNT, 0) == 1
                && WorkManager.INSTANCE.unique.containsKey("update_retry"), s + "a backoff retry is scheduled");
        check(dm.rows.isEmpty(), s + "no DownloadManager row is left behind");
        int before = dm.rows.size();
        startUpdate(manifest());
        check(dm.rows.size() == before, s + "a check inside the backoff window downloads nothing");
    }

    private static void aDeviceFailureRetriesOnceThenSkips() throws Exception {
        String s = "device failure: ";
        phone();
        // The rebuild can't write its temporary file.
        File part = UpdateDownloader.partFile(ctx);
        part.mkdirs();
        Files.write(new File(part, "blocker").toPath(), new byte[1]);
        startUpdate(manifest());
        DownloadManager.Row first = current();
        finish(first);
        runWorker(first.id);
        check(prefs().getLong(Keys.UPDATE_DOWNLOAD_ID, -1) == -1 && UpdateDownloader.isBackingOff(ctx, 1206),
                s + "the first time waits and starts over");
        check(UpdateDownloader.failedKinds(ctx, 1206).isEmpty(), s + "the first time doesn't blame the patch");
        // The retry round: same plan, same failure.
        prefs().edit().remove(Keys.UPDATE_NEXT_RETRY_AT).apply();
        startUpdate(manifest());
        DownloadManager.Row second = current();
        check(second.url.equals("https://gh/p"), s + "the retry downloads the patch again");
        finish(second);
        runWorker(second.id);
        DownloadManager.Row third = current();
        check(third != null && third.url.equals("https://gh/x"), s + "the second time skips to the .xz");
        check(UpdateDownloader.failedKinds(ctx, 1206).contains(UpdatePlan.Kind.PATCH), s + "and remembers it");
    }

    private static void notEnoughRoomForAPatchGoesStraightOn() throws Exception {
        String s = "no room: ";
        phone();
        // status.json says the patch and the .xz are larger than any free space.
        String json = manifest().replace("\"size\":" + patch.length, "\"size\":" + (Long.MAX_VALUE / 4))
                .replace("\"size\":" + xz.length, "\"size\":" + (Long.MAX_VALUE / 4));
        startUpdate(json);
        DownloadManager.Row row = current();
        check(row != null && row.url.equals("https://gh/app.apk"),
                s + "a step that can't fit is skipped before anything is downloaded");
        check(UpdateDownloader.failedKinds(ctx, 1206).isEmpty(), s + "without blaming the patch");
    }

    private static void theClaimProtocol() throws Exception {
        String s = "claim: ";
        phone();
        startUpdate(manifest());
        DownloadManager.Row row = current();
        finish(row);
        UpdateDownloader.Pending claimed = UpdateDownloader.beginFinalize(ctx, row.id);
        check(claimed != null && prefs().contains(Keys.UPDATE_DOWNLOAD_FINALIZING_AT), s + "a worker claims the download");
        WorkManager.INSTANCE.unique.clear();
        startUpdate(manifest());
        check(current().id == row.id && row.dest.exists() && WorkManager.INSTANCE.unique.isEmpty(),
                s + "a check while it is claimed leaves it alone");
        // A claim older than WorkManager's 10-minute limit: the worker died.
        prefs().edit().putLong(Keys.UPDATE_DOWNLOAD_FINALIZING_AT,
                System.currentTimeMillis() - 31L * 60L * 1000L).apply();
        startUpdate(manifest());
        DownloadManager.Row next = current();
        check(next.id != row.id && next.url.equals("https://gh/x"), s + "a dead patch rebuild is skipped, not re-downloaded");
        check(!prefs().contains(Keys.UPDATE_DOWNLOAD_FINALIZING_AT), s + "the new download starts unclaimed");
        check(UpdateDownloader.beginFinalize(ctx, row.id) == null, s + "the old download can no longer be claimed");
    }

    private static void aStoppedWorkerKeepsItsClaim() throws Exception {
        String s = "stopped: ";
        phone();
        startUpdate(manifest());
        DownloadManager.Row row = current();
        finish(row);
        UpdateFinalizeWorker worker = worker(row.id);
        worker.stopped = true;
        ListenableWorker.Result r = worker.doWork();
        check("retry".equals(r.toString()), s + "asks WorkManager to run it again");
        check(prefs().getLong(Keys.UPDATE_DOWNLOAD_ID, -1) == row.id && row.dest.exists()
                && prefs().contains(Keys.UPDATE_DOWNLOAD_FINALIZING_AT), s + "the download and the claim are kept");
        check(!UpdateDownloader.partFile(ctx).exists() && !apk().exists(), s + "nothing half-built is left");
        runWorker(row.id);
        check(UpdateDownloader.isVerifiedReady(ctx, 1206), s + "the next run finishes it");
    }

    private static void aStaleOrDuplicateBroadcastChangesNothing() throws Exception {
        String s = "stale: ";
        phone();
        startUpdate(manifest());
        DownloadManager.Row row = current();
        finish(row);
        Map<String, Object> before = new HashMap<>(prefs().values);
        runWorker(row.id + 999);
        check(before.equals(prefs().values) && row.dest.exists(), s + "a worker for another download id does nothing");
        runWorker(row.id);
        runWorker(row.id);
        check(UpdateDownloader.isVerifiedReady(ctx, 1206) && UpdateNotification.prompts == 1,
                s + "a duplicate run after success does nothing");
    }

    private static void aRecordFromBeforePlansFinishes() throws Exception {
        String s = "legacy record: ";
        phone();
        // What the previous build recorded: URL list + index, no plan.
        DownloadManager.Request req = new DownloadManager.Request(android.net.Uri.parse("https://fd/app.apk"));
        req.setDestinationInExternalFilesDir(ctx, null, "firedown.apk");
        long id = dm.enqueue(req);
        prefs().edit().putLong(Keys.UPDATE_DOWNLOAD_ID, id)
                .putString(Keys.UPDATE_DOWNLOAD_URLS, "https://gh/app.apk\nhttps://fd/app.apk")
                .putInt(Keys.UPDATE_DOWNLOAD_URL_INDEX, 1)
                .putString(Keys.UPDATE_DOWNLOAD_SHA, sha(newApk))
                .putString(Keys.UPDATE_DOWNLOAD_NAME, "Firedown 1.2.6")
                .putInt(Keys.UPDATE_DOWNLOAD_VERSION_CODE, 1206).apply();
        finish(dm.rows.get(id));
        runWorker(id);
        check(UpdateDownloader.isVerifiedReady(ctx, 1206)
                && !prefs().contains(Keys.UPDATE_DOWNLOAD_URLS), s + "verified and the old keys cleared");
    }

    private static void anUnreadableRecordIsCleared() throws Exception {
        String s = "unreadable record: ";
        phone();
        DownloadManager.Request req = new DownloadManager.Request(android.net.Uri.parse("https://gh/p"));
        req.setDestinationInExternalFilesDir(ctx, null, "firedown.apk.patch");
        long id = dm.enqueue(req);
        dm.rows.get(id).status = DownloadManager.STATUS_SUCCESSFUL;
        prefs().edit().putLong(Keys.UPDATE_DOWNLOAD_ID, id).apply(); // no sha, no version
        startUpdate(manifest());
        DownloadManager.Row row = current();
        check(row != null && row.id != id && !dm.rows.containsKey(id), s + "dropped, and the update starts afresh");
    }

    private static void clearPendingRemovesTheDownload() throws Exception {
        String s = "clearPending: ";
        phone();
        startUpdate(manifest());
        DownloadManager.Row row = current();
        UpdateDownloader.clearPending(ctx);
        check(!dm.rows.containsKey(row.id) && prefs().getLong(Keys.UPDATE_DOWNLOAD_ID, -1) == -1,
                s + "a download in flight when the app was updated is removed with the record");
    }

    private static void noPatchForAnotherInstalledApk() throws Exception {
        String s = "other install: ";
        phone();
        byte[] mine = oldApk.clone();
        mine[0] ^= 1;
        Files.write(new File(ctx.applicationInfo.sourceDir).toPath(), mine);
        startUpdate(manifest());
        check(current().url.equals("https://gh/x"), s + "an installed APK no patch starts from gets the .xz");
        ctx.applicationInfo.splitSourceDirs = new String[] {"split.apk"};
        prefs().edit().remove(Keys.UPDATE_DOWNLOAD_ID).apply();
        Files.write(new File(ctx.applicationInfo.sourceDir).toPath(), oldApk);
        startUpdate(manifest());
        check(current().url.equals("https://gh/x"), s + "a split install gets no patch");
    }

    private static void aSignatureFailureOnTheApkTriesTheNextMirror() throws Exception {
        String s = "signature: ";
        phone();
        prefs().edit().putInt(Keys.UPDATE_PATCH_FAILED_VERSION, 1206).putInt(Keys.UPDATE_XZ_FAILED_VERSION, 1206).apply();
        startUpdate(manifest());
        DownloadManager.Row row = current();
        check(row.url.equals("https://gh/app.apk"), s + "only the APK left");
        UpdateApkVerifier.signatureOk = false;
        finish(row);
        runWorker(row.id);
        check(current().url.equals("https://fd/app.apk") && !apk().exists(), s + "refused, the next mirror tried");
    }

    /**
     * 1206 verified and waiting; status.json moves on to 1207 before it is
     * installed. The 1207 download is written to the same path the 1206 APK
     * was at: from the moment it starts nothing may offer "1206 is ready"
     * over that partial file.
     */
    private static void aNewerReleaseRetiresTheReadyOne() throws Exception {
        String s = "newer release: ";
        phone();
        verifiedApkOnly();
        check(UpdateDownloader.isVerifiedReady(ctx, 1206), s + "1206 is verified and ready");
        byte[] next = newApk.clone();
        next[2000] ^= 0x55;
        server.put("https://gh/app-1207.apk", next);
        startUpdate("{\"versionCode\":1207,\"versionName\":\"Firedown 1.2.7\","
                + "\"updateUrl\":\"https://gh/app-1207.apk\",\"sha256\":\"" + sha(next) + "\"}");
        DownloadManager.Row row = current();
        check(row != null && row.dest.equals(apk()), s + "1207 downloads to the APK's own path");
        // DownloadManager writes the destination as it downloads.
        Files.write(row.dest.toPath(), Arrays.copyOf(next, next.length / 3));
        check(!UpdateDownloader.isVerifiedReady(ctx, 1206), s + "1206 is no longer offered over the partial file");
        check(!prefs().contains(Keys.UPDATE_READY) && UpdateDownloader.readyVersionCode(ctx) == -1,
                s + "its ready record is gone");
        check(UpdateNotification.cancelled == 1, s + "and the \"Tap to update\" notification that offered it");
        // The belt: a ready record an older build left beside a download.
        prefs().edit().putBoolean(Keys.UPDATE_READY, true).putInt(Keys.UPDATE_READY_VERSION_CODE, 1206).apply();
        check(!UpdateDownloader.isVerifiedReady(ctx, 1206), s + "a ready record beside a recorded download is not trusted");
        prefs().edit().remove(Keys.UPDATE_READY).remove(Keys.UPDATE_READY_VERSION_CODE).apply();
        Files.delete(row.dest.toPath());
        finish(row);
        runWorker(row.id);
        check(UpdateDownloader.isVerifiedReady(ctx, 1207) && Arrays.equals(next, Files.readAllBytes(apk().toPath())),
                s + "then 1207 is verified and ready");
    }

    /** Retry times and claims written under a wrong clock. */
    private static void aClockSetBackNeverHoldsAVersion() throws Exception {
        String s = "clock: ";
        long now = System.currentTimeMillis();
        long hour = 60L * 60L * 1000L;
        check(UpdateDownloader.backoffActive(now, now + hour), s + "a retry an hour away waits");
        check(UpdateDownloader.backoffActive(now, now + 7L * hour), s + "the longest backoff (6 h + jitter) waits");
        check(!UpdateDownloader.backoffActive(now, now + 365L * 24L * hour),
                s + "a retry a year away (written under a clock set ahead) does not");
        check(!UpdateDownloader.backoffActive(now, now - 1), s + "a retry time passed does not");
        check(UpdateDownloader.claimLive(-60_000L), s + "a claim a minute in the future (clock set back a little) holds");
        check(UpdateDownloader.claimLive(-2L * hour),
                s + "a claim two hours in the future holds (a clock set back while a rebuild runs)");
        check(!UpdateDownloader.claimLive(-7L * hour), s + "a claim seven hours in the future does not");
        check(!UpdateDownloader.claimLive(31L * 60_000L), s + "a claim 31 minutes old does not");

        phone();
        prefs().edit().putInt(Keys.UPDATE_FAILED_VERSION_CODE, 1206).putInt(Keys.UPDATE_FAILED_COUNT, 3)
                .putLong(Keys.UPDATE_NEXT_RETRY_AT, now + 365L * 24L * hour).apply();
        startUpdate(manifest());
        check(current() != null, s + "after the clock is corrected the version downloads again");

        // A finished APK-step download claimed "two hours from now".
        phone();
        startUpdate(apkOnlyManifest());
        DownloadManager.Row row = current();
        finish(row);
        prefs().edit().putLong(Keys.UPDATE_DOWNLOAD_FINALIZING_AT, now + 30L * 24L * hour).apply();
        WorkManager.INSTANCE.unique.clear();
        startUpdate(apkOnlyManifest());
        check(current().id == row.id && WorkManager.INSTANCE.unique.containsKey("update_finalize_" + row.id),
                s + "a claim a month in the future is stale: the APK is handed over again, not left forever");
        // While an install copies, the notification is its progress: never taken down.
        phone();
        verifiedApkOnly();
        UpdateInstaller.installing = true;
        UpdateNotification.cancelled = 0;
        UpdateDownloader.clearReady(ctx);
        check(UpdateNotification.cancelled == 0, "install: clearing a ready record while an install runs leaves its progress notification alone");
    }

    /** A verified 1207 waits; status.json goes back to 1206 (the release was pulled). */
    private static void aPulledReleaseStopsBeingOffered() throws Exception {
        String s = "pulled release: ";
        phone();
        verifiedApkOnly();
        prefs().edit().putInt(Keys.UPDATE_READY_VERSION_CODE, 1207).apply();
        UpdateDownloader.onManifestSeen(ctx, 1206);
        check(UpdateDownloader.isVerifiedReady(ctx, 1207) && apk().exists(),
                s + "one sighting changes nothing (a stale mirror can serve the old file)");
        UpdateDownloader.onManifestSeen(ctx, 1206);
        check(UpdateDownloader.isVerifiedReady(ctx, 1207), s + "nor a second one minutes later");
        UpdateDownloader.onManifestSeen(ctx, 1207);
        check(!prefs().contains(Keys.UPDATE_PULLED_SEEN_AT), s + "seeing the release again forgets the first sighting");
        UpdateDownloader.onManifestSeen(ctx, 1206);
        prefs().edit().putLong(Keys.UPDATE_PULLED_SEEN_AT, System.currentTimeMillis() + 3_600_000L).apply();
        UpdateDownloader.onManifestSeen(ctx, 1206);
        check(UpdateDownloader.isVerifiedReady(ctx, 1207), s + "a first sighting in the future (clock moved back) restarts the count");
        prefs().edit().putLong(Keys.UPDATE_PULLED_SEEN_AT,
                System.currentTimeMillis() - UpdateDownloader.PULLED_CONFIRM_MS - 1).apply();
        UpdateDownloader.onManifestSeen(ctx, 1206);
        check(!UpdateDownloader.isVerifiedReady(ctx, 1207) && !apk().exists()
                && !prefs().contains(Keys.UPDATE_READY) && !prefs().contains(Keys.UPDATE_PULLED_SEEN_AT),
                s + "a day later the APK and its record are dropped");
        check(UpdateNotification.cancelled == 1, s + "with the notification that offered it");
        phone();
        verifiedApkOnly();
        UpdateDownloader.onManifestSeen(ctx, 1206);
        check(UpdateDownloader.isVerifiedReady(ctx, 1206) && !prefs().contains(Keys.UPDATE_PULLED_SEEN_AT),
                s + "status.json naming the ready version is no sighting");
    }

    private static void aDisabledDownloadManagerNeverThrows() throws Exception {
        String s = "disabled DownloadManager: ";
        phone();
        startUpdate(apkOnlyManifest());
        dm.removeThrows = true;
        boolean threw = false;
        try {
            UpdateDownloader.clearPending(ctx);
        } catch (RuntimeException e) {
            threw = true;
        }
        check(!threw && prefs().getLong(Keys.UPDATE_DOWNLOAD_ID, -1) == -1,
                s + "clearPending (run on the main thread after an update) clears the record without throwing");
    }

    // ── the phone, the server, the steps ─────────────────────────────────

    private static void phone() throws IOException {
        File dir = Files.createTempDirectory(root.toPath(), "phone").toFile();
        ctx = new Context();
        ctx.externalFilesDir = new File(dir, "files");
        ctx.externalFilesDir.mkdirs();
        ctx.filesDir = ctx.externalFilesDir;
        dm = new DownloadManager();
        ctx.downloadManager = dm;
        File installed = new File(dir, "base.apk");
        Files.write(installed.toPath(), oldApk);
        ctx.applicationInfo.sourceDir = installed.getPath();
        PreferenceManager.prefs = new SharedPreferences();
        WorkManager.INSTANCE.unique.clear();
        WorkManager.INSTANCE.log.clear();
        UpdateNotification.prompts = 0;
        UpdateNotification.cancelled = 0;
        UpdateInstaller.installing = false;
        UpdateApkVerifier.signatureOk = true;
        server.clear();
        server.put("https://gh/p", patch);
        server.put("https://m/p", patch);
        server.put("https://gh/x", xz);
        server.put("https://m/x", xz);
        server.put("https://gh/app.apk", newApk);
        server.put("https://fd/app.apk", newApk);
    }

    private static String manifest() {
        return manifest(sha(patch), patch.length);
    }

    private static String manifest(String patchSha, long patchSize) {
        return "{\"versionCode\":1206,\"versionName\":\"Firedown 1.2.6\","
                + "\"updateUrl\":\"https://gh/app.apk\",\"updateUrlFallbacks\":[\"https://fd/app.apk\"],"
                + "\"sha256\":\"" + sha(newApk) + "\","
                + "\"patches\":[{\"fromVersionCode\":1205,\"fromSha256\":\"" + sha(oldApk) + "\","
                + "\"urls\":[\"https://gh/p\",\"https://m/p\"],\"sha256\":\"" + patchSha + "\",\"size\":" + patchSize + "}],"
                + "\"compressed\":{\"format\":\"xz\",\"urls\":[\"https://gh/x\",\"https://m/x\"],"
                + "\"sha256\":\"" + sha(xz) + "\",\"size\":" + xz.length + "}}";
    }

    /** 1206 from the APK alone (no patch, no .xz). */
    private static String apkOnlyManifest() {
        return "{\"versionCode\":1206,\"versionName\":\"Firedown 1.2.6\","
                + "\"updateUrl\":\"https://gh/app.apk\",\"sha256\":\"" + sha(newApk) + "\"}";
    }

    /** Downloads and verifies 1206 from the APK step. */
    private static void verifiedApkOnly() throws IOException {
        startUpdate(apkOnlyManifest());
        DownloadManager.Row row = current();
        finish(row);
        runWorker(row.id);
    }

    /** What UpdateWorker does for a newer release that isn't ready yet. */
    private static void startUpdate(String json) {
        UpdateManifest m = UpdateManifest.parse(json);
        String installedSha = m.patches.isEmpty() ? null : UpdateInstalledApk.sha256(ctx);
        UpdatePlan plan = UpdatePlan.build(m, installedSha, UpdateDownloader.failedKinds(ctx, m.versionCode));
        UpdateDownloader.start(ctx, plan, m.sha256, m.versionName, m.versionCode);
    }

    private static DownloadManager.Row current() {
        return dm.rows.get(prefs().getLong(Keys.UPDATE_DOWNLOAD_ID, -1));
    }

    /** DownloadManager finishing a download with what the server serves. */
    private static void finish(DownloadManager.Row row) throws IOException {
        byte[] body = server.get(row.url);
        if (body == null) {
            row.status = DownloadManager.STATUS_FAILED;
            return;
        }
        Files.write(row.dest.toPath(), body);
        row.status = DownloadManager.STATUS_SUCCESSFUL;
    }

    private static UpdateFinalizeWorker worker(long id) {
        return new UpdateFinalizeWorker(ctx, new WorkerParameters(
                new Data.Builder().putLong(UpdateFinalizeWorker.KEY_DOWNLOAD_ID, id).build()));
    }

    private static ListenableWorker.Result runWorker(long id) {
        return worker(id).doWork();
    }

    private static File apk() {
        return Preferences.getUpdateApkFile(ctx);
    }

    private static SharedPreferences prefs() {
        return PreferenceManager.prefs;
    }

    private static String sha(byte[] b) {
        try {
            return UpdatePatch.toHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] xz(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XZOutputStream x = new XZOutputStream(out, new LZMA2Options())) {
            x.write(data);
        }
        return out.toByteArray();
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            pass++;
            System.out.println("ok   " + what);
        } else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }
}

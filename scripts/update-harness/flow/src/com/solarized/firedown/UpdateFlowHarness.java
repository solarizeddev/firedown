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
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
        aDisabledDownloadManagerDownloadsDirectly();
        noDownloadManagerAtAll();
        noExternalStorageDownloadsIntoTheFilesDir();
        aDownloadManagerDisabledMidDownload();
        anInterruptedDirectDownloadResumes();
        aStoppedDirectRunKeepsItsFile();
        aServerIgnoringRangeStartsOver();
        aWrongRangeStartsOver();
        aRefusedDirectDownloadTriesTheNextMirror();
        aDirectDownloadThatNeverConnectsGivesUp();
        aServerErrorIsRetriedNotSkipped();
        aDirectFileLongerThanStatusJsonSays();
        aStaleDirectRunWritesNothing();
        aDirectDownloadInFlightIsNotStartedTwice();
        everyDirectStepFailingBacksOff();
        contentRangeParsing();

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

    // ── direct downloads (the Download Manager can't take them) ──────────

    private static void aDisabledDownloadManagerDownloadsDirectly() throws Exception {
        String s = "direct: ";
        phone();
        dm.disabled = true;
        startUpdate(manifest());
        long id = recordedId();
        check(UpdateDownloader.isDirect(id) && dm.rows.isEmpty(),
                s + "a disabled Download Manager: the download is recorded as a direct one, no row");
        check(WorkManager.INSTANCE.unique.containsKey("update_direct_" + id), s + "and its worker is enqueued");
        FakeHttp http = new FakeHttp();
        check(runDirect(id, http) == UpdateDirectDownload.Outcome.DONE && http.froms.equals(List.of(0L)),
                s + "one run downloads the patch, no Range");
        check(UpdateDownloader.fileFor(ctx, UpdatePlan.Kind.PATCH).isFile()
                        && !UpdateDownloader.directPartFile(ctx, id).exists(),
                s + "the part file is moved to the patch's own file");
        check(WorkManager.INSTANCE.unique.containsKey("update_finalize_" + id), s + "and handed to the finalize worker");
        runWorker(id);
        check(UpdateDownloader.isVerifiedReady(ctx, 1206) && Arrays.equals(newApk, Files.readAllBytes(apk().toPath())),
                s + "rebuilt and verified as a Download Manager download is");
        check(UpdateNotification.prompts == 1 && !prefs().contains(Keys.UPDATE_DIRECT_DONE)
                        && prefs().getLong(Keys.UPDATE_DOWNLOAD_ID, -1) == -1,
                s + "the prompt is posted, the record and its direct keys cleared");
        check(noPartFiles(), s + "no part file is left");
    }

    private static void noDownloadManagerAtAll() throws Exception {
        String s = "no Download Manager: ";
        phone();
        ctx.downloadManager = null;
        startUpdate(apkOnlyManifest());
        long id = recordedId();
        check(UpdateDownloader.isDirect(id), s + "the APK is downloaded directly");
        check(runDirect(id, new FakeHttp()) == UpdateDirectDownload.Outcome.DONE, s + "in one run");
        runWorker(id);
        check(UpdateDownloader.isVerifiedReady(ctx, 1206) && apk().isFile(), s + "verified; the APK stays in place");
    }

    private static void noExternalStorageDownloadsIntoTheFilesDir() throws Exception {
        String s = "no external files dir: ";
        phone();
        File internal = new File(ctx.externalFilesDir.getParentFile(), "internal");
        internal.mkdirs();
        ctx.externalFilesDir = null;
        ctx.filesDir = internal;
        startUpdate(manifest());
        long id = recordedId();
        check(UpdateDownloader.isDirect(id) && dm.rows.isEmpty(),
                s + "the Download Manager can't write there: the download is direct");
        check(UpdateDownloader.directPartFile(ctx, id).getParentFile().equals(internal),
                s + "into the app's own files dir");
        FakeHttp http = new FakeHttp();
        runDirect(id, http);
        check(http.urls.equals(List.of("https://gh/p")), s + "the patch is still tried first (room is measured there)");
        runWorker(id);
        check(UpdateDownloader.isVerifiedReady(ctx, 1206) && new File(internal, "firedown.apk").isFile(),
                s + "verified in the files dir");
    }

    private static void aDownloadManagerDisabledMidDownload() throws Exception {
        String s = "disabled mid-download: ";
        phone();
        startUpdate(apkOnlyManifest());
        long first = recordedId();
        check(!UpdateDownloader.isDirect(first) && dm.rows.containsKey(first), s + "a Download Manager download");
        dm.disabled = true;
        dm.removeThrows = true;
        boolean threw = false;
        try {
            startUpdate(apkOnlyManifest());
        } catch (RuntimeException e) {
            threw = true;
        }
        long second = recordedId();
        check(!threw && UpdateDownloader.isDirect(second),
                s + "the next check can't read it and downloads again, directly, without throwing");
        check(runDirect(second, new FakeHttp()) == UpdateDirectDownload.Outcome.DONE, s + "which finishes");
        runWorker(second);
        check(UpdateDownloader.isVerifiedReady(ctx, 1206), s + "and is verified");
    }

    private static void anInterruptedDirectDownloadResumes() throws Exception {
        String s = "resume: ";
        phone();
        dm.disabled = true;
        startUpdate(apkOnlyManifest());
        long id = recordedId();
        FakeHttp http = new FakeHttp();
        http.cutAfter = 150_000;
        check(runDirect(id, http) == UpdateDirectDownload.Outcome.RETRY, s + "a dropped connection: run again");
        File part = UpdateDownloader.directPartFile(ctx, id);
        check(part.length() == 150_000 && !prefs().contains(Keys.UPDATE_DIRECT_STALLS),
                s + "the bytes so far are kept, and progress is no stall");
        check(runDirect(id, http) == UpdateDirectDownload.Outcome.DONE && http.froms.equals(List.of(0L, 150_000L)),
                s + "the next run asks for the rest only");
        runWorker(id);
        check(UpdateDownloader.isVerifiedReady(ctx, 1206) && Arrays.equals(newApk, Files.readAllBytes(apk().toPath())),
                s + "the joined file is the APK byte for byte");
    }

    private static void aStoppedDirectRunKeepsItsFile() throws Exception {
        String s = "stopped run: ";
        phone();
        dm.disabled = true;
        startUpdate(apkOnlyManifest());
        long id = recordedId();
        FakeHttp http = new FakeHttp();
        http.stopAfter = 100_000;
        check(runDirect(id, http) == UpdateDirectDownload.Outcome.RETRY, s + "stopped by the system: run again");
        check(UpdateDownloader.directPartFile(ctx, id).length() == 100_000
                        && !prefs().contains(Keys.UPDATE_DIRECT_STALLS),
                s + "what it wrote is kept; not a stall");
        stopFlag = false;
        check(runDirect(id, http) == UpdateDirectDownload.Outcome.DONE && http.froms.get(1) == 100_000L,
                s + "the next run resumes");
        runWorker(id);
        check(UpdateDownloader.isVerifiedReady(ctx, 1206), s + "verified");
    }

    private static void aServerIgnoringRangeStartsOver() throws Exception {
        String s = "Range ignored: ";
        phone();
        dm.disabled = true;
        startUpdate(apkOnlyManifest());
        long id = recordedId();
        FakeHttp http = new FakeHttp();
        http.cutAfter = 150_000;
        runDirect(id, http);
        http.ignoreRange = true;
        check(runDirect(id, http) == UpdateDirectDownload.Outcome.DONE, s + "a 200 to a Range request");
        runWorker(id);
        check(UpdateDownloader.isVerifiedReady(ctx, 1206) && Arrays.equals(newApk, Files.readAllBytes(apk().toPath())),
                s + "the whole file replaces the kept part, nothing appended");
    }

    private static void aWrongRangeStartsOver() throws Exception {
        String s = "wrong range: ";
        phone();
        dm.disabled = true;
        startUpdate(apkOnlyManifest());
        long id = recordedId();
        FakeHttp http = new FakeHttp();
        http.cutAfter = 150_000;
        runDirect(id, http);
        http.wrongStart = 10;
        check(runDirect(id, http) == UpdateDirectDownload.Outcome.RETRY
                        && !UpdateDownloader.directPartFile(ctx, id).exists()
                        && prefs().getInt(Keys.UPDATE_DIRECT_STALLS, 0) == 1,
                s + "a 206 for another range: the part is dropped, retried");
        http.wrongStart = -1;
        check(runDirect(id, http) == UpdateDirectDownload.Outcome.DONE && http.froms.get(2) == 0L,
                s + "the next run starts from the first byte");
        runWorker(id);
        check(UpdateDownloader.isVerifiedReady(ctx, 1206), s + "verified");
    }

    private static void aRefusedDirectDownloadTriesTheNextMirror() throws Exception {
        String s = "refused: ";
        phone();
        dm.disabled = true;
        server.remove("https://gh/p");
        startUpdate(manifest());
        long first = recordedId();
        FakeHttp http = new FakeHttp();
        check(runDirect(first, http) == UpdateDirectDownload.Outcome.FINISHED, s + "a 404 ends the attempt");
        long second = recordedId();
        check(second != first && UpdateDownloader.isDirect(second)
                        && WorkManager.INSTANCE.cancelled.contains("update_direct_" + first)
                        && WorkManager.INSTANCE.unique.containsKey("update_direct_" + second),
                s + "the next mirror is a new direct download; the old work is cancelled");
        runDirect(second, http);
        check(http.urls.get(1).equals("https://m/p"), s + "of the same patch");
        runWorker(second);
        check(UpdateDownloader.isVerifiedReady(ctx, 1206), s + "verified");
    }

    private static void aDirectDownloadThatNeverConnectsGivesUp() throws Exception {
        String s = "stalls: ";
        phone();
        dm.disabled = true;
        startUpdate(manifest());
        long id = recordedId();
        FakeHttp http = new FakeHttp();
        http.failConnects = 100;
        for (int i = 1; i < UpdateDirectDownload.MAX_STALLS; i++) {
            runDirect(id, http);
        }
        check(recordedId() == id && prefs().getInt(Keys.UPDATE_DIRECT_STALLS, 0) == UpdateDirectDownload.MAX_STALLS - 1,
                s + (UpdateDirectDownload.MAX_STALLS - 1) + " runs that never connect keep the attempt");
        // Progress in between starts the count over.
        http.failConnects = 0;
        http.cutAfter = 50;
        check(runDirect(id, http) == UpdateDirectDownload.Outcome.RETRY && !prefs().contains(Keys.UPDATE_DIRECT_STALLS),
                s + "a run that downloads something resets the count");
        http.failConnects = 100;
        for (int i = 1; i < UpdateDirectDownload.MAX_STALLS; i++) {
            runDirect(id, http);
        }
        check(recordedId() == id, s + "so the count starts again");
        check(runDirect(id, http) == UpdateDirectDownload.Outcome.FINISHED && recordedId() != id,
                s + UpdateDirectDownload.MAX_STALLS + " in a row: the next mirror");
        check(!UpdateDownloader.directPartFile(ctx, id).exists(), s + "the abandoned part file is deleted");
    }

    private static void aServerErrorIsRetriedNotSkipped() throws Exception {
        String s = "server error: ";
        phone();
        dm.disabled = true;
        startUpdate(manifest());
        long id = recordedId();
        FakeHttp http = new FakeHttp();
        http.code = 503;
        check(runDirect(id, http) == UpdateDirectDownload.Outcome.RETRY && recordedId() == id
                        && prefs().getInt(Keys.UPDATE_DIRECT_STALLS, 0) == 1,
                s + "a 503 is retried as a stall, not a refusal");
    }

    private static void aDirectFileLongerThanStatusJsonSays() throws Exception {
        String s = "too long: ";
        phone();
        dm.disabled = true;
        byte[] longer = Arrays.copyOf(patch, patch.length + 10);
        server.put("https://gh/p", longer);
        startUpdate(manifest());
        long id = recordedId();
        FakeHttp http = new FakeHttp();
        http.noLength = true;
        check(runDirect(id, http) == UpdateDirectDownload.Outcome.FINISHED && recordedId() != id
                        && !UpdateDownloader.directPartFile(ctx, id).exists(),
                s + "a body past status.json's size ends the attempt, the part deleted");
        phone();
        dm.disabled = true;
        server.put("https://gh/p", longer);
        startUpdate(manifest());
        id = recordedId();
        FakeHttp honest = new FakeHttp();
        check(runDirect(id, honest) == UpdateDirectDownload.Outcome.FINISHED && recordedId() != id
                        && honest.bodyReads == 0,
                s + "and so does a Content-Length that says so, before a byte is read");
    }

    private static void aStaleDirectRunWritesNothing() throws Exception {
        String s = "stale run: ";
        phone();
        dm.disabled = true;
        startUpdate(manifest());
        long id = recordedId();
        FakeHttp http = new FakeHttp();
        http.cutAfter = 100;
        runDirect(id, http);
        UpdateDownloader.clearPending(ctx);
        check(!UpdateDownloader.directPartFile(ctx, id).exists()
                        && WorkManager.INSTANCE.cancelled.contains("update_direct_" + id),
                s + "clearPending cancels the work and deletes its part file");
        check(runDirect(id, new FakeHttp()) == UpdateDirectDownload.Outcome.STALE
                        && !UpdateDownloader.fileFor(ctx, UpdatePlan.Kind.PATCH).exists(),
                s + "a run of it that starts anyway writes nothing");
        // A run that was already downloading when the record moved on.
        phone();
        dm.disabled = true;
        startUpdate(manifest());
        id = recordedId();
        File part = UpdateDownloader.directPartFile(ctx, id);
        Files.write(part.toPath(), patch);
        prefs().edit().putLong(Keys.UPDATE_DOWNLOAD_ID, -7L).apply();
        check(!UpdateDownloader.completeDirect(ctx, id, part) && !part.exists()
                        && !UpdateDownloader.fileFor(ctx, UpdatePlan.Kind.PATCH).exists(),
                s + "a finished run whose record moved on is not handed over, nor moved into place");
    }

    private static void aDirectDownloadInFlightIsNotStartedTwice() throws Exception {
        String s = "direct in flight: ";
        phone();
        dm.disabled = true;
        startUpdate(manifest());
        long id = recordedId();
        WorkManager.INSTANCE.unique.clear();
        startUpdate(manifest());
        check(recordedId() == id && WorkManager.INSTANCE.unique.containsKey("update_direct_" + id),
                s + "a check keeps the download, and enqueues its lost work again (KEEP)");
        FakeHttp http = new FakeHttp();
        http.cutAfter = 100;
        runDirect(id, http);
        startUpdate(manifest());
        check(recordedId() == id && UpdateDownloader.directPartFile(ctx, id).length() == 100,
                s + "its part file survives a check");
        runDirect(id, http);
        WorkManager.INSTANCE.unique.clear();
        startUpdate(manifest());
        check(recordedId() == id && WorkManager.INSTANCE.unique.containsKey("update_finalize_" + id)
                        && !WorkManager.INSTANCE.unique.containsKey("update_direct_" + id),
                s + "finished but unclaimed: handed to the finalize worker, not downloaded again");
        runWorker(id);
        check(UpdateDownloader.isVerifiedReady(ctx, 1206), s + "verified");
    }

    private static void everyDirectStepFailingBacksOff() throws Exception {
        String s = "direct, all fail: ";
        phone();
        dm.disabled = true;
        server.clear();
        startUpdate(manifest());
        int runs = 0;
        while (prefs().getLong(Keys.UPDATE_DOWNLOAD_ID, -1) != -1 && runs < 20) {
            runDirect(recordedId(), new FakeHttp());
            runs++;
        }
        check(runs == 6, s + "two patch mirrors, two .xz mirrors, two APK URLs");
        check(UpdateDownloader.isBackingOff(ctx, 1206) && WorkManager.INSTANCE.unique.containsKey("update_retry"),
                s + "then the backoff, as for the Download Manager");
        boolean anyDirect = false;
        for (String name : WorkManager.INSTANCE.unique.keySet()) {
            anyDirect |= name.startsWith("update_direct_");
        }
        check(!anyDirect && noPartFiles(), s + "no direct work and no part file left");
    }

    private static void contentRangeParsing() {
        String s = "Content-Range: ";
        check(Arrays.equals(UpdateDirectDownload.parseContentRange("bytes 100-199/1000"), new long[] {100, 1000}),
                s + "start and total");
        check(Arrays.equals(UpdateDirectDownload.parseContentRange("bytes 5-9/*"), new long[] {5, -1}),
                s + "unknown total");
        check(Arrays.equals(UpdateDirectDownload.parseContentRange("bytes */1000"), new long[] {-1, -1})
                        && Arrays.equals(UpdateDirectDownload.parseContentRange(null), new long[] {-1, -1})
                        && Arrays.equals(UpdateDirectDownload.parseContentRange("items 1-2/3"), new long[] {-1, -1})
                        && Arrays.equals(UpdateDirectDownload.parseContentRange("bytes x-2/3"), new long[] {-1, -1}),
                s + "anything else is unreadable");
    }

    // ── a fake update server for direct downloads ────────────────────────

    private static volatile boolean stopFlag;

    private static final class FakeHttp implements UpdateDirectDownload.Http {
        final List<Long> froms = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        int code;
        boolean ignoreRange;
        boolean noLength;
        long wrongStart = -1;
        int failConnects;
        /** Throw after this many body bytes (once). */
        long cutAfter = -1;
        /** Set the stop flag after this many body bytes (once). */
        long stopAfter = -1;
        /** Body reads served, over all answers. */
        int bodyReads;

        @Override
        public UpdateDirectDownload.Answer open(String url, long from) throws IOException {
            froms.add(from);
            urls.add(url);
            if (failConnects > 0) {
                failConnects--;
                throw new IOException("connection refused");
            }
            if (code != 0) {
                return new UpdateDirectDownload.Answer(code, -1, -1, body(new byte[0]), null);
            }
            byte[] file = server.get(url);
            if (file == null) {
                return new UpdateDirectDownload.Answer(404, -1, -1, body(new byte[0]), null);
            }
            if (from > 0 && !ignoreRange) {
                long start = wrongStart >= 0 ? wrongStart : from;
                byte[] rest = Arrays.copyOfRange(file, (int) start, file.length);
                return new UpdateDirectDownload.Answer(206, start, file.length, body(rest), null);
            }
            return new UpdateDirectDownload.Answer(200, -1, noLength ? -1 : file.length, body(file), null);
        }

        private InputStream body(byte[] data) {
            long cut = cutAfter;
            long stop = stopAfter;
            cutAfter = -1;
            stopAfter = -1;
            return new InputStream() {
                int pos;

                @Override
                public int read() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    bodyReads++;
                    if (cut >= 0 && pos >= cut) {
                        throw new IOException("connection reset");
                    }
                    if (pos >= data.length) {
                        return -1;
                    }
                    int n = Math.min(len, data.length - pos);
                    if (cut >= 0) {
                        n = (int) Math.min(n, cut - pos);
                    }
                    if (stop >= 0) {
                        n = (int) Math.min(n, Math.max(1, stop - pos));
                    }
                    System.arraycopy(data, pos, b, off, n);
                    pos += n;
                    if (stop >= 0 && pos >= stop) {
                        stopFlag = true;
                    }
                    return n;
                }
            };
        }
    }

    private static UpdateDirectDownload.Outcome runDirect(long id, FakeHttp http) {
        return UpdateDirectDownload.run(ctx, id, http, () -> stopFlag);
    }

    private static long recordedId() {
        return prefs().getLong(Keys.UPDATE_DOWNLOAD_ID, -1);
    }

    private static boolean noPartFiles() {
        File dir = apk().getParentFile();
        String[] names = dir.list();
        if (names == null) {
            return true;
        }
        for (String n : names) {
            if (n.startsWith("firedown.apk.dl")) {
                return false;
            }
        }
        return true;
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
        WorkManager.INSTANCE.cancelled.clear();
        stopFlag = false;
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

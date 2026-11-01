package com.solarized.firedown;

import android.app.DownloadManager;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.solarized.firedown.update.UpdatePatch;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Turns a finished update download into a verified APK ready to install.
 *
 * For the APK step that is the two checks it always had: the file's SHA-256
 * is status.json's, and it is signed with the installed app's key. For a
 * patch or an .xz it first checks the downloaded file is the one status.json
 * named, rebuilds the APK from it (the patch also reads the installed APK),
 * and then runs the same two checks on the result. Nothing is offered for
 * install that did not pass them, however it was produced.
 *
 * A worker, not the broadcast receiver: rebuilding and hashing 200 MB takes
 * seconds to tens of seconds on a phone, past what a receiver may hold.
 * Unique work per download id, so a duplicate broadcast runs nothing twice.
 *
 * Outcomes go back to UpdateDownloader: a wrong or failed download tries the
 * next mirror ({@code onAttemptFailed}); a patch or .xz that does not rebuild
 * the expected APK skips to the next kind ({@code onStepFailed}); a failure of
 * this device (storage full) waits and starts over ({@code backOff}).
 */
public class UpdateFinalizeWorker extends Worker {

    private static final String TAG = "UpdateFinalizeWorker";

    static final String KEY_DOWNLOAD_ID = "download_id";

    /** Room left over beyond the APK being written, so the phone is not filled to the byte. */
    private static final long SPACE_MARGIN_BYTES = 32L * 1024L * 1024L;

    public UpdateFinalizeWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void enqueue(Context context, long downloadId) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(UpdateFinalizeWorker.class)
                .setInputData(new Data.Builder().putLong(KEY_DOWNLOAD_ID, downloadId).build())
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                "update_finalize_" + downloadId, ExistingWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        long downloadId = getInputData().getLong(KEY_DOWNLOAD_ID, -1);
        UpdateDownloader.Pending pending = UpdateDownloader.beginFinalize(context, downloadId);
        if (pending == null) {
            // A stale or duplicate completion: the record moved on.
            return Result.success();
        }
        if (!isDownloadSuccessful(context, downloadId)) {
            Log.w(TAG, "update download did not complete successfully");
            UpdateDownloader.onAttemptFailed(context);
            return Result.success();
        }

        UpdatePlan.Step step = pending.plan.current();
        File downloaded = UpdateDownloader.fileFor(context, step.kind);
        if (!downloaded.isFile()) {
            Log.w(TAG, "update download reported complete but its file is missing");
            UpdateDownloader.onAttemptFailed(context);
            return Result.success();
        }

        File apk = Preferences.getUpdateApkFile(context);
        if (step.kind != UpdatePlan.Kind.APK) {
            if (!UpdateApkVerifier.verifySha256(downloaded, step.sha256)) {
                Log.e(TAG, step.kind + " download is not the file status.json named — discarding");
                downloaded.delete();
                UpdateDownloader.onAttemptFailed(context);
                return Result.success();
            }
            Result rebuilt = rebuild(context, pending, step, downloaded, apk);
            if (rebuilt != null) {
                return rebuilt;
            }
        }

        if (!UpdateApkVerifier.verifySha256(apk, pending.apkSha256)) {
            Log.e(TAG, "update APK digest mismatch — discarding");
            apk.delete();
            failStep(context, step);
            return Result.success();
        }
        if (!UpdateApkVerifier.verifySignature(context, apk)) {
            Log.e(TAG, "update APK signature mismatch — discarding");
            apk.delete();
            failStep(context, step);
            return Result.success();
        }

        // Verified — promote to "ready" (clears the download record) and surface
        // it. The (silent) notification is ALWAYS posted, so the update is
        // reachable whether or not the app is open; the in-app sheet
        // (UpdateAvailableSheet, shown on resume) is the additional prominent
        // surface while the app is in use.
        UpdateDownloader.markReady(context, pending.versionCode, pending.name);
        UpdateNotification.showInstallPrompt(context, pending.name);
        return Result.success();
    }

    /**
     * Writes the APK from a patch or an .xz. Returns null when the APK is in
     * place for the checks, else the worker's result (the failure is already
     * reported to UpdateDownloader).
     */
    private Result rebuild(Context context, UpdateDownloader.Pending pending, UpdatePlan.Step step,
                           File downloaded, File apk) {
        File part = UpdateDownloader.partFile(context);
        part.delete();
        try {
            File installed = null;
            long needed;
            if (step.kind == UpdatePlan.Kind.PATCH) {
                installed = UpdateInstalledApk.file(context);
                if (installed == null) {
                    throw new UpdatePatch.PatchException("the installed APK can't be read");
                }
                needed = UpdatePatch.readHeader(downloaded).newSize;
            } else {
                // The .xz index knows the size, but the old APK is a close guess.
                File installedApk = UpdateInstalledApk.file(context);
                long base = installedApk == null ? 0 : installedApk.length();
                needed = Math.max(base, downloaded.length() * 4);
            }
            File dir = part.getParentFile();
            if (dir != null && dir.getUsableSpace() < needed + SPACE_MARGIN_BYTES) {
                Log.w(TAG, "not enough free space to rebuild the update");
                UpdateDownloader.backOff(context);
                return Result.success();
            }

            String sha;
            if (step.kind == UpdatePlan.Kind.PATCH) {
                sha = UpdatePatch.apply(installed, downloaded, part, step.fromSha256, this::isStopped);
            } else {
                sha = UpdatePatch.unxz(downloaded, part, UpdatePatch.MAX_FILE_SIZE, this::isStopped);
            }
            if (!sha.equalsIgnoreCase(pending.apkSha256)) {
                throw new UpdatePatch.PatchException(step.kind + " rebuilt " + sha
                        + ", status.json names " + pending.apkSha256);
            }
            Files.move(part.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING);
            downloaded.delete();
            Log.d(TAG, "rebuilt the update from its " + step.kind);
            return null;
        } catch (UpdatePatch.PatchException e) {
            Log.e(TAG, step.kind + " did not rebuild the update: " + e.getMessage());
            part.delete();
            downloaded.delete();
            UpdateDownloader.onStepFailed(context);
            return Result.success();
        } catch (InterruptedIOException e) {
            // Stopped by the system: WorkManager runs this again, and the claim
            // in UpdateDownloader keeps the download in place meanwhile.
            part.delete();
            return Result.retry();
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "could not rebuild the update on this device", e);
            part.delete();
            UpdateDownloader.backOff(context);
            return Result.success();
        }
    }

    /** A wrong APK: from the APK step, try the next mirror; from a patch or .xz, the next kind. */
    private static void failStep(Context context, UpdatePlan.Step step) {
        if (step.kind == UpdatePlan.Kind.APK) {
            UpdateDownloader.onAttemptFailed(context);
        } else {
            UpdateDownloader.onStepFailed(context);
        }
    }

    private static boolean isDownloadSuccessful(Context context, long downloadId) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            return false;
        }
        return UpdateDownloader.queryStatus(dm, downloadId) == DownloadManager.STATUS_SUCCESSFUL;
    }
}

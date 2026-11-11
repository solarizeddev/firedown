package com.solarized.firedown;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
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
 * A direct download (UpdateDirectDownload) is handed over the same way.
 *
 * Outcomes go back to UpdateDownloader, each naming the download it is about
 * (a stale run can't move a newer record): a wrong or failed download tries
 * the next mirror ({@code onAttemptFailed}); a patch or .xz that does not
 * rebuild the expected APK, or needs more memory than this phone has, skips
 * to the next kind ({@code onStepFailed}); a failure of this device (storage
 * full, a file it can't read) waits and starts over, and skips the step if it
 * happens again ({@code onDeviceFailed}).
 */
public class UpdateFinalizeWorker extends Worker {

    private static final String TAG = "UpdateFinalizeWorker";

    static final String KEY_DOWNLOAD_ID = "download_id";

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

    /** One update check, now (or as soon as there is a network). */
    private static void enqueueCheck(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(UpdateWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                "superseded_update_check", ExistingWorkPolicy.REPLACE, request);
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
            UpdateDownloader.onAttemptFailed(context, downloadId);
            return Result.success();
        }

        UpdatePlan.Step step = pending.plan.current();
        File downloaded = UpdateDownloader.fileFor(context, step.kind);
        if (!downloaded.isFile()) {
            Log.w(TAG, "update download reported complete but its file is missing");
            UpdateDownloader.onAttemptFailed(context, downloadId);
            return Result.success();
        }

        File apk = Preferences.getUpdateApkFile(context);
        if (step.kind != UpdatePlan.Kind.APK) {
            if (!UpdateApkVerifier.verifySha256(downloaded, step.sha256)) {
                Log.e(TAG, step.kind + " download is not the file status.json named — discarding");
                downloaded.delete();
                UpdateDownloader.onAttemptFailed(context, downloadId);
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
            failStep(context, step, downloadId);
            return Result.success();
        }
        if (!UpdateApkVerifier.verifySignature(context, apk)) {
            Log.e(TAG, "update APK signature mismatch — discarding");
            apk.delete();
            failStep(context, step, downloadId);
            return Result.success();
        }

        // Verified — promote to "ready" (clears the download record) and surface
        // it. The (silent) notification is ALWAYS posted, so the update is
        // reachable whether or not the app is open; the in-app sheet
        // (UpdateAvailableSheet, shown on resume) is the additional prominent
        // surface while the app is in use.
        if (!UpdateDownloader.markReady(context, downloadId, pending.versionCode, pending.name)) {
            return Result.success();
        }
        if (UpdateDownloader.newerReleaseSeen(context, pending.versionCode)) {
            // A newer release came out while this one was being checked: the
            // next check replaces it, so ask for that check now rather than
            // announce a version about to be thrown away. Until then it stays
            // ready, in case the newer one's files can't be had.
            Log.d(TAG, "a newer release was seen while " + pending.versionCode + " was checked");
            enqueueCheck(context);
            return Result.success();
        }
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
                    throw new IOException("the installed APK can't be read");
                }
                needed = UpdatePatch.readHeader(downloaded).newSize;
            } else {
                // The .xz index knows the size; the installed APK + a tenth
                // is a close guess, and the cap below bounds a wrong one.
                long expected = UpdateDownloader.expectedApkBytes(context);
                needed = expected > 0 ? expected : downloaded.length() * 3;
            }
            File dir = part.getParentFile();
            if (dir != null && dir.getUsableSpace() < needed + UpdateDownloader.SPACE_MARGIN_BYTES) {
                Log.w(TAG, "not enough free space to rebuild the update");
                UpdateDownloader.onDeviceFailed(context, pending.downloadId);
                return Result.success();
            }

            String sha;
            if (step.kind == UpdatePlan.Kind.PATCH) {
                // Null: the applier hashes the installed APK itself, so the
                // patch's base is checked against the real file, not against
                // the cached hash the plan was built from.
                sha = UpdatePatch.apply(installed, downloaded, part, null, this::isStopped);
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
            UpdateDownloader.onStepFailed(context, pending.downloadId);
            return Result.success();
        } catch (OutOfMemoryError e) {
            // The applier needs ~32 MB of heap; a phone that can't give it
            // will not manage the next time either.
            part.delete();
            downloaded.delete();
            UpdateDownloader.onStepFailed(context, pending.downloadId);
            return Result.success();
        } catch (InterruptedIOException e) {
            // Stopped by the system: WorkManager runs this again, and the claim
            // in UpdateDownloader keeps the download in place meanwhile.
            part.delete();
            return Result.retry();
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "could not rebuild the update on this device", e);
            part.delete();
            UpdateDownloader.onDeviceFailed(context, pending.downloadId);
            return Result.success();
        }
    }

    /** A wrong APK: from the APK step, try the next mirror; from a patch or .xz, the next kind. */
    private static void failStep(Context context, UpdatePlan.Step step, long downloadId) {
        if (step.kind == UpdatePlan.Kind.APK) {
            UpdateDownloader.onAttemptFailed(context, downloadId);
        } else {
            UpdateDownloader.onStepFailed(context, downloadId);
        }
    }

    private static boolean isDownloadSuccessful(Context context, long downloadId) {
        return UpdateDownloader.isDownloadComplete(context, downloadId);
    }
}

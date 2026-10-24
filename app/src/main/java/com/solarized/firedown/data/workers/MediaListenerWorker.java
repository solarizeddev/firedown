package com.solarized.firedown.data.workers;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.manager.RunnableManager;
import com.solarized.firedown.manager.UrlParser;
import com.solarized.firedown.p2pshare.P2pShareController;
import com.solarized.firedown.utils.GalleryPublisher;
import com.solarized.firedown.utils.MessageHelper;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import androidx.preference.PreferenceManager;

@HiltWorker
public class MediaListenerWorker extends Worker {

    private static final String TAG = "MediaListenerWorker";

    private final DownloadDataRepository mDownloadDataRepository;

    @AssistedInject
    public MediaListenerWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters workerParams,
            DownloadDataRepository downloadDataRepository) {
        super(context, workerParams);
        // Hilt now injects the repository directly
        this.mDownloadDataRepository = downloadDataRepository;
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Starting media existence check...");

        // File.exists() == false means EITHER "file deleted" OR "file present
        // but unreadable" — and the second is exactly the state right after a
        // backup-restored reinstall: on Android <= 12 every restored entry's
        // file is unreadable until the user grants READ_EXTERNAL_STORAGE, and
        // on 13+ files owned by the pre-reinstall install stay invisible to
        // the File API entirely. Marking on an untrustworthy negative would
        // wreck the whole restored list on the first Downloads open. So:
        //  - only MARK a file missing when the negative is trustworthy. The
        //    trust is PER PATH: app-private paths (filesDir — the safe vault
        //    lives there) are always readable, no permission involved, so a
        //    missing vault file is always marked. Shared-storage paths are
        //    trustworthy only while the read permission is held on <= 32 (on
        //    33+ no read permission exists in the manifest and the app's own
        //    files are always readable — restored foreign entries there
        //    can't be validated by path at all, which the SAF restore flow
        //    owns);
        //  - always HEAL: an entry this worker errored (FILE_NOT_FOUND) whose
        //    file is readable again (permission granted since) goes back to
        //    FINISHED. Only this worker's own error type is healed, so a
        //    download that genuinely failed is never resurrected.
        // This worker deliberately NEVER deletes a row: exists() is not
        // trustworthy enough for an irreversible action, and the record of
        // what was downloaded (origin, title) is user data in its own right —
        // removing it is the user's call, made on the visible error entry.
        // P2P .part hygiene rides this worker because it is the one sweep that
        // runs WITHOUT the user touching P2P again: the accept-time prune alone
        // kept a failed receive's partial forever for a user who gave up on the
        // feature (a near-complete multi-GB .part with no DownloadEntity row —
        // invisible to this worker's entity loop and to the Downloads UI). Age-
        // gated (7 days) inside, so a live session's minutes-old .part is never
        // touched; runs FIRST so the early no-entities return can't skip it.
        P2pShareController.pruneStaleParts(getApplicationContext());

        boolean sharedStorageTrustworthy;
        if (Build.VERSION.SDK_INT <= 32) {
            sharedStorageTrustworthy = getApplicationContext().checkSelfPermission(
                    Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            sharedStorageTrustworthy = true;
        }
        String privateRoot = getApplicationContext().getFilesDir().getAbsolutePath();
        String safeRoot = StoragePaths.getSafePath(getApplicationContext());
        // No move while the engine runs: a vault download in flight has
        // claimed its path in the safe folder, and a file moved onto it
        // would be truncated by the strategy opening its output.
        boolean engineBusy = RunnableManager.isRunning();

        try {
            // Get all records from the database
            List<DownloadEntity> entityList = mDownloadDataRepository.getAllRawList();

            if (entityList == null || entityList.isEmpty()) {
                return Result.success();
            }

            backfillGallery(entityList);

            for (DownloadEntity entity : entityList) {
                String path = entity.getFilePath();
                boolean onDisk = path != null && new File(path).exists();
                // A null path is broken data, not a permission question — always
                // markable. Otherwise trust is per path: app-private always,
                // shared storage only while readable.
                boolean canTrustMissing = path == null
                        || path.startsWith(privateRoot)
                        || sharedStorageTrustworthy;

                // A vault row whose file sits outside the safe folder: a private
                // tab's download from before the engine placed vault downloads
                // there, written beside the public downloads with a vault row
                // over it. Moved in and re-pointed; the file is the app's own
                // (exists() is true for an owned file), so the move is ours.
                if (onDisk && entity.isFileSafe() && !engineBusy
                        && (entity.getFileStatus() == Download.FINISHED || entity.getFileStatus() == Download.ERROR)
                        && !path.startsWith(safeRoot + File.separator)) {
                    File moved = moveIntoSafeFolder(new File(path), safeRoot);
                    if (moved != null) {
                        Log.d(TAG, "Vault file moved into the safe folder: " + entity.getFileName());
                        if (path.equals(entity.getFileImg())) {
                            entity.setFileImg(moved.getAbsolutePath());
                        }
                        entity.setFilePath(moved.getAbsolutePath());
                        entity.setFileName(moved.getName());
                        mDownloadDataRepository.addSync(entity);
                        GalleryPublisher.forget(getApplicationContext(), path);
                    }
                }

                if (entity.getFileStatus() == Download.FINISHED) {
                    if (!onDisk && canTrustMissing) {
                        Log.d(TAG, "File missing for entity: " + entity.getFileName());

                        // Update the entity state
                        entity.setFileStatus(Download.ERROR);
                        entity.setFileErrorType(MessageHelper.FILE_NOT_FOUND);

                        // Save update back to DB synchronously
                        mDownloadDataRepository.addSync(entity);
                    }
                } else if (entity.getFileStatus() == Download.ERROR
                        && entity.getFileErrorType() == MessageHelper.FILE_NOT_FOUND
                        && onDisk) {
                    Log.d(TAG, "File readable again — healing entity: " + entity.getFileName());

                    entity.setFileStatus(Download.FINISHED);
                    entity.setFileErrorType(MessageHelper.NO_ERROR);

                    mDownloadDataRepository.addSync(entity);
                }
            }

            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Error during media check", e);
            return Result.failure();
        }
    }

    /**
     * Moves a file into the safe folder under a free name — free on disk AND
     * in the table (an ERROR row with no file still owns its path) — by a
     * rename where the folders share a volume, else a copy and a delete
     * (commons-io leaves no half-moved pair behind). Null when the move was
     * not made: a file the app cannot delete (foreign-owned after a
     * reinstall — the copy would be made and thrown away on every resume), a
     * folder without room for it, or a failed move; the row then keeps its
     * public path and the next sweep looks again.
     */
    private File moveIntoSafeFolder(File source, String safeRoot) {
        if (!source.canWrite()) {
            return null;
        }
        try {
            File folder = new File(safeRoot);
            FileUtils.forceMkdir(folder);
            if (folder.getUsableSpace() < source.length() + MOVE_SPACE_MARGIN_BYTES) {
                Log.w(TAG, "moveIntoSafeFolder: no room for " + source.getName());
                return null;
            }
            File target = new File(folder, source.getName());
            while (target.exists() || mDownloadDataRepository.findByFilePath(target.getAbsolutePath()) != null) {
                target = new File(UrlParser.parseFilePath(target.getAbsolutePath()));
            }
            FileUtils.moveFile(source, target);
            return target;
        } catch (IOException e) {
            Log.w(TAG, "moveIntoSafeFolder: " + source.getName(), e);
            return null;
        }
    }

    /** Room the move must leave on the private volume beside the file itself. */
    private static final long MOVE_SPACE_MARGIN_BYTES = 64L * 1024 * 1024;

    /**
     * The gallery switch turned on after downloads were made: every finished
     * public media file is published once, in one scanner request, on the
     * next Downloads resume. Turned off, the next turn-on publishes again.
     */
    private void backfillGallery(List<DownloadEntity> entities) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        boolean on = Preferences.getSaveToGallery(prefs);
        boolean done = prefs.getBoolean(Preferences.GALLERY_BACKFILLED, false);
        if (!on) {
            if (done) {
                prefs.edit().putBoolean(Preferences.GALLERY_BACKFILLED, false).apply();
            }
            return;
        }
        if (done) {
            return;
        }
        List<String> paths = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (DownloadEntity entity : entities) {
            String path = entity.getFilePath();
            if (entity.isFileSafe() || entity.getFileStatus() != Download.FINISHED
                    || path == null || !new File(path).exists()) {
                continue;
            }
            paths.add(path);
            types.add(entity.getFileMimeType());
        }
        GalleryPublisher.publishAll(getApplicationContext(), paths, types);
        prefs.edit().putBoolean(Preferences.GALLERY_BACKFILLED, true).apply();
    }
}

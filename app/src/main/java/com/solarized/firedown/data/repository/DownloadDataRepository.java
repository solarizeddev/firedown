package com.solarized.firedown.data.repository;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.paging.PagingSource;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.data.DownloadDatabase;
import com.solarized.firedown.data.RestoredFileAccess;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.ffmpegutils.FFmpegMetaData;
import com.solarized.firedown.ffmpegutils.FFmpegMetaDataReader;
import com.solarized.firedown.ffmpegutils.FFmpegUtils;
import com.solarized.firedown.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

@Singleton
public class DownloadDataRepository {

    private static final String TAG = "DownloadRepository";

    private final Context mContext;
    private final DownloadDatabase mDatabase;
    private final Executor mDiskExecutor;
    private final Executor mHeavyExecutor;

    /**
     * Live PagingSources of the download table (Downloads + Vault streams),
     * weakly held, registered by {@code DownloadsViewModel.createPagingSource}.
     *
     * <p><b>Why this exists:</b> Room's InvalidationTracker can drop the LAST
     * write of a rapid burst — observed on-device (Room 2.8.4, Samsung): a
     * user-finished SABR download writes PROGRESS+PROCESSING then FINISHED
     * ~100 ms apart, the FINISHED row lands in the DB, but no new paging
     * generation ever fires (the ViewModel's "new paging generation"
     * checkpoint stays silent), so the row sits on "Finishing…" until the
     * screen is re-entered. Intermittent — the refresh pass that serviced the
     * first write's invalidation swallows the second's. Same lost-invalidation
     * class as the recycled-connection saga (see CLAUDE.md "Room
     * invalidation"), surviving persistent tracking mode.
     *
     * <p>The belt: after every write on the DiskIO lane the repository
     * invalidates the registered sources DIRECTLY ({@link PagingSource#invalidate}
     * is public, thread-safe and idempotent — a no-op when Room's tracker
     * already invalidated the source first), so a new generation is guaranteed
     * whether or not the tracker delivered. Registry is cleared on each poke:
     * an invalidated source is dead, and its replacement re-registers when the
     * Pager instantiates it.
     */
    private final Set<PagingSource<?, ?>> mActivePagingSources =
            Collections.newSetFromMap(new WeakHashMap<>());

    @Inject
    public DownloadDataRepository(@ApplicationContext Context context,
                                  DownloadDatabase database,
                                  @Qualifiers.DiskIO Executor diskExecutor,
                                  @Qualifiers.HeavyIO Executor heavyExecutor) {
        this.mContext = context;
        this.mDatabase = database;
        this.mDiskExecutor = diskExecutor;
        this.mHeavyExecutor = heavyExecutor;
    }

    /** Registers a live PagingSource for the direct-invalidation belt — see
     *  {@link #mActivePagingSources}. Called from the ViewModel's paging
     *  factory for every source it creates. */
    public void registerActivePagingSource(PagingSource<?, ?> source) {
        if (source == null) {
            return;
        }
        synchronized (mActivePagingSources) {
            mActivePagingSources.add(source);
        }
    }

    /** Invalidates (and forgets) every registered PagingSource. Called at the
     *  end of every DiskIO write lambda so a new paging generation is
     *  guaranteed even when Room's InvalidationTracker drops the write's
     *  notification — see {@link #mActivePagingSources}. */
    private void invalidateActivePagingSources() {
        List<PagingSource<?, ?>> sources;
        synchronized (mActivePagingSources) {
            if (mActivePagingSources.isEmpty()) {
                return;
            }
            sources = new ArrayList<>(mActivePagingSources);
            mActivePagingSources.clear();
        }
        for (PagingSource<?, ?> source : sources) {
            source.invalidate();
        }
    }

    // --- Paging Queries ---

    public PagingSource<Integer, DownloadEntity> getDownloads() {
        return mDatabase.downloadDao().getDownloads();
    }

    public PagingSource<Integer, DownloadEntity> getDownloadsSize() {
        return mDatabase.downloadDao().getDownloadsSize();
    }

    public PagingSource<Integer, DownloadEntity> getDownloadsName() {
        return mDatabase.downloadDao().getDownloadsName();
    }

    public PagingSource<Integer, DownloadEntity> getSafe() {
        return mDatabase.downloadDao().getSafe();
    }

    public PagingSource<Integer, DownloadEntity> getSafeSize() {
        return mDatabase.downloadDao().getSafeSize();
    }

    public PagingSource<Integer, DownloadEntity> getSafeName() {
        return mDatabase.downloadDao().getSafeName();
    }

    public PagingSource<Integer, DownloadEntity> getDownloadsDomain() {
        return mDatabase.downloadDao().getDownloadsDomain();
    }

    public PagingSource<Integer, DownloadEntity> getSafeDomain() {
        return mDatabase.downloadDao().getSafeDomain();
    }

    public PagingSource<Integer, DownloadEntity> getSearch(int sorting, boolean safe, String query) {
        return mDatabase.downloadDao().search(sorting, safe, query);
    }

    public LiveData<List<DownloadEntity>> getAllRegularLive() {
        return mDatabase.downloadDao().getAllRegularLive();
    }

    public LiveData<List<DownloadEntity>> getAllSafeLive() {
        return mDatabase.downloadDao().getAllSafeLive();
    }

    // --- Standard Queries ---

    /** Live total bytes for finished regular downloads — drives the home
     *  subtitle's "N saved" figure. */
    public LiveData<Long> getRegularFinishedSize() {
        return mDatabase.downloadDao().getRegularFinishedSizeLive();
    }

    public List<DownloadEntity> getAllRawList() {
        return mDatabase.downloadDao().getAllRawList();
    }

    public List<DownloadEntity> getAllRawEnc() {
        return mDatabase.downloadDao().getAllRawEnc();
    }

    public DownloadEntity findByFilePath(String filePath) {
        return mDatabase.downloadDao().findByFilePath(filePath);
    }

    /**
     * Synchronous "is this file already in Downloads?" lookup (name + byte-size).
     * Runs the query on the caller's thread — callers MUST be off the main thread
     * (e.g. a worker thread or a background executor). Used by Cloud Backup to skip
     * a duplicate restore and to locate a local copy for a preview backfill.
     */
    public DownloadEntity findByNameSize(String name, long size) {
        return mDatabase.downloadDao().findByNameSize(name, size);
    }


    /**
     * Persist a download row. The write is deferred onto the serial
     * {@code @DiskIO} lane, so it must capture a SNAPSHOT of the entity's
     * fields NOW — not a live reference whose fields are read whenever the
     * lambda eventually drains.
     *
     * <p><b>Why this matters:</b> {@link com.solarized.firedown.manager.DownloadTask}
     * instances are pooled ({@code mDownloadTaskWorkQueue}) and each holds a
     * single, reused {@link DownloadEntity}. The terminal write in
     * {@code onRunComplete} (with the re-probed file duration from
     * {@code refreshMetadataFromFile}) is enqueued here, then the task is
     * recycled and a subsequent download mutates that SAME entity via
     * {@code initialize()}/{@code resume()}. If the recycle wins the race
     * against this still-pending insert, the lambda would read the new
     * download's fields — writing the wrong row and leaving the finished item
     * with its pre-refresh (capture-time / parser) duration. Snapshotting at
     * call time makes every write reflect the state as of the call, which is
     * the only correct contract for a fire-and-forget write of a reused
     * object. (Same precedent as {@link #updateDownloadThumb}, which already
     * copies before its deferred write.) The extra allocation per call —
     * including the ~1.5s progress cadence — is negligible.
     */
    public void add(DownloadEntity download) {
        DownloadEntity snapshot = new DownloadEntity(download);
        mDiskExecutor.execute(() -> {
            mDatabase.downloadDao().insert(snapshot);
            invalidateActivePagingSources();
        });
    }

    public void addSync(DownloadEntity download) {
        DownloadEntity snapshot = new DownloadEntity(download);
        mDiskExecutor.execute(() -> {
            mDatabase.downloadDao().insertSync(snapshot);
            invalidateActivePagingSources();
        });
    }

    public void insertAllSync(List<DownloadEntity> entityList) {
        mDiskExecutor.execute(() -> {
            mDatabase.downloadDao().insertAll(entityList);
            invalidateActivePagingSources();
        });
    }

    /**
     * Mark a download's Glide thumbnail pipeline as known-unproductive
     * (no embedded cover art, no decodable video frame, corrupt media,
     * etc). Called from the Glide load listener after every decoder in
     * the chain has failed for a {@link Download#FINISHED} file. The
     * next paging access reads the flag from the entity and short-
     * circuits to the static mime icon — no more {@code MediaMetadata-
     * Retriever} or FFmpeg contexts per scroll past this row.
     *
     * <p>Dispatched on {@code mDiskExecutor} so the Room write never
     * runs on the main thread.</p>
     */
    public void setThumbnailUnavailable(int id, boolean unavailable) {
        mDiskExecutor.execute(() -> {
            mDatabase.downloadDao().setThumbnailUnavailableSync(id, unavailable);
            invalidateActivePagingSources();
        });
    }

    /**
     * Refreshes metadata and thumbnail timestamp for a download.
     *
     * <p>The FFmpeg probe runs on the HEAVY executor, not {@code @DiskIO}:
     * it can grind for a long time on a corrupt/odd file, and {@code @DiskIO}
     * is the single serial lane all short DB mutations (incl. deletes) ride
     * on — one wedged probe there froze the Downloads list for the rest of
     * the session. The final write then HOPS BACK to {@code @DiskIO} with an
     * existence check: every row delete runs on that same serial lane, so
     * check+insert there cannot interleave with a delete — without the hop,
     * a probe finishing after the user deleted the row would
     * insert(REPLACE) it back (the two executors DO run concurrently).</p>
     */
    /**
     * Next "Regenerate thumbnail" position: a golden-ratio step from the
     * CURRENT position, wrapped to the clip — NOT a fresh random draw.
     * {@code ThreadLocalRandom.nextLong(duration)} was observed producing the
     * IDENTICAL value sequence in every app process on-device (SM-A426B/SDK 31:
     * the first regenerate always yielded 1257600981, the second 889413877 —
     * the same two positions in a session hours earlier), so every "random"
     * pick collided with a position Glide had already decoded and cached (the
     * position is in the request signature + TargetFrame option, so a repeated
     * position is an instant cache hit) and the visible thumbnail never
     * changed. Stepping from the current position guarantees a NEW position —
     * and therefore a cache MISS and a real re-decode — on every click, with
     * no PRNG in the loop at all; the irrational step spreads successive
     * clicks across the whole clip (low-discrepancy) instead of clustering.
     * {@code floorMod} guards a stale stored position larger than a
     * re-probed, shorter duration.
     */
    private static long nextThumbnailPosition(long currentPos, long duration) {
        long span = duration > 0 ? duration : 100;
        long step = (long) (span * 0.6180339887498949);
        if (step <= 0) {
            step = 1;
        }
        return Math.floorMod(currentPos + step, span);
    }

    public void updateDownloadThumb(DownloadEntity download) {
        mHeavyExecutor.execute(() -> {
            DownloadEntity newEntity = new DownloadEntity(download);
            String filePath = newEntity.getFilePath();
            long duration = newEntity.getDuration();

            if (duration <= 0) {
                FFmpegMetaDataReader reader = new FFmpegMetaDataReader();
                try {
                    FFmpegMetaData meta = reader.getStreamInfo(filePath, null, true);
                    if (meta != null) {
                        duration = meta.getDuration();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Failed to read stream info for thumb update", e);
                } finally {
                    reader.stop();
                    reader.release();
                }
            }

            long thumbPos = nextThumbnailPosition(newEntity.getThumbnailDuration(), duration);
            newEntity.setFileDuration(duration);
            newEntity.setFileDurationFormatted(FFmpegUtils.getFileDuration(duration));
            newEntity.setFileThumbnailDuration(thumbPos);
            // Regenerate is an EXPLICIT "try this thumbnail again" request, so
            // clear the persistent negative-cache flag. Otherwise GlideHelper
            // .load() short-circuits to the mime glyph BEFORE attempting any
            // decode (see isFileThumbnailUnavailable()) — the copy-ctor carried
            // the poison flag forward, so regenerate re-inserted a still-poisoned
            // row and visibly did nothing. Clearing it lets the Glide chain run
            // again, which for a file poisoned before the FFmpegPfdDecoder
            // fallback existed (MMR + skia had failed) now gets that fallback's
            // shot; a genuine re-failure just re-sets the flag.
            newEntity.setFileThumbnailUnavailable(false);

            mDiskExecutor.execute(() -> {
                // Row gone (deleted while the probe ran) — drop the result
                // instead of resurrecting it via insert(REPLACE).
                if (mDatabase.downloadDao().findByIdSync(newEntity.getId()) == null) {
                    return;
                }
                mDatabase.downloadDao().insert(newEntity);
                invalidateActivePagingSources();
            });
        });
    }


    public void deleteDownload(DownloadEntity download) {
        mDiskExecutor.execute(() -> {
            mDatabase.downloadDao().deleteSyncEntity(download);
            deleteFilesInternal(Collections.singletonList(download));
            invalidateActivePagingSources();
        });
    }

    /**
     * Deletes a single download and runs the callback on the disk executor after completion.
     */
    public void deleteDownload(DownloadEntity download, Runnable onComplete) {
        mDiskExecutor.execute(() -> {
            mDatabase.downloadDao().deleteSyncEntity(download);
            deleteFilesInternal(Collections.singletonList(download));
            invalidateActivePagingSources();
            if (onComplete != null) onComplete.run();
        });
    }

    /**
     * Batch-deletes a list of downloads. The FILE is removed first, per entity:
     * an entity whose file can't be deleted because it's a foreign-owned
     * restored file needing a SAF WRITE grant we don't hold KEEPS its row (so
     * the entry stays visible) and is collected; everything else has its row
     * removed. {@code onComplete} receives the kept (needs-grant) entities so
     * the caller can prompt for the grant and retry — see
     * {@link com.solarized.firedown.data.TaskEvent.NeedsDeleteGrant}.
     */
    public void deleteDownloads(List<DownloadEntity> downloads,
                                Consumer<ArrayList<DownloadEntity>> onComplete) {
        if (downloads == null || downloads.isEmpty()) {
            if (onComplete != null) onComplete.accept(new ArrayList<>());
            return;
        }
        mDiskExecutor.execute(() -> {
            ArrayList<DownloadEntity> needGrant = new ArrayList<>();
            // Row removal matches by PRIMARY KEY (uid); the file cleanup
            // matches by PATH. Delete the file FIRST so a foreign restored
            // file we can't remove keeps its row (no orphaned-file-with-no-row
            // state) and the UI can prompt to fix it.
            int removed = 0;
            for (DownloadEntity entity : downloads) {
                if (fileNeedsDeleteGrant(entity)) {
                    needGrant.add(entity);
                    continue;
                }
                Integer result = mDatabase.downloadDao().deleteSyncEntity(entity);
                if (result != null) {
                    removed += result;
                }
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "deleteDownloads: requested=" + downloads.size()
                        + " rowsRemoved=" + removed + " needGrant=" + needGrant.size());
            }
            invalidateActivePagingSources();
            if (onComplete != null) onComplete.accept(needGrant);
        });
    }



    /**
     * Internal helper to clean up physical files after DB records are removed
     * (the single-delete paths). Best-effort: tries the owned file, then the
     * SAF grant for a foreign restored file; an undeletable foreign file is
     * just left (these callers have no UI to prompt for a grant — only the
     * batch {@link #deleteDownloads} path surfaces that).
     */
    private void deleteFilesInternal(List<DownloadEntity> entities) {
        if (entities == null || entities.isEmpty()) return;
        for (DownloadEntity entity : entities) {
            fileNeedsDeleteGrant(entity);
        }
    }

    /**
     * Remove the entity's file. Returns {@code true} iff the file still exists
     * because deleting it needs a folder (SAF) WRITE grant we don't hold — a
     * restored, foreign-owned public file (Android 11+ scoped storage). Every
     * other outcome (deleted, absent, a directory, or an unrecoverable failure
     * on an owned path) returns {@code false}.
     */
    private boolean fileNeedsDeleteGrant(DownloadEntity entity) {
        String path = entity.getFilePath();
        if (path == null) return false;

        File file = new File(path);
        if (!file.exists()) return false;

        // Mutually exclusive — directory deletion is recursive and already
        // removes the entry, so falling through to file.delete() afterwards
        // would always return false and log a misleading warning.
        if (file.isDirectory()) {
            Utils.deleteDirectory(file);
            Log.d(TAG, "Deleted directory: " + path);
            return false;
        }
        if (file.delete()) {
            Log.d(TAG, "Deleted file: " + path);
            return false;
        }
        // File.delete() failed. A restored entry points at a public file this
        // install no longer OWNS; delete it through the persisted SAF tree
        // grant (the same grant RestoredFileAccess reads foreign files through).
        Uri saf = RestoredFileAccess.safDocumentUri(mContext, path);
        if (saf != null) {
            try {
                if (DocumentsContract.deleteDocument(mContext.getContentResolver(), saf)) {
                    Log.d(TAG, "Deleted file via SAF: " + path);
                    return false;
                }
            } catch (Exception e) {
                // SecurityException = the persisted grant is read-only (taken
                // before write was requested); fall through to needs-grant.
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "SAF delete failed: " + path, e);
                }
            }
        }
        // Still here: the file exists and we couldn't remove it. A foreign file
        // in shared storage is fixable with a folder WRITE grant — signal the
        // UI to prompt. An owned-path failure can't be helped by a grant.
        if (RestoredFileAccess.isSharedStoragePath(path)) {
            Log.w(TAG, "File needs SAF write grant to delete: " + path);
            return true;
        }
        Log.w(TAG, "Failed to delete file: " + path);
        return false;
    }
}
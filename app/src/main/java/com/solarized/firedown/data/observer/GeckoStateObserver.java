package com.solarized.firedown.data.observer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonWriter;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.SessionStateStore;
import com.solarized.firedown.data.TabIconStore;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;

import dagger.hilt.android.qualifiers.ApplicationContext;

import org.apache.commons.io.FileUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Persists the tab list to the sessions file, mirroring Firefox for Android's
 * SessionStorage/BrowserStateWriter model (issue #292 hardening):
 *
 * <ul>
 *   <li><b>Streamed write</b> — a versioned document
 *       ({@code {"version":3,"tabs":[…]}}) written directly to the file with
 *       {@code android.util.JsonWriter}; the whole file is never resident as
 *       one String in either direction (the reader streams too).</li>
 *   <li><b>No inline image data</b> — a {@code data:} favicon is externalized
 *       to a sidecar file ({@link TabIconStore}) and referenced by path;
 *       PREVIEW (the og:image, never shown in the tab UI) is not written at
 *       all. Inlined base64 images are what once grew the file into the OOM
 *       boot loop, and Fenix's session file carries none.</li>
 *   <li><b>Atomic + contained</b> — write to {@code .tmp}, fsync, rename; a
 *       failed write deletes the partial {@code .tmp} (AtomicFile's failWrite
 *       contract) so a torn temp can never linger for the boot read's
 *       fallback path.</li>
 * </ul>
 *
 * The versioned-object shape is also what lets the read side be STRICT for
 * writer-controlled files (Fenix parity) while keeping the lenient reader only
 * for the legacy bare-array files older builds wrote — see
 * {@code GeckoStateDataRepository.tryReadEntities}.
 */
public final class GeckoStateObserver implements Observer<List<GeckoStateEntity>> {

    private static final String TAG = GeckoStateObserver.class.getSimpleName();

    private final Executor mDiskExecutor;
    private final Context mContext;

    /**
     * Last time the session-state store was pruned/touched, 0 = never (so the
     * FIRST committed persist after boot always prunes — Chromium's
     * cleanup-once-after-load). Only read/written on the single DiskIO thread.
     */
    private long mLastStatePruneMs;

    /** Persist batching window — Fenix AutoSave parity (its default save
     *  interval is 2 s). onSessionStateChange fires for scroll/form settle,
     *  not just navigation, and each emission used to rewrite + fsync the
     *  WHOLE metadata file — all-day flash churn on a big profile. Batching
     *  is FIXED-INTERVAL with latest-wins, NOT a trailing-reset debounce: a
     *  reset-on-every-event timer never fires under continuous scroll churn
     *  (starvation), while this shape guarantees a persist at most once per
     *  window and at latest one window after the first change. Kill-safety
     *  is preserved by {@link #flush()} — ApplicationLifeCycleHandler flushes
     *  before detaching the observer on pause/destroy, which is exactly when
     *  Android reclaims processes; the residual exposure is a mid-foreground
     *  hard kill losing ≤ one window of scroll position (the same trade
     *  Fenix ships). */
    private static final long PERSIST_BATCH_MS = 2000;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // Batching state — MAIN-THREAD CONFINED (LiveData observeForever delivers
    // onChanged on main; flush is called from activity lifecycle callbacks,
    // also main), so no locking is needed. mHasPending is a separate flag
    // because null is a legitimate pending value (deleteAll posts null).
    private boolean mPersistScheduled;
    private boolean mHasPending;
    @Nullable
    private List<GeckoStateEntity> mPendingEntities;

    private final Runnable mDispatchPending = this::dispatchPending;

    /** State-store prune cadence. Persists fire on every tab event (title,
     *  switch, navigation), and pruning per persist meant a directory scan +
     *  one utimes syscall per referenced file each time — pure inode churn.
     *  Hourly is plenty: the grace window is 24 h, so a referenced file's
     *  clock stays comfortably fresh, and an orphan's deletion slips by at
     *  most an hour. Chromium likewise cleans up once after load, not per
     *  save. */
    private static final long STATE_PRUNE_INTERVAL_MS = 60L * 60 * 1000;

    @Inject
    public GeckoStateObserver(
            @Qualifiers.DiskIO Executor diskExecutor,
            @ApplicationContext Context context) {
        this.mDiskExecutor = diskExecutor;
        this.mContext = context;
    }

    @Override
    public void onChanged(List<GeckoStateEntity> entities) {
        Log.d(TAG, "onChanged");
        mPendingEntities = entities;
        mHasPending = true;
        if (!mPersistScheduled) {
            mPersistScheduled = true;
            mMainHandler.postDelayed(mDispatchPending, PERSIST_BATCH_MS);
        }
    }

    /**
     * Persists any batched change NOW. Called by ApplicationLifeCycleHandler
     * right before this observer is detached (Browser pause/destroy) so a
     * pending snapshot can't be lost to a background kill — see
     * {@link #PERSIST_BATCH_MS}. Main thread only. Idempotent.
     */
    public void flush() {
        mMainHandler.removeCallbacks(mDispatchPending);
        dispatchPending();
    }

    private void dispatchPending() {
        mPersistScheduled = false;
        if (!mHasPending) {
            return;
        }
        final List<GeckoStateEntity> entities = mPendingEntities;
        mHasPending = false;
        mPendingEntities = null;
        mDiskExecutor.execute(() -> persist(entities));
    }

    private void persist(List<GeckoStateEntity> entities) {
        try {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "saveToDiskIO: " + (entities != null ? entities.size() : 0));
            }
            writeSessionFile(entities);
        } catch (OutOfMemoryError | IOException | RuntimeException e) {
            // OutOfMemoryError is caught deliberately (Fenix parity —
            // SessionStorage.save does the same): an uncaught Error on this
            // executor thread reaches Android's default uncaught handler and
            // KILLS THE PROCESS, turning a skippable persist into a crash. A
            // failed persist keeps the last committed file; the torn temp was
            // already cleaned up by writeSessionFile.
            Log.e(TAG, "saveToDiskIO", e);
        } finally {
            if (entities == null || entities.isEmpty()) {
                deleteThumbnails();
            }
        }
    }

    /**
     * Streams the v2 session document to the {@code .tmp} sibling, fsyncs,
     * renames over the live file, then prunes the icon store down to what this
     * persist referenced. On any failure before the rename lands, the partial
     * temp is deleted — the boot read treats a present {@code .tmp} as a
     * fallback snapshot, so a torn one must not survive.
     */
    private void writeSessionFile(List<GeckoStateEntity> entities) throws IOException {
        File dir = mContext.getFilesDir();
        File targetFile = new File(dir, GeckoStateDataRepository.FILE);
        File tempFile = new File(dir, GeckoStateDataRepository.FILE + ".tmp");
        Set<String> referencedIcons = new HashSet<>();
        Set<String> referencedStates = new HashSet<>();
        boolean written = false;

        // Phase 1 — stream the document to the temp file. The failWrite
        // containment is scoped to THIS phase only: a failure here leaves a
        // TORN temp, which must not survive (the boot read treats a present
        // .tmp as a fallback snapshot).
        try {
            try (FileOutputStream fos = new FileOutputStream(tempFile);
                 JsonWriter writer = new JsonWriter(new BufferedWriter(
                         new OutputStreamWriter(fos, StandardCharsets.UTF_8)))) {
                writer.beginObject();
                writer.name(GeckoStateDataRepository.KEY_VERSION);
                writer.value(GeckoStateDataRepository.SESSION_FILE_VERSION);
                writer.name(GeckoStateDataRepository.KEY_TABS);
                writer.beginArray();
                if (entities != null) {
                    for (GeckoStateEntity entity : entities) {
                        if (entity.isHome()) {
                            continue;
                        }
                        String iconRef = TabIconStore.externalize(mContext, entity.getIcon());
                        if (TabIconStore.isStorePath(mContext, iconRef)) {
                            referencedIcons.add(iconRef);
                        }
                        // Session state (v3): a tab holding a LIVE string
                        // (opened this run, or read from a v2 file) is
                        // externalized — content-hash, so an unchanged state
                        // re-hits its existing file and costs nothing (this is
                        // Chromium's dirty-only save, for free). A restored,
                        // never-opened tab holds only its ref — CARRY it
                        // through untouched; the observer never needs to read
                        // state bytes.
                        String stateRef;
                        String liveState = entity.getSessionState();
                        if (!liveState.isEmpty()) {
                            stateRef = SessionStateStore.externalize(mContext, liveState);
                        } else {
                            stateRef = entity.getSessionStateRef();
                        }
                        if (SessionStateStore.isStorePath(mContext, stateRef)) {
                            referencedStates.add(stateRef);
                        }
                        writeEntity(writer, entity, iconRef, stateRef);
                    }
                }
                writer.endArray();
                writer.endObject();
                writer.flush();
                fos.getFD().sync();
            }
            written = true;
        } finally {
            if (!written) {
                FileUtils.deleteQuietly(tempFile);
            }
        }

        // Phase 2 — publish. The temp is now COMPLETE and fsynced, so if the
        // rename dance fails it must be KEPT, never deleted: in the worst
        // interleaving (target deleted, second rename fails, process dies) the
        // valid .tmp is the ONLY remaining copy of the session state, and the
        // boot read's fallback path promotes it. Deleting it here would turn a
        // failed rename into total tab loss.
        if (!tempFile.renameTo(targetFile)) {
            if (targetFile.exists() && !targetFile.delete()) {
                throw new IOException("Failed to delete old session file");
            }
            if (!tempFile.renameTo(targetFile)) {
                throw new IOException("Failed to rename temp session file");
            }
        }

        // Prune only after a committed persist: the referenced sets mirror the
        // file that is now live. Racing a concurrent Glide read of a pruned
        // icon is safe — unlink during an open read is fine on Linux, and a
        // later cache-miss load falls back to the generated domain thumbnail
        // (GlideHelper's failure listener). Session-state files use the
        // GRACE-based prune (touch referenced, delete unreferenced only after
        // RETENTION_MS) so a close-undo or any read race finds its file
        // intact — see SessionStateStore's class doc for the Chromium lessons
        // this encodes. The referenced set is complete because the tab ARCHIVE
        // never holds refs (state is inlined into Room at archive time).
        // The state prune is THROTTLED (first persist after boot, then
        // hourly): its touch pass is one utimes per referenced file plus a
        // directory scan, which per-persist was pure inode churn. Safe under
        // the grace math — any set this prune uses mirrors a COMMITTED
        // metadata file, and a file referenced by the current metadata is
        // never older than the last prune's touch (≤ 1 h) + this persist,
        // far inside the 24 h window. The icon prune stays per-persist: it
        // has no touch pass and the store is small.
        TabIconStore.prune(mContext, referencedIcons);
        long now = System.currentTimeMillis();
        if (mLastStatePruneMs == 0 || now - mLastStatePruneMs >= STATE_PRUNE_INTERVAL_MS) {
            SessionStateStore.prune(mContext, referencedStates);
            mLastStatePruneMs = now;
        }
    }

    /**
     * One tab as v3 JSON. The shape here is the contract the STRICT reader
     * ({@code GeckoStateDataRepository.readEntityStrict}) enforces — add a key
     * in BOTH places and bump {@code SESSION_FILE_VERSION} if the change isn't
     * backward-readable. No PREVIEW, no inline {@code data:} icon, and no
     * inline SESSION string, ever — the caller externalized the icon to
     * {@code iconRef} and the session state to {@code stateRef}
     * ({@code SessionStateStore}); v3 writes only references, which is what
     * makes the boot read O(opened tabs). Nullable strings are written as
     * {@code ""} so the reader needs no null handling for a writer-controlled
     * file.
     */
    private void writeEntity(JsonWriter writer, GeckoStateEntity e, String iconRef,
                             String stateRef) throws IOException {
        writer.beginObject();
        writer.name(GeckoStateEntity.KEYS.DATE).value(e.getCreationDate());
        writer.name(GeckoStateEntity.KEYS.UPDATE).value(e.getLastAccess());
        writer.name(GeckoStateEntity.KEYS.ICON).value(orEmpty(iconRef));
        writer.name(GeckoStateEntity.KEYS.ICON_RESOLUTION).value(e.getIconResolution());
        writer.name(GeckoStateEntity.KEYS.THUMB).value(orEmpty(e.getThumb()));
        writer.name(GeckoStateEntity.KEYS.SESSION_REF).value(orEmpty(stateRef));
        writer.name(GeckoStateEntity.KEYS.URI).value(orEmpty(e.getUri()));
        writer.name(GeckoStateEntity.KEYS.ID).value(e.getId());
        writer.name(GeckoStateEntity.KEYS.PARENT_ID).value(e.getParentId());
        writer.name(GeckoStateEntity.KEYS.TITLE).value(orEmpty(e.getTitle()));
        writer.name(GeckoStateEntity.KEYS.BACKWARD).value(e.canGoBackward());
        writer.name(GeckoStateEntity.KEYS.FORWARD).value(e.canGoForward());
        writer.name(GeckoStateEntity.KEYS.FULLSCREEN).value(e.isFullScreen());
        writer.name(GeckoStateEntity.KEYS.DESKTOP).value(e.isDesktop());
        writer.name(GeckoStateEntity.KEYS.ACTIVE).value(e.isActive());
        writer.name(GeckoStateEntity.KEYS.TRACKING_PROTECTION).value(e.useTrackingProtection());
        writer.name(GeckoStateEntity.KEYS.HOME).value(e.isHome());
        writer.endObject();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private void deleteThumbnails() {
        try {
            FileUtils.cleanDirectory(new File(StoragePaths.getThumbsPath(mContext)));
        } catch (IOException e) {
            Log.e(TAG, "deleteThumbnails", e);
        }
    }
}

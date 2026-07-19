package com.solarized.firedown.data.observer;

import android.content.Context;
import android.util.JsonWriter;
import android.util.Log;

import androidx.lifecycle.Observer;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.StoragePaths;
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
 *       ({@code {"version":2,"tabs":[…]}}) written directly to the file with
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
                        writeEntity(writer, entity, iconRef);
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

        // Prune only after a committed persist: the referenced set mirrors the
        // file that is now live. Racing a concurrent Glide read of a pruned
        // icon is safe — unlink during an open read is fine on Linux, and a
        // later cache-miss load falls back to the generated domain thumbnail
        // (GlideHelper's failure listener).
        TabIconStore.prune(mContext, referencedIcons);
    }

    /**
     * One tab as v2 JSON. The shape here is the contract the STRICT reader
     * ({@code GeckoStateDataRepository.readEntityStrict}) enforces — add a key
     * in BOTH places and bump {@code SESSION_FILE_VERSION} if the change isn't
     * backward-readable. No PREVIEW and no inline {@code data:} icon, ever
     * (the caller already externalized the icon to {@code iconRef}). Nullable
     * strings are written as {@code ""} so the reader needs no null handling
     * for a writer-controlled file.
     */
    private void writeEntity(JsonWriter writer, GeckoStateEntity e, String iconRef)
            throws IOException {
        writer.beginObject();
        writer.name(GeckoStateEntity.KEYS.DATE).value(e.getCreationDate());
        writer.name(GeckoStateEntity.KEYS.UPDATE).value(e.getLastAccess());
        writer.name(GeckoStateEntity.KEYS.ICON).value(orEmpty(iconRef));
        writer.name(GeckoStateEntity.KEYS.ICON_RESOLUTION).value(e.getIconResolution());
        writer.name(GeckoStateEntity.KEYS.THUMB).value(orEmpty(e.getThumb()));
        writer.name(GeckoStateEntity.KEYS.SESSION).value(orEmpty(e.getSessionState()));
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

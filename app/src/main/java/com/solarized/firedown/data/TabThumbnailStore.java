package com.solarized.firedown.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.di.Qualifiers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * Tab screenshots — the Fenix / Chromium shape: ONE store keyed by TAB ID, a
 * byte-bounded memory tier in front of a disk tier, and a tab entity that
 * carries nothing but its id (a repository snapshot stamps the screenshot's
 * {@link #version} so the tab grid's diff can tell a recapture apart).
 *
 * <p>Before this, the bitmap rode on the data model: {@code GeckoState} and
 * every emitted {@code GeckoStateEntity} snapshot held a reference, the diff
 * compared it by identity, the regular repository cleared it after a PNG
 * write on the DB lane with no notify (the grid then rebound the tile from
 * disk with a null placeholder — a blink), the incognito repository
 * hand-rolled a count-capped trim, and both tiers were wired through five
 * classes. The two things that design got RIGHT are kept as rules here:
 *
 * <ul>
 *   <li><b>The just-captured tab is a synchronous memory hit.</b> {@link #put}
 *   stores into the memory tier BEFORE anything is written, and the entry
 *   stays until evicted — there is no memory-to-disk handoff to race the grid.
 *   {@link #peek} is what the tile binds from, on the main thread, with no
 *   placeholder frame.</li>
 *   <li><b>An incognito screenshot never touches disk.</b> {@code persist} is
 *   the CALL's choice, never derived from the id: an incognito repository
 *   passes false, so the memory tier is that tab's only tier and eviction
 *   degrades it to the placeholder until its next contentful paint.</li>
 * </ul>
 *
 * <p>The disk tier is {@code cacheDir/thumbs/<tabId>.webp} (lossy WEBP at
 * {@link #WEBP_QUALITY} — Fenix's format; several times faster to encode and
 * 5–10× smaller than the PNG-at-100 it replaces), written atomically
 * (tmp → rename) on the {@code HeavyIO} lane, never the DB lane — a screenshot
 * encode has no business queueing in front of a download-progress write.
 * The file name is the tab id, so a recapture overwrites in place and the
 * grid finds a tab restored from a previous process by the id the sessions
 * file already carries — no path travels in that file any more (v5 retired
 * the {@code thumb} key). {@link #hasFile} answers from an index built once
 * at construction (optimistic until the index lands, so a bind never has to
 * stat on the main thread), and a per-store monotonic {@link #version} feeds
 * Glide's signature so a recapture can never be served from a stale decode.
 *
 * <p>Memory: {@link #memoryBudget} is an eighth of the heap clamped to
 * [4, 24] MB — ~16 MB on the 128 MB heap most devices give this app, about
 * six half-resolution ARGB_8888 screenshots — shared by BOTH modes and evicted
 * least-recently-used (a bind counts as a use, so what is on screen stays).
 * That replaces the regular repo's "clear every non-active tab on switch" and
 * incognito's fixed cap of 8 with one policy. Nothing here ever
 * {@code recycle()}s a bitmap: an evicted one may still be drawn by a tile,
 * and the GC reclaims it once nothing references it.
 *
 * <p>Lifecycle: {@link #remove} on tab close / archive / delete-all (per id —
 * the memory tier is shared across modes, so a regular delete-all must not
 * blank the incognito grid), {@link #prune} after a committed persist with
 * the ids the sessions file now references. The prune is GRACE-based: a file
 * is written on capture, not on persist, so an unreferenced file younger
 * than {@link #PRUNE_GRACE_MS} is a tab the last snapshot simply missed and
 * is kept; an older one, a legacy {@code <id>_<timestamp>.png} from before
 * the store, a crash's {@code .tmp} or anything foreign is deleted — never
 * destroy what the metadata has not yet had a chance to reference (the
 * Chromium lesson {@code SessionStateStore} records).
 *
 * <p>Verified by {@code sh scripts/thumb-harness/run.sh} (the REAL class
 * against stubbed android.graphics.Bitmap / android.util.LruCache, JDK only).
 */
@Singleton
public class TabThumbnailStore {

    private static final String TAG = "TabThumbnailStore";

    /** Disk-tier file: {@code <tabId>} + this. */
    static final String EXT = ".webp";
    private static final String TMP_EXT = ".webp.tmp";
    /** Lossy WEBP quality for a half-resolution page screenshot. */
    static final int WEBP_QUALITY = 80;
    static final long MIN_MEMORY_BUDGET = 4L << 20;
    static final long MAX_MEMORY_BUDGET = 24L << 20;
    /** An unreferenced file younger than this survives a prune: it may belong
     *  to a tab opened after the snapshot that persist wrote (persists are
     *  batched, captures are not). */
    static final long PRUNE_GRACE_MS = 10L * 60 * 1000;
    /** Longest decimal int incl. a sign — what a store file's stem can be. */
    private static final int MAX_ID_CHARS = 11;

    private final File mDir;
    private final Executor mIoExecutor;
    private final LruCache<Integer, Bitmap> mMemory;
    /** tabId → version of its screenshot. Values come from ONE monotonic
     *  clock for the whole store, so a version is never reused across a
     *  remove/re-put of the same id (Glide keys its memory cache on it). */
    private final Map<Integer, Integer> mVersions = new ConcurrentHashMap<>();
    private final AtomicInteger mVersionClock = new AtomicInteger();
    /** Ids with a file in the disk tier; built by {@link #index}, kept in step
     *  by the write / delete tasks. */
    private final Set<Integer> mOnDisk = ConcurrentHashMap.newKeySet();
    private volatile boolean mIndexed;

    @Inject
    public TabThumbnailStore(@ApplicationContext Context context,
                             @Qualifiers.HeavyIO Executor ioExecutor) {
        this(new File(StoragePaths.getThumbsPath(context)), ioExecutor, memoryBudget());
    }

    /** The harness's door: a directory, an executor it drives by hand, a raw
     *  byte budget (the production clamp lives in {@link #memoryBudget}). */
    @VisibleForTesting
    public TabThumbnailStore(File dir, Executor ioExecutor, long memoryBudgetBytes) {
        mDir = dir;
        mIoExecutor = ioExecutor;
        int budget = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, memoryBudgetBytes));
        mMemory = new LruCache<Integer, Bitmap>(budget) {
            @Override
            protected int sizeOf(@NonNull Integer key, @NonNull Bitmap value) {
                return value.getByteCount();
            }
        };
        mIoExecutor.execute(this::index);
    }

    /** An eighth of the heap, clamped — see the class doc. */
    static long memoryBudget() {
        long eighth = Runtime.getRuntime().maxMemory() / 8;
        return Math.max(MIN_MEMORY_BUDGET, Math.min(MAX_MEMORY_BUDGET, eighth));
    }

    // ── writes ────────────────────────────────────────────────────────────

    /**
     * A fresh screenshot of {@code tabId}. Memory first (a synchronous hit for
     * the next bind), then the version bump the repository's notifyTabs
     * stamps on its snapshot, then — only when {@code persist} — the WEBP
     * write on the IO executor. Main thread; the bitmap is not copied, so
     * the caller must hand over one it will not recycle.
     */
    public void put(int tabId, @NonNull Bitmap bitmap, boolean persist) {
        if (bitmap.isRecycled()) {
            return;
        }
        mMemory.put(tabId, bitmap);
        bump(tabId);
        if (persist) {
            mIoExecutor.execute(() -> write(tabId, bitmap));
        }
    }

    /**
     * The tab is gone (closed, archived, deleted with its mode): memory and
     * the file index at once, the file itself on the IO executor. Bumps the
     * version so a snapshot of a re-added tab with the same id (undo-close)
     * diffs as changed and rebinds to the placeholder.
     */
    public void remove(int tabId) {
        mMemory.remove(tabId);
        mOnDisk.remove(tabId);
        bump(tabId);
        mIoExecutor.execute(() -> {
            deleteQuietly(fileFor(tabId));
            deleteQuietly(tmpFor(tabId));
            // The write task of a put that preceded this remove on the
            // executor re-added the id; a dead tab must not read as on disk
            // (its id can come back through undo-close).
            mOnDisk.remove(tabId);
        });
    }

    /** Every screenshot, both tiers. Nothing in the app calls this today —
     *  deletions are per id so the modes don't blank each other — it exists
     *  for a future "delete browsing data" sweep and the harness. */
    public void clear() {
        mMemory.evictAll();
        mOnDisk.clear();
        mVersions.clear();
        mIoExecutor.execute(() -> {
            File[] files = mDir.listFiles();
            if (files == null) {
                return;
            }
            for (File f : files) {
                deleteQuietly(f);
            }
        });
    }

    /**
     * After a committed persist: delete the disk tier's files for tabs the
     * sessions file no longer references, with the grace the class doc
     * explains. {@code liveTabIds} is copied, so the caller's set may move on.
     */
    public void prune(Collection<Integer> liveTabIds) {
        Set<Integer> live = new HashSet<>(liveTabIds);
        mIoExecutor.execute(() -> pruneNow(live));
    }

    // ── reads (main thread, no IO) ────────────────────────────────────────

    /** The memory tier's bitmap for the tab, or null. A hit counts as a use
     *  for the LRU — what the grid is showing is what it keeps. */
    @Nullable
    public Bitmap peek(int tabId) {
        return mMemory.get(tabId);
    }

    /** The version of the tab's screenshot: changes on every put and remove,
     *  0 for a tab this store has never seen. Snapshots carry it; Glide keys
     *  the file decode on it. */
    public int version(int tabId) {
        Integer v = mVersions.get(tabId);
        return v == null ? 0 : v;
    }

    /** Whether the disk tier holds a file for the tab. Optimistic (true)
     *  until the construction-time index has landed, so the first grid of a
     *  process never stats on the main thread and never shows placeholders
     *  for tabs whose files exist. */
    public boolean hasFile(int tabId) {
        return !mIndexed || mOnDisk.contains(tabId);
    }

    /** The disk-tier file for the tab — a path, no IO; may not exist. */
    @NonNull
    public File fileFor(int tabId) {
        return new File(mDir, tabId + EXT);
    }

    // ── internals ─────────────────────────────────────────────────────────

    private void bump(int tabId) {
        mVersions.put(tabId, mVersionClock.incrementAndGet());
    }

    private File tmpFor(int tabId) {
        return new File(mDir, tabId + TMP_EXT);
    }

    /** The tab id a store file name carries, else null — a tmp, a legacy
     *  {@code <id>_<timestamp>.png} from before the store, anything foreign. */
    @Nullable
    static Integer idOf(String name) {
        if (!name.endsWith(EXT)) {
            return null;
        }
        String stem = name.substring(0, name.length() - EXT.length());
        if (stem.isEmpty() || stem.length() > MAX_ID_CHARS) {
            return null;
        }
        try {
            return Integer.parseInt(stem);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** IO executor, first task queued: what the disk tier holds. */
    private void index() {
        File[] files = mDir.listFiles();
        if (files != null) {
            for (File f : files) {
                Integer id = idOf(f.getName());
                if (id != null) {
                    mOnDisk.add(id);
                }
            }
        }
        mIndexed = true;
    }

    /** IO executor. A failure leaves no tmp behind and no index entry; the
     *  memory tier still serves the tab for this process. */
    private void write(int tabId, Bitmap bitmap) {
        if (bitmap.isRecycled()) {
            return;
        }
        File tmp = tmpFor(tabId);
        File target = fileFor(tabId);
        try {
            if (!mDir.isDirectory() && !mDir.mkdirs() && !mDir.isDirectory()) {
                throw new IOException("cannot create " + mDir);
            }
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                if (!bitmap.compress(compressFormat(), WEBP_QUALITY, out)) {
                    throw new IOException("compress failed");
                }
            }
            if (!tmp.renameTo(target)) {
                deleteQuietly(target);
                if (!tmp.renameTo(target)) {
                    throw new IOException("rename failed: " + target);
                }
            }
            mOnDisk.add(tabId);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tmp);
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "thumbnail write failed for tab " + tabId, e);
            }
        }
    }

    /** IO executor. */
    private void pruneNow(Set<Integer> live) {
        File[] files = mDir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - PRUNE_GRACE_MS;
        for (File f : files) {
            Integer id = idOf(f.getName());
            if (id != null && live.contains(id)) {
                continue;
            }
            if (f.lastModified() > cutoff) {
                // Young and unreferenced: a tab the last persist's snapshot
                // missed, kept for the next one to reference.
                continue;
            }
            boolean deleted = f.delete();
            if (deleted && id != null) {
                mOnDisk.remove(id);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static Bitmap.CompressFormat compressFormat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Bitmap.CompressFormat.WEBP_LOSSY;
        }
        // Below API 30 the plain WEBP constant is lossy for any quality < 100.
        return Bitmap.CompressFormat.WEBP;
    }

    private static void deleteQuietly(File f) {
        if (f.exists() && !f.delete() && BuildConfig.DEBUG) {
            Log.w(TAG, "could not delete " + f);
        }
    }
}

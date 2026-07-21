package com.solarized.firedown.data;

import android.content.Context;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sidecar store for per-tab serialized Gecko session states — the v3 sessions
 * file keeps only a file REFERENCE per tab ({@code session_ref}) and the state
 * string itself lives here, loaded lazily inside
 * {@code GeckoState.getOrCreateGeckoSession()}. Heap cost of the tab list
 * becomes O(opened tabs) instead of O(all tabs): a 300-tab user boots holding
 * 300 small metadata rows and ZERO history strings.
 *
 * <p>This is the Chromium-on-Android model ({@code TabStateFileManager} /
 * {@code TabPersistentStoreImpl} — per-tab {@code tab<id>} files + a small
 * metadata file), adopted WITH the fixes Chromium had to learn the hard way.
 * The lessons and how each is handled here:
 *
 * <ul>
 *   <li><b>Per-file atomicity, never crash on a failed save</b> (Chromium wraps
 *       every tab-state save in {@code AtomicFile} with
 *       {@code safelyFinishOrFailWrite}/{@code safelyFailWrite} and a
 *       catch-Throwable): files here are written tmp → rename and — stronger
 *       than Chromium — are IMMUTABLE: content-hash naming means an existing
 *       file is never rewritten, so the partial-overwrite-corrupts-good-state
 *       class of bugs cannot exist at all. A failed externalize degrades to
 *       {@code ""} (the tab restores by URL), never an exception.</li>
 *   <li><b>Per-file failure containment on restore</b> (Chromium's
 *       {@code restoreTabState} returns null on FileNotFound/IO/Throwable and
 *       the tab falls back to loading its URL — one corrupt file loses one
 *       tab's history, never the boot): {@link #read} returns {@code ""} on
 *       any failure and the caller clears the dangling ref, which routes
 *       {@code setGeckoViewSession} to its plain loadUri branch.</li>
 *   <li><b>ID-collision file clobbering can't happen</b> (Chromium names files
 *       {@code tab<id>} and had duplicate-tab-id bugs — metadata merges after
 *       app death produced two tabs with one id, plus dedupe-rewrite logic):
 *       names here derive from CONTENT (SHA-1), not identity, so two tabs with
 *       colliding ids — or the same restored state — simply share one
 *       immutable file.</li>
 *   <li><b>Cleanup must consult every reference domain, from persisted truth</b>
 *       (Chromium's multi-window cleanup re-reads ALL other instances' metadata
 *       files from disk before deleting a tab file — crbug.com/40486025 is the
 *       "never destroy what you haven't proven unreferenced" lesson): Firedown
 *       has two persisted reference domains — the live sessions file and the
 *       Room tab ARCHIVE. The invariant: the archive NEVER holds refs (the
 *       state string is inlined into the Room row at archive time —
 *       {@code TabStateArchivedRepository.mapToArchivedEntity}), so the
 *       referenced set handed to {@link #prune} (built from exactly what the
 *       just-committed metadata references) is complete by construction.</li>
 *   <li><b>Cleanup only after load, single-flight</b> (Chromium runs
 *       {@code cleanUpPersistentData()} only once the full state load
 *       finished, holds {@code CLEAN_UP_TASK_LOCK}, and cancels cleanup when
 *       another tab model spins up): prune runs only from a COMMITTED persist,
 *       and every store operation lives on the single FIFO DiskIO executor —
 *       the boot read (enqueued at DI time) always precedes it, and
 *       single-flight is by construction.</li>
 *   <li><b>Deferred deletion for undo/races</b> (Chromium routes deletions
 *       through {@code canTabStateBeDeleted} so an undo-able closure or another
 *       window's tab is never deleted under the user): prune here is
 *       GRACE-BASED — every referenced file's mtime is touched on each persist,
 *       and an unreferenced file is deleted only once its mtime is older than
 *       {@link #RETENTION_MS}. A close-undo within the window finds its file
 *       intact; navigation-superseded states linger bounded hours, not
 *       forever. (A {@code .tmp} orphaned by process death is covered by the
 *       same rule — never referenced, so it dies once stale.)</li>
 *   <li><b>Dirty-only saves</b> (Chromium keeps a deduped save queue and skips
 *       CLEAN tabs): content-hash naming gives this for free — an unchanged
 *       state re-hashes to an existing file and {@link #externalizeToDir}
 *       skips the write entirely.</li>
 * </ul>
 *
 * <p>The IO core ({@link #externalizeToDir}, {@link #readFromFile},
 * {@link #pruneDir}) is deliberately pure java.io (no android.*) so the
 * verification harness runs the REAL code on the JVM — the same rule
 * {@code TabIconStore} follows.
 */
public final class SessionStateStore {

    private static final String DIR = "tab_states";
    private static final String PREFIX = "ss_";
    /** How long an unreferenced state file survives after its last persist
     *  reference (close-undo / race grace). Referenced files are re-touched on
     *  every persist, so only genuinely orphaned files age past this. */
    public static final long RETENTION_MS = 24L * 60 * 60 * 1000;
    /** Sanity cap for {@link #read} — a session-state file beyond this is not a
     *  session state (Gecko caps per-tab history at ~50 entries; real states
     *  are KBs to a few hundred KB). Prevents a corrupted/foreign file from
     *  ballooning the heap on the read path. */
    public static final int MAX_STATE_BYTES = 16 * 1024 * 1024;
    /** Count bound on the UNREFERENCED backlog. The grace window alone is only
     *  a TIME bound: onSessionStateChange fires for scroll/form churn too, so
     *  a heavy browsing day can legally pile thousands of young superseded
     *  files before any turns 24 h old — and a backward clock jump gives files
     *  future mtimes that never look stale at all. After the age pass, prune
     *  keeps at most this many unreferenced files (newest-by-mtime win) and
     *  deletes the oldest beyond it. Generous on purpose: every undo/race the
     *  grace protects needs a handful of files for minutes, not hundreds for
     *  hours — the cap only bites pathological churn. */
    public static final int MAX_UNREFERENCED_FILES = 512;

    private SessionStateStore() {
    }

    /**
     * Persists a session-state string as an immutable content-hash-named file
     * and returns its absolute path, or {@code ""} on any failure (the tab
     * then restores by URL — Chromium's degrade-don't-throw save contract).
     */
    public static String externalize(Context context, String state) {
        return externalizeToDir(storeDir(context), state);
    }

    /**
     * Loads a state string back from {@code ref}; {@code ""} if the ref is
     * empty, the file is missing/oversized, or the read fails — per-file
     * containment, the caller treats it as "no persisted state".
     * Context-free on purpose: refs are absolute paths (the THUMB/tab_icons
     * precedent), so the lazy load inside {@code getOrCreateGeckoSession}
     * needs no plumbing.
     */
    public static String read(String ref) {
        if (ref == null || ref.isEmpty()) {
            return "";
        }
        return readFromFile(new File(ref));
    }

    /**
     * Re-anchors a persisted ref to THIS install's store directory by
     * basename. Refs are stored as absolute paths (the THUMB/tab_icons
     * precedent), but a user-profile transfer / OEM phone-clone migrates the
     * files while {@code filesDir}'s prefix changes — a verbatim ref would
     * miss and silently degrade every transferred tab to a URL-only restore.
     * Basename resolution makes the ref relocation-proof; for a normal
     * install it returns the same path unchanged. Applied at READ time
     * (readEntityStrict), so the next persist rewrites the corrected path.
     */
    public static String resolve(Context context, String ref) {
        return resolveToDir(storeDir(context), ref);
    }

    /** True if {@code ref} points into this store — used to build the
     *  referenced set {@link #prune} keeps. */
    public static boolean isStorePath(Context context, String ref) {
        if (ref == null || ref.isEmpty()) {
            return false;
        }
        return ref.startsWith(storeDir(context).getAbsolutePath() + File.separator);
    }

    /**
     * Grace-pruning pass, run after every COMMITTED persist with exactly the
     * set of refs the now-live metadata file references (see the class doc for
     * why that set is complete). Touches referenced files, deletes
     * unreferenced ones older than {@link #RETENTION_MS}.
     */
    public static void prune(Context context, Set<String> referencedPaths) {
        pruneDir(storeDir(context), referencedPaths, System.currentTimeMillis());
    }

    private static File storeDir(Context context) {
        return new File(context.getFilesDir(), DIR);
    }

    // ── pure IO core (JVM-testable) ───────────────────────────────────────

    /** See {@link #resolve}; {@code dir} is the store directory. */
    public static String resolveToDir(File dir, String ref) {
        if (ref == null || ref.isEmpty()) {
            return "";
        }
        return new File(dir, new File(ref).getName()).getAbsolutePath();
    }

    /** See {@link #externalize}; {@code dir} is the store directory. */
    public static String externalizeToDir(File dir, String state) {
        if (state == null || state.isEmpty()) {
            return "";
        }
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return "";
            }
            byte[] bytes = state.getBytes(StandardCharsets.UTF_8);
            File out = new File(dir, PREFIX + sha1Hex(bytes));
            // Immutable content-hash file: existing = identical bytes, skip
            // (this IS the dirty-tracking — an unchanged state costs nothing).
            if (!out.exists()) {
                File tmp = new File(dir, out.getName() + ".tmp");
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    fos.write(bytes);
                    fos.getFD().sync();
                }
                if (!tmp.renameTo(out)) {
                    FileUtils.deleteQuietly(tmp);
                    return "";
                }
            }
            return out.getAbsolutePath();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** See {@link #read}. */
    public static String readFromFile(File file) {
        try {
            long length = file.length();
            if (length <= 0 || length > MAX_STATE_BYTES) {
                return "";
            }
            byte[] bytes = new byte[(int) length];
            try (InputStream in = new FileInputStream(file)) {
                int off = 0;
                while (off < bytes.length) {
                    int n = in.read(bytes, off, bytes.length - off);
                    if (n < 0) {
                        // File shrank under us (prune race is excluded by the
                        // grace window, but stay total anyway).
                        return "";
                    }
                    off += n;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** See {@link #prune}; {@code nowMs} injected for testability. Two passes:
     *  age (grace expired) and then the {@link #MAX_UNREFERENCED_FILES} count
     *  cap over the young survivors, oldest-mtime first — so the store is
     *  bounded in TIME and in COUNT, whatever the churn rate or the clock. */
    public static void pruneDir(File dir, Set<String> referencedPaths, long nowMs) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = nowMs - RETENTION_MS;
        List<File> unreferenced = new ArrayList<>();
        for (File file : files) {
            if (referencedPaths.contains(file.getAbsolutePath())) {
                // Keep the grace clock fresh: "referenced as of this persist".
                // Best-effort — a failed touch only shortens the grace.
                file.setLastModified(nowMs);
            } else if (file.lastModified() < cutoff) {
                FileUtils.deleteQuietly(file);
            } else {
                unreferenced.add(file);
            }
        }
        int excess = unreferenced.size() - MAX_UNREFERENCED_FILES;
        if (excess <= 0) {
            return;
        }
        // Oldest first — future-mtime files (a backward clock jump) sort
        // newest and are kept, but the TOTAL stays capped regardless.
        unreferenced.sort(Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < excess; i++) {
            FileUtils.deleteQuietly(unreferenced.get(i));
        }
    }

    private static String sha1Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is mandatory on every Java/Android runtime.
            throw new AssertionError(e);
        }
    }
}

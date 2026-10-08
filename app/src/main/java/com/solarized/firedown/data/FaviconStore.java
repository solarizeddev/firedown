package com.solarized.firedown.data;

import android.content.Context;

import androidx.annotation.VisibleForTesting;

import com.solarized.firedown.data.di.Qualifiers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * Firedown's own favicon store: the icon BYTES, kept by us, keyed by icon URL —
 * the Firefox (favicons.sqlite) / Chrome (Favicons db) model. Icons used to live
 * only in Glide's general disk cache: 250 MB shared with every captured
 * thumbnail, so favicons were evicted by unrelated images (letters offline), and
 * keyed by URL with no expiry, so an icon replaced behind the same URL never
 * updated.
 *
 * <ul>
 *   <li><b>Read</b> by the Glide favicon loader ({@code FaviconModelLoader}); a
 *       stored icon never touches the network to display.</li>
 *   <li><b>Refreshed on VISIT</b>, like the big browsers: when a page reports its
 *       icon ({@code IconsRepository}, regular tabs only) and the stored copy is
 *       missing or older than {@link #REFRESH_MS}. A failed or non-image
 *       refetch keeps the old bytes, so an icon the site stopped serving keeps
 *       showing instead of decaying to the generated letter.</li>
 *   <li><b>Never written from a private tab</b>: the file is a record that a
 *       site was visited. Private surfaces load with persist off.</li>
 *   <li><b>Cleared with history</b>: deleting all history clears it; a range
 *       delete removes the icons fetched inside the range
 *       ({@link #deleteFetchedSince}). A file's mtime is its last fetch (or
 *       last successful revalidation), so that rule is exact for what the
 *       store can witness.</li>
 *   <li><b>Bounded</b>: per-icon {@link #MAX_ICON_BYTES}, and a count/bytes cap
 *       enforced every {@link #PRUNE_EVERY_WRITES} writes, oldest-fetched
 *       first.</li>
 * </ul>
 *
 * <p>A Hilt singleton (the app's one way to share an instance); the file half is
 * pure java.io, so the verification harness runs the REAL class on the JVM
 * through the {@code (File, Executor)} constructor (the TabIconStore rule).
 */
@Singleton
public final class FaviconStore {

    /** A stored icon older than this is refetched on the next visit to a page
     *  that names it. */
    public static final long REFRESH_MS = TimeUnit.DAYS.toMillis(7);
    /** Larger than any real favicon; an "icon" past this is not one. */
    public static final int MAX_ICON_BYTES = 512 * 1024;
    static final int MAX_FILES = 2000;
    static final long MAX_TOTAL_BYTES = 24L * 1024 * 1024;
    static final int PRUNE_EVERY_WRITES = 32;

    private static final String DIR = "favicons";

    private final File mDir;
    private final Executor mDiskExecutor;
    // Bumped when an icon's bytes CHANGE, so Glide's memory cache (keyed by
    // model) doesn't keep painting the old bitmap. Process-lived on purpose:
    // the memory cache is too.
    private final ConcurrentHashMap<String, Integer> mGenerations = new ConcurrentHashMap<>();
    // Bumped by clear()/deleteFetchedSince(): every key minted before is dead.
    private final AtomicInteger mEpoch = new AtomicInteger();
    private final Set<String> mRefreshing = ConcurrentHashMap.newKeySet();
    private final AtomicInteger mWritesSincePrune = new AtomicInteger();

    @Inject
    public FaviconStore(@ApplicationContext Context context, @Qualifiers.DiskIO Executor diskExecutor) {
        this(new File(context.getFilesDir(), DIR), diskExecutor);
    }

    /** The file half on its own: the harness's door. */
    @VisibleForTesting
    public FaviconStore(File dir, Executor diskExecutor) {
        mDir = dir;
        mDiskExecutor = diskExecutor;
    }

    /** The file an icon is (or would be) stored in. No IO. */
    public File fileFor(String iconUrl) {
        return new File(mDir, sha1Hex(iconUrl));
    }

    /** Part of the Glide memory-cache key: changes when the stored bytes change
     *  or the store is cleared. No IO — safe on the main thread at bind. */
    public String cacheVersion(String iconUrl) {
        Integer generation = mGenerations.get(iconUrl);
        return mEpoch.get() + "." + (generation == null ? 0 : generation);
    }

    /** True when a visit should refetch: no stored copy, or it is stale. */
    public boolean needsRefresh(String iconUrl, long nowMs) {
        File file = fileFor(iconUrl);
        if (!file.isFile()) {
            return true;
        }
        return nowMs - file.lastModified() >= REFRESH_MS;
    }

    /** Single-flight claim for a visit refresh; pair with {@link #endRefresh}. */
    public boolean beginRefresh(String iconUrl) {
        return mRefreshing.add(iconUrl);
    }

    public void endRefresh(String iconUrl) {
        mRefreshing.remove(iconUrl);
    }

    /**
     * Stores fetched bytes for an icon. Refuses anything that is not an image
     * (a 200 HTML login wall or error page must never replace good bytes) or is
     * oversized. Identical bytes only reset the staleness clock (no rewrite, no
     * generation bump). Returns true when the stored bytes changed.
     */
    public boolean store(String iconUrl, byte[] bytes, long nowMs) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_ICON_BYTES || !looksLikeImage(bytes)) {
            return false;
        }
        File file = fileFor(iconUrl);
        try {
            if (file.isFile() && file.length() == bytes.length
                    && Arrays.equals(Files.readAllBytes(file.toPath()), bytes)) {
                file.setLastModified(nowMs);
                return false;
            }
            if (!mDir.isDirectory() && !mDir.mkdirs()) {
                return false;
            }
            File tmp = new File(mDir, file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            if (!tmp.renameTo(file)) {
                tmp.delete();
                return false;
            }
            file.setLastModified(nowMs);
        } catch (IOException | RuntimeException e) {
            return false;
        }
        mGenerations.merge(iconUrl, 1, Integer::sum);
        if (mWritesSincePrune.incrementAndGet() >= PRUNE_EVERY_WRITES) {
            mWritesSincePrune.set(0);
            prune();
        }
        return true;
    }

    /** Deletes every stored icon (history cleared, cache cleared). Blocking. */
    public void clear() {
        deleteFetchedSince(Long.MIN_VALUE);
    }

    /** {@link #clear()} on the disk executor, for a UI action — the same lane
     *  the history deletes run on, so a clear and a delete keep their order.
     *  The cache version moves at once, so nothing bound meanwhile keeps a
     *  cleared icon's key. */
    public void clearInBackground() {
        mEpoch.incrementAndGet();
        mDiskExecutor.execute(this::clear);
    }

    /** Deletes the icons fetched (or revalidated) at or after {@code sinceMs} —
     *  the ones that can witness visits inside a deleted history range. */
    public void deleteFetchedSince(long sinceMs) {
        mEpoch.incrementAndGet();
        File[] files = mDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.lastModified() >= sinceMs) {
                file.delete();
            }
        }
    }

    /** Enforces the count/bytes cap, oldest fetch first, down to 90% of each. */
    void prune() {
        File[] files = mDir.listFiles();
        if (files == null) {
            return;
        }
        List<File> list = new ArrayList<>(Arrays.asList(files));
        long total = 0;
        for (File file : list) {
            total += file.length();
        }
        if (list.size() <= MAX_FILES && total <= MAX_TOTAL_BYTES) {
            return;
        }
        list.sort(Comparator.comparingLong(File::lastModified));
        int count = list.size();
        for (File file : list) {
            if (count <= MAX_FILES * 9 / 10 && total <= MAX_TOTAL_BYTES * 9 / 10) {
                break;
            }
            long length = file.length();
            if (file.delete()) {
                count--;
                total -= length;
            }
        }
    }

    /** True for the image formats a favicon arrives in: PNG, GIF, JPEG, ICO/CUR,
     *  BMP, WEBP, AVIF/HEIF (ISO-BMFF ftyp) and SVG (an svg element near the
     *  start). Anything else — an HTML page, JSON, an empty 200 — is refused. */
    public static boolean looksLikeImage(byte[] b) {
        if (b == null || b.length < 4) {
            return false;
        }
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        int b2 = b[2] & 0xff;
        int b3 = b[3] & 0xff;
        if (b0 == 0x89 && b1 == 'P' && b2 == 'N' && b3 == 'G') {
            return true;
        }
        if (b0 == 'G' && b1 == 'I' && b2 == 'F') {
            return true;
        }
        if (b0 == 0xff && b1 == 0xd8) {
            return true;
        }
        if (b0 == 0 && b1 == 0 && (b2 == 1 || b2 == 2) && b3 == 0) {
            return true;  // ICO / CUR
        }
        if (b0 == 'B' && b1 == 'M') {
            return true;
        }
        if (b.length >= 12 && b0 == 'R' && b1 == 'I' && b2 == 'F' && b3 == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return true;
        }
        if (b.length >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            String brand = new String(b, 8, 4, StandardCharsets.ISO_8859_1);
            return IMAGE_FTYP_BRANDS.contains(brand);  // not any ISO-BMFF: an mp4 is one too
        }
        int head = Math.min(b.length, 1024);
        String start = new String(b, 0, head, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        // An HTML error page can carry an inline <svg> logo near the top.
        return start.contains("<svg") && !start.contains("<html") && !start.contains("<!doctype html");
    }

    private static final Set<String> IMAGE_FTYP_BRANDS =
            Set.of("avif", "avis", "heic", "heix", "mif1", "msf1");

    private static String sha1Hex(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is mandatory on every Java/Android runtime.
            throw new AssertionError(e);
        }
    }
}

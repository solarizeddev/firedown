package com.solarized.firedown.harness;

import android.graphics.Bitmap;

import com.solarized.firedown.data.TabThumbnailStore;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.Executor;

/** Drives the real TabThumbnailStore; see run.sh for what is checked. */
public final class ThumbHarness {

    private static int failures;
    private static int passes;

    /** The IO executor, driven by hand: tasks run only when drain() is called,
     *  so the harness can look at the store BETWEEN a put and its write. */
    private static final class Queue implements Executor {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable r) { tasks.add(r); }
        void drain() { Runnable r; while ((r = tasks.poll()) != null) r.run(); }
    }

    private static void check(String name, boolean ok) {
        if (ok) { passes++; System.out.println("ok    " + name); }
        else { failures++; System.out.println("FAIL  " + name); }
    }

    private static final int W = 100, Hh = 100;
    private static final long THUMB_BYTES = (long) W * Hh * 4;

    private static Bitmap shot(int salt) { return new Bitmap(W, Hh, salt); }

    private static String content(File f) throws Exception {
        return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;
    }

    private static void touch(File f, String text, long mtime) throws Exception {
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        if (!f.setLastModified(mtime)) throw new IllegalStateException("setLastModified " + f);
    }

    private static int webpCount(File dir) {
        String[] names = dir.list((d, n) -> n.endsWith(".webp"));
        return names == null ? 0 : names.length;
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        try {
            run(root);
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL  harness threw: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println(failures == 0 ? "all " + passes + " passed" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run(File root) throws Exception {
        long now = System.currentTimeMillis();
        long hour = 3_600_000L;

        // ── a store over a directory a previous process left behind ───────
        File dir = new File(root, "thumbs");
        dir.mkdirs();
        touch(new File(dir, "7.webp"), "RIFF old capture of 7", now - hour);
        touch(new File(dir, "12_1700000000000.png"), "legacy png", now - hour);
        touch(new File(dir, "junk.txt"), "foreign", now - hour);
        touch(new File(dir, "8.webp.tmp"), "torn", now - hour);
        Queue io = new Queue();
        TabThumbnailStore store = new TabThumbnailStore(dir, io, 3 * THUMB_BYTES);
        check("before the index lands, hasFile is optimistic (no main-thread stat)", store.hasFile(99));
        io.drain();
        check("index: a <id>.webp from a previous process is known", store.hasFile(7));
        check("index: a legacy <id>_<timestamp>.png is not a thumbnail", !store.hasFile(12));
        check("index: an unknown id has no file", !store.hasFile(99));
        check("index: a torn .tmp is not a thumbnail", !store.hasFile(8));
        check("a never-seen tab has version 0", store.version(1) == 0);

        // ── put: memory first, file later ─────────────────────────────────
        Bitmap a1 = shot(1);
        store.put(1, a1, true);
        check("a put is a memory hit before anything is written", store.peek(1) == a1 && !store.fileFor(1).exists());
        int v1 = store.version(1);
        check("a put bumps the version", v1 > 0);
        io.drain();
        check("the write lands as <id>.webp with the capture's bytes",
                store.fileFor(1).isFile() && content(store.fileFor(1)).contains("salt=1"));
        check("...as lossy WEBP", content(store.fileFor(1)).contains("WEBP_LOSSY") && content(store.fileFor(1)).contains("q80"));
        check("...with no tmp left behind", !new File(dir, "1.webp.tmp").exists());
        check("...and the index knows it", store.hasFile(1));
        Bitmap a2 = shot(2);
        store.put(1, a2, true);
        check("a recapture is a new version", store.version(1) > v1);
        check("...served from memory at once", store.peek(1) == a2);
        io.drain();
        check("the file holds the newest capture", content(store.fileFor(1)).contains("salt=2"));
        check("one file per tab, whatever the capture count", webpCount(dir) == 2);

        // ── incognito: memory only ────────────────────────────────────────
        Bitmap b = shot(3);
        store.put(2, b, false);
        check("an incognito put is a memory hit", store.peek(2) == b);
        io.drain();
        check("an incognito screenshot is never written", !store.fileFor(2).exists() && !store.hasFile(2));

        // ── the byte budget evicts least recently USED ────────────────────
        Queue io2 = new Queue();
        TabThumbnailStore budget = new TabThumbnailStore(new File(root, "budget"), io2, 3 * THUMB_BYTES);
        io2.drain();
        Bitmap t1 = shot(11), t2 = shot(12), t3 = shot(13), t4 = shot(14);
        budget.put(1, t1, true);
        budget.put(2, t2, true);
        budget.put(3, t3, true);
        check("three thumbs fit a three-thumb budget", budget.peek(1) == t1 && budget.peek(2) == t2 && budget.peek(3) == t3);
        budget.peek(1); // a bind of tab 1: it is now the most recently used
        budget.put(4, t4, true);
        check("a fourth evicts the least recently USED (2), not the oldest put (1)",
                budget.peek(2) == null && budget.peek(1) == t1 && budget.peek(3) == t3 && budget.peek(4) == t4);
        io2.drain();
        check("an evicted regular tab still has its file for the disk tier", budget.hasFile(2) && budget.fileFor(2).isFile());
        Bitmap recycled = shot(15);
        recycled.recycle();
        budget.put(5, recycled, true);
        check("a recycled bitmap is refused", budget.peek(5) == null && budget.version(5) == 0);

        // ── remove ────────────────────────────────────────────────────────
        int before = store.version(1);
        store.remove(1);
        check("remove drops the memory tier at once", store.peek(1) == null);
        check("remove drops the file index at once", !store.hasFile(1));
        check("remove bumps the version (an undo-close rebinds)", store.version(1) > before);
        io.drain();
        check("remove deletes the file", !store.fileFor(1).exists());
        int afterRemove = store.version(1);
        store.put(1, shot(4), true);
        check("a re-put after remove gets a version above every earlier one", store.version(1) > afterRemove);
        io.drain();
        // a put whose write is still queued when the remove lands
        store.put(3, shot(5), true);
        store.remove(3);
        io.drain();
        check("put-then-remove before the executor ran: no file, not indexed", !store.fileFor(3).exists() && !store.hasFile(3));

        // ── prune ─────────────────────────────────────────────────────────
        Queue io3 = new Queue();
        File pdir = new File(root, "prune");
        pdir.mkdirs();
        touch(new File(pdir, "1.webp"), "live", now - hour);
        touch(new File(pdir, "2.webp"), "dead, old", now - hour);
        touch(new File(pdir, "4.webp"), "unreferenced but young", now);
        touch(new File(pdir, "9_1700000000000.png"), "legacy", now - hour);
        touch(new File(pdir, "junk.txt"), "foreign", now - hour);
        touch(new File(pdir, "6.webp.tmp"), "torn", now - hour);
        TabThumbnailStore pruned = new TabThumbnailStore(pdir, io3, 3 * THUMB_BYTES);
        io3.drain();
        pruned.prune(Set.of(1, 3));
        io3.drain();
        check("prune keeps a referenced file", pruned.fileFor(1).isFile() && pruned.hasFile(1));
        check("prune deletes an unreferenced file past the grace", !pruned.fileFor(2).exists() && !pruned.hasFile(2));
        check("prune keeps a young unreferenced file (a tab the last persist missed)",
                pruned.fileFor(4).isFile() && pruned.hasFile(4));
        check("prune deletes a legacy png, a torn tmp and foreign files",
                !new File(pdir, "9_1700000000000.png").exists() && !new File(pdir, "6.webp.tmp").exists()
                        && !new File(pdir, "junk.txt").exists());
        check("prune never touches the memory tier", pruned.peek(1) == null && pruned.version(1) == 0);

        // ── clear ─────────────────────────────────────────────────────────
        store.clear();
        check("clear empties the memory tier", store.peek(1) == null && store.peek(2) == null);
        check("clear forgets the index", !store.hasFile(7));
        io.drain();
        check("clear deletes every file", webpCount(dir) == 0);
    }
}

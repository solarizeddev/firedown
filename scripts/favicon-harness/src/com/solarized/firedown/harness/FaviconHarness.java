package com.solarized.firedown.harness;

import com.solarized.firedown.data.FaviconStore;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Drives the real FaviconStore; see run.sh for what is checked. */
public final class FaviconHarness {

    private static int failures;
    private static int passes;

    private static void check(String name, boolean ok) {
        if (ok) {
            passes++;
            System.out.println("ok    " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name);
        }
    }

    private static byte[] png(int salt) {
        byte[] b = new byte[64];
        b[0] = (byte) 0x89;
        b[1] = 'P';
        b[2] = 'N';
        b[3] = 'G';
        b[10] = (byte) salt;
        return b;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        try {
            run(dir);
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL  harness threw: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println(failures == 0 ? "all " + passes + " passed" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run(File dir) throws Exception {
        FaviconStore store = new FaviconStore(dir, Runnable::run);
        String ico = "https://abs.twimg.com/favicons/twitter.3.ico";
        long day = 86_400_000L;
        long t0 = 1_791_000_000_000L;

        // ── store + read ──────────────────────────────────────────────────
        check("empty store: a visit must fetch", store.needsRefresh(ico, t0));
        String v0 = store.cacheVersion(ico);
        check("first store reports a change", store.store(ico, png(1), t0));
        File f = store.fileFor(ico);
        check("stored bytes are found by icon url", f.isFile() && Arrays.equals(Files.readAllBytes(f.toPath()), png(1)));
        check("fresh icon: no refetch on a visit", !store.needsRefresh(ico, t0 + day));
        String v1 = store.cacheVersion(ico);
        check("changed bytes bump the cache version", !v1.equals(v0));

        // ── refusals never replace good bytes ───────────────────────────────
        check("an HTML page is refused", !store.store(ico, ascii("<!DOCTYPE html><html><body>Log in</body></html>"), t0));
        check("HTML with an inline <svg> logo is refused",
                !store.store(ico, ascii("<html><head></head><body><svg viewBox='0 0 1 1'></svg>"), t0));
        check("JSON is refused", !store.store(ico, ascii("{\"error\":\"not found\"}"), t0));
        check("an empty body is refused", !store.store(ico, new byte[0], t0));
        byte[] huge = png(2);
        huge = Arrays.copyOf(huge, FaviconStore.MAX_ICON_BYTES + 1);
        check("an oversized body is refused", !store.store(ico, huge, t0));
        byte[] mp4 = new byte[16];
        System.arraycopy(ascii("\0\0\0\u0018ftypisom"), 0, mp4, 0, 12);
        check("an mp4 (non-image ISO-BMFF brand) is refused", !store.store(ico, mp4, t0));
        check("refusals left the good bytes in place", Arrays.equals(Files.readAllBytes(f.toPath()), png(1)));
        check("refusals did not bump the cache version", store.cacheVersion(ico).equals(v1));

        // ── accepted formats ───────────────────────────────────────────────
        check("ICO accepted", FaviconStore.looksLikeImage(new byte[]{0, 0, 1, 0, 1, 0}));
        check("GIF accepted", FaviconStore.looksLikeImage(ascii("GIF89a....")));
        check("JPEG accepted", FaviconStore.looksLikeImage(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0}));
        check("WEBP accepted", FaviconStore.looksLikeImage(ascii("RIFF\0\0\0\0WEBPVP8 ")));
        check("AVIF accepted", FaviconStore.looksLikeImage(ascii("\0\0\0\u001cftypavif\0\0\0\0")));
        check("SVG with an XML prolog accepted",
                FaviconStore.looksLikeImage(ascii("<?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\"/>")));

        // ── staleness and revalidation ─────────────────────────────────────
        long later = t0 + FaviconStore.REFRESH_MS;
        check("a week-old icon is refetched on the next visit", store.needsRefresh(ico, later));
        check("identical bytes report no change", !store.store(ico, png(1), later));
        check("identical bytes reset the staleness clock", !store.needsRefresh(ico, later + day));
        check("identical bytes keep the cache version", store.cacheVersion(ico).equals(v1));
        check("new bytes behind the same url replace the icon", store.store(ico, png(3), later + day));
        check("...and bump the cache version", !store.cacheVersion(ico).equals(v1));

        // ── single-flight visit refresh ────────────────────────────────────
        check("first refresh claim wins", store.beginRefresh(ico));
        check("a second concurrent claim is refused", !store.beginRefresh(ico));
        store.endRefresh(ico);
        check("the claim is released", store.beginRefresh(ico));
        store.endRefresh(ico);

        // ── deleting history ───────────────────────────────────────────────
        String old = "https://old.example/favicon.ico";
        String recent = "https://recent.example/favicon.ico";
        store.store(old, png(4), t0 - 30 * day);
        store.store(recent, png(5), t0);
        String beforeDelete = store.cacheVersion(old);
        store.deleteFetchedSince(t0 - day);
        check("range delete removes an icon fetched inside the range", !store.fileFor(recent).exists());
        check("range delete keeps an icon fetched before the range", store.fileFor(old).isFile());
        check("range delete kills cache versions minted before it", !store.cacheVersion(old).equals(beforeDelete));
        store.clear();
        check("clear() removes every icon", !store.fileFor(old).exists() && !store.fileFor(ico).exists());

        // ── the count cap prunes oldest-fetched first ──────────────────────
        int total = 2050;
        for (int i = 0; i < total; i++) {
            store.store("https://site" + i + ".example/favicon.ico", png(i & 0x7f), t0 + i * 1000L);
        }
        String[] names = dir.list();
        int count = names == null ? 0 : names.length;
        check("count cap holds (" + count + " files)", count <= 2000);
        check("the oldest-fetched icon was pruned", !store.fileFor("https://site0.example/favicon.ico").exists());
        check("the newest icon survived", store.fileFor("https://site" + (total - 1) + ".example/favicon.ico").isFile());
    }
}

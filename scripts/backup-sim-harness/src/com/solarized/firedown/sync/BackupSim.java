package com.solarized.firedown.sync;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import simapi.Phone;

/**
 * "Two phones, one place": several independent phones (each with its own
 * class loader, so its own statics, prefs, OkHttp, store and sidecar cache)
 * share a recovery code and ONE bucket (FakeS3) or ONE WebDAV server
 * (FakeDav), and drive the REAL VaultEngine / VaultObjectReader through the
 * REAL settings → BackupStores path, concurrently and interleaved at the
 * moments that matter (an upload half done, a sidecar not yet written, a
 * delete under a stream, a delete-all under an upload).
 *
 * <p>Written against the code before the 2026-10 review fixes, where four
 * checks failed (a corrupt conflict copy hiding a valid sidecar, for S3 and
 * WebDAV; a skewed phone whose first request is a HEAD; a prefix with dot
 * segments). {@code bug(...)} remains for a documented behaviour the code is
 * known to fail: it counts as a failure and is listed separately.
 * {@code regressions} counts every other failure; a planted mutant must
 * raise it.
 */
public final class BackupSim {

    static final int CHUNK = 8 * 1024 * 1024;
    static final int OVERHEAD = 34;
    static final long HOUR = TimeUnit.HOURS.toMillis(1);
    static final String PREFIX = "fam/";

    private static int passed;
    private static final List<String> failed = new ArrayList<>();
    private static final List<String> bugs = new ArrayList<>();
    private static File work;
    private static boolean quick;
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sim-driver");
        t.setDaemon(true);
        return t;
    });
    private static final List<SimServers.S3> S3_SERVERS = new ArrayList<>();

    private static Phone a;
    private static Phone b;
    private static Phone c;
    private static Phone d;

    public static void main(String[] args) throws Exception {
        work = new File(args[0]);
        quick = "1".equals(System.getenv("SIM_QUICK"));
        long t0 = System.currentTimeMillis();
        a = phone("A");
        b = phone("B");
        c = phone("C");
        d = phone("D");
        a.setCode(code(1));
        b.setCode(code(1));
        d.setCode(code(1));
        c.setCode(code(2));
        d.setClockOffset(-3 * HOUR);
        check("phones: four class loaders, one recovery code on A, B and D",
                a.account().equals(b.account()) && a.account().equals(d.account())
                        && !a.account().equals(c.account())
                        && a.getClass().getClassLoader() != b.getClass().getClassLoader());

        if (!quick) {
            run("s3: end to end (backup, list, stream with seeks, restore) across phones",
                    BackupSim::s3EndToEnd);
        }
        run("s3: both phones back up at once", BackupSim::s3Concurrent);
        run("s3: a listing while another phone's upload is half done", BackupSim::s3MidUpload);
        run("s3: a sidecar not yet written, and the 24 h orphan sweep", BackupSim::s3SidecarPending);
        run("s3: a delete under another phone's stream", BackupSim::s3DeleteWhileStreaming);
        run("s3: the sidecar cache after another phone rewrites or deletes", BackupSim::s3Cache);
        run("s3: delete-all under another phone's upload", BackupSim::s3DeleteAllWhileUploading);
        run("s3: two recovery codes in one bucket", BackupSim::s3Isolation);
        run("s3: conflict copies of a sidecar", BackupSim::s3Conflicts);
        run("s3: the stale-upload sweep and another phone's long upload", BackupSim::s3StaleUploads);
        run("s3: one phone's clock is wrong", BackupSim::s3Skew);
        run("s3: many small files, paginated listings, two phones", BackupSim::s3Pagination);
        if (!quick) {
            run("s3: one phone, four workers on one store", BackupSim::s3FourWorkers);
        }
        if (!quick) {
            run("s3: a corrupted or spliced object", BackupSim::s3Corrupt);
        }
        run("s3: a backup cancelled half way", BackupSim::s3Cancel);
        run("s3: two phones backfill one entry at once", BackupSim::s3BackfillRace);
        run("s3: the same prefix spelled with a dot segment on one phone", BackupSim::s3DotPrefix);
        if (!quick) {
            run("s3: three phones, random operations, at once", BackupSim::s3Stress);
        }
        int leaks = 0;
        for (SimServers.S3 s : S3_SERVERS) {
            leaks += s.gate.leaks.get();
        }
        check("s3: no request carried the secret key in a header or URL (" + S3_SERVERS.size()
                + " servers)", leaks == 0, leaks + " requests");

        if (!quick) {
            run("dav: end to end (backup, list, stream with seeks, restore) across phones",
                    BackupSim::davEndToEnd);
        }
        run("dav: both phones back up at once", BackupSim::davConcurrent);
        run("dav: a listing while another phone's upload is half done", BackupSim::davMidUpload);
        run("dav: a sidecar not yet written, and the 24 h orphan sweep", BackupSim::davSidecarPending);
        run("dav: a delete under another phone's stream", BackupSim::davDeleteWhileStreaming);
        run("dav: the sidecar cache after another phone rewrites or deletes", BackupSim::davCache);
        run("dav: delete-all under another phone's upload", BackupSim::davDeleteAllWhileUploading);
        run("dav: two recovery codes on one server", BackupSim::davIsolation);
        run("dav: conflict copies of a sidecar", BackupSim::davConflicts);
        run("dav: a backup cancelled half way", BackupSim::davCancel);
        run("dav: two phones backfill one entry at once", BackupSim::davBackfillRace);
        if (!quick) {
            run("dav: three phones, random operations, at once", BackupSim::davStress);
        }

        System.out.println();
        System.out.println(passed + " passed, " + failed.size() + " failed");
        for (String f : failed) {
            if (!bugs.contains(f)) {
                System.out.println("  FAIL " + f);
            }
        }
        System.out.println("app bugs (failing on purpose): " + bugs.size());
        for (String bug : bugs) {
            System.out.println("  BUG  " + bug);
        }
        System.out.println("regressions: " + (failed.size() - bugs.size()));
        System.out.println("time: " + (System.currentTimeMillis() - t0) / 1000 + " s");
        System.exit(failed.isEmpty() ? 0 : 1);
    }

    // ---- the frame ----

    interface Case {
        void run() throws Exception;
    }

    /** A case that throws is a failed case (a regression), never an
     *  aborted suite. */
    private static void run(String name, Case c) {
        System.out.println("-- " + name);
        long t0 = System.currentTimeMillis();
        try {
            c.run();
        } catch (Throwable t) {
            failed.add(name + ": threw " + t);
            System.out.println("  FAIL " + name + ": threw " + t);
            t.printStackTrace(System.out);
        }
        System.out.println("   (" + (System.currentTimeMillis() - t0) + " ms)");
    }

    static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed.add(name + (detail.isEmpty() ? "" : " (" + detail + ")"));
            System.out.println("  FAIL " + name + (detail.isEmpty() ? "" : " (" + detail + ")"));
        }
    }

    /** A check of documented behaviour that today's app code fails. */
    static void bug(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name + "  [was an app bug: now fixed]");
        } else {
            String line = name + (detail.isEmpty() ? "" : " (" + detail + ")");
            failed.add(line);
            bugs.add(line);
            System.out.println("  BUG  " + line);
        }
    }

    private static Phone phone(String name) throws Exception {
        String[] parts = System.getProperty("java.class.path").split(File.pathSeparator);
        URL[] urls = new URL[parts.length];
        for (int i = 0; i < parts.length; i++) {
            urls[i] = new File(parts[i]).toURI().toURL();
        }
        ClassLoader loader = new SimServers.PhoneLoader(urls, BackupSim.class.getClassLoader());
        Class<?> k = loader.loadClass("com.solarized.firedown.sync.SimPhone");
        return (Phone) k.getConstructor(String.class, String.class).newInstance(name, work.getPath());
    }

    private static byte[] code(int seed) {
        byte[] code = new byte[32];
        Arrays.fill(code, (byte) seed);
        return code;
    }

    static byte[] data(long seed, int size) {
        byte[] out = new byte[size];
        new Random(seed).nextBytes(out);
        return out;
    }

    static Callable<InputStream> source(byte[] data) {
        return () -> new ByteArrayInputStream(data);
    }

    static <T> Future<T> async(Callable<T> c) {
        return POOL.submit(c);
    }

    /** The failure a future ended with, described by the phone that threw it;
     *  "ok" when it did not fail. */
    static String failureOf(Phone p, Future<?> f) throws InterruptedException {
        try {
            f.get(60, TimeUnit.SECONDS);
            return "ok";
        } catch (ExecutionException e) {
            return p.describe(e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            return "timeout";
        }
    }

    static String failureOf(Phone p, Case c) {
        try {
            c.run();
            return "ok";
        } catch (Throwable t) {
            return p.describe(t);
        }
    }

    static Set<String> ids(List<Map<String, Object>> entries) {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> e : entries) {
            out.add((String) e.get("objectId"));
        }
        return out;
    }

    static Map<String, Object> entry(List<Map<String, Object>> entries, String id) {
        for (Map<String, Object> e : entries) {
            if (id.equals(e.get("objectId"))) {
                return e;
            }
        }
        return null;
    }

    /** Reads the stream at every interesting position (chunk boundaries,
     *  both ends) and compares with the original. */
    static boolean streamMatches(Phone p, String id, byte[] original) throws Exception {
        Phone.Reader r = p.open(id);
        try {
            if (r.length() != original.length) {
                return false;
            }
            long[] positions = {0, 1, CHUNK - 10, CHUNK, CHUNK + 1, 2L * CHUNK - 1, 2L * CHUNK + 5,
                    original.length - 7, 17, CHUNK - 1};
            for (long pos : positions) {
                if (pos < 0 || pos >= original.length) {
                    continue;
                }
                byte[] buf = new byte[(int) Math.min(4096, original.length - pos)];
                int n = r.readAt(pos, buf, 0, buf.length);
                if (n <= 0) {
                    return false;
                }
                for (int i = 0; i < n; i++) {
                    if (buf[i] != original[(int) pos + i]) {
                        return false;
                    }
                }
            }
            return r.readAt(original.length, new byte[8], 0, 8) == -1;
        } finally {
            r.close();
        }
    }

    // ---- S3 helpers ----

    static SimServers.S3 s3Server(Phone... phones) throws Exception {
        SimServers.S3 s = new SimServers.S3();
        S3_SERVERS.add(s);
        for (Phone p : phones) {
            p.useS3(s.endpoint(), s.s3.bucket, PREFIX, s.s3.accessKey, s.s3.secret);
        }
        return s;
    }

    static String objectsPrefix(Phone p) {
        return PREFIX + BackupLayout.ROOT_DIR + "/" + p.account() + "/" + BackupLayout.OBJECTS_DIR + "/";
    }

    static List<String> keysUnder(SimServers.S3 s, String prefix) {
        List<String> out = new ArrayList<>();
        for (String k : new TreeSet<>(s.s3.objects.keySet())) {
            if (k.startsWith(prefix)) {
                out.add(k);
            }
        }
        return out;
    }

    static int keysEnding(SimServers.S3 s, String prefix, String suffix) {
        int n = 0;
        for (String k : keysUnder(s, prefix)) {
            if (k.endsWith(suffix)) {
                n++;
            }
        }
        return n;
    }

    static void ageObject(SimServers.S3 s, String key, long ageMs) {
        FakeS3.Obj o = s.s3.objects.get(key);
        s.s3.objects.put(key, new FakeS3.Obj(o.data, System.currentTimeMillis() - ageMs));
    }

    static String randomId(long seed) {
        byte[] b = data(seed, 16);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    /** Waits until phone {@code ua} has listed the unfinished uploads, then
     *  a moment more for the aborts that would follow. */
    static boolean awaitSweep(SimServers.Gate gate, String ua, int before) throws InterruptedException {
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until) {
            if (gate.countQuery(ua, "GET", "uploads") > before) {
                Thread.sleep(400);
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    // ---- S3 scenarios ----

    private static void s3EndToEnd() throws Exception {
        try (SimServers.S3 s = s3Server(a, b)) {
            byte[] big = data(11, 2 * CHUNK + 3 * 1024 * 1024 + 17);
            String id = a.backup("film.mkv", big.length, source(big), "dGh1bWI", "https://example.com/v");
            List<Map<String, Object>> seen = b.list();
            Map<String, Object> e = entry(seen, id);
            check("e2e: B lists A's backup with its metadata", e != null && "film.mkv".equals(e.get("name"))
                    && Long.valueOf(big.length).equals(e.get("size")) && "dGh1bWI".equals(e.get("thumb"))
                    && Integer.valueOf(3).equals(e.get("chunkCount")), String.valueOf(e));
            check("e2e: the object is three FDVC chunks, multipart in whole chunks",
                    s.s3.objects.get(objectsPrefix(a) + id + ".fdvc").data.length == big.length + 3 * OVERHEAD
                            && s.gate.count("sim-A", "PUT", ".fdvc") == 3 && s.s3.uploads.isEmpty());
            check("e2e: B streams it with seeks across chunk boundaries", streamMatches(b, id, big));
            check("e2e: B restores it byte for byte", Arrays.equals(b.restore(id), big));
            check("e2e: A restores it byte for byte", Arrays.equals(a.restore(id), big));
            check("e2e: D (another clock, same code) restores it byte for byte",
                    failureOf(d, () -> d.useS3(s.endpoint(), s.s3.bucket, PREFIX, s.s3.accessKey, s.s3.secret))
                            .equals("ok") && Arrays.equals(d.restore(id), big));
        }
    }

    private static void s3Concurrent() throws Exception {
        try (SimServers.S3 s = s3Server(a, b)) {
            byte[] fa = data(21, CHUNK + 1024);
            byte[] fb = data(22, CHUNK + 2048);
            Future<String> ja = async(() -> a.backup("a.bin", fa.length, source(fa), null, null));
            Future<String> jb = async(() -> b.backup("b.bin", fb.length, source(fb), null, null));
            String ida = ja.get(60, TimeUnit.SECONDS);
            String idb = jb.get(60, TimeUnit.SECONDS);
            check("concurrent: different files from two phones both land",
                    ids(a.list()).equals(new TreeSet<>(Arrays.asList(ida, idb)))
                            && ids(b.list()).equals(ids(a.list())));
            check("concurrent: each restores byte for byte on the OTHER phone",
                    Arrays.equals(b.restore(ida), fa) && Arrays.equals(a.restore(idb), fb));
            check("concurrent: no unfinished upload left", s.s3.uploads.isEmpty());

            // The same file (name + size) from both phones before either
            // committed: both pass the dedup check.
            byte[] same = data(23, 300_000);
            SimServers.Paused pa = new SimServers.Paused(same, 0);
            SimServers.Paused pb = new SimServers.Paused(same, 0);
            Future<String> sa = async(() -> a.backup("same.jpg", same.length, () -> pa, null, null));
            Future<String> sb = async(() -> b.backup("same.jpg", same.length, () -> pb, null, null));
            boolean both = pa.awaitReached() && pb.awaitReached();
            pa.release();
            pb.release();
            String s1 = sa.get(60, TimeUnit.SECONDS);
            String s2 = sb.get(60, TimeUnit.SECONDS);
            int rows = 0;
            for (Map<String, Object> e : b.list()) {
                if ("same.jpg".equals(e.get("name"))) {
                    rows++;
                }
            }
            check("concurrent same file: residual, both pass dedup and the list holds TWO rows",
                    both && !s1.equals(s2) && rows == 2, rows + " rows");
            check("concurrent same file: both copies restore byte for byte",
                    Arrays.equals(a.restore(s1), same) && Arrays.equals(a.restore(s2), same));
            int objects = keysEnding(s, objectsPrefix(a), ".fdvc");
            String again = a.backup("same.jpg", same.length, source(same), null, null);
            check("dedup across phones: a third backup of it reuses an existing object",
                    (again.equals(s1) || again.equals(s2))
                            && keysEnding(s, objectsPrefix(a), ".fdvc") == objects);
            byte[] later = data(24, 70_000);
            String la = a.backup("later.txt", later.length, source(later), null, null);
            String lb = b.backup("later.txt", later.length, source(later), "dGh1bWI", null);
            check("dedup across phones: B backing up what A already backed up makes no new object",
                    la.equals(lb) && keysEnding(s, objectsPrefix(a), ".fdvc") == objects + 1);
            check("dedup across phones: and B's preview is backfilled into A's entry",
                    "dGh1bWI".equals(entry(a.list(), la).get("thumb")));
        }
    }

    private static void s3MidUpload() throws Exception {
        try (SimServers.S3 s = s3Server(a, b)) {
            byte[] big = data(31, CHUNK + 5000);
            SimServers.Paused src = new SimServers.Paused(big, CHUNK);
            Future<String> job = async(() -> a.backup("half.bin", big.length, () -> src, null, null));
            check("mid-upload: A is paused after its first part",
                    src.awaitReached() && s.s3.uploads.size() == 1, s.s3.uploads.size() + " uploads");
            int sweeps = s.gate.countQuery("sim-B", "GET", "uploads");
            List<Map<String, Object>> seen = b.list();
            check("mid-upload: B lists nothing committed", seen.isEmpty(), String.valueOf(ids(seen)));
            check("mid-upload: no half object in the bucket",
                    keysEnding(s, objectsPrefix(a), ".fdvc") == 0 && keysEnding(s, objectsPrefix(a), ".entry") == 0);
            boolean swept = awaitSweep(s.gate, "sim-B", sweeps);
            check("mid-upload: B's first listing ran its stale-upload sweep, and it spared A's fresh upload",
                    swept && s.s3.uploads.size() == 1, swept + ", " + s.s3.uploads.size() + " uploads");
            src.release();
            String id = job.get(60, TimeUnit.SECONDS);
            check("mid-upload: A's backup completes", ids(b.list()).contains(id));
            check("mid-upload: and B restores it byte for byte", Arrays.equals(b.restore(id), big));
        }
    }

    private static void s3SidecarPending() throws Exception {
        try (SimServers.S3 s = s3Server(a, b)) {
            byte[] f = data(41, 200_000);
            SimServers.Hold hold = s.gate.hold("PUT", ".entry", "sim-A");
            Future<String> job = async(() -> a.backup("pending.bin", f.length, source(f), null, null));
            check("sidecar pending: A's object is up, its sidecar held", hold.awaitArrived()
                    && keysEnding(s, objectsPrefix(a), ".fdvc") == 1);
            check("sidecar pending: B lists nothing (no commit marker yet)", b.list().isEmpty());
            check("sidecar pending: B did not sweep A's fresh object",
                    keysEnding(s, objectsPrefix(a), ".fdvc") == 1);
            String key = keysUnder(s, objectsPrefix(a)).get(0);
            ageObject(s, key, 23 * HOUR);
            b.list();
            check("sidecar pending: nor once it is 23 h old", s.s3.objects.containsKey(key));
            hold.release();
            String id = job.get(60, TimeUnit.SECONDS);
            check("sidecar pending: the commit lands and B lists it",
                    ids(b.list()).contains(id) && Arrays.equals(b.restore(id), f));

            String orphan = objectsPrefix(a) + randomId(42) + ".fdvc";
            String young = objectsPrefix(a) + randomId(43) + ".fdvc";
            String lone = objectsPrefix(a) + randomId(44) + ".entry";
            s.s3.objects.put(orphan, new FakeS3.Obj(data(45, 1000), System.currentTimeMillis() - 25 * HOUR));
            s.s3.objects.put(young, new FakeS3.Obj(data(46, 1000), System.currentTimeMillis() - 2 * HOUR));
            s.s3.objects.put(lone, new FakeS3.Obj(data(47, 1000), System.currentTimeMillis() - 30 * 24 * HOUR));
            List<Map<String, Object>> seen = b.list();
            check("orphans: a sidecar-less object over 24 h old is swept by the other phone",
                    !s.s3.objects.containsKey(orphan));
            check("orphans: a 2 h old one is kept", s.s3.objects.containsKey(young));
            check("orphans: a sidecar with no object is never deleted, never listed",
                    s.s3.objects.containsKey(lone) && seen.size() == 1);
        }
    }

    private static void s3DeleteWhileStreaming() throws Exception {
        try (SimServers.S3 s = s3Server(a, b)) {
            byte[] big = data(51, CHUNK + 4096);
            String id = a.backup("watch.mp4", big.length, source(big), null, null);
            Phone.Reader r = b.open(id);
            byte[] buf = new byte[1000];
            int n = r.readAt(100, buf, 0, buf.length);
            check("stream: B reads chunk 0", n == 1000 && Arrays.equals(buf, Arrays.copyOfRange(big, 100, 1100)));
            a.delete(id);
            check("stream: A's delete removed both files", keysUnder(s, objectsPrefix(a)).isEmpty());
            String later = failureOf(b, () -> r.readAt(CHUNK + 5, new byte[100], 0, 100));
            check("stream: B's next chunk fails as not found, no bytes", "notfound".equals(later), later);
            n = r.readAt(100, buf, 0, buf.length);
            check("stream: what B already read is still the true plaintext",
                    n == 1000 && Arrays.equals(buf, Arrays.copyOfRange(big, 100, 1100)));
            r.close();
            String reopen = failureOf(b, () -> b.open(id));
            check("stream: reopening it is not found", "notfound".equals(reopen), reopen);
        }
    }

    private static void s3Cache() throws Exception {
        try (SimServers.S3 s = s3Server(a, b)) {
            byte[] f = data(61, 90_000);
            String id = a.backup("song.mp3", f.length, source(f), null, null);
            int g0 = s.gate.count("sim-B", "GET", ".entry");
            Map<String, Object> e = entry(b.list(), id);
            int g1 = s.gate.count("sim-B", "GET", ".entry");
            b.list();
            int g2 = s.gate.count("sim-B", "GET", ".entry");
            check("cache: B reads the sidecar once, then from its cache",
                    e != null && e.get("thumb") == null && g1 - g0 == 1 && g2 == g1, (g1 - g0) + ", " + (g2 - g1));
            String same = a.backup("song.mp3", f.length, source(f), "cHJldmlldw", "https://example.com/s");
            e = entry(b.list(), id);
            int g3 = s.gate.count("sim-B", "GET", ".entry");
            check("cache: A's backfill (same object) reaches B's list",
                    same.equals(id) && e != null && "cHJldmlldw".equals(e.get("thumb"))
                            && "https://example.com/s".equals(e.get("origin")) && g3 - g2 == 1,
                    String.valueOf(e));
            a.delete(id);
            check("cache: A's delete leaves B's list", b.list().isEmpty());
            String id2 = b.backup("song2.mp3", f.length, source(f), null, null);
            a.list();
            b.delete(id2);
            check("cache: and the other way round", a.list().isEmpty());
        }
    }

    private static void s3DeleteAllWhileUploading() throws Exception {
        try (SimServers.S3 s = s3Server(a, b)) {
            byte[] big = data(71, CHUNK + 7_000);
            SimServers.Paused src = new SimServers.Paused(big, CHUNK);
            Future<String> job = async(() -> b.backup("big.bin", big.length, () -> src, null, null));
            boolean paused = src.awaitReached();
            byte[] small = data(72, 5000);
            a.backup("old.txt", small.length, source(small), null, null);
            a.deleteAll();
            check("delete-all: A's delete-all aborted B's unfinished upload (documented)",
                    paused && s.s3.uploads.isEmpty() && keysUnder(s, objectsPrefix(a)).isEmpty());
            src.release();
            String why = failureOf(b, job);
            check("delete-all: B's backup fails as a plain, retryable failure",
                    why.startsWith("server:404") || "io".equals(why), why);
            check("delete-all: and leaves nothing behind",
                    keysUnder(s, PREFIX + BackupLayout.ROOT_DIR + "/" + a.account()).isEmpty()
                            && s.s3.uploads.isEmpty());
            String retried = b.backup("big.bin", big.length, source(big), null, null);
            check("delete-all: B's retry backs it up again", ids(a.list()).equals(Set.of(retried)));

            // A delete-all landing between B's object and B's sidecar.
            a.deleteAll();
            SimServers.Hold hold = s.gate.hold("PUT", ".entry", "sim-B");
            Future<String> lone = async(() -> b.backup("lone.bin", small.length, source(small), null, null));
            boolean held = hold.awaitArrived();
            a.deleteAll();
            hold.release();
            String loneId = lone.get(60, TimeUnit.SECONDS);
            check("delete-all race: residual, B's backup reports success yet its object is gone",
                    held && b.list().isEmpty() && keysEnding(s, objectsPrefix(a), ".fdvc") == 0
                            && loneId != null);
            check("delete-all race: the lone sidecar stays (never swept), the next delete-all takes it",
                    keysEnding(s, objectsPrefix(a), ".entry") == 1
                            && failureOf(a, a::deleteAll).equals("ok")
                            && keysUnder(s, objectsPrefix(a)).isEmpty());
        }
    }

    private static void s3Isolation() throws Exception {
        try (SimServers.S3 s = s3Server(a, c)) {
            byte[] fa = data(81, 40_000);
            byte[] fc = data(82, 50_000);
            String ida = a.backup("mine.txt", fa.length, source(fa), null, null);
            String idc = c.backup("theirs.txt", fc.length, source(fc), null, null);
            check("isolation: each code lists only its own", ids(a.list()).equals(Set.of(ida))
                    && ids(c.list()).equals(Set.of(idc)));
            String from = objectsPrefix(a);
            String to = objectsPrefix(c);
            for (String k : keysUnder(s, from)) {
                FakeS3.Obj o = s.s3.objects.get(k);
                s.s3.objects.put(to + k.substring(from.length()),
                        new FakeS3.Obj(o.data, System.currentTimeMillis() - 30 * HOUR));
            }
            check("isolation: A's files copied into C's directory are not C's entries",
                    ids(c.list()).equals(Set.of(idc)));
            check("isolation: and C's listing does not delete them (an unreadable sidecar still claims its object)",
                    s.s3.objects.containsKey(to + ida + ".fdvc") && s.s3.objects.containsKey(to + ida + ".entry"));
            Map<String, Object> aEntry = entry(a.list(), ida);
            String steal = failureOf(c, () -> c.restore(aEntry));
            check("isolation: C cannot decrypt A's backup even with its metadata", "crypto".equals(steal), steal);
            c.deleteAll();
            check("isolation: C's delete-all leaves A's backups alone",
                    ids(a.list()).equals(Set.of(ida)) && Arrays.equals(a.restore(ida), fa)
                            && keysUnder(s, PREFIX + BackupLayout.ROOT_DIR + "/" + c.account()).isEmpty());
        }
    }

    private static void s3Conflicts() throws Exception {
        try (SimServers.S3 s = s3Server(a, b)) {
            byte[] f = data(91, 30_000);
            String id = a.backup("conflict.txt", f.length, source(f), null, null);
            String base = objectsPrefix(a) + id;
            FakeS3.Obj sidecar = s.s3.objects.get(base + ".entry");
            s.s3.objects.put(base + ".entry (1)", new FakeS3.Obj(sidecar.data.clone(), sidecar.modified));
            s.s3.objects.put(base + " (1).entry", new FakeS3.Obj(sidecar.data.clone(), sidecar.modified));
            List<Map<String, Object>> seen = b.list();
            check("conflict: copies of a sidecar never make a second row",
                    seen.size() == 1 && id.equals(seen.get(0).get("objectId")), String.valueOf(ids(seen)));
            ageObject(s, base + ".fdvc", 48 * HOUR);
            b.list();
            check("conflict: the object is not swept", s.s3.objects.containsKey(base + ".fdvc"));
            s.s3.objects.put(base + ".entry (1)", new FakeS3.Obj(data(92, 900), sidecar.modified));
            seen = b.list();
            check("conflict: a corrupt conflict copy beside a valid sidecar does not hide the backup",
                    ids(seen).contains(id), "listed " + ids(seen) + "; BackupLayout.Listing keeps one sidecar per id"
                            + " and the copy sorts after the original");
            // A VALID but stale copy, listed after the real sidecar: A's
            // backfill rewrites the real one, the copy keeps the old fields.
            s.s3.objects.put(base + ".entry (1)", new FakeS3.Obj(sidecar.data.clone(), sidecar.modified));
            String same = a.backup("conflict.txt", f.length, source(f), "cHJldmlldw", "https://example.com/c");
            Map<String, Object> e = entry(b.list(), id);
            check("conflict: a stale conflict copy listed after the sidecar does not hide its newer fields",
                    same.equals(id) && e != null && "cHJldmlldw".equals(e.get("thumb")), String.valueOf(e));
        }
    }

    private static void s3StaleUploads() throws Exception {
        try (SimServers.S3 s = s3Server(a, b)) {
            byte[] big = data(101, CHUNK + 1_234);
            SimServers.Paused src = new SimServers.Paused(big, CHUNK);
            Future<String> job = async(() -> a.backup("slow.bin", big.length, () -> src, null, null));
            boolean paused = src.awaitReached();
            String uploadId = s.s3.uploads.keySet().iterator().next();
            FakeS3.Mpu old = s.s3.uploads.get(uploadId);
            FakeS3.Mpu aged = new FakeS3.Mpu(old.key, System.currentTimeMillis() - 25 * HOUR);
            aged.parts.putAll(old.parts);
            s.s3.uploads.put(uploadId, aged);
            check("stale uploads: A's own sweep never aborts the upload A has open",
                    paused && a.sweepUploadsNow() == 0 && s.s3.uploads.containsKey(uploadId));
            check("stale uploads: residual, B's sweep aborts A's upload once it is a day old",
                    b.sweepUploadsNow() == 1 && !s.s3.uploads.containsKey(uploadId));
            src.release();
            String why = failureOf(a, job);
            check("stale uploads: A's backup then fails as a retryable failure, not a lost file",
                    why.startsWith("server:404"), why);
            check("stale uploads: nothing left of it",
                    keysUnder(s, objectsPrefix(a)).isEmpty() && s.s3.uploads.isEmpty());
        }
        try (SimServers.S3 s = s3Server(b, c)) {
            long now = System.currentTimeMillis();
            s.s3.uploads.put("stale-b", new FakeS3.Mpu(objectsPrefix(b) + randomId(102) + ".fdvc", now - 26 * HOUR));
            s.s3.uploads.put("fresh-b", new FakeS3.Mpu(objectsPrefix(b) + randomId(103) + ".fdvc", now - HOUR));
            s.s3.uploads.put("stale-c", new FakeS3.Mpu(objectsPrefix(c) + randomId(104) + ".fdvc", now - 26 * HOUR));
            int before = s.gate.countQuery("sim-B", "GET", "uploads");
            b.list();
            boolean swept = awaitSweep(s.gate, "sim-B", before);
            check("stale uploads: a fresh store's first listing sweeps its account in the background",
                    swept && !s.s3.uploads.containsKey("stale-b"), String.valueOf(s.s3.uploads.keySet()));
            check("stale uploads: sparing a younger upload and another account's",
                    s.s3.uploads.containsKey("fresh-b") && s.s3.uploads.containsKey("stale-c"));
            int again = s.gate.countQuery("sim-B", "GET", "uploads");
            b.list();
            Thread.sleep(300);
            check("stale uploads: at most once a day per store",
                    s.gate.countQuery("sim-B", "GET", "uploads") == again);
        }
    }

    private static void s3Skew() throws Exception {
        try (SimServers.S3 s = s3Server(a, d)) {
            byte[] f = data(111, CHUNK + 4096);
            String id = d.backup("skew.bin", f.length, source(f), null, null);
            check("skew: D, 3 h behind, backs up (the server's clock corrects it)",
                    ids(a.list()).contains(id) && Arrays.equals(a.restore(id), f));
            check("skew: and D reads it back", Arrays.equals(d.restore(id), f) && streamMatches(d, id, f));
            String old = objectsPrefix(a) + randomId(112) + ".fdvc";
            s.s3.objects.put(old, new FakeS3.Obj(data(113, 100), System.currentTimeMillis() - 25 * HOUR));
            d.list();
            check("skew: D, 3 h behind, sweeps a 25 h old orphan (ages by the server's clock)",
                    !s.s3.objects.containsKey(old));
            d.setClockOffset(3 * HOUR);
            String young = objectsPrefix(a) + randomId(114) + ".fdvc";
            s.s3.objects.put(young, new FakeS3.Obj(data(115, 100), System.currentTimeMillis() - 22 * HOUR));
            String listed = failureOf(d, d::list);
            check("skew: D's clock jumps to 3 h ahead, and D still lists", "ok".equals(listed), listed);
            check("skew: and keeps a 22 h old orphan (its own clock would say 25 h)",
                    s.s3.objects.containsKey(young));
            d.setClockOffset(-3 * HOUR);
        }
        try (SimServers.S3 s = s3Server(a, d)) {
            byte[] f = data(116, 100_000);
            String id = a.backup("head.bin", f.length, source(f), null, null);
            Map<String, Object> e = entry(a.list(), id);
            String first = failureOf(d, () -> d.open(e).close());
            check("skew: a phone whose first request is the stream player's HEAD still opens the backup",
                    "ok".equals(first), first + "; a HEAD answer has no body, so RequestTimeTooSkewed reads as"
                            + " a bare 403 = access denied (S3Client.Call.execute)");
            d.list();
            String after = failureOf(d, () -> d.open(e).close());
            check("skew: once a listing taught D the offset, the stream opens", "ok".equals(after), after);
        }
    }

    private static void s3Pagination() throws Exception {
        try (SimServers.S3 s = s3Server(a, b)) {
            s.s3.pageCap = 4;
            List<Future<List<String>>> jobs = new ArrayList<>();
            Map<String, byte[]> files = new java.util.concurrent.ConcurrentHashMap<>();
            for (int t = 0; t < 4; t++) {
                Phone p = t % 2 == 0 ? a : b;
                int tt = t;
                jobs.add(async(() -> {
                    List<String> out = new ArrayList<>();
                    for (int i = 0; i < 4; i++) {
                        byte[] f = data(1000 + tt * 10 + i, 3000 + tt * 100 + i);
                        String name = "p" + tt + "-" + i + ".txt";
                        files.put(name, f);
                        out.add(p.backup(name, f.length, source(f), null, null));
                    }
                    return out;
                }));
            }
            Set<String> made = new HashSet<>();
            for (Future<List<String>> j : jobs) {
                made.addAll(j.get(120, TimeUnit.SECONDS));
            }
            List<Map<String, Object>> la = a.list();
            List<Map<String, Object>> lb = b.list();
            check("pagination: 16 backups from two phones, listed whole by both through 4-key pages",
                    made.size() == 16 && ids(la).equals(made) && ids(lb).equals(made),
                    made.size() + " made, " + la.size() + " / " + lb.size() + " listed, "
                            + s.gate.countQuery("sim-A", "GET", "list-type") + " list requests by A");
            boolean all = true;
            for (Map<String, Object> e : lb) {
                byte[] f = files.get((String) e.get("name"));
                all &= f != null && Long.valueOf(f.length).equals(e.get("size"));
            }
            Map<String, Object> pick = lb.get(lb.size() / 2);
            check("pagination: every entry has its own name and size; one restores on the other phone",
                    all && Arrays.equals(a.restore((String) pick.get("objectId")), files.get((String) pick.get("name"))));
            b.deleteAll();
            check("pagination: delete-all clears 32 keys across pages",
                    keysUnder(s, PREFIX + BackupLayout.ROOT_DIR + "/" + a.account()).isEmpty() && a.list().isEmpty());
        }
    }

    private static void s3FourWorkers() throws Exception {
        try (SimServers.S3 s = s3Server(a, b)) {
            List<Future<String>> jobs = new ArrayList<>();
            List<byte[]> files = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                byte[] f = data(121 + i, CHUNK + 100_000 * (i + 1));
                files.add(f);
                int ii = i;
                jobs.add(async(() -> a.backup("w" + ii + ".bin", f.length, source(f), null, null)));
            }
            List<String> idsMade = new ArrayList<>();
            for (Future<String> j : jobs) {
                idsMade.add(j.get(120, TimeUnit.SECONDS));
            }
            boolean ok = ids(b.list()).equals(new TreeSet<>(idsMade));
            for (int i = 0; i < 4; i++) {
                ok &= Arrays.equals(b.restore(idsMade.get(i)), files.get(i));
            }
            check("workers: four parallel multipart backups through one store all land and restore", ok);
            check("workers: no unfinished upload left", s.s3.uploads.isEmpty());
        }
    }

    private static void s3Corrupt() throws Exception {
        try (SimServers.S3 s = s3Server(a, b)) {
            byte[] big = data(131, 2 * CHUNK + 999);
            String id = a.backup("corrupt.bin", big.length, source(big), null, null);
            String key = objectsPrefix(a) + id + ".fdvc";
            byte[] stored = s.s3.objects.get(key).data.clone();
            byte[] flipped = stored.clone();
            flipped[CHUNK + OVERHEAD + 100] ^= 1;
            s.s3.objects.put(key, new FakeS3.Obj(flipped, System.currentTimeMillis()));
            Phone.Reader r = b.open(id);
            byte[] buf = new byte[64];
            int n = r.readAt(10, buf, 0, buf.length);
            String bad = failureOf(b, () -> r.readAt(CHUNK + 10, new byte[64], 0, 64));
            r.close();
            check("corrupt: chunk 0 still streams, the flipped chunk fails to decrypt",
                    n == 64 && Arrays.equals(buf, Arrays.copyOfRange(big, 10, 74)) && "crypto".equals(bad), bad);
            String restore = failureOf(b, () -> b.restore(id));
            check("corrupt: a restore refuses it", "crypto".equals(restore), restore);
            byte[] spliced = stored.clone();
            System.arraycopy(stored, 0, spliced, CHUNK + OVERHEAD, CHUNK + OVERHEAD);
            System.arraycopy(stored, CHUNK + OVERHEAD, spliced, 0, CHUNK + OVERHEAD);
            s.s3.objects.put(key, new FakeS3.Obj(spliced, System.currentTimeMillis()));
            Phone.Reader r2 = b.open(id);
            String swapped = failureOf(b, () -> r2.readAt(0, new byte[64], 0, 64));
            r2.close();
            check("corrupt: two whole chunks swapped are refused (position-bound)", "crypto".equals(swapped), swapped);
        }
    }

    // ---- WebDAV helpers ----

    static SimServers.Dav davServer(Phone... phones) throws Exception {
        SimServers.Dav s = new SimServers.Dav();
        for (Phone p : phones) {
            p.useDav(s.url(), s.dav.user, s.dav.password);
        }
        return s;
    }

    static String davObjects(Phone p) {
        return "/dav/" + BackupLayout.ROOT_DIR + "/" + p.account() + "/" + BackupLayout.OBJECTS_DIR;
    }

    static List<String> davFiles(SimServers.Dav s, Phone p) {
        List<String> out = new ArrayList<>();
        for (String child : s.dav.childrenOf(davObjects(p))) {
            out.add(child.substring(child.lastIndexOf('/') + 1));
        }
        return out;
    }

    static int davEnding(SimServers.Dav s, Phone p, String suffix) {
        int n = 0;
        for (String f : davFiles(s, p)) {
            if (f.endsWith(suffix)) {
                n++;
            }
        }
        return n;
    }

    // ---- WebDAV scenarios ----

    private static void davEndToEnd() throws Exception {
        try (SimServers.Dav s = davServer(a, b)) {
            byte[] big = data(211, 2 * CHUNK + 2 * 1024 * 1024 + 3);
            String id = a.backup("film.mkv", big.length, source(big), "dGh1bWI", "https://example.com/v");
            Map<String, Object> e = entry(b.list(), id);
            check("e2e: B lists A's backup with its metadata", e != null && "dGh1bWI".equals(e.get("thumb"))
                    && Integer.valueOf(3).equals(e.get("chunkCount")));
            check("e2e: the object is one PUT of three FDVC chunks",
                    s.dav.get(davObjects(a) + "/" + id + ".fdvc").data.length == big.length + 3 * OVERHEAD
                            && s.gate.count("sim-A", "PUT", ".fdvc") == 1);
            check("e2e: B streams it with seeks across chunk boundaries", streamMatches(b, id, big));
            check("e2e: B restores it byte for byte", Arrays.equals(b.restore(id), big));
            check("e2e: A restores it byte for byte", Arrays.equals(a.restore(id), big));
        }
    }

    private static void davConcurrent() throws Exception {
        try (SimServers.Dav s = davServer(a, b)) {
            byte[] fa = data(221, CHUNK + 1024);
            byte[] fb = data(222, 3 * 1024 * 1024);
            Future<String> ja = async(() -> a.backup("a.bin", fa.length, source(fa), null, null));
            Future<String> jb = async(() -> b.backup("b.bin", fb.length, source(fb), null, null));
            String ida = ja.get(60, TimeUnit.SECONDS);
            String idb = jb.get(60, TimeUnit.SECONDS);
            check("concurrent: different files from two phones both land (one made the directories, "
                            + "the other met them)",
                    ids(a.list()).equals(new TreeSet<>(Arrays.asList(ida, idb)))
                            && ids(b.list()).equals(ids(a.list())));
            check("concurrent: each restores byte for byte on the OTHER phone",
                    Arrays.equals(b.restore(ida), fa) && Arrays.equals(a.restore(idb), fb));
            byte[] same = data(223, 300_000);
            SimServers.Paused pa = new SimServers.Paused(same, 0);
            SimServers.Paused pb = new SimServers.Paused(same, 0);
            Future<String> sa = async(() -> a.backup("same.jpg", same.length, () -> pa, null, null));
            Future<String> sb = async(() -> b.backup("same.jpg", same.length, () -> pb, null, null));
            boolean both = pa.awaitReached() && pb.awaitReached();
            pa.release();
            pb.release();
            String s1 = sa.get(60, TimeUnit.SECONDS);
            String s2 = sb.get(60, TimeUnit.SECONDS);
            int rows = 0;
            for (Map<String, Object> e : a.list()) {
                if ("same.jpg".equals(e.get("name"))) {
                    rows++;
                }
            }
            check("concurrent same file: residual, both pass dedup and the list holds TWO rows",
                    both && !s1.equals(s2) && rows == 2, rows + " rows");
        }
    }

    private static void davMidUpload() throws Exception {
        try (SimServers.Dav s = davServer(a, b)) {
            byte[] big = data(231, CHUNK + 5000);
            SimServers.Paused src = new SimServers.Paused(big, CHUNK);
            Future<String> job = async(() -> a.backup("half.bin", big.length, () -> src, null, null));
            boolean paused = src.awaitReached();
            Thread.sleep(200);
            List<Map<String, Object>> seen = b.list();
            check("mid-upload: B lists nothing while A's PUT is half sent", paused && seen.isEmpty());
            check("mid-upload: and no half file on the server", davFiles(s, a).isEmpty(),
                    String.valueOf(davFiles(s, a)));
            src.release();
            String id = job.get(60, TimeUnit.SECONDS);
            check("mid-upload: A's backup completes and B restores it",
                    ids(b.list()).contains(id) && Arrays.equals(b.restore(id), big));
        }
    }

    private static void davSidecarPending() throws Exception {
        try (SimServers.Dav s = davServer(a, b)) {
            byte[] f = data(241, 200_000);
            SimServers.Hold hold = s.gate.hold("PUT", ".entry", "sim-A");
            Future<String> job = async(() -> a.backup("pending.bin", f.length, source(f), null, null));
            check("sidecar pending: A's object is up, its sidecar held",
                    hold.awaitArrived() && davEnding(s, a, ".fdvc") == 1);
            check("sidecar pending: B lists nothing", b.list().isEmpty());
            String path = davObjects(a) + "/" + davFiles(s, a).get(0);
            s.dav.get(path).modified = System.currentTimeMillis() - 23 * HOUR;
            b.list();
            check("sidecar pending: B keeps A's object, fresh or 23 h old", s.dav.get(path) != null);
            hold.release();
            String id = job.get(60, TimeUnit.SECONDS);
            check("sidecar pending: the commit lands and B lists it",
                    ids(b.list()).contains(id) && Arrays.equals(b.restore(id), f));
            String orphan = davObjects(a) + "/" + randomId(242) + ".fdvc";
            String lone = davObjects(a) + "/" + randomId(243) + ".entry";
            s.dav.putFile(orphan, data(244, 500), System.currentTimeMillis() - 25 * HOUR);
            s.dav.putFile(lone, data(245, 500), System.currentTimeMillis() - 30 * 24 * HOUR);
            List<Map<String, Object>> seen = b.list();
            check("orphans: a sidecar-less object over 24 h old is swept by the other phone",
                    s.dav.get(orphan) == null);
            check("orphans: a sidecar with no object is never deleted, never listed",
                    s.dav.get(lone) != null && seen.size() == 1);
        }
    }

    private static void davDeleteWhileStreaming() throws Exception {
        try (SimServers.Dav s = davServer(a, b)) {
            byte[] big = data(251, CHUNK + 4096);
            String id = a.backup("watch.mp4", big.length, source(big), null, null);
            Phone.Reader r = b.open(id);
            byte[] buf = new byte[1000];
            int n = r.readAt(100, buf, 0, buf.length);
            a.delete(id);
            String later = failureOf(b, () -> r.readAt(CHUNK + 5, new byte[100], 0, 100));
            check("stream: after A's delete, B's next chunk fails as not found",
                    n == 1000 && davFiles(s, a).isEmpty() && "notfound".equals(later), later);
            n = r.readAt(100, buf, 0, buf.length);
            check("stream: what B already read is still the true plaintext",
                    n == 1000 && Arrays.equals(buf, Arrays.copyOfRange(big, 100, 1100)));
            r.close();
        }
    }

    private static void davCache() throws Exception {
        try (SimServers.Dav s = davServer(a, b)) {
            byte[] f = data(261, 90_000);
            String id = a.backup("song.mp3", f.length, source(f), null, null);
            int g0 = s.gate.count("sim-B", "GET", ".entry");
            b.list();
            int g1 = s.gate.count("sim-B", "GET", ".entry");
            b.list();
            int g2 = s.gate.count("sim-B", "GET", ".entry");
            check("cache: B reads the sidecar once, then from its cache", g1 - g0 == 1 && g2 == g1,
                    (g1 - g0) + ", " + (g2 - g1));
            a.backup("song.mp3", f.length, source(f), "cHJldmlldw", null);
            Map<String, Object> e = entry(b.list(), id);
            check("cache: A's backfill reaches B's list", e != null && "cHJldmlldw".equals(e.get("thumb")),
                    String.valueOf(e));
            a.delete(id);
            check("cache: A's delete leaves B's list", b.list().isEmpty());
        }
    }

    private static void davDeleteAllWhileUploading() throws Exception {
        try (SimServers.Dav s = davServer(a, b)) {
            byte[] big = data(271, CHUNK + 7_000);
            byte[] small = data(272, 4000);
            String old = a.backup("old.txt", small.length, source(small), null, null);
            SimServers.Paused src = new SimServers.Paused(big, CHUNK);
            Future<String> job = async(() -> b.backup("big.bin", big.length, () -> src, null, null));
            boolean paused = src.awaitReached();
            a.deleteAll();
            check("delete-all: A's delete-all removes the account while B's PUT is in flight",
                    paused && s.dav.get(davObjects(a)) == null);
            check("delete-all: B lists an empty place, no error", failureOf(b, b::list).equals("ok")
                    && b.list().isEmpty());
            src.release();
            String why = failureOf(b, job);
            List<Map<String, Object>> after = a.list();
            boolean clean = "ok".equals(why) ? after.size() == 1
                    : !"unreachable".equals(why) && !"notfound".equals(why) && after.isEmpty();
            check("delete-all: B's backup either lands whole or fails retryably, never half",
                    clean && !ids(after).contains(old), why + ", " + after.size() + " listed");
            if (!"ok".equals(why)) {
                String retried = b.backup("big.bin", big.length, source(big), null, null);
                check("delete-all: B's retry recreates the directories and lands",
                        ids(a.list()).equals(Set.of(retried)));
            } else {
                check("delete-all: what landed restores byte for byte",
                        Arrays.equals(a.restore((String) after.get(0).get("objectId")), big));
            }
        }
    }

    private static void davIsolation() throws Exception {
        try (SimServers.Dav s = davServer(a, c)) {
            byte[] fa = data(281, 40_000);
            byte[] fc = data(282, 50_000);
            String ida = a.backup("mine.txt", fa.length, source(fa), null, null);
            String idc = c.backup("theirs.txt", fc.length, source(fc), null, null);
            check("isolation: each code lists only its own", ids(a.list()).equals(Set.of(ida))
                    && ids(c.list()).equals(Set.of(idc)));
            for (String name : davFiles(s, a)) {
                FakeDav.Node n = s.dav.get(davObjects(a) + "/" + name);
                s.dav.putFile(davObjects(c) + "/" + name, n.data, System.currentTimeMillis() - 30 * HOUR);
            }
            check("isolation: A's files copied into C's directory are not C's entries, and stay",
                    ids(c.list()).equals(Set.of(idc)) && s.dav.get(davObjects(c) + "/" + ida + ".fdvc") != null);
            Map<String, Object> aEntry = entry(a.list(), ida);
            String steal = failureOf(c, () -> c.restore(aEntry));
            check("isolation: C cannot decrypt A's backup even with its metadata", "crypto".equals(steal), steal);
            c.deleteAll();
            check("isolation: C's delete-all leaves A's backups alone",
                    ids(a.list()).equals(Set.of(ida)) && Arrays.equals(a.restore(ida), fa));
        }
    }

    private static void davConflicts() throws Exception {
        try (SimServers.Dav s = davServer(a, b)) {
            byte[] f = data(291, 30_000);
            String id = a.backup("conflict.txt", f.length, source(f), null, null);
            String base = davObjects(a) + "/" + id;
            FakeDav.Node sidecar = s.dav.get(base + ".entry");
            s.dav.putFile(base + ".entry (1)", sidecar.data.clone(), sidecar.modified);
            s.dav.putFile(base + " (1).entry", sidecar.data.clone(), sidecar.modified);
            List<Map<String, Object>> seen = b.list();
            check("conflict: copies of a sidecar never make a second row",
                    seen.size() == 1 && id.equals(seen.get(0).get("objectId")), String.valueOf(ids(seen)));
            s.dav.get(base + ".fdvc").modified = System.currentTimeMillis() - 48 * HOUR;
            b.list();
            check("conflict: the object is not swept", s.dav.get(base + ".fdvc") != null);
            s.dav.putFile(base + ".entry (1)", data(292, 900), sidecar.modified);
            seen = b.list();
            check("conflict: a corrupt conflict copy beside a valid sidecar does not hide the backup",
                    ids(seen).contains(id), "listed " + ids(seen));
        }
    }

    // ---- shared scenarios ----

    /** What the driver can see of a place: every file under one account's
     *  objects directory, and the unfinished uploads (S3 only). */
    interface View {
        List<String> files();

        int uploads();
    }

    static View s3View(SimServers.S3 s, Phone p) {
        return new View() {
            @Override
            public List<String> files() {
                return keysUnder(s, objectsPrefix(p));
            }

            @Override
            public int uploads() {
                return s.s3.uploads.size();
            }
        };
    }

    static View davView(SimServers.Dav s, Phone p) {
        return new View() {
            @Override
            public List<String> files() {
                return davFiles(s, p);
            }

            @Override
            public int uploads() {
                return 0;
            }
        };
    }

    /** A cancel during a backup: nothing stored, nothing left, nothing listed
     *  by the other phone. */
    private static void cancel(View view) throws Exception {
        byte[] big = data(141, 2 * CHUNK + 5000);
        SimServers.Paused src = new SimServers.Paused(big, CHUNK);
        Future<String> job = async(() -> a.backup("cancel.bin", big.length, () -> src, null, null));
        boolean paused = src.awaitReached();
        a.cancelBackups();
        src.release();
        String why = failureOf(a, job);
        check("cancel: the backup stops with a plain failure", paused && "io".equals(why), why);
        check("cancel: no object, no sidecar, no unfinished upload",
                view.files().isEmpty() && view.uploads() == 0, view.files() + ", " + view.uploads());
        check("cancel: the other phone lists nothing", b.list().isEmpty());
    }

    private static void s3Cancel() throws Exception {
        try (SimServers.S3 s = s3Server(a, b)) {
            cancel(s3View(s, a));
        }
    }

    private static void davCancel() throws Exception {
        try (SimServers.Dav s = davServer(a, b)) {
            cancel(davView(s, a));
        }
    }

    /**
     * Three phones (A and B on one clock, D three hours behind), two threads
     * each, doing random backups, listings, restores and deletes against one
     * place at once. Every listed entry must be one somebody backed up, with
     * its size; every restore must give back exactly those bytes or report
     * the entry gone; nothing else may fail. At rest, both phones list the
     * same entries, each restores, and the place holds exactly an object and
     * a sidecar per entry (no orphan, no lone sidecar, no unfinished upload).
     */
    private static void stress(View view) throws Exception {
        Phone[] phones = {a, b, d};
        Map<String, byte[]> originals = new java.util.concurrent.ConcurrentHashMap<>();
        List<String> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger ops = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger gone = new java.util.concurrent.atomic.AtomicInteger();
        long until = System.currentTimeMillis() + (quick ? 1000 : 4000);
        List<Future<?>> jobs = new ArrayList<>();
        for (int t = 0; t < phones.length * 2; t++) {
            Phone p = phones[t / 2];
            int tt = t;
            jobs.add(async(() -> {
                Random r = new Random(7000 + tt);
                int n = 0;
                while (System.currentTimeMillis() < until) {
                    int op = r.nextInt(10);
                    ops.incrementAndGet();
                    try {
                        if (op < 4) {
                            byte[] f = data(9000L * tt + n, 1 + r.nextInt(60_000));
                            String name = "s-" + p.name() + tt + "-" + (n++) + ".bin";
                            originals.put(name, f);
                            p.backup(name, f.length, source(f), null, null);
                            continue;
                        }
                        List<Map<String, Object>> seen = p.list();
                        for (Map<String, Object> e : seen) {
                            byte[] f = originals.get((String) e.get("name"));
                            if (f == null || !Long.valueOf(f.length).equals(e.get("size"))) {
                                unexpected.add(p.name() + " listed an entry nobody made: " + e.get("name"));
                            }
                        }
                        if (seen.isEmpty() || op < 7) {
                            continue;
                        }
                        Map<String, Object> pick = seen.get(r.nextInt(seen.size()));
                        if (op < 9) {
                            try {
                                byte[] back = p.restore(pick);
                                if (!Arrays.equals(back, originals.get((String) pick.get("name")))) {
                                    unexpected.add(p.name() + " restored the wrong bytes for " + pick.get("name"));
                                }
                            } catch (Exception e) {
                                if ("notfound".equals(p.describe(e))) {
                                    gone.incrementAndGet();
                                } else {
                                    unexpected.add(p.name() + " restore: " + p.describe(e) + " " + e);
                                }
                            }
                        } else {
                            p.delete(pick);
                        }
                    } catch (Exception e) {
                        unexpected.add(p.name() + " op " + op + ": " + p.describe(e) + " " + e);
                    }
                }
                return null;
            }));
        }
        for (Future<?> j : jobs) {
            j.get(120, TimeUnit.SECONDS);
        }
        check("stress: " + ops.get() + " random operations from three phones, nothing unexpected ("
                        + gone.get() + " restores met an entry deleted under them)",
                unexpected.isEmpty(), unexpected.isEmpty() ? "" : unexpected.size() + ": " + unexpected.get(0));
        List<Map<String, Object>> fromA = a.list();
        List<Map<String, Object>> fromB = b.list();
        boolean restores = true;
        for (Map<String, Object> e : fromB) {
            restores &= Arrays.equals(a.restore(e), originals.get((String) e.get("name")));
        }
        check("stress: at rest both phones list the same entries, and every one restores",
                ids(fromA).equals(ids(fromB)) && restores, fromA.size() + " / " + fromB.size());
        List<String> files = view.files();
        int objects = 0;
        int sidecars = 0;
        for (String f : files) {
            objects += f.endsWith(".fdvc") ? 1 : 0;
            sidecars += f.endsWith(".entry") ? 1 : 0;
        }
        check("stress: exactly one object and one sidecar per entry, no unfinished upload",
                objects == fromA.size() && sidecars == fromA.size() && files.size() == 2 * fromA.size()
                        && view.uploads() == 0,
                files.size() + " files for " + fromA.size() + " entries, " + view.uploads() + " uploads");
    }

    private static void s3Stress() throws Exception {
        try (SimServers.S3 s = s3Server(a, b, d)) {
            stress(s3View(s, a));
        }
    }

    private static void davStress() throws Exception {
        try (SimServers.Dav s = davServer(a, b, d)) {
            stress(davView(s, a));
        }
    }

    /**
     * A backs up a file with no preview; then A (adding a preview) and B
     * (adding an origin) backfill the same entry at once, each from what it
     * listed. Each rewrites the whole sidecar, so the later write wins: a
     * residual (a lost backfill comes back the next time the file is backed
     * up), asserted as it is.
     */
    private static void backfillRace(SimServers.Gate gate, String sidecarSuffix) throws Exception {
        byte[] f = data(151, 20_000);
        String id = a.backup("race.txt", f.length, source(f), null, null);
        b.list();
        SimServers.Hold hold = gate.hold("PUT", sidecarSuffix, "sim-A");
        Future<String> withThumb = async(() -> a.backup("race.txt", f.length, source(f), "dGh1bWI", null));
        boolean held = hold.awaitArrived();
        String other = b.backup("race.txt", f.length, source(f), null, "https://example.com/r");
        hold.release();
        String first = withThumb.get(60, TimeUnit.SECONDS);
        Map<String, Object> e = entry(b.list(), id);
        check("backfill race: both backfilled the one entry, no new object",
                held && id.equals(other) && id.equals(first) && e != null);
        check("backfill race: residual, the later sidecar write wins (the preview stays, the origin is lost)",
                e != null && "dGh1bWI".equals(e.get("thumb")) && e.get("origin") == null, String.valueOf(e));
        String again = b.backup("race.txt", f.length, source(f), null, "https://example.com/r");
        e = entry(a.list(), id);
        check("backfill race: the next backup of the file brings the lost field back",
                id.equals(again) && e != null && "dGh1bWI".equals(e.get("thumb"))
                        && "https://example.com/r".equals(e.get("origin")), String.valueOf(e));
    }

    private static void s3BackfillRace() throws Exception {
        try (SimServers.S3 s = s3Server(a, b)) {
            backfillRace(s.gate, ".entry");
        }
    }

    private static void davBackfillRace() throws Exception {
        try (SimServers.Dav s = davServer(a, b)) {
            backfillRace(s.gate, ".entry");
        }
    }

    /**
     * A types the prefix as "./fam", B as "fam". The check accepts both, and
     * OkHttp resolves the dot segment out of every object URL, so A's files
     * land under fam/ (B sees them) while A's LISTINGS ask for the literal
     * "./fam/..." prefix (a query parameter, never resolved): A never sees
     * its own backups.
     */
    private static void s3DotPrefix() throws Exception {
        try (SimServers.S3 s = new SimServers.S3()) {
            S3_SERVERS.add(s);
            String why = failureOf(a, () -> a.useS3(s.endpoint(), s.s3.bucket, "./" + PREFIX,
                    s.s3.accessKey, s.s3.secret));
            b.useS3(s.endpoint(), s.s3.bucket, PREFIX, s.s3.accessKey, s.s3.secret);
            if (!"ok".equals(why)) {
                check("dot prefix: the check refuses a prefix with a dot segment", true);
                return;
            }
            byte[] f = data(161, 10_000);
            String id = a.backup("dot.txt", f.length, source(f), null, null);
            boolean bSees = ids(b.list()).contains(id);
            List<Map<String, Object>> own = a.list();
            check("dot prefix: a phone lists what it just backed up (or the check refuses the prefix)",
                    ids(own).contains(id), "A lists " + own.size() + ", B (prefix fam/) sees it: " + bSees
                            + "; " + keysUnder(s, PREFIX).size() + " keys under " + PREFIX
                            + "; S3Check.normalizePrefix keeps '.'/'..' segments, OkHttp drops them from"
                            + " the object path but not from the list prefix");
            a.useS3(s.endpoint(), s.s3.bucket, PREFIX, s.s3.accessKey, s.s3.secret);
        }
    }
}

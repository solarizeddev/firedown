package com.solarized.firedown.sync;

import android.util.Log;

import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.model.VaultEntry;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import okhttp3.Credentials;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/**
 * Fault injection at the REAL backup code: hostile and broken servers in
 * front of the S3 and WebDAV stores, misbehaving client conditions, and a
 * sweep for secrets over everything the run printed, logged or threw. See
 * run.sh for the list. Written against the code before the 2026-10 review
 * fixes, where 14 of its checks failed (an overflowing Retry-After escaping
 * as a RuntimeException, an endless ListMultipartUploads, a 200 <Error> to
 * DeleteObjects, a cross-host WebDAV redirect saved as the server, user info
 * kept in a typed address, and a dripping server holding a call forever);
 * they pass on the fixed code.
 */
public final class FaultHarness {

    private static int passed;
    private static final List<String> failed = new ArrayList<>();
    private static final List<String> failedNames = new ArrayList<>();
    private static File work;
    private static boolean quick;
    /** Everything written to System.out during the run, for the sweep. */
    private static final StringBuilder captured = new StringBuilder();
    /** Exception chains and toStrings the checks met, for the sweep. */
    private static final List<String> met = new CopyOnWriteArrayList<>();

    static final String S3_SECRET = "SHHs3SecretZx9/plus+slash";
    static final String S3_KEY_ID = "AKIDFAULTHARNESS";
    static final String DAV_PASSWORD = "SHHdavPasswordQp4";
    static final String DAV_USER = "alex";
    static final String DAV = "/remote.php/dav/files/alex";
    static final int CHUNK = VaultEngine.CHUNK_SIZE;

    static final Dns FAKE_DNS = hostname -> {
        String h = hostname.toLowerCase(Locale.ROOT);
        if (h.equals("s3.fake") || h.endsWith(".s3.fake") || h.equals("nas.fake")
                || h.equals("evil.fake")) {
            return Collections.singletonList(InetAddress.getLoopbackAddress());
        }
        return Dns.SYSTEM.lookup(hostname);
    };
    static final OkHttpClient BASE = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY).dns(FAKE_DNS).build();

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--xml-bomb")) {
            xmlBombChild();
            return;
        }
        work = new File(args[0]);
        String keystoreA = args[1];
        String keystoreB = args[2];
        quick = "1".equals(System.getenv("FAULT_HARNESS_QUICK"));
        tee();

        run("s3: broken wire", FaultHarness::s3Wire);
        run("s3: hostile answers", FaultHarness::s3Hostile);
        run("s3: redirects, skew, region", FaultHarness::s3Checks);
        run("webdav: broken wire", FaultHarness::webdavWire);
        run("webdav: redirects", FaultHarness::webdavRedirects);
        run("tls: pinned certificate changes mid-session", () -> pinChanges(keystoreA, keystoreB));
        run("cancel: every chunk boundary", FaultHarness::cancels);
        run("url: userinfo in a typed address", FaultHarness::userinfo);
        if (!quick) {
            run("drip: one byte at a time", FaultHarness::drips);
            run("memory: answers within the size cap, phone-sized heap", FaultHarness::memory);
        } else {
            System.out.println("-- drip / memory: skipped (FAULT_HARNESS_QUICK)");
        }
        run("sweep: no secret anywhere", FaultHarness::sweep);

        System.out.println();
        System.out.println(passed + " passed, " + failed.size() + " failed");
        for (String f : failed) {
            System.out.println("  FAIL " + f);
        }
        // The failing checks' NAMES (no detail), one per line, for run.sh's
        // mutant gate: a mutant is caught only by a failure the unmutated run
        // does not have.
        String fails = System.getenv("FAULT_HARNESS_FAILS");
        if (fails != null && !fails.isEmpty()) {
            Files.write(Paths.get(fails), failedNames, StandardCharsets.UTF_8);
        }
        System.exit(failed.isEmpty() ? 0 : 1);
    }

    // ---- bookkeeping ----

    interface Case {
        void run() throws Exception;
    }

    /** A case that throws is a failed case, never an aborted suite.
     *  FAULT_HARNESS_ONLY=<substring> runs the matching sections alone. */
    private static void run(String name, Case c) {
        String only = System.getenv("FAULT_HARNESS_ONLY");
        if (only != null && !only.isEmpty() && !name.contains(only)) {
            return;
        }
        System.out.println("-- " + name);
        try {
            c.run();
        } catch (Throwable t) {
            failed.add(name + ": threw " + t);
            failedNames.add(name + ": threw");
            remember(t);
            t.printStackTrace(System.out);
        }
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
            failedNames.add(name);
            System.out.println("  FAIL " + name + (detail.isEmpty() ? "" : " (" + detail + ")"));
        }
    }

    interface IoCall {
        void call() throws Exception;
    }

    /** A throwable that is NOT an IOException escaped the call: a
     *  RuntimeException out of the wire, which no worker retries. */
    static final class Unexpected extends IOException {
        Unexpected(Throwable t) {
            super("unexpected " + t, t);
        }
    }

    /** The IOException a call threw (a non-IO throwable wrapped as
     *  {@link Unexpected}), or null. */
    private static IOException expectIo(IoCall c) {
        try {
            c.call();
            return null;
        } catch (IOException e) {
            remember(e);
            return e;
        } catch (Throwable e) {
            remember(e);
            System.out.println("    (a non-IO throwable escaped the call)");
            e.printStackTrace(System.out);
            return new Unexpected(e);
        }
    }

    private static void remember(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c).append(" | ");
        }
        met.add(sb.toString());
    }

    /** A plain, retryable failure: neither needs-the-user, nor full, nor a
     *  missing source file. */
    private static boolean plain(IOException e) {
        return e != null && !(e instanceof Unexpected) && !(e instanceof BackupStore.UnreachableException)
                && !(e instanceof BackupStore.FullException)
                && !(e instanceof FileNotFoundException);
    }

    private static String kind(IOException e) {
        return e == null ? "no exception" : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static void tee() {
        PrintStream orig = System.out;
        System.setOut(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                orig.write(b);
                synchronized (captured) {
                    captured.append((char) b);
                }
            }

            @Override
            public void flush() {
                orig.flush();
            }
        }, true));
    }

    private static SyncIdentity identity(int seed) {
        byte[] code = new byte[32];
        Arrays.fill(code, (byte) seed);
        return SyncIdentity.fromCode(code);
    }

    private static File randomFile(String name, int size, long seed) throws IOException {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        File f = new File(work, name);
        Files.write(f.toPath(), data);
        return f;
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return hex(md.digest(data));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    private static String objectsPrefix(SyncIdentity id) {
        return BackupLayout.ROOT_DIR + "/" + id.accountBase32() + "/" + BackupLayout.OBJECTS_DIR + "/";
    }

    private static int keysEnding(FakeS3 s3, String suffix) {
        int n = 0;
        for (String k : s3.objects.keySet()) {
            if (k.endsWith(suffix)) {
                n++;
            }
        }
        return n;
    }

    /** Runs {@code c} on a daemon thread; true when it finished within
     *  {@code budgetMs}. */
    private static boolean finishesWithin(long budgetMs, IoCall c) throws InterruptedException {
        AtomicBoolean done = new AtomicBoolean();
        Thread t = new Thread(() -> {
            try {
                c.call();
            } catch (Throwable e) {
                remember(e);
            }
            done.set(true);
        }, "budgeted");
        t.setDaemon(true);
        t.start();
        t.join(budgetMs);
        return done.get();
    }

    // ---- servers ----

    /** The JDK HttpServer hosting one of the harness fakes. */
    static final class Backend implements AutoCloseable {
        final HttpServer http;
        final int port;

        Backend(com.sun.net.httpserver.HttpHandler handler, SSLContext tls, int port) throws IOException {
            InetSocketAddress addr = new InetSocketAddress("127.0.0.1", port);
            if (tls == null) {
                http = HttpServer.create(addr, 0);
            } else {
                HttpsServer s = HttpsServer.create(addr, 0);
                s.setHttpsConfigurator(new HttpsConfigurator(tls));
                http = s;
            }
            http.createContext("/", handler);
            http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "backend");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            this.port = http.getAddress().getPort();
        }

        @Override
        public void close() {
            http.stop(0);
        }
    }

    /** A dumb TCP relay whose target can be switched: the "same address,
     *  another server behind it" of a certificate that changed. */
    static final class TcpRelay implements AutoCloseable {
        final int port;
        volatile int target;
        private final ServerSocket server;
        private final List<Socket> open = new CopyOnWriteArrayList<>();
        private volatile boolean closed;

        TcpRelay(int target) throws IOException {
            this.target = target;
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            port = server.getLocalPort();
            Thread t = new Thread(this::accept, "relay");
            t.setDaemon(true);
            t.start();
        }

        void switchTo(int newTarget) {
            target = newTarget;
            for (Socket s : open) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closing
                }
            }
            open.clear();
        }

        private void accept() {
            while (!closed) {
                try {
                    Socket client = server.accept();
                    Socket upstream = new Socket("127.0.0.1", target);
                    open.add(client);
                    open.add(upstream);
                    pump(client, upstream);
                    pump(upstream, client);
                } catch (IOException e) {
                    if (closed) {
                        return;
                    }
                }
            }
        }

        private static void pump(Socket from, Socket to) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[64 * 1024];
                try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (IOException ignored) {
                    // one side went away
                } finally {
                    try {
                        to.close();
                    } catch (IOException ignored) {
                        // closing
                    }
                }
            }, "relay-pump");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void close() {
            closed = true;
            try {
                server.close();
            } catch (IOException ignored) {
                // closing
            }
            switchTo(target);
        }
    }

    private static SSLContext serverTls(String keystore) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(keystore)) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private static String certSha256(String keystore) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(keystore)) {
            ks.load(in, "changeit".toCharArray());
        }
        return ServerTrust.sha256((X509Certificate) ks.getCertificate("srv"));
    }

    /** FakeS3 behind an interposer, with a store whose retries never sleep. */
    static final class S3Rig implements AutoCloseable {
        final FakeS3 s3 = new FakeS3("s3.fake");
        final Backend backend;
        final Interposer proxy;
        final List<Long> waits = new CopyOnWriteArrayList<>();

        S3Rig() throws IOException {
            s3.secret = S3_SECRET;
            s3.accessKey = S3_KEY_ID;
            backend = new Backend(s3, null, 0);
            proxy = new Interposer("127.0.0.1", backend.port);
        }

        String endpoint() {
            return "http://s3.fake:" + proxy.port;
        }

        S3Config config() {
            return new S3Config(endpoint(), "us-east-1", s3.bucket, "", S3_KEY_ID, S3_SECRET, true,
                    S3Config.Addressing.AUTO, null);
        }

        S3Store store() {
            S3Store store = new S3Store(config(), BASE);
            store.client().sleeper = waits::add;
            return store;
        }

        @Override
        public void close() {
            proxy.close();
            backend.close();
        }
    }

    /** FakeDav behind an interposer. */
    static final class DavRig implements AutoCloseable {
        final FakeDav dav = new FakeDav();
        final Backend backend;
        final Interposer proxy;

        DavRig() throws IOException {
            dav.password = DAV_PASSWORD;
            dav.mkdirs(DAV);
            backend = new Backend(dav, null, 0);
            proxy = new Interposer("127.0.0.1", backend.port);
        }

        String url(String path) {
            return "http://nas.fake:" + proxy.port + path;
        }

        WebDavConfig config() {
            return new WebDavConfig(url(DAV + "/"), DAV_USER, DAV_PASSWORD, null);
        }

        WebDavStore store() {
            return new WebDavStore(config(), BASE);
        }

        @Override
        public void close() {
            proxy.close();
            backend.close();
        }
    }

    // ---- rules ----

    private static boolean isList(Interposer.Req r) {
        return r.method.equals("GET") && r.query().contains("list-type=2");
    }

    private static boolean isListUploads(Interposer.Req r) {
        return r.method.equals("GET") && r.query().startsWith("prefix=") && r.query().contains("uploads=")
                || r.method.equals("GET") && r.query().contains("uploads=") && !r.query().contains("uploadId");
    }

    private static boolean isSidecarGet(Interposer.Req r) {
        return r.method.equals("GET") && r.path().endsWith(BackupLayout.ENTRY_EXT);
    }

    private static boolean isRangeGet(Interposer.Req r) {
        return r.method.equals("GET") && r.path().endsWith(BackupLayout.OBJECT_EXT)
                && r.header("Range") != null;
    }

    private static boolean isObjectGet(Interposer.Req r) {
        return r.method.equals("GET") && r.path().endsWith(BackupLayout.OBJECT_EXT);
    }

    private static boolean isPropfind(Interposer.Req r) {
        return r.method.equals("PROPFIND");
    }

    /** A rule that applies {@code f} to the answers of the requests
     *  {@code when} matches, forwarding everything else untouched. */
    private static Interposer.Rule mutateWhen(java.util.function.Predicate<Interposer.Req> when,
                                              java.util.function.Function<Interposer.Reply, Interposer.Reply> f) {
        return req -> when.test(req) ? Interposer.Action.mutate(f) : Interposer.Action.forward();
    }

    private static Interposer.Rule answerWhen(java.util.function.Predicate<Interposer.Req> when,
                                              java.util.function.Function<Interposer.Req, Interposer.Reply> f) {
        return req -> when.test(req) ? Interposer.Action.answer(f.apply(req)) : Interposer.Action.forward();
    }

    private static final String S3_NS = "http://s3.amazonaws.com/doc/2006-03-01/";

    private static String s3Error(String code, String message) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code + "</Code><Message>"
                + message + "</Message></Error>";
    }

    private static String listingPage(String key, int n, boolean truncated) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult xmlns=\"" + S3_NS + "\">"
                + "<Name>backups</Name><Contents><Key>" + key + n + ".fdvc</Key>"
                + "<LastModified>2026-10-18T10:00:00.000Z</LastModified><ETag>\"e" + n + "\"</ETag>"
                + "<Size>10</Size></Contents><KeyCount>1</KeyCount><IsTruncated>" + truncated
                + "</IsTruncated>" + (truncated ? "<NextContinuationToken>t" + n
                + "</NextContinuationToken>" : "") + "</ListBucketResult>";
    }

    private static String uploadsPage(int n) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListMultipartUploadsResult xmlns=\"" + S3_NS
                + "\"><Bucket>backups</Bucket><IsTruncated>true</IsTruncated><NextKeyMarker>k" + n
                + "</NextKeyMarker><NextUploadIdMarker>u" + n + "</NextUploadIdMarker>"
                + "</ListMultipartUploadsResult>";
    }

    // ---- s3: broken wire ----

    private static void s3Wire() throws Exception {
        try (S3Rig rig = new S3Rig()) {
            S3Store store = rig.store();
            SyncIdentity id = identity(1);
            VaultEngine engine = new VaultEngine(store, id);
            String objs = objectsPrefix(id);
            File big = randomFile("big.bin", 2 * CHUNK + 123_456, 1);
            byte[] original = Files.readAllBytes(big.toPath());
            VaultEntry eBig = engine.backupFile(big, "video/mp4", null, null, "https://x.example/v", 1000);
            VaultEntry eSmall = engine.backupFile(randomFile("small.bin", 50_000, 2), "image/jpeg", "t",
                    null, null, 0);
            check("s3: the rig works through the interposer (multipart + a single PUT, listed)",
                    engine.loadManifest().size() == 2 && rig.s3.count("MPUCREATE") == 1
                            && rig.proxy.requests.get() > 5, rig.s3.log.toString());

            // --- listings: truncated, reset, Content-Length lies ---
            int[] cuts = {0, 10, -1};
            for (int cut : cuts) {
                String label = cut < 0 ? "half" : Integer.toString(cut);
                rig.proxy.rule = mutateWhen(FaultHarness::isList,
                        r -> r.truncate(cut < 0 ? r.body.length / 2 : cut));
                IOException e = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
                check("s3: listing body cut off at byte " + label + " is a plain IOException", plain(e), kind(e));
            }
            rig.proxy.rule = mutateWhen(FaultHarness::isList, r -> r.reset(10));
            IOException reset = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
            check("s3: listing connection reset mid-body is a plain IOException", plain(reset), kind(reset));
            rig.proxy.rule = mutateWhen(FaultHarness::isList, r -> r.declare(r.body.length + 5000));
            IOException longer = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
            check("s3: listing Content-Length larger than the body is a plain IOException", plain(longer),
                    kind(longer));
            rig.proxy.rule = mutateWhen(FaultHarness::isList, r -> r.declare(40));
            IOException shorter = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
            check("s3: listing Content-Length smaller than the body (XML cut) is a plain IOException",
                    plain(shorter), kind(shorter));

            // --- sidecars: a fresh store each time, so the cache cannot answer ---
            for (int cut : cuts) {
                String label = cut < 0 ? "half" : Integer.toString(cut);
                rig.proxy.rule = mutateWhen(FaultHarness::isSidecarGet,
                        r -> r.truncate(cut < 0 ? r.body.length / 2 : cut));
                IOException e = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
                check("s3: sidecar GET cut off at byte " + label + " fails the listing (never silently short)",
                        plain(e), kind(e));
            }
            rig.proxy.rule = mutateWhen(FaultHarness::isSidecarGet, r -> r.reset(7));
            IOException scReset = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
            check("s3: sidecar GET reset mid-body fails the listing", plain(scReset), kind(scReset));
            rig.proxy.rule = mutateWhen(FaultHarness::isSidecarGet, r -> r.declare(r.body.length + 100));
            IOException scLonger = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
            check("s3: sidecar Content-Length larger than its body fails the listing", plain(scLonger),
                    kind(scLonger));
            rig.proxy.rule = mutateWhen(FaultHarness::isSidecarGet, r -> r.declare(60));
            List<VaultEntry> cutShort = rig.store().listEntries(id.accountBase32(), id.storageMasterKey());
            check("s3: a sidecar cut short by its Content-Length is skipped (unreadable), not fatal",
                    cutShort.isEmpty(), cutShort.size() + "");
            byte[] oversized = new byte[(int) BackupLayout.MAX_ENTRY_BYTES + 4096];
            rig.proxy.rule = mutateWhen(FaultHarness::isSidecarGet, r -> {
                r.body = oversized;
                return r;
            });
            IOException over = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
            check("s3: a sidecar served larger than the 512 KB cap is refused (the listing fails)",
                    plain(over) && over.getMessage() != null && over.getMessage().contains("more than"),
                    kind(over));
            rig.proxy.rule = answerWhen(FaultHarness::isSidecarGet,
                    r -> Interposer.Reply.xml(404, s3Error("NoSuchKey", "gone")));
            List<VaultEntry> vanished = rig.store().listEntries(id.accountBase32(), id.storageMasterKey());
            check("s3: a sidecar that 404s after the listing named it is skipped", vanished.isEmpty());

            // --- the ETag moves between the listing and the GET ---
            String smallSidecar = objs + eSmall.objectId + BackupLayout.ENTRY_EXT;
            VaultEntry renamed = new VaultEntry(eSmall.objectId, eSmall.wrappedDek, "renamed.jpg", eSmall.size,
                    eSmall.mime, eSmall.downloadedAt, eSmall.chunkCount, eSmall.thumb, eSmall.origin,
                    eSmall.backedUpAt);
            byte[] renamedBlob = BackupLayout.encodeEntry(renamed, id.storageMasterKey());
            AtomicBoolean once = new AtomicBoolean(true);
            rig.proxy.rule = mutateWhen(FaultHarness::isList, r -> {
                if (once.getAndSet(false)) {
                    rig.s3.objects.put(smallSidecar, new FakeS3.Obj(renamedBlob, System.currentTimeMillis()));
                }
                return r;
            });
            S3Store moving = rig.store();
            List<VaultEntry> first = moving.listEntries(id.accountBase32(), id.storageMasterKey());
            rig.proxy.rule = req -> Interposer.Action.forward();
            int gets = rig.proxy.count("GET", BackupLayout.ENTRY_EXT);
            List<VaultEntry> second = moving.listEntries(id.accountBase32(), id.storageMasterKey());
            boolean firstRenamed = false;
            boolean secondRenamed = false;
            for (VaultEntry e : first) {
                firstRenamed |= "renamed.jpg".equals(e.name);
            }
            for (VaultEntry e : second) {
                secondRenamed |= "renamed.jpg".equals(e.name);
            }
            check("s3: a sidecar rewritten between the listing and its GET: the GET's content is used",
                    first.size() == 2 && firstRenamed, first.size() + " " + firstRenamed);
            check("s3: and the next listing (new ETag) re-reads it rather than trusting the old slot",
                    second.size() == 2 && secondRenamed
                            && rig.proxy.count("GET", BackupLayout.ENTRY_EXT) == gets + 1,
                    (rig.proxy.count("GET", BackupLayout.ENTRY_EXT) - gets) + " GETs");

            // --- ranged reads ---
            VaultEntry listedBig = null;
            for (VaultEntry e : engine.loadManifest()) {
                if (e.objectId.equals(eBig.objectId)) {
                    listedBig = e;
                }
            }
            final VaultEntry bigEntry = listedBig;
            rig.proxy.rule = mutateWhen(FaultHarness::isRangeGet, r -> r.truncate(r.body.length / 2)
                    .declare(r.body.length / 2));
            IOException shortRange = expectIo(() -> readAt(store, id, bigEntry, CHUNK + 5));
            check("s3: a 206 whose body is shorter than the range is refused as truncated",
                    plain(shortRange) && String.valueOf(shortRange.getMessage()).contains("truncated"),
                    kind(shortRange));
            rig.proxy.rule = mutateWhen(FaultHarness::isRangeGet, r -> r.remove("Content-Range"));
            IOException noRange = expectIo(() -> readAt(store, id, bigEntry, 5));
            check("s3: a 206 without a Content-Range is refused", plain(noRange), kind(noRange));
            rig.waits.clear();
            rig.proxy.rule = mutateWhen(FaultHarness::isRangeGet, r -> r.reset(1000));
            IOException resetRange = expectIo(() -> readAt(store, id, bigEntry, 5));
            // MAX_ATTEMPTS tries, a backoff between them, none after the last.
            check("s3: a chunk read reset mid-body every time is a plain IOException after bounded retries",
                    plain(resetRange) && rig.waits.size() == S3Client.MAX_ATTEMPTS - 1,
                    kind(resetRange) + " waits=" + rig.waits);
            AtomicInteger resets = new AtomicInteger(1);
            rig.proxy.rule = mutateWhen(FaultHarness::isRangeGet,
                    r -> resets.getAndDecrement() > 0 ? r.reset(1000) : r);
            byte[] got = readAt(store, id, bigEntry, CHUNK + 7);
            check("s3: a chunk read reset ONCE recovers with the right bytes",
                    got.length == 64 && Arrays.equals(got, Arrays.copyOfRange(original, CHUNK + 7, CHUNK + 71)));
            AtomicInteger ranges = new AtomicInteger();
            rig.proxy.rule = req -> isRangeGet(req) && ranges.incrementAndGet() > 1
                    ? Interposer.Action.answer(Interposer.Reply.xml(404, s3Error("NoSuchKey", "gone")))
                    : Interposer.Action.forward();
            IOException midStream = expectIo(() -> {
                VaultObjectReader reader = new VaultObjectReader(store, id, bigEntry);
                try {
                    reader.readAt(5, new byte[8], 0, 8);
                    reader.readAt(CHUNK + 5, new byte[8], 0, 8);
                } finally {
                    reader.close();
                }
            });
            check("s3: an object that 404s between two chunk reads is FileNotFoundException",
                    midStream instanceof FileNotFoundException, kind(midStream));

            // --- the restore stream ---
            rig.proxy.rule = mutateWhen(r -> isObjectGet(r) && r.header("Range") == null,
                    r -> r.declare(r.body.length + 9999));
            File r1 = new File(work, "r1.bin");
            IOException rLonger = expectIo(() -> engine.restoreFile(bigEntry, r1, null));
            check("s3: a restore stream whose Content-Length overstates the body still restores byte-identical "
                            + "(the engine reads exactly the sidecar's size, never to EOF)",
                    rLonger == null && sha256(Files.readAllBytes(r1.toPath())).equals(sha256(original)),
                    kind(rLonger));
            rig.proxy.rule = mutateWhen(r -> isObjectGet(r) && r.header("Range") == null,
                    r -> r.reset(CHUNK + 50));
            IOException rReset = expectIo(() -> engine.restoreFile(bigEntry, new File(work, "r2.bin"), null));
            check("s3: a restore stream reset mid-object is a plain IOException", plain(rReset), kind(rReset));
            rig.proxy.rule = mutateWhen(r -> isObjectGet(r) && r.header("Range") == null,
                    r -> r.truncate(r.body.length - 1).declare(r.body.length - 1));
            IOException rShort = expectIo(() -> engine.restoreFile(bigEntry, new File(work, "r3.bin"), null));
            check("s3: a restore stream one byte short is refused as truncated",
                    plain(rShort) && String.valueOf(rShort.getMessage()).contains("truncated"), kind(rShort));
            rig.proxy.rule = answerWhen(r -> isObjectGet(r) && r.header("Range") == null,
                    r -> Interposer.Reply.xml(404, s3Error("NoSuchKey", "gone")));
            IOException rGone = expectIo(() -> engine.restoreFile(bigEntry, new File(work, "r4.bin"), null));
            check("s3: a restore of an object that is gone is FileNotFoundException",
                    rGone instanceof FileNotFoundException, kind(rGone));
            rig.proxy.rule = req -> Interposer.Action.forward();

            // --- mid-upload: the connection reset while a part is being sent ---
            engine.deleteAll();
            rig.s3.log.clear();
            rig.waits.clear();
            AtomicInteger partResets = new AtomicInteger(1);
            rig.proxy.rule = req -> req.method.equals("PUT") && req.query().contains("partNumber=2")
                    && partResets.getAndDecrement() > 0
                    ? Interposer.Action.resetRequestAfter(1024 * 1024) : Interposer.Action.forward();
            VaultEntry resent = engine.backupFile(big, "video/mp4", null, null, null, 0);
            check("s3: an upload whose connection was reset mid-part ONCE lands (the part is resent)",
                    resent != null && rig.s3.objects.containsKey(objs + resent.objectId + BackupLayout.OBJECT_EXT)
                            && rig.s3.uploads.isEmpty() && rig.s3.count("MPUCREATE") == 1,
                    rig.s3.log.toString());
            File back = new File(work, "resent.bin");
            engine.restoreFile(resent, back, null);
            check("s3: and the object is byte-identical", sha256(Files.readAllBytes(back.toPath()))
                    .equals(sha256(original)));
            engine.deleteAll();
            rig.s3.log.clear();
            rig.proxy.rule = req -> req.method.equals("PUT") && req.query().contains("partNumber=2")
                    ? Interposer.Action.resetRequestAfter(1024 * 1024) : Interposer.Action.forward();
            IOException always = expectIo(() -> engine.backupFile(big, "video/mp4", null, null, null, 0));
            check("s3: reset on every attempt is a plain IOException", plain(always), kind(always));
            check("s3: and the multipart upload was aborted on the server, nothing committed",
                    rig.s3.uploads.isEmpty() && rig.s3.count("MPUABORT") == 1 && keysEnding(rig.s3, ".fdvc") == 0
                            && keysEnding(rig.s3, ".entry") == 0, rig.s3.log.toString());
            rig.proxy.rule = req -> Interposer.Action.forward();

            // --- the server accepts the upload, then lists nothing ---
            File swallowed = randomFile("swallowed.bin", 70_000, 3);
            rig.proxy.rule = req -> req.method.equals("PUT") && req.path().endsWith(BackupLayout.OBJECT_EXT)
                    && !req.query().contains("partNumber") ? Interposer.Action.swallow() : Interposer.Action.forward();
            VaultEntry ghost = engine.backupFile(swallowed, "image/jpeg", null, null, null, 0);
            rig.proxy.rule = req -> Interposer.Action.forward();
            boolean objectThere = rig.s3.objects.containsKey(objs + ghost.objectId + BackupLayout.OBJECT_EXT);
            boolean sidecarThere = rig.s3.objects.containsKey(objs + ghost.objectId + BackupLayout.ENTRY_EXT);
            List<VaultEntry> afterGhost = engine.loadManifest();
            check("s3: an object the server accepted but never stored is not listed (lonely sidecar skipped)",
                    !objectThere && sidecarThere && afterGhost.isEmpty(), afterGhost.size() + "");
            check("s3: and the lonely sidecar is never deleted", rig.s3.objects.containsKey(objs
                    + ghost.objectId + BackupLayout.ENTRY_EXT));
            IOException ghostRestore = expectIo(() -> engine.restoreFile(ghost, new File(work, "ghost.bin"), null));
            check("s3: restoring it is FileNotFoundException, not a hang or a corrupt file",
                    ghostRestore instanceof FileNotFoundException, kind(ghostRestore));
            VaultEntry again = engine.backupFile(swallowed, "image/jpeg", null, null, null, 0);
            check("s3: backing the file up again writes a fresh object (no dedup against the ghost)",
                    !again.objectId.equals(ghost.objectId) && engine.loadManifest().size() == 1);
        }
    }

    private static byte[] readAt(BackupStore store, SyncIdentity id, VaultEntry e, long pos) throws Exception {
        VaultObjectReader reader = new VaultObjectReader(store, id, e);
        try {
            byte[] buf = new byte[64];
            int n = reader.readAt(pos, buf, 0, buf.length);
            return n < 0 ? new byte[0] : Arrays.copyOf(buf, n);
        } finally {
            reader.close();
        }
    }

    // ---- s3: hostile answers ----

    private static void s3Hostile() throws Exception {
        try (S3Rig rig = new S3Rig()) {
            S3Store store = rig.store();
            SyncIdentity id = identity(2);
            VaultEngine engine = new VaultEngine(store, id);
            String objs = objectsPrefix(id);
            engine.backupFile(randomFile("h.bin", 20_000, 5), "image/jpeg", null, null, null, 0);

            // --- XML ---
            byte[] garbage = new byte[4096];
            new Random(9).nextBytes(garbage);
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.of(200, "application/xml", garbage));
            IOException junk = expectIo(engine::loadManifest);
            check("s3: a garbage listing body is a plain IOException", plain(junk), kind(junk));
            String laughs = "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\">"
                    + "<!ENTITY lol1 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">"
                    + "<!ENTITY lol2 \"&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;\">"
                    + "<!ENTITY lol3 \"&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;\">"
                    + "<!ENTITY lol4 \"&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;\">"
                    + "<!ENTITY lol5 \"&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;\">]>"
                    + "<ListBucketResult xmlns=\"" + S3_NS + "\"><Name>&lol5;</Name><IsTruncated>false"
                    + "</IsTruncated></ListBucketResult>";
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.xml(200, laughs));
            long t0 = System.nanoTime();
            IOException doctype = expectIo(engine::loadManifest);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check("s3: an entity-laden (DOCTYPE) listing is refused outright, fast",
                    plain(doctype) && String.valueOf(doctype.getMessage()).contains("DOCTYPE") && ms < 3000,
                    kind(doctype) + " in " + ms + " ms");
            String small = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY k \"" + objs + "x.fdvc\">]>"
                    + "<ListBucketResult xmlns=\"" + S3_NS + "\"><Contents><Key>&k;</Key><Size>1</Size>"
                    + "</Contents><IsTruncated>false</IsTruncated></ListBucketResult>";
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.xml(200, small));
            IOException entity = expectIo(engine::loadManifest);
            check("s3: a listing that resolves a key through an internal entity is refused, not parsed",
                    plain(entity), kind(entity));
            String xxe = "<?xml version=\"1.0\"?><ListBucketResult xmlns=\"" + S3_NS + "\"><Name>&xxe;</Name>"
                    + "<IsTruncated>false</IsTruncated></ListBucketResult>";
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.xml(200, xxe));
            IOException undefined = expectIo(engine::loadManifest);
            check("s3: an undefined entity reference is a plain IOException", plain(undefined), kind(undefined));
            byte[] huge = new byte[9 * 1024 * 1024];
            Arrays.fill(huge, (byte) ' ');
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.of(200, "application/xml", huge));
            IOException enormous = expectIo(engine::loadManifest);
            check("s3: a 9 MB listing is refused past the 8 MB cap", plain(enormous)
                    && String.valueOf(enormous.getMessage()).contains("more than"), kind(enormous));
            byte[] hugeError = new byte[32 * 1024 * 1024];
            Arrays.fill(hugeError, (byte) 'x');
            rig.proxy.bytesSent.set(0);
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.of(400, "application/xml",
                    hugeError).throttle(2));
            IOException bigError = expectIo(engine::loadManifest);
            check("s3: a 32 MB error body (400, served at ~32 MB/s) is not read whole: the client stops at its "
                            + "64 KB cap and closes", plain(bigError) && rig.proxy.bytesSent.get() < 16L * 1024 * 1024,
                    kind(bigError) + " server wrote " + rig.proxy.bytesSent.get());
            String errorIn200 = s3Error("InternalError", "sent after the status");
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.xml(200, errorIn200));
            IOException e200 = expectIo(engine::loadManifest);
            check("s3: a 200 carrying <Error> instead of a listing is a plain IOException", plain(e200), kind(e200));
            rig.s3.log.clear();
            rig.proxy.rule = answerWhen(r -> r.method.equals("POST") && r.query().contains("uploads="),
                    r -> Interposer.Reply.xml(200, errorIn200));
            IOException create200 = expectIo(() -> engine.backupFile(randomFile("c.bin", 2 * CHUNK, 6),
                    "video/mp4", null, null, null, 0));
            check("s3: a 200 carrying <Error> to CreateMultipartUpload is a plain IOException, nothing left",
                    plain(create200) && rig.s3.uploads.isEmpty() && keysEnding(rig.s3, ".fdvc") == 1,
                    kind(create200));
            String deleteError = "<?xml version=\"1.0\"?><Error><Code>AccessDenied</Code><Message>no</Message></Error>";
            rig.proxy.rule = answerWhen(r -> r.method.equals("POST") && r.query().contains("delete="),
                    r -> Interposer.Reply.xml(200, deleteError));
            int before = rig.s3.objects.size();
            engine.backupFile(randomFile("d2.bin", 1000, 7), "image/jpeg", null, null, null, 0);
            IOException del200 = expectIo(engine::deleteAll);
            check("s3: a 200 whose root is <Error> to DeleteObjects must fail the delete-all, not pass",
                    del200 != null, kind(del200) + "; objects still on the server: "
                            + (rig.s3.objects.size() >= before));
            rig.proxy.rule = req -> Interposer.Action.forward();
            engine.deleteAll();

            // --- pagination ---
            AtomicInteger pages = new AtomicInteger();
            rig.proxy.rule = req -> {
                if (!isList(req)) {
                    return Interposer.Action.forward();
                }
                int n = pages.incrementAndGet();
                if (n > 1500) {
                    return Interposer.Action.answer(Interposer.Reply.xml(503, s3Error("SlowDown", "ceiling")).reset(0));
                }
                return Interposer.Action.answer(Interposer.Reply.xml(200, listingPage(objs, n, true)));
            };
            IOException forever = expectIo(engine::loadManifest);
            check("s3: a continuation token that never ends is refused after a bounded number of pages",
                    plain(forever) && pages.get() <= 1001, kind(forever) + " pages=" + pages.get());
            AtomicInteger same = new AtomicInteger();
            rig.proxy.rule = req -> {
                if (!isList(req)) {
                    return Interposer.Action.forward();
                }
                int n = same.incrementAndGet();
                if (n > 1500) {
                    return Interposer.Action.answer(Interposer.Reply.xml(503, s3Error("SlowDown", "ceiling")).reset(0));
                }
                return Interposer.Action.answer(Interposer.Reply.xml(200, listingPage(objs, 1, true)));
            };
            IOException repeating = expectIo(engine::loadManifest);
            check("s3: a continuation token that repeats forever is refused after a bounded number of pages",
                    plain(repeating) && same.get() <= 1001, kind(repeating) + " pages=" + same.get());
            AtomicInteger ups = new AtomicInteger();
            rig.proxy.rule = req -> {
                if (!isListUploads(req)) {
                    return Interposer.Action.forward();
                }
                int n = ups.incrementAndGet();
                if (n > 1500) {
                    return Interposer.Action.answer(Interposer.Reply.xml(503, s3Error("SlowDown", "ceiling")).reset(0));
                }
                return Interposer.Action.answer(Interposer.Reply.xml(200, uploadsPage(n)));
            };
            boolean sweepReturned = finishesWithin(60_000, () -> store.sweepUploads(objs));
            check("s3: ListMultipartUploads that is always truncated with a new marker and no "
                            + "uploads must stop after a bounded number of pages (sweep / delete-all loop forever)",
                    sweepReturned && ups.get() <= 1001, "pages=" + ups.get() + " returned=" + sweepReturned);
            rig.proxy.rule = req -> Interposer.Action.forward();

            // --- 503 / 429 storms with every Retry-After shape ---
            String[] retryAfters = {"0", "-5", "2147483647", "Sat, 01 Jan 2099 00:00:00 GMT",
                    "Sat, 01 Jan 2000 00:00:00 GMT", "soon", ""};
            for (String ra : retryAfters) {
                rig.waits.clear();
                rig.proxy.reset();
                rig.proxy.rule = answerWhen(FaultHarness::isList, r -> {
                    Interposer.Reply reply = Interposer.Reply.xml(503, s3Error("SlowDown", "storm"));
                    if (!ra.isEmpty()) {
                        reply.header("Retry-After", ra);
                    }
                    return reply;
                });
                IOException storm = expectIo(engine::loadManifest);
                boolean bounded = true;
                for (long w : rig.waits) {
                    bounded &= w >= 0 && w <= S3Client.MAX_RETRY_AFTER_MS;
                }
                // OkHttp itself retries a 503 once, at once, when Retry-After is 0.
                int requests = rig.proxy.count("GET", "list-type=2");
                check("s3: a 503 storm with Retry-After '" + ra + "' is a plain IOException after "
                                + S3Client.MAX_ATTEMPTS + " attempts, every wait within [0, 30 s]",
                        plain(storm) && bounded && rig.waits.size() == S3Client.MAX_ATTEMPTS - 1
                                && requests >= S3Client.MAX_ATTEMPTS && requests <= 2 * S3Client.MAX_ATTEMPTS,
                        kind(storm) + " waits=" + rig.waits + " requests=" + requests);
            }
            for (String ra : new String[]{"4294967296", "99999999999999999999"}) {
                rig.waits.clear();
                rig.proxy.reset();
                rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.xml(503,
                        s3Error("SlowDown", "storm")).header("Retry-After", ra));
                IOException overflow = expectIo(engine::loadManifest);
                check("s3: a 503 whose digits-only Retry-After overflows an int ('" + ra
                                + "') must be a plain IOException, not a RuntimeException escaping Call.execute() "
                                + "(OkHttp's RetryAndFollowUpInterceptor parses it with Integer.valueOf; the worker "
                                + "fails the file terminally, the connect check's executor thread dies)",
                        plain(overflow), kind(overflow) + " waits=" + rig.waits);
            }
            // The connect check runs on CloudBackupManager's plain executor with no
            // catch around it: a RuntimeException out of S3Check.run is an uncaught
            // exception on a pool thread, i.e. the CrashHandler, and the sign-in page
            // never gets its answer.
            rig.proxy.reset();
            rig.proxy.rule = req -> Interposer.Action.answer(Interposer.Reply.xml(503,
                    s3Error("SlowDown", "storm")).header("Retry-After", "4294967296"));
            Throwable checkThrew = null;
            S3Check.Result checkResult = null;
            try {
                checkResult = S3Check.run(BASE, rig.endpoint(), "", rig.s3.bucket, "", S3_KEY_ID, S3_SECRET,
                        S3Config.Addressing.AUTO, null);
            } catch (RuntimeException e) {
                checkThrew = e;
                remember(e);
            }
            check("s3: the connect check against a 503 whose Retry-After overflows an int must answer "
                            + "(UNREACHABLE), not throw a RuntimeException the caller's executor thread dies on",
                    checkThrew == null && checkResult != null && checkResult.kind == S3Check.Kind.UNREACHABLE,
                    checkThrew != null ? "threw " + checkThrew : String.valueOf(checkResult == null ? null
                            : checkResult.kind));
            rig.waits.clear();
            rig.proxy.reset();
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.xml(429,
                    s3Error("TooManyRequests", "storm")).header("Retry-After", "-1"));
            IOException tooMany = expectIo(engine::loadManifest);
            check("s3: a 429 storm with a negative Retry-After is bounded the same way",
                    plain(tooMany) && rig.waits.size() == S3Client.MAX_ATTEMPTS - 1, kind(tooMany) + " " + rig.waits);
            rig.proxy.reset();
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.xml(500,
                    s3Error("InternalError", "always")).close());
            IOException fiveHundred = expectIo(engine::loadManifest);
            check("s3: a 500 that closes the connection each time is still a plain, bounded failure",
                    plain(fiveHundred) && rig.proxy.count("GET", "list-type=2") == S3Client.MAX_ATTEMPTS,
                    kind(fiveHundred));
            rig.proxy.rule = req -> Interposer.Action.forward();

            // --- failure classes that must NOT be mistaken for each other ---
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.of(404, "text/html",
                    "<html>not here</html>".getBytes(StandardCharsets.UTF_8)));
            IOException html404 = expectIo(engine::loadManifest);
            check("s3: a bare 404 page on a listing is a plain failure, never 'the source file is gone'",
                    plain(html404), kind(html404));
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.xml(403,
                    s3Error("AccessDenied", "Cannot upload files, storage cap exceeded.")));
            IOException cap = expectIo(engine::loadManifest);
            check("s3: a storage cap in words under AccessDenied is FULL, not needs-the-user",
                    cap instanceof BackupStore.FullException, kind(cap));
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.xml(403,
                    s3Error("AccessDenied", "Access Denied")));
            IOException denied = expectIo(engine::loadManifest);
            check("s3: a plain AccessDenied needs the user (Unreachable)",
                    denied instanceof BackupStore.UnreachableException
                            && !(denied instanceof BackupStore.FullException), kind(denied));
            rig.proxy.rule = req -> Interposer.Action.forward();
        }
    }

    // ---- s3: redirects, clock skew, region follow (the check and the store) ----

    private static void s3Checks() throws Exception {
        try (S3Rig rig = new S3Rig(); Interposer evil = Interposer.standalone()) {
            S3Store store = rig.store();
            SyncIdentity id = identity(3);
            VaultEngine engine = new VaultEngine(store, id);
            engine.backupFile(randomFile("r.bin", 1000, 8), "image/jpeg", null, null, null, 0);
            String goodListing = "<?xml version=\"1.0\"?><ListBucketResult xmlns=\"" + S3_NS + "\">"
                    + "<IsTruncated>false</IsTruncated></ListBucketResult>";
            evil.rule = req -> Interposer.Action.answer(Interposer.Reply.xml(200, goodListing));

            for (int status : new int[]{301, 302, 307}) {
                evil.clearSeen();
                rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.of(status, null, new byte[0])
                        .header("Location", "http://evil.fake:" + evil.port + "/backups?list-type=2"));
                IOException redirected = expectIo(engine::loadManifest);
                boolean classified = status == 302 ? plain(redirected)
                        : redirected instanceof S3Client.RefusedException
                        && ((S3Client.RefusedException) redirected).refusal == S3Client.Refusal.REGION;
                check("s3: a " + status + " is never followed: nothing reaches the other host",
                        evil.requests.get() == 0 && redirected != null, evil.seen.toString());
                check("s3: and a " + status + " is " + (status == 302 ? "a plain failure" : "a wrong-region refusal"),
                        classified, kind(redirected));
                evil.clearSeen();
                S3Check.Result r = S3Check.run(BASE, rig.endpoint(), "", rig.s3.bucket, "", S3_KEY_ID, S3_SECRET,
                        S3Config.Addressing.AUTO, null);
                check("s3: the check on a " + status + " answers " + (status == 302 ? "NOT_S3" : "WRONG_REGION")
                                + " and sends nothing to the other host",
                        r.kind == (status == 302 ? S3Check.Kind.NOT_S3 : S3Check.Kind.WRONG_REGION)
                                && evil.requests.get() == 0, r.kind + " evil=" + evil.seen);
            }

            // --- a redirect to https on another host, and a Location that is not a URL ---
            evil.clearSeen();
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.of(307, null, new byte[0])
                    .header("Location", "https://evil.fake:" + evil.port + "/"));
            IOException toHttps = expectIo(engine::loadManifest);
            check("s3: a 307 to an https host elsewhere: nothing sent there", toHttps != null
                    && evil.requests.get() == 0);
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.of(301, null, new byte[0])
                    .header("Location", "not a url at all"));
            IOException junkLocation = expectIo(engine::loadManifest);
            check("s3: a 301 with a junk Location is a refusal, not a crash",
                    junkLocation instanceof BackupStore.UnreachableException, kind(junkLocation));

            // --- a clock the server never agrees with ---
            rig.proxy.reset();
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.xml(403,
                    s3Error("RequestTimeTooSkewed", "always")).header("Date", "Sat, 01 Jan 2050 00:00:00 GMT"));
            IOException skew = expectIo(engine::loadManifest);
            check("s3: RequestTimeTooSkewed on every answer: re-signed exactly once, then a plain failure",
                    plain(skew) && rig.proxy.count("GET", "list-type=2") == 2,
                    kind(skew) + " requests=" + rig.proxy.count("GET", "list-type=2"));
            rig.proxy.reset();
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.xml(403,
                    s3Error("RequestTimeTooSkewed", "no date to learn from")));
            IOException skewNoDate = expectIo(engine::loadManifest);
            check("s3: RequestTimeTooSkewed with no Date header: one request, a plain failure",
                    plain(skewNoDate) && rig.proxy.count("GET", "list-type=2") == 1, kind(skewNoDate));
            S3Client.phoneClock = System::currentTimeMillis;

            // --- a region that keeps moving ---
            rig.proxy.reset();
            AtomicInteger region = new AtomicInteger();
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.xml(400,
                    "<?xml version=\"1.0\"?><Error><Code>AuthorizationHeaderMalformed</Code><Message>wrong"
                            + "</Message><Region>region-" + region.incrementAndGet() + "</Region></Error>"));
            S3Check.Result moving = S3Check.run(BASE, rig.endpoint(), "", rig.s3.bucket, "", S3_KEY_ID, S3_SECRET,
                    S3Config.Addressing.AUTO, null);
            check("s3: a server that names a new region on every answer is followed a bounded number of times",
                    moving.kind == S3Check.Kind.WRONG_REGION && rig.proxy.count("GET", "list-type=2") <= 4,
                    moving.kind + " requests=" + rig.proxy.count("GET", "list-type=2"));
            rig.proxy.reset();
            rig.proxy.rule = answerWhen(FaultHarness::isList, r -> Interposer.Reply.xml(400,
                    "<?xml version=\"1.0\"?><Error><Code>AuthorizationHeaderMalformed</Code><Message>wrong"
                            + "</Message><Region>" + "r".repeat(50_000) + "</Region></Error>"));
            S3Check.Result longRegion = S3Check.run(BASE, rig.endpoint(), "", rig.s3.bucket, "", S3_KEY_ID,
                    S3_SECRET, S3Config.Addressing.AUTO, null);
            check("s3: a 50 KB region name is followed once and then refused, never saved as OK",
                    longRegion.kind == S3Check.Kind.WRONG_REGION, longRegion.kind + "");
            rig.proxy.rule = req -> Interposer.Action.forward();

            // --- the reconnect rule under a hostile first host ---
            S3Config saved = rig.config();
            try (Interposer other = Interposer.standalone()) {
                other.rule = req -> Interposer.Action.answer(Interposer.Reply.xml(200, goodListing));
                S3Check.Result reuse = S3Check.runReusing(BASE, saved, "http://evil.fake:" + other.port, "",
                        rig.s3.bucket, "", S3_KEY_ID, null, S3Config.Addressing.AUTO, null);
                check("s3: a saved secret is never sent to another endpoint, even one that would accept it",
                        reuse.kind == S3Check.Kind.AUTH_FAILED && other.requests.get() == 0,
                        reuse.kind + " " + other.seen);
            }
        }
    }

    // ---- webdav: broken wire ----

    private static void webdavWire() throws Exception {
        try (DavRig rig = new DavRig()) {
            WebDavStore store = rig.store();
            SyncIdentity id = identity(4);
            VaultEngine engine = new VaultEngine(store, id);
            String objs = DAV + "/" + BackupLayout.ROOT_DIR + "/" + id.accountBase32() + "/"
                    + BackupLayout.OBJECTS_DIR;
            File big = randomFile("dbig.bin", 2 * CHUNK + 777, 11);
            byte[] original = Files.readAllBytes(big.toPath());
            VaultEntry eBig = engine.backupFile(big, "video/mp4", null, null, null, 0);
            engine.backupFile(randomFile("dsmall.bin", 40_000, 12), "image/jpeg", "t", null, null, 0);
            check("webdav: the rig works through the interposer", engine.loadManifest().size() == 2
                    && rig.proxy.requests.get() > 5);

            int[] cuts = {0, 10, -1};
            for (int cut : cuts) {
                String label = cut < 0 ? "half" : Integer.toString(cut);
                rig.proxy.rule = mutateWhen(FaultHarness::isPropfind,
                        r -> r.truncate(cut < 0 ? r.body.length / 2 : cut));
                IOException e = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
                check("webdav: PROPFIND body cut off at byte " + label + " is a plain IOException (not Unreachable)",
                        plain(e), kind(e));
            }
            rig.proxy.rule = mutateWhen(FaultHarness::isPropfind, r -> r.reset(20));
            IOException reset = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
            check("webdav: PROPFIND reset mid-body is a plain IOException", plain(reset), kind(reset));
            rig.proxy.rule = mutateWhen(FaultHarness::isPropfind, r -> r.declare(r.body.length + 777));
            IOException longer = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
            check("webdav: PROPFIND Content-Length larger than the body is a plain IOException", plain(longer),
                    kind(longer));
            rig.proxy.rule = mutateWhen(FaultHarness::isPropfind, r -> r.declare(30));
            IOException shorter = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
            check("webdav: PROPFIND Content-Length smaller than the body (XML cut) is a plain IOException",
                    plain(shorter), kind(shorter));
            byte[] garbage = new byte[2048];
            new Random(3).nextBytes(garbage);
            rig.proxy.rule = mutateWhen(FaultHarness::isPropfind, r -> {
                r.body = garbage;
                return r;
            });
            IOException junk = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
            check("webdav: a garbage 207 body is a plain IOException", plain(junk), kind(junk));
            String doctype = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x \"y\">]><d:multistatus xmlns:d=\"DAV:\">"
                    + "<d:response><d:href>/&x;</d:href></d:response></d:multistatus>";
            rig.proxy.rule = mutateWhen(FaultHarness::isPropfind, r -> {
                r.body = doctype.getBytes(StandardCharsets.UTF_8);
                return r;
            });
            IOException dt = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
            check("webdav: a 207 with a DOCTYPE is refused", plain(dt)
                    && String.valueOf(dt.getMessage()).contains("DOCTYPE"), kind(dt));
            byte[] huge = new byte[33 * 1024 * 1024];
            Arrays.fill(huge, (byte) ' ');
            rig.proxy.rule = mutateWhen(FaultHarness::isPropfind, r -> {
                r.body = huge;
                return r;
            });
            IOException enormous = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
            check("webdav: a 33 MB PROPFIND answer is refused past the 32 MB cap", plain(enormous)
                    && String.valueOf(enormous.getMessage()).contains("more than"), kind(enormous));

            for (int cut : cuts) {
                String label = cut < 0 ? "half" : Integer.toString(cut);
                rig.proxy.rule = mutateWhen(FaultHarness::isSidecarGet,
                        r -> r.truncate(cut < 0 ? r.body.length / 2 : cut));
                IOException e = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
                check("webdav: sidecar GET cut off at byte " + label + " fails the listing (never silently short)",
                        plain(e), kind(e));
            }
            rig.proxy.rule = mutateWhen(FaultHarness::isSidecarGet, r -> r.reset(5));
            IOException scReset = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
            check("webdav: sidecar GET reset mid-body fails the listing", plain(scReset), kind(scReset));
            byte[] oversized = new byte[(int) BackupLayout.MAX_ENTRY_BYTES + 1];
            rig.proxy.rule = mutateWhen(FaultHarness::isSidecarGet, r -> {
                r.body = oversized;
                return r;
            });
            IOException over = expectIo(() -> rig.store().listEntries(id.accountBase32(), id.storageMasterKey()));
            check("webdav: a sidecar served larger than the 512 KB cap is refused", plain(over)
                    && String.valueOf(over.getMessage()).contains("more than"), kind(over));
            rig.proxy.rule = answerWhen(FaultHarness::isSidecarGet, r -> Interposer.Reply.of(404, null, new byte[0]));
            List<VaultEntry> vanished = rig.store().listEntries(id.accountBase32(), id.storageMasterKey());
            check("webdav: a sidecar that 404s after the listing named it is skipped", vanished.isEmpty());

            // --- ranged reads and the restore stream ---
            VaultEntry listedBig = null;
            for (VaultEntry e : engine.loadManifest()) {
                if (e.objectId.equals(eBig.objectId)) {
                    listedBig = e;
                }
            }
            final VaultEntry bigEntry = listedBig;
            rig.proxy.rule = mutateWhen(FaultHarness::isRangeGet, r -> r.truncate(r.body.length / 2)
                    .declare(r.body.length / 2));
            IOException shortRange = expectIo(() -> readAt(store, id, bigEntry, CHUNK + 5));
            check("webdav: a 206 whose body is shorter than the range is refused as truncated",
                    plain(shortRange) && String.valueOf(shortRange.getMessage()).contains("truncated"),
                    kind(shortRange));
            rig.proxy.rule = mutateWhen(FaultHarness::isRangeGet, r -> r.remove("Content-Range"));
            IOException noRange = expectIo(() -> readAt(store, id, bigEntry, 5));
            check("webdav: a 206 without a Content-Range is refused", plain(noRange), kind(noRange));
            rig.proxy.rule = mutateWhen(FaultHarness::isRangeGet, r -> r.reset(100));
            IOException resetRange = expectIo(() -> readAt(store, id, bigEntry, 5));
            check("webdav: a chunk read reset mid-body is a plain IOException", plain(resetRange), kind(resetRange));
            AtomicInteger ranges = new AtomicInteger();
            rig.proxy.rule = req -> isRangeGet(req) && ranges.incrementAndGet() > 1
                    ? Interposer.Action.answer(Interposer.Reply.of(404, null, new byte[0]))
                    : Interposer.Action.forward();
            IOException midStream = expectIo(() -> {
                VaultObjectReader reader = new VaultObjectReader(store, id, bigEntry);
                try {
                    reader.readAt(5, new byte[8], 0, 8);
                    reader.readAt(CHUNK + 5, new byte[8], 0, 8);
                } finally {
                    reader.close();
                }
            });
            check("webdav: an object that 404s between two chunk reads is FileNotFoundException",
                    midStream instanceof FileNotFoundException, kind(midStream));
            rig.proxy.rule = req -> Interposer.Action.forward();
            byte[] good = readAt(store, id, bigEntry, CHUNK + 7);
            check("webdav: with the interposer forwarding again, the bytes are right",
                    Arrays.equals(good, Arrays.copyOfRange(original, CHUNK + 7, CHUNK + 71)));
            rig.proxy.rule = mutateWhen(r -> isObjectGet(r) && r.header("Range") == null,
                    r -> r.declare(r.body.length + 9999));
            File dr1 = new File(work, "dr1.bin");
            IOException rLonger = expectIo(() -> engine.restoreFile(bigEntry, dr1, null));
            check("webdav: a restore stream whose Content-Length overstates the body still restores byte-identical",
                    rLonger == null && sha256(Files.readAllBytes(dr1.toPath())).equals(sha256(original)),
                    kind(rLonger));
            rig.proxy.rule = mutateWhen(r -> isObjectGet(r) && r.header("Range") == null, r -> r.reset(CHUNK + 50));
            IOException rReset = expectIo(() -> engine.restoreFile(bigEntry, new File(work, "dr2.bin"), null));
            check("webdav: a restore stream reset mid-object is a plain IOException", plain(rReset), kind(rReset));

            // --- the streamed upload cut mid-body ---
            engine.deleteAll();
            rig.proxy.rule = req -> req.method.equals("PUT") && req.path().endsWith(BackupLayout.OBJECT_EXT)
                    ? Interposer.Action.resetRequestAfter(3 * 1024 * 1024) : Interposer.Action.forward();
            IOException cut = expectIo(() -> engine.backupFile(big, "video/mp4", null, null, null, 0));
            check("webdav: an upload whose connection is reset mid-body is a plain IOException", plain(cut), kind(cut));
            check("webdav: and the server holds neither the object nor a sidecar",
                    rig.dav.childrenOf(objs).isEmpty(), rig.dav.childrenOf(objs).toString());
            rig.proxy.rule = req -> req.method.equals("PUT") && req.path().endsWith(BackupLayout.OBJECT_EXT)
                    ? Interposer.Action.answer(Interposer.Reply.of(201, null, new byte[0]).close())
                    : Interposer.Action.forward();
            IOException early = expectIo(() -> engine.backupFile(big, "video/mp4", null, null, null, 0));
            boolean earlyOk = early == null;
            List<String> after = rig.dav.childrenOf(objs);
            check("webdav: a server that answers 201 before reading the body: the backup reports what the "
                            + "server said, and a listing then shows a lonely sidecar skipped",
                    (earlyOk && engine.loadManifest().isEmpty()) || plain(early),
                    kind(early) + " children=" + after);
            rig.dav.nodes.keySet().removeIf(k -> k.startsWith(objs + "/"));

            // --- a 503 storm: the WebDAV client never retries by itself, the worker does ---
            rig.proxy.reset();
            rig.proxy.rule = answerWhen(FaultHarness::isPropfind, r -> Interposer.Reply.of(503, "text/plain",
                    "busy".getBytes(StandardCharsets.UTF_8)).header("Retry-After", "2147483647"));
            IOException storm = expectIo(engine::loadManifest);
            check("webdav: a 503 with an absurd Retry-After is one request and a plain IOException",
                    plain(storm) && rig.proxy.count("PROPFIND", null) == 1, kind(storm) + " requests="
                            + rig.proxy.count("PROPFIND", null));
            rig.proxy.reset();
            rig.proxy.rule = answerWhen(FaultHarness::isPropfind, r -> Interposer.Reply.of(503, "text/plain",
                    "busy".getBytes(StandardCharsets.UTF_8)).header("Retry-After", "4294967296"));
            IOException overflow = expectIo(engine::loadManifest);
            check("webdav: a 503 whose digits-only Retry-After overflows an int must be a plain "
                            + "IOException, not a RuntimeException escaping Call.execute()",
                    plain(overflow), kind(overflow));
            // Same for the connect check (run on the manager's executor with no catch).
            rig.proxy.reset();
            rig.proxy.rule = req -> Interposer.Action.answer(Interposer.Reply.of(503, "text/plain",
                    "busy".getBytes(StandardCharsets.UTF_8)).header("Retry-After", "4294967296"));
            Throwable checkThrew = null;
            WebDavCheck.Result checkResult = null;
            try {
                checkResult = WebDavCheck.run(BASE, rig.url("/dav/"), DAV_USER, DAV_PASSWORD, null);
            } catch (RuntimeException e) {
                checkThrew = e;
                remember(e);
            }
            check("webdav: the connect check against a 503 whose Retry-After overflows an int must "
                            + "answer (UNREACHABLE), not throw a RuntimeException the caller's executor thread dies on",
                    checkThrew == null && checkResult != null && checkResult.kind == WebDavCheck.Kind.UNREACHABLE,
                    checkThrew != null ? "threw " + checkThrew : String.valueOf(checkResult == null ? null
                            : checkResult.kind));
            rig.proxy.reset();
            rig.proxy.rule = answerWhen(FaultHarness::isPropfind, r -> Interposer.Reply.of(401, null, new byte[0])
                    .header("WWW-Authenticate", "Basic realm=\"x\""));
            IOException refused = expectIo(engine::loadManifest);
            check("webdav: a 401 needs the user", refused instanceof BackupStore.UnreachableException, kind(refused));
            rig.proxy.rule = answerWhen(FaultHarness::isPropfind, r -> Interposer.Reply.of(507, null, new byte[0]));
            IOException full = expectIo(engine::loadManifest);
            check("webdav: a 507 is FULL", full instanceof BackupStore.FullException, kind(full));
            rig.proxy.rule = req -> Interposer.Action.forward();
        }
    }

    // ---- webdav: redirects ----

    private static void webdavRedirects() throws Exception {
        String multistatus = "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\"><d:response>"
                + "<d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype>"
                + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";
        Interposer.Rule evilDav = req -> {
            switch (req.method) {
                case "PROPFIND":
                    return Interposer.Action.answer(req.path().equals("/dav/")
                            ? Interposer.Reply.of(207, "application/xml", multistatus.getBytes(StandardCharsets.UTF_8))
                            : Interposer.Reply.of(404, null, new byte[0]));
                case "MKCOL":
                case "PUT":
                    return Interposer.Action.answer(Interposer.Reply.of(201, null, new byte[0]));
                case "DELETE":
                    return Interposer.Action.answer(Interposer.Reply.of(204, null, new byte[0]));
                default:
                    return Interposer.Action.answer(Interposer.Reply.of(405, null, new byte[0]));
            }
        };
        try (DavRig rig = new DavRig(); Interposer evil = Interposer.standalone()) {
            evil.rule = evilDav;
            rig.dav.mkdirs("/dav");
            for (int status : new int[]{301, 302, 307}) {
                evil.clearSeen();
                rig.proxy.rule = answerWhen(r -> isPropfind(r) && r.path().equals("/dav/"),
                        r -> Interposer.Reply.of(status, null, new byte[0])
                                .header("Location", "http://evil.fake:" + evil.port + "/dav/"));
                WebDavCheck.Result r = WebDavCheck.run(BASE, rig.url("/dav/"), DAV_USER, DAV_PASSWORD, null);
                if (System.getenv("FAULT_HARNESS_DEBUG") != null) {
                    System.out.println("    DEBUG evil saw " + evil.seen);
                    WebDavClient probe = new WebDavClient(BASE, HttpUrl.get(rig.url("/dav/")), DAV_USER,
                            DAV_PASSWORD, null);
                    WebDavClient.Propfind pf = probe.propfind(HttpUrl.get(rig.url("/dav/")), 0);
                    System.out.println("    DEBUG direct propfind: code=" + pf.code + " final=" + pf.finalUrl
                            + " self=" + (pf.self == null ? "null" : pf.self.url + " coll=" + pf.self.collection)
                            + " children=" + pf.children.size());
                }
                boolean authSeen = false;
                for (Interposer.Req req : evil.seen) {
                    authSeen |= req.header("Authorization") != null;
                }
                check("webdav: a " + status + " to another host: the redirected request carries no Authorization",
                        evil.requests.get() > 0 && !authSeen, evil.seen.toString());
                boolean relocated = r.kind == WebDavCheck.Kind.OK && r.config != null
                        && r.config.url.contains("evil.fake");
                if (relocated) {
                    evil.clearSeen();
                    new WebDavStore(r.config, BASE).isReachable();
                    boolean leaked = false;
                    for (Interposer.Req req : evil.seen) {
                        leaked |= req.header("Authorization") != null;
                    }
                    check("webdav: a " + status + " to ANOTHER host must not relocate the saved server "
                                    + "there (the password then goes to the redirect target on every request)",
                            !leaked, "saved url=" + r.config.url + " authorization sent=" + leaked);
                } else {
                    check("webdav: a " + status + " to another host is answered MOVED, naming the target, "
                                    + "and nothing is saved",
                            r.kind == WebDavCheck.Kind.MOVED && r.config == null && r.movedTo != null
                                    && r.movedTo.contains("evil.fake"),
                            r.kind + " movedTo=" + r.movedTo);
                }
            }
            rig.proxy.rule = req -> Interposer.Action.forward();
        }
    }

    // ---- tls: a pinned certificate that changes under a live store ----

    private static void pinChanges(String keystoreA, String keystoreB) throws Exception {
        String pinA = certSha256(keystoreA);
        String pinB = certSha256(keystoreB);
        SSLContext tlsA = serverTls(keystoreA);
        SSLContext tlsB = serverTls(keystoreB);

        // S3: the same address, another certificate behind it.
        FakeS3 s3a = new FakeS3("s3.fake");
        s3a.secret = S3_SECRET;
        s3a.accessKey = S3_KEY_ID;
        FakeS3 s3b = new FakeS3("s3.fake");
        s3b.secret = S3_SECRET;
        s3b.accessKey = S3_KEY_ID;
        try (Backend a = new Backend(s3a, tlsA, 0); Backend b = new Backend(s3b, tlsB, 0);
             TcpRelay relay = new TcpRelay(a.port)) {
            S3Config cfg = new S3Config("https://s3.fake:" + relay.port, "us-east-1", "backups", "", S3_KEY_ID,
                    S3_SECRET, true, S3Config.Addressing.AUTO, pinA);
            S3Store store = new S3Store(cfg, BASE);
            store.client().sleeper = ms -> { };
            SyncIdentity id = identity(5);
            VaultEngine engine = new VaultEngine(store, id);
            engine.backupFile(randomFile("tls.bin", 1000, 5), "image/jpeg", null, null, null, 0);
            check("tls s3: backups work through the pin", engine.loadManifest().size() == 1);
            relay.switchTo(b.port);
            IOException changed = expectIo(engine::loadManifest);
            check("tls s3: the certificate changing mid-session is UNREACHABLE (needs the user)",
                    changed instanceof BackupStore.UnreachableException, kind(changed));
            ServerTrust.CertInfo seen = store.client().rejectedCert();
            check("tls s3: the new certificate is what the client recorded for the trust prompt",
                    seen != null && pinB.equals(seen.sha256));
            check("tls s3: no request reached the server behind the changed certificate", s3b.log.isEmpty(),
                    s3b.log.toString());
            S3Check.Result re = S3Check.runReusing(BASE, cfg, "https://s3.fake:" + relay.port, "", "backups", "",
                    S3_KEY_ID, null, S3Config.Addressing.AUTO, null);
            check("tls s3: a reconnect with the old pin shows the NEW certificate to trust, sends nothing",
                    re.kind == S3Check.Kind.UNTRUSTED_CERT && re.cert != null && pinB.equals(re.cert.sha256)
                            && s3b.log.isEmpty(), re.kind + "");
            S3Check.Result trustB = S3Check.runReusing(BASE, cfg, "https://s3.fake:" + relay.port, "", "backups", "",
                    S3_KEY_ID, null, S3Config.Addressing.AUTO, pinB);
            check("tls s3: trusting the new certificate reconnects with the saved secret",
                    trustB.kind == S3Check.Kind.OK && trustB.config != null && pinB.equals(trustB.config.pinSha256),
                    trustB.kind + "");
        }

        // WebDAV: the same, and an https address redirected to http.
        FakeDav davA = new FakeDav();
        davA.password = DAV_PASSWORD;
        davA.mkdirs(DAV);
        davA.mkdirs("/dav");
        FakeDav davB = new FakeDav();
        davB.password = DAV_PASSWORD;
        davB.mkdirs(DAV);
        try (Backend a = new Backend(davA, tlsA, 0); Backend b = new Backend(davB, tlsB, 0);
             TcpRelay relay = new TcpRelay(a.port); Interposer evil = Interposer.standalone()) {
            WebDavConfig cfg = new WebDavConfig("https://nas.fake:" + relay.port + DAV + "/", DAV_USER, DAV_PASSWORD,
                    pinA);
            WebDavStore store = new WebDavStore(cfg, BASE);
            SyncIdentity id = identity(6);
            VaultEngine engine = new VaultEngine(store, id);
            engine.backupFile(randomFile("dtls.bin", 1000, 6), "image/jpeg", null, null, null, 0);
            check("tls webdav: backups work through the pin", engine.loadManifest().size() == 1);
            relay.switchTo(b.port);
            IOException changed = expectIo(engine::loadManifest);
            check("tls webdav: the certificate changing mid-session is UNREACHABLE (needs the user)",
                    changed instanceof BackupStore.UnreachableException, kind(changed));
            check("tls webdav: no request reached the server behind the changed certificate", davB.log.isEmpty(),
                    davB.log.toString());
            WebDavCheck.Result re = WebDavCheck.runReusing(BASE, cfg, "https://nas.fake:" + relay.port + DAV + "/",
                    DAV_USER, null, null);
            check("tls webdav: a reconnect with the old pin shows the NEW certificate, sends nothing",
                    re.kind == WebDavCheck.Kind.UNTRUSTED_CERT && re.cert != null && pinB.equals(re.cert.sha256)
                            && davB.log.isEmpty(), re.kind + "");
            relay.switchTo(a.port);

            evil.rule = req -> Interposer.Action.answer(Interposer.Reply.of(207, "application/xml",
                    ("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/dav/</d:href>"
                            + "<d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>"
                            + "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>")
                            .getBytes(StandardCharsets.UTF_8)));
            // Same host name, plain http (another port): the other-host rule
            // does not apply, so this is the downgrade rule's case alone.
            davA.redirects.put("/dav/", "http://nas.fake:" + evil.port + "/dav/");
            WebDavCheck.Result down = WebDavCheck.run(BASE, "https://nas.fake:" + relay.port + "/dav/", DAV_USER,
                    DAV_PASSWORD, pinA);
            boolean authSeen = false;
            for (Interposer.Req req : evil.seen) {
                authSeen |= req.header("Authorization") != null;
            }
            check("tls webdav: an https address redirected to http is not accepted by the check",
                    down.kind != WebDavCheck.Kind.OK, down.kind + "");
            check("tls webdav: and the http host, which did receive the redirected PROPFIND, saw no Authorization",
                    evil.requests.get() > 0 && !authSeen, evil.seen.toString());
        }
    }

    // ---- cancel at every chunk boundary ----

    private static void cancels() throws Exception {
        try (S3Rig rig = new S3Rig()) {
            S3Store store = rig.store();
            SyncIdentity id = identity(7);
            File big = randomFile("cbig.bin", 3 * CHUNK + 5, 13);
            long total = big.length();
            long[] points = {0, CHUNK, 2L * CHUNK, 3L * CHUNK, total};
            for (long point : points) {
                rig.s3.log.clear();
                VaultEngine engine = new VaultEngine(store, id);
                IOException cancelled = expectIo(() -> engine.backupFile(big, "video/mp4", null, (done, t) -> {
                    if (done >= point) {
                        engine.cancel();
                    }
                }, null, 0));
                String label = point == total ? "after the last chunk, before the sidecar"
                        : "after " + (point / CHUNK) + " chunk(s)";
                check("cancel s3: " + label + ": the backup fails as cancelled",
                        cancelled != null && "cancelled".equals(cancelled.getMessage()), kind(cancelled));
                check("cancel s3: " + label + ": no upload left open, no object, no sidecar",
                        rig.s3.uploads.isEmpty() && keysEnding(rig.s3, ".fdvc") == 0
                                && keysEnding(rig.s3, ".entry") == 0
                                && (point == total ? rig.s3.count("MPUCOMPLETE") == 1 && rig.s3.count("DELETE ") >= 1
                                : rig.s3.count("MPUCREATE") == 0 || rig.s3.count("MPUABORT") == 1),
                        rig.s3.log.toString());
            }
            check("cancel s3: nothing is listed afterwards", new VaultEngine(store, id).loadManifest().isEmpty());
        }
        try (DavRig rig = new DavRig()) {
            WebDavStore store = rig.store();
            SyncIdentity id = identity(8);
            String objs = DAV + "/" + BackupLayout.ROOT_DIR + "/" + id.accountBase32() + "/"
                    + BackupLayout.OBJECTS_DIR;
            File big = randomFile("dcbig.bin", 3 * CHUNK + 5, 14);
            long total = big.length();
            long[] points = {0, CHUNK, 2L * CHUNK, total};
            for (long point : points) {
                VaultEngine engine = new VaultEngine(store, id);
                IOException cancelled = expectIo(() -> engine.backupFile(big, "video/mp4", null, (done, t) -> {
                    if (done >= point) {
                        engine.cancel();
                    }
                }, null, 0));
                String label = point == total ? "after the last chunk" : "after " + (point / CHUNK) + " chunk(s)";
                check("cancel webdav: " + label + ": cancelled, and the server holds nothing",
                        cancelled != null && "cancelled".equals(cancelled.getMessage())
                                && rig.dav.childrenOf(objs).isEmpty(),
                        kind(cancelled) + " " + rig.dav.childrenOf(objs));
            }
        }
    }

    // ---- url: userinfo ----

    private static void userinfo() {
        HttpUrl dav = WebDavCheck.normalize("https://alice:PWINURL@nas.fake/dav/");
        check("url: a WebDAV address typed with user:password@ keeps the password in the URL "
                        + "(saved in plain prefs, printed by WebDavConfig.toString)",
                dav != null && !dav.toString().contains("PWINURL")
                        && !new WebDavConfig(dav.toString(), "alice", "x", null).toString().contains("PWINURL"),
                dav == null ? "null" : "userinfo kept");
        HttpUrl s3 = S3Check.normalizeEndpoint("https://akid:PWINURL@s3.fake");
        check("url: an S3 endpoint typed with user:password@ keeps it in the endpoint "
                        + "(saved in plain prefs, printed by S3Config.toString)",
                s3 != null && !S3Check.endpointString(s3).contains("PWINURL"),
                s3 == null ? "null" : "userinfo kept");
    }

    // ---- drips ----

    private static void drips() throws Exception {
        // StallGuard wants 16 KB per window; two minutes on a phone, one
        // second here, so a 400 ms drip trips it within the budget.
        long window = StallGuard.windowMs;
        StallGuard.windowMs = 1000;
        try {
            dripsWithShortWindow();
        } finally {
            StallGuard.windowMs = window;
        }
    }

    private static void dripsWithShortWindow() throws Exception {
        try (S3Rig rig = new S3Rig()) {
            S3Store store = rig.store();
            SyncIdentity id = identity(9);
            new VaultEngine(store, id).backupFile(randomFile("drip.bin", 1000, 15), "image/jpeg", null, null,
                    null, 0);
            rig.proxy.rule = mutateWhen(FaultHarness::isSidecarGet, r -> r.drip(400));
            boolean returned = finishesWithin(8000, () -> rig.store().listEntries(id.accountBase32(),
                    id.storageMasterKey()));
            rig.proxy.stopDrips = true;
            check("s3: a sidecar dripped one byte per 400 ms times out (StallGuard), the listing does "
                    + "not hang for as long as bytes keep coming", returned, "still reading after 8 s");
        }
        try (DavRig rig = new DavRig()) {
            WebDavStore store = rig.store();
            SyncIdentity id = identity(10);
            new VaultEngine(store, id).backupFile(randomFile("ddrip.bin", 1000, 16), "image/jpeg", null, null,
                    null, 0);
            rig.proxy.rule = mutateWhen(FaultHarness::isPropfind, r -> r.drip(400));
            boolean returned = finishesWithin(8000, () -> rig.store().listEntries(id.accountBase32(),
                    id.storageMasterKey()));
            rig.proxy.stopDrips = true;
            check("webdav: a PROPFIND dripped one byte per 400 ms times out (StallGuard)", returned,
                    "still reading after 8 s");
        }
    }

    // ---- memory: within the byte caps, on a phone-sized heap ----

    private static void memory() throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String cp = System.getProperty("harness.cp");
        StringBuilder s3Seen = new StringBuilder();
        StringBuilder davSeen = new StringBuilder();
        boolean s3At256 = false;
        boolean davAt256 = false;
        for (int mb : new int[]{128, 256, 512}) {
            ProcessBuilder pb = new ProcessBuilder(java, "-Xmx" + mb + "m", "-cp", cp,
                    "com.solarized.firedown.sync.FaultHarness", "--xml-bomb");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(120, TimeUnit.SECONDS);
            boolean s3Ok = out.contains("s3-bomb: handled");
            boolean davOk = out.contains("dav-bomb: handled");
            s3Seen.append(mb).append(" MB: ").append(s3Ok ? "ok" : firstLine(out, "s3-bomb")).append("; ");
            davSeen.append(mb).append(" MB: ").append(davOk ? "ok" : firstLine(out, "dav-bomb")).append("; ");
            if (mb == 256) {
                s3At256 = s3Ok;
                davAt256 = davOk;
            }
        }
        System.out.println("    s3 2M-element 8 MB listing: " + s3Seen);
        System.out.println("    webdav 700k-response 32 MB multistatus: " + davSeen);
        check("memory s3: an 8 MB listing of 2 million empty elements, inside the byte cap, is parsed or refused "
                + "within a 256 MB heap (it needs more than 128 MB: the element tree is unbounded in COUNT)",
                s3At256, s3Seen.toString());
        check("memory webdav: a 32 MB multistatus of 700k responses, inside the byte cap, is parsed or refused "
                + "within a 256 MB heap (it needs more than 128 MB: one HttpUrl + Resource per response)",
                davAt256, davSeen.toString());
    }

    private static String firstLine(String out, String prefix) {
        for (String line : out.split("\n")) {
            if (line.startsWith(prefix)) {
                return line.trim();
            }
        }
        return "(no " + prefix + " line; output: " + out.substring(0, Math.min(400, out.length())) + ")";
    }

    private static void xmlBombChild() {
        try {
            StringBuilder sb = new StringBuilder(8 * 1024 * 1024 + 200);
            sb.append("<?xml version=\"1.0\"?><ListBucketResult xmlns=\"").append(S3_NS).append("\">");
            while (sb.length() < 8 * 1024 * 1024 - 100) {
                sb.append("<a/>");
            }
            sb.append("</ListBucketResult>");
            byte[] xml = sb.toString().getBytes(StandardCharsets.UTF_8);
            sb = null;
            try {
                S3Client.Node n = S3Client.parseXml(xml, "bomb");
                System.out.println("s3-bomb: handled (parsed " + n.children.size() + " children)");
            } catch (IOException e) {
                System.out.println("s3-bomb: handled (refused: " + e.getMessage() + ")");
            }
        } catch (Throwable t) {
            System.out.println("s3-bomb: " + t);
        }
        try {
            StringBuilder sb = new StringBuilder(32 * 1024 * 1024 + 200);
            sb.append("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">");
            int i = 0;
            while (sb.length() < 32 * 1024 * 1024 - 200) {
                sb.append("<d:response><d:href>/o/").append(i++).append("</d:href></d:response>");
            }
            sb.append("</d:multistatus>");
            byte[] xml = sb.toString().getBytes(StandardCharsets.UTF_8);
            sb = null;
            try {
                List<WebDavClient.Resource> rs = WebDavClient.parseMultistatus(xml, HttpUrl.get("http://h/o/"));
                System.out.println("dav-bomb: handled (parsed " + rs.size() + " resources)");
            } catch (IOException e) {
                System.out.println("dav-bomb: handled (refused: " + e.getMessage() + ")");
            }
        } catch (Throwable t) {
            System.out.println("dav-bomb: " + t);
        }
    }

    // ---- the sweep ----

    private static void sweep() {
        String basic = Credentials.basic(DAV_USER, DAV_PASSWORD, StandardCharsets.UTF_8);
        String[] needles = {S3_SECRET, DAV_PASSWORD, basic, basic.substring(6), "PWINURL"};
        Pattern signature = Pattern.compile("Signature=[0-9a-f]{64}|X-Amz-Signature|AWS4-HMAC-SHA256 Credential=");
        List<String> haystack = new ArrayList<>();
        synchronized (captured) {
            haystack.add(captured.toString());
        }
        haystack.addAll(met);
        haystack.addAll(Log.lines);
        haystack.add(new S3Config("https://s3.fake", "us-east-1", "backups", "", S3_KEY_ID, S3_SECRET, true,
                S3Config.Addressing.AUTO, null).toString());
        haystack.add(new WebDavConfig("https://nas.fake/dav/", DAV_USER, DAV_PASSWORD, null).toString());
        List<String> hits = new ArrayList<>();
        for (String text : haystack) {
            for (String needle : needles) {
                if (text.contains(needle)) {
                    hits.add(needle.substring(0, Math.min(6, needle.length())) + "…");
                }
            }
            if (signature.matcher(text).find()) {
                hits.add("a signature or credential header");
            }
        }
        check("sweep: no secret key, password, Basic credential or signature in any printed line, exception "
                + "chain, Log call or config toString of this run (" + haystack.size() + " texts, "
                + met.size() + " exception chains)", hits.isEmpty(), hits.toString());
        check("sweep: the capturing Log stub saw no line at all from the classes under test (none log)",
                Log.lines.isEmpty(), Log.lines.toString());
    }
}

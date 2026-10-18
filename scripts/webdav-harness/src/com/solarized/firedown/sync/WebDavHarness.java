package com.solarized.firedown.sync;

import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.model.VaultEntry;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Drives the REAL WebDAV backup code (WebDavClient / WebDavStore /
 * WebDavCheck) and the REAL VaultEngine + VaultObjectReader on top of it,
 * against {@link FakeDav} for every failure the client must classify, and
 * against wsgidav (a real WebDAV server) when one is installed.
 */
public final class WebDavHarness {

    private static int passed;
    private static final List<String> failed = new ArrayList<>();
    private static File work;
    private static final OkHttpClient BASE =
            new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build();
    private static final String DAV = "/remote.php/dav/files/alex";

    public static void main(String[] args) throws Exception {
        work = new File(args[0]);
        String keystoreA = args[1];
        String keystoreB = args[2];
        String davServerSource = args[3];
        String wsgidav = args.length > 4 ? args[4] : "";

        run("units", WebDavHarness::units);
        run("check", WebDavHarness::checks);
        run("store", WebDavHarness::store);
        run("failures", WebDavHarness::failures);
        run("tls", () -> tls(keystoreA, keystoreB));
        run("davserver", () -> davServer(davServerSource));
        String litmus = System.getenv("LITMUS");
        if (litmus != null && !litmus.isEmpty()) {
            run("litmus", () -> litmus(litmus, davServerSource));
        } else {
            System.out.println("-- litmus: not installed, the server's conformance run skipped "
                    + "(apt install litmus, or set LITMUS)");
        }
        if (!wsgidav.isEmpty()) {
            run("wsgidav", () -> wsgidav(wsgidav));
        } else {
            System.out.println("-- wsgidav: not installed, real-server suite skipped "
                    + "(pip install wsgidav cheroot, or set WSGIDAV)");
        }

        System.out.println();
        System.out.println(passed + " passed, " + failed.size() + " failed");
        for (String f : failed) {
            System.out.println("  FAIL " + f);
        }
        System.exit(failed.isEmpty() ? 0 : 1);
    }

    interface Case {
        void run() throws Exception;
    }

    /** A case that throws is a failed case, never an aborted suite. */
    private static void run(String name, Case c) {
        System.out.println("-- " + name);
        try {
            c.run();
        } catch (Throwable t) {
            failed.add(name + ": threw " + t);
            t.printStackTrace(System.out);
        }
    }

    private static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed.add(name + (detail.isEmpty() ? "" : " (" + detail + ")"));
            System.out.println("  FAIL " + name + (detail.isEmpty() ? "" : " (" + detail + ")"));
        }
    }

    // ---- servers ----

    private static final class Server implements AutoCloseable {
        final FakeDav dav = new FakeDav();
        final HttpServer http;

        Server(SSLContext tls) throws IOException {
            if (tls == null) {
                http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                dav.scheme = "http";
            } else {
                HttpsServer s = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                s.setHttpsConfigurator(new HttpsConfigurator(tls));
                http = s;
                dav.scheme = "https";
            }
            http.createContext("/", dav);
            http.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "fakedav");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            dav.port = http.getAddress().getPort();
        }

        String url(String path) {
            return dav.scheme + "://127.0.0.1:" + dav.port + path;
        }

        @Override
        public void close() {
            http.stop(0);
        }
    }

    /** A Nextcloud-shaped server: the site root is not WebDAV, the user's
     *  files collection is. */
    private static Server nextcloud() throws IOException {
        Server s = new Server(null);
        s.dav.propfindRefused.add("/");
        s.dav.mkdirs(DAV);
        return s;
    }

    private static WebDavConfig config(Server s) {
        return new WebDavConfig(s.url(DAV + "/"), "alex", "secret", null);
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

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return toHex(md.digest());
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static String objectsPath(SyncIdentity id) {
        return DAV + "/Firedown Backup/" + id.accountBase32() + "/objects";
    }

    // ---- units ----

    private static void units() throws Exception {
        check("normalize: bare host gets https and a slash",
                "https://cloud.example.com/".equals(String.valueOf(WebDavCheck.normalize("cloud.example.com"))));
        check("normalize: davs:// maps to https, keeps port and path",
                "https://nas:5006/backups/".equals(String.valueOf(WebDavCheck.normalize(" davs://nas:5006/backups "))));
        check("normalize: dav:// maps to http",
                "http://nas/x/".equals(String.valueOf(WebDavCheck.normalize("dav://nas/x"))));
        check("normalize: query and fragment dropped",
                "http://x/y/".equals(String.valueOf(WebDavCheck.normalize("http://x/y?z=1#f"))));
        check("normalize: empty is no address", WebDavCheck.normalize("  ") == null);
        check("normalize: garbage is no address", WebDavCheck.normalize("ftp://x") == null);

        HttpUrl root = WebDavCheck.normalize("cloud.example.com/nc");
        List<HttpUrl> c = WebDavCheck.candidates(root, "a b");
        check("candidates: typed, then the two Nextcloud endpoints under it", c.size() == 3
                && c.get(1).toString().equals("https://cloud.example.com/nc/remote.php/dav/files/a%20b/")
                && c.get(2).toString().equals("https://cloud.example.com/nc/remote.php/webdav/"),
                String.valueOf(c));
        check("candidates: a remote.php address is tried alone",
                WebDavCheck.candidates(WebDavCheck.normalize("h/remote.php/webdav"), "a").size() == 1);

        String xml = "<?xml version=\"1.0\"?><D:multistatus xmlns:D=\"DAV:\">"
                + "<D:response><D:href>/dav/Firedown%20Backup/</D:href>"
                + "<D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype>"
                + "<D:getlastmodified>Tue, 13 Oct 2026 10:00:00 GMT</D:getlastmodified></D:prop>"
                + "<D:status>HTTP/1.1 200 OK</D:status></D:propstat>"
                + "<D:propstat><D:prop><D:getcontentlength/></D:prop>"
                + "<D:status>HTTP/1.1 404 Not Found</D:status></D:propstat></D:response>"
                + "<D:response><D:href>http://h/dav/Firedown%20Backup/a.entry</D:href>"
                + "<D:propstat><D:prop><D:resourcetype/><D:getcontentlength>42</D:getcontentlength>"
                + "<D:getetag>\"e1\"</D:getetag></D:prop></D:propstat></D:response>"
                + "</D:multistatus>";
        List<WebDavClient.Resource> rs = WebDavClient.parseMultistatus(
                xml.getBytes(StandardCharsets.UTF_8), HttpUrl.get("http://h/dav/Firedown%20Backup/"));
        check("multistatus: two resources", rs.size() == 2, String.valueOf(rs.size()));
        WebDavClient.Resource dir = rs.get(0);
        check("multistatus: collection, decoded name, date",
                dir.collection && dir.name.equals("Firedown Backup")
                        && dir.modified == 1791885600000L, dir.name + " " + dir.modified);
        WebDavClient.Resource file = rs.get(1);
        check("multistatus: absolute href, size, etag, no status = ok",
                !file.collection && file.size == 42 && "\"e1\"".equals(file.etag)
                        && file.name.equals("a.entry"));

        boolean refused = false;
        try {
            WebDavClient.parseMultistatus(("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY a \"b\">]>"
                    + "<d:multistatus xmlns:d=\"DAV:\"/>").getBytes(StandardCharsets.UTF_8),
                    HttpUrl.get("http://h/"));
        } catch (IOException e) {
            refused = true;
        }
        check("multistatus: a DOCTYPE is refused", refused);

        String id = "0123456789abcdef0123456789abcdef";
        check("objectIdOf: plain", id.equals(BackupLayout.objectIdOf(id + ".entry", ".entry")));
        check("objectIdOf: conflict copy matches by prefix",
                id.equals(BackupLayout.objectIdOf(id + ".entry (1)", ".entry")));
        check("objectIdOf: not hex", BackupLayout.objectIdOf("Z" + id.substring(1) + ".fdvc", ".fdvc") == null);
        check("objectLength: every chunk carries 34 bytes",
                VaultEngine.objectLength(20L << 20, 3) == (20L << 20) + 102);
    }

    // ---- check ----

    private static void checks() throws Exception {
        try (Server s = nextcloud()) {
            WebDavCheck.Result r = WebDavCheck.run(BASE, s.url("/"), "alex", "secret", null);
            check("check: a Nextcloud root finds remote.php/dav/files/<user>/",
                    r.kind == WebDavCheck.Kind.OK && r.config != null
                            && r.config.url.equals(s.url(DAV + "/")), r.kind + " " + (r.config == null ? "" : r.config.url));
            check("check: made Firedown Backup, left no probe",
                    s.dav.get(DAV + "/Firedown Backup") != null
                            && s.dav.childrenOf(DAV + "/Firedown Backup").isEmpty());
            check("check: the sign-in rode the first request",
                    s.dav.log.get(0).endsWith("\tauth"), s.dav.log.get(0));
            check("check: no Expect: 100-continue", !s.dav.expectHeaders.contains("100-continue"));
        }
        try (Server s = nextcloud()) {
            WebDavCheck.Result r = WebDavCheck.run(BASE, s.url("/"), "alex", "wrong", null);
            check("check: wrong password is AUTH_FAILED", r.kind == WebDavCheck.Kind.AUTH_FAILED, String.valueOf(r.kind));
        }
        try (Server s = new Server(null)) {
            s.dav.propfindRefused.add("/");
            WebDavCheck.Result r = WebDavCheck.run(BASE, s.url("/files"), "alex", "secret", null);
            check("check: nothing WebDAV anywhere is NOT_WEBDAV", r.kind == WebDavCheck.Kind.NOT_WEBDAV, String.valueOf(r.kind));
        }
        int closed;
        try (ServerSocket ss = new ServerSocket(0)) {
            closed = ss.getLocalPort();
        }
        WebDavCheck.Result r = WebDavCheck.run(BASE, "http://127.0.0.1:" + closed + "/", "alex", "secret", null);
        check("check: nobody listening is UNREACHABLE", r.kind == WebDavCheck.Kind.UNREACHABLE, String.valueOf(r.kind));
        check("check: not an address is BAD_ADDRESS",
                WebDavCheck.run(BASE, "  ", "a", "b", null).kind == WebDavCheck.Kind.BAD_ADDRESS);
        try (Server s = nextcloud()) {
            s.dav.readOnly = true;
            WebDavCheck.Result ro = WebDavCheck.run(BASE, s.url("/"), "alex", "secret", null);
            check("check: a folder it cannot write is READ_ONLY", ro.kind == WebDavCheck.Kind.READ_ONLY, String.valueOf(ro.kind));
        }
        try (Server s = nextcloud()) {
            s.dav.full = true;
            WebDavCheck.Result full = WebDavCheck.run(BASE, s.url("/"), "alex", "secret", null);
            check("check: a full server is FULL", full.kind == WebDavCheck.Kind.FULL, String.valueOf(full.kind));
            check("check: the folder the failed check made is removed again",
                    s.dav.get(DAV + "/Firedown Backup") == null);
        }
        try (Server s = new Server(null)) {
            s.dav.propfindRefused.add("/");
            s.dav.mkdirs("/remote.php/webdav");
            s.dav.user = "alex@example.com";
            WebDavCheck.Result legacy = WebDavCheck.run(BASE, s.url("/"), "alex@example.com", "secret", null);
            check("check: an email sign-in lands on the legacy remote.php/webdav/",
                    legacy.kind == WebDavCheck.Kind.OK && legacy.config.url.equals(s.url("/remote.php/webdav/")),
                    legacy.kind + "");
        }
        try (Server s = new Server(null)) {
            s.dav.mkdirs("/dav-new");
            s.dav.redirects.put("/dav/", s.url("/dav-new/"));
            WebDavCheck.Result moved = WebDavCheck.run(BASE, s.url("/dav"), "alex", "secret", null);
            check("check: the SAVED address is the one that answered after a redirect",
                    moved.kind == WebDavCheck.Kind.OK && moved.config.url.equals(s.url("/dav-new/")),
                    moved.kind + " " + (moved.config == null ? "" : moved.config.url));
        }
        try (Server s = new Server(null)) {
            s.dav.mkdirs("/dav");
            s.dav.absoluteHrefs = true;
            WebDavCheck.Result abs = WebDavCheck.run(BASE, s.url("/dav"), "alex", "secret", null);
            check("check: a server answering absolute hrefs", abs.kind == WebDavCheck.Kind.OK, String.valueOf(abs.kind));
        }
        // A reconnect from the sign-in page: an empty password means the saved
        // one, but only for the server and user it was saved for.
        try (Server s = nextcloud(); Server other = nextcloud()) {
            WebDavConfig saved = new WebDavConfig(s.url(DAV + "/"), "alex", "secret", null);
            WebDavCheck.Result kept = WebDavCheck.runReusing(BASE, saved, s.url("/"), "alex", null, null);
            check("reuse: an empty password on the saved server signs in with the saved one",
                    kept.kind == WebDavCheck.Kind.OK && kept.config != null
                            && "secret".equals(kept.config.password), String.valueOf(kept.kind));
            WebDavCheck.Result typed = WebDavCheck.runReusing(BASE, saved, s.url("/"), "alex", "wrong", null);
            check("reuse: a typed password wins over the saved one",
                    typed.kind == WebDavCheck.Kind.AUTH_FAILED, String.valueOf(typed.kind));
            int before = s.dav.log.size();
            WebDavCheck.Result user = WebDavCheck.runReusing(BASE, saved, s.url("/"), "sam", null, null);
            check("reuse: another user does not get the saved password",
                    user.kind == WebDavCheck.Kind.AUTH_FAILED && s.dav.log.size() == before,
                    user.kind + " " + (s.dav.log.size() - before));
            WebDavCheck.Result elsewhere = WebDavCheck.runReusing(BASE, saved, other.url("/"), "alex", null, null);
            check("reuse: the saved password is never sent to another server",
                    elsewhere.kind == WebDavCheck.Kind.AUTH_FAILED && other.dav.log.isEmpty(),
                    elsewhere.kind + " " + other.dav.log);
            check("reuse: no saved sign-in, no password, is AUTH_FAILED",
                    WebDavCheck.runReusing(BASE, null, s.url("/"), "alex", null, null).kind
                            == WebDavCheck.Kind.AUTH_FAILED);
        }
    }

    // ---- store ----

    private static void store() throws Exception {
        try (Server s = nextcloud()) {
            WebDavStore store = new WebDavStore(config(s), BASE);
            SyncIdentity id = identity(7);
            VaultEngine engine = new VaultEngine(store, id);
            check("store: reachable", store.isReachable());
            check("store: label is the host and port", store.providerLabel().equals("127.0.0.1:" + s.dav.port));
            check("store: empty before anything is backed up", engine.loadManifest().isEmpty());

            int size = 20 * 1024 * 1024 + 123;
            File src = randomFile("clip.mp4", size, 1);
            boolean[] early = {false};
            VaultEntry e = engine.backupFile(src, "video/mp4", null, (done, total) -> {
                if (done > 0 && done < total) {
                    for (String c : s.dav.childrenOf(objectsPath(id))) {
                        early[0] |= c.endsWith(".entry");
                    }
                }
            }, "https://example.com/v", 1000L);
            check("store: no sidecar exists while the object uploads (the commit marker is last)",
                    !early[0]);
            FakeDav.Node object = s.dav.get(objectsPath(id) + "/" + e.objectId + ".fdvc");
            FakeDav.Node sidecar = s.dav.get(objectsPath(id) + "/" + e.objectId + ".entry");
            check("store: the object is size + 34 per chunk under the shared layout",
                    object != null && object.data.length == VaultEngine.objectLength(size, 3),
                    object == null ? "missing" : String.valueOf(object.data.length));
            check("store: the sidecar (commit marker) is there", sidecar != null);
            check("store: one PUT for the object, streamed", s.dav.count("PUT", ".fdvc") == 1);

            List<VaultEntry> list = engine.loadManifest();
            check("store: listing returns the entry", list.size() == 1 && list.get(0).name.equals("clip.mp4")
                    && list.get(0).size == size && list.get(0).chunkCount == 3);

            File back = new File(work, "restored.mp4");
            engine.restoreFile(list.get(0), back, null);
            check("store: restore is byte-identical", sha256(back).equals(sha256(src)));

            byte[] original = Files.readAllBytes(src.toPath());
            check("store: ranged reads match the plaintext across chunk boundaries",
                    rangesMatch(store, id, list.get(0), original));
            s.dav.ignoreRange = true;
            check("store: a server that ignores Range still reads right",
                    rangesMatch(store, id, list.get(0), original));
            s.dav.ignoreRange = false;
            s.dav.wrongContentRange = true;
            boolean refused = false;
            try (ReaderHandle r = new ReaderHandle(store, id, list.get(0))) {
                r.reader.readAt(5, new byte[10], 0, 10);
            } catch (IOException ex) {
                refused = ex.getMessage() != null && ex.getMessage().contains("asked for");
            }
            check("store: a 206 for another range is refused", refused);
            s.dav.wrongContentRange = false;

            int puts = s.dav.count("PUT", ".fdvc");
            engine.backupFile(src, "video/mp4", null, null, null, 0);
            check("store: same name + size is not uploaded again", s.dav.count("PUT", ".fdvc") == puts);

            File second = randomFile("song.mp3", 3000, 2);
            VaultEntry e2 = engine.backupFile(second, "audio/mpeg", "dGh1bWI", null, null, 0);
            int getsBefore = s.dav.count("GET", ".entry");
            engine.loadManifest();
            int afterFirst = s.dav.count("GET", ".entry");
            engine.loadManifest();
            int afterSecond = s.dav.count("GET", ".entry");
            check("store: a listing reads each new sidecar once", afterFirst - getsBefore >= 1);
            check("store: an unchanged sidecar is not read again", afterSecond == afterFirst,
                    afterFirst + " -> " + afterSecond);
            FakeDav.Node sc = s.dav.get(objectsPath(id) + "/" + e2.objectId + ".entry");
            sc.version += 1; // another phone rewrote it: the ETag moves
            engine.loadManifest();
            check("store: a sidecar rewritten elsewhere is read again",
                    s.dav.count("GET", ".entry") == afterSecond + 1);

            // the orphan sweep
            String objs = objectsPath(id);
            String oldOrphan = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
            String youngOrphan = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
            String lonely = "cccccccccccccccccccccccccccccccc";
            s.dav.putFile(objs + "/" + oldOrphan + ".fdvc", new byte[100],
                    FakeDav.now() - TimeUnit.DAYS.toMillis(2));
            s.dav.putFile(objs + "/" + youngOrphan + ".fdvc", new byte[100], FakeDav.now());
            s.dav.putFile(objs + "/" + lonely + ".entry", new byte[100], FakeDav.now());
            List<VaultEntry> swept = engine.loadManifest();
            check("store: an object without a sidecar older than a day is swept",
                    s.dav.get(objs + "/" + oldOrphan + ".fdvc") == null);
            check("store: a young one (an upload in flight) is kept",
                    s.dav.get(objs + "/" + youngOrphan + ".fdvc") != null);
            check("store: a sidecar without its object is neither listed nor deleted",
                    s.dav.get(objs + "/" + lonely + ".entry") != null && swept.size() == 2);

            // another code beside us, untouched by our delete-all
            SyncIdentity other = identity(9);
            VaultEngine otherEngine = new VaultEngine(store, other);
            otherEngine.backupFile(second, "audio/mpeg", null, null, null, 0);

            engine.deleteEntry(e2);
            check("store: delete removes sidecar and object",
                    s.dav.get(objs + "/" + e2.objectId + ".entry") == null
                            && s.dav.get(objs + "/" + e2.objectId + ".fdvc") == null);
            engine.deleteAll();
            check("store: delete-all removes this account's directory",
                    s.dav.get(DAV + "/Firedown Backup/" + id.accountBase32()) == null);
            check("store: another code's backups beside it are untouched",
                    otherEngine.loadManifest().size() == 1);
            check("store: after delete-all the next backup makes the folders again",
                    engine.backupFile(second, "audio/mpeg", null, null, null, 0) != null
                            && engine.loadManifest().size() == 1);
        }
    }

    /** A VaultObjectReader for try-with-resources. */
    private static final class ReaderHandle implements AutoCloseable {
        final VaultObjectReader reader;

        ReaderHandle(BackupStore store, SyncIdentity id, VaultEntry e) throws Exception {
            reader = new VaultObjectReader(store, id, e);
        }

        @Override
        public void close() {
            reader.close();
        }
    }

    private static boolean rangesMatch(BackupStore store, SyncIdentity id, VaultEntry e, byte[] original)
            throws Exception {
        VaultObjectReader reader = new VaultObjectReader(store, id, e);
        try {
            long[] positions = {0, 1, VaultEngine.CHUNK_SIZE - 10, VaultEngine.CHUNK_SIZE,
                    2L * VaultEngine.CHUNK_SIZE + 5, original.length - 7};
            for (long pos : positions) {
                byte[] buf = new byte[64];
                int n = reader.readAt(pos, buf, 0, buf.length);
                int want = (int) Math.min(64, Math.min(original.length - pos,
                        VaultEngine.CHUNK_SIZE - (pos % VaultEngine.CHUNK_SIZE)));
                if (n != want) {
                    System.out.println("    readAt(" + pos + ") gave " + n + ", want " + want);
                    return false;
                }
                for (int i = 0; i < n; i++) {
                    if (buf[i] != original[(int) pos + i]) {
                        System.out.println("    byte mismatch at " + (pos + i));
                        return false;
                    }
                }
            }
            return reader.readAt(original.length, new byte[4], 0, 4) == -1;
        } finally {
            reader.close();
        }
    }

    // ---- failures ----

    private static void failures() throws Exception {
        try (Server s = nextcloud()) {
            WebDavStore store = new WebDavStore(config(s), BASE);
            SyncIdentity id = identity(3);
            VaultEngine engine = new VaultEngine(store, id);
            File src = randomFile("big.bin", 12 * 1024 * 1024, 3);
            String objs = objectsPath(id);

            s.dav.dropPutAfter = 5 * 1024 * 1024;
            IOException dropped = expectIo(() -> engine.backupFile(src, "application/octet-stream", null, null, null, 0));
            s.dav.dropPutAfter = -1;
            check("fail: a dropped upload is a retryable IOException",
                    dropped != null && !(dropped instanceof BackupStore.UnreachableException), String.valueOf(dropped));
            check("fail: a dropped upload leaves nothing on the server", s.dav.childrenOf(objs).isEmpty(),
                    String.valueOf(s.dav.childrenOf(objs)));

            VaultEngine[] holder = new VaultEngine[1];
            holder[0] = new VaultEngine(store, id);
            IOException cancelled = expectIo(() -> holder[0].backupFile(src, "application/octet-stream", null,
                    (done, total) -> {
                        if (done > 0) {
                            holder[0].cancel();
                        }
                    }, null, 0));
            check("fail: a cancel mid-upload ends the backup",
                    cancelled != null && "cancelled".equals(cancelled.getMessage()), String.valueOf(cancelled));
            check("fail: a cancelled upload stores nothing", s.dav.childrenOf(objs).isEmpty());

            byte[] shorter = new byte[9 * 1024 * 1024];
            IOException shrunk = expectIo(() -> engine.backupStream("shrunk.bin", 10L * 1024 * 1024,
                    () -> new ByteArrayInputStream(shorter), "application/octet-stream", null, null, null, 0));
            check("fail: a file shorter than declared stores nothing",
                    shrunk != null && s.dav.childrenOf(objs).isEmpty(), String.valueOf(shrunk));

            s.dav.full = true;
            IOException full = expectIo(() -> engine.backupFile(src, "application/octet-stream", null, null, null, 0));
            s.dav.full = false;
            check("fail: a full server is FullException (its own words, not a refusal)",
                    full instanceof BackupStore.FullException
                            && full.getMessage().contains("out of space"), String.valueOf(full));

            s.dav.password = "changed";
            IOException auth = expectIo(engine::loadManifest);
            check("fail: a password changed on the server is UNREACHABLE (needs the user)",
                    auth instanceof BackupStore.UnreachableException, String.valueOf(auth));
            IOException authUpload = expectIo(() -> engine.backupFile(src, "application/octet-stream", null, null, null, 0));
            check("fail: ... for an upload too", authUpload instanceof BackupStore.UnreachableException,
                    String.valueOf(authUpload));
            check("store: isReachable is false with a refused sign-in", !store.isReachable());
            s.dav.password = "secret";

            s.dav.forcePropfindStatus = 500;
            IOException fiveHundred = expectIo(engine::loadManifest);
            s.dav.forcePropfindStatus = 0;
            check("fail: a 500 is retryable, not UNREACHABLE",
                    fiveHundred != null && !(fiveHundred instanceof BackupStore.UnreachableException));

            VaultEntry ok = engine.backupFile(randomFile("x.bin", 100, 4), "application/octet-stream", null, null, null, 0);
            s.dav.nodes.remove(objs + "/" + ok.objectId + ".fdvc");
            IOException missing = expectIo(() -> {
                engine.restoreFile(ok, new File(work, "x.out"), null);
                return null;
            });
            check("fail: a restore of an object gone from the server is FileNotFound",
                    missing instanceof FileNotFoundException, String.valueOf(missing));
            IOException missingRanges = expectIo(() -> new VaultObjectReader(store, id, ok));
            check("fail: so is opening it for streaming",
                    missingRanges instanceof FileNotFoundException, String.valueOf(missingRanges));

            synchronized (s.dav.nodes) {
                s.dav.nodes.keySet().removeIf(k -> k.startsWith(DAV));
            }
            IOException gone = expectIo(engine::loadManifest);
            check("fail: the collection gone from the server is UNREACHABLE",
                    gone instanceof BackupStore.UnreachableException, String.valueOf(gone));
            check("store: isReachable is false when the collection is gone", !store.isReachable());
        }
    }

    interface IoCall {
        Object call() throws Exception;
    }

    private static IOException expectIo(IoCall c) {
        try {
            c.call();
            return null;
        } catch (IOException e) {
            return e;
        } catch (Exception e) {
            return new IOException("unexpected " + e, e);
        }
    }

    // ---- tls ----

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
        X509Certificate cert = (X509Certificate) ks.getCertificate("dav");
        return toHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
    }

    private static void tls(String keystoreA, String keystoreB) throws Exception {
        String pinA = certSha256(keystoreA);
        try (Server s = new Server(serverTls(keystoreA))) {
            s.dav.mkdirs("/dav");
            WebDavCheck.Result first = WebDavCheck.run(BASE, s.url("/dav"), "alex", "secret", null);
            check("tls: a self-made certificate is UNTRUSTED_CERT with its fingerprint",
                    first.kind == WebDavCheck.Kind.UNTRUSTED_CERT && first.cert != null
                            && first.cert.sha256.equals(pinA), first.kind + "");
            check("tls: the subject is shown", first.cert != null && first.cert.subject.contains("CN=nas.local"));
            check("tls: the fingerprint reads like a server shows it",
                    first.cert != null && first.cert.fingerprintDisplay().matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"));
            check("tls: nothing was sent before the certificate was refused",
                    s.dav.log.isEmpty(), String.valueOf(s.dav.log));

            WebDavCheck.Result pinned = WebDavCheck.run(BASE, s.url("/dav"), "alex", "secret", pinA.toUpperCase());
            check("tls: trusted by its fingerprint (and its name, which is not 127.0.0.1)",
                    pinned.kind == WebDavCheck.Kind.OK && pinned.config != null
                            && pinA.equals(pinned.config.pinSha256), pinned.kind + "");

            WebDavCheck.Result again = WebDavCheck.runReusing(BASE, pinned.config, s.url("/dav"),
                    "alex", null, null);
            check("tls: a reconnect keeps the certificate already trusted (and the password)",
                    again.kind == WebDavCheck.Kind.OK && again.config != null
                            && pinA.equals(again.config.pinSha256), again.kind + "");

            WebDavStore store = new WebDavStore(pinned.config, BASE);
            VaultEngine engine = new VaultEngine(store, identity(5));
            engine.backupFile(randomFile("tls.bin", 1000, 5), "application/octet-stream", null, null, null, 0);
            check("tls: backups work through the pin", engine.loadManifest().size() == 1);
        }
        try (Server s = new Server(serverTls(keystoreB))) {
            s.dav.mkdirs("/dav");
            WebDavStore store = new WebDavStore(
                    new WebDavConfig(s.url("/dav/"), "alex", "secret", pinA), BASE);
            IOException changed = expectIo(() -> new VaultEngine(store, identity(5)).loadManifest());
            check("tls: a certificate that changed is UNREACHABLE (needs the user)",
                    changed instanceof BackupStore.UnreachableException, String.valueOf(changed));
            check("tls: and the store reads as unreachable", !store.isReachable());
        }
    }

    // ---- real servers ----

    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static void wsgidav(String exe) throws Exception {
        File root = new File(work, "wsgidav-root");
        File dav = new File(root, "alex");
        if (!dav.mkdirs()) {
            throw new IOException("mkdir " + dav);
        }
        int port = freePort();
        File cfg = new File(work, "wsgidav.yaml");
        String yaml = "host: 127.0.0.1\n"
                + "port: " + port + "\n"
                + "provider_mapping:\n"
                + "  \"/dav\": \"" + root.getAbsolutePath() + "\"\n"
                + "http_authenticator:\n"
                + "  domain_controller: null\n"
                + "  accept_basic: true\n"
                + "  accept_digest: false\n"
                + "  default_to_digest: false\n"
                + "simple_dc:\n"
                + "  user_mapping:\n"
                + "    \"*\":\n"
                + "      \"alex\":\n"
                + "        password: \"secret\"\n"
                + "verbose: 1\n";
        Files.write(cfg.toPath(), yaml.getBytes(StandardCharsets.UTF_8));
        Process p = new ProcessBuilder(exe, "--config=" + cfg.getAbsolutePath())
                .redirectErrorStream(true)
                .redirectOutput(new File(work, "wsgidav.log"))
                .start();
        try {
            waitForPort(port);
            roundTrip("wsgidav", "http://127.0.0.1:" + port + "/dav/alex", null, dav);
        } finally {
            p.destroy();
            p.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /**
     * The whole life of a backup against a REAL server whose files are on
     * this disk at {@code dir}: the check (a wrong password first), backup,
     * the bytes on disk at the layout's path, list, restore, streaming reads,
     * dedup, delete, delete-all.
     */
    private static WebDavStore roundTrip(String label, String base, String pin, File dir)
            throws Exception {
        WebDavCheck.Result bad = WebDavCheck.run(BASE, base, "alex", "nope", pin);
        check(label + ": wrong password is AUTH_FAILED", bad.kind == WebDavCheck.Kind.AUTH_FAILED, bad.kind + "");
        WebDavCheck.Result r = WebDavCheck.run(BASE, base, "alex", "secret", pin);
        check(label + ": the check passes", r.kind == WebDavCheck.Kind.OK, r.kind + "");
        check(label + ": the check made Firedown Backup on disk",
                new File(dir, "Firedown Backup").isDirectory());
        WebDavStore store = new WebDavStore(r.config, BASE);
        SyncIdentity id = identity(11);
        VaultEngine engine = new VaultEngine(store, id);
        int size = 17 * 1024 * 1024 + 5;
        File src = randomFile(label + "-real.mp4", size, 11);
        VaultEntry e = engine.backupFile(src, "video/mp4", null, null, null, 0);
        File objects = new File(dir, "Firedown Backup/" + id.accountBase32() + "/objects");
        File onDisk = new File(objects, e.objectId + ".fdvc");
        check(label + ": the object is on disk at the shared layout's path, exact length",
                onDisk.isFile() && onDisk.length() == VaultEngine.objectLength(size, 3),
                onDisk + " " + onDisk.length());
        check(label + ": the sidecar is on disk", new File(objects, e.objectId + ".entry").isFile());
        List<VaultEntry> list = engine.loadManifest();
        check(label + ": listed", list.size() == 1 && list.get(0).size == size);
        File back = new File(work, label + "-real.out");
        engine.restoreFile(list.get(0), back, null);
        check(label + ": restore is byte-identical", sha256(back).equals(sha256(src)));
        check(label + ": ranged reads match",
                rangesMatch(store, id, list.get(0), Files.readAllBytes(src.toPath())));
        VaultEntry again = engine.backupFile(src, "video/mp4", null, null, null, 0);
        check(label + ": a second backup of the same file is the same entry",
                again.objectId.equals(e.objectId));
        File emptySrc = new File(work, label + "-empty.txt");
        Files.write(emptySrc.toPath(), new byte[0]);
        VaultEntry empty = engine.backupFile(emptySrc, "text/plain", null, null, null, 0);
        File emptyBack = new File(work, label + "-empty.out");
        engine.restoreFile(empty, emptyBack, null);
        check(label + ": an empty file backs up and restores", emptyBack.isFile() && emptyBack.length() == 0
                && new File(objects, empty.objectId + ".fdvc").length() == VaultEngine.CHUNK_OVERHEAD);
        VaultEntry small = engine.backupFile(randomFile(label + "-small.txt", 10, 12), "text/plain", null, null, null, 0);
        engine.deleteEntry(small);
        check(label + ": delete removes both files",
                !new File(objects, small.objectId + ".fdvc").exists()
                        && !new File(objects, small.objectId + ".entry").exists());
        check(label + ": reachable", store.isReachable());
        engine.deleteAll();
        check(label + ": delete-all removes the account directory",
                !new File(dir, "Firedown Backup/" + id.accountBase32()).exists());
        return store;
    }

    // ---- DavServer (scripts/webdav-server), the server a phone is pointed at ----

    private static final class DavServerProcess implements AutoCloseable {
        final Process process;
        final int port;
        final String fingerprint;
        final File log;

        DavServerProcess(Process process, int port, String fingerprint, File log) {
            this.process = process;
            this.port = port;
            this.fingerprint = fingerprint;
            this.log = log;
        }

        @Override
        public void close() throws InterruptedException {
            process.destroy();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static int davServers;

    /** Starts DavServer.java from source the way a person would, on an
     *  ephemeral port, and waits for its LISTENING line. */
    private static DavServerProcess startDavServer(String source, File root, String... extra)
            throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(System.getProperty("java.home"), "bin/java").getPath());
        cmd.add(source);
        cmd.add("--root");
        cmd.add(root.getPath());
        cmd.add("--host");
        cmd.add("127.0.0.1");
        cmd.add("--port");
        cmd.add("0");
        cmd.addAll(Arrays.asList(extra));
        File log = new File(work, "davserver-" + (++davServers) + ".log");
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log);
        pb.environment().put("LC_ALL", "C.UTF-8");
        Process p = pb.start();
        long deadline = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < deadline) {
            String out = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
            int at = out.indexOf("LISTENING ");
            if (at >= 0 && out.indexOf('\n', at) > 0) {
                int port = Integer.parseInt(out.substring(at + 10, out.indexOf('\n', at)).trim());
                String fp = null;
                int f = out.indexOf("FINGERPRINT ");
                if (f >= 0) {
                    fp = out.substring(f + 12, out.indexOf('\n', f)).trim();
                }
                return new DavServerProcess(p, port, fp, log);
            }
            if (!p.isAlive()) {
                throw new IOException("DavServer exited: " + out);
            }
            Thread.sleep(100);
        }
        p.destroy();
        throw new IOException("DavServer did not start");
    }

    private static File freshDir(String name) throws IOException {
        File d = new File(work, name);
        if (!d.mkdirs()) {
            throw new IOException("mkdir " + d);
        }
        return d;
    }

    private static boolean hasTempFiles(File dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir.toPath())) {
            return s.anyMatch(p -> p.getFileName().toString().startsWith(".davput-"));
        }
    }

    private static void davServer(String source) throws Exception {
        File root = freshDir("davserver-plain");
        try (DavServerProcess s = startDavServer(source, root)) {
            String base = "http://127.0.0.1:" + s.port;
            WebDavStore store = roundTrip("davserver", base, null, root);

            // A PROPFIND with no body is allprop: every live prop, the way a
            // desktop client asks (Firedown names its props, so only this
            // asks it).
            Files.write(new File(root, "allprop.txt").toPath(), new byte[5]);
            Request allprop = new Request.Builder().url(base + "/")
                    .method("PROPFIND", RequestBody.create(new byte[0], null))
                    .header("Depth", "1")
                    .header("Authorization", Credentials.basic("alex", "secret"))
                    .build();
            try (Response resp = BASE.newCall(allprop).execute()) {
                List<WebDavClient.Resource> rs = WebDavClient.parseMultistatus(
                        resp.body().bytes(), HttpUrl.get(base + "/"));
                WebDavClient.Resource file = null;
                for (WebDavClient.Resource r : rs) {
                    if (r.name.equals("allprop.txt")) {
                        file = r;
                    }
                }
                check("davserver: allprop answers size, date and etag", resp.code() == 207
                        && file != null && file.size == 5 && file.modified > 0 && file.etag != null);
            }

            SyncIdentity id = identity(21);
            VaultEngine engine = new VaultEngine(store, id);
            File big = randomFile("big64.bin", 64 * 1024 * 1024 + 99, 21);
            VaultEntry e = engine.backupFile(big, "application/octet-stream", null, null, null, 0);
            File back = new File(work, "big64.out");
            engine.restoreFile(e, back, null);
            check("davserver: a 64 MB file (9 chunks) round-trips byte-identical",
                    e.chunkCount == 9 && sha256(back).equals(sha256(big)), e.chunkCount + "");

            VaultEngine[] holder = {new VaultEngine(store, id)};
            IOException cancelled = expectIo(() -> holder[0].backupFile(
                    randomFile("cancel.bin", 30 * 1024 * 1024, 22), "application/octet-stream", null,
                    (done, total) -> {
                        if (done > 0) {
                            holder[0].cancel();
                        }
                    }, null, 0));
            Thread.sleep(500); // the server notices the cut connection
            File objects = new File(root, "Firedown Backup/" + id.accountBase32() + "/objects");
            String[] left = objects.list();
            check("davserver: a cancelled upload leaves only the earlier backup on disk",
                    cancelled != null && left != null && left.length == 2, Arrays.toString(left));
            check("davserver: and no half-written temporary file", !hasTempFiles(root));
        }

        File nc = freshDir("davserver-nextcloud");
        try (DavServerProcess s = startDavServer(source, nc, "--layout", "nextcloud")) {
            WebDavCheck.Result r = WebDavCheck.run(BASE, "http://127.0.0.1:" + s.port, "alex", "secret", null);
            check("davserver nextcloud: typing only host:port finds /remote.php/dav/files/alex/",
                    r.kind == WebDavCheck.Kind.OK
                            && r.config.url.equals("http://127.0.0.1:" + s.port + "/remote.php/dav/files/alex/"),
                    r.kind + " " + (r.config == null ? "" : r.config.url));
            check("davserver nextcloud: Firedown Backup made in the user's files",
                    new File(nc, "Firedown Backup").isDirectory());
        }

        File ro = freshDir("davserver-readonly");
        try (DavServerProcess s = startDavServer(source, ro, "--read-only")) {
            WebDavCheck.Result r = WebDavCheck.run(BASE, "http://127.0.0.1:" + s.port, "alex", "secret", null);
            check("davserver read-only: READ_ONLY", r.kind == WebDavCheck.Kind.READ_ONLY, r.kind + "");
        }

        File tiny = freshDir("davserver-tiny");
        try (DavServerProcess s = startDavServer(source, tiny, "--quota", "10")) {
            WebDavCheck.Result r = WebDavCheck.run(BASE, "http://127.0.0.1:" + s.port, "alex", "secret", null);
            check("davserver quota: a full server is FULL at the check", r.kind == WebDavCheck.Kind.FULL, r.kind + "");
        }

        File quota = freshDir("davserver-quota");
        try (DavServerProcess s = startDavServer(source, quota, "--quota", "4000000")) {
            WebDavCheck.Result r = WebDavCheck.run(BASE, "http://127.0.0.1:" + s.port, "alex", "secret", null);
            check("davserver quota: room for the check", r.kind == WebDavCheck.Kind.OK, r.kind + "");
            VaultEngine engine = new VaultEngine(new WebDavStore(r.config, BASE), identity(23));
            IOException full = expectIo(() -> engine.backupFile(randomFile("over.bin", 12 * 1024 * 1024, 23),
                    "application/octet-stream", null, null, null, 0));
            System.out.println("    (a backup past the quota failed with: " + full + ")");
            check("davserver quota: a backup past the quota is FullException, not UNREACHABLE",
                    full instanceof BackupStore.FullException, String.valueOf(full));
            check("davserver quota: and stores nothing", !hasTempFiles(quota)
                    && engine.loadManifest().isEmpty());
        }

        File tls = freshDir("davserver-tls");
        File keystore = new File(work, "davserver-tls.p12");
        try (DavServerProcess s = startDavServer(source, tls, "--tls", "--keystore", keystore.getPath())) {
            String base = "https://127.0.0.1:" + s.port;
            WebDavCheck.Result first = WebDavCheck.run(BASE, base, "alex", "secret", null);
            check("davserver tls: its self-made certificate is UNTRUSTED_CERT",
                    first.kind == WebDavCheck.Kind.UNTRUSTED_CERT, first.kind + "");
            check("davserver tls: the fingerprint the check shows is the one the server printed",
                    first.cert != null && first.cert.sha256.equals(s.fingerprint),
                    (first.cert == null ? "none" : first.cert.sha256) + " vs " + s.fingerprint);
            roundTrip("davserver tls", base, s.fingerprint, tls);
        }
        try (DavServerProcess s = startDavServer(source, tls, "--tls", "--keystore", keystore.getPath())) {
            check("davserver tls: the certificate survives a restart (same keystore, same pin)",
                    s.fingerprint != null && WebDavCheck.run(BASE, "https://127.0.0.1:" + s.port, "alex",
                            "secret", s.fingerprint).kind == WebDavCheck.Kind.OK);
        }
    }

    /** The server against litmus, the WebDAV conformance suite: the
     *  class-1 suites it implements must pass whole. (props is left out:
     *  it needs PROPPATCH, which the server does not do.) */
    private static void litmus(String exe, String source) throws Exception {
        File root = freshDir("davserver-litmus");
        try (DavServerProcess s = startDavServer(source, root)) {
            for (String suite : new String[]{"basic", "copymove", "http"}) {
                ProcessBuilder pb = new ProcessBuilder(exe, "http://127.0.0.1:" + s.port + "/", "alex", "secret")
                        .redirectErrorStream(true)
                        .directory(work);
                pb.environment().put("TESTS", suite);
                Process p = pb.start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                boolean done = p.waitFor(120, TimeUnit.SECONDS);
                String summary = "";
                for (String line : out.split("\n")) {
                    if (line.contains("summary for")) {
                        summary = line.trim();
                    }
                }
                check("litmus " + suite + ": " + summary, done && p.exitValue() == 0
                        && summary.contains(" 0 failed"), out.length() > 600 ? out.substring(out.length() - 600) : out);
            }
        }
    }

    private static void waitForPort(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                return;
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        throw new IOException("wsgidav did not start on " + port);
    }
}

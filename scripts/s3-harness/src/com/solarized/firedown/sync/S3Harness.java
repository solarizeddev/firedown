package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;

import com.solarized.firedown.data.di.NetworkModule;
import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.model.VaultEntry;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Drives the REAL S3 backup code: the signer against AWS's published
 * examples and botocore; the client, the store and the check against
 * {@link FakeS3} (which verifies every signature with its own code) for
 * every failure the client must classify; the stored kind of place and the
 * sealed settings ({@link BackupStores}, {@link S3Settings}) on stub prefs;
 * and the same round trip against moto and a real MinIO when they are
 * available, with the stored objects checked by boto3, an independent
 * client.
 */
public final class S3Harness {

    private static int passed;
    private static final List<String> failed = new ArrayList<>();
    private static File work;
    private static File fixtures;

    static final String AWS_KEY = "AKIAIOSFODNN7EXAMPLE";
    static final String AWS_SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    /** Resolves s3.fake and anything under it to the loopback; the rest as
     *  the system would. */
    static final Dns FAKE_DNS = hostname -> {
        String h = hostname.toLowerCase();
        if (h.equals("s3.fake") || h.endsWith(".s3.fake")) {
            return Collections.singletonList(InetAddress.getLoopbackAddress());
        }
        return Dns.SYSTEM.lookup(hostname);
    };
    static final OkHttpClient BASE = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY).dns(FAKE_DNS).build();

    public static void main(String[] args) throws Exception {
        work = new File(args[0]);
        fixtures = new File(args[1]);
        String keystoreA = args.length > 2 ? args[2] : "";
        String keystoreB = args.length > 3 ? args[3] : "";
        boolean quick = "1".equals(System.getenv("S3_HARNESS_QUICK"));

        run("sigv4: AWS examples", S3Harness::awsExamples);
        run("sigv4: botocore", S3Harness::botocore);
        run("client units", S3Harness::clientUnits);
        run("places", S3Harness::places);
        run("check", S3Harness::checks);
        run("store", S3Harness::store);
        run("failures", S3Harness::failures);
        if (!keystoreA.isEmpty()) {
            run("tls", () -> tls(keystoreA, keystoreB));
        }
        String moto = System.getenv("MOTO");
        if (quick) {
            System.out.println("-- moto / minio: skipped (S3_HARNESS_QUICK)");
        } else {
            if (moto != null && !moto.isEmpty()) {
                run("moto", () -> realServer("moto", startMoto(moto)));
            } else {
                System.out.println("-- moto: not installed, skipped (pip install 'moto[server]', "
                        + "or set MOTO=/path/to/moto_server)");
            }
            String minio = System.getenv("MINIO");
            if (minio != null && !minio.isEmpty()) {
                run("minio", () -> minio(minio));
            } else {
                System.out.println("-- minio: not installed, skipped (set MINIO=/path/to/minio; "
                        + "go install github.com/minio/minio@latest builds one)");
            }
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

    interface IoCall {
        void call() throws Exception;
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
        md.update(Files.readAllBytes(f.toPath()));
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    // ---- sigv4 ----

    private static List<String[]> q(String... nameValues) {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            out.add(new String[]{nameValues[i], nameValues[i + 1]});
        }
        return out;
    }

    /**
     * The five signatures AWS publishes for S3 (Signature Version 4,
     * "Examples: Signature Calculations"): examplebucket in us-east-1 at
     * 20130524T000000Z with the documentation's example keys.
     */
    private static void awsExamples() {
        String date = "20130524T000000Z";
        String host = "examplebucket.s3.amazonaws.com";

        TreeMap<String, String> h = new TreeMap<>();
        h.put("host", host);
        h.put("range", "bytes=0-9");
        h.put("x-amz-content-sha256", S3Signer.EMPTY_SHA256);
        h.put("x-amz-date", date);
        String sig = S3Signer.sign("GET", "/test.txt", q(), h, S3Signer.EMPTY_SHA256, date,
                "us-east-1", AWS_KEY, AWS_SECRET).signature;
        check("GET object with Range", sig.equals(
                "f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"), sig);

        String payload = S3Signer.sha256Hex("Welcome to Amazon S3.".getBytes(StandardCharsets.UTF_8));
        h = new TreeMap<>();
        h.put("host", host);
        h.put("date", "Fri, 24 May 2013 00:00:00 GMT");
        h.put("x-amz-date", date);
        h.put("x-amz-storage-class", "REDUCED_REDUNDANCY");
        h.put("x-amz-content-sha256", payload);
        String path = S3Signer.encodePath("/test$file.text");
        check("PUT path encodes $", path.equals("/test%24file.text"), path);
        sig = S3Signer.sign("PUT", path, q(), h, payload, date, "us-east-1", AWS_KEY, AWS_SECRET)
                .signature;
        check("PUT object with a payload", sig.equals(
                "98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"), sig);

        h = new TreeMap<>();
        h.put("host", host);
        h.put("x-amz-date", date);
        h.put("x-amz-content-sha256", S3Signer.EMPTY_SHA256);
        sig = S3Signer.sign("GET", "/", q("lifecycle", ""), h, S3Signer.EMPTY_SHA256, date,
                "us-east-1", AWS_KEY, AWS_SECRET).signature;
        check("GET ?lifecycle (a value-less parameter)", sig.equals(
                "fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543"), sig);

        sig = S3Signer.sign("GET", "/", q("prefix", "J", "max-keys", "2"), h,
                S3Signer.EMPTY_SHA256, date, "us-east-1", AWS_KEY, AWS_SECRET).signature;
        check("GET list (parameters sorted)", sig.equals(
                "34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7"), sig);

        TreeMap<String, String> hostOnly = new TreeMap<>();
        hostOnly.put("host", host);
        sig = S3Signer.sign("GET", "/test.txt", q(
                        "X-Amz-Algorithm", "AWS4-HMAC-SHA256",
                        "X-Amz-Credential", AWS_KEY + "/20130524/us-east-1/s3/aws4_request",
                        "X-Amz-Date", date,
                        "X-Amz-Expires", "86400",
                        "X-Amz-SignedHeaders", "host"),
                hostOnly, "UNSIGNED-PAYLOAD", date, "us-east-1", AWS_KEY, AWS_SECRET).signature;
        check("presigned GET (also pinned by firedown-api's presign_test.go)", sig.equals(
                "aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404"), sig);

        S3Signer.Signed s = S3Signer.sign("GET", "/test.txt", q(), h, S3Signer.EMPTY_SHA256, date,
                "us-east-1", AWS_KEY, AWS_SECRET);
        check("Authorization header shape", s.authorization.equals("AWS4-HMAC-SHA256 Credential="
                + AWS_KEY + "/20130524/us-east-1/s3/aws4_request, SignedHeaders="
                + "host;x-amz-content-sha256;x-amz-date, Signature=" + s.signature), s.authorization);
        check("header values trimmed and collapsed",
                S3Signer.trimHeaderValue("  a   b \t c  ").equals("a b c"));
        check("amz date", S3Signer.amzDate(1369353600000L).equals("20130524T000000Z"),
                S3Signer.amzDate(1369353600000L));
    }

    /** Every line of fixtures/botocore-vectors.tsv: the path encoding, the
     *  canonical query and the signature all equal botocore's. */
    private static void botocore() throws IOException {
        List<String> lines = Files.readAllLines(new File(fixtures, "botocore-vectors.tsv").toPath(),
                StandardCharsets.UTF_8);
        int ok = 0;
        List<String> bad = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.split("\t", -1);
            String key = new JSONArray("[" + f[1] + "]").getString(0);
            JSONArray qa = new JSONArray(f[2]);
            List<String[]> query = new ArrayList<>();
            for (int i = 0; i < qa.length(); i++) {
                query.add(new String[]{qa.getJSONArray(i).getString(0),
                        qa.getJSONArray(i).getString(1)});
            }
            byte[] payload = new byte[f[3].length() / 2];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) Integer.parseInt(f[3].substring(2 * i, 2 * i + 2), 16);
            }
            String path = "/" + S3Signer.encodePath(key);
            String hash = S3Signer.sha256Hex(payload);
            TreeMap<String, String> h = new TreeMap<>();
            h.put("host", f[7]);
            h.put("x-amz-content-sha256", hash);
            h.put("x-amz-date", f[9]);
            String sig = S3Signer.sign(f[0], path, query, h, hash, f[9], f[8],
                    "AKIDEXAMPLE12345", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY").signature;
            if (path.equals(f[4]) && S3Signer.canonicalQuery(query).equals(f[5]) && sig.equals(f[6])) {
                ok++;
            } else {
                bad.add(f[0] + " " + f[1]);
            }
        }
        check("botocore: " + ok + " of " + lines.size() + " requests sign the same",
                bad.isEmpty() && lines.size() >= 60, bad.toString());
    }

    // ---- client units ----

    static S3Config config(String endpoint, boolean pathStyle) {
        return new S3Config(endpoint, "us-east-1", "backups", "", "AKID", "SECRET", pathStyle,
                S3Config.Addressing.AUTO, null);
    }

    private static void clientUnits() throws IOException {
        S3Client path = new S3Client(null, config("https://s3.example.com", true));
        HttpUrl u = path.url("Firedown Backup/ABC/objects/x.fdvc", "partNumber=1&uploadId=a%2Fb");
        check("path-style object URL", u.toString().equals(
                "https://s3.example.com/backups/Firedown%20Backup/ABC/objects/x.fdvc"
                        + "?partNumber=1&uploadId=a%2Fb"), u.toString());
        check("path-style bucket URL", path.url(null, null).toString()
                .equals("https://s3.example.com/backups"), path.url(null, null).toString());
        S3Client virtual = new S3Client(null, config("https://s3.example.com", false));
        check("virtual-hosted object URL", virtual.url("a b", null).toString()
                .equals("https://backups.s3.example.com/a%20b"), virtual.url("a b", null).toString());
        check("virtual-hosted bucket URL", virtual.url(null, null).toString()
                .equals("https://backups.s3.example.com/"));
        S3Client based = new S3Client(null, config("http://nas.local:9000/s3", true));
        check("a base path and a port", based.url("k", null).toString()
                .equals("http://nas.local:9000/s3/backups/k"), based.url("k", null).toString());
        check("Host header: port kept when not the default",
                S3Client.hostHeader(HttpUrl.get("http://nas.local:9000/")).equals("nas.local:9000"));
        check("Host header: default port dropped",
                S3Client.hostHeader(HttpUrl.get("https://s3.example.com:443/")).equals("s3.example.com"));
        check("Host header: IPv6 in brackets",
                S3Client.hostHeader(HttpUrl.get("http://[::1]:9000/")).equals("[::1]:9000"));
        HttpUrl odd = path.url("a+b=c&d!'()*~/e", null);
        check("a key's reserved characters are sent as the signer encoded them",
                odd.encodedPath().equals("/backups/a%2Bb%3Dc%26d%21%27%28%29%2A~/e"), odd.encodedPath());

        check("classify: InvalidAccessKeyId is AUTH",
                refusal(S3Client.classify(403, "InvalidAccessKeyId", "", "", "x")) == S3Client.Refusal.AUTH);
        check("classify: SignatureDoesNotMatch is AUTH",
                refusal(S3Client.classify(403, "SignatureDoesNotMatch", "", "", "x")) == S3Client.Refusal.AUTH);
        check("classify: NoSuchBucket", refusal(S3Client.classify(404, "NoSuchBucket", "", "", "x"))
                == S3Client.Refusal.NO_BUCKET);
        check("classify: AccessDenied is DENIED", refusal(S3Client.classify(403, "AccessDenied",
                "Access Denied", "", "x")) == S3Client.Refusal.DENIED);
        IOException region = S3Client.classify(400, "AuthorizationHeaderMalformed",
                "the region 'us-east-1' is wrong; expecting 'eu-west-1'", "eu-west-1", "x");
        check("classify: a wrong region names the right one",
                refusal(region) == S3Client.Refusal.REGION
                        && "eu-west-1".equals(((S3Client.RefusedException) region).region));
        check("classify: B2's storage cap (AccessDenied in words) is FULL",
                S3Client.classify(403, "AccessDenied",
                        "Cannot upload files, storage cap exceeded.", "", "x")
                        instanceof BackupStore.FullException);
        check("classify: MinIO's quota is FULL",
                S3Client.classify(400, "XMinioAdminBucketQuotaExceeded", "", "", "x")
                        instanceof BackupStore.FullException);
        check("classify: 507 is FULL",
                S3Client.classify(507, "", "", "", "x") instanceof BackupStore.FullException);
        IOException e500 = S3Client.classify(500, "InternalError", "", "", "x");
        check("classify: 500 is a plain failure",
                e500 instanceof S3Client.ServerException
                        && !(e500 instanceof BackupStore.UnreachableException));
        IOException e404 = S3Client.classify(404, "NoSuchUpload", "", "", "x");
        check("classify: a 404 that is not 'no such key' never reads as a missing SOURCE file",
                !(e404 instanceof FileNotFoundException), e404.getClass().getSimpleName());
        check("classify: a bare 403 is DENIED",
                refusal(S3Client.classify(403, "", "", "", "x")) == S3Client.Refusal.DENIED);
        check("classify: 301 with a region header is REGION",
                refusal(S3Client.classify(301, "", "", "eu-central-1", "x")) == S3Client.Refusal.REGION);

        check("Retry-After seconds", S3Client.parseRetryAfter("7") == 7000);
        check("Retry-After absent", S3Client.parseRetryAfter(null) == -1);
        check("Retry-After junk", S3Client.parseRetryAfter("soon") == -1);
        check("ISO 8601 dates", S3Client.parseIso("2013-05-24T00:00:00.000Z") == 1369353600000L);
        check("XML escaping", S3Client.escapeXml("a&b<c>\"'").equals("a&amp;b&lt;c&gt;&quot;&apos;"));
        boolean refused = false;
        try {
            S3Client.parseXml(("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY a \"b\">]>"
                    + "<Error><Code>&a;</Code></Error>").getBytes(StandardCharsets.UTF_8), "x");
        } catch (IOException e) {
            refused = true;
        }
        check("an answer with a DOCTYPE is refused", refused);
        S3Client.Node n = S3Client.parseXml(("<ListBucketResult xmlns=\"http://s3.amazonaws.com/"
                + "doc/2006-03-01/\"><IsTruncated>true</IsTruncated><Contents><Key>a b</Key>"
                + "<Size>12</Size></Contents></ListBucketResult>").getBytes(StandardCharsets.UTF_8), "x");
        check("namespaced XML read by local names", n.name.equals("ListBucketResult")
                && n.children("Contents").get(0).text("Key").equals("a b"));

        long stride = S3Store.STRIDE;
        check("part size: one FDVC chunk for an ordinary file",
                S3Store.partSize(20L * 1024 * 1024) == stride);
        check("part size: one chunk is a single PUT (length == part)",
                S3Store.partSize(stride) == stride);
        long huge = 100_000L * stride;
        long part = S3Store.partSize(huge);
        check("part size: a huge file stays within 10,000 parts, in whole chunks",
                part % stride == 0 && (huge + part - 1) / part <= S3Store.MAX_PARTS, part + "");
        check("part size: never under S3's 5 MiB minimum", stride >= 5L * 1024 * 1024);

        check("labels: a known provider by name",
                S3Store.providerOf("https://s3.us-west-004.backblazeb2.com").equals("Backblaze B2"));
        check("labels: R2", S3Store.providerOf("https://abc.r2.cloudflarestorage.com").equals("Cloudflare R2"));
        check("labels: else the host and its port",
                S3Store.providerOf("http://nas.local:9000").equals("nas.local:9000"));
        check("labels: bucket and prefix", S3Store.locationOf("backups", "phone/").equals("backups/phone"));

        check("region guess: B2", S3Check.guessRegion("s3.us-west-004.backblazeb2.com").equals("us-west-004"));
        check("region guess: R2", S3Check.guessRegion("x.r2.cloudflarestorage.com").equals("auto"));
        check("region guess: Wasabi", S3Check.guessRegion("s3.eu-central-1.wasabisys.com").equals("eu-central-1"));
        check("region guess: AWS", S3Check.guessRegion("s3.eu-west-3.amazonaws.com").equals("eu-west-3"));
        check("region guess: Hetzner", S3Check.guessRegion("fsn1.your-objectstorage.com").equals("fsn1"));
        check("region guess: Scaleway", S3Check.guessRegion("s3.fr-par.scw.cloud").equals("fr-par"));
        check("region guess: anything else", S3Check.guessRegion("nas.local").equals("us-east-1"));
        check("prefix: normalised", S3Check.normalizePrefix(" /a//b/ ").equals("a/b/")
                && S3Check.normalizePrefix("").isEmpty());
        check("endpoint: a bare host gets https, no trailing slash",
                "https://s3.example.com".equals(S3Check.endpointString(S3Check.normalizeEndpoint("s3.example.com/"))));
        check("endpoint: not an address", S3Check.normalizeEndpoint("ftp://x") == null
                && S3Check.normalizeEndpoint("  ") == null);
    }

    private static S3Client.Refusal refusal(IOException e) {
        return e instanceof S3Client.RefusedException ? ((S3Client.RefusedException) e).refusal : null;
    }

    // ---- places: the stored kind, the migration, the sealed settings ----

    /** Prefs held in memory, one map per file name, apply = commit. */
    static final class MemPrefs implements SharedPreferences {
        final Map<String, Object> values = new ConcurrentHashMap<>();

        @Override
        public String getString(String key, String defValue) {
            Object v = values.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = values.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            Map<String, Object> pending = new HashMap<>();
            List<String> removed = new ArrayList<>();
            return new Editor() {
                @Override
                public Editor putString(String key, String value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor putBoolean(String key, boolean value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor remove(String key) {
                    removed.add(key);
                    return this;
                }

                @Override
                public void apply() {
                    commit();
                }

                @Override
                public boolean commit() {
                    for (String k : removed) {
                        values.remove(k);
                    }
                    values.putAll(pending);
                    return true;
                }
            };
        }

        String dump() {
            return new TreeMap<>(values).toString();
        }
    }

    static final class MemContext extends Context {
        final Map<String, MemPrefs> files = new ConcurrentHashMap<>();

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return files.computeIfAbsent(name, n -> new MemPrefs());
        }

        MemPrefs secret() {
            return files.computeIfAbsent(SyncSecrets.PREFS, n -> new MemPrefs());
        }
    }

    private static void places() {
        NetworkModule.globalClient = BASE;
        S3Config cfg = new S3Config("https://s3.us-west-004.backblazeb2.com", "us-west-004",
                "backups", "phone/", "0045ab12cdKEYID", "K004SuperSecretValue", true,
                S3Config.Addressing.AUTO, null);

        MemContext fresh = new MemContext();
        check("places: a fresh install migrates to FOLDER",
                BackupStores.place(fresh) == BackupStores.Place.FOLDER);
        check("places: and the migration is stored",
                "folder".equals(fresh.secret().getString(BackupStores.KEY_PLACE, null)));

        MemContext dav = new MemContext();
        new WebDavSettings(dav).save(new WebDavConfig("https://nas.local/dav/", "alex", "pw", null));
        check("places: an install with a saved WebDAV server migrates to WEBDAV",
                BackupStores.place(dav) == BackupStores.Place.WEBDAV);
        check("places: a server is a network place", BackupStores.usesNetwork(dav)
                && BackupStores.transferConstraints(dav).network == androidx.work.NetworkType.CONNECTED);

        MemContext stored = new MemContext();
        new WebDavSettings(stored).save(new WebDavConfig("https://nas.local/dav/", "alex", "pw", null));
        stored.secret().edit().putString(BackupStores.KEY_PLACE, "folder").apply();
        check("places: a stored kind wins over leftovers",
                BackupStores.place(stored) == BackupStores.Place.FOLDER);
        stored.secret().edit().putString(BackupStores.KEY_PLACE, "gdrive").apply();
        check("places: an unknown stored kind (a later build's) re-migrates",
                BackupStores.place(stored) == BackupStores.Place.WEBDAV);

        MemContext s3 = new MemContext();
        new WebDavSettings(s3).save(new WebDavConfig("https://nas.local/dav/", "alex", "pw", null));
        new S3Settings(s3).save(cfg);
        BackupStores.choose(s3, BackupStores.Place.S3);
        check("places: choosing S3 records it", BackupStores.place(s3) == BackupStores.Place.S3);
        check("places: and forgets the WebDAV sign-in", !new WebDavSettings(s3).isConfigured()
                && !s3.secret().contains("firedown.backup.webdav.password"));
        String dump = s3.secret().dump();
        check("places: neither key is stored in the clear",
                !dump.contains("0045ab12cdKEYID") && !dump.contains("K004SuperSecretValue"), dump);
        S3Config back = new S3Settings(s3).load();
        check("places: the settings round-trip through the seal", cfg.equals(back),
                String.valueOf(back));
        check("places: the labels need no unsealing", "backups".equals(new S3Settings(s3).bucket())
                && "phone/".equals(new S3Settings(s3).prefix()));
        BackupStore store = BackupStores.open(s3, new FolderStore(s3, new MemPrefs()));
        check("places: open() gives the S3 store", store instanceof S3Store);
        check("places: cached for the process",
                BackupStores.open(s3, new FolderStore(s3, new MemPrefs())) == store);
        check("places: S3 is a network place (backups need a connection)",
                BackupStores.transferConstraints(s3).network == androidx.work.NetworkType.CONNECTED);
        new S3Settings(s3).save(cfg.withRegion("us-west-002"));
        check("places: a new save gives a new store",
                BackupStores.open(s3, new FolderStore(s3, new MemPrefs())) != store);

        SyncSecrets.keyGone = true;
        new S3Settings(s3).save(cfg);
        BackupStore lost = BackupStores.open(s3, new FolderStore(s3, new MemPrefs()));
        SyncSecrets.keyGone = false;
        new S3Settings(s3).save(cfg);
        check("places: a key that no longer unwraps reads as not set up",
                lost instanceof FolderStore && !lost.isConfigured());

        BackupStores.choose(s3, BackupStores.Place.FOLDER);
        check("places: choosing the folder forgets the bucket and BOTH keys",
                !new S3Settings(s3).isConfigured()
                        && !s3.secret().contains("firedown.backup.s3.secret")
                        && !s3.secret().contains("firedown.backup.s3.access_key"),
                s3.secret().dump());
        check("places: a folder needs no connection",
                BackupStores.transferConstraints(s3).network == androidx.work.NetworkType.NOT_REQUIRED);
    }

    // ---- the fake ----

    static final class Server implements AutoCloseable {
        final FakeS3 s3 = new FakeS3("s3.fake");
        final HttpServer http;
        final String scheme;
        final int port;

        Server(SSLContext tls) throws IOException {
            if (tls == null) {
                http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                scheme = "http";
            } else {
                HttpsServer s = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                s.setHttpsConfigurator(new HttpsConfigurator(tls));
                http = s;
                scheme = "https";
            }
            http.createContext("/", s3);
            http.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "fakes3");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            port = http.getAddress().getPort();
        }

        String endpoint() {
            return scheme + "://s3.fake:" + port;
        }

        S3Config config() {
            return new S3Config(endpoint(), s3.region, s3.bucket, "", s3.accessKey, s3.secret,
                    true, S3Config.Addressing.AUTO, null);
        }

        S3Check.Result check() {
            return S3Check.run(BASE, endpoint(), "", s3.bucket, "", s3.accessKey, s3.secret,
                    S3Config.Addressing.AUTO, null);
        }

        @Override
        public void close() {
            http.stop(0);
        }
    }

    /** A store whose retries do not sleep; the waits asked for are kept. */
    private static S3Store store(Server s, List<Long> waits) {
        S3Store store = new S3Store(s.config(), BASE);
        store.client().sleeper = ms -> waits.add(ms);
        return store;
    }

    private static String objectsPrefix(SyncIdentity id) {
        return BackupLayout.ROOT_DIR + "/" + id.accountBase32() + "/" + BackupLayout.OBJECTS_DIR + "/";
    }

    private static int keysEnding(Server s, String suffix) {
        int n = 0;
        for (String k : s.s3.objects.keySet()) {
            if (k.endsWith(suffix)) {
                n++;
            }
        }
        return n;
    }

    // ---- check ----

    private static void checks() throws Exception {
        try (Server s = new Server(null)) {
            S3Check.Result ok = s.check();
            check("check: a reachable, writable bucket is OK", ok.kind == S3Check.Kind.OK
                    && ok.config != null, ok.kind + "");
            check("check: path-style first, and saved", ok.config != null && ok.config.pathStyle);
            check("check: an empty region is guessed (us-east-1 here)",
                    ok.config != null && ok.config.region.equals("us-east-1"));
            check("check: it wrote, read and deleted its test file, and left nothing",
                    s.s3.count("PUT ") == 1 && s.s3.count("GET ") == 1 && s.s3.count("DELETE ") == 1
                            && s.s3.objects.isEmpty(), s.s3.log.toString());
            check("check: every request's signature verified by the fake",
                    s.s3.badSignatures.get() == 0);

            S3Check.Result prefixed = S3Check.run(BASE, s.endpoint() + "/", "us-east-1", "backups",
                    "/phone/a/", s.s3.accessKey, s.s3.secret, S3Config.Addressing.AUTO, null);
            check("check: a prefix is normalised and used", prefixed.kind == S3Check.Kind.OK
                    && prefixed.config != null && prefixed.config.prefix.equals("phone/a/")
                    && s.s3.log.get(s.s3.log.size() - 3).contains("phone/a/Firedown Backup/"),
                    prefixed.kind + "");

            s.s3.pathStyleRefused = true;
            S3Check.Result virtual = s.check();
            check("check: AUTO falls back to virtual-hosted when path-style finds no bucket",
                    virtual.kind == S3Check.Kind.OK && virtual.config != null
                            && !virtual.config.pathStyle, virtual.kind + "");
            S3Check.Result forcedPath = S3Check.run(BASE, s.endpoint(), "", "backups", "",
                    s.s3.accessKey, s.s3.secret, S3Config.Addressing.PATH, null);
            check("check: an explicit path-style tries only that", forcedPath.kind
                    == S3Check.Kind.NO_SUCH_BUCKET, forcedPath.kind + "");
            S3Check.Result pasted = S3Check.run(BASE, "http://backups.s3.fake:" + s.port, "",
                    "backups", "", s.s3.accessKey, s.s3.secret, S3Config.Addressing.AUTO, null);
            check("check: an endpoint pasted with the bucket in front is reduced to the host",
                    pasted.kind == S3Check.Kind.OK && pasted.config != null
                            && pasted.config.endpoint.equals(s.endpoint()) && !pasted.config.pathStyle,
                    pasted.kind + " " + (pasted.config == null ? "" : pasted.config.endpoint));
            s.s3.pathStyleRefused = false;

            check("check: not an address is BAD_ADDRESS", S3Check.run(BASE, "ftp://x", "", "b", "",
                    "k", "s", S3Config.Addressing.AUTO, null).kind == S3Check.Kind.BAD_ADDRESS);
            int closed;
            try (ServerSocket ss = new ServerSocket(0)) {
                closed = ss.getLocalPort();
            }
            S3Check.Result down = S3Check.run(BASE, "http://127.0.0.1:" + closed, "", "backups", "",
                    "k", "s", S3Config.Addressing.AUTO, null);
            check("check: nothing listening is UNREACHABLE", down.kind == S3Check.Kind.UNREACHABLE,
                    down.kind + "");

            S3Check.Result wrongKey = S3Check.run(BASE, s.endpoint(), "", "backups", "", "AKIDWRONG",
                    s.s3.secret, S3Config.Addressing.AUTO, null);
            check("check: an unknown access key is AUTH_FAILED", wrongKey.kind == S3Check.Kind.AUTH_FAILED,
                    wrongKey.kind + "");
            S3Check.Result wrongSecret = S3Check.run(BASE, s.endpoint(), "", "backups", "",
                    s.s3.accessKey, "not-the-secret", S3Config.Addressing.AUTO, null);
            check("check: a wrong secret is AUTH_FAILED", wrongSecret.kind == S3Check.Kind.AUTH_FAILED,
                    wrongSecret.kind + "");
            S3Check.Result noBucket = S3Check.run(BASE, s.endpoint(), "", "other", "",
                    s.s3.accessKey, s.s3.secret, S3Config.Addressing.AUTO, null);
            check("check: a bucket that is not there is NO_SUCH_BUCKET",
                    noBucket.kind == S3Check.Kind.NO_SUCH_BUCKET, noBucket.kind + "");

            for (String op : new String[]{"LIST", "PUT", "GET", "DELETE"}) {
                s.s3.deny.clear();
                s.s3.deny.add(op);
                S3Check.Result denied = s.check();
                check("check: a key that may not " + op + " is ACCESS_DENIED",
                        denied.kind == S3Check.Kind.ACCESS_DENIED, denied.kind + "");
            }
            s.s3.deny.clear();
            check("check: a refused read or delete still leaves no test file behind (best-effort)",
                    keysEnding(s, ".tmp") <= 1, s.s3.objects.keySet().toString());
            s.s3.objects.clear();

            s.s3.region = "eu-central-1";
            S3Check.Result moved = S3Check.run(BASE, s.endpoint(), "us-east-1", "backups", "",
                    s.s3.accessKey, s.s3.secret, S3Config.Addressing.AUTO, null);
            check("check: a wrong region the server names is followed once, and saved",
                    moved.kind == S3Check.Kind.OK && moved.config != null
                            && moved.config.region.equals("eu-central-1"), moved.kind + "");
            s.s3.regionInError = false;
            S3Check.Result unnamed = S3Check.run(BASE, s.endpoint(), "us-east-1", "backups", "",
                    s.s3.accessKey, s.s3.secret, S3Config.Addressing.AUTO, null);
            check("check: a wrong region the server does not name is WRONG_REGION",
                    unnamed.kind == S3Check.Kind.WRONG_REGION, unnamed.kind + "");
            s.s3.regionInError = true;
            s.s3.region = "us-east-1";

            S3Client.phoneClock = () -> System.currentTimeMillis() - TimeUnit.HOURS.toMillis(3);
            S3Check.Result skewed = s.check();
            S3Client.phoneClock = System::currentTimeMillis;
            check("check: a phone clock three hours behind still works (re-signed from the server's Date)",
                    skewed.kind == S3Check.Kind.OK, skewed.kind + "");

            s.s3.quota = true;
            S3Check.Result full = s.check();
            check("check: a storage cap is FULL", full.kind == S3Check.Kind.FULL, full.kind + "");
            s.s3.quota = false;

            s.s3.notS3 = true;
            S3Check.Result web = s.check();
            check("check: a web server that is not S3 is NOT_S3", web.kind == S3Check.Kind.NOT_S3,
                    web.kind + "");
            s.s3.notS3 = false;

            // ---- the reconnect rules ----
            S3Config saved = ok.config;
            s.s3.log.clear();
            S3Check.Result again = S3Check.runReusing(BASE, saved, s.endpoint(), "", "backups", "",
                    s.s3.accessKey, null, S3Config.Addressing.AUTO, null);
            check("reuse: an empty secret reuses the saved one for the same endpoint, bucket and key",
                    again.kind == S3Check.Kind.OK, again.kind + "");
            s.s3.log.clear();
            S3Check.Result otherKey = S3Check.runReusing(BASE, saved, s.endpoint(), "", "backups", "",
                    "AKIDOTHER", null, S3Config.Addressing.AUTO, null);
            check("reuse: never paired with another access key (and nothing is sent)",
                    otherKey.kind == S3Check.Kind.AUTH_FAILED && s.s3.log.isEmpty(), otherKey.kind + "");
            S3Check.Result otherBucket = S3Check.runReusing(BASE, saved, s.endpoint(), "", "elsewhere",
                    "", s.s3.accessKey, null, S3Config.Addressing.AUTO, null);
            check("reuse: never for another bucket", otherBucket.kind == S3Check.Kind.AUTH_FAILED
                    && s.s3.log.isEmpty(), otherBucket.kind + "");
            try (Server other = new Server(null)) {
                S3Check.Result otherServer = S3Check.runReusing(BASE, saved, other.endpoint(), "",
                        "backups", "", s.s3.accessKey, null, S3Config.Addressing.AUTO, null);
                check("reuse: never sent to another endpoint", otherServer.kind
                        == S3Check.Kind.AUTH_FAILED && other.s3.log.isEmpty(), otherServer.kind + "");
            }
            S3Check.Result typed = S3Check.runReusing(BASE, saved, s.endpoint(), "", "backups", "",
                    s.s3.accessKey, "typed-and-wrong", S3Config.Addressing.AUTO, null);
            check("reuse: a typed secret wins over the saved one", typed.kind == S3Check.Kind.AUTH_FAILED,
                    typed.kind + "");
        }
    }

    // ---- store ----

    private static void store() throws Exception {
        try (Server s = new Server(null)) {
            List<Long> waits = new CopyOnWriteArrayList<>();
            S3Store store = store(s, waits);
            SyncIdentity id = identity(1);
            VaultEngine engine = new VaultEngine(store, id);
            String objs = objectsPrefix(id);

            check("store: reachable", store.isReachable());
            check("store: an empty bucket lists nothing", engine.loadManifest().isEmpty());

            File small = randomFile("small.jpg", 300_000, 1);
            VaultEntry e1 = engine.backupFile(small, "image/jpeg", "thumb", null, "https://x.example", 1000);
            check("store: a small file is ONE PUT, no multipart", s.s3.count("PUT " + objs) == 2
                    && s.s3.count("MPUCREATE") == 0, s.s3.log.toString());
            FakeS3.Obj o1 = s.s3.objects.get(objs + e1.objectId + BackupLayout.OBJECT_EXT);
            check("store: the object has its exact length",
                    o1 != null && o1.data.length == VaultEngine.objectLength(300_000, 1));
            check("store: the sidecar is there", s.s3.objects.containsKey(objs + e1.objectId
                    + BackupLayout.ENTRY_EXT));

            File big = randomFile("clip.mp4", 20 * 1024 * 1024 + 123, 2);
            s.s3.log.clear();
            VaultEntry e2 = engine.backupFile(big, "video/mp4", null, null, null, 0);
            check("store: a 20 MiB file is a multipart upload of 3 parts",
                    s.s3.count("MPUCREATE") == 1 && s.s3.count("PART") == 3
                            && s.s3.count("MPUCOMPLETE") == 1, s.s3.log.toString());
            check("store: the parts passed the strict rules (>= 5 MiB, equal but the last)",
                    s.s3.objects.containsKey(objs + e2.objectId + BackupLayout.OBJECT_EXT)
                            && s.s3.uploads.isEmpty());

            File exact = randomFile("exact.bin", 2 * VaultEngine.CHUNK_SIZE, 3);
            s.s3.log.clear();
            VaultEntry e3 = engine.backupFile(exact, "application/octet-stream", null, null, null, 0);
            check("store: two whole chunks: two full parts, the last sent at close",
                    s.s3.count("PART") == 2 && s.s3.count("MPUCOMPLETE") == 1, s.s3.log.toString());
            File one = randomFile("one.bin", VaultEngine.CHUNK_SIZE, 4);
            s.s3.log.clear();
            engine.backupFile(one, "application/octet-stream", null, null, null, 0);
            check("store: exactly one chunk is still one PUT", s.s3.count("MPUCREATE") == 0
                    && s.s3.count("PUT " + objs) == 2, s.s3.log.toString());
            check("store: every signature verified", s.s3.badSignatures.get() == 0);

            List<VaultEntry> list = engine.loadManifest();
            check("store: the listing has all four", list.size() == 4, list.size() + "");
            VaultEntry listed2 = null;
            VaultEntry listed3 = null;
            for (VaultEntry e : list) {
                if (e.objectId.equals(e2.objectId)) {
                    listed2 = e;
                }
                if (e.objectId.equals(e3.objectId)) {
                    listed3 = e;
                }
            }
            final VaultEntry big2 = listed2;
            File back = new File(work, "back.mp4");
            engine.restoreFile(listed2, back, null);
            check("store: restore of the multipart object is byte-identical", sha256(back).equals(sha256(big)));
            File back3 = new File(work, "back3.bin");
            engine.restoreFile(listed3, back3, null);
            check("store: and of the two-chunk one", sha256(back3).equals(sha256(exact)));

            byte[] original = Files.readAllBytes(big.toPath());
            check("store: ranged reads match across chunk boundaries",
                    rangesMatch(store, id, listed2, original));
            int gets = s.s3.count("GET " + objs + e2.objectId);
            VaultObjectReader one1 = new VaultObjectReader(store, id, listed2);
            byte[] buf = new byte[100];
            one1.readAt(VaultEngine.CHUNK_SIZE + 10, buf, 0, buf.length);
            one1.close();
            check("store: a seek reads one 8 MiB chunk (one ranged GET)",
                    s.s3.count("GET " + objs + e2.objectId) == gets + 1);
            s.s3.ignoreRange = true;
            check("store: a server that ignores Range still reads right",
                    rangesMatch(store, id, listed2, original));
            s.s3.ignoreRange = false;
            s.s3.wrongRange = true;
            IOException wrong = expectIo(() -> rangesMatch(store, id, big2, original));
            check("store: a 206 for another range is refused", wrong != null, String.valueOf(wrong));
            s.s3.wrongRange = false;

            s.s3.log.clear();
            engine.backupFile(small, "image/jpeg", "thumb", null, "https://x.example", 1000);
            check("store: the same file again is not uploaded again (dedup)", s.s3.count("PUT " + objs) == 0
                    && s.s3.count("MPUCREATE") == 0);

            s.s3.log.clear();
            engine.loadManifest();
            check("store: an unchanged sidecar is not read twice (cached by ETag)",
                    s.s3.count("GET ") == 0, s.s3.log.toString());
            s.s3.objects.put(objs + e1.objectId + BackupLayout.ENTRY_EXT, new FakeS3.Obj(
                    BackupLayout.encodeEntry(new VaultEntry(e1.objectId, e1.wrappedDek, "renamed.jpg",
                            e1.size, e1.mime, e1.downloadedAt, e1.chunkCount, e1.thumb, e1.origin,
                            e1.backedUpAt), id.storageMasterKey()), System.currentTimeMillis()));
            boolean renamed = false;
            for (VaultEntry e : engine.loadManifest()) {
                renamed |= e.name.equals("renamed.jpg");
            }
            check("store: a rewritten sidecar is read again", renamed);

            long old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2);
            String orphanOld = objs + "0123456789abcdef0123456789abcdef" + BackupLayout.OBJECT_EXT;
            String orphanYoung = objs + "11111111111111111111111111111111" + BackupLayout.OBJECT_EXT;
            String lonely = objs + "22222222222222222222222222222222" + BackupLayout.ENTRY_EXT;
            s.s3.objects.put(orphanOld, new FakeS3.Obj(new byte[10], old));
            s.s3.objects.put(orphanYoung, new FakeS3.Obj(new byte[10], System.currentTimeMillis()));
            s.s3.objects.put(lonely, new FakeS3.Obj(new byte[10], old));
            int n = engine.loadManifest().size();
            check("store: an old object with no sidecar is swept", !s.s3.objects.containsKey(orphanOld));
            check("store: a young one is an upload in flight, kept", s.s3.objects.containsKey(orphanYoung));
            check("store: a sidecar with no object is skipped and never deleted",
                    s.s3.objects.containsKey(lonely) && n == 4, n + "");

            s.s3.pageCap = 2;
            check("store: a paged listing is read whole", engine.loadManifest().size() == 4);
            s.s3.pageCap = 1000;

            engine.deleteEntry(listed3);
            check("store: delete removes the object and its sidecar",
                    !s.s3.objects.containsKey(objs + e3.objectId + BackupLayout.OBJECT_EXT)
                            && !s.s3.objects.containsKey(objs + e3.objectId + BackupLayout.ENTRY_EXT)
                            && engine.loadManifest().size() == 3);

            // the stale-upload sweep
            FakeS3.Mpu stale = new FakeS3.Mpu(objs + "33333333333333333333333333333333.fdvc",
                    System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2));
            FakeS3.Mpu youngUp = new FakeS3.Mpu(objs + "44444444444444444444444444444444.fdvc",
                    System.currentTimeMillis());
            s.s3.uploads.put("stale-1", stale);
            s.s3.uploads.put("young-1", youngUp);
            OutputStream open = store.openNewObject(id.accountBase32(),
                    "55555555555555555555555555555555", 3 * S3Store.STRIDE);
            open.write(new byte[(int) S3Store.STRIDE]);
            String openId = null;
            for (Map.Entry<String, FakeS3.Mpu> e : s.s3.uploads.entrySet()) {
                if (e.getValue().key.contains("55555555")) {
                    openId = e.getKey();
                }
            }
            if (openId != null) {
                FakeS3.Mpu u = s.s3.uploads.get(openId);
                FakeS3.Mpu aged = new FakeS3.Mpu(u.key, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3));
                aged.parts.putAll(u.parts);
                s.s3.uploads.put(openId, aged);
            }
            int aborted = store.sweepUploads(objs);
            check("store: the sweep aborts an unfinished upload older than a day",
                    !s.s3.uploads.containsKey("stale-1") && aborted == 1, aborted + "");
            check("store: and keeps a young one", s.s3.uploads.containsKey("young-1"));
            check("store: and never one this store has open, however old",
                    openId != null && s.s3.uploads.containsKey(openId));
            IOException shortClose = expectIo(open::close);
            check("store: an upload closed short stores nothing and aborts its parts",
                    shortClose != null && openId != null && !s.s3.uploads.containsKey(openId)
                            && !s.s3.objects.containsKey(objs + "55555555555555555555555555555555.fdvc"));
            s.s3.uploads.remove("young-1");

            S3Store fresh = store(s, waits);
            VaultEngine freshEngine = new VaultEngine(fresh, id);
            s.s3.log.clear();
            freshEngine.loadManifest();
            freshEngine.loadManifest();
            Thread.sleep(300);
            check("store: a listing runs the sweep, at most once a day per store",
                    s.s3.count("LISTMPU") == 1, s.s3.count("LISTMPU") + "");

            // delete-all, batched, beside another account
            SyncIdentity other = identity(9);
            VaultEngine otherEngine = new VaultEngine(store, other);
            otherEngine.backupFile(randomFile("o.bin", 1000, 9), "application/octet-stream", null, null, null, 0);
            s.s3.log.clear();
            engine.deleteAll();
            check("store: delete-all uses DeleteObjects", s.s3.count("BATCHDELETE") >= 1);
            boolean leftover = false;
            for (String k : s.s3.objects.keySet()) {
                leftover |= k.contains(id.accountBase32());
            }
            check("store: delete-all removes the account's keys", !leftover && engine.loadManifest().isEmpty());
            check("store: and leaves another code's backups alone", otherEngine.loadManifest().size() == 1);
            s.s3.noBatchDelete = true;
            engine.backupFile(small, "image/jpeg", null, null, null, 0);
            engine.backupFile(one, "application/octet-stream", null, null, null, 0);
            s.s3.log.clear();
            engine.deleteAll();
            check("store: a provider without DeleteObjects gets single deletes",
                    s.s3.count("BATCHDELETE") == 1 && s.s3.count("DELETE ") >= 4
                            && engine.loadManifest().isEmpty(), s.s3.log.toString());
            check("store: no retry waits were needed", waits.isEmpty(), waits.toString());
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
        try (Server s = new Server(null)) {
            List<Long> waits = new CopyOnWriteArrayList<>();
            S3Store store = store(s, waits);
            SyncIdentity id = identity(3);
            VaultEngine engine = new VaultEngine(store, id);
            File src = randomFile("big.bin", 20 * 1024 * 1024, 3);
            String objs = objectsPrefix(id);

            s.s3.fail500.set(2);
            VaultEntry ok = engine.backupFile(src, "application/octet-stream", null, null, null, 0);
            check("fail: two 500s are retried and the backup lands", ok != null
                    && engine.loadManifest().size() == 1);
            check("fail: with backoff (1 s, then 2 s)", waits.size() == 2 && waits.get(0) == 1000
                    && waits.get(1) == 2000, waits.toString());
            engine.deleteAll();
            waits.clear();

            s.s3.fail500.set(50);
            IOException hard = expectIo(() -> engine.backupFile(src, "application/octet-stream",
                    null, null, null, 0));
            s.s3.fail500.set(0);
            check("fail: a 500 that persists is a plain (retryable) IOException",
                    hard != null && !(hard instanceof BackupStore.UnreachableException)
                            && !(hard instanceof BackupStore.FullException), String.valueOf(hard));
            check("fail: after three attempts per request", waits.size() == 2, waits.toString());
            check("fail: and leaves no parts behind", s.s3.uploads.isEmpty());
            waits.clear();

            s.s3.slowDown.set(1);
            engine.backupFile(src, "application/octet-stream", null, null, null, 0);
            check("fail: SlowDown (503) is retried", engine.loadManifest().size() == 1 && waits.size() == 1);
            engine.deleteAll();
            waits.clear();

            s.s3.tooMany.set(1);
            s.s3.retryAfterSeconds = 2;
            engine.backupFile(randomFile("t.bin", 1000, 4), "application/octet-stream", null, null, null, 0);
            check("fail: 429 honours Retry-After", waits.size() == 1 && waits.get(0) == 2000,
                    waits.toString());
            engine.deleteAll();
            waits.clear();

            s.s3.dropPartOnce = 2;
            s.s3.log.clear();
            VaultEntry dropped = engine.backupFile(src, "application/octet-stream", null, null, null, 0);
            check("fail: an upload dropped mid-part retries THAT part, not the file",
                    s.s3.count("DROPPED") == 1 && s.s3.count("MPUCREATE") == 1
                            && s.s3.count("PART " + objs + dropped.objectId) == 3
                            && s.s3.count("PART " + objs + dropped.objectId + ".fdvc?partNumber=2") == 1,
                    s.s3.log.toString());
            File back = new File(work, "dropped.bin");
            engine.restoreFile(engine.loadManifest().get(0), back, null);
            check("fail: and the object is intact", sha256(back).equals(sha256(src)));
            engine.deleteAll();
            waits.clear();

            s.s3.log.clear();
            VaultEngine cancelling = new VaultEngine(store, id);
            IOException cancelled = expectIo(() -> cancelling.backupFile(src, "application/octet-stream",
                    null, (done, total) -> {
                        if (done > 9L * 1024 * 1024) {
                            cancelling.cancel();
                        }
                    }, null, 0));
            check("fail: a cancelled multipart upload fails", cancelled != null);
            check("fail: and leaves no parts and no object behind", s.s3.uploads.isEmpty()
                    && s.s3.count("MPUABORT") == 1 && keysEnding(s, ".fdvc") == 0
                    && keysEnding(s, ".entry") == 0, s.s3.log.toString());

            IOException shrunk = expectIo(() -> engine.backupStream("shrunk.bin", 20L * 1024 * 1024,
                    () -> new ByteArrayInputStream(new byte[12 * 1024 * 1024]),
                    "application/octet-stream", null, null, null, 0));
            check("fail: a file that shrank under the backup stores nothing",
                    shrunk != null && s.s3.uploads.isEmpty() && keysEnding(s, ".fdvc") == 0,
                    String.valueOf(shrunk));

            s.s3.completeErrorIn200Once = true;
            IOException complete200 = expectIo(() -> engine.backupFile(src, "application/octet-stream",
                    null, null, null, 0));
            check("fail: an <Error> inside a 200 to CompleteMultipartUpload is a failure",
                    complete200 != null && keysEnding(s, ".entry") == 0 && s.s3.uploads.isEmpty(),
                    String.valueOf(complete200));

            s.s3.quota = true;
            IOException full = expectIo(() -> engine.backupFile(randomFile("q.bin", 1000, 5),
                    "application/octet-stream", null, null, null, 0));
            check("fail: a storage cap is FullException", full instanceof BackupStore.FullException,
                    String.valueOf(full));
            IOException fullMpu = expectIo(() -> engine.backupFile(src, "application/octet-stream",
                    null, null, null, 0));
            check("fail: also for a multipart upload, with nothing left behind",
                    fullMpu instanceof BackupStore.FullException && s.s3.uploads.isEmpty(),
                    String.valueOf(fullMpu));
            s.s3.quota = false;

            engine.backupFile(randomFile("a.bin", 1000, 6), "application/octet-stream", null, null, null, 0);
            engine.backupFile(randomFile("b.bin", 1000, 7), "application/octet-stream", null, null, null, 0);
            S3Store cold = store(s, waits);
            VaultEngine coldEngine = new VaultEngine(cold, id);
            String vanish = null;
            for (String k : s.s3.objects.keySet()) {
                if (k.endsWith(BackupLayout.ENTRY_EXT)) {
                    vanish = k;
                }
            }
            s.s3.vanishAfterList.add(vanish);
            List<VaultEntry> afterVanish = coldEngine.loadManifest();
            check("fail: a sidecar that vanished between the listing and the read is skipped",
                    afterVanish.size() == 1, afterVanish.size() + "");
            S3Store colder = store(s, waits);
            s.s3.failGetSuffix = BackupLayout.ENTRY_EXT;
            IOException sidecarFail = expectIo(() -> new VaultEngine(colder, id).loadManifest());
            s.s3.failGetSuffix = null;
            check("fail: a sidecar read that fails fails the listing (never silently short)",
                    sidecarFail != null, String.valueOf(sidecarFail));
            waits.clear();

            S3Client.phoneClock = () -> System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
            S3Store skewed = store(s, waits);
            VaultEngine skewEngine = new VaultEngine(skewed, id);
            int before = s.s3.requests.get();
            skewEngine.loadManifest();
            int first = s.s3.requests.get() - before;
            before = s.s3.requests.get();
            skewEngine.loadManifest();
            int second = s.s3.requests.get() - before;
            check("fail: a skewed phone clock is corrected from the server's Date",
                    Math.abs(skewed.client().skewMillis() - TimeUnit.HOURS.toMillis(1)) < 5000,
                    skewed.client().skewMillis() + "");
            check("fail: and the offset is kept (no extra request the second time)",
                    second < first, first + " then " + second);
            S3Client.phoneClock = System::currentTimeMillis;

            s.s3.secret = "rotated";
            IOException rotated = expectIo(engine::loadManifest);
            check("fail: rotated keys are UNREACHABLE (Reconnect)",
                    rotated instanceof BackupStore.UnreachableException, String.valueOf(rotated));
            s.s3.secret = "harness/secret+key";
            s.s3.noBucket = true;
            IOException gone = expectIo(engine::loadManifest);
            check("fail: a bucket that is gone is UNREACHABLE",
                    gone instanceof BackupStore.UnreachableException, String.valueOf(gone));
            check("fail: and the store reads as unreachable", !store.isReachable());
            s.s3.noBucket = false;
            s.s3.deny.add("PUT");
            IOException denied = expectIo(() -> engine.backupFile(randomFile("d.bin", 1000, 8),
                    "application/octet-stream", null, null, null, 0));
            check("fail: access denied is UNREACHABLE", denied instanceof BackupStore.UnreachableException,
                    String.valueOf(denied));
            s.s3.deny.clear();
            s.s3.region = "eu-central-1";
            IOException moved = expectIo(engine::loadManifest);
            check("fail: a bucket that moved region is UNREACHABLE (the reconnect follows it)",
                    moved instanceof BackupStore.UnreachableException, String.valueOf(moved));
            s.s3.region = "us-east-1";

            VaultEntry ghost = new VaultEntry("66666666666666666666666666666666", ok.wrappedDek, "g",
                    10, null, 0, 1, null, null);
            IOException missing = expectIo(() -> new VaultObjectReader(store, id, ghost));
            check("fail: streaming an object that is not there is FileNotFoundException",
                    missing instanceof FileNotFoundException, String.valueOf(missing));
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
        X509Certificate cert = (X509Certificate) ks.getCertificate("s3");
        return ServerTrust.sha256(cert);
    }

    private static void tls(String keystoreA, String keystoreB) throws Exception {
        String pinA = certSha256(keystoreA);
        S3Config pinned;
        try (Server s = new Server(serverTls(keystoreA))) {
            S3Check.Result first = s.check();
            check("tls: a self-made certificate is UNTRUSTED_CERT with its fingerprint",
                    first.kind == S3Check.Kind.UNTRUSTED_CERT && first.cert != null
                            && first.cert.sha256.equals(pinA), first.kind + "");
            check("tls: nothing was sent before the certificate was refused", s.s3.log.isEmpty());
            S3Check.Result trusted = S3Check.run(BASE, s.endpoint(), "", "backups", "",
                    s.s3.accessKey, s.s3.secret, S3Config.Addressing.AUTO, pinA.toUpperCase());
            check("tls: trusted by its fingerprint", trusted.kind == S3Check.Kind.OK
                    && trusted.config != null && pinA.equals(trusted.config.pinSha256), trusted.kind + "");
            pinned = trusted.config;
            S3Check.Result again = S3Check.runReusing(BASE, pinned, s.endpoint(), "", "backups", "",
                    s.s3.accessKey, null, S3Config.Addressing.AUTO, null);
            check("tls: a reconnect keeps the certificate already trusted (and the secret)",
                    again.kind == S3Check.Kind.OK && again.config != null
                            && pinA.equals(again.config.pinSha256), again.kind + "");
            s.s3.pathStyleRefused = true;
            S3Check.Result virtual = S3Check.run(BASE, s.endpoint(), "", "backups", "",
                    s.s3.accessKey, s.s3.secret, S3Config.Addressing.AUTO, pinA);
            check("tls: the pin also covers the bucket's own host (virtual-hosted)",
                    virtual.kind == S3Check.Kind.OK && virtual.config != null && !virtual.config.pathStyle,
                    virtual.kind + "");
            s.s3.pathStyleRefused = false;
            S3Store store = new S3Store(pinned, BASE);
            VaultEngine engine = new VaultEngine(store, identity(5));
            engine.backupFile(randomFile("tls.bin", 1000, 5), "application/octet-stream", null, null, null, 0);
            check("tls: backups work through the pin", engine.loadManifest().size() == 1);
        }
        try (Server s = new Server(serverTls(keystoreB))) {
            S3Config samePin = new S3Config(s.endpoint(), "us-east-1", "backups", "", s.s3.accessKey,
                    s.s3.secret, true, S3Config.Addressing.AUTO, pinA);
            S3Store store = new S3Store(samePin, BASE);
            IOException changed = expectIo(() -> new VaultEngine(store, identity(5)).loadManifest());
            check("tls: a certificate that changed is UNREACHABLE (needs the user)",
                    changed instanceof BackupStore.UnreachableException, String.valueOf(changed));
            check("tls: and the store reads as unreachable", !store.isReachable());
        }
    }

    // ---- real servers: moto, MinIO ----

    /** A running S3 server: its endpoint, keys, and how to stop it. */
    static final class Real implements AutoCloseable {
        final Process process;
        final String endpoint;
        final String accessKey;
        final String secret;
        final String python;

        Real(Process process, String endpoint, String accessKey, String secret, String python) {
            this.process = process;
            this.endpoint = endpoint;
            this.accessKey = accessKey;
            this.secret = secret;
            this.python = python;
        }

        @Override
        public void close() {
            process.destroy();
            try {
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            process.destroyForcibly();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static void waitFor(String endpoint, Process p) throws Exception {
        OkHttpClient c = BASE.newBuilder().connectTimeout(1, TimeUnit.SECONDS).build();
        for (int i = 0; i < 120; i++) {
            if (!p.isAlive()) {
                throw new IOException("the server exited: " + p.exitValue());
            }
            try (Response r = c.newCall(new Request.Builder().url(endpoint + "/").head().build()).execute()) {
                return;
            } catch (IOException e) {
                Thread.sleep(500);
            }
        }
        throw new IOException("the server did not start: " + endpoint);
    }

    private static Real startMoto(String motoServer) throws Exception {
        int port = freePort();
        ProcessBuilder pb = new ProcessBuilder(motoServer, "-H", "127.0.0.1", "-p", Integer.toString(port));
        pb.redirectErrorStream(true).redirectOutput(new File(work, "moto.log"));
        Process p = pb.start();
        String endpoint = "http://127.0.0.1:" + port;
        waitFor(endpoint, p);
        String python = new File(new File(motoServer).getParentFile(), "python").getPath();
        return new Real(p, endpoint, "AKIDMOTOTEST", "moto/secret", python);
    }

    /** A bucket made with one signed PUT (the store never makes buckets). */
    private static void createBucket(Real real, String bucket, String region) throws IOException {
        HttpUrl url = HttpUrl.get(real.endpoint + "/" + bucket);
        String amzDate = S3Signer.amzDate(System.currentTimeMillis());
        byte[] body = region.equals("us-east-1") ? new byte[0]
                : ("<CreateBucketConfiguration><LocationConstraint>" + region
                + "</LocationConstraint></CreateBucketConfiguration>").getBytes(StandardCharsets.UTF_8);
        String hash = S3Signer.sha256Hex(body);
        TreeMap<String, String> h = new TreeMap<>();
        h.put("host", S3Client.hostHeader(url));
        h.put("x-amz-content-sha256", hash);
        h.put("x-amz-date", amzDate);
        S3Signer.Signed s = S3Signer.sign("PUT", url.encodedPath(), new ArrayList<>(), h, hash,
                amzDate, region, real.accessKey, real.secret);
        Request request = new Request.Builder().url(url)
                .put(RequestBody.create(body, MediaType.get("application/xml")))
                .header("x-amz-content-sha256", hash).header("x-amz-date", amzDate)
                .header("Authorization", s.authorization).build();
        try (Response r = BASE.newCall(request).execute()) {
            if (r.code() != 200 && r.code() != 409) {
                throw new IOException("create bucket: HTTP " + r.code() + " " + r.body().string());
            }
        }
    }

    /** The bucket's keys and sizes as boto3 sees them: an independent client. */
    private static Map<String, Long> botoList(Real real, String bucket, String region) throws Exception {
        String script = "import boto3,json,sys\n"
                + "c=boto3.client('s3',endpoint_url=sys.argv[1],aws_access_key_id=sys.argv[2],"
                + "aws_secret_access_key=sys.argv[3],region_name=sys.argv[5])\n"
                + "out={}\n"
                + "for page in c.get_paginator('list_objects_v2').paginate(Bucket=sys.argv[4]):\n"
                + "    for o in page.get('Contents',[]): out[o['Key']]=o['Size']\n"
                + "ups=c.list_multipart_uploads(Bucket=sys.argv[4]).get('Uploads',[])\n"
                + "print(json.dumps({'objects':out,'uploads':len(ups)}))\n";
        File f = new File(work, "botolist.py");
        Files.write(f.toPath(), script.getBytes(StandardCharsets.UTF_8));
        Process p = new ProcessBuilder(real.python, "-I", f.getPath(), real.endpoint, real.accessKey,
                real.secret, bucket, region).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor(60, TimeUnit.SECONDS);
        String last = out.substring(out.lastIndexOf('\n') + 1);
        JSONObject j = new JSONObject(last);
        Map<String, Long> keys = new TreeMap<>();
        JSONObject objects = j.getJSONObject("objects");
        for (String k : objects.keySet()) {
            keys.put(k, objects.getLong(k));
        }
        keys.put("__uploads__", j.getLong("uploads"));
        return keys;
    }

    /** The backup round trip against a real S3 server, checked through boto3. */
    private static void realServer(String label, Real real) throws Exception {
        try (Real r = real) {
            roundTrip(label, r, "backups", "us-east-1", false);
        }
    }

    private static void roundTrip(String label, Real real, String bucket, String region,
                                  boolean verifiesSignatures) throws Exception {
        createBucket(real, bucket, region);
        S3Check.Result ok = S3Check.run(BASE, real.endpoint, region, bucket, "phone/",
                real.accessKey, real.secret, S3Config.Addressing.AUTO, null);
        check(label + ": the check passes", ok.kind == S3Check.Kind.OK && ok.config != null, ok.kind + "");
        if (ok.config == null) {
            return;
        }
        S3Store store = new S3Store(ok.config, BASE);
        SyncIdentity id = identity(21);
        VaultEngine engine = new VaultEngine(store, id);
        File small = randomFile(label + "-small.bin", 70_000, 21);
        File big = randomFile(label + "-big.bin", 17 * 1024 * 1024 + 5, 22);
        VaultEntry es = engine.backupFile(small, "image/jpeg", "t", null, null, 0);
        VaultEntry eb = engine.backupFile(big, "video/mp4", null, null, null, 0);
        Map<String, Long> keys = botoList(real, bucket, region);
        String objs = "phone/" + objectsPrefix(id);
        check(label + ": boto3 sees both objects at their exact lengths",
                Long.valueOf(VaultEngine.objectLength(70_000, 1)).equals(keys.get(objs + es.objectId + ".fdvc"))
                        && Long.valueOf(VaultEngine.objectLength(17 * 1024 * 1024 + 5, 3))
                        .equals(keys.get(objs + eb.objectId + ".fdvc")), keys.toString());
        check(label + ": and both sidecars", keys.containsKey(objs + es.objectId + ".entry")
                && keys.containsKey(objs + eb.objectId + ".entry"));
        check(label + ": and no unfinished upload", keys.get("__uploads__") == 0L);
        check(label + ": nothing but the backups (the test file is gone)",
                keys.size() == 5, keys.keySet().toString());
        List<VaultEntry> list = engine.loadManifest();
        check(label + ": the listing has both", list.size() == 2);
        VaultEntry listedBig = list.get(0).objectId.equals(eb.objectId) ? list.get(0) : list.get(1);
        File back = new File(work, label + "-back.bin");
        engine.restoreFile(listedBig, back, null);
        check(label + ": restore is byte-identical", sha256(back).equals(sha256(big)));
        check(label + ": ranged reads match", rangesMatch(store, id, listedBig,
                Files.readAllBytes(big.toPath())));
        VaultEngine cancelling = new VaultEngine(store, id);
        IOException cancelled = expectIo(() -> cancelling.backupFile(randomFile(label + "-c.bin",
                        18 * 1024 * 1024, 23), "application/octet-stream", null, (done, total) -> {
                    if (done > 9L * 1024 * 1024) {
                        cancelling.cancel();
                    }
                }, null, 0));
        Map<String, Long> afterCancel = botoList(real, bucket, region);
        check(label + ": a cancelled multipart upload leaves no upload and no object",
                cancelled != null && afterCancel.get("__uploads__") == 0L && afterCancel.size() == 5,
                afterCancel.toString());
        engine.deleteEntry(list.get(0).objectId.equals(es.objectId) ? list.get(0) : list.get(1));
        check(label + ": delete removes one backup", engine.loadManifest().size() == 1);
        engine.deleteAll();
        Map<String, Long> afterAll = botoList(real, bucket, region);
        check(label + ": delete-all leaves the bucket empty", afterAll.size() == 1, afterAll.toString());
        S3Check.Result wrongSecret = S3Check.run(BASE, real.endpoint, region, bucket, "",
                real.accessKey, "definitely-wrong", S3Config.Addressing.AUTO, null);
        if (verifiesSignatures) {
            check(label + ": a wrong secret is AUTH_FAILED", wrongSecret.kind == S3Check.Kind.AUTH_FAILED,
                    wrongSecret.kind + "");
        } else {
            System.out.println("    (" + label + " does not verify signatures; a wrong secret answered "
                    + wrongSecret.kind + ", not counted)");
        }
        S3Check.Result noBucket = S3Check.run(BASE, real.endpoint, region, "no-such-bucket-here", "",
                real.accessKey, real.secret, S3Config.Addressing.AUTO, null);
        check(label + ": a missing bucket is NO_SUCH_BUCKET", noBucket.kind == S3Check.Kind.NO_SUCH_BUCKET,
                noBucket.kind + "");
    }

    private static void minio(String minio) throws Exception {
        File data = new File(work, "minio-data");
        if (!data.mkdirs() && !data.isDirectory()) {
            throw new IOException("no data dir");
        }
        int port = freePort();
        int console = freePort();
        ProcessBuilder pb = new ProcessBuilder(minio, "server", data.getPath(), "--address",
                "127.0.0.1:" + port, "--console-address", "127.0.0.1:" + console);
        pb.environment().put("MINIO_ROOT_USER", "harnessminio");
        pb.environment().put("MINIO_ROOT_PASSWORD", "harness-minio-secret");
        pb.environment().put("MINIO_REGION", "eu-west-9");
        pb.environment().put("MINIO_SITE_REGION", "eu-west-9");
        pb.redirectErrorStream(true).redirectOutput(new File(work, "minio.log"));
        Process p = pb.start();
        String endpoint = "http://127.0.0.1:" + port;
        String python = System.getenv("BOTO_PYTHON");
        try (Real real = new Real(p, endpoint, "harnessminio", "harness-minio-secret",
                python == null ? "python3" : python)) {
            waitFor(endpoint, p);
            if (python == null) {
                System.out.println("  (BOTO_PYTHON not set: the boto3 checks need it)");
            }
            roundTrip("minio", real, "backups", "eu-west-9", true);
            S3Client.phoneClock = () -> System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2);
            S3Check.Result skewed = S3Check.run(BASE, endpoint, "eu-west-9", "backups", "",
                    real.accessKey, real.secret, S3Config.Addressing.AUTO, null);
            S3Client.phoneClock = System::currentTimeMillis;
            check("minio: a phone clock two hours behind is put right by MinIO's Date header",
                    skewed.kind == S3Check.Kind.OK, skewed.kind + "");
            S3Check.Result follow = S3Check.run(BASE, endpoint, "us-east-1", "backups", "",
                    real.accessKey, real.secret, S3Config.Addressing.AUTO, null);
            System.out.println("    (MinIO in eu-west-9 asked as us-east-1: " + follow.kind
                    + (follow.config == null ? "" : ", saved region " + follow.config.region) + ")");
            check("minio: a wrong region is followed (or named as wrong), never a false OK",
                    (follow.kind == S3Check.Kind.OK && follow.config != null
                            && follow.config.region.equals("eu-west-9"))
                            || follow.kind == S3Check.Kind.WRONG_REGION, follow.kind + "");
        }
    }
}

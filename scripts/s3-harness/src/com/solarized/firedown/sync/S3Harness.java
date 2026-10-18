package com.solarized.firedown.sync;

import org.json.JSONArray;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import okhttp3.HttpUrl;

/**
 * Drives the REAL S3 backup code: the signer against AWS's published
 * examples and botocore, then the client's pure parts.
 */
public final class S3Harness {

    private static int passed;
    private static final List<String> failed = new ArrayList<>();
    private static File fixtures;

    static final String AWS_KEY = "AKIAIOSFODNN7EXAMPLE";
    static final String AWS_SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    public static void main(String[] args) throws Exception {
        fixtures = new File(args[1]);

        run("sigv4: AWS examples", S3Harness::awsExamples);
        run("sigv4: botocore", S3Harness::botocore);
        run("client units", S3Harness::clientUnits);

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
    }

    private static S3Client.Refusal refusal(IOException e) {
        return e instanceof S3Client.RefusedException ? ((S3Client.RefusedException) e).refusal : null;
    }
}

package com.solarized.firedown.sync;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * An in-JVM S3 for the harness: one bucket, path-style and virtual-hosted,
 * that VERIFIES every request's SigV4 signature with its own, separately
 * written canonicalisation (so a request whose wire form differs from what
 * the client signed fails here, the way a real server fails it), checks the
 * payload hash against the body, and enforces the multipart rules the
 * strictest provider has (every part but the last at least 5 MiB AND the same
 * size, R2's rule). Switches for every failure the client must classify.
 */
final class FakeS3 implements HttpHandler {

    static final class Obj {
        final byte[] data;
        final long modified;
        final String etag;

        Obj(byte[] data, long modified) {
            this.data = data;
            this.modified = modified;
            this.etag = "\"" + md5Hex(data) + "\"";
        }
    }

    static final class Mpu {
        final String key;
        final long initiated;
        final Map<Integer, byte[]> parts = new TreeMap<>();

        Mpu(String key, long initiated) {
            this.key = key;
            this.initiated = initiated;
        }
    }

    final String baseHost;
    volatile String bucket = "backups";
    volatile String region = "us-east-1";
    volatile String accessKey = "AKIDHARNESS";
    volatile String secret = "harness/secret+key";
    final Map<String, Obj> objects = new ConcurrentHashMap<>();
    final Map<String, Mpu> uploads = new ConcurrentHashMap<>();
    private final AtomicInteger nextUpload = new AtomicInteger(1);

    // ---- switches ----
    /** Operations answered 403 AccessDenied: LIST, GET, PUT, DELETE, MPU. */
    final Set<String> deny = Collections.synchronizedSet(new HashSet<>());
    volatile boolean noBucket;
    volatile boolean quota;
    volatile boolean regionInError = true;
    volatile long clockOffsetMs;
    final AtomicInteger fail500 = new AtomicInteger();
    final AtomicInteger slowDown = new AtomicInteger();
    final AtomicInteger tooMany = new AtomicInteger();
    volatile int retryAfterSeconds = 2;
    /** A part number whose upload is cut mid-body once. */
    volatile int dropPartOnce;
    volatile boolean ignoreRange;
    volatile boolean wrongRange;
    volatile boolean noBatchDelete;
    volatile boolean pathStyleRefused;
    volatile boolean notS3;
    volatile boolean completeErrorIn200Once;
    /** Keys deleted right after a listing names them (vanished between the
     *  listing and the read). */
    final Set<String> vanishAfterList = Collections.synchronizedSet(new HashSet<>());
    /** Key suffixes whose GET always fails with 500. */
    volatile String failGetSuffix;
    /** Max keys per list page the server allows (forces pagination). */
    volatile int pageCap = 1000;

    // ---- records ----
    final List<String> log = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger badSignatures = new AtomicInteger();
    final AtomicInteger requests = new AtomicInteger();

    FakeS3(String baseHost) {
        this.baseHost = baseHost;
    }

    void reset() {
        objects.clear();
        uploads.clear();
        deny.clear();
        noBucket = false;
        quota = false;
        regionInError = true;
        clockOffsetMs = 0;
        fail500.set(0);
        slowDown.set(0);
        tooMany.set(0);
        retryAfterSeconds = 2;
        dropPartOnce = 0;
        ignoreRange = false;
        wrongRange = false;
        noBatchDelete = false;
        pathStyleRefused = false;
        notS3 = false;
        completeErrorIn200Once = false;
        vanishAfterList.clear();
        failGetSuffix = null;
        pageCap = 1000;
        region = "us-east-1";
        accessKey = "AKIDHARNESS";
        secret = "harness/secret+key";
        bucket = "backups";
        log.clear();
        badSignatures.set(0);
        requests.set(0);
    }

    int count(String prefix) {
        int n = 0;
        synchronized (log) {
            for (String l : log) {
                if (l.startsWith(prefix)) {
                    n++;
                }
            }
        }
        return n;
    }

    long now() {
        return System.currentTimeMillis() + clockOffsetMs;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            serve(ex);
        } catch (Dropped d) {
            // the connection is cut on purpose
        } catch (RuntimeException e) {
            e.printStackTrace(System.out);
            send(ex, 500, error("InternalError", "harness: " + e));
        } finally {
            ex.close();
        }
    }

    private static final class Dropped extends RuntimeException {
    }

    private void serve(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        String method = ex.getRequestMethod();
        String rawPath = ex.getRequestURI().getRawPath();
        String rawQuery = ex.getRequestURI().getRawQuery();
        Map<String, String> query = parseQuery(rawQuery);
        String host = ex.getRequestHeaders().getFirst("Host");
        String hostName = host == null ? "" : host.replaceAll(":\\d+$", "");

        if (notS3) {
            drain(ex);
            byte[] page = "<html><body>Hello</body></html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html");
            send(ex, 200, page);
            return;
        }

        // Which bucket and key: virtual-hosted when the host has the bucket
        // in front of the base host, else path-style.
        String bucketName;
        String key;
        boolean pathStyle;
        String decodedPath = decodePath(rawPath);
        if (hostName.endsWith("." + baseHost)) {
            bucketName = hostName.substring(0, hostName.length() - baseHost.length() - 1);
            key = decodedPath.startsWith("/") ? decodedPath.substring(1) : decodedPath;
            pathStyle = false;
        } else {
            String p = decodedPath.startsWith("/") ? decodedPath.substring(1) : decodedPath;
            int slash = p.indexOf('/');
            bucketName = slash < 0 ? p : p.substring(0, slash);
            key = slash < 0 ? "" : p.substring(slash + 1);
            pathStyle = true;
        }
        byte[] body = readBody(ex, method, key, query);

        String op = opOf(method, key, query);
        log.add(op + " " + key + (rawQuery == null ? "" : "?" + rawQuery));

        // ---- authentication: the server's own SigV4 check ----
        Headers h = ex.getRequestHeaders();
        String auth = h.getFirst("Authorization");
        Matcher m = auth == null ? null : AUTH.matcher(auth);
        if (m == null || !m.matches()) {
            send(ex, 403, error("AccessDenied", "no signature"));
            return;
        }
        String credAk = m.group(1);
        String credDate = m.group(2);
        String credRegion = m.group(3);
        String signedHeaders = m.group(4);
        String signature = m.group(5);
        if (!credAk.equals(accessKey)) {
            send(ex, 403, error("InvalidAccessKeyId",
                    "The AWS Access Key Id you provided does not exist in our records."));
            return;
        }
        String amzDate = h.getFirst("x-amz-date");
        long sent = parseAmzDate(amzDate);
        if (sent <= 0 || Math.abs(now() - sent) > 15 * 60 * 1000L) {
            ex.getResponseHeaders().set("Date", DateTimeFormatter.RFC_1123_DATE_TIME
                    .format(Instant.ofEpochMilli(now()).atOffset(ZoneOffset.UTC)));
            send(ex, 403, error("RequestTimeTooSkewed",
                    "The difference between the request time and the current time is too large."));
            return;
        }
        if (!credDate.equals(amzDate.substring(0, 8))) {
            send(ex, 403, error("SignatureDoesNotMatch", "date mismatch"));
            return;
        }
        String payloadHash = h.getFirst("x-amz-content-sha256");
        if (payloadHash == null || !payloadHash.equals(sha256Hex(body))) {
            send(ex, 400, error("XAmzContentSHA256Mismatch", "payload hash"));
            return;
        }
        String expected = sign(method, rawPath, rawQuery, h, signedHeaders, payloadHash,
                amzDate, credRegion);
        if (!expected.equals(signature)) {
            badSignatures.incrementAndGet();
            send(ex, 403, error("SignatureDoesNotMatch",
                    "The request signature we calculated does not match the signature you provided."));
            return;
        }

        // ---- the bucket ----
        if (pathStyle && pathStyleRefused) {
            send(ex, 404, error("NoSuchBucket", "path-style is not served here"));
            return;
        }
        if (noBucket || !bucketName.equals(bucket)) {
            send(ex, 404, error("NoSuchBucket", "The specified bucket does not exist"));
            return;
        }
        if (!credRegion.equals(region)) {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error>"
                    + "<Code>AuthorizationHeaderMalformed</Code><Message>"
                    + (regionInError ? "the region '" + credRegion + "' is wrong; expecting '"
                    + region + "'</Message><Region>" + region + "</Region>"
                    : "The authorization header is malformed; the region is wrong.</Message>")
                    + "</Error>";
            send(ex, 400, xml.getBytes(StandardCharsets.UTF_8));
            return;
        }

        // ---- injected failures ----
        if (fail500.get() > 0 && fail500.getAndDecrement() > 0) {
            send(ex, 500, error("InternalError", "We encountered an internal error."));
            return;
        }
        if (slowDown.get() > 0 && slowDown.getAndDecrement() > 0) {
            send(ex, 503, error("SlowDown", "Please reduce your request rate."));
            return;
        }
        if (tooMany.get() > 0 && tooMany.getAndDecrement() > 0) {
            ex.getResponseHeaders().set("Retry-After", Integer.toString(retryAfterSeconds));
            send(ex, 429, error("TooManyRequests", "slow down"));
            return;
        }
        String denyOp = op.startsWith("MPU") || op.startsWith("PART") ? "PUT" : op;
        if (deny.contains(denyOp) || deny.contains(op)) {
            send(ex, 403, error("AccessDenied", "Access Denied"));
            return;
        }

        switch (op) {
            case "LIST":
                list(ex, query);
                return;
            case "LISTMPU":
                listUploads(ex, query);
                return;
            case "BATCHDELETE":
                batchDelete(ex, body, h);
                return;
            case "PUT":
                if (quota) {
                    send(ex, 403, error("AccessDenied", "Cannot upload files, storage cap exceeded."));
                    return;
                }
                objects.put(key, new Obj(body, now()));
                ex.getResponseHeaders().set("ETag", objects.get(key).etag);
                send(ex, 200, new byte[0]);
                return;
            case "PART":
                part(ex, key, query, body);
                return;
            case "MPUCREATE": {
                if (quota) {
                    send(ex, 403, error("AccessDenied", "Cannot upload files, storage cap exceeded."));
                    return;
                }
                String id = "up-" + nextUpload.getAndIncrement() + "/x+y=";
                uploads.put(id, new Mpu(key, now()));
                send(ex, 200, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><InitiateMultipartUploadResult>"
                        + "<Bucket>" + bucket + "</Bucket><Key>" + esc(key) + "</Key><UploadId>"
                        + esc(id) + "</UploadId></InitiateMultipartUploadResult>")
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            case "MPUCOMPLETE":
                complete(ex, key, query, body);
                return;
            case "MPUABORT": {
                Mpu u = uploads.remove(query.get("uploadId"));
                if (u == null) {
                    send(ex, 404, error("NoSuchUpload", "The specified upload does not exist."));
                    return;
                }
                send(ex, 204, null);
                return;
            }
            case "GET":
            case "HEAD":
                get(ex, key, op.equals("HEAD"));
                return;
            case "DELETE":
                objects.remove(key);
                send(ex, 204, null);
                return;
            default:
                send(ex, 501, error("NotImplemented", op));
        }
    }

    private static final Pattern AUTH = Pattern.compile("AWS4-HMAC-SHA256 Credential=([^/]+)/(\\d{8})/"
            + "([^/]+)/s3/aws4_request, ?SignedHeaders=([a-z0-9;-]+), ?Signature=([0-9a-f]{64})");

    private static String opOf(String method, String key, Map<String, String> q) {
        if (key.isEmpty()) {
            if (method.equals("GET") && q.containsKey("uploads")) {
                return "LISTMPU";
            }
            if (method.equals("GET")) {
                return "LIST";
            }
            if (method.equals("POST") && q.containsKey("delete")) {
                return "BATCHDELETE";
            }
            return method + "-BUCKET";
        }
        switch (method) {
            case "PUT":
                return q.containsKey("uploadId") ? "PART" : "PUT";
            case "POST":
                if (q.containsKey("uploads")) {
                    return "MPUCREATE";
                }
                return q.containsKey("uploadId") ? "MPUCOMPLETE" : "POST";
            case "DELETE":
                return q.containsKey("uploadId") ? "MPUABORT" : "DELETE";
            default:
                return method;
        }
    }

    // ---- operations ----

    private void list(HttpExchange ex, Map<String, String> q) throws IOException {
        String prefix = q.getOrDefault("prefix", "");
        int max = Math.min(pageCap, Integer.parseInt(q.getOrDefault("max-keys", "1000")));
        String after = q.containsKey("continuation-token")
                ? new String(Base64.getDecoder().decode(q.get("continuation-token")), StandardCharsets.UTF_8)
                : null;
        List<String> keys = new ArrayList<>(new TreeMap<>(objects).keySet());
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>"
                + bucket + "</Name><Prefix>" + esc(prefix) + "</Prefix>");
        int n = 0;
        String last = null;
        boolean truncated = false;
        List<String> named = new ArrayList<>();
        for (String k : keys) {
            if (!k.startsWith(prefix) || (after != null && k.compareTo(after) <= 0)) {
                continue;
            }
            if (n == max) {
                truncated = true;
                break;
            }
            Obj o = objects.get(k);
            if (o == null) {
                continue;
            }
            xml.append("<Contents><Key>").append(esc(k)).append("</Key><LastModified>")
                    .append(Instant.ofEpochMilli(o.modified)).append("</LastModified><ETag>")
                    .append(esc(o.etag)).append("</ETag><Size>").append(o.data.length)
                    .append("</Size><StorageClass>STANDARD</StorageClass></Contents>");
            named.add(k);
            last = k;
            n++;
        }
        xml.append("<KeyCount>").append(n).append("</KeyCount><MaxKeys>").append(max)
                .append("</MaxKeys><IsTruncated>").append(truncated).append("</IsTruncated>");
        if (truncated) {
            xml.append("<NextContinuationToken>")
                    .append(Base64.getEncoder().encodeToString(last.getBytes(StandardCharsets.UTF_8)))
                    .append("</NextContinuationToken>");
        }
        xml.append("</ListBucketResult>");
        send(ex, 200, xml.toString().getBytes(StandardCharsets.UTF_8));
        for (String k : named) {
            if (vanishAfterList.remove(k)) {
                objects.remove(k);
            }
        }
    }

    private void listUploads(HttpExchange ex, Map<String, String> q) throws IOException {
        String prefix = q.getOrDefault("prefix", "");
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListMultipartUploadsResult><Bucket>" + bucket + "</Bucket>");
        for (Map.Entry<String, Mpu> e : new TreeMap<>(uploads).entrySet()) {
            if (!e.getValue().key.startsWith(prefix)) {
                continue;
            }
            xml.append("<Upload><Key>").append(esc(e.getValue().key)).append("</Key><UploadId>")
                    .append(esc(e.getKey())).append("</UploadId><Initiated>")
                    .append(Instant.ofEpochMilli(e.getValue().initiated)).append("</Initiated></Upload>");
        }
        xml.append("<IsTruncated>false</IsTruncated></ListMultipartUploadsResult>");
        send(ex, 200, xml.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void batchDelete(HttpExchange ex, byte[] body, Headers h) throws IOException {
        if (noBatchDelete) {
            send(ex, 501, error("NotImplemented", "A header you provided implies functionality "
                    + "that is not implemented"));
            return;
        }
        String md5 = h.getFirst("Content-MD5");
        if (md5 == null || !md5.equals(Base64.getEncoder().encodeToString(md5(body)))) {
            send(ex, 400, error("InvalidRequest", "Missing required header for this request: Content-MD5"));
            return;
        }
        String xml = new String(body, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<Key>(.*?)</Key>").matcher(xml);
        int n = 0;
        while (m.find()) {
            objects.remove(unesc(m.group(1)));
            n++;
        }
        if (n > 1000) {
            send(ex, 400, error("MalformedXML", "too many keys"));
            return;
        }
        send(ex, 200, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><DeleteResult></DeleteResult>"
                .getBytes(StandardCharsets.UTF_8));
    }

    private void part(HttpExchange ex, String key, Map<String, String> q, byte[] body)
            throws IOException {
        Mpu u = uploads.get(q.get("uploadId"));
        if (u == null || !u.key.equals(key)) {
            send(ex, 404, error("NoSuchUpload", "The specified upload does not exist."));
            return;
        }
        int n = Integer.parseInt(q.get("partNumber"));
        if (quota) {
            send(ex, 403, error("AccessDenied", "Cannot upload files, storage cap exceeded."));
            return;
        }
        u.parts.put(n, body);
        ex.getResponseHeaders().set("ETag", "\"" + md5Hex(body) + "\"");
        send(ex, 200, new byte[0]);
    }

    private void complete(HttpExchange ex, String key, Map<String, String> q, byte[] body)
            throws IOException {
        Mpu u = uploads.get(q.get("uploadId"));
        if (u == null || !u.key.equals(key)) {
            send(ex, 404, error("NoSuchUpload", "The specified upload does not exist."));
            return;
        }
        if (completeErrorIn200Once) {
            completeErrorIn200Once = false;
            send(ex, 200, error("InternalError", "We encountered an internal error. Please try again."));
            return;
        }
        String xml = new String(body, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<Part><PartNumber>(\\d+)</PartNumber><ETag>(.*?)</ETag></Part>")
                .matcher(xml);
        List<byte[]> parts = new ArrayList<>();
        int expect = 1;
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            byte[] p = u.parts.get(n);
            if (n != expect++ || p == null || !unesc(m.group(2)).equals("\"" + md5Hex(p) + "\"")) {
                send(ex, 400, error("InvalidPart", "One or more of the specified parts could not be found."));
                return;
            }
            parts.add(p);
        }
        for (int i = 0; i < parts.size() - 1; i++) {
            if (parts.get(i).length < 5 * 1024 * 1024) {
                send(ex, 400, error("EntityTooSmall", "Your proposed upload is smaller than the minimum."));
                return;
            }
            if (parts.get(i).length != parts.get(0).length) {
                send(ex, 400, error("InvalidPart", "All non-trailing parts must have the same length."));
                return;
            }
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            all.write(p);
        }
        uploads.remove(q.get("uploadId"));
        objects.put(key, new Obj(all.toByteArray(), now()));
        send(ex, 200, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><CompleteMultipartUploadResult>"
                + "<Key>" + esc(key) + "</Key><ETag>" + esc(objects.get(key).etag)
                + "</ETag></CompleteMultipartUploadResult>").getBytes(StandardCharsets.UTF_8));
    }

    private void get(HttpExchange ex, String key, boolean head) throws IOException {
        Obj o = objects.get(key);
        if (o == null) {
            if (head) {
                ex.sendResponseHeaders(404, -1);
            } else {
                send(ex, 404, error("NoSuchKey", "The specified key does not exist."));
            }
            return;
        }
        if (failGetSuffix != null && key.endsWith(failGetSuffix) && !head) {
            send(ex, 500, error("InternalError", "harness: persistent read failure"));
            return;
        }
        ex.getResponseHeaders().set("ETag", o.etag);
        if (head) {
            ex.getResponseHeaders().set("Content-Length", Integer.toString(o.data.length));
            ex.sendResponseHeaders(200, -1);
            return;
        }
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range != null && !ignoreRange) {
            Matcher m = Pattern.compile("bytes=(\\d+)-(\\d+)").matcher(range);
            if (m.matches()) {
                long start = Long.parseLong(m.group(1));
                long end = Math.min(Long.parseLong(m.group(2)), o.data.length - 1);
                if (start >= o.data.length) {
                    ex.getResponseHeaders().set("Content-Range", "bytes */" + o.data.length);
                    send(ex, 416, error("InvalidRange", "The requested range is not satisfiable"));
                    return;
                }
                long shown = wrongRange ? start + 1 : start;
                byte[] slice = new byte[(int) (end - start + 1)];
                System.arraycopy(o.data, (int) start, slice, 0, slice.length);
                ex.getResponseHeaders().set("Content-Range",
                        "bytes " + shown + "-" + end + "/" + o.data.length);
                send(ex, 206, slice);
                return;
            }
        }
        send(ex, 200, o.data);
    }

    // ---- the signature, written apart from S3Signer ----

    private String sign(String method, String rawPath, String rawQuery, Headers h,
                        String signedHeaders, String payloadHash, String amzDate, String region) {
        List<String> pairs = new ArrayList<>();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            for (String part : rawQuery.split("&")) {
                int eq = part.indexOf('=');
                String n = eq < 0 ? part : part.substring(0, eq);
                String v = eq < 0 ? "" : part.substring(eq + 1);
                pairs.add(awsEncode(urlDecode(n), true) + "=" + awsEncode(urlDecode(v), true));
            }
        }
        pairs.sort((a, b) -> {
            String na = a.substring(0, a.indexOf('='));
            String nb = b.substring(0, b.indexOf('='));
            int c = na.compareTo(nb);
            return c != 0 ? c : a.substring(a.indexOf('=') + 1).compareTo(b.substring(b.indexOf('=') + 1));
        });
        StringBuilder canonHeaders = new StringBuilder();
        for (String name : signedHeaders.split(";")) {
            String v = h.getFirst(name);
            canonHeaders.append(name).append(':')
                    .append(v == null ? "" : v.trim().replaceAll("\\s+", " ")).append('\n');
        }
        String canonical = method + "\n" + awsEncode(decodePath(rawPath), false) + "\n"
                + String.join("&", pairs) + "\n" + canonHeaders + "\n" + signedHeaders + "\n"
                + payloadHash;
        String scope = amzDate.substring(0, 8) + "/" + region + "/s3/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] k = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), amzDate.substring(0, 8));
        k = hmac(k, region);
        k = hmac(k, "s3");
        k = hmac(k, "aws4_request");
        return hex(hmac(k, toSign));
    }

    private static String awsEncode(String s, boolean slash) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || (c == '/' && !slash)) {
                sb.append((char) c);
            } else {
                sb.append(String.format("%%%02X", c));
            }
        }
        return sb.toString();
    }

    /** Percent-decodes a path ('+' stays a '+'). */
    private static String decodePath(String raw) {
        return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new TreeMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            out.put(urlDecode(eq < 0 ? part : part.substring(0, eq)),
                    eq < 0 ? "" : urlDecode(part.substring(eq + 1)));
        }
        return out;
    }

    private static long parseAmzDate(String s) {
        if (s == null || s.length() != 16) {
            return 0;
        }
        try {
            return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
                    .parse(s, Instant::from).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ---- wire helpers ----

    private byte[] readBody(HttpExchange ex, String method, String key, Map<String, String> q)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = ex.getRequestBody();
        byte[] buf = new byte[64 * 1024];
        long declared = -1;
        String cl = ex.getRequestHeaders().getFirst("Content-Length");
        if (cl != null) {
            declared = Long.parseLong(cl);
        }
        boolean drop = method.equals("PUT") && q.containsKey("partNumber")
                && dropPartOnce > 0 && Integer.parseInt(q.get("partNumber")) == dropPartOnce;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (drop && declared > 0 && out.size() >= declared / 2) {
                dropPartOnce = 0;
                log.add("DROPPED part " + q.get("partNumber"));
                throw new Dropped();
            }
        }
        return out.toByteArray();
    }

    private static void drain(HttpExchange ex) throws IOException {
        InputStream in = ex.getRequestBody();
        byte[] buf = new byte[8192];
        while (in.read(buf) != -1) {
            // discard
        }
    }

    private static void send(HttpExchange ex, int code, byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            ex.sendResponseHeaders(code, -1);
            return;
        }
        if (ex.getResponseHeaders().getFirst("Content-Type") == null) {
            ex.getResponseHeaders().set("Content-Type", "application/xml");
        }
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    static byte[] error(String code, String message) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code + "</Code><Message>"
                + esc(message) + "</Message><RequestId>harness</RequestId></Error>")
                .getBytes(StandardCharsets.UTF_8);
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static String unesc(String s) {
        return s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&");
    }

    static byte[] md5(byte[] b) {
        try {
            return MessageDigest.getInstance("MD5").digest(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String md5Hex(byte[] b) {
        return hex(md5(b));
    }

    static String sha256Hex(byte[] b) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }
}

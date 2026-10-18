package com.solarized.firedown.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The S3 wire, and nothing else: signed REST calls over OkHttp (no SDK), with
 * the server's answers mapped to the {@link BackupStore} failure classes.
 * {@link S3Store} puts the backup layout on top; {@link S3Check} uses it to
 * test a bucket before anything is saved.
 *
 * <p><b>Only calls every listed provider has:</b> PUT / GET (with Range) /
 * HEAD / DELETE of an object, {@code ListObjectsV2}, {@code DeleteObjects}
 * (with a single-delete fallback for a provider that answers it with 501),
 * the four multipart calls and {@code ListMultipartUploads}. Backblaze B2,
 * Cloudflare R2, Wasabi and MinIO all implement these; nothing here relies on
 * checksum headers, tagging, ACLs or object lock, which R2 and B2 lack.
 *
 * <p><b>Signing:</b> {@link S3Signer}, the real payload SHA-256 on every
 * request. The Host header is set explicitly to the value signed, the path is
 * encoded once by the signer's rules and sent as is, and the query sent IS the
 * canonical query, so what is signed and what goes on the wire cannot differ.
 * Redirects are not followed: a redirected S3 request would carry a signature
 * for the wrong host, and S3's own redirects (301 PermanentRedirect, 307) mean
 * "wrong region or endpoint", which is an answer, not a detour.
 *
 * <p><b>Failure classes.</b> Wrong keys, a missing bucket, a denied
 * permission, a wrong region and a refused certificate throw
 * {@link RefusedException} (an {@link BackupStore.UnreachableException}, so
 * the workers say "Reconnect"); a storage cap or quota throws
 * {@link BackupStore.FullException}; 5xx, 429, {@code SlowDown} and dropped
 * connections are retried here up to {@link #MAX_ATTEMPTS} times (honouring
 * {@code Retry-After}, capped at {@link #MAX_RETRY_AFTER_MS}) and then thrown
 * as a plain {@link IOException}, which the worker retries with its backoff.
 * Every request body here is a byte array, so a retry resends the same bytes.
 *
 * <p><b>Clock skew.</b> S3 refuses a request whose {@code x-amz-date} is more
 * than ~15 minutes from its own clock ({@code RequestTimeTooSkewed}). The
 * first such answer sets an offset from the server's {@code Date} header and
 * the request is signed again; the offset is kept for this client's
 * lifetime, so a phone with a wrong clock pays one extra request per process.
 *
 * <p>Nothing here logs: a request carries the access key id and a signature
 * that is as good as the secret for the minutes it is valid.
 */
public final class S3Client {

    /** Why a request was refused in a way only the user can fix. */
    public enum Refusal {
        /** The access key id is unknown or the signature (the secret) is wrong. */
        AUTH,
        /** The bucket does not exist at this endpoint. */
        NO_BUCKET,
        /** The keys are valid but may not do this (read, write, delete, list). */
        DENIED,
        /** The bucket lives in another region; {@link RefusedException#region}
         *  names it when the server did. */
        REGION,
        /** The server's certificate is not one the phone or the pin accepts. */
        CERT,
    }

    /** A refusal the user must act on, with the reason for the sign-in page. */
    public static final class RefusedException extends BackupStore.UnreachableException {
        @NonNull
        public final Refusal refusal;
        /** The region the server said the bucket is in, for {@link Refusal#REGION}. */
        @Nullable
        public final String region;

        RefusedException(@NonNull Refusal refusal, @Nullable String region, String message) {
            super(message);
            this.refusal = refusal;
            this.region = region;
        }

        RefusedException(@NonNull Refusal refusal, String message, Throwable cause) {
            super(message, cause);
            this.refusal = refusal;
            this.region = null;
        }
    }

    /** Any other answer the server gave: the status and the S3 error code
     *  ("" when the body had none). A plain failure a retry may fix. */
    public static final class ServerException extends IOException {
        public final int status;
        @NonNull
        public final String code;

        ServerException(int status, @NonNull String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    /** One key of a listing. */
    static final class ObjectInfo {
        final String key;
        final long size;
        /** Epoch ms; 0 when the server said nothing readable. */
        final long modified;
        @Nullable
        final String etag;

        ObjectInfo(String key, long size, long modified, @Nullable String etag) {
            this.key = key;
            this.size = size;
            this.modified = modified;
            this.etag = etag;
        }
    }

    /** One page of {@code ListObjectsV2}. */
    static final class ListPage {
        final List<ObjectInfo> objects;
        /** The continuation token of the next page, or null on the last. */
        @Nullable
        final String next;

        ListPage(List<ObjectInfo> objects, @Nullable String next) {
            this.objects = objects;
            this.next = next;
        }
    }

    /** One unfinished multipart upload. */
    static final class Upload {
        final String key;
        final String uploadId;
        /** Epoch ms; 0 when the server said nothing readable. */
        final long initiated;

        Upload(String key, String uploadId, long initiated) {
            this.key = key;
            this.uploadId = uploadId;
            this.initiated = initiated;
        }
    }

    /** How a retry waits; replaced by the harness so it does not sleep. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** The phone's clock. Only the harness replaces it, to play a phone
     *  whose clock is wrong (the server's Date header is then what puts the
     *  signatures right). */
    static volatile LongSupplier phoneClock = System::currentTimeMillis;

    static final int MAX_ATTEMPTS = 3;
    static final long MAX_RETRY_AFTER_MS = 30_000;
    private static final long BACKOFF_MS = 1_000;
    private static final long SKEW_SUSPECT_MS = 10 * 60_000;
    /** An error body is a few hundred bytes; read no more than this. */
    private static final long MAX_ERROR_BYTES = 64 * 1024;
    /** A list page of 1000 keys is a few hundred KB. */
    private static final long MAX_LIST_BYTES = 8L * 1024 * 1024;
    /** S3's own cap on keys per DeleteObjects call. */
    static final int DELETE_BATCH = 1000;
    /** Pages of {@code ListMultipartUploads} read at most (1000 uploads each). */
    static final int MAX_UPLOAD_PAGES = 100;
    /** Elements one XML answer may hold: a full listing page is ~10 000. */
    static final int MAX_XML_NODES = 200_000;
    private static final MediaType OCTET = MediaType.get("application/octet-stream");
    private static final MediaType XML = MediaType.get("application/xml");

    private final OkHttpClient client;
    private final ServerTrust trust;
    private final S3Config config;
    private final HttpUrl endpoint;
    /** Server clock minus phone clock, learned from a RequestTimeTooSkewed. */
    private final AtomicLong skewMillis = new AtomicLong();
    /** Set once a DeleteObjects call answered "not implemented". */
    private volatile boolean batchDeleteUnsupported;
    volatile Sleeper sleeper = Thread::sleep;

    /**
     * @param base the app's client (its DNS and pool), or null for a plain one
     */
    public S3Client(@Nullable OkHttpClient base, @NonNull S3Config config) {
        this.config = config;
        this.endpoint = config.endpointUrl();
        this.trust = new ServerTrust(config.pinSha256);
        this.client = trust.newBuilder(base)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    @NonNull
    S3Config config() {
        return config;
    }

    /** The certificate the last refused TLS handshake presented, or null. */
    @Nullable
    public ServerTrust.CertInfo rejectedCert() {
        return trust.rejected();
    }

    /** The learned clock offset (for the harness). */
    long skewMillis() {
        return skewMillis.get();
    }

    /** Now by the server's clock: the phone's plus the learned offset. */
    long serverNow() {
        return phoneClock.getAsLong() + skewMillis.get();
    }

    // ---- objects ----

    /** PUT of a whole object. */
    void putObject(@NonNull String key, @NonNull byte[] body, int offset, int length,
                   @NonNull String what) throws IOException {
        Call call = new Call("PUT", key, what);
        call.body(body, offset, length, OCTET);
        close(call.execute());
    }

    /** GET of a small object, at most {@code max} bytes; null when there is
     *  no such key. */
    @Nullable
    byte[] getObjectBytes(@NonNull String key, long max, @NonNull String what) throws IOException {
        Call call = new Call("GET", key, what).allowMissing();
        Response response = call.execute();
        if (response == null) {
            return null;
        }
        try (Response r = response; InputStream in = r.body().byteStream()) {
            return BackupLayout.readAll(in, max);
        } catch (IOException e) {
            throw trust.translate(e);
        }
    }

    /** GET of a whole object as a stream; the stream owns the response.
     *  {@link FileNotFoundException} when there is no such key. */
    @NonNull
    InputStream openObject(@NonNull String key, @NonNull String what) throws IOException {
        Response response = new Call("GET", key, what).allowMissing().execute();
        if (response == null) {
            throw new FileNotFoundException(what + ": not in the bucket");
        }
        ResponseBody body = response.body();
        return new FilterInputStream(body.byteStream()) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                try {
                    return super.read(b, off, len);
                } catch (IOException e) {
                    throw trust.translate(e);
                }
            }

            @Override
            public void close() {
                response.close();
            }
        };
    }

    /**
     * {@code length} bytes at {@code offset} through a ranged GET; fewer at
     * the end of the object, none past it. A server that ignores the range
     * and answers 200 still gives the right bytes (skipped to, then cut off).
     * A 206 that starts somewhere else is refused rather than handed on as
     * the bytes asked for. A connection dropped mid-body is retried like any
     * transient failure: the whole range is asked for again.
     */
    @NonNull
    byte[] getRange(@NonNull String key, long offset, int length, @NonNull String what)
            throws IOException {
        if (length <= 0) {
            return new byte[0];
        }
        IOException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Call call = new Call("GET", key, what).allowMissing().header("Range",
                    "bytes=" + offset + "-" + (offset + length - 1));
            call.rangeNotSatisfiableIsEmpty();
            Response response = call.execute();
            if (response == null) {
                throw new FileNotFoundException(what + ": not in the bucket");
            }
            try (Response r = response; InputStream in = r.body().byteStream()) {
                int code = r.code();
                if (code == 416) {
                    return new byte[0];
                }
                if (code == 206) {
                    long start = rangeStart(r.header("Content-Range"));
                    if (start != offset) {
                        throw new ServerException(206, "", what + ": server sent bytes from "
                                + start + ", asked for " + offset);
                    }
                } else if (!skipFully(in, offset)) {
                    return new byte[0];
                }
                return readUpTo(in, length);
            } catch (ServerException e) {
                throw e;
            } catch (IOException e) {
                IOException t = trust.translate(e);
                if (t instanceof BackupStore.UnreachableException) {
                    throw t;
                }
                last = t;
                if (attempt + 1 < MAX_ATTEMPTS) {
                    backoff(attempt, null);
                }
            }
        }
        throw last;
    }

    /** HEAD of an object: true when it is there, false on 404. */
    boolean headObject(@NonNull String key, @NonNull String what) throws IOException {
        Response response = new Call("HEAD", key, what).allowMissing().execute();
        if (response == null) {
            return false;
        }
        close(response);
        return true;
    }

    /** DELETE of one object; a missing key is not an error (S3 says 204). */
    void deleteObject(@NonNull String key, @NonNull String what) throws IOException {
        Response response = new Call("DELETE", key, what).allowMissing().execute();
        if (response != null) {
            close(response);
        }
    }

    /**
     * Deletes {@code keys}, {@link #DELETE_BATCH} per {@code DeleteObjects}
     * call (quiet mode: the answer lists only failures). A provider that does
     * not have the call (501 NotImplemented) gets single DELETEs from then on.
     * A key that failed for any reason but "no such key" fails the whole
     * call, so a delete-all is never silently partial.
     */
    void deleteObjects(@NonNull List<String> keys, @NonNull String what) throws IOException {
        for (int i = 0; i < keys.size(); i += DELETE_BATCH) {
            List<String> batch = keys.subList(i, Math.min(keys.size(), i + DELETE_BATCH));
            if (batchDeleteUnsupported || batch.size() == 1) {
                for (String key : batch) {
                    deleteObject(key, what);
                }
                continue;
            }
            try {
                deleteBatch(batch, what);
            } catch (ServerException e) {
                if (!isNotImplemented(e)) {
                    throw e;
                }
                batchDeleteUnsupported = true;
                for (String key : batch) {
                    deleteObject(key, what);
                }
            }
        }
    }

    private void deleteBatch(List<String> batch, String what) throws IOException {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Delete><Quiet>true</Quiet>");
        for (String key : batch) {
            xml.append("<Object><Key>").append(escapeXml(key)).append("</Key></Object>");
        }
        xml.append("</Delete>");
        byte[] body = xml.toString().getBytes(StandardCharsets.UTF_8);
        Call call = new Call("POST", null, what).query("delete", "");
        call.body(body, 0, body.length, XML);
        // Required by AWS for this call (any other checksum header would do,
        // but MD5 is the one every provider knows).
        call.header("Content-MD5", md5Base64(body));
        Node result;
        try (Response r = call.execute()) {
            result = parseXml(r, MAX_LIST_BYTES, what);
        }
        if ("Error".equals(result.name)) {
            // An <Error> inside a 200, as CompleteMultipartUpload can answer:
            // nothing was deleted.
            throw classify(200, result.text("Code"), result.text("Message"),
                    result.text("Region"), what);
        }
        if (!"DeleteResult".equals(result.name)) {
            throw new ServerException(200, "", what + ": not a delete result");
        }
        for (Node error : result.children("Error")) {
            String code = error.text("Code");
            if (!"NoSuchKey".equals(code)) {
                throw new ServerException(200, code, what + ": " + code + " on a key");
            }
        }
    }

    private static boolean isNotImplemented(ServerException e) {
        return e.status == 501 || "NotImplemented".equals(e.code)
                || (e.status == 405 && !"AccessDenied".equals(e.code));
    }

    // ---- listing ----

    /** One page of {@code ListObjectsV2} under {@code prefix}. */
    @NonNull
    ListPage listObjects(@NonNull String prefix, @Nullable String token, int maxKeys,
                         @NonNull String what) throws IOException {
        Call call = new Call("GET", null, what)
                .query("list-type", "2")
                .query("prefix", prefix)
                .query("max-keys", Integer.toString(maxKeys));
        if (token != null) {
            call.query("continuation-token", token);
        }
        Node result;
        try (Response r = call.execute()) {
            result = parseXml(r, MAX_LIST_BYTES, what);
        }
        if (!"ListBucketResult".equals(result.name)) {
            // XML, but not a bucket listing: something other than S3 answered.
            throw new ServerException(200, "", what + ": not a bucket listing");
        }
        List<ObjectInfo> out = new ArrayList<>();
        for (Node c : result.children("Contents")) {
            String key = c.text("Key");
            if (key.isEmpty()) {
                continue;
            }
            String etag = c.text("ETag");
            out.add(new ObjectInfo(key, parseLong(c.text("Size")), parseIso(c.text("LastModified")),
                    etag.isEmpty() ? null : etag));
        }
        boolean truncated = "true".equalsIgnoreCase(result.text("IsTruncated"));
        String next = result.text("NextContinuationToken");
        if (truncated && next.isEmpty()) {
            // More to come but no way to ask for it (a server answering the
            // V1 shape): a short list would hide backups from dedup and the
            // Backups list, so it is an error, never an end.
            throw new ServerException(200, "", what + ": a truncated listing with no "
                    + "continuation token");
        }
        if (!truncated) {
            next = null;
        }
        return new ListPage(out, next);
    }

    /** Every unfinished multipart upload under {@code prefix}, at most
     *  {@code max}. */
    @NonNull
    List<Upload> listUploads(@NonNull String prefix, int max, @NonNull String what)
            throws IOException {
        List<Upload> out = new ArrayList<>();
        String keyMarker = null;
        String idMarker = null;
        int emptyPages = 0;
        for (int page = 0; out.size() < max; page++) {
            if (page >= MAX_UPLOAD_PAGES) {
                // A truncated listing that never ends: a server bug, or one
                // that hands out fresh markers forever.
                throw new ServerException(200, "", what + ": more than " + MAX_UPLOAD_PAGES
                        + " pages of unfinished uploads");
            }
            Call call = new Call("GET", null, what).query("uploads", "").query("prefix", prefix);
            if (keyMarker != null) {
                call.query("key-marker", keyMarker);
            }
            if (idMarker != null) {
                call.query("upload-id-marker", idMarker);
            }
            Node result;
            try (Response r = call.execute()) {
                result = parseXml(r, MAX_LIST_BYTES, what);
            }
            int before = out.size();
            for (Node u : result.children("Upload")) {
                String key = u.text("Key");
                String id = u.text("UploadId");
                if (!key.isEmpty() && !id.isEmpty()) {
                    out.add(new Upload(key, id, parseIso(u.text("Initiated"))));
                }
            }
            if (!"true".equalsIgnoreCase(result.text("IsTruncated"))) {
                break;
            }
            // A truncated page with nothing in it twice running is not
            // progress: the next marker is the server's invention.
            emptyPages = out.size() == before ? emptyPages + 1 : 0;
            if (emptyPages >= 2) {
                break;
            }
            String nextKey = result.text("NextKeyMarker");
            String nextId = result.text("NextUploadIdMarker");
            if (nextKey.isEmpty() || (nextKey.equals(keyMarker) && nextId.equals(idMarker))) {
                break;
            }
            keyMarker = nextKey;
            idMarker = nextId;
        }
        return out;
    }

    // ---- multipart ----

    /** CreateMultipartUpload; returns the upload id. */
    @NonNull
    String createMultipart(@NonNull String key, @NonNull String what) throws IOException {
        Call call = new Call("POST", key, what).query("uploads", "");
        call.body(new byte[0], 0, 0, OCTET);
        Node result;
        try (Response r = call.execute()) {
            result = parseXml(r, MAX_ERROR_BYTES, what);
        }
        String id = result.text("UploadId");
        if (id.isEmpty()) {
            throw new ServerException(200, "", what + ": no upload id in the answer");
        }
        return id;
    }

    /** UploadPart; returns the part's ETag exactly as the server sent it. */
    @NonNull
    String uploadPart(@NonNull String key, @NonNull String uploadId, int partNumber,
                      @NonNull byte[] buf, int offset, int length, @NonNull String what)
            throws IOException {
        Call call = new Call("PUT", key, what)
                .query("partNumber", Integer.toString(partNumber))
                .query("uploadId", uploadId);
        call.body(buf, offset, length, OCTET);
        try (Response r = call.execute()) {
            String etag = r.header("ETag");
            if (etag == null || etag.isEmpty()) {
                throw new ServerException(r.code(), "", what + ": no ETag for part " + partNumber);
            }
            return etag;
        }
    }

    /**
     * CompleteMultipartUpload. S3 can answer 200 and still carry an
     * {@code <Error>} (it sends the status before it knows), so the body is
     * read either way.
     */
    void completeMultipart(@NonNull String key, @NonNull String uploadId,
                           @NonNull List<String> etags, @NonNull String what) throws IOException {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<CompleteMultipartUpload>");
        for (int i = 0; i < etags.size(); i++) {
            xml.append("<Part><PartNumber>").append(i + 1).append("</PartNumber><ETag>")
                    .append(escapeXml(etags.get(i))).append("</ETag></Part>");
        }
        xml.append("</CompleteMultipartUpload>");
        byte[] body = xml.toString().getBytes(StandardCharsets.UTF_8);
        Call call = new Call("POST", key, what).query("uploadId", uploadId);
        call.body(body, 0, body.length, XML);
        Node result;
        try (Response r = call.execute()) {
            result = parseXml(r, MAX_ERROR_BYTES, what);
        }
        if ("Error".equals(result.name)) {
            throw classify(200, result.text("Code"), result.text("Message"),
                    result.text("Region"), what);
        }
    }

    /** AbortMultipartUpload; an upload already gone is not an error. */
    void abortMultipart(@NonNull String key, @NonNull String uploadId, @NonNull String what)
            throws IOException {
        Response response = new Call("DELETE", key, what).query("uploadId", uploadId)
                .allowMissing().execute();
        if (response != null) {
            close(response);
        }
    }

    // ---- one signed request ----

    /** A request being built: method, key (null = the bucket), query, body,
     *  unsigned extra headers. */
    private final class Call {
        final String method;
        @Nullable
        final String key;
        final String what;
        final List<String[]> query = new ArrayList<>();
        final List<String[]> headers = new ArrayList<>();
        byte[] body;
        int offset;
        int length;
        MediaType type;
        boolean allowMissing;
        boolean rangeNotSatisfiableIsEmpty;

        Call(String method, @Nullable String key, String what) {
            this.method = method;
            this.key = key;
            this.what = what;
        }

        Call query(String name, String value) {
            query.add(new String[]{name, value});
            return this;
        }

        Call header(String name, String value) {
            headers.add(new String[]{name, value});
            return this;
        }

        void body(byte[] body, int offset, int length, MediaType type) {
            this.body = body;
            this.offset = offset;
            this.length = length;
            this.type = type;
        }

        /** A 404 NoSuchKey answers null instead of throwing. */
        Call allowMissing() {
            allowMissing = true;
            return this;
        }

        void rangeNotSatisfiableIsEmpty() {
            rangeNotSatisfiableIsEmpty = true;
        }

        /**
         * Sends the request, signing it afresh on every attempt (a retry is
         * a new x-amz-date), and returns the response of a 2xx (or a 416 when
         * asked). Null for a missing key when {@link #allowMissing}.
         */
        @Nullable
        Response execute() throws IOException {
            String payloadHash = body == null
                    ? S3Signer.EMPTY_SHA256 : S3Signer.sha256Hex(body, offset, length);
            boolean skewFixed = false;
            int attempt = 0;
            while (true) {
                Request request = build(payloadHash);
                Response response;
                try {
                    response = client.newCall(request).execute();
                } catch (IOException | RuntimeException failure) {
                    // A RuntimeException out of OkHttp is still the server's
                    // doing: its retry step parses a 503's Retry-After with
                    // Integer.valueOf, so "Retry-After: 4294967296" throws
                    // NumberFormatException. Uncaught it would end a backup
                    // with no retry, or kill the process on a pool thread.
                    IOException e = failure instanceof IOException ? (IOException) failure
                            : new IOException(what + ": " + failure.getClass().getSimpleName(),
                            failure);
                    IOException t = trust.translate(e);
                    boolean interrupted = e instanceof InterruptedIOException
                            && Thread.currentThread().isInterrupted();
                    if (t instanceof BackupStore.UnreachableException || interrupted
                            || attempt + 1 >= MAX_ATTEMPTS) {
                        throw t;
                    }
                    backoff(attempt++, null);
                    continue;
                }
                int status = response.code();
                if (status >= 200 && status < 300) {
                    return response;
                }
                if (status == 416 && rangeNotSatisfiableIsEmpty) {
                    return response;
                }
                Node error;
                try (Response r = response) {
                    error = readError(r);
                }
                String code = error.text("Code");
                if (status == 404 && allowMissing && !"NoSuchBucket".equals(code)) {
                    return null;
                }
                // A HEAD answer has no body, so a skewed clock arrives as a
                // bare 403: judged by the Date header instead, or a stream
                // opened first in a fresh process reads as access denied.
                boolean skewed = "RequestTimeTooSkewed".equals(code)
                        || (status == 403 && code.isEmpty() && dateFarOff(response));
                if (skewed && !skewFixed) {
                    skewFixed = true;
                    if (learnSkew(response, error)) {
                        continue;
                    }
                }
                if (isTransient(status, code) && attempt + 1 < MAX_ATTEMPTS) {
                    backoff(attempt++, response.header("Retry-After"));
                    continue;
                }
                String region = error.text("Region");
                if (region.isEmpty()) {
                    String header = response.header("x-amz-bucket-region");
                    region = header == null ? "" : header;
                }
                throw classify(status, code, error.text("Message"), region, what);
            }
        }

        private Request build(String payloadHash) {
            HttpUrl url = url(key, S3Signer.canonicalQuery(query));
            String amzDate = S3Signer.amzDate(serverNow());
            String host = hostHeader(url);
            TreeMap<String, String> signed = new TreeMap<>();
            signed.put("host", host);
            signed.put("x-amz-content-sha256", payloadHash);
            signed.put("x-amz-date", amzDate);
            S3Signer.Signed signature = S3Signer.sign(method, url.encodedPath(), query, signed,
                    payloadHash, amzDate, config.region, config.accessKeyId, config.secret);
            RequestBody requestBody = body == null
                    ? null : RequestBody.create(body, type, offset, length);
            if (requestBody == null && ("PUT".equals(method) || "POST".equals(method))) {
                requestBody = RequestBody.create(new byte[0], OCTET);
            }
            Request.Builder b = new Request.Builder().url(url).method(method, requestBody)
                    .header("Host", host)
                    .header("x-amz-content-sha256", payloadHash)
                    .header("x-amz-date", amzDate)
                    .header("Authorization", signature.authorization);
            for (String[] h : headers) {
                b.header(h[0], h[1]);
            }
            return b.build();
        }
    }

    /**
     * The URL of {@code key} (null = the bucket itself), with
     * {@code encodedQuery} as the query (null or "" for none). Path-style:
     * {@code endpoint/bucket/key}; virtual-hosted: {@code bucket.host/key}.
     */
    @NonNull
    HttpUrl url(@Nullable String key, @Nullable String encodedQuery) {
        // The base path re-encoded the way the server canonicalizes it: OkHttp
        // leaves ( ) ! * as typed, which S3's UriEncode escapes, and the
        // signature would be over a different path than the one checked.
        StringBuilder path = new StringBuilder();
        for (String segment : endpoint.pathSegments()) {
            if (!segment.isEmpty()) {
                path.append('/').append(S3Signer.encode(segment, true));
            }
        }
        HttpUrl.Builder b = endpoint.newBuilder().query(null).fragment(null);
        if (config.pathStyle) {
            path.append('/').append(S3Signer.encode(config.bucket, true));
            if (key != null) {
                path.append('/').append(S3Signer.encodePath(key));
            }
        } else {
            b.host(config.bucket + "." + endpoint.host());
            path.append('/');
            if (key != null) {
                path.append(S3Signer.encodePath(key));
            }
        }
        b.encodedPath(path.toString());
        b.encodedQuery(encodedQuery == null || encodedQuery.isEmpty() ? null : encodedQuery);
        return b.build();
    }

    /** The Host header a request to {@code url} carries: the port only when
     *  it is not the scheme's own, an IPv6 literal in brackets. */
    @NonNull
    static String hostHeader(@NonNull HttpUrl url) {
        String host = url.host();
        if (host.indexOf(':') >= 0) {
            host = "[" + host + "]";
        }
        return url.port() == HttpUrl.defaultPort(url.scheme()) ? host : host + ":" + url.port();
    }

    // ---- answers ----

    private static boolean isTransient(int status, String code) {
        if (status >= 500 || status == 429) {
            // 501 is "not implemented" and 507 "out of space": answers, not
            // hiccups.
            return status != 501 && status != 507;
        }
        return "SlowDown".equals(code) || "RequestTimeout".equals(code)
                || "InternalError".equals(code) || "ServiceUnavailable".equals(code);
    }

    /** The failure class of an S3 error (see the class doc). */
    static IOException classify(int status, String code, String message, String region,
                                String what) {
        String c = code == null ? "" : code;
        String m = message == null ? "" : message;
        if (isQuota(status, c, m)) {
            return new BackupStore.FullException(what + ": the storage is full (" + c + ")");
        }
        switch (c) {
            case "InvalidAccessKeyId":
            case "SignatureDoesNotMatch":
            case "InvalidToken":
            case "ExpiredToken":
            case "InvalidSecurity":
            case "InvalidClientTokenId":
                return new RefusedException(Refusal.AUTH, null, what + ": " + c);
            case "AuthorizationHeaderMalformed": {
                // The same code for a malformed credential and for a wrong
                // region; the region one names the right region in <Region>
                // (AWS) or in its message ("... expecting 'eu-west-1'").
                String named = region.isEmpty() ? expectedRegion(m) : region;
                if (!named.isEmpty()) {
                    return new RefusedException(Refusal.REGION, named, what + ": " + c);
                }
                if (m.toLowerCase(Locale.ROOT).contains("region")) {
                    return new RefusedException(Refusal.REGION, null, what + ": " + c);
                }
                return new RefusedException(Refusal.AUTH, null, what + ": " + c);
            }
            case "PermanentRedirect":
            case "TemporaryRedirect":
            case "IllegalLocationConstraintException":
            case "InvalidRegion":
            case "AuthorizationQueryParametersError":
                return new RefusedException(Refusal.REGION, region.isEmpty() ? null : region,
                        what + ": " + c);
            case "NoSuchBucket":
            case "InvalidBucketName":
                return new RefusedException(Refusal.NO_BUCKET, null, what + ": " + c);
            case "AccessDenied":
            case "AllAccessDisabled":
            case "AccountProblem":
            case "Forbidden":
                return new RefusedException(Refusal.DENIED, null, what + ": " + c);
            case "RequestTimeTooSkewed":
                return new ServerException(status, c, what + ": the phone's clock is too far off");
            default:
                break;
        }
        if (status == 301 || status == 307) {
            return new RefusedException(Refusal.REGION, region.isEmpty() ? null : region,
                    what + ": HTTP " + status);
        }
        if (status == 401) {
            return new RefusedException(Refusal.AUTH, null, what + ": HTTP 401");
        }
        if (status == 403) {
            return new RefusedException(Refusal.DENIED, null, what + ": HTTP 403" + codeSuffix(c));
        }
        // A 404 here is never "no such key": every read that may miss a key
        // asks for that answer (allowMissing). Anything else that 404s (an
        // upload id the server dropped, NoSuchUpload) is a failure a retry of
        // the whole file fixes, and must NOT read as FileNotFoundException,
        // which the backup worker takes to mean the SOURCE file is gone.
        return new ServerException(status, c, what + ": HTTP " + status + codeSuffix(c));
    }

    /**
     * A storage cap or quota, by code where one exists (MinIO's, the generic
     * ones) and by message where providers say it in words under a generic
     * code: B2 answers a reached storage cap with 403 AccessDenied "Cannot
     * upload files, storage cap exceeded".
     */
    static boolean isQuota(int status, String code, String message) {
        if (status == 507) {
            return true;
        }
        switch (code) {
            case "QuotaExceeded":
            case "StorageQuotaExceeded":
            case "XMinioStorageFull":
            case "XMinioAdminBucketQuotaExceeded":
            case "InsufficientStorage":
            case "StorageLimitExceeded":
                return true;
            default:
                break;
        }
        if (status == 429 || status >= 500) {
            // A rate limit or an outage may well mention a "quota"; it is not
            // a full bucket, and "Storage full" would end the backup for good.
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("cap exceeded") || m.contains("storage cap") || m.contains("quota")
                || m.contains("storage limit") || m.contains("insufficient storage")
                || m.contains("out of space");
    }

    private static final Pattern EXPECTING = Pattern.compile("expecting '([a-z0-9-]+)'");

    /** The region an AuthorizationHeaderMalformed message names, or "". */
    static String expectedRegion(String message) {
        Matcher matcher = EXPECTING.matcher(message);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String codeSuffix(String code) {
        return code.isEmpty() ? "" : " " + code;
    }

    /** Learns the server's clock from its Date header (else the body's
     *  ServerTime); false when neither says. */
    /** The server's Date header is further from the time a request was
     *  signed with than a working clock could be (S3 allows 15 minutes). */
    private boolean dateFarOff(Response response) {
        long server = parseHttpDate(response.header("Date"));
        return server > 0 && Math.abs(server - serverNow()) > SKEW_SUSPECT_MS;
    }

    private boolean learnSkew(Response response, Node error) {
        long server = parseHttpDate(response.header("Date"));
        if (server <= 0) {
            server = parseIso(error.text("ServerTime"));
        }
        if (server <= 0) {
            return false;
        }
        skewMillis.set(server - phoneClock.getAsLong());
        return true;
    }

    private void backoff(int attempt, @Nullable String retryAfter) throws IOException {
        long wait = BACKOFF_MS << attempt;
        long asked = parseRetryAfter(retryAfter);
        if (asked >= 0) {
            wait = asked;
        }
        wait = Math.min(wait, MAX_RETRY_AFTER_MS);
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting to retry");
        }
    }

    /** {@code Retry-After} as milliseconds: seconds or an HTTP date; -1 when
     *  absent or unreadable. */
    static long parseRetryAfter(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) {
            return -1;
        }
        String v = value.trim();
        try {
            return Math.max(0, Long.parseLong(v)) * 1000L;
        } catch (NumberFormatException ignored) {
            // not seconds: maybe a date
        }
        long date = parseHttpDate(v);
        return date <= 0 ? -1 : Math.max(0, date - System.currentTimeMillis());
    }

    private static void close(Response response) {
        response.close();
    }

    // ---- XML ----

    /** A tiny element tree: local names only, text trimmed. */
    static final class Node {
        final String name;
        final StringBuilder textBuilder = new StringBuilder();
        final List<Node> children = new ArrayList<>();

        Node(String name) {
            this.name = name;
        }

        /** The trimmed text of the first child named {@code child}, or "". */
        String text(String child) {
            for (Node n : children) {
                if (n.name.equals(child)) {
                    return n.textBuilder.toString().trim();
                }
            }
            return "";
        }

        List<Node> children(String child) {
            List<Node> out = new ArrayList<>();
            for (Node n : children) {
                if (n.name.equals(child)) {
                    out.add(n);
                }
            }
            return out;
        }
    }

    private Node parseXml(Response response, long max, String what) throws IOException {
        byte[] bytes;
        try (InputStream in = response.body().byteStream()) {
            bytes = BackupLayout.readAll(in, max);
        } catch (IOException e) {
            throw trust.translate(e);
        }
        return parseXml(bytes, what);
    }

    /** The error body of a failed request (an empty node when it has none
     *  or it is not XML: HEAD answers carry no body). */
    private static Node readError(Response response) {
        try (InputStream in = response.body().byteStream()) {
            byte[] bytes = BackupLayout.readAll(in, MAX_ERROR_BYTES);
            if (bytes.length == 0) {
                return new Node("");
            }
            return parseXml(bytes, "read an error");
        } catch (IOException | RuntimeException e) {
            return new Node("");
        }
    }

    /**
     * The root element of an S3 answer. Namespace-aware SAX on local names;
     * a DOCTYPE is refused outright (no S3 answer carries one, and it is the
     * door to entity expansion on whichever parser the platform ships).
     */
    static Node parseXml(byte[] xml, String what) throws IOException {
        if (containsDoctype(xml)) {
            throw new IOException(what + ": answer carries a DOCTYPE");
        }
        TreeBuilder handler = new TreeBuilder();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities");
            setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities");
            SAXParser parser = factory.newSAXParser();
            parser.getXMLReader().setEntityResolver((publicId, systemId) ->
                    new InputSource(new StringReader("")));
            parser.parse(new ByteArrayInputStream(xml), handler);
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException(what + ": unreadable answer", e);
        }
        if (handler.root == null) {
            throw new IOException(what + ": empty answer");
        }
        return handler.root;
    }

    private static final class TreeBuilder extends DefaultHandler {
        Node root;
        private final List<Node> stack = new ArrayList<>();
        private int nodes;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs)
                throws SAXException {
            // The byte cap bounds the size, not the count: 8 MB of "<a/>" is
            // two million nodes, enough to run a small heap out of memory.
            if (++nodes > MAX_XML_NODES) {
                throw new SAXException("more than " + MAX_XML_NODES + " elements");
            }
            String name = localName == null || localName.isEmpty() ? qName : localName;
            Node node = new Node(name);
            if (stack.isEmpty()) {
                root = node;
            } else {
                stack.get(stack.size() - 1).children.add(node);
            }
            stack.add(node);
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (!stack.isEmpty()) {
                stack.get(stack.size() - 1).textBuilder.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (!stack.isEmpty()) {
                stack.remove(stack.size() - 1);
            }
        }
    }

    private static void setFeatureQuietly(SAXParserFactory factory, String feature) {
        try {
            factory.setFeature(feature, false);
        } catch (Exception ignored) {
            // A parser that does not know the feature does not resolve it either.
        }
    }

    private static boolean containsDoctype(byte[] xml) {
        byte[] needle = "<!doctype".getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + needle.length <= xml.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                int c = xml[i + j];
                if (c >= 'A' && c <= 'Z') {
                    c += 'a' - 'A';
                }
                if (c != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    static String escapeXml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    private static String md5Base64(byte[] body) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no MD5", e);
        }
    }

    // ---- small parsers ----

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** ISO 8601 ({@code 2026-10-18T10:00:00.000Z}); 0 when unreadable. */
    static long parseIso(@Nullable String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return Instant.parse(s.trim()).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** RFC 1123; 0 when absent or unreadable. */
    static long parseHttpDate(@Nullable String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return DateTimeFormatter.RFC_1123_DATE_TIME.parse(s.trim(), Instant::from).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The first byte of a {@code Content-Range: bytes a-b/n}; -1 when absent
     *  or malformed. */
    private static long rangeStart(@Nullable String contentRange) {
        if (contentRange == null) {
            return -1;
        }
        String v = contentRange.trim();
        if (!v.regionMatches(true, 0, "bytes ", 0, 6)) {
            return -1;
        }
        int dash = v.indexOf('-', 6);
        if (dash < 0) {
            return -1;
        }
        try {
            return Long.parseLong(v.substring(6, dash).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Skips {@code n} bytes; false when the stream ends first. */
    private static boolean skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        byte[] sink = null;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (sink == null) {
                    sink = new byte[64 * 1024];
                }
                int read = in.read(sink, 0, (int) Math.min(sink.length, remaining));
                if (read < 0) {
                    return false;
                }
                skipped = read;
            }
            remaining -= skipped;
        }
        return true;
    }

    private static byte[] readUpTo(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int got = 0;
        while (got < length) {
            int n = in.read(buf, got, length - got);
            if (n < 0) {
                break;
            }
            got += n;
        }
        if (got == length) {
            return buf;
        }
        byte[] out = new byte[got];
        System.arraycopy(buf, 0, out, 0, got);
        return out;
    }
}

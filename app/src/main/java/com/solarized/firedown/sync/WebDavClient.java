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
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import okhttp3.Call;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Pipe;

/**
 * The WebDAV wire, and nothing else: PROPFIND, MKCOL, PUT (whole or
 * streamed), ranged GET, DELETE, HEAD over OkHttp, with the server's answers
 * mapped to the {@link BackupStore} failure classes. {@link WebDavStore} puts
 * the backup layout on top; {@link WebDavCheck} uses it to find and test a
 * server before anything is saved.
 *
 * <p><b>Sign-in:</b> HTTP Basic, sent PREEMPTIVELY on every request to the
 * server's own origin. A streamed upload cannot be sent twice, so waiting
 * for a 401 challenge is not an option, and an app password over TLS is what
 * Nextcloud and ownCloud expect. OkHttp drops the header on a redirect to
 * another host or scheme. Digest-only servers are not supported.
 *
 * <p><b>Trust:</b> {@link ServerTrust}, shared with the S3 client: the
 * phone's own trust store first, plus the leaf certificate the user confirmed
 * once ({@link WebDavConfig#pinSha256}). When neither accepts, the leaf is
 * recorded ({@link #rejectedCert()}) so the check can show it, and the
 * failure is an {@link BackupStore.UnreachableException}: a certificate that
 * changed stops backups until the user looks at it.
 *
 * <p>No {@code Expect: 100-continue}: a server that ignores it would make
 * every upload wait out the read timeout first. The client is built from the
 * app's shared one (its DNS and connection pool) with every interceptor but
 * the User-Agent removed and the cookie jar off ({@link ServerTrust#newBuilder}).
 */
public final class WebDavClient {

    static final MediaType OCTET = MediaType.get("application/octet-stream");
    private static final MediaType XML = MediaType.get("application/xml; charset=utf-8");
    private static final String DAV_NS = "DAV:";
    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
                    + "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/>"
                    + "</d:prop></d:propfind>";
    /** A multistatus of a few thousand files is a few MB; refuse anything
     *  that would not fit a phone's memory comfortably. */
    private static final long MAX_PROPFIND_BYTES = 32L * 1024 * 1024;
    /** How much of a streamed upload may wait between the caller and the
     *  socket. */
    private static final long PIPE_BYTES = 1024 * 1024;

    /** One resource of a PROPFIND answer. */
    public static final class Resource {
        public final HttpUrl url;
        /** The last path segment, decoded; "" for the server root. */
        public final String name;
        public final boolean collection;
        public final long size;
        /** Epoch ms; 0 when the server did not say or said it oddly. */
        public final long modified;
        @Nullable
        public final String etag;

        Resource(HttpUrl url, String name, boolean collection, long size, long modified,
                 @Nullable String etag) {
            this.url = url;
            this.name = name;
            this.collection = collection;
            this.size = size;
            this.modified = modified;
            this.etag = etag;
        }
    }

    /** A PROPFIND answer: the status, the URL that answered (after
     *  redirects), the resource the request named, and the others. */
    public static final class Propfind {
        public final int code;
        public final HttpUrl finalUrl;
        @Nullable
        public final Resource self;
        public final List<Resource> children;

        Propfind(int code, HttpUrl finalUrl, @Nullable Resource self, List<Resource> children) {
            this.code = code;
            this.finalUrl = finalUrl;
            this.self = self;
            this.children = children;
        }
    }

    private final OkHttpClient client;
    private final ServerTrust trust;

    /**
     * @param base   the app's client (its DNS and pool), or null for a plain one
     * @param origin the server; the sign-in is sent to this origin only
     * @param pinSha256 a leaf certificate trusted on top of the phone's, or null
     */
    public WebDavClient(@Nullable OkHttpClient base, @NonNull HttpUrl origin,
                        @NonNull String user, @NonNull String password,
                        @Nullable String pinSha256) {
        this.trust = new ServerTrust(pinSha256);
        String credential = Credentials.basic(user, password, StandardCharsets.UTF_8);
        String scheme = origin.scheme();
        String host = origin.host();
        int port = origin.port();
        Interceptor auth = chain -> {
            Request request = chain.request();
            HttpUrl url = request.url();
            boolean ours = url.scheme().equals(scheme) && url.host().equals(host)
                    && url.port() == port;
            if (ours && request.header("Authorization") == null) {
                request = request.newBuilder().header("Authorization", credential).build();
            }
            return chain.proceed(request);
        };
        this.client = trust.newBuilder(base)
                .addInterceptor(auth)
                .followRedirects(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    /** The certificate the last refused TLS handshake presented, or null. */
    @Nullable
    public ServerTrust.CertInfo rejectedCert() {
        return trust.rejected();
    }

    // ---- requests ----

    /** PROPFIND at {@code depth} (0 or 1). A non-207 answer comes back with
     *  no resources; the caller judges the code. */
    public Propfind propfind(HttpUrl url, int depth) throws IOException {
        Request request = new Request.Builder().url(url)
                .method("PROPFIND", RequestBody.create(PROPFIND_BODY, XML))
                .header("Depth", Integer.toString(depth))
                .build();
        try (Response response = execute(request)) {
            HttpUrl finalUrl = response.request().url();
            if (response.code() != 207) {
                return new Propfind(response.code(), finalUrl, null, new ArrayList<>());
            }
            byte[] xml;
            try (InputStream in = response.body().byteStream()) {
                xml = BackupLayout.readAll(in, MAX_PROPFIND_BYTES);
            }
            List<Resource> all = parseMultistatus(xml, finalUrl);
            Resource self = null;
            List<Resource> children = new ArrayList<>();
            List<String> selfPath = trimmedSegments(finalUrl);
            for (Resource r : all) {
                if (self == null && trimmedSegments(r.url).equals(selfPath)) {
                    self = r;
                } else {
                    children.add(r);
                }
            }
            if (self == null && depth == 0 && children.size() == 1) {
                // A Depth 0 answer names one resource: the one asked about,
                // however oddly the server spelled its href.
                self = children.remove(0);
            }
            return new Propfind(207, finalUrl, self, children);
        }
    }

    /** MKCOL; returns the status (201 made, 405 already there). */
    public int mkcol(HttpUrl url) throws IOException {
        Request request = new Request.Builder().url(url).method("MKCOL", null).build();
        try (Response response = execute(request)) {
            return response.code();
        }
    }

    /** PUT of a small body (a sidecar, the write check); returns the status. */
    public int put(HttpUrl url, byte[] body) throws IOException {
        Request request = new Request.Builder().url(url).put(RequestBody.create(body, OCTET)).build();
        try (Response response = execute(request)) {
            return response.code();
        }
    }

    /** DELETE; returns the status (404 = already gone). */
    public int delete(HttpUrl url) throws IOException {
        Request request = new Request.Builder().url(url).delete().build();
        try (Response response = execute(request)) {
            return response.code();
        }
    }

    /** HEAD; returns the status. */
    public int head(HttpUrl url) throws IOException {
        Request request = new Request.Builder().url(url).head().build();
        try (Response response = execute(request)) {
            return response.code();
        }
    }

    /** GET of a small file, at most {@code max} bytes; null on 404. */
    @Nullable
    public byte[] getBytes(HttpUrl url, long max, String what) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = execute(request)) {
            if (response.code() == 404) {
                return null;
            }
            if (!isSuccess(response.code())) {
                throw statusError(response.code(), what);
            }
            try (InputStream in = response.body().byteStream()) {
                return BackupLayout.readAll(in, max);
            }
        }
    }

    /** GET of a whole file as a stream; the stream owns the response.
     *  {@link FileNotFoundException} on 404. */
    public InputStream openStream(HttpUrl url, String what) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        Response response = execute(request);
        if (response.code() == 404) {
            response.close();
            throw new FileNotFoundException(what + ": not on the server");
        }
        if (!isSuccess(response.code())) {
            response.close();
            throw statusError(response.code(), what);
        }
        ResponseBody body = response.body();
        return new FilterInputStream(body.byteStream()) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                try {
                    return super.read(b, off, len);
                } catch (IOException e) {
                    throw translate(e);
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
     * the end of the file, none past it. A server that ignores the range and
     * answers 200 still gives the right bytes (skipped to, then cut off),
     * just more slowly. A 206 for a different range than asked is refused
     * rather than handed on as if it were the requested bytes.
     */
    public byte[] getRange(HttpUrl url, long offset, int length, String what) throws IOException {
        if (length <= 0) {
            return new byte[0];
        }
        long last = offset + length - 1;
        Request request = new Request.Builder().url(url).get()
                .header("Range", "bytes=" + offset + "-" + last)
                .build();
        try (Response response = execute(request)) {
            int code = response.code();
            if (code == 416) {
                return new byte[0];
            }
            if (code == 404) {
                throw new FileNotFoundException(what + ": not on the server");
            }
            if (code != 206 && code != 200) {
                throw statusError(code, what);
            }
            try (InputStream in = response.body().byteStream()) {
                if (code == 206) {
                    long start = rangeStart(response.header("Content-Range"));
                    if (start != offset) {
                        throw new IOException(what + ": server sent bytes from " + start
                                + ", asked for " + offset);
                    }
                } else if (!skipFully(in, offset)) {
                    return new byte[0];
                }
                return readUpTo(in, length);
            } catch (IOException e) {
                throw translate(e);
            }
        }
    }

    /**
     * A PUT whose body is written through the returned stream, of exactly
     * {@code length} bytes. The request runs on its own thread from the
     * first byte; {@link OutputStream#close()} waits for the answer and
     * throws unless the server stored it. Closing before {@code length}
     * bytes were written cancels the request (the server stores nothing);
     * writing more than {@code length} is refused.
     */
    public OutputStream putStream(HttpUrl url, long length, String what) {
        return new StreamingPut(url, length, what);
    }

    private Response execute(Request request) throws IOException {
        try {
            return client.newCall(request).execute();
        } catch (IOException e) {
            throw translate(e);
        }
    }

    /** A refused certificate (or a name a pin does not cover) is the
     *  server's identity, not the network: it needs the user. */
    IOException translate(IOException e) {
        return trust.translate(e);
    }

    static boolean isSuccess(int code) {
        return code >= 200 && code < 300;
    }

    /** The failure class of a status: a refused sign-in or permission needs
     *  the user ({@link BackupStore.UnreachableException}); anything else a
     *  retry may fix. */
    static IOException statusError(int code, String what) {
        if (code == 401) {
            return new BackupStore.UnreachableException(what + ": the server refused the sign-in");
        }
        if (code == 403) {
            return new BackupStore.UnreachableException(what + ": not allowed on the server");
        }
        if (code == 507) {
            return new IOException(what + ": the server is out of space");
        }
        return new IOException(what + ": HTTP " + code);
    }

    // ---- the streamed PUT ----

    private final class StreamingPut extends OutputStream {
        private final Pipe pipe = new Pipe(PIPE_BYTES);
        private final BufferedSink sink = Okio.buffer(pipe.sink());
        private final long length;
        private final String what;
        private final Call call;
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile int status = -1;
        private volatile IOException failure;
        private long written;
        private boolean closed;

        StreamingPut(HttpUrl url, long length, String what) {
            this.length = length;
            this.what = what;
            RequestBody body = new RequestBody() {
                @Override
                public MediaType contentType() {
                    return OCTET;
                }

                @Override
                public long contentLength() {
                    return length;
                }

                @Override
                public boolean isOneShot() {
                    return true;
                }

                @Override
                public void writeTo(@NonNull BufferedSink out) throws IOException {
                    out.writeAll(pipe.source());
                }
            };
            this.call = client.newCall(new Request.Builder().url(url).put(body).build());
            Thread sender = new Thread(this::send, "webdav-put");
            sender.setDaemon(true);
            sender.start();
        }

        private void send() {
            try (Response response = call.execute()) {
                status = response.code();
            } catch (IOException e) {
                failure = e;
            } finally {
                // A request that ended (answered early, failed, cancelled)
                // reads no more: unblock a writer still feeding the pipe.
                pipe.cancel();
                done.countDown();
            }
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(@NonNull byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new IOException(what + ": stream closed");
            }
            if (len > length - written) {
                abort();
                throw new IOException(what + ": more than the declared " + length + " bytes");
            }
            try {
                sink.write(b, off, len);
            } catch (IOException e) {
                throw failureOr(e);
            }
            written += len;
        }

        @Override
        public void flush() throws IOException {
            try {
                sink.flush();
            } catch (IOException e) {
                throw failureOr(e);
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            if (written != length) {
                abort();
                awaitSender();
                throw new IOException(what + ": " + written + " of " + length
                        + " bytes written; nothing stored");
            }
            try {
                sink.close();
            } catch (IOException e) {
                abort();
                awaitSender();
                throw failureOr(e);
            }
            awaitSender();
            if (failure != null) {
                throw translate(failure);
            }
            if (!isSuccess(status)) {
                throw statusError(status, what);
            }
        }

        private void abort() {
            call.cancel();
            pipe.cancel();
        }

        private void awaitSender() throws IOException {
            try {
                done.await();
            } catch (InterruptedException e) {
                abort();
                Thread.currentThread().interrupt();
                throw new InterruptedIOException(what + ": interrupted");
            }
        }

        /** What actually went wrong, when the request already said. */
        private IOException failureOr(IOException e) {
            if (done.getCount() == 0) {
                if (failure != null) {
                    return translate(failure);
                }
                if (!isSuccess(status)) {
                    return statusError(status, what);
                }
            }
            return translate(e);
        }
    }

    // ---- multistatus ----

    /**
     * The resources of a 207 body. Namespace-aware SAX; props are taken only
     * from a {@code propstat} whose status is 2xx (or absent), so a 404
     * propstat listing the props a server lacks cannot blank the real ones.
     * A DOCTYPE is refused outright: no multistatus carries one, and it is
     * the door to entity expansion on whichever parser the platform ships.
     */
    static List<Resource> parseMultistatus(byte[] xml, HttpUrl base) throws IOException {
        if (containsDoctype(xml)) {
            throw new IOException("PROPFIND answer carries a DOCTYPE");
        }
        MultistatusHandler handler = new MultistatusHandler(base);
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
            throw new IOException("unreadable PROPFIND answer", e);
        }
        return handler.out;
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

    private static final class MultistatusHandler extends DefaultHandler {
        final List<Resource> out = new ArrayList<>();
        private final HttpUrl base;
        private final StringBuilder text = new StringBuilder();
        private boolean inResponse;
        private boolean inPropstat;
        private boolean inResourceType;
        private String href;
        private boolean collection;
        private long size;
        private long modified;
        private String etag;
        // the propstat being read, committed only when its status is 2xx
        private boolean psCollection;
        private String psSize;
        private String psModified;
        private String psEtag;
        private String psStatus;

        MultistatusHandler(HttpUrl base) {
            this.base = base;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            text.setLength(0);
            if (!DAV_NS.equals(uri)) {
                return;
            }
            switch (localName) {
                case "response":
                    inResponse = true;
                    href = null;
                    collection = false;
                    size = 0;
                    modified = 0;
                    etag = null;
                    break;
                case "propstat":
                    inPropstat = true;
                    psCollection = false;
                    psSize = null;
                    psModified = null;
                    psEtag = null;
                    psStatus = null;
                    break;
                case "resourcetype":
                    inResourceType = true;
                    break;
                case "collection":
                    if (inResourceType) {
                        psCollection = true;
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (!DAV_NS.equals(uri)) {
                return;
            }
            String value = text.toString().trim();
            switch (localName) {
                case "href":
                    if (inResponse && !inPropstat) {
                        href = value;
                    }
                    break;
                case "status":
                    if (inPropstat) {
                        psStatus = value;
                    }
                    break;
                case "resourcetype":
                    inResourceType = false;
                    break;
                case "getcontentlength":
                    psSize = value;
                    break;
                case "getlastmodified":
                    psModified = value;
                    break;
                case "getetag":
                    psEtag = value;
                    break;
                case "propstat":
                    inPropstat = false;
                    if (psStatus == null || isOkStatus(psStatus)) {
                        collection |= psCollection;
                        if (psSize != null) {
                            size = parseLong(psSize);
                        }
                        if (psModified != null) {
                            modified = parseHttpDate(psModified);
                        }
                        if (psEtag != null && !psEtag.isEmpty()) {
                            etag = psEtag;
                        }
                    }
                    break;
                case "response":
                    inResponse = false;
                    HttpUrl url = href == null ? null : base.resolve(href);
                    if (url != null) {
                        out.add(new Resource(url, lastSegment(url), collection, size, modified, etag));
                    }
                    break;
                default:
                    break;
            }
            text.setLength(0);
        }
    }

    /** {@code HTTP/1.1 200 OK} → true; any non-2xx code → false. */
    private static boolean isOkStatus(String status) {
        String[] parts = status.trim().split("\\s+");
        if (parts.length < 2) {
            return false;
        }
        return parts[1].length() == 3 && parts[1].charAt(0) == '2';
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** RFC 1123 (what RFC 4918 prescribes); 0 when a server says otherwise. */
    static long parseHttpDate(String s) {
        try {
            return DateTimeFormatter.RFC_1123_DATE_TIME.parse(s.trim(), Instant::from).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The decoded path segments without a trailing empty one, so
     *  {@code /a/b/} and {@code /a/b} compare equal. */
    static List<String> trimmedSegments(HttpUrl url) {
        List<String> segments = new ArrayList<>(url.pathSegments());
        while (!segments.isEmpty() && segments.get(segments.size() - 1).isEmpty()) {
            segments.remove(segments.size() - 1);
        }
        return segments;
    }

    private static String lastSegment(HttpUrl url) {
        List<String> segments = trimmedSegments(url);
        return segments.isEmpty() ? "" : segments.get(segments.size() - 1);
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

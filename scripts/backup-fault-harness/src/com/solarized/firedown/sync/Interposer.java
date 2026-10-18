package com.solarized.firedown.sync;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * A raw-socket HTTP/1.1 interposer: a hostile or broken server, or a proxy
 * that sits in front of a well-behaved one ({@code FakeS3}, {@code FakeDav})
 * and damages what passes through. Every request is recorded (method,
 * target, headers, body) so a check can assert what reached it, and a
 * {@link Rule} decides per request whether to forward it, answer it, swallow
 * it, or cut the connection part-way through its body.
 *
 * <p>What the JDK's {@code HttpServer} cannot do and this can: a response
 * whose Content-Length is a lie, a body cut off or reset at a chosen byte, a
 * body dripped one byte at a time, a TCP reset instead of a close, and a
 * request body reset after N bytes (a connection lost mid-upload).
 *
 * <p>One thread per connection; keep-alive honoured until a response breaks
 * the framing, after which the connection is closed. Forwarded requests go
 * to the backend over a fresh connection each ({@code Connection: close}),
 * with the request head copied byte for byte apart from that header, so a
 * SigV4 signature over it still verifies at the backend.
 */
final class Interposer implements AutoCloseable {

    /** One request as it arrived. */
    static final class Req {
        final String method;
        final String target;
        final String version;
        final List<String[]> headers = new ArrayList<>();
        byte[] body = new byte[0];

        Req(String method, String target, String version) {
            this.method = method;
            this.target = target;
            this.version = version;
        }

        String header(String name) {
            for (String[] h : headers) {
                if (h[0].equalsIgnoreCase(name)) {
                    return h[1];
                }
            }
            return null;
        }

        String path() {
            int q = target.indexOf('?');
            return q < 0 ? target : target.substring(0, q);
        }

        String query() {
            int q = target.indexOf('?');
            return q < 0 ? "" : target.substring(q + 1);
        }

        @Override
        public String toString() {
            return method + " " + target;
        }
    }

    /** A response to write, with the ways it can be damaged on the way out. */
    static final class Reply {
        int status;
        String reason;
        final LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        byte[] body;
        /** Send only this many body bytes, then close. -1 = all. */
        int truncateAt = -1;
        /** Send this many body bytes, then RST the connection. -1 = never. */
        int resetAt = -1;
        /** Milliseconds between body bytes; 0 = at once. */
        long dripMs;
        /** Content-Length to declare; -1 = the body's real length. */
        long declaredLength = -1;
        /** Close the connection after this response. */
        boolean closeAfter;
        /** Milliseconds slept per 64 KB of body written; 0 = none. */
        long throttleMs;

        Reply(int status, String reason, byte[] body) {
            this.status = status;
            this.reason = reason;
            this.body = body == null ? new byte[0] : body;
        }

        static Reply of(int status, String contentType, byte[] body) {
            Reply r = new Reply(status, reasonOf(status), body);
            if (contentType != null) {
                r.headers.put("Content-Type", contentType);
            }
            return r;
        }

        static Reply xml(int status, String xml) {
            return of(status, "application/xml", xml.getBytes(StandardCharsets.UTF_8));
        }

        Reply header(String name, String value) {
            for (String k : new ArrayList<>(headers.keySet())) {
                if (k.equalsIgnoreCase(name)) {
                    headers.remove(k);
                }
            }
            headers.put(name, value);
            return this;
        }

        String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        }

        Reply truncate(int at) {
            truncateAt = at;
            return this;
        }

        Reply reset(int at) {
            resetAt = at;
            return this;
        }

        Reply drip(long ms) {
            dripMs = ms;
            return this;
        }

        Reply declare(long length) {
            declaredLength = length;
            return this;
        }

        Reply close() {
            closeAfter = true;
            return this;
        }

        Reply throttle(long msPer64k) {
            throttleMs = msPer64k;
            return this;
        }

        /** Drops a header whatever its case. */
        Reply remove(String name) {
            for (String k : new ArrayList<>(headers.keySet())) {
                if (k.equalsIgnoreCase(name)) {
                    headers.remove(k);
                }
            }
            return this;
        }

        static String reasonOf(int status) {
            switch (status) {
                case 200: return "OK";
                case 204: return "No Content";
                case 206: return "Partial Content";
                case 207: return "Multi-Status";
                case 301: return "Moved Permanently";
                case 302: return "Found";
                case 307: return "Temporary Redirect";
                case 400: return "Bad Request";
                case 403: return "Forbidden";
                case 404: return "Not Found";
                case 416: return "Range Not Satisfiable";
                case 429: return "Too Many Requests";
                case 500: return "Internal Server Error";
                case 503: return "Service Unavailable";
                default: return "Status";
            }
        }
    }

    /** What to do with one request. */
    static final class Action {
        Reply answer;
        boolean swallow;
        long resetRequestAfter = -1;
        Function<Reply, Reply> mutate;

        static Action forward() {
            return new Action();
        }

        /** Answer this, never touching the backend. */
        static Action answer(Reply reply) {
            Action a = new Action();
            a.answer = reply;
            return a;
        }

        /** Read the request, forward nothing, answer 200 with an ETag. */
        static Action swallow() {
            Action a = new Action();
            a.swallow = true;
            return a;
        }

        /** Read this many bytes of the request body, then RST. */
        static Action resetRequestAfter(long bytes) {
            Action a = new Action();
            a.resetRequestAfter = bytes;
            return a;
        }

        /** Forward, then change the backend's answer. */
        static Action mutate(Function<Reply, Reply> f) {
            Action a = new Action();
            a.mutate = f;
            return a;
        }
    }

    interface Rule {
        Action decide(Req req);
    }

    private static final int MAX_HEAD = 256 * 1024;
    private static final long MAX_BODY = 256L * 1024 * 1024;

    final List<Req> seen = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger requests = new AtomicInteger();
    /** Body bytes written to clients. */
    final AtomicLong bytesSent = new AtomicLong();
    volatile Rule rule = req -> Action.forward();
    /** Set to end every drip in progress. */
    volatile boolean stopDrips;
    /** The client-side socket read timeout; a drip test lowers nothing here. */
    volatile int clientReadTimeoutMs = 120_000;

    final int port;
    private final String backendHost;
    private final int backendPort;
    private final ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "interposer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;

    /** @param backendHost null for a server that only answers for itself */
    Interposer(String backendHost, int backendPort) throws IOException {
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        this.server = new ServerSocket();
        this.server.setReuseAddress(true);
        this.server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        this.port = server.getLocalPort();
        Thread acceptor = new Thread(this::acceptLoop, "interposer-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** A server with no backend: it answers whatever its rule says. */
    static Interposer standalone() throws IOException {
        return new Interposer(null, 0);
    }

    int count(String method, String targetContains) {
        int n = 0;
        synchronized (seen) {
            for (Req r : seen) {
                if ((method == null || r.method.equals(method))
                        && (targetContains == null || r.target.contains(targetContains))) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Forgets what was seen; the rule stays. */
    void clearSeen() {
        seen.clear();
        requests.set(0);
        bytesSent.set(0);
    }

    /** Forgets what was seen and forwards everything again. */
    void reset() {
        clearSeen();
        rule = req -> Action.forward();
        stopDrips = false;
    }

    @Override
    public void close() {
        closed = true;
        stopDrips = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // closing
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                return;
            }
            pool.execute(() -> serve(s));
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(clientReadTimeoutMs);
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
            OutputStream out = socket.getOutputStream();
            while (!closed) {
                Req req = readHead(in);
                if (req == null) {
                    break;
                }
                Action action = rule.decide(req);
                long length = contentLength(req);
                if (action.resetRequestAfter >= 0) {
                    readUpTo(in, Math.min(length < 0 ? Long.MAX_VALUE : length,
                            action.resetRequestAfter), null);
                    seen.add(req);
                    requests.incrementAndGet();
                    rst(socket);
                    return;
                }
                if (length > 0) {
                    req.body = readExactly(in, length);
                } else if ("chunked".equalsIgnoreCase(req.header("Transfer-Encoding"))) {
                    req.body = readChunked(in);
                }
                seen.add(req);
                requests.incrementAndGet();
                Reply reply;
                if (action.answer != null) {
                    reply = action.answer;
                } else if (action.swallow) {
                    reply = Reply.of(200, null, new byte[0]);
                    reply.headers.put("ETag", "\"swallowed\"");
                } else {
                    reply = forward(req);
                }
                if (action.mutate != null) {
                    reply = action.mutate.apply(reply);
                }
                boolean keep = write(socket, out, reply, "HEAD".equals(req.method));
                boolean clientClose = "close".equalsIgnoreCase(req.header("Connection"));
                if (!keep || clientClose || reply.closeAfter) {
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            // the client went away, or a hostile answer broke the connection
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    /** Writes a reply; false when the connection can no longer be reused. */
    private boolean write(Socket socket, OutputStream out, Reply r, boolean head) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(r.reason).append("\r\n");
        boolean noBody = head || r.status == 204 || r.status == 304 || r.status / 100 == 1;
        for (Map.Entry<String, String> h : r.headers.entrySet()) {
            String k = h.getKey().toLowerCase(Locale.ROOT);
            if (k.equals("content-length") || k.equals("transfer-encoding") || k.equals("connection")) {
                continue;
            }
            sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        boolean breaks = r.truncateAt >= 0 || r.resetAt >= 0 || r.dripMs > 0 || r.declaredLength >= 0
                || r.throttleMs > 0;
        if (!(r.status == 204 || r.status == 304 || r.status / 100 == 1)) {
            long declared = r.declaredLength >= 0 ? r.declaredLength : r.body.length;
            sb.append("Content-Length: ").append(declared).append("\r\n");
        }
        if (breaks || r.closeAfter) {
            sb.append("Connection: close\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (noBody) {
            out.flush();
            return !breaks;
        }
        byte[] body = r.body;
        if (r.dripMs > 0) {
            for (int i = 0; i < body.length && !stopDrips && !closed; i++) {
                out.write(body[i]);
                out.flush();
                bytesSent.incrementAndGet();
                try {
                    Thread.sleep(r.dripMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }
        if (r.resetAt >= 0) {
            int n = Math.min(r.resetAt, body.length);
            out.write(body, 0, n);
            out.flush();
            bytesSent.addAndGet(n);
            rst(socket);
            return false;
        }
        int n = r.truncateAt >= 0 ? Math.min(r.truncateAt, body.length) : body.length;
        writeCounting(out, body, n, r.throttleMs);
        out.flush();
        return !breaks && r.throttleMs == 0;
    }

    private void writeCounting(OutputStream out, byte[] body, int n, long throttleMs) throws IOException {
        int at = 0;
        while (at < n) {
            int step = Math.min(64 * 1024, n - at);
            out.write(body, at, step);
            out.flush();
            at += step;
            bytesSent.addAndGet(step);
            if (throttleMs > 0) {
                try {
                    Thread.sleep(throttleMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
        }
    }

    private static void rst(Socket socket) {
        try {
            socket.setSoLinger(true, 0);
        } catch (SocketException ignored) {
            // best-effort
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing
        }
    }

    // ---- the backend ----

    private Reply forward(Req req) throws IOException {
        if (backendHost == null) {
            return Reply.of(404, null, new byte[0]);
        }
        try (Socket b = new Socket(backendHost, backendPort)) {
            b.setSoTimeout(60_000);
            OutputStream out = b.getOutputStream();
            StringBuilder sb = new StringBuilder();
            sb.append(req.method).append(' ').append(req.target).append(' ').append(req.version)
                    .append("\r\n");
            boolean hadLength = false;
            for (String[] h : req.headers) {
                String k = h[0].toLowerCase(Locale.ROOT);
                if (k.equals("connection") || k.equals("transfer-encoding")) {
                    continue;
                }
                if (k.equals("content-length")) {
                    hadLength = true;
                }
                sb.append(h[0]).append(": ").append(h[1]).append("\r\n");
            }
            if (!hadLength && req.body.length > 0) {
                sb.append("Content-Length: ").append(req.body.length).append("\r\n");
            }
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(req.body);
            out.flush();
            InputStream in = new BufferedInputStream(b.getInputStream(), 64 * 1024);
            String statusLine = readLine(in);
            if (statusLine == null) {
                throw new IOException("backend closed without answering");
            }
            String[] parts = statusLine.split(" ", 3);
            int status = Integer.parseInt(parts[1]);
            Reply reply = new Reply(status, parts.length > 2 ? parts[2] : Reply.reasonOf(status), null);
            String line;
            long length = -1;
            boolean chunked = false;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("Content-Length")) {
                    length = Long.parseLong(value);
                } else if (name.equalsIgnoreCase("Transfer-Encoding")) {
                    chunked = value.toLowerCase(Locale.ROOT).contains("chunked");
                } else if (!name.equalsIgnoreCase("Connection")) {
                    reply.headers.put(name, value);
                }
            }
            boolean noBody = "HEAD".equals(req.method) || status == 204 || status == 304
                    || status / 100 == 1;
            if (noBody) {
                reply.body = new byte[0];
            } else if (chunked) {
                reply.body = readChunked(in);
            } else if (length >= 0) {
                reply.body = readExactly(in, length);
            } else {
                reply.body = readUpTo(in, Long.MAX_VALUE, null);
            }
            return reply;
        }
    }

    // ---- wire helpers ----

    private static Req readHead(InputStream in) throws IOException {
        String requestLine = readLine(in);
        while (requestLine != null && requestLine.isEmpty()) {
            requestLine = readLine(in);
        }
        if (requestLine == null) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 3) {
            throw new IOException("bad request line: " + requestLine);
        }
        Req req = new Req(parts[0], parts[1], parts[2]);
        String line;
        int total = requestLine.length();
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            total += line.length();
            if (total > MAX_HEAD) {
                throw new IOException("head too large");
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                req.headers.add(new String[]{line.substring(0, colon).trim(),
                        line.substring(colon + 1).trim()});
            }
        }
        return req;
    }

    private static long contentLength(Req req) {
        String cl = req.header("Content-Length");
        if (cl == null) {
            return -1;
        }
        try {
            return Long.parseLong(cl.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                out.write(c);
            }
            if (out.size() > MAX_HEAD) {
                throw new IOException("line too long");
            }
        }
        if (!any) {
            return null;
        }
        return out.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static byte[] readExactly(InputStream in, long length) throws IOException {
        if (length > MAX_BODY) {
            throw new IOException("body too large: " + length);
        }
        byte[] out = new byte[(int) length];
        int at = 0;
        while (at < out.length) {
            int n = in.read(out, at, out.length - at);
            if (n < 0) {
                throw new IOException("body ended after " + at + " of " + length + " bytes");
            }
            at += n;
        }
        return out;
    }

    private static byte[] readUpTo(InputStream in, long max, OutputStream sink) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        while (total < max) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, max - total));
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            total += n;
            if (total > MAX_BODY) {
                throw new IOException("body too large");
            }
        }
        return out.toByteArray();
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                throw new IOException("chunked body ended early");
            }
            int semi = sizeLine.indexOf(';');
            int size = Integer.parseInt((semi < 0 ? sizeLine : sizeLine.substring(0, semi)).trim(), 16);
            if (size == 0) {
                String trailer;
                while ((trailer = readLine(in)) != null && !trailer.isEmpty()) {
                    // trailers ignored
                }
                return out.toByteArray();
            }
            out.write(readExactly(in, size));
            readLine(in);
            if (out.size() > MAX_BODY) {
                throw new IOException("body too large");
            }
        }
    }
}

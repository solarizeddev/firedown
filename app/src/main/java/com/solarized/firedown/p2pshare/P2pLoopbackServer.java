package com.solarized.firedown.p2pshare;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.data.RestoredFileAccess;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loopback byte bridge between the p2pshare WebRTC engine (a page script in a
 * hidden GeckoSession — createOffer() hangs in a WebExtension background page,
 * so the engine runs in a real content document served from /engine here) and
 * the filesystem. File bytes must NOT cross the native-messaging bridge — every
 * chunk would be base64-in-JSON through the EventDispatcher — so instead the
 * engine speaks plain HTTP to this server on 127.0.0.1:
 *
 * <ul>
 *   <li>SEND: {@code GET /read?t=<token>} streams the shared file; the engine
 *       fetch()es it and pumps the response stream into the DataChannel.
 *       Optional {@code &from=<offset>} starts mid-file (a resumed transfer
 *       streams only the remainder) and {@code &len=<bytes>} caps the body
 *       (the engine's resume-tail verification reads one 64 KB window).</li>
 *   <li>RECEIVE: {@code POST /write?t=<token>&off=<offset>} appends a chunk
 *       batch to the target file. The engine serializes its POSTs and the
 *       offset is verified against the bytes already written, so ordering on
 *       disk is guaranteed even if a retry ever duplicated a request. A
 *       resumed session arms the target at the kept byte count; {@code off=0}
 *       against a non-empty target is the one sanctioned exception — the
 *       sender refused the resume (tail mismatch), so truncate and restart.</li>
 * </ul>
 *
 * Bound to the loopback address only and gated by a per-session random token,
 * so nothing off-device (and no other app reading logcat — the token is never
 * logged) can read the shared file or corrupt a transfer. Lifetime is exactly
 * one share session: {@link P2pShareController} starts it when a transfer is
 * prepared and stops it when the share screen closes, mirroring the old
 * LAN-share "nothing listens when the sheet isn't open" contract.
 */
public class P2pLoopbackServer {

    private static final String TAG = "P2pLoopbackServer";

    /**
     * Cap on a single write body (the engine flushes ~4 MiB batches; anything
     * bigger is a protocol violation, not a bigger batch).
     */
    private static final int MAX_WRITE_BODY = 16 * 1024 * 1024;

    private static final int STREAM_BUFFER = 64 * 1024;

    private final Context mContext;
    private final String mToken;

    private volatile ServerSocket mServerSocket;
    private volatile ExecutorService mHandlerPool;
    private Thread mAcceptThread;
    private volatile boolean mRunning;

    // SEND role — the file served on /read.
    private volatile String mReadPath;

    // RECEIVE role — the .part file /write appends to. Guarded by mWriteLock:
    // the engine's POSTs are sequential by contract, but stop() can race a
    // late write and must not close the RAF under it.
    private final Object mWriteLock = new Object();
    private RandomAccessFile mWriteFile;
    private long mWritten;

    public P2pLoopbackServer(Context context) {
        mContext = context.getApplicationContext();
        byte[] tokenBytes = new byte[16];
        new SecureRandom().nextBytes(tokenBytes);
        StringBuilder token = new StringBuilder(tokenBytes.length * 2);
        for (byte b : tokenBytes) {
            token.append(String.format(Locale.US, "%02x", b));
        }
        mToken = token.toString();
    }

    public void start() throws IOException {
        // Ephemeral port on the IPv4 loopback only — never a LAN interface.
        //
        // MUST bind to 127.0.0.1 explicitly, NOT InetAddress.getLoopbackAddress():
        // on Android the latter returns the IPv6 loopback ::1 (AOSP's
        // loopbackAddresses() is {Inet6Address.LOOPBACK, Inet4Address.LOOPBACK}
        // and getLoopbackAddress() takes [0]). The engine URL (baseUrl()) is the
        // IPv4 literal http://127.0.0.1:<port>, so binding to ::1 left nothing
        // listening on IPv4 and the GeckoView content process's page load was
        // refused with NS_ERROR_CONNECTION_REFUSED (0x804B000D). Bind and connect
        // must be the same family.
        mServerSocket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        mHandlerPool = Executors.newFixedThreadPool(2);
        mRunning = true;
        mAcceptThread = new Thread(this::acceptLoop, "P2pLoopback");
        mAcceptThread.start();
    }

    public void stop() {
        mRunning = false;
        if (mServerSocket != null) {
            try {
                mServerSocket.close();
            } catch (IOException e) {
                // Closing is best-effort; the accept loop exits on the error.
            }
            mServerSocket = null;
        }
        if (mHandlerPool != null) {
            mHandlerPool.shutdownNow();
            mHandlerPool = null;
        }
        closeWriteTarget();
    }

    public void setReadFile(String absolutePath) {
        mReadPath = absolutePath;
    }

    /**
     * Arm the write side. The target is a .part file owned by the app (the
     * controller creates it); bytes are appended strictly in order.
     *
     * @param keepBytes bytes of an existing partial to keep for a RESUMED
     *                  transfer — the next write must land at exactly this
     *                  offset (or at 0, the sender-refused-resume restart).
     *                  0 = fresh transfer, any existing content is dropped.
     */
    public void setWriteTarget(File partFile, long keepBytes) throws IOException {
        synchronized (mWriteLock) {
            mWriteFile = new RandomAccessFile(partFile, "rw");
            mWriteFile.setLength(keepBytes);
            mWriteFile.seek(keepBytes);
            mWritten = keepBytes;
        }
    }

    /**
     * Close the write file so the controller can verify + rename it. Safe to
     * call twice (stop() calls it as a backstop).
     */
    public void closeWriteTarget() {
        synchronized (mWriteLock) {
            if (mWriteFile != null) {
                try {
                    mWriteFile.close();
                } catch (IOException e) {
                    Log.e(TAG, "closeWriteTarget", e);
                }
                mWriteFile = null;
            }
        }
    }

    public String getReadUrl() {
        return baseUrl() + "/read?t=" + mToken;
    }

    /**
     * The engine appends {@code &off=<offset>} per POST.
     */
    public String getWriteUrl() {
        return baseUrl() + "/write?t=" + mToken;
    }

    /** The URL the hidden GeckoSession loads to host the WebRTC engine. */
    public String getEnginePageUrl() {
        return baseUrl() + "/engine";
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + mServerSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (mRunning) {
            // stop() (main thread) can null the socket/pool the instant after
            // the mRunning check; snapshot into locals and catch EVERYTHING so
            // a teardown race (NPE on a nulled socket, RejectedExecution on a
            // shut-down pool) can never kill this bare thread → crash the app.
            Socket client;
            try {
                ServerSocket socket = mServerSocket;
                if (socket == null) {
                    return;
                }
                client = socket.accept();
            } catch (Exception e) {
                // Socket closed by stop(), or a transient/teardown error.
                if (mRunning && BuildConfig.DEBUG) {
                    Log.d(TAG, "accept ended: " + e.getMessage());
                }
                return;
            }
            ExecutorService pool = mHandlerPool;
            if (pool == null) {
                closeQuietly(client);
                return;
            }
            try {
                pool.execute(() -> handleConnection(client));
            } catch (RuntimeException e) {
                // Pool shut down between the null check and execute().
                closeQuietly(client);
                return;
            }
        }
    }

    private void handleConnection(Socket client) {
        try {
            client.setSoTimeout(30000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            RequestHead head = readHead(in);
            if (head == null) {
                sendStatus(out, 400, "Bad Request");
                return;
            }

            // The engine page + its script are served UNGATED (they're just the
            // bundled engine code, not sensitive) so the hidden GeckoSession can
            // load them; the file endpoints stay token-gated.
            if ("GET".equals(head.method) && "/engine".equals(head.path)) {
                sendAsset(out, "engine.html", "text/html; charset=utf-8");
            } else if ("GET".equals(head.method) && "/engine-page.js".equals(head.path)) {
                sendAsset(out, "engine-page.js", "application/javascript; charset=utf-8");
            } else if (!mToken.equals(head.query.get("t"))) {
                sendStatus(out, 403, "Forbidden");
            } else if ("GET".equals(head.method) && "/read".equals(head.path)) {
                handleRead(head, out);
            } else if ("POST".equals(head.method) && "/write".equals(head.path)) {
                handleWrite(head, in, out);
            } else {
                sendStatus(out, 404, "Not Found");
            }
            out.flush();
        } catch (IOException e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "connection: " + e.getMessage());
            }
        } finally {
            closeQuietly(client);
        }
    }

    private void sendAsset(OutputStream out, String assetName, String contentType) throws IOException {
        byte[] body;
        try (InputStream asset = mContext.getAssets().open("p2pshare/" + assetName)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[STREAM_BUFFER];
            int read;
            while ((read = asset.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            body = buffer.toByteArray();
        }
        String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
    }

    private void handleRead(RequestHead head, OutputStream out) throws IOException {
        String path = mReadPath;
        if (path == null) {
            sendStatus(out, 404, "Not Found");
            return;
        }
        long from;
        long lenCap;
        try {
            from = Long.parseLong(head.query.getOrDefault("from", "0"));
            lenCap = Long.parseLong(head.query.getOrDefault("len", "-1"));
        } catch (NumberFormatException e) {
            sendStatus(out, 400, "Bad Request");
            return;
        }
        if (from < 0) {
            sendStatus(out, 400, "Bad Request");
            return;
        }
        // RestoredFileAccess: a restored (foreign-owned) download opens via
        // the persisted SAF grant where a plain FileInputStream would EACCES.
        ParcelFileDescriptor pfd = RestoredFileAccess.openReadOnly(mContext, path);
        if (pfd == null) {
            sendStatus(out, 404, "Not Found");
            return;
        }
        try (ParcelFileDescriptor held = pfd;
             FileInputStream stream = new FileInputStream(held.getFileDescriptor())) {
            long total = held.getStatSize();
            if (from > total) {
                sendStatus(out, 400, "Bad Request");
                return;
            }
            long length = total - from;
            if (lenCap >= 0 && lenCap < length) {
                length = lenCap;
            }
            if (!skipFully(stream, from)) {
                sendStatus(out, 500, "Seek Failed");
                return;
            }
            String header = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Content-Length: " + length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.US_ASCII));
            // Bounded copy: with a len cap the stream has more bytes than the
            // declared body — never write past Content-Length.
            byte[] buffer = new byte[STREAM_BUFFER];
            long remaining = length;
            while (mRunning && remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int read = stream.read(buffer, 0, want);
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    /**
     * InputStream.skip may skip fewer bytes than asked even mid-file; loop to
     * the exact offset (the ranged read's byte positions must be precise —
     * a short skip would silently serve the wrong bytes).
     */
    private static boolean skipFully(InputStream stream, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = stream.skip(remaining);
            if (skipped <= 0) {
                return false;
            }
            remaining -= skipped;
        }
        return true;
    }

    private void handleWrite(RequestHead head, InputStream in, OutputStream out) throws IOException {
        long offset;
        long declared;
        try {
            offset = Long.parseLong(head.query.getOrDefault("off", "-1"));
            declared = Long.parseLong(head.headers.getOrDefault("content-length", "-1"));
        } catch (NumberFormatException e) {
            sendStatus(out, 400, "Bad Request");
            return;
        }
        if (offset < 0 || declared < 0 || declared > MAX_WRITE_BODY) {
            sendStatus(out, 400, "Bad Request");
            return;
        }
        synchronized (mWriteLock) {
            if (mWriteFile == null) {
                sendStatus(out, 409, "Conflict");
                return;
            }
            // The offset check makes writes idempotent-safe: a duplicated or
            // out-of-order request cannot silently corrupt the file.
            if (offset != mWritten) {
                if (offset == 0 && mWritten > 0) {
                    // The one sanctioned mismatch: the write side was armed to
                    // resume a partial, but the sender refused (tail hash
                    // mismatch = different file behind the same name) and is
                    // streaming from byte 0. Drop the kept bytes and restart.
                    // Safe against the duplicate-request worry the strict rule
                    // guards: the engine's POSTs are chained on one promise and
                    // never retried, so a mid-transfer off=0 cannot recur.
                    mWriteFile.setLength(0);
                    mWriteFile.seek(0);
                    mWritten = 0;
                } else {
                    sendStatus(out, 409, "Conflict");
                    return;
                }
            }
            byte[] buffer = new byte[STREAM_BUFFER];
            long remaining = declared;
            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, want);
                if (read < 0) {
                    throw new IOException("body truncated");
                }
                mWriteFile.write(buffer, 0, read);
                remaining -= read;
            }
            mWritten += declared;
        }
        sendStatus(out, 204, "No Content");
    }

    /** Cap the request head so a malformed client can't grow it unbounded. */
    private static final int MAX_HEAD_BYTES = 16 * 1024;

    /**
     * Minimal HTTP request-head parser — request line + headers, query split.
     * The only clients are our own extension fetch() calls (and the peer's
     * answer POST — see P2pAnswerServer, which shares this parser), so this
     * stays deliberately small (no chunked bodies, no keep-alive).
     *
     * <p><b>Reads raw bytes up to and INCLUDING the terminating {@code \r\n\r\n},
     * and no further</b>, so {@code in} is left positioned EXACTLY at the first
     * body byte. This is load-bearing: a {@code BufferedReader}/{@code readLine}
     * approach leaves the blank line's final {@code \n} unconsumed (readLine
     * consumes the {@code \r}, sets an internal {@code skipLF}, and returns
     * before eating the {@code \n}), so a subsequent raw body read starts one
     * byte early — it takes the stray {@code \n} as {@code body[0]} and DROPS
     * the last real byte. For a deflate-compressed answer/chunk that one lost
     * byte corrupts the whole stream ("operation aborted" on decode). Parsing
     * the head as bytes and stopping at the exact terminator avoids it.
     */
    static RequestHead readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        // Match the CRLFCRLF terminator; `match` is the index into it.
        final byte[] term = {'\r', '\n', '\r', '\n'};
        int match = 0;
        int b;
        while ((b = in.read()) >= 0) {
            buffer.write(b);
            if (b == term[match]) {
                match++;
                if (match == term.length) {
                    break;
                }
            } else {
                // A stray '\r' can still begin a fresh terminator.
                match = (b == '\r') ? 1 : 0;
            }
            if (buffer.size() > MAX_HEAD_BYTES) {
                return null;
            }
        }
        if (buffer.size() == 0) {
            return null;
        }
        String[] lines = new String(buffer.toByteArray(), StandardCharsets.US_ASCII)
                .split("\r\n");
        if (lines.length == 0 || lines[0].isEmpty()) {
            return null;
        }
        String[] parts = lines[0].split(" ");
        if (parts.length < 2) {
            return null;
        }
        RequestHead head = new RequestHead();
        head.method = parts[0];
        String target = parts[1];
        int queryStart = target.indexOf('?');
        if (queryStart >= 0) {
            head.path = target.substring(0, queryStart);
            for (String pair : target.substring(queryStart + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    head.query.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        } else {
            head.path = target;
        }
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                head.headers.put(
                        line.substring(0, colon).trim().toLowerCase(Locale.US),
                        line.substring(colon + 1).trim());
            }
        }
        return head;
    }

    static void sendStatus(OutputStream out, int code, String reason) throws IOException {
        String response = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Best-effort.
        }
    }

    static final class RequestHead {
        String method;
        String path;
        final HashMap<String, String> query = new HashMap<>();
        final HashMap<String, String> headers = new HashMap<>();
    }
}

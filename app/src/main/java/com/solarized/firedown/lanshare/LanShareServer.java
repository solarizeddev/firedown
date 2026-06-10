package com.solarized.firedown.lanshare;

import android.content.res.AssetManager;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.BuildConfig;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "Send to browser" — a tiny, ephemeral HTTP server that shares one or more
 * finished downloads with any browser on the local network.
 *
 * <p><b>Lifecycle = the share sheet's lifecycle.</b> {@link #start} binds the
 * socket; {@link #stop} closes it. Nothing listens when the sheet isn't open —
 * there is no background service, no persisted visibility, no discovery
 * announcement. The session (PIN, cookie, file list) dies with the sheet.
 *
 * <p><b>Access control:</b> the bare URL ({@code http://ip:port/}) only ever
 * yields the PIN page. A correct PIN (typed, or carried by the QR's
 * {@code ?pin=} form) sets a random {@code HttpOnly} session cookie that
 * gates the file list and the file bytes. Three wrong attempts lock the
 * session permanently — the 4-digit space cannot be brute-forced. The PIN
 * protects <i>access</i> (who on the LAN can download), not transport
 * secrecy: this is plain HTTP, so a passive sniffer on a hostile network can
 * still capture the bytes. That ceiling is accepted for v1 (TLS on a LAN IP
 * means self-signed-certificate interstitials that would kill the
 * zero-install flow); a LocalSend-protocol phase would bring pinned HTTPS.
 *
 * <p>The vault never reaches this class: the Send affordance only exists on
 * finished, non-safe entries (the options sheet's quick row), same contract
 * as the backup mirror — vault content does not leave the device.
 */
public final class LanShareServer {

    private static final String TAG = LanShareServer.class.getSimpleName();

    /** Fixed preferred port (shared with nothing — chosen to be memorable);
     *  falls back to an ephemeral port if taken. */
    private static final int PREFERRED_PORT = 53317;
    private static final int MAX_PIN_ATTEMPTS = 3;
    private static final String COOKIE_NAME = "fdshare";

    /** One shared file: display name, on-disk file, mime. */
    public static final class SharedFile {
        public final String name;
        public final File file;
        public final String mime;

        public SharedFile(@NonNull String name, @NonNull File file, @Nullable String mime) {
            this.name = name;
            this.file = file;
            this.mime = TextUtils.isEmpty(mime) ? "application/octet-stream" : mime;
        }
    }

    private final List<SharedFile> mFiles;
    private final String mPin;
    private final String mCookieValue;
    private final String mDeviceName;
    // The served pages are static asset templates under assets/lanshare/
    // (style.css, logo.svg, pin.html, files.html, row.html, locked.html,
    // notfound.html); this server only substitutes {{TOKENS}} and serves the
    // bytes — no HTML is built in Java. Assets are read once and cached.
    private final AssetManager mAssets;
    private final Map<String, byte[]> mAssetCache = new HashMap<>();
    private final AtomicInteger mPinAttempts = new AtomicInteger(0);
    private final AtomicBoolean mLocked = new AtomicBoolean(false);
    private final AtomicBoolean mRunning = new AtomicBoolean(false);

    private ServerSocket mServerSocket;
    private Thread mAcceptThread;
    private ExecutorService mHandlerPool;

    public LanShareServer(@NonNull List<SharedFile> files, @NonNull String deviceName,
                          @NonNull AssetManager assets) {
        this.mFiles = new ArrayList<>(files);
        this.mDeviceName = deviceName;
        this.mAssets = assets;
        SecureRandom random = new SecureRandom();
        this.mPin = String.format(Locale.ROOT, "%04d", random.nextInt(10_000));
        byte[] cookie = new byte[16];
        random.nextBytes(cookie);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : cookie) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        this.mCookieValue = hex.toString();
    }

    /** The 4-digit PIN shown on the sender's screen. */
    @NonNull
    public String getPin() {
        return mPin;
    }

    /** Bound port, valid after {@link #start}. */
    public int getPort() {
        ServerSocket socket = mServerSocket;
        return socket != null ? socket.getLocalPort() : -1;
    }

    /**
     * The device's site-local IPv4 (Wi-Fi) address, or null when not on a
     * LAN. Static so the UI can check connectivity before starting.
     */
    @Nullable
    public static String getLocalIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.isLoopbackAddress() || address.getAddress().length != 4) {
                        continue;
                    }
                    if (!address.isSiteLocalAddress()) {
                        continue;
                    }
                    String name = networkInterface.getName();
                    if (name != null && name.startsWith("wlan")) {
                        return address.getHostAddress();
                    }
                    if (fallback == null) {
                        fallback = address.getHostAddress();
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            Log.e(TAG, "getLocalIpv4 failed", e);
            return null;
        }
    }

    /** Bind and start serving. Throws on bind failure. */
    public synchronized void start() throws IOException {
        if (mRunning.get()) {
            return;
        }
        ServerSocket socket;
        try {
            socket = new ServerSocket(PREFERRED_PORT);
        } catch (IOException preferredTaken) {
            socket = new ServerSocket(0);
        }
        mServerSocket = socket;
        mHandlerPool = Executors.newFixedThreadPool(3);
        mRunning.set(true);
        mAcceptThread = new Thread(this::acceptLoop, "LanShareServer");
        mAcceptThread.start();
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "started on port " + socket.getLocalPort());
        }
    }

    /** Close the socket and kill all handlers. Idempotent. */
    public synchronized void stop() {
        if (!mRunning.getAndSet(false)) {
            return;
        }
        try {
            if (mServerSocket != null) {
                mServerSocket.close();
            }
        } catch (IOException ignored) {
        }
        if (mHandlerPool != null) {
            mHandlerPool.shutdownNow();
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "stopped");
        }
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private void acceptLoop() {
        while (mRunning.get()) {
            try {
                Socket client = mServerSocket.accept();
                mHandlerPool.execute(() -> handleConnection(client));
            } catch (IOException e) {
                // Socket closed by stop() — exit quietly.
                return;
            } catch (Exception e) {
                Log.e(TAG, "accept failed", e);
                return;
            }
        }
    }

    private void handleConnection(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(15_000);
            InputStream rawIn = socket.getInputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(rawIn, StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();

            String requestLine = in.readLine();
            if (TextUtils.isEmpty(requestLine)) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0];
            String target = parts[1];

            // Headers — we only need Cookie and Content-Length.
            String cookieHeader = null;
            int contentLength = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (name.equals("cookie")) {
                    cookieHeader = value;
                } else if (name.equals("content-length")) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            String path = target;
            String query = null;
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }

            // Static assets — branding, not data — served unauthenticated so
            // the pre-auth PIN page can pull them. They leak nothing.
            if (path.equals("/logo.svg") && method.equals("GET")) {
                sendAsset(out, "logo.svg", "image/svg+xml");
                return;
            }
            if (path.equals("/style.css") && method.equals("GET")) {
                sendAsset(out, "style.css", "text/css; charset=utf-8");
                return;
            }

            boolean authed = isAuthed(cookieHeader);

            if (mLocked.get() && !authed) {
                sendHtml(out, 403, page("locked.html"));
                return;
            }

            if (path.equals("/") && method.equals("GET")) {
                // QR path: /?pin=XXXX authenticates in one hop.
                String pinParam = queryParam(query, "pin");
                if (pinParam != null && checkPin(pinParam)) {
                    sendRedirectWithCookie(out);
                    return;
                }
                if (pinParam != null && mLocked.get()) {
                    sendHtml(out, 403, page("locked.html"));
                    return;
                }
                if (authed) {
                    sendRedirect(out, "/s");
                    return;
                }
                sendHtml(out, 200, renderPin(pinParam != null));
                return;
            }

            if (path.equals("/pin") && method.equals("POST")) {
                char[] body = new char[Math.min(Math.max(contentLength, 0), 1024)];
                int read = 0;
                while (read < body.length) {
                    int n = in.read(body, read, body.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                String pinParam = queryParam(new String(body, 0, read), "pin");
                if (pinParam != null && checkPin(pinParam)) {
                    sendRedirectWithCookie(out);
                    return;
                }
                if (mLocked.get()) {
                    sendHtml(out, 403, page("locked.html"));
                    return;
                }
                sendHtml(out, 200, renderPin(true));
                return;
            }

            if (path.equals("/s") && method.equals("GET")) {
                if (!authed) {
                    sendRedirect(out, "/");
                    return;
                }
                sendHtml(out, 200, renderFiles());
                return;
            }

            if (path.startsWith("/f/") && method.equals("GET")) {
                if (!authed) {
                    sendRedirect(out, "/");
                    return;
                }
                int index;
                try {
                    index = Integer.parseInt(path.substring(3));
                } catch (NumberFormatException e) {
                    sendHtml(out, 404, page("notfound.html"));
                    return;
                }
                if (index < 0 || index >= mFiles.size()) {
                    sendHtml(out, 404, page("notfound.html"));
                    return;
                }
                sendFile(out, mFiles.get(index));
                return;
            }

            sendHtml(out, 404, page("notfound.html"));
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "connection handler", e);
            }
        }
    }

    private boolean isAuthed(@Nullable String cookieHeader) {
        if (cookieHeader == null) {
            return false;
        }
        for (String part : cookieHeader.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (COOKIE_NAME.equals(name) && mCookieValue.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** Constant-shape PIN check with the attempt counter and lock. */
    private boolean checkPin(@NonNull String candidate) {
        if (mLocked.get()) {
            return false;
        }
        if (mPin.equals(candidate.trim())) {
            return true;
        }
        if (mPinAttempts.incrementAndGet() >= MAX_PIN_ATTEMPTS) {
            mLocked.set(true);
            Log.w(TAG, "session locked after " + MAX_PIN_ATTEMPTS + " bad PIN attempts");
        }
        return false;
    }

    @Nullable
    private static String queryParam(@Nullable String query, @NonNull String key) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (!key.equals(pair.substring(0, eq))) {
                continue;
            }
            try {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Responses
    // ------------------------------------------------------------------

    private void sendHtml(OutputStream out, int status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String statusText = status == 200 ? "OK" : status == 403 ? "Forbidden" : "Not Found";
        String head = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private void sendAsset(OutputStream out, String name, String contentType) throws IOException {
        byte[] body = readAsset(name);
        if (body == null) {
            sendHtml(out, 404, page("notfound.html"));
            return;
        }
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: max-age=3600\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    // ------------------------------------------------------------------
    // Asset templates
    // ------------------------------------------------------------------

    /** Read assets/lanshare/<name> once, cached. Null if missing. */
    @Nullable
    private synchronized byte[] readAsset(String name) {
        byte[] cached = mAssetCache.get(name);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = mAssets.open("lanshare/" + name)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            byte[] bytes = buf.toByteArray();
            mAssetCache.put(name, bytes);
            return bytes;
        } catch (IOException e) {
            Log.e(TAG, "asset read failed: " + name, e);
            return null;
        }
    }

    /** A template asset as a UTF-8 string ("" if missing — never null so the
     *  caller's substitutions stay simple). */
    private String page(String name) {
        byte[] bytes = readAsset(name);
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private String renderPin(boolean wrongAttempt) {
        String error = "";
        if (wrongAttempt) {
            int left = MAX_PIN_ATTEMPTS - mPinAttempts.get();
            error = "<div class=\"err\">Wrong PIN &mdash; " + Math.max(left, 0)
                    + (left == 1 ? " attempt" : " attempts")
                    + " left before this session locks</div>";
        }
        return page("pin.html")
                .replace("{{ERROR}}", error)
                .replace("{{DEVICE}}", escapeHtml(mDeviceName));
    }

    private String renderFiles() {
        String row = page("row.html");
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < mFiles.size(); i++) {
            SharedFile f = mFiles.get(i);
            rows.append(row
                    .replace("{{ICON}}", iconFor(f.mime))
                    .replace("{{NAME}}", escapeHtml(f.name))
                    .replace("{{TYPE}}", typeLabel(f.mime))
                    .replace("{{SIZE}}", formatSize(f.file.length()))
                    .replace("{{INDEX}}", Integer.toString(i)));
        }
        String count = mFiles.size() + (mFiles.size() == 1 ? " file" : " files");
        return page("files.html")
                .replace("{{DEVICE}}", escapeHtml(mDeviceName))
                .replace("{{COUNT}}", count)
                .replace("{{FILES}}", rows.toString());
    }

    private static String iconFor(String mime) {
        if (mime == null) {
            return "&#128196;";
        }
        if (mime.startsWith("video/")) {
            return "&#127916;";
        }
        if (mime.startsWith("audio/")) {
            return "&#127911;";
        }
        if (mime.startsWith("image/")) {
            return "&#128247;";
        }
        return "&#128196;";
    }

    /** Friendly type word for display (the precise mime drives Content-Type
     *  + the icon; a receiver shouldn't see "video/iso.segment"). */
    private static String typeLabel(String mime) {
        if (mime == null) {
            return "File";
        }
        if (mime.startsWith("video/")) {
            return "Video";
        }
        if (mime.startsWith("audio/")) {
            return "Audio";
        }
        if (mime.startsWith("image/")) {
            return "Image";
        }
        if (mime.equals("application/pdf")) {
            return "PDF";
        }
        if (mime.startsWith("text/")) {
            return "Text";
        }
        return "File";
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        }
        if (bytes >= 1L << 20) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (double) (1L << 20));
        }
        if (bytes >= 1L << 10) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / (double) (1L << 10));
        }
        return bytes + " B";
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void sendRedirect(OutputStream out, String location) throws IOException {
        String head = "HTTP/1.1 303 See Other\r\n"
                + "Location: " + location + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void sendRedirectWithCookie(OutputStream out) throws IOException {
        String head = "HTTP/1.1 303 See Other\r\n"
                + "Location: /s\r\n"
                + "Set-Cookie: " + COOKIE_NAME + "=" + mCookieValue + "; HttpOnly; Path=/; SameSite=Strict\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void sendFile(OutputStream out, SharedFile shared) throws IOException {
        File file = shared.file;
        if (!file.exists() || !file.canRead()) {
            sendHtml(out, 404, page("notfound.html"));
            return;
        }
        long length = file.length();
        // RFC 6266 / RFC 8187: ASCII fallback + UTF-8 form for non-ASCII names.
        String asciiName = shared.name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'");
        String encodedName = Uri.encode(shared.name);
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + shared.mime + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Content-Disposition: attachment; filename=\"" + asciiName
                + "\"; filename*=UTF-8''" + encodedName + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream fis = new FileInputStream(file)) {
            int n;
            while ((n = fis.read(buffer)) > 0) {
                if (!mRunning.get()) {
                    return; // stop() mid-transfer — socket is going away anyway
                }
                out.write(buffer, 0, n);
            }
        }
        out.flush();
    }
}

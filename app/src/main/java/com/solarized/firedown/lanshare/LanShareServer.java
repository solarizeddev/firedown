package com.solarized.firedown.lanshare;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.data.RestoredFileAccess;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.SequenceInputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

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
 * session permanently — the 4-digit space cannot be brute-forced.
 *
 * <p><b>Transport:</b> TLS by default, with a self-signed per-install
 * certificate ({@link LanShareTls}) — the receiver clicks through one
 * "connection not private" interstitial, and in exchange a passive sniffer
 * on hostile Wi-Fi (open networks; shared-PSK WPA2 where anyone with the
 * password can decrypt the air) gets only ECDHE ciphertext: file bytes, the
 * QR's {@code ?pin=} and the session cookie all stop being readable. The
 * port speaks BOTH protocols: the first byte of each connection is sniffed
 * (a TLS ClientHello starts 0x16), plain-HTTP requests get the branded
 * ONBOARDING page (it explains the upcoming certificate warning, Continue
 * goes to {@code https://}; the QR lands here first, PIN in the URL
 * fragment so it never crosses plaintext), and when the TLS identity is
 * unavailable the server degrades to serving plain HTTP rather than not
 * sharing at all. Remaining ceiling, documented and
 * accepted: an ACTIVE MITM can present its own self-signed cert — the
 * receiver can't tell it apart; cert-pinned verification is the
 * LocalSend-protocol phase.
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
    /**
     * Languages with a block in {@code assets/lanshare/i18n.json} — the app's
     * 16 translated locales + English. The served pages are localized per
     * REQUEST from the receiver's own {@code Accept-Language} header (the
     * sender's locale is irrelevant — the reader is the other person);
     * anything unsupported falls back to English, and any key missing from
     * a language block falls back to the English value.
     */
    private static final String[] SUPPORTED_LANGS = {"en", "bg", "cs", "de", "es", "et",
            "fi", "fr", "hi", "ja", "nl", "pl", "pt", "ru", "tr", "uk", "zh"};

    /** One shared file: display name, on-disk file, mime. */
    public static final class SharedFile {
        public final String name;
        public final File file;
        public final String mime;
        /** App context, so the server can open a foreign-owned RESTORED file
         *  through the persisted SAF grant (RestoredFileAccess) rather than a
         *  bare FileInputStream that would EACCES. */
        public final Context context;

        public SharedFile(@NonNull Context context, @NonNull String name,
                          @NonNull File file, @Nullable String mime) {
            this.context = context.getApplicationContext();
            this.name = name;
            this.file = file;
            this.mime = TextUtils.isEmpty(mime) ? "application/octet-stream" : mime;
        }
    }

    /**
     * A connected socket whose input stream replays one already-consumed
     * byte before the live stream — the transport sniff reads the first
     * byte to tell TLS from HTTP, and the layered SSLSocket needs to see
     * the complete ClientHello including it. Delegates everything else.
     */
    private static final class PrereadSocket extends Socket {
        private final Socket mDelegate;
        private final InputStream mInput;

        PrereadSocket(@NonNull Socket delegate, int firstByte) throws IOException {
            this.mDelegate = delegate;
            this.mInput = new SequenceInputStream(
                    new ByteArrayInputStream(new byte[]{(byte) firstByte}),
                    delegate.getInputStream());
        }

        @Override
        public InputStream getInputStream() {
            return mInput;
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return mDelegate.getOutputStream();
        }

        @Override
        public synchronized void close() throws IOException {
            mDelegate.close();
        }

        @Override
        public InetAddress getInetAddress() {
            return mDelegate.getInetAddress();
        }

        @Override
        public InetAddress getLocalAddress() {
            return mDelegate.getLocalAddress();
        }

        @Override
        public int getPort() {
            return mDelegate.getPort();
        }

        @Override
        public int getLocalPort() {
            return mDelegate.getLocalPort();
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return mDelegate.getRemoteSocketAddress();
        }

        @Override
        public SocketAddress getLocalSocketAddress() {
            return mDelegate.getLocalSocketAddress();
        }

        @Override
        public boolean isConnected() {
            return mDelegate.isConnected();
        }

        @Override
        public boolean isBound() {
            return mDelegate.isBound();
        }

        @Override
        public boolean isClosed() {
            return mDelegate.isClosed();
        }

        @Override
        public synchronized void setSoTimeout(int timeout) throws SocketException {
            mDelegate.setSoTimeout(timeout);
        }

        @Override
        public synchronized int getSoTimeout() throws SocketException {
            return mDelegate.getSoTimeout();
        }

        @Override
        public void setTcpNoDelay(boolean on) throws SocketException {
            mDelegate.setTcpNoDelay(on);
        }

        @Override
        public boolean getTcpNoDelay() throws SocketException {
            return mDelegate.getTcpNoDelay();
        }

        @Override
        public void setSoLinger(boolean on, int linger) throws SocketException {
            mDelegate.setSoLinger(on, linger);
        }

        @Override
        public int getSoLinger() throws SocketException {
            return mDelegate.getSoLinger();
        }

        @Override
        public void setKeepAlive(boolean on) throws SocketException {
            mDelegate.setKeepAlive(on);
        }

        @Override
        public boolean getKeepAlive() throws SocketException {
            return mDelegate.getKeepAlive();
        }

        @Override
        public synchronized void setSendBufferSize(int size) throws SocketException {
            mDelegate.setSendBufferSize(size);
        }

        @Override
        public synchronized int getSendBufferSize() throws SocketException {
            return mDelegate.getSendBufferSize();
        }

        @Override
        public synchronized void setReceiveBufferSize(int size) throws SocketException {
            mDelegate.setReceiveBufferSize(size);
        }

        @Override
        public synchronized int getReceiveBufferSize() throws SocketException {
            return mDelegate.getReceiveBufferSize();
        }

        @Override
        public void shutdownInput() throws IOException {
            mDelegate.shutdownInput();
        }

        @Override
        public void shutdownOutput() throws IOException {
            mDelegate.shutdownOutput();
        }

        @Override
        public boolean isInputShutdown() {
            return mDelegate.isInputShutdown();
        }

        @Override
        public boolean isOutputShutdown() {
            return mDelegate.isOutputShutdown();
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
    /** Per-language resolved string tables (en merged under), built lazily. */
    private final Map<String, Map<String, String>> mStringsCache = new HashMap<>();
    private final AtomicInteger mPinAttempts = new AtomicInteger(0);
    private final AtomicBoolean mLocked = new AtomicBoolean(false);
    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    /** Null = TLS unavailable, serve plain HTTP (degraded but working). */
    private final SSLContext mSslContext;

    private ServerSocket mServerSocket;
    private Thread mAcceptThread;
    private ExecutorService mHandlerPool;
    /**
     * Live per-connection sockets, closed by {@link #stop} for deterministic
     * teardown. SO_TIMEOUT bounds only READS — a receiver that stops ACKing
     * mid-download leaves sendFile blocked in a socket WRITE indefinitely
     * (no write timeout, no keepalive), and shutdownNow()'s interrupt cannot
     * unblock socket I/O; only closing the socket can.
     */
    private final Set<Socket> mClientSockets = ConcurrentHashMap.newKeySet();

    public LanShareServer(@NonNull List<SharedFile> files, @NonNull String deviceName,
                          @NonNull AssetManager assets, @Nullable SSLContext sslContext) {
        this.mFiles = new ArrayList<>(files);
        this.mDeviceName = deviceName;
        this.mAssets = assets;
        this.mSslContext = sslContext;
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

    /** Whether connections are TLS-encrypted (false = plain-HTTP fallback). */
    public boolean isTls() {
        return mSslContext != null;
    }

    /**
     * Interface-name prefixes a receiver could actually reach: Wi-Fi STA
     * (wlan), the LocalOnlyHotspot AP (ap/swlan/softap, vendor-dependent),
     * and wired ethernet. Deliberately an ALLOWLIST, not "any site-local
     * address": carrier-grade NAT hands cellular interfaces (rmnet*) 10.x
     * addresses, which pass isSiteLocalAddress() — the old open fallback
     * would show a carrier IP on a 5G-only device that no peer can ever
     * reach. VPN tunnels (tun*) are equally unreachable and also excluded
     * by not being listed.
     */
    private static final String[] SHAREABLE_INTERFACE_PREFIXES =
            {"wlan", "ap", "swlan", "softap", "eth"};

    /**
     * The device's reachable site-local IPv4 (Wi-Fi, hotspot AP, or
     * ethernet), or null when there is none. Static so the UI can check
     * connectivity before starting. Prefers the Wi-Fi STA (wlan*).
     */
    @Nullable
    public static String getLocalIpv4() {
        List<String[]> candidates = listShareableIpv4s();
        for (String[] candidate : candidates) {
            if (candidate[0].startsWith("wlan")) {
                return candidate[1];
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0)[1];
    }

    /**
     * The LocalOnlyHotspot AP's IPv4 — the address a receiver JOINED TO THE
     * HOTSPOT can reach. Distinct from {@link #getLocalIpv4()} because the
     * preference is inverted: when the user switched to the direct
     * connection FROM a working Wi-Fi (AP-isolation escape), the wlan STA
     * keeps its address, and that address is on the wrong network for a
     * hotspot client — preferring wlan there would serve an unreachable URL.
     *
     * @param staIp the STA address captured BEFORE the hotspot came up (or
     *              null) — used to tell the AP apart from the STA on vendors
     *              that name the AP interface wlan1/wlan2 (no ap- or swlan-
     *              prefix to recognise it by).
     */
    @Nullable
    public static String getHotspotIpv4(@Nullable String staIp) {
        List<String[]> candidates = listShareableIpv4s();
        // A recognisably-named AP interface wins outright.
        for (String[] candidate : candidates) {
            String name = candidate[0];
            if (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap")) {
                return candidate[1];
            }
        }
        // Otherwise: any address that isn't the pre-hotspot STA one (covers
        // a wlan1/wlan2 AP next to the wlan0 STA).
        for (String[] candidate : candidates) {
            if (!candidate[1].equals(staIp)) {
                return candidate[1];
            }
        }
        // Only the old STA address remains (or nothing) — Wi-Fi may have
        // been dropped by the hotspot start; better than null.
        return candidates.isEmpty() ? null : candidates.get(0)[1];
    }

    /** Every (interface name, site-local IPv4) pair on the allowlisted interfaces. */
    @NonNull
    private static List<String[]> listShareableIpv4s() {
        List<String[]> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                String name = networkInterface.getName();
                if (!isShareableInterface(name)) {
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
                    result.add(new String[]{name, address.getHostAddress()});
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "listShareableIpv4s failed", e);
        }
        return result;
    }

    private static boolean isShareableInterface(@Nullable String name) {
        if (name == null) {
            return false;
        }
        for (String prefix : SHAREABLE_INTERFACE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
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
        // Unblock any handler stuck in a socket write (see mClientSockets).
        for (Socket client : mClientSockets) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
        mClientSockets.clear();
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
        // Transport negotiation: one port, both protocols. The first byte
        // tells them apart — a TLS ClientHello record starts with 0x16; no
        // HTTP method does. TLS connections are wrapped server-side (the
        // peeked byte handed back via the factory's `consumed` stream);
        // plain HTTP gets a 301 to the https:// URL so a typed ip:port
        // still lands on the encrypted flow. With no TLS identity
        // (LanShareTls failed), plain HTTP is served directly — degraded
        // beats not sharing.
        Socket socket = client;
        mClientSockets.add(client);
        try {
            client.setSoTimeout(15_000);
            InputStream transportIn = client.getInputStream();
            int firstByte = transportIn.read();
            if (firstByte < 0) {
                return;
            }
            InputStream requestIn;
            if (firstByte == 0x16) {
                if (mSslContext == null) {
                    // Client speaks TLS, we can't — nothing useful to say.
                    return;
                }
                // Android's SSLSocketFactory lacks the JDK's
                // createSocket(Socket, InputStream consumed, boolean)
                // overload, so the peeked byte is handed back through a
                // delegating socket whose getInputStream() prepends it —
                // the layered TLS socket reads the full ClientHello.
                Socket preread = new PrereadSocket(client, firstByte);
                SSLSocket sslSocket = (SSLSocket) mSslContext.getSocketFactory().createSocket(
                        preread, client.getInetAddress().getHostAddress(), client.getPort(),
                        true);
                sslSocket.setUseClientMode(false);
                try {
                    // Explicit handshake so a failure logs HERE with its
                    // real cause instead of surfacing as a read error.
                    sslSocket.startHandshake();
                } catch (IOException handshake) {
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "TLS handshake failed (client sees connection closed)",
                                handshake);
                    }
                    return;
                }
                socket = sslSocket;
                requestIn = sslSocket.getInputStream();
            } else {
                PushbackInputStream pushback = new PushbackInputStream(transportIn, 1);
                pushback.unread(firstByte);
                requestIn = pushback;
                if (mSslContext != null) {
                    serveOnboarding(pushback, client);
                    return;
                }
            }
            serveRequest(socket, requestIn);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "connection transport", e);
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            try {
                client.close();
            } catch (IOException ignored) {
            }
            mClientSockets.remove(client);
        }
    }

    /**
     * Plain-HTTP side of the dual-protocol port (TLS available): the
     * receiver's ON-RAMP. Instead of a cold 301 into the certificate
     * interstitial, serve a branded onboarding page that explains the
     * warning they're about to accept, with a Continue button to the
     * {@code https://} URL. The QR carries the PIN in the URL FRAGMENT
     * ({@code #p=NNNN}) — fragments never cross the wire, so nothing
     * secret travels over this plaintext request; the page's JS forwards
     * it to the https side as the usual {@code ?pin=}. The two style
     * assets are served here too so the page renders branded.
     */
    private void serveOnboarding(@NonNull InputStream in, @NonNull Socket client)
            throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        String requestLine = reader.readLine();
        String target = "/";
        if (requestLine != null) {
            String[] parts = requestLine.split(" ");
            if (parts.length >= 2 && parts[1].startsWith("/")) {
                target = parts[1];
            }
        }
        String path = target;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        // Drain the headers for Accept-Language (the receiver's locale).
        String acceptLanguage = null;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (name.equals("accept-language")) {
                acceptLanguage = line.substring(colon + 1).trim();
            }
        }
        OutputStream out = client.getOutputStream();
        if (path.equals("/logo.svg")) {
            sendAsset(out, "logo.svg", "image/svg+xml");
            return;
        }
        if (path.equals("/style.css")) {
            sendAsset(out, "style.css", "text/css; charset=utf-8");
            return;
        }
        String lang = resolveLang(acceptLanguage);
        Map<String, String> t = strings(lang);
        InetAddress local = client.getLocalAddress();
        String host = local != null ? local.getHostAddress() : getLocalIpv4();
        String continueUrl = "https://" + host + ":" + getPort() + "/";
        String html = localize(page("onboard.html"), t, lang)
                .replace("{{CONTINUE}}", continueUrl)
                .replace("{{DEVICE}}", escapeHtml(mDeviceName));
        sendHtml(out, 200, html);
    }

    private void serveRequest(Socket socket, InputStream rawIn) {
        try {
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

            // Headers — we need Cookie, Content-Length and Accept-Language
            // (the receiver's locale, which drives page localization).
            String cookieHeader = null;
            String acceptLanguage = null;
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
                } else if (name.equals("accept-language")) {
                    acceptLanguage = value;
                }
            }

            String lang = resolveLang(acceptLanguage);
            Map<String, String> t = strings(lang);

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
                sendHtml(out, 403, localize(page("locked.html"), t, lang));
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
                    sendHtml(out, 403, localize(page("locked.html"), t, lang));
                    return;
                }
                if (authed) {
                    sendRedirect(out, "/s");
                    return;
                }
                sendHtml(out, 200, renderPin(pinParam != null, t, lang));
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
                    sendHtml(out, 403, localize(page("locked.html"), t, lang));
                    return;
                }
                sendHtml(out, 200, renderPin(true, t, lang));
                return;
            }

            if (path.equals("/s") && method.equals("GET")) {
                if (!authed) {
                    sendRedirect(out, "/");
                    return;
                }
                sendHtml(out, 200, renderFiles(t, lang));
                return;
            }

            if (path.startsWith("/f/") && method.equals("GET")) {
                if (!authed) {
                    sendRedirect(out, "/");
                    return;
                }
                // /f/<index>/<name> — the trailing name segment is ignored
                // server-side; it exists so a NATIVE download (the blob-save
                // fallback path) names the file from the URL instead of "0".
                String indexPart = path.substring(3);
                int slash = indexPart.indexOf('/');
                if (slash >= 0) {
                    indexPart = indexPart.substring(0, slash);
                }
                int index;
                try {
                    index = Integer.parseInt(indexPart);
                } catch (NumberFormatException e) {
                    sendHtml(out, 404, localize(page("notfound.html"), t, lang));
                    return;
                }
                if (index < 0 || index >= mFiles.size()) {
                    sendHtml(out, 404, localize(page("notfound.html"), t, lang));
                    return;
                }
                sendFile(out, mFiles.get(index), t, lang);
                return;
            }

            sendHtml(out, 404, localize(page("notfound.html"), t, lang));
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
            // Internal error (missing bundled asset) — English is fine here.
            sendHtml(out, 404, localize(page("notfound.html"), strings("en"), "en"));
            return;
        }
        // no-store, deliberately: a receiver's browser that cached an older
        // stylesheet for an hour kept rendering a stale design under fresh
        // HTML (observed on-device). These assets are tiny and the transfer
        // is LAN-local, so freshness wins over caching; the templates also
        // version the URLs (?v=N) to bust anything already cached.
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
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

    // ------------------------------------------------------------------
    // Localization — receiver-driven (Accept-Language), assets/lanshare/i18n.json
    // ------------------------------------------------------------------

    /**
     * Resolved string table for a language: the English block with the
     * requested language's block merged over it, so a missing key degrades
     * to English per-key instead of failing the page. Cached per language.
     */
    @NonNull
    private synchronized Map<String, String> strings(@NonNull String lang) {
        Map<String, String> cached = mStringsCache.get(lang);
        if (cached != null) {
            return cached;
        }
        Map<String, String> result = new HashMap<>();
        try {
            JSONObject root = new JSONObject(page("i18n.json"));
            fillStrings(result, root.optJSONObject("en"));
            if (!lang.equals("en")) {
                fillStrings(result, root.optJSONObject(lang));
            }
        } catch (Exception e) {
            Log.e(TAG, "i18n load failed — pages fall back to raw tokens", e);
        }
        mStringsCache.put(lang, result);
        return result;
    }

    private static void fillStrings(Map<String, String> out, @Nullable JSONObject block) {
        if (block == null) {
            return;
        }
        Iterator<String> keys = block.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            out.put(key, block.optString(key));
        }
    }

    /** First supported primary language subtag of an Accept-Language header. */
    @NonNull
    private static String resolveLang(@Nullable String acceptLanguage) {
        if (acceptLanguage == null) {
            return "en";
        }
        for (String part : acceptLanguage.split(",")) {
            String tag = part.trim();
            int semicolon = tag.indexOf(';');
            if (semicolon >= 0) {
                tag = tag.substring(0, semicolon).trim();
            }
            int dash = tag.indexOf('-');
            if (dash >= 0) {
                tag = tag.substring(0, dash);
            }
            tag = tag.toLowerCase(Locale.ROOT);
            for (String supported : SUPPORTED_LANGS) {
                if (supported.equals(tag)) {
                    return tag;
                }
            }
        }
        return "en";
    }

    /** Substitute every {{T_*}} token (and {{LANG}}) in a template. */
    private static String localize(String template, Map<String, String> t, String lang) {
        String result = template.replace("{{LANG}}", lang);
        for (Map.Entry<String, String> entry : t.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    /** A single translated string, never null. */
    @NonNull
    private static String tr(Map<String, String> t, String key) {
        String value = t.get(key);
        return value != null ? value : "";
    }

    /** A template asset as a UTF-8 string ("" if missing — never null so the
     *  caller's substitutions stay simple). */
    private String page(String name) {
        byte[] bytes = readAsset(name);
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private String renderPin(boolean wrongAttempt, Map<String, String> t, String lang) {
        String error = "";
        if (wrongAttempt) {
            int left = Math.max(MAX_PIN_ATTEMPTS - mPinAttempts.get(), 0);
            String message = tr(t, left == 1 ? "T_PIN_WRONG_ONE" : "T_PIN_WRONG_MANY")
                    .replace("{{N}}", Integer.toString(left));
            error = "<div class=\"err\">" + message + "</div>";
        }
        return localize(page("pin.html"), t, lang)
                .replace("{{ERROR}}", error)
                .replace("{{DEVICE}}", escapeHtml(mDeviceName));
    }

    private String renderFiles(Map<String, String> t, String lang) {
        String row = localize(page("row.html"), t, lang);
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < mFiles.size(); i++) {
            SharedFile f = mFiles.get(i);
            rows.append(row
                    .replace("{{ICON}}", iconFor(f.mime))
                    .replace("{{NAME}}", escapeHtml(f.name))
                    .replace("{{NAMEURL}}", Uri.encode(f.name))
                    .replace("{{TYPE}}", tr(t, typeLabelKey(f.mime)))
                    .replace("{{SIZE}}", formatSize(RestoredFileAccess.length(f.context, f.file.getPath())))
                    .replace("{{INDEX}}", Integer.toString(i)));
        }
        String count = mFiles.size() == 1
                ? tr(t, "T_FILE_ONE")
                : tr(t, "T_FILES_MANY").replace("{{N}}", Integer.toString(mFiles.size()));
        return localize(page("files.html"), t, lang)
                .replace("{{DEVICE}}", escapeHtml(mDeviceName))
                .replace("{{COUNT}}", count)
                .replace("{{FILES}}", rows.toString());
    }

    // Inline SVGs of the app's OWN drawables (ic_movie_24 / ic_headphones_24 /
    // ic_baseline_image_24 / ic_draft_24 pathData verbatim), white on the
    // coral tile. Emoji were device-font dependent and rendered badly
    // (observed on-device: Samsung's clapperboard on the gradient).
    private static final String SVG_OPEN_24 = "<svg viewBox=\"0 0 24 24\" fill=\"#fff\"><path d=\"";
    private static final String SVG_OPEN_960 = "<svg viewBox=\"0 0 960 960\" fill=\"#fff\"><path d=\"";
    private static final String SVG_CLOSE = "\"/></svg>";

    private static final String ICON_VIDEO = SVG_OPEN_24
            + "M4,4 L6,8H9L7,4H9L11,8H14L12,4H14L16,8H19L17,4H20Q20.825,4 21.413,4.588Q22,5.175 22,6V18Q22,18.825 21.413,19.413Q20.825,20 20,20H4Q3.175,20 2.588,19.413Q2,18.825 2,18V6Q2,5.175 2.588,4.588Q3.175,4 4,4ZM4,10V18Q4,18 4,18Q4,18 4,18H20Q20,18 20,18Q20,18 20,18V10Z"
            + SVG_CLOSE;
    private static final String ICON_AUDIO = SVG_OPEN_960
            + "M360,840L200,840Q167,840 143.5,816.5Q120,793 120,760L120,480Q120,405 148.5,339.5Q177,274 225.5,225.5Q274,177 339.5,148.5Q405,120 480,120Q555,120 620.5,148.5Q686,177 734.5,225.5Q783,274 811.5,339.5Q840,405 840,480L840,760Q840,793 816.5,816.5Q793,840 760,840L600,840L600,520L760,520L760,480Q760,363 678.5,281.5Q597,200 480,200Q363,200 281.5,281.5Q200,363 200,480L200,520L360,520L360,840Z"
            + SVG_CLOSE;
    private static final String ICON_IMAGE = SVG_OPEN_24
            + "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z"
            + SVG_CLOSE;
    private static final String ICON_FILE = SVG_OPEN_24
            + "M6,22Q5.175,22 4.588,21.413Q4,20.825 4,20V4Q4,3.175 4.588,2.587Q5.175,2 6,2H14L20,8V20Q20,20.825 19.413,21.413Q18.825,22 18,22ZM13,9V4H6Q6,4 6,4Q6,4 6,4V20Q6,20 6,20Q6,20 6,20H18Q18,20 18,20Q18,20 18,20V9Z"
            + SVG_CLOSE;

    private static String iconFor(String mime) {
        if (mime == null) {
            return ICON_FILE;
        }
        if (mime.startsWith("video/")) {
            return ICON_VIDEO;
        }
        if (mime.startsWith("audio/")) {
            return ICON_AUDIO;
        }
        if (mime.startsWith("image/")) {
            return ICON_IMAGE;
        }
        return ICON_FILE;
    }

    /** i18n key of the friendly type word (the precise mime drives
     *  Content-Type + the icon; a receiver shouldn't see "video/iso.segment"). */
    private static String typeLabelKey(String mime) {
        if (mime == null) {
            return "T_FILE";
        }
        if (mime.startsWith("video/")) {
            return "T_VIDEO";
        }
        if (mime.startsWith("audio/")) {
            return "T_AUDIO";
        }
        if (mime.startsWith("image/")) {
            return "T_IMAGE";
        }
        if (mime.equals("application/pdf")) {
            return "T_PDF";
        }
        if (mime.startsWith("text/")) {
            return "T_TEXT";
        }
        return "T_FILE";
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

    private void sendFile(OutputStream out, SharedFile shared,
                          Map<String, String> t, String lang) throws IOException {
        File file = shared.file;
        // Open through RestoredFileAccess: the owned file directly, or a
        // foreign-owned RESTORED file via the persisted SAF grant (a bare
        // FileInputStream EACCES-es on the latter).
        ParcelFileDescriptor pfd = RestoredFileAccess.openReadOnly(shared.context, file.getPath());
        if (pfd == null) {
            sendHtml(out, 404, localize(page("notfound.html"), t, lang));
            return;
        }
        try {
            long statSize = pfd.getStatSize();
            long length = statSize > 0 ? statSize : file.length();
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
            try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                int n;
                while ((n = fis.read(buffer)) > 0) {
                    if (!mRunning.get()) {
                        return; // stop() mid-transfer — socket is going away anyway
                    }
                    out.write(buffer, 0, n);
                }
            }
            out.flush();
        } finally {
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
        }
    }
}

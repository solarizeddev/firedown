package com.solarized.firedown.p2pshare;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.BuildConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One-shot LAN listener for the receiver's ANSWER code — what turns the share
 * into a SINGLE-scan flow. WebRTC needs an answer back (it carries the
 * receiver's candidates/fingerprint, which don't exist until Accept), and with
 * offline signaling that used to mean a second, human-relayed QR. But once the
 * receiver has scanned the offer it knows the sender's LAN address — so the
 * offer embeds {@code ans: http://<lan-ip>:<port>/answer?t=<token>} and the
 * receiver POSTs its answer straight back over the network. The reply QR
 * remains as the fallback when the POST can't reach (different networks, AP
 * isolation, VPN).
 *
 * <p><b>Scope/security</b> — this is the ONLY thing Firedown ever listens for
 * on a LAN interface, and it is deliberately tiny: it accepts a single
 * {@code POST /answer?t=<token>} (token = 16 random bytes, carried only inside
 * the offer QR/code the user physically hands over), body-capped, first
 * delivery wins, and its lifetime is the sender's offer stage — started with
 * the offer, stopped the moment an answer is applied or the share screen
 * closes. It never serves file bytes (those ride the WebRTC DataChannel;
 * the loopback server stays 127.0.0.1-only).
 *
 * <p>Interface pick: prefers a wlan* site-local IPv4 so an active VPN's tun
 * address (unreachable by the peer) isn't advertised.
 */
public class P2pAnswerServer {

    private static final String TAG = "P2pAnswerServer";

    /** An answer code is ~1-2 KB; anything bigger is a protocol violation. */
    private static final int MAX_BODY = 16 * 1024;

    /** First valid answer wins; later posts get 409. */
    public interface Callback {
        void onAnswer(@NonNull String code);
    }

    private final Callback mCallback;
    private final String mToken;

    private volatile ServerSocket mServerSocket;
    private volatile boolean mRunning;
    private volatile boolean mDelivered;
    private String mAnswerUrl;

    public P2pAnswerServer(@NonNull Callback callback) {
        mCallback = callback;
        byte[] tokenBytes = new byte[16];
        new SecureRandom().nextBytes(tokenBytes);
        StringBuilder token = new StringBuilder(tokenBytes.length * 2);
        for (byte b : tokenBytes) {
            token.append(String.format(Locale.US, "%02x", b));
        }
        mToken = token.toString();
    }

    /**
     * Bind to the device's LAN address. Returns false (and stays inert) when
     * there is no usable site-local IPv4 — the caller then simply omits the
     * answer-return URL and the flow falls back to the reply QR.
     */
    public boolean start() {
        InetAddress lanAddress = findLanAddress();
        if (lanAddress == null) {
            return false;
        }
        try {
            mServerSocket = new ServerSocket(0, 2, lanAddress);
        } catch (IOException e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "bind failed: " + e.getMessage());
            }
            return false;
        }
        mAnswerUrl = "http://" + lanAddress.getHostAddress() + ":"
                + mServerSocket.getLocalPort() + "/answer?t=" + mToken;
        mRunning = true;
        Thread acceptThread = new Thread(this::acceptLoop, "P2pAnswer");
        acceptThread.start();
        return true;
    }

    @Nullable
    public String getAnswerUrl() {
        return mAnswerUrl;
    }

    public void stop() {
        mRunning = false;
        ServerSocket socket = mServerSocket;
        mServerSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // Best-effort; the accept loop exits on the error.
            }
        }
    }

    private void acceptLoop() {
        while (mRunning) {
            Socket client;
            try {
                ServerSocket socket = mServerSocket;
                if (socket == null) {
                    return;
                }
                client = socket.accept();
            } catch (Exception e) {
                // Closed by stop(), or a teardown race — never crash the thread.
                return;
            }
            // Handled inline on this thread: bodies are ~1-2 KB and there is
            // exactly one legitimate client, so no handler pool is warranted.
            try {
                handleConnection(client);
            } catch (Exception e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "answer connection: " + e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket client) throws IOException {
        try {
            client.setSoTimeout(10000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            P2pLoopbackServer.RequestHead head = P2pLoopbackServer.readHead(in);
            if (head == null) {
                P2pLoopbackServer.sendStatus(out, 400, "Bad Request");
                return;
            }
            if (!mToken.equals(head.query.get("t"))) {
                P2pLoopbackServer.sendStatus(out, 403, "Forbidden");
                return;
            }
            if (!"POST".equals(head.method) || !"/answer".equals(head.path)) {
                P2pLoopbackServer.sendStatus(out, 404, "Not Found");
                return;
            }
            if (mDelivered) {
                P2pLoopbackServer.sendStatus(out, 409, "Conflict");
                return;
            }
            long declared;
            try {
                declared = Long.parseLong(head.headers.getOrDefault("content-length", "-1"));
            } catch (NumberFormatException e) {
                declared = -1;
            }
            if (declared <= 0 || declared > MAX_BODY) {
                P2pLoopbackServer.sendStatus(out, 400, "Bad Request");
                return;
            }
            byte[] body = new byte[(int) declared];
            int have = 0;
            while (have < body.length) {
                int read = in.read(body, have, body.length - have);
                if (read < 0) {
                    throw new IOException("body truncated");
                }
                have += read;
            }
            mDelivered = true;
            P2pLoopbackServer.sendStatus(out, 204, "No Content");
            out.flush();
            mCallback.onAnswer(new String(body, StandardCharsets.UTF_8).trim());
        } finally {
            try {
                client.close();
            } catch (IOException e) {
                // Best-effort.
            }
        }
    }

    /**
     * The device's LAN IPv4. wlan* interfaces are preferred so an active VPN's
     * tun address (site-local too, but unreachable by the peer) isn't picked
     * when Wi-Fi is also up; otherwise the first up, non-loopback site-local
     * IPv4 wins. Null when the device has no LAN address at all.
     */
    @Nullable
    private static InetAddress findLanAddress() {
        try {
            List<NetworkInterface> interfaces =
                    Collections.list(NetworkInterface.getNetworkInterfaces());
            InetAddress fallback = null;
            for (NetworkInterface networkInterface : interfaces) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    boolean siteLocalV4 = address instanceof Inet4Address
                            && address.isSiteLocalAddress();
                    if (!siteLocalV4) {
                        continue;
                    }
                    String name = networkInterface.getName();
                    if (name != null && name.startsWith("wlan")) {
                        return address;
                    }
                    if (fallback == null) {
                        fallback = address;
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }
}

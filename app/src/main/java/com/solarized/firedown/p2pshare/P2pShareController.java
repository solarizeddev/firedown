package com.solarized.firedown.p2pshare;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.sync.crypto.Pow;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.RestoredFileAccess;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.manager.UrlParser;
import com.solarized.firedown.manager.UrlType;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.GalleryPublisher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebExtension;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Java side of the P2P share engine — the bridge between the share screens
 * (P2pSendFragment / P2pReceiveFragment) and the WebRTC engine.
 *
 * <p><b>Why a hidden GeckoSession, not a background page.</b>
 * {@code RTCPeerConnection.createOffer()} HANGS FOREVER in a WebExtension
 * background page (it has no docShell / browsing context) but works exactly
 * like a normal tab in a real content document — proven on-device. So the
 * engine ({@code assets/p2pshare/engine-page.js}) runs as a page-world script
 * in a hidden {@link GeckoSession} that loads {@code http://127.0.0.1:<port>/engine},
 * served by this session's own {@link P2pLoopbackServer}. A thin bridge content
 * script ({@code content.js}) opens the native port and relays page↔Java. This
 * mirrors {@link PoTokenGenerator}'s hidden-session pattern.
 *
 * <p>Ownership split (mirrors PoTokenGenerator): this controller owns the
 * hidden engine {@link GeckoSession}, the native {@link WebExtension.Port}
 * (handed over by GeckoRuntimeHelper's onConnect once content.js connects),
 * the loopback byte server, and the post-transfer bookkeeping (verify + rename
 * the received file, insert the FINISHED download row). The page owns
 * RTCPeerConnection/DataChannel. The fragments own all UI and the
 * session-scoped WebRTC pref flip.
 *
 * <p>One session at a time by design — the share screens are modal
 * full-screen destinations, and the engine mirrors that with a single
 * session slot. Starting a new session tears down the previous one.
 *
 * <p><b>Readiness ordering.</b> RTCPeerConnection is pref-gated WebIDL
 * evaluated at page-global creation, so the fragment enables the WebRTC pref
 * and waits for it to APPLY before calling start. This controller then opens a
 * FRESH engine session, whose page therefore loads with the pref already on —
 * no reload dance needed (a fresh page-global sees the enabled pref). The
 * engine posts {@code {type:"ready", rtc:true}} once the page script is live;
 * the queued action runs on that event.
 */
@Singleton
public class P2pShareController {

    private static final String TAG = "P2pShareController";

    /**
     * Native port name — must match connectNative() in
     * assets/p2pshare/content.js. Port names reject hyphens.
     */
    public static final String PORT_NAME = "p2pshare";

    /**
     * Code prefixes — must match OFFER_PREFIX/ANSWER_PREFIX in
     * assets/p2pshare/engine-page.js. The UI uses them to validate a
     * scan/paste before handing it to the engine.
     */
    public static final String OFFER_PREFIX = "FDS1.";
    public static final String ANSWER_PREFIX = "FDR1.";
    /**
     * Short OFFER REFERENCE prefix — {@code FDO1.<rendezvous-id>}. Not a
     * self-contained offer (that's {@code FDS1.}) but a pointer: the sender
     * brokered the full {@code FDS1.} code at the rendezvous offer mailbox
     * ({@code /v1/p2p/o/<id>}) so the shared LINK can be short. The receiver
     * fetches the real offer by id, then runs the normal receive. Java-only —
     * the engine never sees this; the controller resolves it to an {@code FDS1.}
     * before {@code recv-start}. The QR keeps the full {@code FDS1.} form (the
     * in-person path needs no server).
     */
    public static final String OFFER_REF_PREFIX = "FDO1.";

    /**
     * Deep-link wrapper so a scan with ANY scanner (the phone's system camera
     * included) offers "open in Firedown" and jumps straight into the receive
     * flow — a bare {@code FDS1.…} code is just text to a generic scanner. The
     * scheme+host are registered on DownloadsActivity (AndroidManifest) and
     * routed by {@code handleP2pDeepLink}: an OFFER to {@code p2p_receive}, an
     * ANSWER into the sender's LIVE session ({@link #provideExternalAnswer}).
     * {@link #stripDeepLink} makes the in-app scanner + paste accept the
     * wrapped, https and bare forms alike.
     */
    public static final String DEEP_LINK_SCHEME = "firedown";
    public static final String DEEP_LINK_HOST = "p2p";
    public static final String DEEP_LINK_PREFIX =
            DEEP_LINK_SCHEME + "://" + DEEP_LINK_HOST + "/";

    /**
     * The MESSENGER-facing wrapper: {@code https://firedown.app/s#<code>}.
     * Chat apps auto-link https where a custom scheme renders as dead text, so
     * this is what the share sheet sends (both the offer and the reply). The
     * code rides in the {@code #fragment}, which a browser NEVER sends to the
     * server — the page behind /s is a static "open in Firedown" bouncer, not
     * a signaling service. With the verified App Link (assetlinks.json on
     * firedown.app) the link opens the app directly and no page is ever
     * loaded; without verification (some de-Googled ROMs, desktop) the bouncer
     * fires the firedown:// scheme instead. QRs keep the firedown:// form —
     * they're the in-person path, where "open in Firedown" is always right.
     */
    public static final String HTTPS_LINK_HOST = "firedown.app";
    public static final String HTTPS_LINK_PATH = "/s";
    public static final String HTTPS_LINK_PREFIX =
            "https://" + HTTPS_LINK_HOST + HTTPS_LINK_PATH + "#";

    /** Wrap a signaling code as a {@code firedown://p2p/<code>} deep link (QR form). */
    @NonNull
    public static String toDeepLink(@NonNull String code) {
        return DEEP_LINK_PREFIX + code;
    }

    /** Wrap a signaling code as an {@code https://firedown.app/s#<code>} link
     *  (the share-sheet form — see {@link #HTTPS_LINK_PREFIX}). */
    @NonNull
    public static String toHttpsLink(@NonNull String code) {
        return HTTPS_LINK_PREFIX + code;
    }

    /**
     * Return the bare {@code FDS1.}/{@code FDR1.} code from a scanned/pasted
     * value, stripping the {@code firedown://p2p/} or
     * {@code https://firedown.app/s#} wrapper if present. Bare codes pass
     * through unchanged.
     */
    @NonNull
    public static String stripDeepLink(@NonNull String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith(DEEP_LINK_PREFIX)) {
            return trimmed.substring(DEEP_LINK_PREFIX.length());
        }
        // Accept the https form on either host spelling (a messenger may
        // rewrite to www.) — everything after the first '#' is the code.
        if (trimmed.startsWith("https://" + HTTPS_LINK_HOST + HTTPS_LINK_PATH)
                || trimmed.startsWith("https://www." + HTTPS_LINK_HOST + HTTPS_LINK_PATH)) {
            int hash = trimmed.indexOf('#');
            if (hash >= 0 && hash + 1 < trimmed.length()) {
                return trimmed.substring(hash + 1);
            }
        }
        return trimmed;
    }

    private static final long ENGINE_READY_TIMEOUT_MS = 8000;

    /**
     * Head-start the LAN answer return gets before the rendezvous mailbox is
     * ALSO tried (happy-eyeballs). A real same-LAN sender's listener answers in
     * a few ms, so the mailbox never fires and the answer stays on the LAN; a
     * sender behind a full-tunnel VPN/proxy is unreachable on its advertised
     * {@code ans} endpoint, so after this it falls to the mailbox in parallel
     * instead of waiting out the LAN path's full connect timeout.
     */
    private static final long ANSWER_RENDEZVOUS_HEADSTART_MS = 700;

    /**
     * Cap on how long a "Short link" tap waits for the lazy offer-broker upload
     * before falling back to the full self-contained link, so a slow/dead
     * network can't hang the share sheet. The upload keeps running and caches
     * the short link for the next share if it lands after this.
     */
    private static final long SHORT_LINK_BROKER_TIMEOUT_MS = 2500;

    /**
     * The fixed hashcash "resource" the relay PoW is bound to — must match
     * firedown-api's {@code relayPoWResource} ({@code []byte("relay")})
     * byte-for-byte. {@link Pow} prepends its own register-domain prefix; the
     * separate server-side store makes cross-domain replay impossible anyway.
     */
    private static final byte[] RELAY_POW_RESOURCE =
            "relay".getBytes(StandardCharsets.US_ASCII);
    /**
     * Refuse to solve a PoW harder than this — the base is ~14 bits and the
     * adaptive ceiling is base+8; a value beyond this is a spoofed/hostile
     * response, so treat it as "no relay" rather than spin the CPU.
     */
    private static final int RELAY_POW_MAX_BITS = 26;

    private static final SecureRandom ID_RANDOM = new SecureRandom();

    /**
     * UI-facing events. All callbacks arrive on the main thread.
     */
    public interface Listener {
        /** A signaling code is ready to show (QR / share sheet). */
        void onCode(@NonNull String role, @NonNull String code);

        /** Receiver only: the scanned offer parsed — show the preview card. */
        void onOfferParsed(@NonNull String name, long size, @NonNull String mime, @NonNull String device);

        /** connecting | connected | closed */
        void onConnectionState(@NonNull String state);

        /**
         * Reported once the live path is known (on every "connected"): true when
         * it RELAYS through the TURN server (the file bytes pass through, still
         * end-to-end encrypted), false when it's DIRECT peer-to-peer. Lets the UI
         * say honestly whether the file touches a server.
         */
        void onTransport(boolean relayed);

        void onProgress(long done, long total, long rate);

        /** Transfer finished. For receive, the file row is already inserted. */
        void onDone(@NonNull String role, long bytes);

        /**
         * Errors: "bad-code" is soft (the current step stays valid, user
         * retries the scan/paste); everything else ends the session.
         */
        void onError(@NonNull String code, @NonNull String detail);
    }

    private final Context mContext;
    private final SharedPreferences mSharedPreferences;
    private final DownloadDataRepository mDownloadDataRepository;
    private final Executor mDiskExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // Runtime + session registrar, wired by GeckoRuntimeHelper after runtime
    // creation (can't be @Inject'd — GeckoRuntimeHelper depends on this
    // controller, so the reverse would be a cycle; same setter pattern the
    // PoTokenGenerator uses via its constructor).
    private GeckoRuntime mRuntime;
    private Consumer<GeckoSession> mSessionRegistrar;

    // The hidden GeckoSession hosting the page-world WebRTC engine. Its
    // lifetime is one share session — recreated fresh each start so the page
    // loads with the just-enabled WebRTC pref (see the readiness note above).
    private GeckoSession mEngineSession;
    private WebExtension.Port mPort;
    private boolean mEngineReady;
    private Runnable mPendingEngineAction;
    private final Runnable mEngineTimeout = this::onEngineTimeout;

    // Active session state. Main-thread only (all port + fragment traffic is
    // UI-thread), except the finalize step which hops to the disk executor.
    private Listener mListener;
    private String mRole; // "send" | "receive" | null
    private P2pLoopbackServer mServer;
    // Single-scan answer return. Sender: the LAN listener the offer's `ans`
    // URL points at (null = no LAN address, reply-QR only). Receiver: the
    // sender's `ans` URL from the parsed offer (empty = reply-QR only).
    private P2pAnswerServer mAnswerServer;
    private String mRecvAnswerUrl;
    // Optional signaling relay (cross-network one-link, see P2pSignalingClient).
    // Sender: base + minted id, and the https share link once the offer is up.
    // Receiver: base + id it opened the relay link with. Null = serverless.
    private P2pSignalingClient mSignaling;
    private String mSignalingBase;
    private String mSignalingId;
    private String mShareLink;
    private String mOfferCode;
    private String mRecvSignalingBase;
    private String mRecvSignalingId;
    // Answer rendezvous (always on — the first-party api mailbox that removes
    // the reply step, see Preferences.P2P_RENDEZVOUS_URL). Sender: a poll
    // client + minted id. Receiver: the sender's rendezvous answer URL from
    // the parsed offer's `rvz`.
    private P2pSignalingClient mRendezvous;
    private String mRendezvousId;
    private String mRecvRendezvousUrl;
    private String mRecvName;
    private String mRecvMime;
    private File mRecvPartFile;
    private boolean mRecvFinalized;
    // Set once bytes are actually moving (first progress event), cleared on
    // done/teardown — drives the "abandon transfer?" back-press confirm.
    private boolean mTransferring;
    // The finished download row, so the receiver's "Open" can act on it.
    private DownloadEntity mReceivedEntity;

    // Short-deadline client for the receiver's direct answer POST: a LAN peer
    // answers in milliseconds or not at all, and the relay/reply fallback should
    // kick in fast when it doesn't. Derived from the shared client (pool/DNS
    // reused), tightened timeouts only.
    private final OkHttpClient mAnswerClient;
    // The shared client, kept so a P2pSignalingClient can be spun up per share.
    private final OkHttpClient mOkHttpClient;
    // Short-deadline client for the relay-creds GET: the fetch gates the start
    // command, so its worst case (server unreachable) must stay a small, fixed
    // delay before the share proceeds relay-less.
    private final OkHttpClient mRelayClient;
    // Cached /v1/relay/creds response + its validity end (wall clock). The
    // creds are session-agnostic bearer material with an hour-scale TTL, so the
    // cache survives stopSession on purpose — back-to-back shares reuse it.
    private JSONObject mRelayCreds;
    private long mRelayCredsExpiryMs;

    @Inject
    public P2pShareController(
            @ApplicationContext Context context,
            SharedPreferences sharedPreferences,
            DownloadDataRepository downloadDataRepository,
            @Qualifiers.DiskIO Executor diskExecutor,
            OkHttpClient okHttpClient
    ) {
        mContext = context;
        mSharedPreferences = sharedPreferences;
        mDownloadDataRepository = downloadDataRepository;
        mDiskExecutor = diskExecutor;
        mOkHttpClient = okHttpClient;
        mAnswerClient = okHttpClient.newBuilder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .build();
        mRelayClient = okHttpClient.newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(4, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Wire the GeckoRuntime + session registrar. Called once by
     * GeckoRuntimeHelper right after the runtime is created (a setter, not
     * constructor injection, to avoid the Hilt dependency cycle — see the
     * field comment). {@code registrar} is {@code GeckoRuntimeHelper::registerSession},
     * which attaches the loaded WebExtensions' delegates to a session so the
     * p2pshare content script + native port bind to it.
     */
    @UiThread
    public void attachRuntime(@NonNull GeckoRuntime runtime,
                              @NonNull Consumer<GeckoSession> registrar) {
        mRuntime = runtime;
        mSessionRegistrar = registrar;
    }

    /* ── port lifecycle (called from GeckoRuntimeHelper) ────────────────── */

    @UiThread
    public void onPortConnected(@NonNull WebExtension.Port port) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "engine port connected");
        }
        mPort = port;
        port.setDelegate(new WebExtension.PortDelegate() {
            @Override
            public void onPortMessage(@NonNull Object message, @NonNull WebExtension.Port p) {
                if (message instanceof JSONObject json) {
                    handleEngineEvent(json);
                }
            }

            @Override
            public void onDisconnect(@NonNull WebExtension.Port p) {
                if (mPort == p) {
                    mPort = null;
                    mEngineReady = false;
                    // The page went away. If WE closed the session (stopSession
                    // ran first, nulling mListener/mRole) this is the expected
                    // teardown and the guard below no-ops; otherwise the engine
                    // page crashed mid-share and the transfer can't survive it.
                    if (mRole != null && mListener != null) {
                        postError("engine", "engine disconnected");
                    }
                }
            }
        });
    }

    /* ── session API (fragments, main thread) ───────────────────────────── */

    /**
     * Sender: serve {@code entity}'s file on the loopback and ask the engine
     * for an offer code. Events flow to {@code listener} until stop().
     */
    @UiThread
    public void startSend(@NonNull DownloadEntity entity, @NonNull Listener listener) {
        stopSession(false);
        mListener = listener;
        mRole = "send";

        String path = entity.getFilePath();
        if (path == null) {
            postError("file", "file unreadable");
            return;
        }
        long size = RestoredFileAccess.length(mContext, path);
        if (size <= 0) {
            postError("file", "file unreadable");
            return;
        }
        if (!startLoopback()) {
            postError("engine", "loopback start failed");
            return;
        }
        mServer.setReadFile(path);

        // Single-scan flow: bind the LAN answer listener so the offer carries
        // an answer-return URL and the receiver's Accept connects directly —
        // no second QR. Binding can fail (no LAN address); the flow then
        // simply falls back to the relay or the reply QR.
        mAnswerServer = new P2pAnswerServer(code ->
                mMainHandler.post(() -> onDirectAnswer(code)));
        if (!mAnswerServer.start()) {
            mAnswerServer = null;
        }

        // Always-on answer rendezvous: mint an id and embed its mailbox URL so
        // the receiver's Accept POSTs the answer to api.firedown.app and the
        // sender's long-poll (started in onOfferReady) picks it up — a
        // cross-network share with NO reply step. Self-contained offer, so if
        // the endpoint is down the reply link/QR still stands.
        mRendezvous = new P2pSignalingClient(mOkHttpClient);
        mRendezvousId = P2pSignalingClient.mintId();
        final String rendezvousUrl =
                Preferences.P2P_RENDEZVOUS_URL + "/a/" + mRendezvousId;

        // Optional signaling relay (DORMANT — Preferences.P2P_SIGNALING_DEFAULT
        // is empty; a fuller one-link flow that also uploads the offer). The
        // rendezvous above covers the reply-step removal without it.
        mSignalingBase = Preferences.getP2pSignalingUrl(mSharedPreferences);
        if (!mSignalingBase.isEmpty()) {
            mSignaling = new P2pSignalingClient(mOkHttpClient);
            mSignalingId = P2pSignalingClient.mintId();
        }

        final JSONObject command = new JSONObject();
        try {
            command.put("type", "send-start");
            command.put("readUrl", mServer.getReadUrl());
            command.put("answerUrl", mAnswerServer != null ? mAnswerServer.getAnswerUrl() : "");
            command.put("rendezvousUrl", rendezvousUrl);
            command.put("name", entity.getFileName());
            command.put("size", size);
            // Derive the mime from the file's OWN extension, not the entity's
            // stored label — that label is Firedown's internal/UI value (an
            // HLS capture muxed to .mp4 can carry a manifest mime) and would
            // mis-bucket the received download's type/icon/viewer.
            command.put("mime", FileUriHelper.getMimeTypeFromFile(new File(path).getName()));
            command.put("device", Build.MODEL);
        } catch (JSONException e) {
            postError("engine", "command build failed");
            return;
        }
        // The relay-creds fetch gates the command (iceServers are fixed at
        // RTCPeerConnection construction); its short timeouts bound the wait
        // and a failure just means a relay-less share — see withRelayCreds.
        withRelayCreds(creds -> {
            if (!"send".equals(mRole) || mServer == null) {
                return; // torn down while fetching
            }
            try {
                putIceServers(command, creds);
            } catch (JSONException e) {
                postError("engine", "command build failed");
                return;
            }
            ensureEngineSession(() -> postCommand(command));
        });
    }

    /**
     * Build the {@code iceServers} list for a start command (RTC shape: each
     * entry {@code {urls, username?, credential?}}). Three sources, per the
     * CLAUDE.md P2P section: the user's STUN echo, the user's OPTIONAL custom
     * TURN (off by default), and the first-party Firedown relay via the
     * FETCHED ephemeral {@code relayCreds} (null = fetch failed/undeployed →
     * the share proceeds direct+STUN-only, the pre-relay behavior). Never a
     * hardcoded fallback list, never a baked credential.
     */
    private void putIceServers(JSONObject command, @Nullable JSONObject relayCreds)
            throws JSONException {
        JSONArray servers = new JSONArray();

        String stun = Preferences.getP2pStunServer(mSharedPreferences);
        if (stun != null && !stun.isEmpty()) {
            servers.put(new JSONObject().put("urls", stun));
        }

        // Optional user-configured TURN (Settings → Direct share), an
        // ADDITIONAL relay for networks the default can't cross.
        Preferences.P2pTurn turn = Preferences.getP2pTurn(mSharedPreferences);
        if (turn != null) {
            JSONObject entry = new JSONObject().put("urls", turn.url);
            if (!turn.username.isEmpty()) {
                entry.put("username", turn.username);
            }
            if (!turn.credential.isEmpty()) {
                entry.put("credential", turn.credential);
            }
            servers.put(entry);
        }

        // The Firedown relay — free, short-lived creds fetched per session
        // (withRelayCreds). ICE only actually relays through it when no
        // direct path exists; the relayed bytes stay DTLS-encrypted.
        if (relayCreds != null) {
            servers.put(new JSONObject()
                    .put("urls", relayCreds.optJSONArray("urls"))
                    .put("username", relayCreds.optString("username"))
                    .put("credential", relayCreds.optString("credential")));
        }

        command.put("iceServers", servers);
    }

    /**
     * Hand {@code action} the Firedown relay's ephemeral TURN creds — cached
     * if still fresh, else fetched from {@link Preferences#P2P_RELAY_CREDS_URL}
     * (an anonymous GET, no identifiers) — or {@code null} when unavailable
     * (endpoint undeployed → 404, offline, timeout), which callers treat as
     * "share without a relay". {@code action} always runs on the MAIN thread;
     * callers re-check session state inside it (the fetch spans a teardown
     * window). Short timeouts on {@link #mRelayClient} bound the added
     * latency; the cache (TTL minus a safety margin) makes back-to-back
     * shares instant.
     */
    private void withRelayCreds(@NonNull Consumer<JSONObject> action) {
        if (mRelayCreds != null && System.currentTimeMillis() < mRelayCredsExpiryMs) {
            action.accept(mRelayCreds);
            return;
        }
        // Step 1: fetch a PoW challenge. 404 = relay/PoW not deployed → skip
        // straight to a relay-less share. Any other failure degrades the same.
        Request challengeReq = new Request.Builder()
                .url(Preferences.P2P_RELAY_CHALLENGE_URL)
                .get()
                .build();
        mRelayClient.newCall(challengeReq).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "relay challenge fetch failed: " + e.getMessage());
                }
                deliverRelayCreds(null, action);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                String challengeB64 = null;
                int bits = 0;
                try (Response r = response) {
                    if (r.isSuccessful() && r.body() != null) {
                        JSONObject json = new JSONObject(r.body().string());
                        challengeB64 = json.optString("challenge", "");
                        bits = json.optInt("pow_bits", 0);
                    }
                } catch (IOException | JSONException e) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "relay challenge parse failed: " + e.getMessage());
                    }
                }
                // A hostile/spoofed difficulty must not spin the solver forever
                // (HTTPS to api.firedown.app makes this unlikely, but cheap to
                // bound). Above the cap, treat as "no relay".
                if (challengeB64 == null || challengeB64.isEmpty()
                        || bits <= 0 || bits > RELAY_POW_MAX_BITS) {
                    deliverRelayCreds(null, action);
                    return;
                }
                fetchRelayCredsWithPoW(challengeB64, bits, action);
            }
        });
    }

    /**
     * Step 2 (runs on the OkHttp callback thread — off main, where the CPU
     * solve belongs): solve the hashcash and GET the creds with
     * challenge+nonce. The solve is a few ms at the base difficulty; the
     * adaptive climb only bites a mass-minter, never a single share.
     */
    private void fetchRelayCredsWithPoW(@NonNull String challengeB64, int bits,
                                        @NonNull Consumer<JSONObject> action) {
        String nonceB64;
        try {
            byte[] challenge = Base64.decode(
                    challengeB64, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            byte[] nonce = Pow.solve(RELAY_POW_RESOURCE, challenge, bits);
            nonceB64 = Base64.encodeToString(
                    nonce, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            deliverRelayCreds(null, action); // malformed challenge
            return;
        }
        HttpUrl url = HttpUrl.get(Preferences.P2P_RELAY_CREDS_URL).newBuilder()
                .addQueryParameter("challenge", challengeB64)
                .addQueryParameter("nonce", nonceB64)
                .build();
        mRelayClient.newCall(new Request.Builder().url(url).get().build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "relay creds fetch failed: " + e.getMessage());
                }
                deliverRelayCreds(null, action);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                JSONObject creds = null;
                try (Response r = response) {
                    if (r.isSuccessful() && r.body() != null) {
                        JSONObject json = new JSONObject(r.body().string());
                        JSONArray urls = json.optJSONArray("urls");
                        if (urls != null && urls.length() > 0
                                && !json.optString("username").isEmpty()
                                && !json.optString("credential").isEmpty()) {
                            creds = json;
                        }
                    }
                } catch (IOException | JSONException e) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "relay creds parse failed: " + e.getMessage());
                    }
                }
                deliverRelayCreds(creds, action);
            }
        });
    }

    /** Cache (on success) and deliver relay creds on the main thread. */
    private void deliverRelayCreds(@Nullable JSONObject creds,
                                   @NonNull Consumer<JSONObject> action) {
        mMainHandler.post(() -> {
            if (creds != null) {
                mRelayCreds = creds;
                // Refresh a minute before coturn stops honoring the credential
                // (an expired cred also breaks allocation REFRESHES mid-share).
                long ttlSeconds = creds.optLong("ttl_seconds", 600);
                mRelayCredsExpiryMs = System.currentTimeMillis()
                        + Math.max(0, ttlSeconds - 60) * 1000;
            }
            action.accept(creds);
        });
    }

    /**
     * Sender: the scanned/pasted FDR1. reply code.
     */
    @UiThread
    public void provideAnswer(@NonNull String code) {
        JSONObject command = new JSONObject();
        try {
            command.put("type", "send-answer");
            command.put("code", code);
        } catch (JSONException e) {
            return;
        }
        postCommand(command);
        // The answer is in (whichever way it arrived) — the LAN listener and
        // relay poll have done their job; don't keep sockets open through the
        // transfer. (The engine ignores a second answer, so a race is benign.)
        if (mAnswerServer != null) {
            mAnswerServer.stop();
            mAnswerServer = null;
        }
        if (mRendezvous != null) {
            mRendezvous.cancel();
        }
        if (mSignaling != null) {
            mSignaling.cancel();
        }
    }

    /**
     * Sender: the engine minted the offer. Show it (QR) and, if a relay is
     * configured, upload it and long-poll for the answer so a shared LINK
     * completes across networks. The LAN answer listener runs in parallel; the
     * first answer to arrive wins.
     */
    private void onOfferReady(@NonNull String code) {
        mOfferCode = code;
        // Start the rendezvous long-poll: the receiver's answer will land in
        // the api mailbox and this picks it up. First answer to arrive (LAN
        // return, rendezvous, or a human-relayed reply) wins; the engine
        // ignores a later duplicate.
        if (mRendezvous != null && mRendezvousId != null) {
            final String id = mRendezvousId;
            mRendezvous.pollAnswer(Preferences.P2P_RENDEZVOUS_URL, id, ans ->
                    mMainHandler.post(() -> onRendezvousAnswer(ans)));
            // NB: the offer is NOT brokered here — that's LAZY, done only when the
            // user picks the SHORT link (resolveShortLink). Brokering eagerly would
            // upload the offer (which carries the file's name/size) to the server
            // for every share, making the "Private link" choice meaningless. So a
            // private share uploads nothing. (The answer still returns via `rvz`
            // for both — that's the receiver's candidates, not the shared file.)
        }
        if (mSignaling != null && mSignalingId != null && !mSignalingBase.isEmpty()) {
            final String base = mSignalingBase;
            final String id = mSignalingId;
            mSignaling.uploadOffer(base, id, code, ok -> mMainHandler.post(() -> {
                if (!"send".equals(mRole)) {
                    return;
                }
                if (ok != null) {
                    mShareLink = base + "/s/" + id;
                    mSignaling.pollAnswer(base, id, ans ->
                            mMainHandler.post(() -> onRelayAnswer(ans)));
                }
            }));
        }
        if (mListener != null) {
            mListener.onCode("offer", code);
        }
    }

    /**
     * Resolve the SHORT share link, brokering the offer LAZILY on first use: the
     * offer is uploaded to the rendezvous offer mailbox now (not eagerly at
     * mint), so the "Private link" path can genuinely upload nothing. Hands
     * {@code cb} (main thread) the short {@code FDO1.<id>} https link once the
     * upload lands, or the FULL self-contained link if brokering is unavailable
     * or fails — so "Short link" is never a dead action, it just isn't shorter
     * offline. Cached (`mShareLink`) so a re-share is instant.
     */
    @UiThread
    public void resolveShortLink(@NonNull Consumer<String> cb) {
        if (!"send".equals(mRole) || mOfferCode == null) {
            cb.accept(null);
            return;
        }
        if (mShareLink != null) {
            cb.accept(mShareLink);
            return;
        }
        final String full = mOfferCode;
        if (mRendezvous == null || mRendezvousId == null) {
            cb.accept(toHttpsLink(full)); // no mailbox → the full link still works
            return;
        }
        final String id = mRendezvousId;
        // Bound the wait: on a slow/dead network the upload must NOT hang the
        // share sheet. Race it against a short timer — whichever fires first
        // delivers cb exactly once; the upload keeps running and, if it lands
        // later, caches mShareLink so the NEXT share is instantly short.
        final boolean[] done = {false};
        final Runnable timeout = () -> {
            if (!done[0]) {
                done[0] = true;
                cb.accept(toHttpsLink(full));
            }
        };
        mMainHandler.postDelayed(timeout, SHORT_LINK_BROKER_TIMEOUT_MS);
        mRendezvous.uploadOffer(Preferences.P2P_RENDEZVOUS_URL, id, full, ok ->
                mMainHandler.post(() -> {
                    if (ok != null && "send".equals(mRole)) {
                        mShareLink = toHttpsLink(OFFER_REF_PREFIX + id);
                    }
                    if (done[0]) {
                        return; // the timer already answered with the full link
                    }
                    done[0] = true;
                    mMainHandler.removeCallbacks(timeout);
                    cb.accept(mShareLink != null ? mShareLink : toHttpsLink(full));
                }));
    }

    /**
     * Sender: an ANSWER arrived from OUTSIDE the share screen — the receiver's
     * reply link tapped in a messenger (deep link via DownloadsActivity), on
     * this same phone. Applied like a scanned reply when a send session is
     * live; returns false when there is none (app relaunched, share closed) so
     * the caller can say so honestly instead of dropping the tap.
     */
    @UiThread
    public boolean provideExternalAnswer(@NonNull String raw) {
        if (!"send".equals(mRole)) {
            return false;
        }
        applyIncomingAnswer(raw, "deep link");
        return true;
    }

    /**
     * Sender: an answer arrived over the LAN listener (same-network) or the
     * relay poll (cross-network) — validate and apply like a scanned reply.
     * First one wins; the engine ignores a later duplicate. Runs on main.
     */
    private void onDirectAnswer(@NonNull String raw) {
        applyIncomingAnswer(raw, "LAN return");
    }

    private void onRendezvousAnswer(@Nullable String raw) {
        if (raw == null) {
            return; // poll gave up / cancelled — LAN or reply still stand
        }
        applyIncomingAnswer(raw, "rendezvous");
    }

    private void onRelayAnswer(@Nullable String raw) {
        if (raw == null) {
            return; // poll gave up / cancelled — LAN or reply QR still stand
        }
        applyIncomingAnswer(raw, "relay");
    }

    private void applyIncomingAnswer(@Nullable String raw, String via) {
        if (!"send".equals(mRole) || raw == null) {
            return;
        }
        String code = stripDeepLink(raw);
        if (!code.startsWith(ANSWER_PREFIX)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "answer rejected (bad prefix) via " + via);
            }
            return;
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "answer received via " + via);
        }
        provideAnswer(code);
    }

    /**
     * Receiver: parse a scanned/pasted FDS1. offer code. onOfferParsed fires
     * with the preview metadata; nothing connects until acceptOffer().
     */
    @UiThread
    public void startReceive(@NonNull String code, @NonNull Listener listener) {
        stopSession(false);
        mListener = listener;
        mRole = "receive";
        beginReceive(code);
    }

    /**
     * Receiver: open a relay link — fetch the offer from the signaling relay by
     * {@code id}, then proceed as a normal receive. On accept, the answer is
     * posted back to the SAME relay (and/or the LAN), so a shared link needs no
     * reply code. {@code base} is the relay origin the link was opened with.
     */
    @UiThread
    public void startReceiveFromRelay(@NonNull String base, @NonNull String id,
                                      @NonNull Listener listener) {
        stopSession(false);
        mListener = listener;
        mRole = "receive";
        mRecvSignalingBase = base;
        mRecvSignalingId = id;
        mSignaling = new P2pSignalingClient(mOkHttpClient);
        mSignaling.fetchOffer(base, id, offer -> mMainHandler.post(() -> {
            if (!"receive".equals(mRole)) {
                return; // torn down while fetching
            }
            if (offer == null) {
                // Soft: the link is expired or wrong — let the UI show "bad code"
                // and keep the scan/paste entry usable.
                if (mListener != null) {
                    mListener.onError("bad-code", "link expired");
                }
                stopSession(false);
                return;
            }
            beginReceive(offer);
        }));
    }

    /**
     * Receiver: open a SHORT offer link — {@code FDO1.<id>}. Fetch the full
     * self-contained offer the sender brokered at the rendezvous offer mailbox
     * ({@code /v1/p2p/o/<id>}), then run the normal receive. The answer still
     * returns via the offer's own {@code ans}/{@code rvz} (deliverAnswer), so we
     * do NOT set a signaling answer-back here — {@code mSignaling} is kept only
     * so teardown can cancel the in-flight fetch.
     */
    @UiThread
    public void startReceiveFromOfferRef(@NonNull String id, @NonNull Listener listener) {
        stopSession(false);
        mListener = listener;
        mRole = "receive";
        mSignaling = new P2pSignalingClient(mOkHttpClient);
        mSignaling.fetchOffer(Preferences.P2P_RENDEZVOUS_URL, id, offer ->
                mMainHandler.post(() -> {
                    if (!"receive".equals(mRole)) {
                        return; // torn down while fetching
                    }
                    if (offer == null || !offer.startsWith(OFFER_PREFIX)) {
                        // 204/expired or a garbage body — soft, so scan/paste stays
                        // usable and the UI says the link is no longer valid.
                        if (mListener != null) {
                            mListener.onError("bad-code", "link expired");
                        }
                        stopSession(false);
                        return;
                    }
                    beginReceive(offer);
                }));
    }

    /** Shared receive kickoff: start the loopback + engine and hand it the offer. */
    private void beginReceive(@NonNull String code) {
        // The loopback must exist now to host the engine page; the write
        // target is armed later in acceptOffer() on this same server.
        if (mServer == null && !startLoopback()) {
            postError("engine", "loopback start failed");
            return;
        }

        final JSONObject command = new JSONObject();
        try {
            command.put("type", "recv-start");
            command.put("code", code);
        } catch (JSONException e) {
            postError("engine", "command build failed");
            return;
        }
        // Same relay-creds gating as startSend; a failed fetch just means a
        // relay-less receive.
        withRelayCreds(creds -> {
            if (!"receive".equals(mRole) || mServer == null) {
                return; // torn down while fetching
            }
            try {
                putIceServers(command, creds);
            } catch (JSONException e) {
                postError("engine", "command build failed");
                return;
            }
            ensureEngineSession(() -> postCommand(command));
        });
    }

    /**
     * Receiver: user accepted the preview — create the target file, arm the
     * loopback write side, and let the engine answer.
     */
    @UiThread
    public void acceptOffer() {
        if (!"receive".equals(mRole) || mRecvName == null || mServer == null) {
            return;
        }
        // The target-file work (buildTargetFile → findByFilePath) hits Room,
        // which fatally rejects a main-thread query, plus disk I/O — so it MUST
        // run off the UI thread (same disk executor finalizeReceivedFile uses).
        // Snapshot the fields the disk task needs; the recv-accept command is
        // posted back on the main thread once the write side is armed.
        final String name = mRecvName;
        final String mime = mRecvMime;
        final P2pLoopbackServer server = mServer;
        mDiskExecutor.execute(() -> {
            final File partFile;
            try {
                StoragePaths.ensureDownloadPath(mContext);
                File target = buildTargetFile(name, mime);
                partFile = new File(target.getParentFile(), target.getName() + ".part");
                if (partFile.exists() && !partFile.delete()) {
                    throw new IOException("stale part file");
                }
                // Arm the write side on the SAME loopback that hosts the engine
                // page (started in startReceive) — no second server.
                server.setWriteTarget(partFile);
            } catch (IOException e) {
                mMainHandler.post(() -> postError("file", "cannot create target file"));
                return;
            }
            mMainHandler.post(() -> {
                // The session may have been torn down while we hopped threads
                // (Decline / back-press) — don't resurrect a dead one.
                if (!"receive".equals(mRole) || mServer != server) {
                    return;
                }
                mRecvPartFile = partFile;
                JSONObject command = new JSONObject();
                try {
                    command.put("type", "recv-accept");
                    command.put("writeUrl", server.getWriteUrl());
                } catch (JSONException e) {
                    postError("engine", "command build failed");
                    return;
                }
                postCommand(command);
            });
        });
    }

    /**
     * End the session: tells the engine to close the peer connection, stops
     * the loopback, deletes an unfinished .part. Called by the fragments in
     * onDestroyView — screen lifetime IS session lifetime.
     */
    @UiThread
    public void stop() {
        // Best-effort tell the engine to close; NEVER route through postCommand
        // (its no-port path posts a fatal onError back into a fragment that is
        // being torn down — e.g. Decline during the reload window flashed the
        // error card). Teardown proceeds regardless of the port state.
        if (mPort != null) {
            try {
                JSONObject command = new JSONObject();
                command.put("type", "stop");
                mPort.postMessage(command);
            } catch (JSONException e) {
                // Teardown proceeds regardless.
            }
        }
        stopSession(true);
    }

    /* ── engine events ──────────────────────────────────────────────────── */

    private void handleEngineEvent(JSONObject json) {
        String type = json.optString("type", "");
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "engine event: " + type);
        }
        switch (type) {
            case "ready" -> {
                mEngineReady = json.optBoolean("rtc", false);
                if (mEngineReady) {
                    runPendingIfReady();
                } else {
                    // The page has a real docShell but no RTCPeerConnection —
                    // the WebRTC pref didn't apply before the page loaded.
                    // Nothing to retry (the fragment owns the pref); fail so the
                    // UI shows an error instead of hanging on "preparing".
                    postError("engine", "webrtc unavailable");
                }
            }
            case "code" -> {
                String role = json.optString("role", "");
                String code = json.optString("code", "");
                if ("offer".equals(role)) {
                    onOfferReady(code);
                } else if ("answer".equals(role)) {
                    deliverAnswer(code);
                }
            }
            case "offer-parsed" -> {
                mRecvName = json.optString("name", "");
                mRecvMime = json.optString("mime", "");
                mRecvAnswerUrl = json.optString("ans", "");
                mRecvRendezvousUrl = json.optString("rvz", "");
                if (mListener != null) {
                    mListener.onOfferParsed(mRecvName, json.optLong("size", 0), mRecvMime,
                            json.optString("device", ""));
                }
            }
            case "state" -> {
                if (mListener != null) {
                    mListener.onConnectionState(json.optString("state", ""));
                }
            }
            case "transport" -> {
                if (mListener != null) {
                    mListener.onTransport(json.optBoolean("relayed", false));
                }
            }
            case "progress" -> {
                mTransferring = true;
                if (mListener != null) {
                    mListener.onProgress(json.optLong("done", 0), json.optLong("total", 0), json.optLong("rate", 0));
                }
            }
            case "done" -> handleDone(json);
            case "error" -> {
                String code = json.optString("code", "engine");
                if ("bad-code".equals(code)) {
                    // Soft: the session (and the shown QR) stays valid.
                    if (mListener != null) {
                        mListener.onError(code, json.optString("detail", ""));
                    }
                } else {
                    postError(code, json.optString("detail", ""));
                }
            }
            default -> {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "unknown engine event: " + type);
                }
            }
        }
    }

    private void handleDone(JSONObject json) {
        String role = json.optString("role", "");
        long bytes = json.optLong("bytes", 0);
        mTransferring = false; // completed — no "abandon?" confirm past here
        if ("send".equals(role)) {
            if (mListener != null) {
                mListener.onDone(role, bytes);
            }
            return;
        }
        // Receive: verify + rename off the main thread, insert the FINISHED
        // row (Room invalidation refreshes the Downloads list on its own),
        // and make the file visible to gallery apps.
        final Listener listener = mListener;
        final File partFile = mRecvPartFile;
        final String name = mRecvName;
        final String mime = mRecvMime;
        if (mServer != null) {
            mServer.closeWriteTarget();
        }
        mRecvFinalized = true;
        mDiskExecutor.execute(() -> {
            DownloadEntity entity = finalizeReceivedFile(partFile, name, mime, bytes);
            mMainHandler.post(() -> {
                mReceivedEntity = entity;
                if (entity == null) {
                    if (listener != null) {
                        listener.onError("file", "finalize failed");
                    }
                } else if (listener != null) {
                    listener.onDone(role, bytes);
                }
            });
        });
    }

    @Nullable
    private DownloadEntity finalizeReceivedFile(File partFile, String name, String mime, long bytes) {
        if (partFile == null || partFile.length() != bytes) {
            Log.e(TAG, "finalize: size mismatch");
            return null;
        }
        // Re-uniquify at completion — a same-named download may have landed
        // during the transfer.
        File target = buildTargetFile(name, mime);
        if (!partFile.renameTo(target)) {
            Log.e(TAG, "finalize: rename failed");
            return null;
        }
        String path = target.getAbsolutePath();
        String resolvedMime = mime == null || mime.isEmpty()
                ? FileUriHelper.getMimeTypeFromFile(path) : mime;

        DownloadEntity entity = new DownloadEntity();
        entity.setId(ID_RANDOM.nextInt());
        entity.setFileType((FileUriHelper.isImage(resolvedMime) ? UrlType.IMAGE : UrlType.FILE).getValue());
        // Rendered as the row's "MIME · domain" meta line — a p2p:// pseudo
        // host names the transport honestly without claiming a web origin.
        entity.setFileUrl("p2p://" + deviceSlug());
        entity.setFileOriginUrl("");
        entity.setFileName(target.getName());
        entity.setFileImg(path);
        entity.setFilePath(path);
        entity.setFileMimeType(resolvedMime);
        entity.setFileProgress(100);
        entity.setFileDate(System.currentTimeMillis());
        entity.setFileSize(bytes);
        entity.setFileStatus(Download.FINISHED);
        entity.setFileSafe(false);
        entity.setFileThumbnailUnavailable(false);
        mDownloadDataRepository.add(entity);
        GalleryPublisher.publish(mContext, path, resolvedMime);
        return entity;
    }

    private File buildTargetFile(String name, String mime) {
        // Mirror the download pipeline's naming (decode → sanitize → ensure
        // extension) so a P2P-received file is named identically to the same
        // file downloaded.
        String safeName = FileUriHelper.sanitizeFileName(FileUriHelper.decodeName(name));
        safeName = FileUriHelper.checkFileExtension(safeName, mime);
        File target = new File(StoragePaths.getDownloadPath(mContext), safeName);
        // Uniquify against BOTH disk AND the download table: a path free on
        // disk can still be owned by a queued/errored download row, and
        // colliding would let deleting that row destroy this file.
        while (target.exists()
                || mDownloadDataRepository.findByFilePath(target.getAbsolutePath()) != null) {
            target = new File(UrlParser.parseFilePath(target.getAbsolutePath()));
        }
        return target;
    }

    private static String deviceSlug() {
        String model = Build.MODEL == null ? "device" : Build.MODEL;
        String slug = model.toLowerCase(Locale.US).replaceAll("[^a-z0-9.-]+", "-");
        return slug.isEmpty() ? "device" : slug;
    }

    /* ── internals ──────────────────────────────────────────────────────── */

    /**
     * Start this session's loopback server (serves the engine page + read/write
     * byte endpoints). Called synchronously at session start so the engine-page
     * URL is available before the hidden GeckoSession is opened.
     */
    private boolean startLoopback() {
        try {
            mServer = new P2pLoopbackServer(mContext);
            mServer.start();
            return true;
        } catch (IOException e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "loopback start failed: " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Ensure the hidden engine session is open and RTC-ready, then run
     * {@code action}. The session hosts the page-world WebRTC engine (see the
     * class doc); it's opened fresh per share so the page picks up the
     * just-enabled WebRTC pref. {@code action} runs once the engine posts a
     * {@code ready} event with {@code rtc:true}; a timeout backstops a page
     * that never comes up.
     */
    private void ensureEngineSession(Runnable action) {
        if (mRuntime == null || mSessionRegistrar == null) {
            postError("engine", "engine unavailable");
            return;
        }
        if (mServer == null) {
            postError("engine", "loopback not started");
            return;
        }
        if (mEngineReady && mPort != null) {
            action.run();
            return;
        }
        mPendingEngineAction = action;
        if (mEngineSession == null) {
            openEngineSession();
        }
        mMainHandler.removeCallbacks(mEngineTimeout);
        mMainHandler.postDelayed(mEngineTimeout, ENGINE_READY_TIMEOUT_MS);
    }

    /**
     * Open the hidden GeckoSession on the loopback engine page. Order mirrors
     * PoTokenGenerator: registerSession (attach the WebExtension delegates)
     * MUST run before open() so the p2pshare content script + native port bind
     * to this session when GeckoView's WebExtension subsystem wires it up.
     */
    private void openEngineSession() {
        GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .allowJavascript(true)
                .build();
        GeckoSession session = new GeckoSession(settings);
        mEngineSession = session;
        mSessionRegistrar.accept(session);
        session.open(mRuntime);
        // Mark active so GeckoView treats it as a live tab for content-script
        // injection and doesn't background-throttle the engine's timers.
        session.setActive(true);
        session.loadUri(mServer.getEnginePageUrl());
    }

    private void closeEngineSession() {
        if (mEngineSession != null) {
            GeckoSession session = mEngineSession;
            mEngineSession = null;
            try {
                session.setActive(false);
                session.close();
            } catch (RuntimeException e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "engine session close: " + e.getMessage());
                }
            }
        }
        mPort = null;
    }

    /**
     * Receiver: get the answer back to the sender. Tries, in order of best UX:
     * (1) the LAN answer listener (serverless, same-network, instant),
     * (2) the signaling relay (cross-network, if the receiver opened a relay
     *     link), (3) the reply QR/code (last resort — the sender scans it).
     * Each step falls through to the next on failure.
     */
    private void deliverAnswer(@NonNull String code) {
        boolean hasLan = mRecvAnswerUrl != null && !mRecvAnswerUrl.isEmpty();
        boolean hasRvz = mRecvRendezvousUrl != null && !mRecvRendezvousUrl.isEmpty();
        if (!hasLan) {
            // Cross-network offer (no site-local `ans`): straight to the mailbox,
            // then the human-relayed reply if even that fails.
            if (hasRvz) {
                postAnswer(mRecvRendezvousUrl, code, ok -> {
                    if (!ok) {
                        relayOrReply(code);
                    }
                });
            } else {
                relayOrReply(code);
            }
            return;
        }
        // LAN + rendezvous, HAPPY-EYEBALLS (not strictly sequential). Fire the
        // LAN return now; if it hasn't delivered within a short head-start, ALSO
        // fire the mailbox in parallel. First to arrive wins — the sender treats
        // a later duplicate answer as a soft no-op (signalingState). This keeps a
        // real same-LAN share fully on the LAN (the listener answers in a few ms,
        // so the mailbox never fires), while a sender behind a full-tunnel
        // VPN/proxy — unreachable on its advertised `ans` endpoint — no longer
        // pays that path's full ~4s connect timeout BEFORE the mailbox starts.
        // That delay was fatal: the answer landed so late the sender installed
        // its TURN permissions after the peer's ICE had already given up (relay
        // candidates on both sides, yet no-path).
        final boolean[] settled = {false};      // a path delivered — suppress the rest
        final boolean[] lanFailed = {false};
        postAnswer(mRecvAnswerUrl, code, ok -> {
            if (ok) {
                settled[0] = true;
            } else {
                lanFailed[0] = true;
                if (!hasRvz && !settled[0]) {
                    relayOrReply(code);         // LAN failed, no mailbox → human reply
                }
            }
        });
        if (hasRvz) {
            mMainHandler.postDelayed(() -> {
                if (settled[0] || !"receive".equals(mRole)) {
                    return;                     // LAN already won (stays local), or session gone
                }
                postAnswer(mRecvRendezvousUrl, code, ok -> {
                    if (ok) {
                        settled[0] = true;
                    } else if (lanFailed[0] && !settled[0]) {
                        relayOrReply(code);     // both push paths failed
                    }
                });
            }, ANSWER_RENDEZVOUS_HEADSTART_MS);
        }
    }

    private void relayOrReply(@NonNull String code) {
        if (mSignaling != null && mRecvSignalingId != null
                && mRecvSignalingBase != null && !mRecvSignalingBase.isEmpty()) {
            mSignaling.postAnswer(mRecvSignalingBase, mRecvSignalingId, code, r ->
                    mMainHandler.post(() -> {
                        if (r != null) {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "answer posted to relay");
                            }
                            // Success: sender's poll picks it up; ICE proceeds.
                        } else {
                            deliverReplyFallback(code);
                        }
                    }));
        } else {
            deliverReplyFallback(code);
        }
    }

    /** POST the answer to one return endpoint (the sender's LAN listener or the
     *  rendezvous mailbox — same shape); report delivery to {@code done} on the
     *  main thread (true = the sender got it). */
    private void postAnswer(@NonNull String answerUrl, @NonNull String code,
                            @NonNull Consumer<Boolean> done) {
        Request request = new Request.Builder()
                .url(answerUrl)
                .post(RequestBody.create(code, MediaType.parse("text/plain; charset=utf-8")))
                .build();
        mAnswerClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "answer push failed (" + answerUrl + "): " + e.getMessage());
                }
                mMainHandler.post(() -> done.accept(false));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                boolean ok = response.isSuccessful();
                response.close();
                if (BuildConfig.DEBUG && ok) {
                    Log.d(TAG, "answer delivered: " + answerUrl);
                }
                mMainHandler.post(() -> done.accept(ok));
            }
        });
    }

    private void deliverReplyFallback(@NonNull String code) {
        // The session may have moved on (connected anyway, or torn down).
        if ("receive".equals(mRole) && mListener != null) {
            mRecvAnswerUrl = null; // don't re-attempt LAN on a re-emit
            mListener.onCode("answer", code);
        }
    }

    private void runPendingIfReady() {
        if (mEngineReady && mPendingEngineAction != null) {
            mMainHandler.removeCallbacks(mEngineTimeout);
            Runnable action = mPendingEngineAction;
            mPendingEngineAction = null;
            action.run();
        }
    }

    private void onEngineTimeout() {
        if (mPendingEngineAction != null) {
            mPendingEngineAction = null;
            postError("engine", "engine unavailable");
        }
    }

    private void postCommand(JSONObject command) {
        if (mPort == null) {
            postError("engine", "engine not connected");
            return;
        }
        mPort.postMessage(command);
    }

    private void postError(String code, String detail) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "error: " + code + " " + detail);
        }
        Listener listener = mListener;
        stopSession(true);
        if (listener != null) {
            listener.onError(code, detail);
        }
    }

    private void stopSession(boolean deletePartial) {
        mMainHandler.removeCallbacks(mEngineTimeout);
        mPendingEngineAction = null;
        mEngineReady = false;
        // Null the listener/role BEFORE closing the session so the port's
        // onDisconnect (fired by close()) sees a torn-down session and doesn't
        // post a spurious "engine disconnected" error.
        mListener = null;
        mRole = null;
        closeEngineSession();
        if (mServer != null) {
            mServer.stop();
            mServer = null;
        }
        if (mAnswerServer != null) {
            mAnswerServer.stop();
            mAnswerServer = null;
        }
        if (mRendezvous != null) {
            mRendezvous.cancel();
            mRendezvous = null;
        }
        if (mSignaling != null) {
            mSignaling.cancel();
            mSignaling = null;
        }
        mSignalingBase = null;
        mSignalingId = null;
        mShareLink = null;
        mOfferCode = null;
        mRendezvousId = null;
        mRecvRendezvousUrl = null;
        mRecvSignalingBase = null;
        mRecvSignalingId = null;
        mRecvAnswerUrl = null;
        if (deletePartial && mRecvPartFile != null && !mRecvFinalized) {
            // Off the main thread — exists()/delete() are disk I/O (StrictMode
            // flagged them on the error path). The loopback is already stopped
            // and the write target closed above, so nothing re-creates it.
            final File partFile = mRecvPartFile;
            mDiskExecutor.execute(() -> {
                if (partFile.exists() && !partFile.delete()) {
                    Log.e(TAG, "partial delete failed");
                }
            });
        }
        mRecvPartFile = null;
        mRecvName = null;
        mRecvMime = null;
        mRecvFinalized = false;
        mTransferring = false;
        mReceivedEntity = null;
    }

    /**
     * True while bytes are actively moving (a progress event has fired and the
     * transfer hasn't completed) — used to confirm before a back-press
     * abandons an in-flight transfer.
     */
    @UiThread
    public boolean isTransferActive() {
        return mTransferring;
    }

    /** The finished download row from the last completed receive, or null. */
    @Nullable
    @UiThread
    public DownloadEntity getReceivedEntity() {
        return mReceivedEntity;
    }
}

package com.solarized.firedown.p2pshare;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.StoragePaths;
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
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import okhttp3.Call;
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
     * Deep-link wrapper for the OFFER code so a scan with ANY scanner (the
     * phone's system camera included) offers "open in Firedown" and jumps
     * straight into the receive flow — a bare {@code FDS1.…} code is just text
     * to a generic scanner. The scheme+host are registered on DownloadsActivity
     * (AndroidManifest) and routed to {@code p2p_receive}. Only the offer is
     * wrapped: the ANSWER is fed back into the sender's already-open, LIVE
     * session (in-app scan / paste), so a deep link there would just relaunch
     * the app with no session to hand it to. {@link #stripDeepLink} makes the
     * in-app scanner + paste accept both the wrapped and bare forms.
     */
    public static final String DEEP_LINK_SCHEME = "firedown";
    public static final String DEEP_LINK_HOST = "p2p";
    public static final String DEEP_LINK_PREFIX =
            DEEP_LINK_SCHEME + "://" + DEEP_LINK_HOST + "/";

    /** Wrap a signaling code as a {@code firedown://p2p/<code>} deep link. */
    @NonNull
    public static String toDeepLink(@NonNull String code) {
        return DEEP_LINK_PREFIX + code;
    }

    /**
     * Return the bare {@code FDS1.}/{@code FDR1.} code from a scanned/pasted
     * value, stripping the {@code firedown://p2p/} deep-link wrapper if present.
     * Bare codes (the answer, or a pre-deep-link offer) pass through unchanged.
     */
    @NonNull
    public static String stripDeepLink(@NonNull String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith(DEEP_LINK_PREFIX)) {
            return trimmed.substring(DEEP_LINK_PREFIX.length());
        }
        return trimmed;
    }

    private static final long ENGINE_READY_TIMEOUT_MS = 8000;

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

        // Cross-network one-link: if a relay is configured, mint a rendezvous
        // id now; the offer is uploaded and the answer polled once the engine
        // emits the offer code (onOfferReady). Empty = serverless.
        mSignalingBase = Preferences.getP2pSignalingUrl(mSharedPreferences);
        if (!mSignalingBase.isEmpty()) {
            mSignaling = new P2pSignalingClient(mOkHttpClient);
            mSignalingId = P2pSignalingClient.mintId();
        }

        JSONObject command = new JSONObject();
        try {
            command.put("type", "send-start");
            command.put("readUrl", mServer.getReadUrl());
            command.put("answerUrl", mAnswerServer != null ? mAnswerServer.getAnswerUrl() : "");
            command.put("name", entity.getFileName());
            command.put("size", size);
            // Derive the mime from the file's OWN extension, not the entity's
            // stored label — that label is Firedown's internal/UI value (an
            // HLS capture muxed to .mp4 can carry a manifest mime) and would
            // mis-bucket the received download's type/icon/viewer.
            command.put("mime", FileUriHelper.getMimeTypeFromFile(new File(path).getName()));
            command.put("device", Build.MODEL);
            putIceServers(command);
        } catch (JSONException e) {
            postError("engine", "command build failed");
            return;
        }
        ensureEngineSession(() -> postCommand(command));
    }

    /**
     * Build the {@code iceServers} list for a start command (RTC shape: each
     * entry {@code {urls, username?, credential?}}). Per the CLAUDE.md P2P
     * invariant these come ONLY from the user's own settings — never a
     * baked-in fallback list: entry one is the chosen STUN echo, entry two is
     * the OPTIONAL user-configured TURN (their own coturn), off by default. So
     * a share with default settings contacts nothing but the STUN server, and a
     * genuinely-unreachable pair (VPN/CGNAT) fails honestly rather than leaking
     * to a public relay.
     *
     * <p><b>Relay-provider seam.</b> The first-party metered relay (paid,
     * unlinkable, per-use via the cloud mint) will add a THIRD, credentialed
     * entry here — short-lived coturn REST creds fetched against the account's
     * relay-GB balance — at {@link #appendMeteredRelay(JSONArray)}. It's a
     * no-op until that provider ships, keeping this method's contract (≤2
     * user-settings entries) intact today.
     */
    private void putIceServers(JSONObject command) throws JSONException {
        JSONArray servers = new JSONArray();

        String stun = Preferences.getP2pStunServer(mSharedPreferences);
        if (stun != null && !stun.isEmpty()) {
            servers.put(new JSONObject().put("urls", stun));
        }

        // Optional user-configured TURN (Settings → Direct share), for peers
        // that can't reach each other directly. Off by default.
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

        appendMeteredRelay(servers);

        command.put("iceServers", servers);
    }

    /**
     * Seam for the first-party metered relay (see the cloud mint / relay-GB
     * design). When it ships, this fetches short-lived coturn REST credentials
     * against the anonymous account's relay-GB balance and appends them as an
     * {@code iceServers} entry — so a paying user's VPN/CGNAT share falls back
     * to the firedown relay, per-use and unlinkable, with no config. Deliberately
     * a no-op until then: no relay ships in the app, and nothing is contacted.
     */
    private void appendMeteredRelay(JSONArray servers) {
        // Intentionally empty — the metered relay provider is not wired yet.
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

    /** The content the "Send a link" action shares: the relay link when the
     *  offer is up on a relay, otherwise the self-contained offer deep link. */
    @Nullable
    @UiThread
    public String getShareContent() {
        if (mShareLink != null) {
            return mShareLink;
        }
        return mOfferCode != null ? toDeepLink(mOfferCode) : null;
    }

    /**
     * Sender: an answer arrived over the LAN listener (same-network) or the
     * relay poll (cross-network) — validate and apply like a scanned reply.
     * First one wins; the engine ignores a later duplicate. Runs on main.
     */
    private void onDirectAnswer(@NonNull String raw) {
        applyIncomingAnswer(raw, "LAN return");
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

    /** Shared receive kickoff: start the loopback + engine and hand it the offer. */
    private void beginReceive(@NonNull String code) {
        // The loopback must exist now to host the engine page; the write
        // target is armed later in acceptOffer() on this same server.
        if (mServer == null && !startLoopback()) {
            postError("engine", "loopback start failed");
            return;
        }

        JSONObject command = new JSONObject();
        try {
            command.put("type", "recv-start");
            command.put("code", code);
            putIceServers(command);
        } catch (JSONException e) {
            postError("engine", "command build failed");
            return;
        }
        ensureEngineSession(() -> postCommand(command));
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
        if (mRecvAnswerUrl != null && !mRecvAnswerUrl.isEmpty()) {
            postAnswerLan(mRecvAnswerUrl, code, () -> relayOrReply(code));
        } else {
            relayOrReply(code);
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

    /** POST the answer to the sender's LAN listener; run {@code onFail} (main
     *  thread) if it doesn't land. */
    private void postAnswerLan(@NonNull String answerUrl, @NonNull String code,
                               @NonNull Runnable onFail) {
        Request request = new Request.Builder()
                .url(answerUrl)
                .post(RequestBody.create(code, MediaType.parse("text/plain; charset=utf-8")))
                .build();
        mAnswerClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "LAN answer failed: " + e.getMessage());
                }
                mMainHandler.post(onFail);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                boolean ok = response.isSuccessful();
                response.close();
                if (ok) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "answer returned over LAN");
                    }
                } else {
                    mMainHandler.post(onFail);
                }
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
        if (mSignaling != null) {
            mSignaling.cancel();
            mSignaling = null;
        }
        mSignalingBase = null;
        mSignalingId = null;
        mShareLink = null;
        mOfferCode = null;
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

package com.solarized.firedown.p2pshare;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.sync.crypto.Pow;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.RestoredFileAccess;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.manager.UrlType;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.GalleryPublisher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.ExperimentalGeckoViewApi;
import org.mozilla.geckoview.GeckoPreferenceController;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebExtension;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * (P2pSendFragment / P2pReceiveFragment), the foreground service that
 * carries a transfer ({@link P2pShareService}) and the WebRTC engine.
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
 * <p><b>Ownership: the SESSION is this controller's, the screens are windows
 * onto it.</b> The controller owns the hidden engine {@link GeckoSession},
 * the native {@link WebExtension.Port} (handed over by GeckoRuntimeHelper's
 * onConnect once content.js connects), the loopback byte server, the LAN
 * listeners, the session-scoped WebRTC pref flips and the post-transfer
 * bookkeeping (verify + rename the received file, insert the FINISHED
 * download row). The page owns RTCPeerConnection/DataChannel. The screens
 * own NO session state: they observe {@link #getState()} — a
 * {@link ShareState} snapshot republished on every change — and render it,
 * so a screen created while a session is already running (the sender came
 * back through the notification, or rotated) paints the same thing the one
 * that started it would. {@link P2pShareService} observes the same stream
 * and keeps the process foreground with a progress notification from the
 * moment a session must outlive its screen (the sender's offer is minted,
 * the receiver accepted — {@link P2pShareService#start}).
 * The old rule "session lifetime = view lifetime" is gone with this; what
 * ends a session is the transfer finishing, a cancel (screen or
 * notification), a failure, or an offer nobody accepted within
 * {@link #OFFER_LIFETIME_MS}.
 *
 * <p>One session at a time by design: starting a new one tears down the
 * previous. A terminal state (DONE / ERROR) keeps its snapshot — the engine
 * and servers are already gone — until a screen dismisses it
 * ({@link #dismissFinished()}) or a new session starts.
 *
 * <p><b>Readiness ordering.</b> RTCPeerConnection is pref-gated WebIDL
 * evaluated at page-global creation, so the controller applies the WebRTC
 * prefs and waits for them to APPLY before opening a FRESH engine session —
 * its page therefore loads with the prefs already on, no reload dance (a
 * fresh page-global sees the enabled pref). The engine posts
 * {@code {type:"ready", rtc:true}} once the page script is live; the queued
 * action runs on that event.
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
     * How long a minted offer waits for someone to accept before the session
     * ends on its own with {@link ShareState#ERROR_TIMEOUT}. Mirrors the
     * rendezvous mailbox's offer TTL and the answer poll's deadline
     * (firedown-api {@code offerTTL} / {@code P2pSignalingClient.POLL_DEADLINE_MS},
     * 15 min): past it a shared link no longer resolves and the poll has
     * stopped, so the offer is dead whichever way it was handed over. Needed
     * now that the sender can leave the screen — without a bound, a forgotten
     * share would hold the foreground service, the hidden session and the
     * LAN listener until the app died. Armed at onOfferReady, disarmed the
     * moment an answer is applied. (The poll's own null callback is NOT the
     * signal: it also fires at once when the mailbox answers an unexpected
     * status, which would end a healthy LAN-only share.)
     */
    private static final long OFFER_LIFETIME_MS = 15 * 60 * 1000;

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
    /**
     * No new relay-credential step (challenge → PoW → creds, one retry of a
     * transiently failed step) STARTS after this; a step already in flight
     * finishes under its own call timeout (10 s), so the worst case is about
     * 30 s, not 20. Beyond it the share proceeds relay-less — but SAYS so
     * (ShareState.relayAvailable) instead of blaming the network later. A
     * phone with no network, or behind a captive portal, skips the fetch.
     */
    private static final long RELAY_CREDS_BUDGET_MS = 20_000;

    /** The transfer locks' safety timeout: the dataSync service's 6-hour cap. */
    private static final long TRANSFER_LOCK_MAX_MS = 6L * 60 * 60 * 1000;

    private static final SecureRandom ID_RANDOM = new SecureRandom();

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
    // loads with the just-applied WebRTC prefs (see the readiness note above).
    private GeckoSession mEngineSession;
    private WebExtension.Port mPort;
    private boolean mEngineReady;
    private boolean mEngineOpening;
    private Runnable mPendingEngineAction;
    private final Runnable mEngineTimeout = this::onEngineTimeout;
    private final Runnable mOfferTimeout = this::onOfferTimeout;

    // The published session snapshot (see ShareState) and the controller's
    // own mutable copy of it. mState outlives the session's transport: after
    // DONE/ERROR the engine/servers are gone but the snapshot stays for the
    // screen. mSessionActive is the "transport belongs to a live session"
    // flag — false from the first line of stopSession, so a port
    // onDisconnect fired by our own close() can't post a spurious error.
    private final MutableLiveData<ShareState> mStateLive = new MutableLiveData<>();
    private ShareState mState;
    private boolean mSessionActive;

    // Active session transport. Main-thread only (all port + screen traffic
    // is UI-thread), except the finalize step which hops to the disk executor.
    private P2pLoopbackServer mServer;
    // Sender: the LAN listener the offer's `ans` URL points at (null = no LAN
    // address, mailbox / reply only). Receiver: the sender's `ans` URL from
    // the parsed offer (empty = no LAN return).
    private P2pLanServer mAnswerServer;
    // Set by the LAN workers (several may run at once): the first answer on
    // the wire is taken (204), later ones get 409 — until the engine refuses
    // the taken one as unreadable, which reopens it (onSenderBadCode).
    private final AtomicBoolean mAnswerTaken = new AtomicBoolean();
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
    // A long-poll is out (it hands back at most one answer per call).
    private boolean mRendezvousPolling;
    // Sender: the engine applied an answer (its "connecting" event). Until
    // then every answer path stays open, because an answer can still be
    // refused as unreadable or as another offer's.
    private boolean mAnswerApplied;
    private String mRecvRendezvousUrl;
    private String mRecvName;
    private String mRecvMime;
    private long mRecvSize;
    private File mRecvPartFile;
    private boolean mRecvFinalized;

    // Nearby (the receiver's side): the /offer listener + its NSD record,
    // alive only while the Receive screen says so (setNearbyListening).
    // Independent of a session — a phone stays visible between transfers.
    private final MutableLiveData<String> mVisibleAs = new MutableLiveData<>();
    private P2pLanServer mNearbyServer;
    private P2pNearbyAdvertiser mAdvertiser;
    private boolean mNearbyListening;
    // Read on the LAN server's worker threads: a second nearby sender while
    // an offer/transfer is in flight is answered 409, never queued. Atomic
    // because two workers can decide at once.
    private final AtomicBoolean mNearbyBusy = new AtomicBoolean();

    /**
     * Resume-tail window — MUST equal the engine's RESUME_TAIL: both sides
     * hash "the last min(64 KB, offset) bytes", and different window sizes
     * hash different ranges, so every resume would look like a mismatch and
     * silently restart from 0.
     */
    private static final int RESUME_TAIL_BYTES = 64 * 1024;

    /**
     * A kept .part older than this is dropped at the next accept — its sender
     * has long moved on, and unbounded partials would quietly eat storage.
     */
    private static final long PART_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    // Short-deadline client for the receiver's direct answer POST and the
    // sender's nearby offer POST: a LAN peer answers in milliseconds or not
    // at all, and the fallback should kick in fast when it doesn't. Derived
    // from the shared client (pool/DNS reused), tightened timeouts only.
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
    // Held while bytes can move (answer applied / offer accepted → stopSession),
    // see acquireTransferLocks.
    private WifiManager.WifiLock mWifiLock;
    private PowerManager.WakeLock mWakeLock;

    // Bumped by every stopSession: an async callback (relay creds, a poll,
    // an upload, a POST, a disk hop, the pref write) captures it at start and
    // acts only while it still matches, so a late answer from a cancelled
    // session can never land in the next one (isCurrent).
    private int mSessionGen;
    // Never reset, so a notice of a new session cannot reuse the number a
    // screen already showed (and dropped) for the last one.
    private int mNoticeCounter;
    // The session switched host-candidate obfuscation off and has not put
    // it back yet (see setObfuscation).
    private boolean mObfuscationOff;
    // Receiver: a previewed offer nobody accepted or declined.
    private final Runnable mPreviewTimeout = this::onPreviewTimeout;

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
        // Generous enough for a cold cellular TLS handshake through the edge
        // (the old 3 s connect / 4 s call lost the relay on exactly the
        // networks that need it), bounded as a whole by RELAY_CREDS_BUDGET_MS.
        mRelayClient = okHttpClient.newBuilder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
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

    /* ── observable state ───────────────────────────────────────────────── */

    /**
     * The session as the screens and the service see it: a fresh
     * {@link ShareState} snapshot on every change, {@code null} when there is
     * no session (never started, cancelled, or dismissed after DONE/ERROR).
     * LiveData replays the current value to a new observer, which is exactly
     * what a screen attaching to a running session needs.
     */
    @NonNull
    public LiveData<ShareState> getState() {
        return mStateLive;
    }

    /** The current snapshot, or null — for a synchronous decision (attach vs start). */
    @Nullable
    @UiThread
    public ShareState peekState() {
        return mState == null ? null : mState.copy();
    }

    /**
     * The name this phone is advertised under to nearby senders while
     * {@link #setNearbyListening} is on; null = not visible (listening off,
     * no Wi-Fi/LAN address, or NSD refused the registration).
     */
    @NonNull
    public LiveData<String> getVisibleAs() {
        return mVisibleAs;
    }

    private void publish() {
        mStateLive.setValue(mState == null ? null : mState.copy());
    }

    private boolean isSend() {
        return mSessionActive && mState != null && mState.role == ShareState.Role.SEND;
    }

    private boolean isReceive() {
        return mSessionActive && mState != null && mState.role == ShareState.Role.RECEIVE;
    }

    private void setStage(@NonNull ShareState.Stage stage) {
        if (mState != null && mState.stage != stage) {
            mState.stage = stage;
            publish();
        }
    }

    private void notice(@NonNull String key, @Nullable String arg) {
        if (mState != null) {
            mState.notice = key;
            mState.noticeArg = arg;
            mState.noticeSeq = ++mNoticeCounter;
            publish();
        }
    }

    /**
     * A screen showed notice {@code seq}: drop it from the state so a screen
     * that attaches later (rotation, the notification) does not show it
     * again. A newer notice is left alone.
     */
    @UiThread
    public void consumeNotice(int seq) {
        if (mState != null && mState.notice != null && mState.noticeSeq == seq) {
            mState.notice = null;
            mState.noticeArg = null;
            publish();
        }
    }

    /** {@code gen} is still the running session (see {@link #mSessionGen}). */
    private boolean isCurrent(int gen) {
        return mSessionActive && gen == mSessionGen;
    }

    /**
     * True while a transfer is connecting or moving bytes. A new share must
     * not replace it ({@link P2pSessionPolicy#blocksNewSession}); the screens
     * check this before starting one and say so instead.
     */
    @UiThread
    public boolean isBusyWithTransfer() {
        return mState != null && P2pSessionPolicy.blocksNewSession(true,
                mState.isLive(), mState.isInTransfer());
    }

    /* ── port lifecycle (called from GeckoRuntimeHelper) ────────────────── */

    @UiThread
    public void onPortConnected(@NonNull WebExtension.Port port) {
        // Only the hidden engine session's page may drive a share: the
        // content script is matched on 127.0.0.1, so any other session that
        // loaded a 127.0.0.1 page would otherwise hand over a port too.
        if (mEngineSession == null || port.sender.session != mEngineSession) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "p2pshare port from a foreign session refused");
            }
            port.disconnect();
            return;
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "engine port connected");
        }
        mPort = port;
        port.setDelegate(new WebExtension.PortDelegate() {
            @Override
            public void onPortMessage(@NonNull Object message, @NonNull WebExtension.Port p) {
                // Ignore a message from a port that is no longer the current one
                // (a previously-closed engine session's port delivering late) —
                // it must not be processed against the new session's state.
                // Mirrors the mPort == p guard in onDisconnect.
                if (mPort != p) {
                    return;
                }
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
                    // ran first, clearing mSessionActive) this is the expected
                    // teardown and the guard below no-ops; otherwise the engine
                    // page crashed mid-share and the transfer can't survive it.
                    if (mSessionActive) {
                        postError(ShareState.ERROR_ENGINE, "engine disconnected");
                    }
                }
            }
        });
    }

    /* ── session API (screens + service, main thread) ───────────────────── */

    /**
     * Sender: serve {@code entity}'s file on the loopback and ask the engine
     * for an offer code. From here the session is the controller's — the
     * screen may be left; {@link P2pShareService} carries it.
     */
    @UiThread
    public void startSend(@NonNull DownloadEntity entity) {
        if (isBusyWithTransfer()) {
            return; // the screen checked and said so; never kill a running transfer
        }
        stopSession(false);
        mState = new ShareState(ShareState.Role.SEND);
        mState.entity = entity;
        mSessionActive = true;
        mNearbyBusy.set(true);
        final int gen = mSessionGen;

        String path = entity.getFilePath();
        if (path == null) {
            publish();
            postError("file", "file unreadable");
            return;
        }
        long size = RestoredFileAccess.length(mContext, path);
        if (size <= 0) {
            publish();
            postError("file", "file unreadable");
            return;
        }
        // Derive the mime from the file's OWN extension, not the entity's
        // stored label — that label is Firedown's internal/UI value (an
        // HLS capture muxed to .mp4 can carry a manifest mime) and would
        // mis-bucket the received download's type/icon/viewer.
        String fileName = new File(path).getName();
        String mime = FileUriHelper.getMimeTypeFromFile(fileName);
        mState.fileName = fileName;
        mState.fileSize = size;
        mState.mime = mime;
        publish();

        if (!startLoopback()) {
            postError(ShareState.ERROR_ENGINE, "loopback start failed");
            return;
        }
        mServer.setReadFile(path);

        // Single-step flow: bind the LAN answer listener so the offer carries
        // an answer-return URL and the receiver's Accept connects directly —
        // no reply code. Binding can fail (no LAN address); the flow then
        // simply falls back to the mailbox or the human-relayed reply.
        mAnswerTaken.set(false);
        mAnswerServer = new P2pLanServer(senderLanHandler(gen), true, false);
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
            command.put("mime", mime);
            command.put("device", deviceName());
        } catch (JSONException e) {
            postError(ShareState.ERROR_ENGINE, "command build failed");
            return;
        }
        // The transfer must outlive the screen from here on: the offer waits
        // for an accept, then bytes move. The service observes the state and
        // stops itself when the session ends.
        P2pShareService.start(mContext);
        // The relay-creds fetch gates the command (iceServers are fixed at
        // RTCPeerConnection construction); its budget bounds the wait and a
        // failure just means a relay-less share — see withRelayCreds. The
        // generation tells THIS session from one started after a cancel
        // inside the fetch window.
        withRelayCreds(gen, creds -> {
            if (!isCurrent(gen) || !isSend()) {
                return; // torn down (or replaced) while fetching
            }
            try {
                putIceServers(command, creds);
            } catch (JSONException e) {
                postError(ShareState.ERROR_ENGINE, "command build failed");
                return;
            }
            // The sender gathers ICE the moment send-start runs, so its host
            // candidates must already be plain IPs (see setObfuscation).
            ensureEngineSession(gen, true, () -> postCommand(command));
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
    private void withRelayCreds(int gen, @NonNull Consumer<JSONObject> action) {
        long now = System.currentTimeMillis();
        if (mRelayCreds != null && RelayCredsPolicy.reusable(now, mRelayCredsExpiryMs)) {
            if (mState != null) {
                mState.relayAvailable = true;
                publish();
            }
            action.accept(mRelayCreds);
            return;
        }
        if (!networkAllowsRelayFetch()) {
            // No network (or a captive portal) = no STUN and no relay
            // either; don't spend the budget timing out against an
            // unreachable API before a LAN share can even mint its offer.
            deliverRelayCreds(gen, null, action);
            return;
        }
        fetchRelayChallenge(gen, action, now + RELAY_CREDS_BUDGET_MS, 1);
    }

    /**
     * Step 1: fetch a PoW challenge. 404 = relay/PoW not deployed → skip
     * straight to a relay-less share. A TRANSIENT failure (I/O) retries once
     * while the budget allows; anything else degrades the same way.
     */
    private void fetchRelayChallenge(int gen, @NonNull Consumer<JSONObject> action,
                                     long deadlineMs, int retriesLeft) {
        if (System.currentTimeMillis() >= deadlineMs) {
            deliverRelayCreds(gen, null, action);
            return;
        }
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
                if (retriesLeft > 0) {
                    fetchRelayChallenge(gen, action, deadlineMs, retriesLeft - 1);
                } else {
                    deliverRelayCreds(gen, null, action);
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                String challengeB64 = null;
                int bits = 0;
                boolean transient5xx = false;
                try (Response r = response) {
                    if (r.isSuccessful() && r.body() != null) {
                        JSONObject json = new JSONObject(r.body().string());
                        challengeB64 = json.optString("challenge", "");
                        bits = json.optInt("pow_bits", 0);
                    } else {
                        transient5xx = r.code() >= 500;
                    }
                } catch (IOException | JSONException e) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "relay challenge parse failed: " + e.getMessage());
                    }
                }
                if (challengeB64 == null || challengeB64.isEmpty()) {
                    if (transient5xx && retriesLeft > 0) {
                        fetchRelayChallenge(gen, action, deadlineMs, retriesLeft - 1);
                    } else {
                        deliverRelayCreds(gen, null, action);
                    }
                    return;
                }
                // A hostile/spoofed difficulty must not spin the solver forever
                // (HTTPS to api.firedown.app makes this unlikely, but cheap to
                // bound). Above the cap, treat as "no relay".
                if (bits <= 0 || bits > RELAY_POW_MAX_BITS) {
                    deliverRelayCreds(gen, null, action);
                    return;
                }
                fetchRelayCredsWithPoW(gen, challengeB64, bits, action, deadlineMs, retriesLeft);
            }
        });
    }

    /**
     * Whether the active network is worth the relay-credential fetch — the
     * decision itself is {@link RelayCredsPolicy#networkAllowsFetch} (no
     * network or a captive portal skips; "connected, no internet" does NOT,
     * because such networks often do reach the internet).
     */
    private boolean networkAllowsRelayFetch() {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return true;
            }
            Network network = manager.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
            return RelayCredsPolicy.networkAllowsFetch(network != null, caps != null,
                    caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL));
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Step 2 (runs on the OkHttp callback thread — off main, where the CPU
     * solve belongs): solve the hashcash and GET the creds with
     * challenge+nonce. The solve is a few ms at the base difficulty; the
     * adaptive climb only bites a mass-minter, never a single share.
     */
    private void fetchRelayCredsWithPoW(int gen, @NonNull String challengeB64, int bits,
                                        @NonNull Consumer<JSONObject> action,
                                        long deadlineMs, int retriesLeft) {
        String nonceB64;
        try {
            byte[] challenge = Base64.decode(
                    challengeB64, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            byte[] nonce = Pow.solve(RELAY_POW_RESOURCE, challenge, bits);
            nonceB64 = Base64.encodeToString(
                    nonce, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            deliverRelayCreds(gen, null, action); // malformed challenge
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
                // A solved challenge is single-use server-side, so a retry
                // starts over from a fresh challenge (budget permitting).
                if (retriesLeft > 0 && System.currentTimeMillis() < deadlineMs) {
                    fetchRelayChallenge(gen, action, deadlineMs, retriesLeft - 1);
                } else {
                    deliverRelayCreds(gen, null, action);
                }
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
                deliverRelayCreds(gen, creds, action);
            }
        });
    }

    /**
     * Cache (on success) and deliver relay creds on the main thread — to the
     * session that asked ({@code gen}) only: the cache is session-agnostic,
     * the relayAvailable flag and the start command are not.
     */
    private void deliverRelayCreds(int gen, @Nullable JSONObject creds,
                                   @NonNull Consumer<JSONObject> action) {
        mMainHandler.post(() -> {
            long now = System.currentTimeMillis();
            JSONObject handed = creds;
            if (creds != null) {
                mRelayCreds = creds;
                // The policy's margin keeps the refresh off an expiring
                // credential (an expired one also breaks allocation REFRESHES
                // mid-share, see RelayCredsPolicy).
                mRelayCredsExpiryMs = RelayCredsPolicy.expiryFor(now,
                        creds.optLong("ttl_seconds", 600));
            } else if (mRelayCreds != null
                    && RelayCredsPolicy.stillValid(now, mRelayCredsExpiryMs)) {
                // The fresh fetch failed but the cached credential is still
                // honoured: a short relay life beats no relay at all.
                handed = mRelayCreds;
            }
            if (!isCurrent(gen)) {
                return; // the session that asked is gone; keep only the cache
            }
            if (mState != null) {
                mState.relayAvailable = handed != null;
                publish();
            }
            action.accept(handed);
        });
    }

    /**
     * Sender: the scanned/pasted/pushed FDR1. reply code. Only handed to the
     * engine: nothing is torn down and the stage does not move until the
     * engine has APPLIED it ({@link #onAnswerApplied}, on its "connecting"
     * event). The answer can still be refused — unreadable (a truncated
     * paste, a link a messenger cut short) or another offer's (a stale reply
     * in the clipboard) — and that refusal is soft: the offer stays valid and
     * every other way an answer can arrive must still be open. Tearing them
     * down here used to leave the sender on "Connecting" with no paste or
     * scan button, no lifetime timer and the service held until Cancel.
     */
    @UiThread
    public void provideAnswer(@NonNull String code) {
        if (!isSend() || mAnswerApplied) {
            return;
        }
        JSONObject command = new JSONObject();
        try {
            command.put("type", "send-answer");
            command.put("code", code);
        } catch (JSONException e) {
            return;
        }
        postCommand(command);
    }

    /**
     * Sender: the engine applied an answer. Now the LAN listener, the offer
     * lifetime and the polls have done their job — no sockets held through
     * the transfer — and bytes can move, so the transfer locks go on.
     */
    private void onAnswerApplied() {
        if (mAnswerApplied) {
            return;
        }
        mAnswerApplied = true;
        mMainHandler.removeCallbacks(mOfferTimeout);
        if (mAnswerServer != null) {
            mAnswerServer.stop();
            mAnswerServer = null;
        }
        if (mRendezvous != null) {
            mRendezvous.cancel();
        }
        mRendezvousPolling = false;
        if (mSignaling != null) {
            mSignaling.cancel();
        }
        acquireTransferLocks();
    }

    /**
     * Sender: the engine refused an answer softly. Reopen the paths that
     * consumed it: the LAN listener takes the next answer instead of 409ing
     * it, and a rendezvous poll that returned this answer polls again.
     */
    private void onSenderBadCode() {
        reopenAnswerPaths();
        notice(ShareState.NOTICE_BAD_CODE, null);
    }

    /**
     * An answer that arrived was not usable: let the LAN listener take the
     * next one instead of 409ing it, and watch the mailbox again (a poll
     * hands back one answer per call, so the one that returned it is done).
     */
    private void reopenAnswerPaths() {
        mAnswerTaken.set(false);
        startRendezvousPoll(mSessionGen);
    }

    /** One long-poll at a time on the rendezvous mailbox, for session {@code gen}. */
    private void startRendezvousPoll(int gen) {
        if (mRendezvous == null || mRendezvousId == null || mRendezvousPolling
                || mAnswerApplied || !isCurrent(gen)) {
            return;
        }
        mRendezvousPolling = true;
        mRendezvous.pollAnswer(Preferences.P2P_RENDEZVOUS_URL, mRendezvousId, ans ->
                mMainHandler.post(() -> {
                    if (!isCurrent(gen)) {
                        return;
                    }
                    mRendezvousPolling = false;
                    onRendezvousAnswer(ans);
                }));
    }

    /**
     * Sender: the engine minted the offer. Publish it (the nearby list, the
     * link and the QR all act on it), start the rendezvous long-poll so a
     * shared LINK completes across networks, and arm the offer's lifetime.
     * The LAN answer listener runs in parallel; the first answer to arrive
     * wins.
     */
    private void onOfferReady(@NonNull String code) {
        final int gen = mSessionGen;
        mOfferCode = code;
        // ICE gathering is over (the offer carries every candidate), so the
        // browsing tabs get their host-candidate obfuscation back now rather
        // than after a wait of up to 15 minutes plus the transfer.
        setObfuscation(true, null);
        // Start the rendezvous long-poll: the receiver's answer will land in
        // the api mailbox and this picks it up. First answer to arrive (LAN
        // return, rendezvous, or a human-relayed reply) wins; the engine
        // ignores a later duplicate.
        if (mRendezvous != null && mRendezvousId != null) {
            startRendezvousPoll(gen);
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
                if (!isCurrent(gen) || !isSend()) {
                    return;
                }
                if (ok != null) {
                    mShareLink = base + "/s/" + id;
                    mSignaling.pollAnswer(base, id, ans ->
                            mMainHandler.post(() -> {
                                if (isCurrent(gen)) {
                                    onRelayAnswer(ans);
                                }
                            }));
                }
            }));
        }
        mMainHandler.removeCallbacks(mOfferTimeout);
        mMainHandler.postDelayed(mOfferTimeout, OFFER_LIFETIME_MS);
        if (mState != null) {
            mState.offerCode = code;
            mState.stage = ShareState.Stage.READY;
            publish();
        }
    }

    /** The offer outlived its window with no accept — end the session honestly. */
    private void onOfferTimeout() {
        if (isSend() && mState.stage == ShareState.Stage.READY) {
            postError(ShareState.ERROR_TIMEOUT, "no accept within the offer lifetime");
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
        if (!isSend() || mOfferCode == null) {
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
        final int gen = mSessionGen;
        // Bound the wait: on a slow/dead network the upload must NOT hang the
        // share sheet. Race it against a short timer — whichever fires first
        // delivers cb exactly once; the upload keeps running and, if it lands
        // later, caches mShareLink so the NEXT share is instantly short. A
        // session that ended meanwhile gets null (nothing to share): its
        // offer is dead, and caching its short link in the NEXT session
        // would share a link to the wrong offer.
        final boolean[] done = {false};
        final Runnable timeout = () -> {
            if (!done[0]) {
                done[0] = true;
                cb.accept(isCurrent(gen) ? toHttpsLink(full) : null);
            }
        };
        mMainHandler.postDelayed(timeout, SHORT_LINK_BROKER_TIMEOUT_MS);
        mRendezvous.uploadOffer(Preferences.P2P_RENDEZVOUS_URL, id, full, ok ->
                mMainHandler.post(() -> {
                    boolean current = isCurrent(gen) && isSend();
                    if (ok != null && current) {
                        mShareLink = toHttpsLink(OFFER_REF_PREFIX + id);
                    }
                    if (done[0]) {
                        return; // the timer already answered with the full link
                    }
                    done[0] = true;
                    mMainHandler.removeCallbacks(timeout);
                    if (!current) {
                        cb.accept(null);
                        return;
                    }
                    cb.accept(mShareLink != null ? mShareLink : toHttpsLink(full));
                }));
    }

    /**
     * Sender: a link went out through the share sheet. Recorded on the state
     * (not the screen) so a sender who leaves and comes back sees the waiting
     * card, not the share buttons again. {@code privateMode} is what "Send
     * link again" re-shares.
     */
    @UiThread
    public void markLinkShared(boolean privateMode) {
        if (isSend()) {
            mState.linkShared = true;
            mState.linkPrivate = privateMode;
            publish();
        }
    }

    /**
     * Sender: hand the offer to a phone from the nearby list — POST the whole
     * {@code FDS1.} code to its advertised {@code /offer}. The receiver then
     * previews it like a scanned code; its Accept comes back over this
     * session's {@code /answer} (same LAN, so the LAN return is what carries
     * it). 204 = delivered (the state names the device and waits); 409 = the
     * phone is busy with another transfer; anything else = unreachable. The
     * offer stays valid through all three — the user picks another phone or
     * sends a link.
     */
    @UiThread
    public void sendOfferToNearby(@NonNull P2pNearbyDiscovery.Device device) {
        if (!isSend() || mOfferCode == null || mState.stage != ShareState.Stage.READY) {
            return;
        }
        final String name = device.name;
        if (name.equals(mState.peerDevice)) {
            // Already sent there and waiting: a second POST would meet that
            // phone busy with our own first one and read as "X is busy".
            return;
        }
        final int gen = mSessionGen;
        Request request;
        try {
            request = new Request.Builder()
                    .url(device.offerUrl())
                    .post(RequestBody.create(mOfferCode, MediaType.parse("text/plain; charset=utf-8")))
                    .build();
        } catch (IllegalArgumentException e) {
            // Not a valid URL host (discovery lists IPv4 only, but never
            // crash the main thread over what a LAN peer advertised).
            notice(ShareState.NOTICE_UNREACHABLE, name);
            return;
        }
        mState.peerDevice = name;
        publish();
        mAnswerClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "nearby offer push failed: " + e.getMessage());
                }
                mMainHandler.post(() -> onNearbyOfferResult(gen, name, false, false));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                int code = response.code();
                response.close();
                mMainHandler.post(() -> onNearbyOfferResult(gen, name,
                        code >= 200 && code < 300, code == 409));
            }
        });
    }

    private void onNearbyOfferResult(int gen, @NonNull String name, boolean delivered, boolean busy) {
        if (!isCurrent(gen) || !isSend() || !name.equals(mState.peerDevice)) {
            return; // torn down, or the user already picked another phone
        }
        if (delivered) {
            return; // waiting for that phone's Accept (its answer lands on /answer)
        }
        mState.peerDevice = null;
        notice(busy ? ShareState.NOTICE_BUSY : ShareState.NOTICE_UNREACHABLE, name);
    }

    /** The receiver of a nearby/LAN offer said no — back to choosing. */
    private void onPeerDeclined() {
        if (isSend() && mState.stage == ShareState.Stage.READY) {
            mState.peerDevice = null;
            notice(ShareState.NOTICE_DECLINED, null);
        }
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
        if (!isSend()) {
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
        if (!isSend() || raw == null) {
            return;
        }
        String code = stripDeepLink(raw);
        if (!code.startsWith(ANSWER_PREFIX)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "answer rejected (bad prefix) via " + via);
            }
            // Not an answer at all: the path that delivered it must not stay
            // consumed (the LAN listener would 409 the real one, the mailbox
            // would never be polled again). No notice — the user handed in
            // nothing.
            if (!mAnswerApplied) {
                reopenAnswerPaths();
            }
            return;
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "answer received via " + via);
        }
        provideAnswer(code);
    }

    /**
     * The sender's LAN routes for session {@code gen}: a receiver's answer, or
     * its decline. Runs on the LAN server's workers; everything it hands on
     * lands on the main thread only while {@code gen} is still the session.
     */
    private P2pLanServer.Handler senderLanHandler(int gen) {
        return new P2pLanServer.Handler() {
            @Override
            public boolean onAnswer(@NonNull String body) {
                if (P2pLanServer.DECLINED_BODY.equals(body)) {
                    mMainHandler.post(() -> {
                        if (isCurrent(gen)) {
                            onPeerDeclined();
                        }
                    });
                    return true;
                }
                // First answer wins; a later one is 409 (the engine would
                // ignore it anyway, but say so on the wire). Reopened if the
                // engine refuses the first as unreadable (onSenderBadCode).
                if (!mAnswerTaken.compareAndSet(false, true)) {
                    return false;
                }
                mMainHandler.post(() -> {
                    if (isCurrent(gen)) {
                        onDirectAnswer(body);
                    }
                });
                return true;
            }

            @Override
            public boolean onOffer(@NonNull String body) {
                return false; // the sender serves no /offer
            }
        };
    }

    /**
     * Receiver: parse a scanned/pasted/pushed FDS1. offer code. The state
     * moves to OFFERED with the preview metadata; nothing connects until
     * {@link #acceptOffer()}. {@code arrivedRemote} = via link or paste (the
     * phones are apart), which shapes the error copy.
     */
    @UiThread
    public void startReceive(@NonNull String code, boolean arrivedRemote) {
        if (!beginReceiveSession(arrivedRemote, false)) {
            return;
        }
        beginReceive(code);
    }

    /**
     * Receiver: open a relay link — fetch the offer from the signaling relay by
     * {@code id}, then proceed as a normal receive. On accept, the answer is
     * posted back to the SAME relay (and/or the LAN), so a shared link needs no
     * reply code. {@code base} is the relay origin the link was opened with.
     */
    @UiThread
    public void startReceiveFromRelay(@NonNull String base, @NonNull String id) {
        if (!beginReceiveSession(true, false)) {
            return;
        }
        final int gen = mSessionGen;
        mRecvSignalingBase = base;
        mRecvSignalingId = id;
        mSignaling = new P2pSignalingClient(mOkHttpClient);
        mSignaling.fetchOffer(base, id, offer -> mMainHandler.post(() -> {
            if (!isCurrent(gen) || !isReceive()) {
                return; // torn down while fetching
            }
            if (offer == null) {
                // The link is expired or wrong — a terminal "bad code" the
                // screen dismisses back to its idle state.
                postError(ShareState.NOTICE_BAD_CODE, "link expired");
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
    public void startReceiveFromOfferRef(@NonNull String id) {
        if (!beginReceiveSession(true, false)) {
            return;
        }
        final int gen = mSessionGen;
        mSignaling = new P2pSignalingClient(mOkHttpClient);
        mSignaling.fetchOffer(Preferences.P2P_RENDEZVOUS_URL, id, offer ->
                mMainHandler.post(() -> {
                    if (!isCurrent(gen) || !isReceive()) {
                        return; // torn down while fetching
                    }
                    if (offer == null || !offer.startsWith(OFFER_PREFIX)) {
                        // 204/expired or a garbage body — the link is no longer
                        // valid; say so and let the screen go idle.
                        postError(ShareState.NOTICE_BAD_CODE, "link expired");
                        return;
                    }
                    beginReceive(offer);
                }));
    }

    /**
     * Tear down whatever ran before and open a fresh RECEIVE snapshot. False
     * (and nothing touched) while another transfer is connecting or moving
     * bytes — the screen checked {@link #isBusyWithTransfer()} and said so.
     */
    private boolean beginReceiveSession(boolean arrivedRemote, boolean arrivedNearby) {
        if (isBusyWithTransfer()) {
            return false;
        }
        stopSession(false);
        mState = new ShareState(ShareState.Role.RECEIVE);
        mState.arrivedRemote = arrivedRemote;
        mState.arrivedNearby = arrivedNearby;
        mSessionActive = true;
        mNearbyBusy.set(true);
        publish();
        return true;
    }

    /** Shared receive kickoff: start the loopback + engine and hand it the offer. */
    private void beginReceive(@NonNull String code) {
        // The loopback must exist now to host the engine page; the write
        // target is armed later in acceptOffer() on this same server.
        if (mServer == null && !startLoopback()) {
            postError(ShareState.ERROR_ENGINE, "loopback start failed");
            return;
        }

        final JSONObject command = new JSONObject();
        try {
            command.put("type", "recv-start");
            command.put("code", code);
        } catch (JSONException e) {
            postError(ShareState.ERROR_ENGINE, "command build failed");
            return;
        }
        // Same relay-creds gating as startSend; a failed fetch just means a
        // relay-less receive, and the generation pins the callback to this
        // session (see startSend).
        final int gen = mSessionGen;
        withRelayCreds(gen, creds -> {
            if (!isCurrent(gen) || !isReceive()) {
                return; // torn down (or replaced) while fetching
            }
            try {
                putIceServers(command, creds);
            } catch (JSONException e) {
                postError(ShareState.ERROR_ENGINE, "command build failed");
                return;
            }
            // The receiver gathers only at Accept (createAnswer), so the
            // engine opens with obfuscation untouched; acceptOffer switches
            // it off for exactly the gathering window.
            ensureEngineSession(gen, false, () -> postCommand(command));
        });
    }

    /**
     * Receiver: user accepted the preview — create the target file, arm the
     * loopback write side, and let the engine answer. From here the session
     * outlives the screen (the service carries it).
     */
    @UiThread
    public void acceptOffer() {
        if (!isReceive() || mRecvName == null || mServer == null
                || mState.stage != ShareState.Stage.OFFERED) {
            return;
        }
        setStage(ShareState.Stage.CONNECTING);
        mMainHandler.removeCallbacks(mPreviewTimeout);
        // Accepted: bytes will move — keep the CPU and Wi-Fi up from here.
        acquireTransferLocks();
        final int gen = mSessionGen;
        // The target-file work (buildTargetFile → findByFilePath) hits Room,
        // which fatally rejects a main-thread query, plus disk I/O — so it MUST
        // run off the UI thread (same disk executor finalizeReceivedFile uses).
        // Snapshot the fields the disk task needs; the recv-accept command is
        // posted back on the main thread once the write side is armed.
        final String name = mRecvName;
        final String mime = mRecvMime;
        final long size = mRecvSize;
        final P2pLoopbackServer server = mServer;
        mDiskExecutor.execute(() -> {
            final File partFile;
            long resumeOff = 0;
            String resumeTail = null;
            try {
                StoragePaths.ensureDownloadPath(mContext);
                pruneStaleParts(new File(StoragePaths.getDownloadPath(mContext)));
                File target = buildTargetFile(name, mime);
                partFile = new File(target.getParentFile(), target.getName() + ".part");
                // RESUME: a kept partial from an earlier broken attempt of this
                // same offer lands on the same .part path (the target name
                // re-uniquifies identically while nothing final exists). Offer
                // its bytes back: offset = what's on disk, tail = hash of the
                // last 64 KB — the SENDER verifies the tail against its own
                // bytes, so a different file behind the same name restarts at 0
                // instead of splicing. Every byte on disk is a correct prefix
                // even after a mid-batch cut: the channel is reliable+ordered
                // and the loopback writes sequentially, so a torn last POST
                // leaves a shorter correct prefix, never wrong bytes.
                long have = partFile.length();
                if (have > 0 && have <= size) {
                    resumeTail = tailHash(partFile, have);
                    if (resumeTail != null) {
                        resumeOff = have;
                    }
                }
                if (resumeOff == 0 && partFile.exists() && !partFile.delete()) {
                    throw new IOException("stale part file");
                }
                // Arm the write side on the SAME loopback that hosts the engine
                // page (started in startReceive) — no second server. keepBytes
                // holds the resumed prefix; the engine's answer carries the
                // matching off/tail (or nothing, and the transfer runs as
                // before from 0).
                server.setWriteTarget(partFile, resumeOff);
            } catch (IOException e) {
                mMainHandler.post(() -> postError("file", "cannot create target file"));
                return;
            }
            final long recvResumeOff = resumeOff;
            final String recvResumeTail = resumeTail;
            mMainHandler.post(() -> {
                // The session may have been torn down while we hopped threads
                // (Decline / cancel) — don't resurrect a dead one. We already
                // opened the write target on the disk thread, so UNDO it here:
                // close the RandomAccessFile (else its FD leaks — stopSession's
                // closeWriteTarget ran before mWriteFile existed) and delete the
                // orphaned EMPTY .part (stopSession's deletePartial saw a null
                // mRecvPartFile). A non-empty one is a kept resumable partial —
                // deleting it here would destroy the resume capital this
                // torn-down attempt never touched. closeWriteTarget is
                // synchronized, safe on the stopped server; the file delete is
                // disk I/O, off-main.
                if (!isCurrent(gen) || !isReceive() || mServer != server) {
                    server.closeWriteTarget();
                    mDiskExecutor.execute(() -> deletePartIfEmpty(partFile));
                    return;
                }
                mRecvPartFile = partFile;
                JSONObject command = new JSONObject();
                try {
                    command.put("type", "recv-accept");
                    command.put("writeUrl", server.getWriteUrl());
                    if (recvResumeOff > 0 && recvResumeTail != null) {
                        command.put("resumeOff", recvResumeOff);
                        command.put("resumeTail", recvResumeTail);
                    }
                } catch (JSONException e) {
                    postError(ShareState.ERROR_ENGINE, "command build failed");
                    return;
                }
                // Accepted: the transfer now outlives the screen.
                P2pShareService.start(mContext);
                // createAnswer gathers the receiver's candidates: they must be
                // plain IPs for a same-LAN pair (see setObfuscation), so the
                // command waits for the pref write; the "answer" code puts it
                // back.
                setObfuscation(false, () -> {
                    if (isCurrent(gen) && isReceive()) {
                        postCommand(command);
                    }
                });
            });
        });
    }

    /**
     * Receiver: say no to the offer being previewed (or still being read).
     * A LAN offer's sender is told at once (its {@code /answer} takes
     * {@code declined}) so it can pick another phone instead of waiting out
     * the offer's lifetime; a link/QR offer's sender is told too when its
     * {@code ans} is reachable, best-effort. Then the session is gone.
     */
    @UiThread
    public void declineOffer() {
        if (!isReceive() || (mState.stage != ShareState.Stage.OFFERED
                && mState.stage != ShareState.Stage.PREPARING)) {
            return;
        }
        if (mRecvAnswerUrl != null && !mRecvAnswerUrl.isEmpty()) {
            postAnswer(mRecvAnswerUrl, P2pLanServer.DECLINED_BODY, ok -> { });
        }
        stop();
    }

    /**
     * A previewed offer sat unanswered for the offer lifetime (the screen
     * left in the background, a pushed offer nobody saw): its sender has
     * given up by now, so decline it and free everything the session holds.
     */
    private void onPreviewTimeout() {
        if (isReceive() && mState.stage == ShareState.Stage.OFFERED) {
            declineOffer();
        }
    }

    /**
     * End the session from the screen's Cancel, the notification's Cancel,
     * or a screen leaving a pre-accept receive: tells the engine to close the
     * peer connection, stops the loopback, deletes an unfinished EMPTY .part,
     * and clears the published state.
     */
    @UiThread
    public void stop() {
        // Best-effort tell the engine to close; NEVER route through postCommand
        // (its no-port path posts a fatal error back into a screen that is
        // being torn down). Teardown proceeds regardless of the port state.
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
        mState = null;
        publish();
    }

    /**
     * A screen is done showing a DONE/ERROR outcome — drop the snapshot so the
     * next Send/Receive opens clean. No-op on a live session (a screen that
     * is merely being left must NOT end a running transfer).
     */
    @UiThread
    public void dismissFinished() {
        if (mState != null && !mState.isLive()) {
            mState = null;
            publish();
        }
    }

    /**
     * The foreground service was timed out by the system (the dataSync
     * 6-hour cap, {@code Service.onTimeout}) — the transfer cannot be kept
     * honest without it, so it ends as an error the user can retry.
     */
    @UiThread
    public void failForSystemTimeout() {
        if (mSessionActive) {
            postError("system", "foreground limit");
        }
    }

    /* ── nearby (the receiver's visibility) ─────────────────────────────── */

    /**
     * Receiver: be discoverable by nearby senders — bind the {@code /offer}
     * LAN listener and advertise it over NSD under this phone's name. On only
     * while the Receive screen is open AND idle (the screen toggles it);
     * {@link #getVisibleAs()} reports the registered name, or null when the
     * phone could not be made visible. Idempotent.
     */
    @UiThread
    public void setNearbyListening(boolean on) {
        if (on == mNearbyListening) {
            return;
        }
        mNearbyListening = on;
        if (!on) {
            if (mAdvertiser != null) {
                mAdvertiser.stop();
                mAdvertiser = null;
            }
            if (mNearbyServer != null) {
                mNearbyServer.stop();
                mNearbyServer = null;
            }
            mVisibleAs.setValue(null);
            return;
        }
        mNearbyServer = new P2pLanServer(mReceiverLanHandler, false, true);
        if (!mNearbyServer.start()) {
            // No LAN address (no Wi-Fi) — nothing to advertise.
            mNearbyServer = null;
            mVisibleAs.setValue(null);
            return;
        }
        mAdvertiser = new P2pNearbyAdvertiser(mContext, deviceName(),
                mNearbyServer.getPort(), mNearbyServer.getToken(), name -> {
            if (mNearbyListening) {
                mVisibleAs.setValue(name);
            }
        });
        mAdvertiser.start();
    }

    /** The receiver's LAN route: a nearby sender's offer. */
    private final P2pLanServer.Handler mReceiverLanHandler = new P2pLanServer.Handler() {
        @Override
        public boolean onAnswer(@NonNull String body) {
            return false; // the receiver serves no /answer
        }

        @Override
        public boolean onOffer(@NonNull String body) {
            if (!body.startsWith(OFFER_PREFIX) || !mNearbyBusy.compareAndSet(false, true)) {
                return false; // garbage, or already previewing/transferring
            }
            mMainHandler.post(() -> onNearbyOffer(body));
            return true;
        }
    };

    private void onNearbyOffer(@NonNull String code) {
        if (!mNearbyListening || (mState != null && mState.isLive())) {
            if (mState == null || !mState.isLive()) {
                mNearbyBusy.set(false);
            }
            return; // the screen closed, or a session slipped in first
        }
        if (!beginReceiveSession(false, true)) {
            return;
        }
        beginReceive(code);
    }

    /**
     * What the other phone sees this one as: the user's device name
     * (Settings → About → Device name), else the model. Also the offer's
     * {@code dev} field, so the receiver's "from X" and the sender's list
     * entry agree.
     */
    @NonNull
    private String deviceName() {
        String name = null;
        try {
            name = Settings.Global.getString(mContext.getContentResolver(),
                    Settings.Global.DEVICE_NAME);
        } catch (RuntimeException e) {
            // A settings provider hiccup is never worth more than the model.
        }
        if (name == null || name.trim().isEmpty()) {
            name = Build.MODEL;
        }
        return name == null || name.trim().isEmpty() ? "Firedown" : name.trim();
    }

    /* ── engine events ──────────────────────────────────────────────────── */

    private void handleEngineEvent(JSONObject json) {
        String type = json.optString("type", "");
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "engine event: " + type);
        }
        if (!mSessionActive) {
            return; // a late event from a session already torn down
        }
        switch (type) {
            case "ready" -> {
                mEngineReady = json.optBoolean("rtc", false);
                if (mEngineReady) {
                    runPendingIfReady();
                } else {
                    // The page has a real docShell but no RTCPeerConnection —
                    // the WebRTC pref didn't apply before the page loaded.
                    // Fail so the UI shows an error instead of hanging on
                    // "preparing".
                    postError(ShareState.ERROR_ENGINE, "webrtc unavailable");
                }
            }
            case "code" -> {
                String role = json.optString("role", "");
                String code = json.optString("code", "");
                if ("offer".equals(role)) {
                    onOfferReady(code);
                } else if ("answer".equals(role)) {
                    // The receiver's gathering is over: obfuscation back on.
                    setObfuscation(true, null);
                    deliverAnswer(code);
                }
            }
            case "offer-parsed" -> {
                mRecvName = json.optString("name", "");
                mRecvMime = json.optString("mime", "");
                mRecvSize = json.optLong("size", 0);
                mRecvAnswerUrl = json.optString("ans", "");
                mRecvRendezvousUrl = json.optString("rvz", "");
                if (mState != null) {
                    mState.fileName = mRecvName;
                    mState.mime = mRecvMime;
                    mState.fileSize = mRecvSize;
                    mState.peerDevice = json.optString("device", "");
                    mState.stage = ShareState.Stage.OFFERED;
                    publish();
                    mMainHandler.removeCallbacks(mPreviewTimeout);
                    mMainHandler.postDelayed(mPreviewTimeout, OFFER_LIFETIME_MS);
                }
            }
            case "state" -> onConnectionState(json.optString("state", ""));
            case "transport" -> {
                if (mState != null) {
                    mState.relayed = json.optBoolean("relayed", false);
                    publish();
                }
            }
            case "progress" -> {
                if (mState != null) {
                    mState.stage = ShareState.Stage.TRANSFERRING;
                    mState.done = json.optLong("done", 0);
                    mState.total = json.optLong("total", 0);
                    mState.rate = json.optLong("rate", 0);
                    mState.reconnecting = false;
                    publish();
                }
            }
            case "done" -> handleDone(json);
            case "error" -> {
                String code = json.optString("code", ShareState.ERROR_ENGINE);
                if (ShareState.NOTICE_BAD_CODE.equals(code) && isSend()) {
                    // Soft on the sender: the offer stays valid, the user just
                    // re-pastes or re-scans the reply, and every answer path
                    // stays (or is put back) open.
                    onSenderBadCode();
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

    /**
     * connecting | connected | disconnected | closed. Once bytes are flowing,
     * a state change NEVER leaves TRANSFERRING — a lossy/VPN path flaps
     * connected↔disconnected repeatedly, and reverting the screen to a
     * "Connected" card each time made the progress bar vanish and reappear.
     * The snapshot instead carries {@code reconnecting} (bar frozen, spinner
     * shown) until progress or "connected" clears it.
     */
    private void onConnectionState(@NonNull String state) {
        if (mState == null) {
            return;
        }
        if (mState.stage == ShareState.Stage.TRANSFERRING) {
            mState.reconnecting = !"connected".equals(state);
            publish();
            return;
        }
        if ("connecting".equals(state)) {
            // On the sender this is the engine saying it APPLIED an answer
            // (posted right after setRemoteDescription), the one point where
            // the answer paths can close.
            if (isSend()) {
                onAnswerApplied();
            }
            setStage(ShareState.Stage.CONNECTING);
        } else if ("connected".equals(state)) {
            setStage(ShareState.Stage.CONNECTED);
        }
    }

    private void handleDone(JSONObject json) {
        String role = json.optString("role", "");
        long bytes = json.optLong("bytes", 0);
        if ("send".equals(role)) {
            finish(ShareState.Stage.DONE, null, bytes, null);
            return;
        }
        // Receive: verify + rename off the main thread, insert the FINISHED
        // row (Room invalidation refreshes the Downloads list on its own),
        // and make the file visible to gallery apps.
        final File partFile = mRecvPartFile;
        final String name = mRecvName;
        final String mime = mRecvMime;
        if (mServer != null) {
            mServer.closeWriteTarget();
        }
        mRecvFinalized = true;
        final int gen = mSessionGen;
        mDiskExecutor.execute(() -> {
            DownloadEntity entity = finalizeReceivedFile(partFile, name, mime, bytes);
            mMainHandler.post(() -> {
                if (!isCurrent(gen) || !isReceive()) {
                    return; // cancelled while finalizing
                }
                if (entity == null) {
                    postError("file", "finalize failed");
                } else {
                    finish(ShareState.Stage.DONE, null, bytes, entity);
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
        GalleryPublisher.publishIfEnabled(mContext, path, resolvedMime);
        return entity;
    }

    private File buildTargetFile(String name, String mime) {
        // Mirror the download pipeline's naming (decode → sanitize → ensure
        // extension) so a P2P-received file is named identically to the same
        // file downloaded.
        String safeName = FileUriHelper.sanitizeFileName(
                FileUriHelper.decodeName(P2pFileNames.orFallback(name)));
        safeName = FileUriHelper.checkFileExtension(P2pFileNames.orFallback(safeName), mime);
        // Uniquify against BOTH disk AND the download table: a path free on
        // disk can still be owned by a queued/errored download row, and
        // colliding would let deleting that row destroy this file. Bounded,
        // and correct for a name with no extension — the old loop over
        // UrlParser.parseFilePath spun forever there, on the shared disk
        // executor (see P2pFileNames).
        return P2pFileNames.uniquify(new File(StoragePaths.getDownloadPath(mContext)),
                safeName, candidate -> candidate.exists()
                        || mDownloadDataRepository.findByFilePath(candidate.getAbsolutePath()) != null);
    }

    /**
     * base64url (no padding) SHA-256 of the file's last {@code min(64 KB,
     * length)} bytes — the receiver's half of the resume handshake, compared
     * by the sender against the same range of its own file. Encoding matches
     * the engine's bytesToBase64Url exactly; null on any failure (the caller
     * then starts fresh — a lost resume, never a wrong one).
     */
    @Nullable
    private static String tailHash(File file, long length) {
        int want = (int) Math.min(RESUME_TAIL_BYTES, length);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(length - want);
            byte[] tail = new byte[want];
            raf.readFully(tail);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.encodeToString(digest.digest(tail),
                    Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (IOException | NoSuchAlgorithmException e) {
            Log.e(TAG, "tailHash", e);
            return null;
        }
    }

    /** Delete a .part only when empty — a non-empty one is resume capital. */
    private static void deletePartIfEmpty(File partFile) {
        if (partFile.exists() && partFile.length() == 0 && !partFile.delete()) {
            Log.e(TAG, "empty part delete failed");
        }
    }

    /**
     * Drop kept partials whose retry window has passed, from any background
     * thread. Second trigger for {@link #pruneStaleParts(File)} — see there for
     * why a next-accept-only prune isn't enough.
     */
    public static void pruneStaleParts(@NonNull Context context) {
        pruneStaleParts(new File(StoragePaths.getDownloadPath(context)));
    }

    /**
     * Drop kept partials whose retry window has passed. TWO triggers, both
     * needed: (1) the disk executor inside acceptOffer, BEFORE the resume
     * check — so an expired partial of the very offer being accepted correctly
     * starts fresh; (2) MediaListenerWorker on every DownloadsActivity resume —
     * because accept-only pruning kept a failed receive's partial FOREVER for a
     * user who never accepts another share, and that user is exactly the likely
     * one after a big transfer died near the end (a ~98 GB .part with no
     * DownloadEntity row: invisible to the Downloads UI and the missing-file
     * sweep, reclaimable only via a file manager). Only this feature writes
     * {@code *.part} in the public download dir (the download pipeline does
     * not), and the age gate keeps every trigger safe against a live session:
     * the one .part a running session owns is at most minutes old.
     */
    private static void pruneStaleParts(File downloadDir) {
        File[] parts = downloadDir.listFiles((dir, name) -> name.endsWith(".part"));
        if (parts == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - PART_MAX_AGE_MS;
        for (File part : parts) {
            if (part.lastModified() < cutoff && !part.delete() && BuildConfig.DEBUG) {
                Log.d(TAG, "stale part prune failed: " + part.getName());
            }
        }
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
     * Keep the transfer moving with the screen off, from the moment bytes can
     * move (the sender's engine applied an answer, the receiver accepted)
     * until stopSession — NOT through the up-to-15-minute wait for an accept
     * or a receiver's preview, where nothing moves and holding them only
     * costs battery.
     * <ul>
     *   <li>A PARTIAL wake lock: the foreground service keeps the process
     *       alive but not the CPU, and a suspended CPU stops sending (the
     *       watchdog then fails the share as "stalled"). Timed at the
     *       dataSync service's 6-hour cap, so a leak can never outlive it.</li>
     *   <li>A Wi-Fi lock (FULL_HIGH_PERF): Wi-Fi power save turned background
     *       transfers into a crawl. Deprecated in API 34 and possibly inert
     *       there; LOW_LATENCY is no substitute (foreground + screen on
     *       only, the case that does not need it), so it is kept for the
     *       releases where it works and the wake lock carries the rest.</li>
     * </ul>
     * Reference counting off so a double acquire/release cannot strand either.
     */
    @SuppressWarnings("deprecation")
    private void acquireTransferLocks() {
        try {
            if (mWakeLock == null) {
                PowerManager power = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
                if (power != null) {
                    mWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                            "firedown:p2pshare");
                    mWakeLock.setReferenceCounted(false);
                }
            }
            if (mWakeLock != null && !mWakeLock.isHeld()) {
                mWakeLock.acquire(TRANSFER_LOCK_MAX_MS);
            }
        } catch (RuntimeException e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "wake lock: " + e.getMessage());
            }
        }
        try {
            if (mWifiLock == null) {
                WifiManager manager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
                if (manager != null) {
                    mWifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                            "firedown:p2pshare");
                    mWifiLock.setReferenceCounted(false);
                }
            }
            if (mWifiLock != null && !mWifiLock.isHeld()) {
                mWifiLock.acquire();
            }
        } catch (RuntimeException e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "wifi lock: " + e.getMessage());
            }
        }
    }

    private void releaseTransferLocks() {
        try {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        } catch (RuntimeException e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "wake unlock: " + e.getMessage());
            }
        }
        try {
            if (mWifiLock != null && mWifiLock.isHeld()) {
                mWifiLock.release();
            }
        } catch (RuntimeException e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "wifi unlock: " + e.getMessage());
            }
        }
    }

    /**
     * Ensure the hidden engine session is open and RTC-ready, then run
     * {@code action}. The session hosts the page-world WebRTC engine (see the
     * class doc); it's opened fresh per share, AFTER the session-scoped WebRTC
     * prefs are applied ({@link #applyWebRtcPrefs}), so the page picks them
     * up. {@code action} runs once the engine posts a {@code ready} event with
     * {@code rtc:true}; a timeout backstops a page that never comes up.
     */
    private void ensureEngineSession(int gen, boolean obfuscationOff, Runnable action) {
        if (mRuntime == null || mSessionRegistrar == null) {
            postError(ShareState.ERROR_ENGINE, "engine unavailable");
            return;
        }
        if (mServer == null) {
            postError(ShareState.ERROR_ENGINE, "loopback not started");
            return;
        }
        if (mEngineReady && mPort != null) {
            action.run();
            return;
        }
        mPendingEngineAction = action;
        if (mEngineSession == null && !mEngineOpening) {
            mEngineOpening = true;
            applyWebRtcPrefs(obfuscationOff, () -> {
                // A write from a session already torn down must not open an
                // engine for (or clear the flag of) the one that replaced it.
                if (!isCurrent(gen)) {
                    return;
                }
                mEngineOpening = false;
                if (mServer == null || mEngineSession != null) {
                    return; // re-opened while the prefs applied
                }
                openEngineSession();
            });
        }
        mMainHandler.removeCallbacks(mEngineTimeout);
        mMainHandler.postDelayed(mEngineTimeout, ENGINE_READY_TIMEOUT_MS);
    }

    /**
     * The WebRTC prefs, applied BEFORE the engine page opens — a page loaded
     * before the writes are visible sees the old values, and
     * RTCPeerConnection is pref-gated WebIDL evaluated at page-global
     * creation:
     * <ul>
     *   <li>{@code media.peerconnection.enabled}: ALWAYS on since the user
     *       toggle was removed (boot sets it). The write here is an idempotent
     *       belt-and-braces so a share can never race a cold boot's async
     *       pref write.</li>
     *   <li>Host-candidate obfuscation off, for a SENDER only — its page
     *       gathers ICE the moment send-start runs (see
     *       {@link #setObfuscation}). A receiver turns it off later, around
     *       its own gathering at Accept.</li>
     * </ul>
     * {@code then} runs on the main thread, applied or not (a refused write
     * surfaces as the engine's {@code rtc:false} ready event, which fails the
     * session honestly instead of hanging it).
     */
    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    private void applyWebRtcPrefs(boolean obfuscationOff, @NonNull Runnable then) {
        GeckoPreferenceController
                .setGeckoPref("media.peerconnection.enabled", true,
                        GeckoPreferenceController.PREF_BRANCH_USER)
                .accept(unused -> {
                    if (obfuscationOff) {
                        setObfuscation(false, then);
                    } else {
                        then.run();
                    }
                }, error -> then.run());
    }

    /**
     * {@code media.peerconnection.ice.obfuscate_host_addresses}, for exactly
     * the ICE-gathering windows of a share. Firefox hides host ICE candidates
     * behind mDNS {@code <uuid>.local} names (a browsing-privacy feature —
     * pages can't read the LAN IP); resolving a peer's .local name needs
     * multicast DNS, which on Android generally fails (no MulticastLock), so
     * with obfuscation on a same-LAN pair finds NO host pair and ICE dies with
     * "add a TURN server". Gecko reads the pref when a peer connection starts
     * gathering, so it is off only from just before gathering to the minted
     * code: the sender's offer ({@link #onOfferReady}), the receiver's answer.
     * It used to stay off for the whole session — up to 15 minutes of waiting
     * plus the transfer, often while the user browsed, and any page creating
     * an RTCPeerConnection meanwhile got raw LAN IPs. A process death in the
     * window would leave the saved user pref off; GeckoRuntimeHelper restores
     * it at boot. {@code then} (may be null) runs on the main thread, written
     * or not.
     */
    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    private void setObfuscation(boolean on, @Nullable Runnable then) {
        if (on && !mObfuscationOff) {
            if (then != null) {
                then.run();
            }
            return;
        }
        mObfuscationOff = !on;
        GeckoPreferenceController
                .setGeckoPref("media.peerconnection.ice.obfuscate_host_addresses", on,
                        GeckoPreferenceController.PREF_BRANCH_USER)
                .accept(unused -> {
                    if (then != null) {
                        then.run();
                    }
                }, error -> {
                    if (then != null) {
                        then.run();
                    }
                });
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
        // injection and doesn't background-throttle the engine's timers, and
        // ask for process priority: with the screen gone (the service carries
        // the transfer) this hidden session's content process is otherwise
        // the first thing Android trims under memory pressure, which ended a
        // transfer as "engine disconnected".
        session.setActive(true);
        session.setPriorityHint(GeckoSession.PRIORITY_HIGH);
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
     *     link), (3) the reply code (last resort — a human carries it).
     * Each step falls through to the next on failure.
     */
    private void deliverAnswer(@NonNull String code) {
        final int gen = mSessionGen;
        boolean hasLan = mRecvAnswerUrl != null && !mRecvAnswerUrl.isEmpty();
        boolean hasRvz = mRecvRendezvousUrl != null && !mRecvRendezvousUrl.isEmpty();
        if (!hasLan) {
            // Cross-network offer (no site-local `ans`): straight to the mailbox,
            // then the human-relayed reply if even that fails.
            if (hasRvz) {
                postAnswer(mRecvRendezvousUrl, code, ok -> {
                    if (!ok && isCurrent(gen)) {
                        relayOrReply(gen, code);
                    }
                });
            } else {
                relayOrReply(gen, code);
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
        final String rendezvousUrl = mRecvRendezvousUrl;
        postAnswer(mRecvAnswerUrl, code, ok -> {
            if (!isCurrent(gen)) {
                return;
            }
            if (ok) {
                settled[0] = true;
            } else {
                lanFailed[0] = true;
                if (!hasRvz && !settled[0]) {
                    relayOrReply(gen, code);    // LAN failed, no mailbox → human reply
                }
            }
        });
        if (hasRvz) {
            mMainHandler.postDelayed(() -> {
                if (settled[0] || !isCurrent(gen) || !isReceive()) {
                    return;                     // LAN already won (stays local), or session gone
                }
                postAnswer(rendezvousUrl, code, ok -> {
                    if (!isCurrent(gen)) {
                        return;
                    }
                    if (ok) {
                        settled[0] = true;
                    } else if (lanFailed[0] && !settled[0]) {
                        relayOrReply(gen, code); // both push paths failed
                    }
                });
            }, ANSWER_RENDEZVOUS_HEADSTART_MS);
        }
    }

    private void relayOrReply(int gen, @NonNull String code) {
        if (mSignaling != null && mRecvSignalingId != null
                && mRecvSignalingBase != null && !mRecvSignalingBase.isEmpty()) {
            mSignaling.postAnswer(mRecvSignalingBase, mRecvSignalingId, code, r ->
                    mMainHandler.post(() -> {
                        if (!isCurrent(gen)) {
                            return;
                        }
                        if (r != null) {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "answer posted to relay");
                            }
                            // Success: sender's poll picks it up; ICE proceeds.
                        } else {
                            deliverReplyFallback(gen, code);
                        }
                    }));
        } else {
            deliverReplyFallback(gen, code);
        }
    }

    /** POST a body to one return endpoint (the sender's LAN listener or the
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

    private void deliverReplyFallback(int gen, @NonNull String code) {
        // The session may have moved on (connected anyway, or torn down).
        if (isCurrent(gen) && isReceive()) {
            mRecvAnswerUrl = null; // don't re-attempt LAN on a re-emit
            mState.answerCode = code;
            publish();
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
            postError(ShareState.ERROR_ENGINE, "engine unavailable");
        }
    }

    private void postCommand(JSONObject command) {
        if (mPort == null) {
            postError(ShareState.ERROR_ENGINE, "engine not connected");
            return;
        }
        mPort.postMessage(command);
    }

    private void postError(String code, String detail) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "error: " + code + " " + detail);
        }
        if (mState == null) {
            stopSession(true);
            return;
        }
        finish(ShareState.Stage.ERROR, code, 0, null);
    }

    /**
     * End the transport and publish the TERMINAL snapshot: the engine, the
     * servers and the polls go now, the state stays (with the outcome) until a
     * screen dismisses it. An ERROR drops an EMPTY partial; DONE keeps the
     * (already renamed) file.
     */
    private void finish(@NonNull ShareState.Stage stage, @Nullable String errorCode,
                        long bytes, @Nullable DownloadEntity received) {
        ShareState snapshot = mState;
        stopSession(stage == ShareState.Stage.ERROR);
        mState = snapshot;
        if (mState != null) {
            mState.stage = stage;
            mState.errorCode = errorCode;
            mState.bytes = bytes;
            mState.received = received;
            mState.reconnecting = false;
            mState.answerCode = null;
            publish();
        }
    }

    /**
     * Drop the session's TRANSPORT (engine session, loopback, LAN listener,
     * polls, timers, per-session fields). Deliberately does NOT touch
     * {@link #mState}: callers decide whether the snapshot survives
     * ({@link #finish} keeps it as the outcome, {@link #stop} clears it, a
     * new start replaces it).
     */
    private void stopSession(boolean deletePartial) {
        // The transport is dead from this line: a port onDisconnect fired by
        // our own close() below, or a late engine event, must see a torn-down
        // session and not post a spurious error. The generation bump makes
        // every callback still in flight for it a no-op (isCurrent).
        mSessionActive = false;
        mSessionGen++;
        mNearbyBusy.set(false);
        mAnswerTaken.set(false);
        mAnswerApplied = false;
        mRendezvousPolling = false;
        mEngineOpening = false;
        mMainHandler.removeCallbacks(mEngineTimeout);
        mMainHandler.removeCallbacks(mOfferTimeout);
        mMainHandler.removeCallbacks(mPreviewTimeout);
        mPendingEngineAction = null;
        mEngineReady = false;
        closeEngineSession();
        setObfuscation(true, null);
        if (mServer != null) {
            mServer.stop();
            mServer = null;
        }
        releaseTransferLocks();
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
            //
            // RESUME: only an EMPTY partial is deleted. A non-empty .part is
            // the resume capital — re-accepting the same offer picks it up and
            // streams only the remainder (the tail-hash handshake keeps a
            // different same-named file from splicing onto it). Bounded by
            // pruneStaleParts at the next accept, so kept partials cannot
            // accumulate past PART_MAX_AGE_MS.
            final File partFile = mRecvPartFile;
            mDiskExecutor.execute(() -> deletePartIfEmpty(partFile));
        }
        mRecvPartFile = null;
        mRecvName = null;
        mRecvMime = null;
        mRecvSize = 0;
        mRecvFinalized = false;
    }

    /**
     * True while bytes are actively moving (a progress event has fired and the
     * transfer hasn't completed) — the screens confirm an explicit Cancel on
     * it.
     */
    @UiThread
    public boolean isTransferActive() {
        return mState != null && mState.isTransferring();
    }

    /** The finished download row from the last completed receive, or null. */
    @Nullable
    @UiThread
    public DownloadEntity getReceivedEntity() {
        return mState == null ? null : mState.received;
    }
}

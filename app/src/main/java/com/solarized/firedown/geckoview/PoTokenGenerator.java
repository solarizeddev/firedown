package com.solarized.firedown.geckoview;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Mints PO tokens for SABR downloads from Java instead of the WebExtension's
 * JS orchestration in {@code background.js}.
 *
 * <h3>Why this exists</h3>
 * The pre-existing JS path ({@code background.js generatePoToken} →
 * {@code browser.tabs.create('robots.txt')} → content script BotGuard runner)
 * is fragile because (a) it goes through GeckoView's WebExtension Tabs API,
 * whose state-conversion code throws {@code webProgress is undefined} /
 * {@code WindowEventDispatcher win is null} cascades that destroy the tab
 * mid-mint, and (b) it relies on JS {@code setTimeout} for timeout/retry,
 * which dies after those same GeckoView faults corrupt the WebExtension
 * event dispatcher. We worked around both with pre-warm, coalescing,
 * microtask-yield retry loops, and a 3-attempt outer loop, but each
 * workaround layers fragility on fragility.
 *
 * <h3>What this class does differently</h3>
 * <ul>
 *   <li>Creates a {@link GeckoSession} <i>directly</i> via {@code new GeckoSession()}
 *       instead of going through {@code browser.tabs.create}. The session
 *       isn't enrolled in the WebExtension tab list, so {@code ext-tabs.js}
 *       never iterates over it and its buggy state-conversion code never
 *       fires for our session.</li>
 *   <li>Owns the timeout / retry / lifecycle on a JVM thread with
 *       {@link CompletableFuture#get(long, TimeUnit)} and Java's executor
 *       primitives. These don't share a fate with GeckoView's JS event
 *       dispatcher — they survive WebExtension scheduler faults.</li>
 *   <li>Keeps the BotGuard session alive across {@link #generate} calls so
 *       per-video mints reuse the cached BotGuard VM inside {@code content.js}
 *       (~5h validity window) and complete in ~100ms instead of ~3s.</li>
 *   <li>Mints fresh per-video each call (never caches the token itself) so
 *       the {@code contentBinding} always matches the video YouTube checks
 *       against — fixing the latent bug in the JS cache that returned a
 *       videoA-bound token to a videoB download.</li>
 * </ul>
 *
 * <h3>Communication with the page</h3>
 * The BotGuard JS still has to run inside the page (it needs {@code youtube.com}
 * origin for the {@code jnn-pa.googleapis.com} fetch + a DOM for the
 * {@code bgutils-js} VM). What we change is who orchestrates around it: a
 * native port named {@code youtube-potoken} opened by the existing
 * {@code content.js} when it loads on {@code /robots.txt}. Java holds the
 * port, sends {@code mint} requests over it, and receives the per-video
 * token over the same port. No {@code browser.tabs.create}, no
 * {@code runtime.sendMessage} via {@code background.js}, no {@code setTimeout}
 * in the critical path.
 *
 * <h3>Lifecycle</h3>
 * Singleton. Session is created on first {@link #generate} call, reused
 * across calls until {@value #SESSION_TTL_MS} (matches the BotGuard minter's
 * own ~5h cache TTL inside {@code content.js} — re-using the session past
 * that point would just trigger a fresh BotGuard challenge inside the page,
 * so we recycle the whole thing instead). Closes the session and fails any
 * in-flight mints on {@link #shutdown}, on session age expiry, or on an
 * unrecoverable port disconnect.
 *
 * <h3>Concurrency</h3>
 * All session state mutations go through {@link #lock}. Multiple
 * {@link #generate} callers serialize on the lock for the session-creation
 * step but run mints concurrently against the live port (each mint carries
 * its own request id, replies dispatched via the {@link #pending} map).
 */
public class PoTokenGenerator {

    private static final String TAG = "PoTokenGenerator";

    /** Plain-text page on youtube.com — no CSP, so the page can {@code eval} bgutils.
     *  The {@code #fd-native} hash tells {@code content.js} to open the native
     *  port (otherwise it would try to in every JS-orchestrated robots.txt tab
     *  too, churning our port whenever that tab loads or dies). */
    private static final String ROBOTS_URL = "https://www.youtube.com/robots.txt#fd-native";

    /** Matches the {@code cm}-cache TTL inside {@code content.js}; after this we recycle the session. */
    private static final long SESSION_TTL_MS = 5L * 60 * 60 * 1000;

    /** Max wait for the page to load + content script to send {@code ready} over the port.
     *
     *  <p>Was 3 s, justified as "fast fail is more important than chasing the
     *  last few % of slow networks" — reasoning that belonged to the era when
     *  a JS-shipped token backstopped a native miss. There is no fallback now:
     *  no token means SABR walks into the attestation wall ~60 s in and the
     *  download fails, so a premature give-up doesn't fail fast, it fails the
     *  download. Loading youtube.com/robots.txt on a cold mobile connection
     *  routinely passes 3 s.</p> */
    private static final long INIT_TIMEOUT_MS = 10_000;

    /** How long a not-yet-ready session is left alone after {@link #INIT_TIMEOUT_MS}
     *  expires before we give up on it and rebuild.
     *
     *  <p>Load-bearing for slow networks: tearing the session down the moment
     *  OUR wait expired discarded a page that was still loading, so the next
     *  attempt restarted from zero and hit the same wall — a slow connection
     *  could never converge, no matter how many downloads were tried. Leaving
     *  it up lets the next caller piggy-back on the same {@link #readyFuture}
     *  and collect the page when it finally arrives; the grace window bounds
     *  that so a genuinely dead session still gets recycled.</p> */
    private static final long SESSION_INIT_GRACE_MS = 30_000;

    /** Max wait for a mint reply that can use the page's cached minter —
     *  normally &lt;100 ms. */
    private static final long MINT_TIMEOUT_MS = 15_000;

    /** Max wait for a {@code forceFresh} mint, which runs the FULL BotGuard
     *  attestation in the page: att/get → interpreter-VM fetch → snapshot →
     *  GenerateIT → new minter. The snapshot step ALONE is allowed 10 s by
     *  {@code content.js}, and the VM script is a large fetch, so the 15 s
     *  above is not a safe ceiling for it — on a slow device the attestation
     *  recovery would time out and return no token exactly when it is needed.
     *  Must stay below {@code content.js}'s own mint ceiling so the Java side
     *  is the one that gives up first. */
    private static final long MINT_FRESH_TIMEOUT_MS = 30_000;

    /** How long a minted token may be served from {@link #tokenCache}.
     *
     *  <p>Much shorter than the session, and deliberately so: the cache exists
     *  only to spare a video download and its timedtext sibling a second
     *  round-trip, which happens within seconds of each other. A warm mint is
     *  ~100 ms and hits no network, so a short window costs almost nothing —
     *  whereas serving an hours-old token (captured now, downloaded after
     *  lunch) is a straight attestation failure the moment the download
     *  starts. The token's real validity is anchored to the page's integrity
     *  token, whose lifetime this class cannot see; this bounds our exposure
     *  to it. */
    private static final long TOKEN_CACHE_TTL_MS = 10 * 60 * 1000;

    /** Port name the content script connects to. Must match the literal in
     *  {@code content.js}. Note: {@code connectNative} validates against
     *  {@code /^\w+(\.\w+)*$/} — hyphens are rejected, so use underscore. */
    public static final String PORT_NAME = "youtube_potoken";

    private final GeckoRuntime runtime;
    /** Hooks the session into the WebExtension wiring so the youtube extension
     *  content scripts get injected when robots.txt loads. Provided by
     *  {@code GeckoRuntimeHelper} (which owns the loaded extensions map) to
     *  avoid a circular dependency. */
    private final Consumer<GeckoSession> sessionRegistrar;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Serializes session-create/close + lifecycle field access.
     *
     * <p><b>Lock ordering invariant:</b> when both {@code lock} and {@code pending}
     * are held, {@code lock} is acquired first. No method takes {@code pending}
     * first and then {@code lock}. Mint registration in {@link #mint} acquires
     * both in this order to make the port-snapshot + pending-registration
     * atomic vs. the disconnect sweep in {@link #failAllPending}.</p>
     *
     * <p><b>Lock-vs-future-wait invariant:</b> callers MUST NOT hold {@code lock}
     * while blocking on {@link CompletableFuture#get}. The port-handshake
     * delegate (driven by the Gecko main thread) takes {@code lock} to mutate
     * state, so any thread sleeping under the lock would deadlock the
     * handshake. {@link #ensureReady} captures the future under the lock and
     * then releases before awaiting.</p>
     */
    private final Object lock = new Object();

    @GuardedBy("lock") private GeckoSession session;
    @GuardedBy("lock") private long sessionCreatedAt;
    /** Set when the content script's {@code ready} message arrives over the port. */
    @GuardedBy("lock") private CompletableFuture<Void> readyFuture;
    /** The Port handed to us by {@link #onPortConnected}; set after content script connects. */
    @GuardedBy("lock") private WebExtension.Port port;

    /** Per-mint reply tracker — requestId → future. Concurrent because completions arrive
     *  from the port delegate on the GeckoView main thread, but {@code generate()} waits
     *  on the future from arbitrary caller threads. */
    private final Map<String, CompletableFuture<String>> pending = new HashMap<>();

    /** videoId → minted token, shared across callers (SABR + timedtext) for the
     *  same video. A PoToken's contentBinding is the videoId, so a token minted
     *  for video X is valid for any download of video X — letting a subtitle
     *  download reuse the token the video download already minted (and vice
     *  versa), saving a ~100ms page round-trip. Keyed by videoId so we never
     *  reintroduce the old JS bug of serving a videoA token to a videoB
     *  download. Cleared in {@link #closeSessionLocked} so a cached token can
     *  never outlive the BotGuard session that backs its validity. */
    @GuardedBy("lock") private final Map<String, CachedToken> tokenCache = new HashMap<>();

    /** A minted token plus when we minted it, so {@link #TOKEN_CACHE_TTL_MS}
     *  can be enforced on read. */
    private static final class CachedToken {
        final String token;
        final long mintedAt;
        CachedToken(String token, long mintedAt) {
            this.token = token;
            this.mintedAt = mintedAt;
        }
    }

    /**
     * Outcome of one mint round-trip. {@code timedOut} distinguishes the two
     * failures that both used to surface as a bare {@code null}, and they want
     * opposite responses: the page answering with an error (att/get 429, VM
     * fault) means the session is ALIVE and recycling it would just burn ~3 s
     * to land in the same place, whereas the page not answering at all is the
     * wedged-session signal — that one must recycle, or every later mint pays
     * the same full timeout for the rest of the session TTL.
     */
    private static final class MintResult {
        @Nullable final String token;
        final boolean timedOut;
        MintResult(@Nullable String token, boolean timedOut) {
            this.token = token;
            this.timedOut = timedOut;
        }
    }

    public PoTokenGenerator(@NonNull GeckoRuntime runtime,
                            @NonNull Consumer<GeckoSession> sessionRegistrar) {
        this.runtime = runtime;
        this.sessionRegistrar = sessionRegistrar;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Public API
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Mint a PO token bound to the given video. Blocking call — run from a
     * background thread (NOT the main thread, NOT a Gecko thread).
     *
     * @param videoId YouTube video ID, used as the BotGuard {@code contentBinding}.
     *                Falls back to {@code visitorData} only if videoId is empty.
     * @param visitorData Base64-encoded visitor data from YouTube. Passed
     *                    through to the in-page BotGuard runner.
     * @return token, or {@code null} on any failure (timeout, port dropped,
     *         session create failed, content script error). Caller decides
     *         whether to retry or fall back.
     */
    @Nullable
    public String generate(@NonNull String videoId, @Nullable String visitorData) {
        return generate(videoId, visitorData, false);
    }

    /**
     * Mint a PO token the server has NOT already seen — the recovery path for
     * a mid-stream {@code STREAM_PROTECTION_STATUS 3} (attestation required).
     *
     * <p>This exists because plain {@link #generate} is cache-first at TWO
     * layers, and on this path both of them hand back the exact token the
     * server just rejected:</p>
     * <ol>
     *   <li>{@link #tokenCache} here (videoId → token) returns the rejected
     *       token in 0 ms — the shipped bug: both of {@code SabrDownloader}'s
     *       "fresh PO token" attempts were cache hits, so a 100-minute
     *       download died ~4 s after the demand having never once asked the
     *       page to mint anything.</li>
     *   <li>The page's cached {@code WebPoMinter} in {@code content.js}
     *       ({@code cm}, ~5 h TTL). It is bound to one integrity token and
     *       mints over the identifier, so re-minting through it reproduces
     *       the same rejected token — clearing only the Java cache would
     *       still recover nothing.</li>
     * </ol>
     *
     * <p>So this evicts the entry here AND sets {@code forceFresh} on the
     * mint request, which makes {@code content.js} drop {@code cm} and run
     * the full BotGuard attestation again (att/get → interpreter VM →
     * snapshot → GenerateIT → new minter). That is the strongest reset
     * available without recycling the whole session, and it is the only
     * thing that yields a token bound to an integrity token the server has
     * not already refused.</p>
     *
     * <p>Costs a real attestation round-trip (~3 s) instead of ~100 ms — the
     * right trade when the alternative is failing the download. The result
     * replaces the cache entry, so a later caller (a timedtext download of
     * the same video) gets the good token rather than the rejected one.</p>
     */
    @Nullable
    public String generateFresh(@NonNull String videoId, @Nullable String visitorData) {
        return generate(videoId, visitorData, true);
    }

    @Nullable
    private String generate(@NonNull String videoId, @Nullable String visitorData,
                            boolean forceFresh) {
        Log.i(TAG, "generate: videoId=" + videoId + " visitorData="
                + (visitorData != null ? visitorData.length() + " chars" : "null")
                + (forceFresh ? " forceFresh" : ""));
        // Step 1: make sure we have a live session + content script ready.
        // Critical: we MUST NOT hold `lock` while awaiting the ready signal.
        // The signal arrives via onPortConnected → handlePortMessage on the
        // GeckoView main thread, both of which take `lock` to mutate state.
        // If we held `lock` during readyFuture.get(), the port handshake
        // would deadlock waiting for the lock we're sleeping on — exactly
        // the failure mode the diagnostic logs surfaced ("Long monitor
        // contention ... in onPortConnected for 2.591s" then "ready" arrives
        // ~14ms after our timeout fired).
        boolean ready = ensureReady();
        if (!ready) {
            Log.w(TAG, "generate: session not ready, aborting");
            return null;
        }

        // Step 2: serve a cached token if we already minted one for this
        // video within the current session. Checked AFTER ensureReady so a
        // recycled session (which clears the cache in closeSessionLocked)
        // can't hand back a token whose backing BotGuard session is gone.
        // Skipped entirely on the forceFresh path — there the cached token
        // is precisely the one the server refused, so serving it would make
        // the whole recovery a no-op (see generateFresh).
        if (!TextUtils.isEmpty(videoId)) {
            synchronized (lock) {
                if (forceFresh) {
                    // Evict BEFORE minting, not after: if the fresh mint
                    // fails we must not leave the rejected token behind for
                    // the next caller to pick up as a "hit".
                    if (tokenCache.remove(videoId) != null) {
                        Log.i(TAG, "generate: evicted rejected token for " + videoId);
                    }
                } else {
                    CachedToken cached = tokenCache.get(videoId);
                    if (cached != null) {
                        long age = System.currentTimeMillis() - cached.mintedAt;
                        if (age < TOKEN_CACHE_TTL_MS) {
                            Log.i(TAG, "generate: cache hit for " + videoId
                                    + " (" + cached.token.length() + " chars, age=" + age + "ms)");
                            return cached.token;
                        }
                        // Aged out — drop it rather than serve a token the
                        // server is likely to refuse (see TOKEN_CACHE_TTL_MS).
                        Log.i(TAG, "generate: cached token for " + videoId
                                + " expired (age=" + age + "ms) — re-minting");
                        tokenCache.remove(videoId);
                    }
                }
            }
        }

        // Step 3: send mint request, wait for reply. Both can happen
        // concurrently across callers because the port can multiplex via
        // per-request ids.
        MintResult result = mint(videoId, visitorData, forceFresh);
        if (!TextUtils.isEmpty(result.token) && !TextUtils.isEmpty(videoId)) {
            synchronized (lock) {
                tokenCache.put(videoId, new CachedToken(result.token, System.currentTimeMillis()));
            }
        }
        if (result.timedOut) {
            // The page never answered. Whatever wedged it (a dead BotGuard VM,
            // a navigated-away document, a broken event dispatcher) will still
            // be wedged on the next call, and ensureReady's liveness test —
            // session != null && port != null — cannot see any of it, so every
            // later mint would pay this same full timeout until the session
            // ages out hours from now. Recycle so the next caller rebuilds.
            Log.w(TAG, "mint timed out — recycling session so the next attempt rebuilds");
            synchronized (lock) {
                closeSessionLocked();
            }
        }
        Log.i(TAG, "generate: result="
                + (result.token != null ? result.token.length() + " chars" : "null"));
        return result.token;
    }

    /**
     * Drop the cached token for one video, so the next {@link #generate}
     * mints instead of serving it. For a caller that has learned the token
     * is bad in a way this class cannot see — the server refusing it — and
     * wants the knowledge to outlive the failed download. Idempotent; leaves
     * the session and every other video's token alone.
     */
    public void invalidate(@NonNull String videoId) {
        synchronized (lock) {
            if (tokenCache.remove(videoId) != null) {
                Log.i(TAG, "invalidate: dropped cached token for " + videoId);
            }
        }
    }

    /**
     * Tear down the session and fail any in-flight mints. Idempotent.
     * Call from app shutdown (and from anywhere else state recovery is needed).
     */
    public void shutdown() {
        Log.d(TAG, "shutdown");
        synchronized (lock) {
            closeSessionLocked();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Wired by GeckoRuntimeHelper when the content script's native port connects
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Called by {@code GeckoRuntimeHelper.MessageDelegate.onConnect} when
     * the content script on robots.txt opens its native port.
     *
     * <p>Wires up the port's delegate to dispatch replies into {@link #pending}.
     * Also resolves the {@link #readyFuture} so {@link #ensureReadyLocked} can
     * proceed.</p>
     */
    public void onPortConnected(@NonNull WebExtension.Port newPort) {
        Log.i(TAG, "port connected (sender=" + newPort.sender + ")");
        synchronized (lock) {
            // If we're somehow already holding a port (e.g. content script
            // reconnected after a session restart), drop the old one first.
            if (port != null && port != newPort) {
                Log.w(TAG, "replacing existing port — failing any in-flight mints");
                failAllPending("port replaced");
            }
            port = newPort;
        }
        newPort.setDelegate(new WebExtension.PortDelegate() {
            @Override
            public void onPortMessage(@NonNull Object message, @NonNull WebExtension.Port src) {
                if (!(message instanceof JSONObject)) {
                    Log.w(TAG, "onPortMessage: not a JSONObject");
                    return;
                }
                JSONObject json = (JSONObject) message;
                handlePortMessage(json);
            }

            @Override
            public void onDisconnect(@NonNull WebExtension.Port src) {
                synchronized (lock) {
                    // Only the port we are actually holding may tear the
                    // session down. A reconnect (the page reloading, which
                    // onPortConnected above already anticipates) leaves the
                    // OLD port to disconnect afterwards, and this used to
                    // close the session and null the brand-new port that had
                    // just replaced it — destroying a live, healthy session
                    // and failing any mint riding on it. The next generate()
                    // then rebuilt from zero for no reason, and a mint caught
                    // in the window returned no token at all.
                    if (port != src) {
                        Log.w(TAG, "stale port disconnected — keeping the live session");
                        return;
                    }
                    Log.w(TAG, "port disconnected");
                    port = null;
                    failAllPending("port disconnected");
                    // Session is likely dead too; clear it so the next
                    // generate() rebuilds from scratch.
                    closeSessionLocked();
                }
            }
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // Internal
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Returns true once we have a live session + connected port + content
     * script that signalled ready. The caller MUST NOT hold {@link #lock}
     * — see the comment in {@link #generate} for why.
     *
     * <p>Splits into two phases on purpose: a short critical section under
     * {@code lock} that either confirms the cached session is still good or
     * kicks off creation + captures the {@link #readyFuture}, followed by
     * the long {@code future.get()} wait done WITHOUT the lock so the port
     * handshake delegate can take the lock and resolve the future.</p>
     */
    private boolean ensureReady() {
        CompletableFuture<Void> waitOn;
        synchronized (lock) {
            long age = System.currentTimeMillis() - sessionCreatedAt;
            Log.i(TAG, "ensureReady: session=" + (session != null)
                    + " port=" + (port != null) + " age=" + age + "ms");
            if (session != null && port != null && age < SESSION_TTL_MS) {
                return true;
            }
            // A creation kicked off by a concurrent caller may already be
            // in flight — piggy-back on its future instead of starting a
            // second creation that would race with the first. Two cases:
            //   (a) session==null but readyFuture in flight: the main-thread
            //       runnable hasn't created the session yet.
            //   (b) session!=null but port==null and readyFuture in flight:
            //       session is created, awaiting content.js to connect.
            // Either way, our future.get() will resolve when the port
            // handshake fires.
            if (readyFuture != null && !readyFuture.isDone()) {
                Log.i(TAG, "ensureReady: piggy-backing on in-flight creation");
                waitOn = readyFuture;
            } else {
                // Session is stale or never created — tear down and rebuild.
                if (session != null) {
                    Log.i(TAG, "session stale (age=" + age + "ms) — recycling");
                    closeSessionLocked();
                }
                waitOn = startSessionLocked();
                if (waitOn == null) {
                    return false;
                }
            }
        }

        long t0 = System.currentTimeMillis();
        try {
            waitOn.get(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Our wait expiring is not proof the page is dead — on a slow
            // connection it is usually still loading. Tearing it down here
            // meant the next attempt restarted from zero and hit the same
            // wall, so a slow network never converged and every download on
            // it ran tokenless into the attestation wall. Leave a young
            // session up instead: the next caller piggy-backs on the same
            // readyFuture and collects the page when it arrives. Past the
            // grace window it is genuinely stuck, so recycle.
            synchronized (lock) {
                long age = System.currentTimeMillis() - sessionCreatedAt;
                boolean giveUp = session == null || age > SESSION_INIT_GRACE_MS;
                Log.w(TAG, "ready signal timed out after " + INIT_TIMEOUT_MS + "ms"
                        + (giveUp ? " — recycling (age=" + age + "ms)"
                                  : " — leaving the session loading (age=" + age + "ms)"));
                if (giveUp) {
                    closeSessionLocked();
                }
            }
            return false;
        } catch (ExecutionException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.w(TAG, "ready signal failed: " + e.getMessage());
            synchronized (lock) { closeSessionLocked(); }
            return false;
        }
        Log.i(TAG, "session ready after " + (System.currentTimeMillis() - t0) + "ms");
        return true;
    }

    /** Caller MUST hold {@link #lock}. Kicks off session creation on the main
     *  thread and returns the future that resolves when content.js signals
     *  ready. Returns {@code null} only on the rare path where we couldn't
     *  even post the creation runnable. */
    @Nullable
    private CompletableFuture<Void> startSessionLocked() {
        Log.i(TAG, "createSession: building hidden session for " + ROBOTS_URL);
        final CompletableFuture<Void> future = new CompletableFuture<>();
        readyFuture = future;
        // Create + register + open + load all on the Gecko main thread.
        // Order matters: registerSession must run BEFORE session.open() so
        // the WebExtension MessageDelegate is attached when GeckoView's
        // WebExtension subsystem binds content scripts to the session.
        // That's how TabDelegate.onNewTab works — it returns an unopened
        // session and GeckoView opens it later, after delegates are wired.
        mainHandler.post(() -> {
            try {
                GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                        .usePrivateMode(false)
                        .suspendMediaWhenInactive(true)
                        .allowJavascript(true)
                        .build();
                GeckoSession s = new GeckoSession(settings);
                // 1) Attach delegates first — so content scripts get bound
                //    when GeckoView opens the session.
                sessionRegistrar.accept(s);
                // 2) Open the session — content scripts attach here.
                s.open(runtime);
                // 3) Mark active so the WebExtension API treats this as a
                //    live tab for content-script injection purposes.
                s.setActive(true);
                // 4) Stash session + timestamp so concurrent callers see the
                //    live session before content.js fires ready. Take the
                //    lock briefly — we're not blocking on anything here.
                synchronized (lock) {
                    session = s;
                    sessionCreatedAt = System.currentTimeMillis();
                }
                // 5) Finally, navigate.
                s.loadUri(ROBOTS_URL);
                Log.i(TAG, "createSession: session opened, awaiting content script ready");
            } catch (Exception e) {
                Log.e(TAG, "session create failed", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** Send a mint request over {@link #port} and block on the reply.
     *  {@code forceFresh} tells {@code content.js} to discard its cached
     *  {@code WebPoMinter} and re-run the full BotGuard attestation — see
     *  {@link #generateFresh}. */
    @NonNull
    private MintResult mint(@NonNull String videoId, @Nullable String visitorData,
                            boolean forceFresh) {
        // Capture port AND register pending atomically under the same lock
        // that onDisconnect / closeSession take. Otherwise there's a small
        // window where the disconnect sweep clears `pending` between our
        // port read and our pending.put, leaving the future orphaned and
        // forcing the full 15s timeout. Holding `lock` here means the
        // sweep either runs entirely before our registration (port is null,
        // fast fail) or entirely after (our future is in the snapshot and
        // gets failed exceptionally).
        final String requestId = "pot-" + System.nanoTime();
        final CompletableFuture<String> future = new CompletableFuture<>();
        final WebExtension.Port p;
        synchronized (lock) {
            if (port == null) {
                Log.w(TAG, "mint: no port");
                return new MintResult(null, false);
            }
            p = port;
            synchronized (pending) {
                pending.put(requestId, future);
            }
        }

        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "mint");
            msg.put("requestId", requestId);
            msg.put("videoId", videoId);
            msg.put("visitorData", visitorData != null ? visitorData : "");
            msg.put("forceFresh", forceFresh);
            // postMessage can be called from any thread — internally posts
            // to the Gecko main thread.
            p.postMessage(msg);
        } catch (JSONException e) {
            // Won't happen — all keys are static strings — but handle for
            // completeness so generate() always exits cleanly.
            Log.e(TAG, "mint: JSON build failed", e);
            synchronized (pending) {
                pending.remove(requestId);
            }
            return new MintResult(null, false);
        }

        // A forceFresh mint re-runs the whole attestation in the page and
        // needs the wider ceiling — see MINT_FRESH_TIMEOUT_MS.
        final long timeout = forceFresh ? MINT_FRESH_TIMEOUT_MS : MINT_TIMEOUT_MS;
        try {
            return new MintResult(future.get(timeout, TimeUnit.MILLISECONDS), false);
        } catch (TimeoutException e) {
            Log.w(TAG, "mint: timeout id=" + requestId + " after " + timeout + "ms");
            return new MintResult(null, true);
        } catch (ExecutionException e) {
            // The page answered, with a failure. It is alive — don't recycle.
            Log.w(TAG, "mint: failed id=" + requestId + " err=" + e.getCause());
            return new MintResult(null, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "mint: interrupted id=" + requestId);
            return new MintResult(null, false);
        } finally {
            synchronized (pending) {
                pending.remove(requestId);
            }
        }
    }

    /** Route an inbound port message to the right handler. */
    private void handlePortMessage(@NonNull JSONObject json) {
        String type = json.optString("type", "");
        switch (type) {
            case "ready":
                // Content script finished injecting bgutils + the BotGuard
                // runner and is ready to mint. Resolve the ready future so
                // createSessionLocked() can return.
                Log.i(TAG, "port: ready");
                synchronized (lock) {
                    if (readyFuture != null && !readyFuture.isDone()) {
                        readyFuture.complete(null);
                    }
                }
                break;
            case "mintResult": {
                String requestId = json.optString("requestId", "");
                Log.i(TAG, "port: mintResult id=" + requestId
                        + " token=" + json.optString("token", "").length() + " chars"
                        + " error=" + json.optString("error", ""));
                if (requestId.isEmpty()) {
                    Log.w(TAG, "mintResult missing requestId");
                    return;
                }
                CompletableFuture<String> f;
                synchronized (pending) {
                    f = pending.remove(requestId);
                }
                if (f == null) {
                    // Late reply — caller's get() already timed out and removed
                    // the entry. Nothing to do.
                    Log.d(TAG, "mintResult late or unknown id=" + requestId);
                    return;
                }
                String error = json.optString("error", "");
                if (!error.isEmpty()) {
                    f.completeExceptionally(new RuntimeException(error));
                } else {
                    String token = json.optString("token", "");
                    f.complete(token);
                }
                break;
            }
            default:
                Log.d(TAG, "unhandled port message type=" + type);
        }
    }

    /** Caller MUST hold {@link #lock}. */
    private void closeSessionLocked() {
        if (session != null) {
            final GeckoSession s = session;
            session = null;
            mainHandler.post(() -> {
                try {
                    s.close();
                } catch (Exception e) {
                    Log.w(TAG, "session.close failed", e);
                }
            });
        }
        sessionCreatedAt = 0L;
        port = null;
        // Token validity is backed by the BotGuard session/integrity token;
        // once the session is gone the cached tokens are dead. Drop them so
        // the next generate() mints fresh against the rebuilt session.
        tokenCache.clear();
        if (readyFuture != null && !readyFuture.isDone()) {
            readyFuture.completeExceptionally(new IllegalStateException("session closing"));
        }
        readyFuture = null;
        failAllPending("session closing");
    }

    /**
     * Fails any pending mints with the given reason. Manages its own
     * {@code pending} synchronization internally so callers can invoke it
     * with or without holding {@link #lock}.
     *
     * <p>Note: in current call sites we DO hold {@code lock} when calling
     * this (from {@link #onPortConnected} / port {@code onDisconnect} /
     * {@link #closeSessionLocked}). Future-waiters in {@code mint()} are
     * blocked on {@code future.get()} which doesn't require {@code lock},
     * so completing-while-locked is safe today. If anyone ever attaches a
     * {@code whenComplete} callback to these futures that re-takes
     * {@code lock}, this would deadlock — keep this in mind.</p>
     */
    private void failAllPending(@NonNull String reason) {
        List<CompletableFuture<String>> snapshot;
        synchronized (pending) {
            snapshot = new ArrayList<>(pending.values());
            pending.clear();
        }
        for (CompletableFuture<String> f : snapshot) {
            f.completeExceptionally(new IllegalStateException(reason));
        }
    }
}

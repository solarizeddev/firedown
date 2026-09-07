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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     *  <p>This is NOT a prediction of when a token expires — nobody can make
     *  that prediction. yt-dlp's own guide puts observed validity anywhere
     *  from ~12 hours to several months, and the only authority on whether a
     *  token still works is the server refusing it (SABR
     *  STREAM_PROTECTION_STATUS 3), which the attestation recovery reacts to.
     *  Nothing here needs to guess.</p>
     *
     *  <p>What this window actually buys is scope: minting happens at
     *  DOWNLOAD time (never at capture — {@code SabrStrategy} and
     *  {@code TimedTextStrategy} are the only callers), so the pairing the
     *  cache exists for is one download and its timedtext sibling, seconds
     *  apart. Ten minutes covers that generously while declining to reuse a
     *  token across unrelated downloads much later, where a warm re-mint
     *  costs ~100 ms and no network — cheaper than starting a download on a
     *  token that may already be dead and finding out a minute of media in.
     *  Being conservative here is nearly free; being wrong costs a wasted
     *  attempt.</p> */
    private static final long TOKEN_CACHE_TTL_MS = 10 * 60 * 1000;

    /** Hard ceiling on {@link #tokenCache} entries.
     *
     *  <p>The TTL above is enforced lazily, on a read of that same videoId —
     *  so a token for a video never asked about again is never examined and
     *  never removed. Browsing YouTube mints one per captured video, and the
     *  map only emptied when the session recycled hours later, so it grew for
     *  the whole session. Small (a few hundred bytes an entry) but unbounded,
     *  which is the part that matters. */
    private static final int MAX_CACHED_TOKENS = 64;

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

    /** Content binding of a minted token — the identifier BotGuard signs the
     *  token over, which the server checks against the request the token
     *  rides on. The two are NOT interchangeable, and a token with the wrong
     *  binding is exactly the shape of the shipped bug this enum exists for:
     *  it is well-formed, the server accepts it silently most of the time,
     *  and it is refused only when the server spot-checks the session — so
     *  a download dies at the attestation wall intermittently, and a fresh
     *  re-mint with the same wrong binding is refused again at once.
     *  <ul>
     *    <li>{@link #VIDEO}: bound to the video ID. What the player request
     *        and the timedtext (subtitles) request check.</li>
     *    <li>{@link #VISITOR}: bound to the visitor data. What the
     *        Google Video Server checks on {@code videoplayback} — the SABR
     *        stream's {@code poToken} (yt-dlp's GVS context; on the WEB
     *        client a logged-out session binds to visitor data).</li>
     *  </ul> */
    public enum Binding {
        VIDEO("video"),
        VISITOR("visitor");

        /** The value sent to {@code content.js} on the mint message. */
        final String wire;

        Binding(String wire) {
            this.wire = wire;
        }
    }

    /** cache key → minted token. The key is the binding identifier: the
     *  videoId for a {@link Binding#VIDEO} token, {@code "visitor:"} +
     *  visitorData for a {@link Binding#VISITOR} one — so a video-bound
     *  token can never be served to a stream request or vice versa, and a
     *  visitor-bound token IS shared across every video of the session (the
     *  server binds it to the visitor, not the clip). A video-bound token
     *  minted for video X is valid for any video-bound consumer of X (a
     *  subtitle download reuses the one an earlier timedtext minted). Cleared
     *  in {@link #closeSessionLocked} so a cached token can never outlive
     *  the BotGuard session that backs its validity. */
    @GuardedBy("lock") private final Map<String, CachedToken> tokenCache = new HashMap<>();

    /** Cache keys whose NEXT mint must run {@code forceFresh}, set by
     *  {@link #invalidate}/{@link #invalidateStream}. Dropping the cached
     *  token alone is not enough there: the page's minter ({@code cm} in
     *  {@code content.js}) is the one whose output the server refused, and a
     *  plain re-mint through it reproduces the refused bytes ~100 ms later
     *  (see {@link #generateFresh}). A retry after a refusal therefore pays
     *  one real attestation. Consumed on the first mint of that key. */
    @GuardedBy("lock") private final Set<String> forceFreshNext = new HashSet<>();

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
        /** The port this mint ran on — set only when {@code timedOut}, so the
         *  recycle in {@link #generate} can verify it is still the CURRENT
         *  port before tearing anything down. A timed-out caller only has
         *  standing to recycle the session it observed failing: between its
         *  {@code TimeoutException} and its lock acquisition, that session
         *  can die (port disconnect, a sibling's timeout) and a concurrent
         *  caller can build a healthy replacement — which an unguarded
         *  recycle would then destroy, failing the replacement's in-flight
         *  mints. Same defect class as the stale-port disconnect guard. */
        @Nullable final WebExtension.Port port;
        MintResult(@Nullable String token, boolean timedOut) {
            this(token, timedOut, null);
        }
        MintResult(@Nullable String token, boolean timedOut,
                   @Nullable WebExtension.Port port) {
            this.token = token;
            this.timedOut = timedOut;
            this.port = port;
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
     * Mint a PO token bound to the given VIDEO ({@link Binding#VIDEO}) — the
     * token the player request and the timedtext (subtitles) request check.
     * NOT the token a SABR / {@code videoplayback} request carries; that one
     * is {@link #generateForStream}. Blocking call — run from a background
     * thread (NOT the main thread, NOT a Gecko thread).
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
        return generate(Binding.VIDEO, videoId, visitorData, false);
    }

    /**
     * Mint the PO token a SABR / {@code videoplayback} request carries: bound
     * to the VISITOR DATA, not the video ({@link Binding#VISITOR}). This is
     * the token the Google Video Server validates, and it checks the binding
     * against the session's visitor — a video-bound token there is accepted
     * unchecked most of the time and refused the moment the server
     * spot-checks, which is how a download used to die at ~60 s with
     * {@code STREAM_PROTECTION_STATUS 3} after two immediate re-mint
     * refusals, then work fine on the next launch. Same blocking contract as
     * {@link #generate}. {@code videoId} is only carried for logging and
     * for the page to fall back on when visitorData is empty.
     */
    @Nullable
    public String generateForStream(@NonNull String videoId, @NonNull String visitorData) {
        return generate(Binding.VISITOR, videoId, visitorData, false);
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
        return generate(Binding.VIDEO, videoId, visitorData, true);
    }

    /** {@link #generateFresh} for the visitor-bound stream token — the
     *  mid-stream attestation recovery of a SABR download. */
    @Nullable
    public String generateFreshForStream(@NonNull String videoId, @NonNull String visitorData) {
        return generate(Binding.VISITOR, videoId, visitorData, true);
    }

    /** The {@link #tokenCache} key for one binding: the identifier the token
     *  is minted over, namespaced so the two bindings can never collide. */
    @NonNull
    private static String cacheKey(@NonNull Binding binding, @NonNull String videoId,
                                   @Nullable String visitorData) {
        if (binding == Binding.VISITOR) {
            return "visitor:" + (visitorData != null ? visitorData : "");
        }
        return videoId;
    }

    @Nullable
    private String generate(@NonNull Binding binding, @NonNull String videoId,
                            @Nullable String visitorData, boolean forceFreshRequested) {
        Log.i(TAG, "generate: binding=" + binding.wire + " videoId=" + videoId + " visitorData="
                + (visitorData != null ? visitorData.length() + " chars" : "null")
                + (forceFreshRequested ? " forceFresh" : ""));
        // A visitor-bound token minted over an EMPTY identifier is bound to
        // nothing the server can match; refuse to cache or mint one rather
        // than hand the download a token guaranteed to be refused.
        if (binding == Binding.VISITOR && TextUtils.isEmpty(visitorData)) {
            Log.w(TAG, "generate: stream token requested without visitorData, aborting");
            return null;
        }
        final String key = cacheKey(binding, videoId, visitorData);
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
        boolean forceFresh = forceFreshRequested;
        if (!TextUtils.isEmpty(key)) {
            synchronized (lock) {
                // A refusal reported through invalidate() outlives the
                // failed download: the first mint after it re-attests in
                // the page instead of re-minting through the minter that
                // produced the refused token. One-shot, consumed here.
                if (forceFreshNext.remove(key)) {
                    Log.i(TAG, "generate: " + key + " was invalidated — forcing a fresh attestation");
                    forceFresh = true;
                }
                if (forceFresh) {
                    // Evict BEFORE minting, not after: if the fresh mint
                    // fails we must not leave the rejected token behind for
                    // the next caller to pick up as a "hit".
                    if (tokenCache.remove(key) != null) {
                        Log.i(TAG, "generate: evicted rejected token for " + key);
                    }
                } else {
                    CachedToken cached = tokenCache.get(key);
                    if (cached != null) {
                        long age = System.currentTimeMillis() - cached.mintedAt;
                        if (age < TOKEN_CACHE_TTL_MS) {
                            Log.i(TAG, "generate: cache hit for " + key
                                    + " (" + cached.token.length() + " chars, age=" + age + "ms)");
                            return cached.token;
                        }
                        // Aged out — drop it rather than serve a token the
                        // server is likely to refuse (see TOKEN_CACHE_TTL_MS).
                        Log.i(TAG, "generate: cached token for " + key
                                + " expired (age=" + age + "ms) — re-minting");
                        tokenCache.remove(key);
                    }
                }
            }
        }

        // Step 3: send mint request, wait for reply. Both can happen
        // concurrently across callers because the port can multiplex via
        // per-request ids.
        MintResult result = mint(binding, videoId, visitorData, forceFresh);
        if (!TextUtils.isEmpty(result.token) && !TextUtils.isEmpty(key)) {
            synchronized (lock) {
                pruneTokenCacheLocked();
                tokenCache.put(key, new CachedToken(result.token, System.currentTimeMillis()));
            }
        }
        if (result.timedOut) {
            // The page never answered. Whatever wedged it (a dead BotGuard VM,
            // a navigated-away document, a broken event dispatcher) will still
            // be wedged on the next call, and ensureReady's liveness test —
            // session != null && port != null — cannot see any of it, so every
            // later mint would pay this same full timeout until the session
            // ages out hours from now. Recycle so the next caller rebuilds —
            // but ONLY if the port we minted on is still the current one.
            // Between our TimeoutException and this lock, the wedged session
            // can die on its own (port disconnect, a sibling's timeout) and a
            // concurrent caller can build a healthy replacement; an unguarded
            // close here destroyed that replacement and failed its in-flight
            // mints. We only have standing to recycle what we saw fail.
            synchronized (lock) {
                if (result.port != null && result.port == port) {
                    Log.w(TAG, "mint timed out — recycling session so the next attempt rebuilds");
                    closeSessionLocked();
                } else {
                    Log.w(TAG, "mint timed out on a superseded session — leaving the live one alone");
                }
            }
        }
        Log.i(TAG, "generate: result="
                + (result.token != null ? result.token.length() + " chars" : "null"));
        return result.token;
    }

    /**
     * Caller MUST hold {@link #lock}. Keeps {@link #tokenCache} bounded:
     * drop everything past its TTL (those are dead weight — a read would
     * re-mint anyway), and if that still leaves no room, evict the oldest.
     * Called before an insert, so the map is checked exactly when it grows.
     */
    private void pruneTokenCacheLocked() {
        final long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, CachedToken>> it = tokenCache.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().mintedAt >= TOKEN_CACHE_TTL_MS) {
                it.remove();
            }
        }
        while (tokenCache.size() >= MAX_CACHED_TOKENS) {
            String oldestKey = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, CachedToken> e : tokenCache.entrySet()) {
                if (e.getValue().mintedAt < oldest) {
                    oldest = e.getValue().mintedAt;
                    oldestKey = e.getKey();
                }
            }
            if (oldestKey == null) {
                break;
            }
            tokenCache.remove(oldestKey);
        }
    }

    /**
     * Drop the cached token for one video, so the next {@link #generate}
     * mints instead of serving it. For a caller that has learned the token
     * is bad in a way this class cannot see — the server refusing it — and
     * wants the knowledge to outlive the failed download. Idempotent; leaves
     * the session and every other video's token alone.
     */
    public void invalidate(@NonNull String videoId) {
        invalidateKey(videoId);
    }

    /** {@link #invalidate} for the visitor-bound stream token: the server
     *  refused it on {@code videoplayback}, so drop it AND make the next
     *  {@link #generateForStream} re-attest. Called by {@code SabrStrategy}
     *  when a download ends at the attestation wall, so the user's retry
     *  does not start from the very token that just failed. */
    public void invalidateStream(@NonNull String visitorData) {
        invalidateKey(cacheKey(Binding.VISITOR, "", visitorData));
    }

    private void invalidateKey(@NonNull String key) {
        synchronized (lock) {
            if (tokenCache.remove(key) != null) {
                Log.i(TAG, "invalidate: dropped cached token for " + key);
            }
            // Regardless of whether a cached entry existed: the token the
            // caller is complaining about may have been minted fresh (the
            // recovery path bypasses the cache) and is still the page
            // minter's output. The next mint must not go through it.
            forceFreshNext.add(key);
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
            // `s` and `opened` live outside the try so the failure path can
            // close a session we already opened. An open GeckoSession holds a
            // content process; one that nothing references can never be
            // closed by anyone, so it survives until the app dies.
            GeckoSession s = null;
            boolean opened = false;
            try {
                GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                        .usePrivateMode(false)
                        .suspendMediaWhenInactive(true)
                        .allowJavascript(true)
                        .build();
                s = new GeckoSession(settings);
                // 1) Attach delegates first — so content scripts get bound
                //    when GeckoView opens the session.
                sessionRegistrar.accept(s);
                // 2) Open the session — content scripts attach here.
                s.open(runtime);
                opened = true;
                // 3) Mark active so the WebExtension API treats this as a
                //    live tab for content-script injection purposes.
                s.setActive(true);
                // 4) Stash session + timestamp so concurrent callers see the
                //    live session before content.js fires ready. Take the
                //    lock briefly — we're not blocking on anything here.
                //
                //    Adopt ONLY if this creation is still the current one.
                //    This runnable is queued behind whatever else the main
                //    thread is doing, so ensureReady may have timed out and
                //    given up (clearing readyFuture), or shutdown may have
                //    run, or a later caller may have started its own session
                //    — and then nothing would ever hold or close this one.
                //    readyFuture identity is the test: it is set to `future`
                //    when this creation starts and replaced or nulled by any
                //    of those events.
                boolean abandoned;
                synchronized (lock) {
                    abandoned = (readyFuture != future);
                    if (!abandoned) {
                        session = s;
                        sessionCreatedAt = System.currentTimeMillis();
                    }
                }
                if (abandoned) {
                    Log.w(TAG, "createSession: abandoned while queued — closing the orphan");
                    s.close();
                    return;
                }
                // 5) Finally, navigate.
                s.loadUri(ROBOTS_URL);
                Log.i(TAG, "createSession: session opened, awaiting content script ready");
            } catch (Exception e) {
                Log.e(TAG, "session create failed", e);
                // An exception anywhere after open() (setActive, loadUri, the
                // registrar) leaves an OPEN session that was never stored, so
                // no later closeSessionLocked can reach it. Close it here or
                // it holds a content process for the life of the app.
                if (s != null && opened) {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                        // Already dying; nothing useful left to do.
                    }
                }
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
    private MintResult mint(@NonNull Binding binding, @NonNull String videoId,
                            @Nullable String visitorData, boolean forceFresh) {
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
            // Which identifier the page mints over — see Binding. Absent on
            // the wire before this field existed, and content.js treats an
            // unknown/missing value as the video binding, so an old page and
            // a new Java side still agree on the subtitles token.
            msg.put("binding", binding.wire);
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
        } catch (RuntimeException e) {
            // postMessage throws when Gecko considers the port dead. Without
            // this the exception escaped mint() past the finally below, so
            // the entry we just registered stayed in `pending` forever — a
            // leaked map entry AND a future no one will ever complete — and
            // the throw propagated out of generate() into the download.
            // A port that rejects a post is unusable, so drop the session
            // with it; ensureReady cannot tell it is dead on its own.
            Log.w(TAG, "mint: postMessage failed — dropping the dead session", e);
            synchronized (pending) {
                pending.remove(requestId);
            }
            synchronized (lock) {
                closeSessionLocked();
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
            return new MintResult(null, true, p);
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
        // A rebuilt session attests from scratch anyway, so a pending
        // force-fresh mark has nothing left to force.
        forceFreshNext.clear();
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

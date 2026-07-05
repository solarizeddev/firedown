package com.solarized.firedown.nostr;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.solarized.firedown.BuildConfig;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * Native half of the {@code window.nostr} (NIP-07) provider. Receives requests
 * from the {@code nostr@solarized.dev} content script over the
 * {@code sendNativeMessage("nostr", …)} channel (routed here from
 * {@code GeckoRuntimeHelper.MessageDelegate.onMessage}), delegates the actual
 * signing to a NIP-55 signer app (Amber) via {@link NostrSignerActivity}, and
 * completes the JS Promise with the result.
 *
 * <h3>Why an intent, not a page redirect</h3>
 * Amber only returns its result IN-PROCESS ({@code setResult(RESULT_OK, …)})
 * when launched with {@code startActivityForResult}. Launching it as a page
 * navigation to {@code nostrsigner:…} instead would (a) be swallowed by
 * Firedown's "Block app redirects" toggle (default ON — it silently blocks
 * page-initiated non-http schemes) and (b) force Amber down its clipboard /
 * callbackUrl fallback. So the whole flow is native intents; the page never
 * navigates.
 *
 * <h3>Contract with the JS side</h3>
 * Every request resolves (never rejects) the returned {@link GeckoResult} with
 * a JSON ENVELOPE STRING — {@code {"ok":true,"data":…}} or
 * {@code {"ok":false,"error":"…"}}. Primitives/Strings serialize cleanly across
 * the GeckoView message boundary (a JSONObject return does not — see the
 * get-debug-flag note in GeckoRuntimeHelper); the content script translates the
 * envelope into resolve/reject. {@code data} is a hex string (getPublicKey /
 * nip*), a plaintext/ciphertext string, or the signed-event object (signEvent).
 *
 * <h3>Serialization</h3>
 * Requests run ONE AT A TIME (Amber is a foreground activity): a FIFO queue
 * feeds a single {@link NostrSignerActivity} launch at a time, so two rapid
 * signEvent calls resolve in order.
 */
@Singleton
public class NostrSignerBridge {

    private static final String TAG = "NostrSigner";

    /** The only NIP-55 signer supported for now. Read from here (not inlined)
     *  so a signer-picker can be added later without touching intent building. */
    public static final String SIGNER_PACKAGE = "com.greenart7c3.nostrsigner";

    private static final String PREFS = "nostr_prefs";
    private static final String KEY_PUBKEY = "pubkey";
    private static final String PERM_PREFIX = "perm:";

    private final Context mAppContext;
    private final SharedPreferences mPrefs;

    private final Object mLock = new Object();
    private final Deque<NostrRequest> mQueue = new ArrayDeque<>();
    private final Map<Long, NostrRequest> mInFlight = new HashMap<>();
    private long mSeq = 0;
    private boolean mActivityActive = false;

    @Inject
    public NostrSignerBridge(@ApplicationContext Context context) {
        mAppContext = context;
        mPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Entry point from the message delegate. Validates the request, enqueues
     * it, and returns the pending result. Always completes (never rejects) the
     * result with an envelope string.
     */
    public GeckoResult<Object> handle(JSONObject msg, @Nullable String senderUrl) {
        GeckoResult<Object> result = new GeckoResult<>();
        String type = msg.optString("type", "");
        if (!isSupportedType(type)) {
            result.complete(error("unsupported request"));
            return result;
        }
        String origin = originOf(senderUrl);
        if (origin == null) {
            result.complete(error("no origin"));
            return result;
        }

        NostrRequest req;
        synchronized (mLock) {
            req = new NostrRequest(++mSeq, type, origin,
                    msg.optString("eventJson", null),
                    emptyToNull(msg.optString("pubkey", null)),
                    // plaintext (encrypt) or ciphertext (decrypt) share one slot
                    firstNonNull(emptyToNull(msg.optString("plaintext", null)),
                            emptyToNull(msg.optString("ciphertext", null))),
                    result);
            mInFlight.put(req.key, req);
            mQueue.addLast(req);
        }
        pump();
        return result;
    }

    /** Launches the next queued request if no signer activity is running. */
    private void pump() {
        NostrRequest next;
        synchronized (mLock) {
            if (mActivityActive) {
                return;
            }
            next = mQueue.peekFirst();
            if (next == null) {
                return;
            }
            mActivityActive = true;
        }
        try {
            Intent intent = new Intent(mAppContext, NostrSignerActivity.class);
            intent.putExtra(NostrSignerActivity.EXTRA_KEY, next.key);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mAppContext.startActivity(intent);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Failed to launch signer activity", e);
            }
            deliver(next.key, error("signer UI unavailable"));
        }
    }

    // --- Called by NostrSignerActivity ---

    @Nullable
    NostrRequest getRequest(long key) {
        synchronized (mLock) {
            return mInFlight.get(key);
        }
    }

    /**
     * Completes the request's JS Promise with the given envelope string, drops
     * it from the queue, and starts the next one. Idempotent per key.
     */
    void deliver(long key, String envelopeJson) {
        NostrRequest req;
        synchronized (mLock) {
            req = mInFlight.remove(key);
            if (req != null) {
                mQueue.remove(req);
            }
            mActivityActive = false;
        }
        if (req != null) {
            req.result.complete(envelopeJson);
        }
        pump();
    }

    // --- Per-origin permission + cached pubkey ---

    /** null = never asked, TRUE = allowed, FALSE = denied (remembered). */
    @Nullable
    Boolean getPermission(String origin) {
        String key = PERM_PREFIX + origin;
        if (!mPrefs.contains(key)) {
            return null;
        }
        return mPrefs.getBoolean(key, false);
    }

    void setPermission(String origin, boolean allowed) {
        mPrefs.edit().putBoolean(PERM_PREFIX + origin, allowed).apply();
    }

    @Nullable
    String getCachedPubkey() {
        return emptyToNull(mPrefs.getString(KEY_PUBKEY, null));
    }

    void setCachedPubkey(String hexPubkey) {
        mPrefs.edit().putString(KEY_PUBKEY, hexPubkey).apply();
    }

    // --- Envelope helpers (shared with the activity) ---

    static String okString(String data) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("data", data);
            return o.toString();
        } catch (JSONException e) {
            return error("internal error");
        }
    }

    static String okObject(JSONObject data) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("data", data);
            return o.toString();
        } catch (JSONException e) {
            return error("internal error");
        }
    }

    static String error(String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", message);
            return o.toString();
        } catch (JSONException e) {
            // Last-resort hand-built envelope; message is our own constant text.
            return "{\"ok\":false,\"error\":\"internal error\"}";
        }
    }

    // --- Helpers ---

    private static boolean isSupportedType(String type) {
        switch (type) {
            case "get_public_key":
            case "sign_event":
            case "nip04_encrypt":
            case "nip04_decrypt":
            case "nip44_encrypt":
            case "nip44_decrypt":
                return true;
            default:
                return false;
        }
    }

    /** scheme://host[:port] of the sender page, or null if not http(s). */
    @Nullable
    private static String originOf(@Nullable String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null
                || (!"http".equals(scheme) && !"https".equals(scheme))) {
            return null;
        }
        int port = uri.getPort();
        return port >= 0 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
    }

    @Nullable
    private static String emptyToNull(@Nullable String s) {
        return TextUtils.isEmpty(s) ? null : s;
    }

    @Nullable
    private static String firstNonNull(@Nullable String a, @Nullable String b) {
        return a != null ? a : b;
    }

    /** One queued signing request + the JS Promise waiting on it. */
    static final class NostrRequest {
        final long key;
        final String type;
        final String origin;
        @Nullable final String eventJson;   // sign_event
        @Nullable final String pubkey;      // nip04/nip44 counterparty (hex)
        @Nullable final String payload;     // plaintext (encrypt) / ciphertext (decrypt)
        final GeckoResult<Object> result;

        NostrRequest(long key, String type, String origin,
                     @Nullable String eventJson, @Nullable String pubkey,
                     @Nullable String payload, GeckoResult<Object> result) {
            this.key = key;
            this.type = type;
            this.origin = origin;
            this.eventJson = eventJson;
            this.pubkey = pubkey;
            this.payload = payload;
            this.result = result;
        }
    }
}

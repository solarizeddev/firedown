package com.solarized.firedown.p2pshare;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.BuildConfig;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Talks to the optional Firedown signaling relay (see {@code signaling/server.js})
 * so a shared link works one-way across networks: the sender uploads its offer
 * and long-polls for the receiver's answer; the receiver fetches the offer and
 * posts its answer back. The relay only ever brokers those two ~1&nbsp;KB blobs —
 * never the file, which stays peer-to-peer and end-to-end encrypted.
 *
 * <p>Serverless by default: this is used ONLY when the user configured a relay
 * URL AND the same-network LAN return didn't already carry the answer. All calls
 * run on OkHttp's async pool; results are handed back on that pool, so callers
 * hop to the main thread themselves.
 *
 * <p>One instance per share session; {@link #cancel()} stops any in-flight
 * long-poll (called from the controller's teardown).
 */
final class P2pSignalingClient {

    private static final String TAG = "P2pSignaling";

    private static final MediaType TEXT = MediaType.parse("text/plain; charset=utf-8");
    /**
     * Stop long-polling after this. Mirrors the server's OFFER mailbox TTL
     * (firedown-api {@code offerTTL} = 15 min): the shared link serves a full,
     * convincing offer for that whole window, so the sender must be listening
     * for an answer for the whole window too — with the old 150 s deadline, a
     * link opened minutes after sharing previewed fine but the automatic
     * Accept→connect path was already dead even with the sender sitting on the
     * share screen (the link advertised a validity its own return path didn't
     * honour). The previous comment blamed a "~3 min relay TTL" that does not
     * exist: the server's {@code waiter()} CREATES (or re-creates an expired)
     * mailbox entry on every poll and parks 25 s, so there is no server-side
     * poll cutoff — the answer store's 5-min TTL only bites an answer nobody
     * collects, and a live long-poll is woken instantly on delivery. Verified
     * against firedown-api {@code handler_rendezvous.go}. Cost: one parked
     * ~25 s request at a time while the share screen is open; {@link #cancel()}
     * still ends it with the screen.
     */
    private static final long POLL_DEADLINE_MS = 15 * 60 * 1000;
    /** Backoff after a transient poll failure before retrying. */
    private static final long POLL_RETRY_MS = 1_500;

    private final OkHttpClient mClient;
    private volatile boolean mStopped;
    private volatile Call mActiveCall;

    P2pSignalingClient(@NonNull OkHttpClient base) {
        // The answer long-poll parks ~25 s server-side; give reads headroom.
        mClient = base.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS)
                .build();
    }

    /** A fresh 128-bit rendezvous id (base64url), minted by the sender. */
    @NonNull
    static String mintId() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(22);
        for (byte b : bytes) {
            sb.append(B64URL.charAt((b >> 2) & 0x3f)); // 6 bits/char is fine for an id;
            sb.append(B64URL.charAt(b & 0x0f));         // 12 chars, url-safe, plenty unique
        }
        return sb.toString();
    }

    private static final String B64URL =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    interface Result {
        /** {@code value} is the body on success, or null on failure/expiry. */
        void done(@Nullable String value);
    }

    /** Sender: PUT the offer under {@code id}. */
    void uploadOffer(@NonNull String base, @NonNull String id, @NonNull String offer,
                     @NonNull Result cb) {
        post(base + "/o/" + id, offer, cb);
    }

    /** Receiver: fetch the offer for {@code id} (null if not there / expired). */
    void fetchOffer(@NonNull String base, @NonNull String id, @NonNull Result cb) {
        Request request = new Request.Builder().url(base + "/o/" + id).get().build();
        enqueue(request, cb, false);
    }

    /** Receiver: POST the answer under {@code id}. */
    void postAnswer(@NonNull String base, @NonNull String id, @NonNull String answer,
                    @NonNull Result cb) {
        post(base + "/a/" + id, answer, cb);
    }

    /**
     * Sender: long-poll for the answer under {@code id} until it arrives, the
     * deadline passes, or {@link #cancel()} is called. {@code cb} fires exactly
     * once — with the answer, or null if it never came.
     */
    void pollAnswer(@NonNull String base, @NonNull String id, @NonNull Result cb) {
        pollAnswerUntil(base, id, System.currentTimeMillis() + POLL_DEADLINE_MS, cb);
    }

    private void pollAnswerUntil(String base, String id, long deadline, Result cb) {
        if (mStopped || System.currentTimeMillis() >= deadline) {
            cb.done(null);
            return;
        }
        Request request = new Request.Builder().url(base + "/a/" + id + "?wait=1").get().build();
        Call call = mClient.newCall(request);
        mActiveCall = call;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call c, @NonNull IOException e) {
                if (mStopped) { cb.done(null); return; }
                // Transient (timeout / blip) — back off and keep waiting.
                retry(base, id, deadline, cb);
            }

            @Override
            public void onResponse(@NonNull Call c, @NonNull Response response) {
                try (Response r = response) {
                    int code = r.code();
                    if (code == 200) {
                        String body = r.body() != null ? r.body().string().trim() : "";
                        cb.done(body.isEmpty() ? null : body);
                    } else if (code == 204) {
                        // Server long-poll timed out with no answer yet — re-poll.
                        pollAnswerUntil(base, id, deadline, cb);
                    } else {
                        // The server only sends 200/204/400/503 here (its
                        // waiter() re-creates expired entries, so there is no
                        // "session gone" response). Anything else is a proxy
                        // or a different server — give up defensively.
                        cb.done(null);
                    }
                } catch (IOException e) {
                    if (mStopped) { cb.done(null); } else { retry(base, id, deadline, cb); }
                }
            }
        });
    }

    private void retry(String base, String id, long deadline, Result cb) {
        try {
            Thread.sleep(POLL_RETRY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cb.done(null);
            return;
        }
        pollAnswerUntil(base, id, deadline, cb);
    }

    private void post(String url, String body, Result cb) {
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, TEXT))
                .build();
        enqueue(request, cb, true);
    }

    private void enqueue(Request request, Result cb, boolean statusOnly) {
        mClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call c, @NonNull IOException e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "call failed: " + e.getMessage());
                }
                cb.done(null);
            }

            @Override
            public void onResponse(@NonNull Call c, @NonNull Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful()) {
                        cb.done(null);
                    } else if (statusOnly) {
                        cb.done("ok");
                    } else {
                        String body = r.body() != null ? r.body().string().trim() : "";
                        cb.done(body.isEmpty() ? null : body);
                    }
                } catch (IOException e) {
                    cb.done(null);
                }
            }
        });
    }

    /** Stop the long-poll and any in-flight call. Idempotent. */
    void cancel() {
        mStopped = true;
        Call call = mActiveCall;
        if (call != null) {
            call.cancel();
        }
    }
}

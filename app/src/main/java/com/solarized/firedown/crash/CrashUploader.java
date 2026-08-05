package com.solarized.firedown.crash;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import androidx.annotation.NonNull;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.sync.crypto.Pow;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * One-tap crash send: POSTs a {@link CrashReport}'s JSON — the exact bytes
 * already stored under {@code filesDir/crashes/} — to the api's
 * {@code /v1/crash} collector. Anonymous by construction: the request carries
 * no account, no headers beyond OkHttp's defaults, and the server dedups by a
 * trace signature it computes itself. This is the alternative to "log in to
 * GitHub and paste"; the Report/Copy actions on the sheet remain for users who
 * prefer the public tracker.
 *
 * <p>The send is HASHCASH-GATED (the P2P relay-creds pattern, same {@link Pow}
 * solver the sync registration uses): fetch {@code /v1/crash/challenge}, solve
 * on the OkHttp callback thread (off main, where the CPU work belongs — a few
 * tens of ms at the server's base difficulty), and carry challenge+nonce in
 * the POST body. That makes spamming the collector cost CPU per report; a
 * 404 on the challenge means the server runs with the gate disabled and the
 * POST goes bare.</p>
 */
public final class CrashUploader {

    private static final String ENDPOINT =
            Preferences.SYNC_DEFAULT_BACKEND + "/v1/crash";
    private static final String CHALLENGE_ENDPOINT =
            Preferences.SYNC_DEFAULT_BACKEND + "/v1/crash/challenge";
    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");

    /** Must match the server's {@code crashPoWResource} byte-for-byte. */
    private static final byte[] POW_RESOURCE =
            "crash".getBytes(StandardCharsets.US_ASCII);

    /**
     * Refuse to solve a PoW harder than this — the base is 16 bits and the
     * adaptive ceiling is base+8; a value beyond that is a spoofed/hostile
     * response, so fail the send rather than spin the CPU (the
     * {@code RELAY_POW_MAX_BITS} rule).
     */
    private static final int POW_MAX_BITS = 26;

    private CrashUploader() {
    }

    /**
     * Sends the report; {@code callback} runs on the MAIN thread with
     * {@code true} only for a 2xx from the collector. Any serialization,
     * transport, or server failure is {@code false} — the caller keeps the
     * sheet up so Copy/Report stay available as the fallback.
     */
    public static void send(@NonNull OkHttpClient client,
                            @NonNull CrashReport report,
                            @NonNull Consumer<Boolean> callback) {
        Handler main = new Handler(Looper.getMainLooper());
        Request challengeRequest = new Request.Builder()
                .url(CHALLENGE_ENDPOINT)
                .get()
                .build();
        client.newCall(challengeRequest).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                main.post(() -> callback.accept(false));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (response.code() == 404) {
                    // Gate disabled server-side — the POST goes bare.
                    response.close();
                    postReport(client, report, null, null, main, callback);
                    return;
                }
                String challengeB64 = null;
                int bits = 0;
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        JSONObject body = new JSONObject(response.body().string());
                        challengeB64 = body.optString("challenge", "");
                        bits = body.optInt("pow_bits", 0);
                    }
                } catch (IOException | JSONException e) {
                    challengeB64 = null;
                } finally {
                    response.close();
                }
                if (challengeB64 == null || challengeB64.isEmpty()
                        || bits <= 0 || bits > POW_MAX_BITS) {
                    main.post(() -> callback.accept(false));
                    return;
                }
                // Solve here, on the OkHttp callback thread — off main.
                String nonceB64;
                try {
                    byte[] challenge = Base64.decode(challengeB64,
                            Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                    byte[] nonce = Pow.solve(POW_RESOURCE, challenge, bits);
                    nonceB64 = Base64.encodeToString(nonce,
                            Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                } catch (IllegalArgumentException e) {
                    main.post(() -> callback.accept(false));
                    return;
                }
                postReport(client, report, challengeB64, nonceB64, main, callback);
            }
        });
    }

    private static void postReport(@NonNull OkHttpClient client,
                                   @NonNull CrashReport report,
                                   String challengeB64, String nonceB64,
                                   @NonNull Handler main,
                                   @NonNull Consumer<Boolean> callback) {
        String body;
        try {
            JSONObject json = report.toJson();
            if (challengeB64 != null && nonceB64 != null) {
                json.put("challenge", challengeB64);
                json.put("nonce", nonceB64);
            }
            body = json.toString();
        } catch (JSONException e) {
            main.post(() -> callback.accept(false));
            return;
        }
        Request request = new Request.Builder()
                .url(ENDPOINT)
                .post(RequestBody.create(body, JSON))
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                main.post(() -> callback.accept(false));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                boolean ok = response.isSuccessful();
                response.close();
                main.post(() -> callback.accept(ok));
            }
        });
    }
}

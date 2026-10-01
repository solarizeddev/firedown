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
 * The anonymous, HASHCASH-GATED POST shared by the crash send
 * ({@link CrashUploader}) and the Settings "Send feedback" dialog: fetch
 * {@code <path>/challenge}, solve on the OkHttp callback thread (off main,
 * where the CPU work belongs — tens of ms at the server's base difficulty),
 * and carry challenge+nonce in the JSON body. A 404 on the challenge means the
 * server runs with the gate disabled and the POST goes bare. No account, no
 * headers beyond OkHttp's defaults.
 *
 * <p>One implementation on purpose: the two sends differ only by path, PoW
 * resource and body, and a second copy of this flow is the drift this
 * codebase keeps recording.</p>
 */
public final class AnonymousPost {

    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");

    /**
     * Refuse to solve a PoW harder than this — the server base is 16 bits and
     * the adaptive ceiling base+8; a value beyond that is a spoofed/hostile
     * response, so fail the send rather than spin the CPU (the
     * {@code RELAY_POW_MAX_BITS} rule).
     */
    private static final int POW_MAX_BITS = 26;

    private AnonymousPost() {
    }

    /**
     * POSTs {@code body} to {@code SYNC_DEFAULT_BACKEND + path}; {@code
     * callback} runs on the MAIN thread with {@code true} only for a 2xx.
     *
     * @param resource the server's PoW resource for this route, byte-for-byte
     *                 ({@code "crash"}, {@code "feedback"})
     */
    public static void send(@NonNull OkHttpClient client,
                            @NonNull String path,
                            @NonNull String resource,
                            @NonNull JSONObject body,
                            @NonNull Consumer<Boolean> callback) {
        Handler main = new Handler(Looper.getMainLooper());
        String endpoint = Preferences.SYNC_DEFAULT_BACKEND + path;
        byte[] powResource = resource.getBytes(StandardCharsets.US_ASCII);
        Request challengeRequest = new Request.Builder()
                .url(endpoint + "/challenge")
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
                    post(client, endpoint, body, main, callback);
                    return;
                }
                String challengeB64 = null;
                int bits = 0;
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        JSONObject json = new JSONObject(response.body().string());
                        challengeB64 = json.optString("challenge", "");
                        bits = json.optInt("pow_bits", 0);
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
                try {
                    byte[] challenge = Base64.decode(challengeB64,
                            Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                    byte[] nonce = Pow.solve(powResource, challenge, bits);
                    body.put("challenge", challengeB64);
                    body.put("nonce", Base64.encodeToString(nonce,
                            Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP));
                } catch (IllegalArgumentException | JSONException e) {
                    main.post(() -> callback.accept(false));
                    return;
                }
                post(client, endpoint, body, main, callback);
            }
        });
    }

    private static void post(@NonNull OkHttpClient client,
                             @NonNull String endpoint,
                             @NonNull JSONObject body,
                             @NonNull Handler main,
                             @NonNull Consumer<Boolean> callback) {
        Request request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(body.toString(), JSON))
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

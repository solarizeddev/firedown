package com.solarized.firedown.crash;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.solarized.firedown.Preferences;

import org.json.JSONException;

import java.io.IOException;
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
 */
public final class CrashUploader {

    private static final String ENDPOINT =
            Preferences.SYNC_DEFAULT_BACKEND + "/v1/crash";
    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");

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
        String body;
        try {
            body = report.toJson().toString();
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

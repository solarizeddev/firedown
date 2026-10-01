package com.solarized.firedown.crash;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.function.Consumer;

import okhttp3.OkHttpClient;

/**
 * One-tap crash send: POSTs a {@link CrashReport}'s JSON — the exact bytes
 * already stored under {@code filesDir/crashes/} — to the api's
 * {@code /v1/crash} collector. Anonymous by construction: the request carries
 * no account, no headers beyond OkHttp's defaults, and the server dedups by a
 * trace signature it computes itself. This is the alternative to "log in to
 * GitHub and paste"; the Report/Copy actions on the sheet remain for users who
 * prefer the public tracker.
 *
 * <p>The send is HASHCASH-GATED (the P2P relay-creds pattern, same
 * {@code Pow} solver the sync registration uses) through {@link
 * AnonymousPost}, the flow it shares with the Settings feedback dialog.</p>
 */
public final class CrashUploader {

    private static final String PATH = "/v1/crash";

    /** Must match the server's {@code crashPoWResource} byte-for-byte. */
    private static final String POW_RESOURCE = "crash";

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
        JSONObject json;
        try {
            json = report.toJson();
        } catch (JSONException e) {
            new Handler(Looper.getMainLooper()).post(() -> callback.accept(false));
            return;
        }
        AnonymousPost.send(client, PATH, POW_RESOURCE, json, callback);
    }
}

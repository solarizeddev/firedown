package com.solarized.firedown.donate;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Manages the on-chain Bitcoin address shown on the donate screen.
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>App opens donate screen → {@link #getCachedAddress()} returns
 *       immediately. Either the previously-cached address, or the
 *       hardcoded {@link #FALLBACK_ADDRESS} on first launch before any
 *       network call has succeeded.</li>
 *   <li>If no real address has ever been fetched, a background fetch
 *       starts via {@link #fetchIfNeeded()}.</li>
 *   <li>User taps Copy or Open in Wallet → the screen calls
 *       {@link #onAddressUsed()}, which kicks off a background fetch
 *       for the next address. The new address lands silently in
 *       SharedPreferences and is shown the *next* time the screen
 *       opens — not the current visit. This keeps the UI stable and
 *       guarantees the user copies the address they actually saw.</li>
 * </ol>
 *
 * <p>Network failures are silent. A 404, timeout, or DNS error just
 * leaves the cached address in place — donations still work, the user
 * just doesn't get rotation that visit.</p>
 *
 * <p>The fallback address is the address derived at index 0 of the same
 * zpub the server uses — a known-good baseline that's always valid
 * even if the API is unreachable. Replace the constant before shipping.</p>
 */
public class BitcoinAddressProvider {

    private static final String TAG = "BitcoinAddressProvider";

    private static final String API_URL =
            "https://firedown.app/api/btc-address";

    /**
     * Hardcoded fallback used only when (a) it's the first launch and
     * the API call hasn't completed, or (b) every API call has failed.
     * Should be the address at index 0 of the same zpub the server
     * derives from.
     */
    public static final String FALLBACK_ADDRESS =
            "bc1qt2ndpfrghqek3l5ze9nqsz3wva9mmpseyleee7";

    private static final String PREFS_NAME      = "firedown_btc_address";
    private static final String KEY_ADDRESS     = "address";
    private static final String KEY_INDEX       = "index";
    private static final String KEY_HAS_REAL    = "has_real";  // false until first successful fetch

    private final SharedPreferences mPrefs;
    private final OkHttpClient   mClient;
    private final AtomicBoolean  mFetchInFlight = new AtomicBoolean(false);

    public BitcoinAddressProvider(@NonNull Context context, OkHttpClient client) {
        Context mAppContext = context.getApplicationContext();
        mClient = client;
        mPrefs = mAppContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * @return the most recently fetched address, or {@link #FALLBACK_ADDRESS}
     *         if nothing has ever been fetched. Safe to call from the
     *         main thread.
     */
    @NonNull
    public String getCachedAddress() {
        return mPrefs.getString(KEY_ADDRESS, FALLBACK_ADDRESS);
    }

    /**
     * @return true if {@link #getCachedAddress()} would return a real,
     *         server-derived address (vs. the hardcoded fallback). Useful
     *         if you want to show a tiny "syncing…" badge on first launch.
     */
    public boolean hasRealAddress() {
        return mPrefs.getBoolean(KEY_HAS_REAL, false);
    }

    /**
     * Trigger a background fetch only if we've never successfully fetched
     * before. Idempotent and safe to call on every screen open.
     */
    @MainThread
    public void fetchIfNeeded() {
        if (!hasRealAddress()) fetchNext();
    }

    /**
     * Called when the user actually used the address (tapped Copy or
     * Open in Wallet). Kicks off a background fetch for the *next*
     * address. The new address replaces the cached value silently —
     * the current visit keeps showing what the user saw.
     */
    @MainThread
    public void onAddressUsed() {
        fetchNext();
    }

    // ─────────────────────────────────────────────────────────────────

    private void fetchNext() {
        if (!mFetchInFlight.compareAndSet(false, true)) {
            // Already a fetch in flight — don't pile up requests.
            return;
        }

        Request req = new Request.Builder()
                .url(API_URL)
                .header("Accept", "application/json")
                .build();

        mClient.newCall(req).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.d(TAG, "BTC address fetch failed (silent)", e);
                mFetchInFlight.set(false);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful() || body == null) {
                        Log.e(TAG, "BTC address fetch HTTP " + response.code());
                        return;
                    }
                    JSONObject json = new JSONObject(body.string());
                    String address = json.optString("address", null);
                    int index = json.optInt("index", -1);
                    if (address == null || address.isEmpty() || !address.startsWith("bc1")) {
                        Log.e(TAG, "Bad address in response: " + address);
                        return;
                    }
                    mPrefs.edit()
                            .putString(KEY_ADDRESS, address)
                            .putInt(KEY_INDEX, index)
                            .putBoolean(KEY_HAS_REAL, true)
                            .apply();
                    Log.d(TAG, "Cached new BTC address (index " + index + ")");
                } catch (Exception e) {
                    Log.e(TAG, "BTC address parse failed", e);
                } finally {
                    mFetchInFlight.set(false);
                }
            }
        });
    }
}
package com.solarized.firedown.donate;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
 * <p>The server is gap-free / mempool-gated: it serves the same address
 * to every caller until mempool.space sees a tx funding it, then
 * advances by one. So fetching is essentially "pick up whatever the
 * server says is current right now" — clients call {@link #refresh()}
 * on every screen open and any rotation the server has already
 * performed becomes visible to the user without requiring them to
 * tap Copy first.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link #getCachedAddress()} returns immediately — either the
 *       previously-cached address, or the hardcoded
 *       {@link #FALLBACK_ADDRESS} on first launch before any network
 *       call has succeeded.</li>
 *   <li>{@link #refresh()} kicks a background fetch (coalesced by the
 *       in-flight guard). The new address lands silently in
 *       SharedPreferences and, if a listener is registered, fires on
 *       the main thread so the UI can update mid-screen. Without the
 *       listener, the first-launch user would see the fallback the
 *       entire visit and the real address only on their next visit —
 *       which means every "first donation per install" would otherwise
 *       go to the shared fallback address. The listener closes that
 *       window.</li>
 * </ol>
 *
 * <p>Network failures are silent. A 404, timeout, or DNS error just
 * leaves the cached address in place — donations still work, the user
 * just doesn't get a fresh address that visit.</p>
 *
 * <p>The fallback address is the address derived at index 0 of the
 * same zpub the server uses — a known-good baseline that's always
 * valid even if the API is unreachable. Replace the constant before
 * shipping.</p>
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
    private final Handler        mMainHandler   = new Handler(Looper.getMainLooper());

    /** Main-thread-only. Mutated by {@link #setListener} from the UI thread
     *  and read by a main-thread {@link Handler#post} after a successful
     *  fetch, so no synchronization is needed. */
    @Nullable private AddressListener mListener;

    /**
     * Optional observer for fetched addresses. Fires on the main thread
     * after a successful fetch lands in SharedPreferences. Used by the
     * donate screen to refresh the displayed address + QR mid-visit
     * instead of waiting for the next fragment recreation.
     */
    public interface AddressListener {
        /**
         * Called on the main thread when a fresh address has been
         * fetched and cached. May fire with the same value as the
         * previous fetch if the server hasn't rotated yet — consumers
         * should compare before redrawing.
         */
        void onAddressFetched(@NonNull String address);
    }

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
     * Register a callback fired on the main thread when a fetch lands.
     * Pass {@code null} to clear. The provider holds a strong reference,
     * so a fragment / activity that registers MUST clear in
     * {@code onDestroyView} (or equivalent) to avoid leaking.
     */
    @MainThread
    public void setListener(@Nullable AddressListener listener) {
        mListener = listener;
    }

    /**
     * Kick a background fetch. Safe to call on every donate-screen
     * open — coalesced internally by {@link #mFetchInFlight}. Pairs
     * with the mempool-gated server: each visit picks up whatever
     * rotation the server has already performed, so the user doesn't
     * need a "double-use" (copy → reopen → copy) to see a new
     * address after a previous donation has been mined.
     */
    @MainThread
    public void refresh() {
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
                    // Notify any UI observer on the main thread. Read
                    // mListener at dispatch time so a listener cleared
                    // between fetch-completion and post-dispatch (e.g.
                    // fragment destroyed mid-flight) is a no-op rather
                    // than a NPE or a leak.
                    final String fetchedAddress = address;
                    mMainHandler.post(() -> {
                        AddressListener l = mListener;
                        if (l != null) l.onAddressFetched(fetchedAddress);
                    });
                } catch (Exception e) {
                    Log.e(TAG, "BTC address parse failed", e);
                } finally {
                    mFetchInFlight.set(false);
                }
            }
        });
    }
}
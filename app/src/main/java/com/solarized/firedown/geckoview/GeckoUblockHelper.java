package com.solarized.firedown.geckoview;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import dagger.hilt.android.qualifiers.ApplicationContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Helper class to manage uBlock Origin state and communication.
 * Refactored to use Hilt and LiveData for reactive UI updates.
 */
@Singleton
public class GeckoUblockHelper {

    private static final String TAG = GeckoUblockHelper.class.getSimpleName();
    // LiveData streams for the UI to observe.
    //
    // Ads-blocked count is per-mode so the incognito toolbar never
    // reflects counts from regular browsing and vice versa. Callers
    // that know the session's incognito-ness should call
    // onAdsCount(count, isIncognito); the no-arg overload exists for
    // legacy callers and routes to the regular stream.
    private final MutableLiveData<String> mAdsBlockedLive = new MutableLiveData<>("0");
    private final MutableLiveData<String> mAdsBlockedLiveIncognito = new MutableLiveData<>("0");
    /** Cumulative blocked requests since uBlock was installed.
     *  Source of truth lives in {@code µb.requestStats.blockedCount}
     *  inside the extension — firedown.js pushes the value over the
     *  native message bus on load + every firewall update + a coarse
     *  60s interval. Reads here are just a relay. Incognito sessions
     *  don't contribute (uBlock excludes them from its persisted
     *  stats). */
    private final MutableLiveData<Long> mCumulativeBlockedLive = new MutableLiveData<>(0L);
    /** Blocked count since the start of the current calendar day.
     *  Derived from {@code cumulative - baselineAtMidnight}; baseline
     *  rolls forward on the first push of a new day. Gives the info
     *  sheet a 'X today, Y since install' framing without persistent
     *  per-event storage. */
    private final MutableLiveData<Long> mTodayBlockedLive = new MutableLiveData<>(0L);

    /** Per-category breakdown of blocked requests since install,
     *  bucketed by request type at the uBlock side. uBlock's static
     *  engine doesn't preserve filter-list origin, so we can't honestly
     *  bucket by 'Tracker vs Ad'; instead firedown.js groups by the
     *  filter context's itype (script / image-or-pixel / frame /
     *  other) and pushes the four-key map alongside the cumulative
     *  total. The TrackersInfoSheet renders each bucket under the
     *  hero number to give 'what was blocked' some texture. */
    public enum Category { SCRIPTS, PIXELS, FRAMES, OTHER }

    private final MutableLiveData<Map<Category, Long>> mCategoryBlockedLive =
            new MutableLiveData<>(emptyCategoryMap());

    /** Top blocked third-party hostnames since install (or since the user
     *  last hit 'Disable & clear' from the sheet). uBlock's firedown.js
     *  maintains a per-host counter map, evicts at 500 entries, gates on
     *  per-tab incognito state, and pushes the sorted top 10 over the
     *  native port. The TrackersInfoSheet renders these as a list under
     *  the per-type breakdown. */
    public static final class HostCount {
        public final String host;
        public final long count;
        public HostCount(String host, long count) {
            this.host = host;
            this.count = count;
        }
    }

    private final MutableLiveData<List<HostCount>> mTopTrackersLive =
            new MutableLiveData<>(Collections.emptyList());

    // Firewall activation is a global user preference — not per-mode.
    private final MutableLiveData<Boolean> mFirewallActiveLive = new MutableLiveData<>();

    private final SharedPreferences mPrefs;

    /** Persists the last known cumulative-blocked count so the Home
     *  card has something to show on cold start, before uBlock has
     *  initialised (which only happens when a tab opens, not when
     *  the user lands on Home). The card shows the cached value
     *  immediately; the live value overrides it the moment the
     *  extension pushes a fresh number. */
    private static final String KEY_CUMULATIVE_BLOCKED = "ublock.cumulative.blocked";
    private static final String KEY_DAY_BASELINE_DATE  = "ublock.day.baseline.date";
    private static final String KEY_DAY_BASELINE_COUNT = "ublock.day.baseline.count";

    @Inject
    public GeckoUblockHelper(@ApplicationContext Context context) {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        long cachedBlocked = mPrefs.getLong(KEY_CUMULATIVE_BLOCKED, 0L);
        if (cachedBlocked > 0L) mCumulativeBlockedLive.postValue(cachedBlocked);
        // Seed today's count from the cached baseline so the sheet
        // has something to show on cold start before the extension
        // pushes a fresh cumulative. If the baseline is for an older
        // date the next push will roll it forward; meanwhile this
        // value is at worst stale, not wrong.
        String savedDate = mPrefs.getString(KEY_DAY_BASELINE_DATE, null);
        if (cachedBlocked > 0L && todayKey().equals(savedDate)) {
            long baseline = mPrefs.getLong(KEY_DAY_BASELINE_COUNT, cachedBlocked);
            mTodayBlockedLive.postValue(Math.max(0L, cachedBlocked - baseline));
        }
    }


    /**
     * @return LiveData containing the current ads/trackers blocked count string
     * for regular browsing.
     */
    public LiveData<String> getAdsBlockedLive() {
        return mAdsBlockedLive;
    }

    /**
     * @return LiveData containing the current ads/trackers blocked count string
     * for incognito browsing.
     */
    public LiveData<String> getAdsBlockedLiveIncognito() {
        return mAdsBlockedLiveIncognito;
    }

    /**
     * @return LiveData containing the current activation status of the uBlock firewall.
     */
    public LiveData<Boolean> getFirewallActiveLive() {
        return mFirewallActiveLive;
    }


    // Cookie-notice blocking is a global filter-list selection, not per-hostname.
    // Reflects whether fanboy-cookiemonster is in µb.selectedFilterLists.
    private final MutableLiveData<Boolean> mCookieNoticesBlockedLive = new MutableLiveData<>(false);

    // --- State Management ---

    /**
     * @return LiveData carrying the cumulative blocked-request count
     * since install. Updates whenever firedown.js relays a fresh
     * value from {@code µb.requestStats.blockedCount}.
     */
    public LiveData<Long> getCumulativeBlockedLive() {
        return mCumulativeBlockedLive;
    }

    /**
     * Called from {@code handleUblockMessage} when the extension pushes
     * a fresh cumulative-blocked count. Just relays — uBlock owns the
     * source of truth, including legitimate resets when the user
     * clears the request-stats counter. Caches to SharedPreferences
     * so the Home card has something to show on the next cold start
     * before the extension finishes loading.
     */
    public void onCumulativeBlocked(long blocked) {
        if (blocked < 0) return;
        mCumulativeBlockedLive.postValue(blocked);

        // Roll the day baseline if we've crossed midnight since the
        // last push. Baseline = the cumulative value the day started
        // at, so 'today' = current - baseline. Storing date as ISO
        // yyyy-MM-dd keeps the comparison locale-stable. clamp to
        // zero so a clock skew (user winds backwards, cumulative
        // suddenly < baseline) doesn't render a negative count.
        String today = todayKey();
        String savedDate = mPrefs.getString(KEY_DAY_BASELINE_DATE, null);
        long baseline = mPrefs.getLong(KEY_DAY_BASELINE_COUNT, blocked);
        SharedPreferences.Editor editor = mPrefs.edit().putLong(KEY_CUMULATIVE_BLOCKED, blocked);
        if (!today.equals(savedDate)) {
            baseline = blocked;
            editor.putString(KEY_DAY_BASELINE_DATE, today)
                  .putLong(KEY_DAY_BASELINE_COUNT, baseline);
        }
        editor.apply();
        mTodayBlockedLive.postValue(Math.max(0L, blocked - baseline));
    }

    public LiveData<Long> getTodayBlockedLive() {
        return mTodayBlockedLive;
    }

    public LiveData<Map<Category, Long>> getCategoryBlockedLive() {
        return mCategoryBlockedLive;
    }

    /**
     * Called from {@code handleUblockMessage} when firedown.js relays
     * the per-category counters. The JS side sends a 4-key object
     * ({@code scripts}, {@code pixels}, {@code frames}, {@code other});
     * unknown keys or missing keys default to zero. Negative or NaN
     * values are clamped to the last good value to keep the sheet from
     * rendering nonsense if storage gets corrupted.
     */
    public LiveData<List<HostCount>> getTopTrackersLive() {
        return mTopTrackersLive;
    }

    /**
     * Called from {@code handleUblockMessage} when firedown.js relays a
     * top-trackers payload. Format: a JSON array of {host, count} pairs
     * already sorted descending. Entries with empty hostnames or
     * non-positive counts are dropped defensively.
     */
    public void onTopTrackers(@NonNull JSONArray array) {
        List<HostCount> next = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject row = array.optJSONObject(i);
            if (row == null) continue;
            String host = row.optString("host", "");
            long count = row.optLong("count", 0);
            if (host.isEmpty() || count <= 0) continue;
            next.add(new HostCount(host, count));
        }
        mTopTrackersLive.postValue(next);
    }

    public void onCategoryBlocked(long scripts, long pixels, long frames, long other) {
        Map<Category, Long> next = emptyCategoryMap();
        next.put(Category.SCRIPTS, Math.max(0L, scripts));
        next.put(Category.PIXELS,  Math.max(0L, pixels));
        next.put(Category.FRAMES,  Math.max(0L, frames));
        next.put(Category.OTHER,   Math.max(0L, other));
        mCategoryBlockedLive.postValue(next);
    }

    @NonNull
    private static Map<Category, Long> emptyCategoryMap() {
        EnumMap<Category, Long> m = new EnumMap<>(Category.class);
        for (Category c : Category.values()) m.put(c, 0L);
        return m;
    }

    private static String todayKey() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    /**
     * Session-aware ads count. Routes to the correct per-mode stream.
     */
    public void onAdsCount(String count, boolean isIncognito) {
        String value = TextUtils.isEmpty(count) ? "0" : count;
        if (isIncognito) {
            mAdsBlockedLiveIncognito.postValue(value);
        } else {
            mAdsBlockedLive.postValue(value);
        }
    }

    /**
     * @return LiveData containing the current state of cookie-notice list
     * selection. True when fanboy-cookiemonster is selected.
     */
    public LiveData<Boolean> getCookieNoticesBlockedLive() {
        return mCookieNoticesBlockedLive;
    }


    /**
     * Called when the uBlock firewall settings change.
     */
    public void onFirewallChanged(boolean activated, boolean cookieNoticesBlocked) {
        Log.d(TAG, "onFirewallChanged: " + activated);
        mFirewallActiveLive.postValue(activated);
        mCookieNoticesBlockedLive.postValue(cookieNoticesBlocked);
    }

    public boolean isActivated() {
        Boolean active = mFirewallActiveLive.getValue();
        return active != null && active;
    }

    /**
     * Resets the helper state (e.g., when a new session starts or engine is cleared).
     */
    public void clear() {
        Log.d(TAG, "onFirewallChanged clear");
        mAdsBlockedLive.postValue("0");
        mAdsBlockedLiveIncognito.postValue("0");
        mFirewallActiveLive.postValue(false);
        mCookieNoticesBlockedLive.postValue(false);
        mCategoryBlockedLive.postValue(emptyCategoryMap());
        mTopTrackersLive.postValue(Collections.emptyList());
    }
}
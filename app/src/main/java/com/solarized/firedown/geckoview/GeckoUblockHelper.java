package com.solarized.firedown.geckoview;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.solarized.firedown.BuildConfig;

import dagger.hilt.android.qualifiers.ApplicationContext;

import java.util.Calendar;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * The blocked-request numbers: this page's count and hosts for the shield
 * sheet, the lifetime and today figures for Settings → Privacy → "Trackers
 * and ads blocked" and the home line.
 *
 * <p>They come from the capture extension (webrequests/js/blocked-stats.js),
 * which sees every request a blocker refuses — whichever add-on did it, and
 * Gecko's own tracker blocking — so the numbers don't depend on one ad
 * blocker. The extension sends DELTAS for the lifetime figures (regular tabs
 * only) and this class adds them to what it persisted, which is why a
 * lifetime count from before the official uBlock Origin carries on.
 */
@Singleton
public class GeckoUblockHelper {

    private static final String TAG = GeckoUblockHelper.class.getSimpleName();

    /** Requests blocked on the page on screen, per mode (a private tab's count never shows on a regular one). */
    private final MutableLiveData<String> mAdsBlockedLive = new MutableLiveData<>("0");
    private final MutableLiveData<String> mAdsBlockedLiveIncognito = new MutableLiveData<>("0");
    /** Since install. Regular tabs only. */
    private final MutableLiveData<Long> mCumulativeBlockedLive = new MutableLiveData<>(0L);
    /** Since local midnight: cumulative minus the count the day started at. */
    private final MutableLiveData<Long> mTodayBlockedLive = new MutableLiveData<>(0L);

    /** Today's blocks by request type (Settings' "Today by request type"). */
    public enum Category { SCRIPTS, PIXELS, FRAMES, OTHER }

    private final MutableLiveData<Map<Category, Long>> mCategoryBlockedLive =
            new MutableLiveData<>(emptyCategoryMap());

    /** A host and how often it was refused. {@code domain} is the host when no
     *  registrable domain is known ({@link com.solarized.firedown.ui.HostLabel}
     *  then styles it whole). */
    public static final class HostCount {
        public final String host;
        public final String domain;
        public final long count;
        public HostCount(String host, String domain, long count) {
            this.host = host;
            this.domain = domain;
            this.count = count;
        }
    }

    /** Today's ten most refused hosts. */
    private final MutableLiveData<List<HostCount>> mTopTrackersLive =
            new MutableLiveData<>(Collections.emptyList());

    /** The hosts refused on the page on screen, per mode. */
    private final MutableLiveData<List<HostCount>> mPageBlocksLive =
            new MutableLiveData<>(Collections.emptyList());

    private final MutableLiveData<List<HostCount>> mPageBlocksLiveIncognito =
            new MutableLiveData<>(Collections.emptyList());

    /** Today's per-host counts, the source of the top ten. Bounded. */
    private final Map<String, Long> mTodayHosts = new HashMap<>();
    private final Map<Category, Long> mTodayCategories = emptyCategoryMap();

    static final int MAX_TODAY_HOSTS = 500;
    static final int TOP_HOSTS = 10;
    /** The breakdown (categories, up to 500 hosts) is written at most this often; the lifetime count every time. */
    static final long BREAKDOWN_WRITE_INTERVAL_MS = 10_000;
    private long mLastBreakdownWrite;

    private final SharedPreferences mPrefs;

    /** In {@code backup_local.xml}, excluded from Auto Backup: these figures
     *  describe this install's browsing, and a restore onto a new phone
     *  starts at zero. The key names are the ones the bundled uBlock fed,
     *  so the lifetime count carries on. */
    private static final String LOCAL_PREFS = "backup_local";
    private static final String KEY_CUMULATIVE_BLOCKED = "ublock.cumulative.blocked";
    private static final String KEY_DAY_BASELINE_DATE  = "ublock.day.baseline.date";
    private static final String KEY_DAY_BASELINE_COUNT = "ublock.day.baseline.count";
    private static final String KEY_TODAY_CATEGORIES = "blocked.today.categories";
    private static final String KEY_TODAY_HOSTS = "blocked.today.hosts";

    @Inject
    public GeckoUblockHelper(@ApplicationContext Context context) {
        mPrefs = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
        long cachedBlocked = mPrefs.getLong(KEY_CUMULATIVE_BLOCKED, 0L);
        mCumulativeBlockedLive.postValue(cachedBlocked);
        String savedDate = mPrefs.getString(KEY_DAY_BASELINE_DATE, null);
        if (todayKey().equals(savedDate)) {
            long baseline = mPrefs.getLong(KEY_DAY_BASELINE_COUNT, cachedBlocked);
            mTodayBlockedLive.postValue(Math.max(0L, cachedBlocked - baseline));
            readToday();
        }
    }

    public LiveData<String> getAdsBlockedLive() {
        return mAdsBlockedLive;
    }

    public LiveData<String> getAdsBlockedLiveIncognito() {
        return mAdsBlockedLiveIncognito;
    }

    public LiveData<Long> getCumulativeBlockedLive() {
        return mCumulativeBlockedLive;
    }

    public LiveData<Long> getTodayBlockedLive() {
        return mTodayBlockedLive;
    }

    public LiveData<Map<Category, Long>> getCategoryBlockedLive() {
        return mCategoryBlockedLive;
    }

    public LiveData<List<HostCount>> getTopTrackersLive() {
        return mTopTrackersLive;
    }

    public LiveData<List<HostCount>> getPageBlocksLive() {
        return mPageBlocksLive;
    }

    public LiveData<List<HostCount>> getPageBlocksLiveIncognito() {
        return mPageBlocksLiveIncognito;
    }

    /**
     * The extension's lifetime deltas: {@code {blocked, scripts, pixels,
     * frames, other, hosts:[{host, count}]}}. Added to the persisted
     * figures; the day's figures start over at local midnight.
     */
    public synchronized void onBlockedTotals(@NonNull JSONObject totals) {
        long blocked = Math.max(0L, totals.optLong("blocked", 0));
        if (blocked == 0) {
            return;
        }
        String today = todayKey();
        String savedDate = mPrefs.getString(KEY_DAY_BASELINE_DATE, null);
        long previous = mPrefs.getLong(KEY_CUMULATIVE_BLOCKED, 0L);
        long cumulative = previous + blocked;
        SharedPreferences.Editor editor = mPrefs.edit().putLong(KEY_CUMULATIVE_BLOCKED, cumulative);
        long baseline;
        if (!today.equals(savedDate)) {
            // The day's first blocks: today counts from the previous total.
            baseline = previous;
            editor.putString(KEY_DAY_BASELINE_DATE, today).putLong(KEY_DAY_BASELINE_COUNT, baseline);
            mTodayHosts.clear();
            for (Category c : Category.values()) {
                mTodayCategories.put(c, 0L);
            }
        } else {
            baseline = mPrefs.getLong(KEY_DAY_BASELINE_COUNT, previous);
        }
        add(Category.SCRIPTS, totals.optLong("scripts", 0));
        add(Category.PIXELS, totals.optLong("pixels", 0));
        add(Category.FRAMES, totals.optLong("frames", 0));
        add(Category.OTHER, totals.optLong("other", 0));
        JSONArray hosts = totals.optJSONArray("hosts");
        if (hosts != null) {
            for (int i = 0; i < hosts.length(); i++) {
                JSONObject row = hosts.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                String host = row.optString("host", "");
                long count = row.optLong("count", 0);
                if (host.isEmpty() || count <= 0) {
                    continue;
                }
                Long had = mTodayHosts.get(host);
                if (had != null) {
                    mTodayHosts.put(host, had + count);
                } else if (mTodayHosts.size() < MAX_TODAY_HOSTS) {
                    mTodayHosts.put(host, count);
                }
            }
        }
        long now = SystemClock.elapsedRealtime();
        boolean newDay = !today.equals(savedDate);
        if (newDay || now - mLastBreakdownWrite >= BREAKDOWN_WRITE_INTERVAL_MS) {
            // A process death loses at most this interval of the day's
            // breakdown; the lifetime count is never behind.
            mLastBreakdownWrite = now;
            editor.putString(KEY_TODAY_CATEGORIES, categoriesJson().toString())
                  .putString(KEY_TODAY_HOSTS, hostsJson().toString());
        }
        editor.apply();
        mCumulativeBlockedLive.postValue(cumulative);
        mTodayBlockedLive.postValue(Math.max(0L, cumulative - baseline));
        mCategoryBlockedLive.postValue(new EnumMap<>(mTodayCategories));
        mTopTrackersLive.postValue(top(mTodayHosts, TOP_HOSTS));
    }

    private void add(@NonNull Category category, long delta) {
        if (delta > 0) {
            Long had = mTodayCategories.get(category);
            mTodayCategories.put(category, (had == null ? 0L : had) + delta);
        }
    }

    /**
     * The page on screen: its count and hosts ({@code [{host, count}]},
     * most refused first). An empty list is a page with nothing refused.
     */
    public void onPageBlocks(long count, @Nullable JSONArray array, boolean isIncognito) {
        List<HostCount> next;
        if (array == null || array.length() == 0) {
            next = Collections.emptyList();
        } else {
            next = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.optJSONObject(i);
                if (row == null) continue;
                String host = row.optString("host", "");
                long n = row.optLong("count", 0);
                if (host.isEmpty() || n <= 0) continue;
                next.add(new HostCount(host, row.optString("domain", host), n));
            }
        }
        String value = String.valueOf(Math.max(0L, count));
        if (isIncognito) {
            mPageBlocksLiveIncognito.postValue(next);
            mAdsBlockedLiveIncognito.postValue(value);
        } else {
            mPageBlocksLive.postValue(next);
            mAdsBlockedLive.postValue(value);
        }
    }

    // ── Persistence of today's breakdown ────────────────────────────────

    private void readToday() {
        try {
            JSONObject categories = new JSONObject(mPrefs.getString(KEY_TODAY_CATEGORIES, "{}"));
            for (Category c : Category.values()) {
                mTodayCategories.put(c, Math.max(0L, categories.optLong(c.name(), 0)));
            }
            JSONArray hosts = new JSONArray(mPrefs.getString(KEY_TODAY_HOSTS, "[]"));
            for (int i = 0; i < hosts.length() && mTodayHosts.size() < MAX_TODAY_HOSTS; i++) {
                JSONObject row = hosts.optJSONObject(i);
                if (row != null && !row.optString("host", "").isEmpty() && row.optLong("count", 0) > 0) {
                    mTodayHosts.put(row.optString("host"), row.optLong("count"));
                }
            }
        } catch (JSONException e) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "today's blocked figures unreadable", e);
            }
            mTodayHosts.clear();
        }
        mCategoryBlockedLive.postValue(new EnumMap<>(mTodayCategories));
        mTopTrackersLive.postValue(top(mTodayHosts, TOP_HOSTS));
    }

    @NonNull
    private JSONObject categoriesJson() {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<Category, Long> e : mTodayCategories.entrySet()) {
                o.put(e.getKey().name(), e.getValue());
            }
        } catch (JSONException e) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "categories", e);
            }
        }
        return o;
    }

    @NonNull
    private JSONArray hostsJson() {
        JSONArray a = new JSONArray();
        try {
            for (Map.Entry<String, Long> e : mTodayHosts.entrySet()) {
                a.put(new JSONObject().put("host", e.getKey()).put("count", e.getValue()));
            }
        } catch (JSONException e) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "hosts", e);
            }
        }
        return a;
    }

    @NonNull
    static List<HostCount> top(@NonNull Map<String, Long> hosts, int max) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(hosts.entrySet());
        Collections.sort(entries, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<HostCount> out = new ArrayList<>(Math.min(max, entries.size()));
        for (int i = 0; i < entries.size() && i < max; i++) {
            Map.Entry<String, Long> e = entries.get(i);
            out.add(new HostCount(e.getKey(), e.getKey(), e.getValue()));
        }
        return out;
    }

    @NonNull
    private static Map<Category, Long> emptyCategoryMap() {
        EnumMap<Category, Long> m = new EnumMap<>(Category.class);
        for (Category c : Category.values()) m.put(c, 0L);
        return m;
    }

    private static String todayKey() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH));
    }
}

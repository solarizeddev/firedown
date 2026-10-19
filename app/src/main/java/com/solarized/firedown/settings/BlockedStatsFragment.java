package com.solarized.firedown.settings;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceCategory;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.solarized.firedown.R;
import com.solarized.firedown.geckoview.GeckoUblockHelper;
import com.solarized.firedown.geckoview.GeckoUblockHelper.Category;
import com.solarized.firedown.geckoview.GeckoUblockHelper.HostCount;
import com.solarized.firedown.settings.ui.CountPreference;
import com.solarized.firedown.settings.ui.HostCountPreference;
import com.solarized.firedown.ui.HostLabel;

import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Settings → Privacy → "Trackers and ads blocked": what the built-in ad
 * blocker has done. The hero (blocked since install / estimated data saved /
 * blocked today), a "Today by request type" group, a "Most blocked domains
 * today" group (every blocked host, the site's own included — there is no
 * third-party filter, so it doesn't say "trackers"; revealed once the list has
 * a few rows) and a footnote naming the midnight reset and the estimate.
 * Everything is read live from {@link GeckoUblockHelper}, which relays what
 * firedown.js pushes over the uBlock native port; nothing here is a control.
 * The domain rows share their anatomy with the per-page "Blocked on this
 * page" sheet: a 14sp host ({@link HostCountPreference}) through
 * {@link HostLabel}, the count always shown.
 *
 * <p>This is where the lifetime figure lives now. It used to be a counter on
 * the home with a bottom sheet behind it; the home became the mark over the
 * search field (home v6) and the maintainer moved the stats here ("move the
 * blocked ads stats to the settings"). The per-PAGE count stays on the
 * security sheet; Gecko's own ETP count stays on the Enhanced Tracking
 * Protection screen — a separate measurement, deliberately not shown beside
 * this one.</p>
 */
@AndroidEntryPoint
public class BlockedStatsFragment extends BasePreferenceFragment {

    private static final String KEY_HERO =
            "com.solarized.firedown.preferences.privacy.blocked.stats.hero";
    private static final String KEY_TYPE_SCRIPTS =
            "com.solarized.firedown.preferences.privacy.blocked.stats.type.scripts";
    private static final String KEY_TYPE_PIXELS =
            "com.solarized.firedown.preferences.privacy.blocked.stats.type.pixels";
    private static final String KEY_TYPE_FRAMES =
            "com.solarized.firedown.preferences.privacy.blocked.stats.type.frames";
    private static final String KEY_TYPE_OTHER =
            "com.solarized.firedown.preferences.privacy.blocked.stats.type.other";
    private static final String KEY_TOP =
            "com.solarized.firedown.preferences.privacy.blocked.stats.top";

    /** Rows before the "Most blocked domains today" group is shown: fewer
     *  read as "not working yet" rather than "still building" (a fresh
     *  install, or the first hour after the midnight rebuild). */
    private static final int TOP_TRACKERS_MIN_REVEAL = 3;

    @Inject
    GeckoUblockHelper mGeckoUblockHelper;

    private BlockedStatsPreference mHero;
    private CountPreference mScripts;
    private CountPreference mPixels;
    private CountPreference mFrames;
    private CountPreference mOther;
    private PreferenceCategory mTop;

    private long mTotal;
    private long mToday;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.settings_blocked_stats, rootKey);
        mHero = getPreferenceScreen().findPreference(KEY_HERO);
        mScripts = getPreferenceScreen().findPreference(KEY_TYPE_SCRIPTS);
        mPixels = getPreferenceScreen().findPreference(KEY_TYPE_PIXELS);
        mFrames = getPreferenceScreen().findPreference(KEY_TYPE_FRAMES);
        mOther = getPreferenceScreen().findPreference(KEY_TYPE_OTHER);
        mTop = getPreferenceScreen().findPreference(KEY_TOP);
        if (mTop != null) {
            mTop.setVisible(false);
        }
        tintIcons();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Every push from the extension changes counts IN PLACE, and
        // RecyclerView's default change animation cross-faded each changed
        // row — the whole list flashed on every update while the screen was
        // open. Counts that tick should just change.
        RecyclerView.ItemAnimator animator = getListView().getItemAnimator();
        if (animator instanceof SimpleItemAnimator simple) {
            simple.setSupportsChangeAnimations(false);
        }

        mGeckoUblockHelper.getCumulativeBlockedLive().observe(getViewLifecycleOwner(), blocked -> {
            mTotal = blocked == null ? 0L : blocked;
            applyHero();
        });
        mGeckoUblockHelper.getTodayBlockedLive().observe(getViewLifecycleOwner(), today -> {
            mToday = today == null ? 0L : today;
            applyHero();
        });
        // Per-type breakdown: firedown.js buckets today's blocked requests by
        // fctxt.itype and pushes the four-key map; the rows tick while the
        // screen is open.
        mGeckoUblockHelper.getCategoryBlockedLive().observe(getViewLifecycleOwner(), buckets -> {
            if (buckets == null) {
                return;
            }
            setCount(mScripts, buckets, Category.SCRIPTS);
            setCount(mPixels, buckets, Category.PIXELS);
            setCount(mFrames, buckets, Category.FRAMES);
            setCount(mOther, buckets, Category.OTHER);
        });
        mGeckoUblockHelper.getTopTrackersLive().observe(getViewLifecycleOwner(), this::applyTopTrackers);
    }

    private void applyHero() {
        if (mHero != null) {
            mHero.setStats(mTotal, mToday);
        }
    }

    private static void setCount(@Nullable CountPreference row,
                                 @NonNull Map<Category, Long> buckets, @NonNull Category key) {
        if (row == null) {
            return;
        }
        Long value = buckets.get(key);
        row.setCount(value == null ? 0L : value);
    }

    /**
     * Rebuilds the "Most blocked domains today" group from the pre-sorted
     * top-N list the extension pushes (descending count, capped at 10
     * upstream), hidden below {@link #TOP_TRACKERS_MIN_REVEAL} entries so a
     * thin list never floats a header over one or two rows.
     *
     * <p>The rows are UPDATED IN PLACE: row i takes the i-th host, and rows
     * are added or removed only when the list's length changes. It used to
     * {@code removeAll()} and add ten fresh rows on every push — every burst
     * of blocks and every minute while the screen is open — which re-laid out
     * the whole group each time and was half of why the list flashed (the
     * other half: the change animation, off in {@link #onViewCreated}).
     * {@code setTitle} and {@code setCount} are no-ops for an unchanged value,
     * so a push that only moves one count rebinds one row.</p>
     */
    private void applyTopTrackers(@Nullable List<HostCount> trackers) {
        if (mTop == null) {
            return;
        }
        int size = trackers == null ? 0 : trackers.size();
        if (size < TOP_TRACKERS_MIN_REVEAL) {
            mTop.removeAll();
            mTop.setVisible(false);
            return;
        }
        for (int i = 0; i < size; i++) {
            HostCount tracker = trackers.get(i);
            HostCountPreference row;
            if (i < mTop.getPreferenceCount()) {
                row = (HostCountPreference) mTop.getPreference(i);
            } else {
                row = new HostCountPreference(requireContext());
                mTop.addPreference(row);
            }
            row.setTitle(HostLabel.format(requireContext(), tracker.host, tracker.domain));
            row.setCount(tracker.count);
        }
        while (mTop.getPreferenceCount() > size) {
            mTop.removePreference(mTop.getPreference(mTop.getPreferenceCount() - 1));
        }
        mTop.setVisible(true);
    }
}

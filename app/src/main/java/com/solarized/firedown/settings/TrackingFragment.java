package com.solarized.firedown.settings;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.settings.ui.RadioButtonPreference;
import com.solarized.firedown.utils.NavigationUtils;
import dagger.hilt.android.AndroidEntryPoint;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.ContentBlockingController;
import org.mozilla.geckoview.ContentBlockingController.TrackingDbEvent;
import org.mozilla.geckoview.GeckoRuntime;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


@AndroidEntryPoint
public class TrackingFragment extends BasePreferenceFragment implements Preference.OnPreferenceClickListener {

    private static final String TAG = TrackingFragment.class.getName();

    private static final String KEY_STRIP_LIST_NAV =
            "com.solarized.firedown.preferences.browser.tracking.strip.list.nav";

    private static final String KEY_STATS =
            "com.solarized.firedown.preferences.browser.tracking.stats";

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private TrackingStatsPreference mStatsPreference;

    private RadioButtonPreference mDefaultPreference;

    private RadioButtonPreference mStrictPreference;

    private RadioButtonPreference mCustomPreference;

    private Preference mStripListNavPreference;


    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_tracking, rootKey);

        mDefaultPreference = getPreferenceScreen().findPreference(Preferences.SETTINGS_ANTI_TRACKING_DEFAULT);

        mStrictPreference = getPreferenceScreen().findPreference(Preferences.SETTINGS_ANTI_TRACKING_STRICT);

        mCustomPreference = getPreferenceScreen().findPreference(Preferences.SETTINGS_ANTI_TRACKING_CUSTOM);

        mStripListNavPreference = getPreferenceScreen().findPreference(KEY_STRIP_LIST_NAV);

        mStatsPreference = getPreferenceScreen().findPreference(KEY_STATS);

        mDefaultPreference.addToRadioGroup(mStrictPreference);
        mDefaultPreference.addToRadioGroup(mCustomPreference);
        mStrictPreference.addToRadioGroup(mDefaultPreference);
        mStrictPreference.addToRadioGroup(mCustomPreference);
        mCustomPreference.addToRadioGroup(mDefaultPreference);
        mCustomPreference.addToRadioGroup(mStrictPreference);

        if(mDefaultPreference != null)
            mDefaultPreference.setOnPreferenceClickListener(this);

        if(mStrictPreference != null)
            mStrictPreference.setOnPreferenceClickListener(this);

        if(mCustomPreference != null)
            mCustomPreference.setOnPreferenceClickListener(this);

        if(mStripListNavPreference != null) {
            mStripListNavPreference.setOnPreferenceClickListener(p -> {
                NavigationUtils.navigateSafe(mNavController, R.id.action_tracking_to_query_params);
                return true;
            });
        }

        setTrackingRadio();

        tintIcons();

    }


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        loadTrackingStats();
    }

    /**
     * Query Gecko's persisted ETP database and push the aggregates into
     * the stats header. ONE query (0..now) yields everything: each
     * {@link TrackingDbEvent} carries a "YYYY-MM-DD" date and a count, so
     * the all-time total, the trailing-7-day and today windows, the
     * per-category buckets, and the earliest-recorded date are all
     * derived from the same result. ISO date strings compare
     * lexicographically, so the window checks are plain string compares.
     *
     * <p>Runs on the main thread (a {@code GeckoResult} attached here
     * dispatches its callback back to the main thread), so touching the
     * preference is safe; guarded on {@link #isAdded()} against a screen
     * left before the async result lands.</p>
     */
    private void loadTrackingStats() {
        if (mStatsPreference == null) return;
        GeckoRuntime runtime = mGeckoRuntimeHelper.getGeckoRuntime();
        if (runtime == null) return;
        ContentBlockingController controller = runtime.getContentBlockingController();

        long now = System.currentTimeMillis();
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String todayKey = dayFmt.format(new Date(now));
        String weekKey = dayFmt.format(new Date(now - 7L * DAY_MS));

        controller.getTrackingDbEventsByDateRange(0L, now).accept(events -> {
            if (!isAdded() || events == null) return;

            long total = 0L, week = 0L, today = 0L, trackers = 0L, cookies = 0L,
                    fingerprinters = 0L, cryptominers = 0L, social = 0L, bounce = 0L;
            String minDate = null;
            for (TrackingDbEvent e : events) {
                total += e.count;
                switch (e.type) {
                    case TrackingDbEvent.TRACKERS_ID:
                        trackers += e.count;
                        break;
                    case TrackingDbEvent.TRACKING_COOKIES_ID:
                    case TrackingDbEvent.OTHER_COOKIES_BLOCKED_ID:
                        cookies += e.count;
                        break;
                    case TrackingDbEvent.FINGERPRINTERS_ID:
                    case TrackingDbEvent.SUSPICIOUS_FINGERPRINTERS_ID:
                        fingerprinters += e.count;
                        break;
                    case TrackingDbEvent.CRYPTOMINERS_ID:
                        cryptominers += e.count;
                        break;
                    case TrackingDbEvent.SOCIAL_ID:
                        social += e.count;
                        break;
                    case TrackingDbEvent.BOUNCETRACKERS_ID:
                        bounce += e.count;
                        break;
                    default:
                        break;
                }
                if (e.date != null) {
                    if (e.date.compareTo(weekKey) >= 0) week += e.count;
                    if (e.date.equals(todayKey)) today += e.count;
                    if (minDate == null || e.date.compareTo(minDate) < 0) minDate = e.date;
                }
            }

            long sinceMs = 0L;
            if (minDate != null) {
                try {
                    Date d = dayFmt.parse(minDate);
                    if (d != null) sinceMs = d.getTime();
                } catch (ParseException ignored) {
                    // Leave sinceMs 0 — the header just omits the 'since' line.
                }
            }

            mStatsPreference.setStats(total, week, today, sinceMs,
                    trackers, cookies, fingerprinters, cryptominers, social, bounce);
        });
    }


    private void setTrackingRadio() {
        if(mSharedPreferences.getBoolean(Preferences.SETTINGS_ANTI_TRACKING_STRICT, false)){
            mStrictPreference.setChecked(true);
        }else if(mSharedPreferences.getBoolean(Preferences.SETTINGS_ANTI_TRACKING_CUSTOM, false)){
            mCustomPreference.setChecked(true);
        }else{
            mDefaultPreference.setChecked(true);
        }
        updateStripListEnabled();
    }


    private void updateStripListEnabled() {
        if(mStripListNavPreference != null) {
            mStripListNavPreference.setEnabled(
                    mSharedPreferences.getBoolean(Preferences.SETTINGS_ANTI_TRACKING_CUSTOM, false));
        }
    }


    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
        ContentBlocking.Settings cb = mGeckoRuntimeHelper.getGeckoRuntime().getSettings().getContentBlocking();
        if((Preferences.SETTINGS_ANTI_TRACKING_DEFAULT).equals(preference.getKey())){
            mDefaultPreference.toggleRadioButton();
            cb.setAntiTracking(ContentBlocking.AntiTracking.DEFAULT);
            cb.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT);
            cb.setQueryParameterStrippingEnabled(false);
            cb.setQueryParameterStrippingPrivateBrowsingEnabled(false);
        }else if(Preferences.SETTINGS_ANTI_TRACKING_STRICT.equals(preference.getKey())){
            mStrictPreference.toggleRadioButton();
            cb.setAntiTracking(ContentBlocking.AntiTracking.STRICT | ContentBlocking.AntiTracking.STP);
            cb.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT);
            cb.setQueryParameterStrippingEnabled(false);
            cb.setQueryParameterStrippingPrivateBrowsingEnabled(false);
        }else if(Preferences.SETTINGS_ANTI_TRACKING_CUSTOM.equals(preference.getKey())){
            mCustomPreference.toggleRadioButton();
            cb.setAntiTracking(ContentBlocking.AntiTracking.DEFAULT);
            cb.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT);
            cb.setQueryParameterStrippingEnabled(true);
            cb.setQueryParameterStrippingPrivateBrowsingEnabled(true);
            cb.setQueryParameterStrippingStripList(
                    Preferences.getQueryParameterStripList(mSharedPreferences));
        }
        updateStripListEnabled();
        return false;
    }
}

package com.solarized.firedown.settings;

import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.settings.ui.GroupableRadioButton;
import com.solarized.firedown.settings.ui.RadioButtonPreference;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Settings screen for controlling automatic tab archiving.
 *
 * <p>Exposes a master switch ({@link Preferences#SETTINGS_TABS_ARCHIVE}) and
 * a mutually-exclusive set of {@link RadioButtonPreference}s that write the
 * chosen interval to {@link Preferences#SETTINGS_TABS_ARCHIVE_INTERVAL} as a
 * {@code long} (milliseconds). "Never" is encoded as
 * {@link Preferences#NEVER_INTERVAL} ({@code -1}).</p>
 *
 * <p>Whenever the user flips the switch on or shortens the interval, we run
 * an immediate archive sweep on the disk executor so the change takes effect
 * without waiting for the next cold start.</p>
 */
@AndroidEntryPoint
public class TabsFragment extends BasePreferenceFragment {

    private static final String TAG = TabsFragment.class.getSimpleName();

    // Radio button keys — UI only, not persisted.
    private static final String KEY_NEVER     = "pref_tabs_archive_interval_never";
    private static final String KEY_ONE_DAY   = "pref_tabs_archive_interval_one_day";
    private static final String KEY_ONE_WEEK  = "pref_tabs_archive_interval_one_week";
    private static final String KEY_ONE_MONTH = "pref_tabs_archive_interval_one_month";

    @Inject
    GeckoStateDataRepository mGeckoStateDataRepository;

    @Inject
    @Qualifiers.DiskIO
    Executor mDiskExecutor;

    private SwitchPreferenceCompat mArchiveSwitch;
    private RadioButtonPreference mNeverRadio;
    private RadioButtonPreference mOneDayRadio;
    private RadioButtonPreference mOneWeekRadio;
    private RadioButtonPreference mOneMonthRadio;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.settings_tabs, rootKey);

        mArchiveSwitch = findPreference(Preferences.SETTINGS_TABS_ARCHIVE);
        mNeverRadio    = findPreference(KEY_NEVER);
        mOneDayRadio   = findPreference(KEY_ONE_DAY);
        mOneWeekRadio  = findPreference(KEY_ONE_WEEK);
        mOneMonthRadio = findPreference(KEY_ONE_MONTH);

        wireRadioGroup();
        bindRadioInitialState();
        bindListeners();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tintIcons();
    }

    /**
     * Registers every radio button as a listener of every other one, so that
     * checking any button un-checks its siblings — i.e. single-select behaviour
     * via the existing {@link GroupableRadioButton} contract.
     */
    private void wireRadioGroup() {
        List<RadioButtonPreference> group = Arrays.asList(
                mNeverRadio, mOneDayRadio, mOneWeekRadio, mOneMonthRadio);

        for (RadioButtonPreference self : group) {
            if (self == null) continue;
            for (RadioButtonPreference other : group) {
                if (other == null || other == self) continue;
                self.addToRadioGroup(other);
            }
        }
    }

    /**
     * Reads the current persisted interval and ticks the matching radio button.
     * If nothing is persisted yet, defaults to one week (matching the bootstrap
     * default in the repository initialization).
     */
    private void bindRadioInitialState() {
        long current = mSharedPreferences.getLong(
                Preferences.SETTINGS_TABS_ARCHIVE_INTERVAL,
                Preferences.ONE_WEEK_INTERVAL);

        setCheckedSilently(mNeverRadio,    current == Preferences.NEVER_INTERVAL);
        setCheckedSilently(mOneDayRadio,   current == Preferences.ONE_DAY_INTERVAL);
        setCheckedSilently(mOneWeekRadio,  current == Preferences.ONE_WEEK_INTERVAL);
        setCheckedSilently(mOneMonthRadio, current == Preferences.THIRTY_DAYS_INTERVAL);
    }

    /**
     * Sets the checked state without triggering the change listener — used
     * during initial binding so we don't fire a spurious archive sweep on
     * screen entry.
     */
    private void setCheckedSilently(@Nullable RadioButtonPreference pref, boolean checked) {
        if (pref == null) return;
        Preference.OnPreferenceChangeListener saved = pref.getOnPreferenceChangeListener();
        pref.setOnPreferenceChangeListener(null);
        pref.setChecked(checked);
        pref.setOnPreferenceChangeListener(saved);
    }

    private void bindListeners() {
        if (mArchiveSwitch != null) {
            mArchiveSwitch.setOnPreferenceChangeListener((pref, newValue) -> {
                boolean enabled = Boolean.TRUE.equals(newValue);
                Log.d(TAG, "archive switch → " + enabled);
                if (enabled) {
                    long threshold = readIntervalFromRadios();
                    if (threshold > 0) runArchiveSweep(threshold);
                }
                return true;
            });
        }

        bindIntervalListener(mNeverRadio,    Preferences.NEVER_INTERVAL);
        bindIntervalListener(mOneDayRadio,   Preferences.ONE_DAY_INTERVAL);
        bindIntervalListener(mOneWeekRadio,  Preferences.ONE_WEEK_INTERVAL);
        bindIntervalListener(mOneMonthRadio, Preferences.THIRTY_DAYS_INTERVAL);
    }

    private void bindIntervalListener(@Nullable RadioButtonPreference pref, long intervalMillis) {
        if (pref == null) return;
        pref.setOnPreferenceChangeListener((p, newValue) -> {
            boolean checked = Boolean.TRUE.equals(newValue);
            if (!checked) {
                // Radio buttons un-check through the group mechanism; we only
                // act on the positive edge to avoid duplicated work.
                return true;
            }

            // 1. Un-tick siblings via the radio group contract.
            ((RadioButtonPreference) p).toggleRadioButton();
            // RadioButtonPreference.toggleRadioButton() runs only when
            // isChecked() is already true, but at this point in the change
            // flow the new value hasn't been committed yet. Do it manually:
            notifySiblings((RadioButtonPreference) p);

            // 2. Persist the long value for the rest of the app.
            mSharedPreferences.edit()
                    .putLong(Preferences.SETTINGS_TABS_ARCHIVE_INTERVAL, intervalMillis)
                    .apply();
            Log.d(TAG, "interval → " + intervalMillis + "ms");

            // 3. If auto-close is on and the new interval is finite, sweep now.
            boolean enabled = mSharedPreferences.getBoolean(
                    Preferences.SETTINGS_TABS_ARCHIVE, true);
            if (enabled && intervalMillis > 0) {
                runArchiveSweep(intervalMillis);
            }
            return true;
        });
    }

    /**
     * Manually un-checks every radio button in the group except {@code selected}.
     * We can't rely on {@link RadioButtonPreference#toggleRadioButton()} during
     * the change callback because the new checked state hasn't been committed
     * yet at that point.
     */
    private void notifySiblings(RadioButtonPreference selected) {
        RadioButtonPreference[] all = {
                mNeverRadio, mOneDayRadio, mOneWeekRadio, mOneMonthRadio
        };
        for (RadioButtonPreference other : all) {
            if (other != null && other != selected && other.isChecked()) {
                other.setChecked(false);
            }
        }
    }

    private long readIntervalFromRadios() {
        if (mNeverRadio    != null && mNeverRadio.isChecked())    return Preferences.NEVER_INTERVAL;
        if (mOneDayRadio   != null && mOneDayRadio.isChecked())   return Preferences.ONE_DAY_INTERVAL;
        if (mOneWeekRadio  != null && mOneWeekRadio.isChecked())  return Preferences.ONE_WEEK_INTERVAL;
        if (mOneMonthRadio != null && mOneMonthRadio.isChecked()) return Preferences.THIRTY_DAYS_INTERVAL;
        return Preferences.ONE_WEEK_INTERVAL;
    }

    /**
     * Runs an archive pass on the disk executor and records the timestamp
     * so any scheduler can decide whether a subsequent sweep is needed.
     */
    private void runArchiveSweep(long thresholdMillis) {
        mDiskExecutor.execute(() -> {
            int archived = mGeckoStateDataRepository.archiveInactiveTabs(thresholdMillis);
            Log.d(TAG, "archive sweep archived " + archived + " tab(s)");
            mSharedPreferences.edit()
                    .putLong(Preferences.SETTINGS_TABS_ARCHIVE_LAST_RUN,
                            System.currentTimeMillis())
                    .apply();
        });
    }
}
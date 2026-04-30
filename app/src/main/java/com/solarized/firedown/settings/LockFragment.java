package com.solarized.firedown.settings;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.models.LockViewModel;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.Utils;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class LockFragment extends BasePreferenceFragment implements
        Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {

    private LockViewModel mViewModel;
    private SwitchPreferenceCompat mLockPref;
    private Preference mTimePref;

    private final androidx.activity.result.ActivityResultLauncher<Intent> mSettingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> syncUIState());

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_lock, rootKey);
        mViewModel = new ViewModelProvider(this).get(LockViewModel.class);

        mLockPref = findPreference(Preferences.SETTINGS_APP_LOCK);
        mTimePref = findPreference(Preferences.SETTINGS_APP_LOCK_TIME);

        if (mLockPref != null) mLockPref.setOnPreferenceClickListener(this);
        if (mTimePref != null) mTimePref.setOnPreferenceChangeListener(this);

        syncUIState();
        tintIcons();
    }

    private void syncUIState() {
        boolean enabled = mViewModel.isAppLockEnabled();
        // Fallback: If enabled but no hardware found, force off
        if (enabled && !mViewModel.isBiometricAvailable()) {
            enabled = false;
            mViewModel.setLockEnabled(false);
            if (mLockPref != null) mLockPref.setChecked(false);
        }
        updateSubPreferences(enabled);
    }

    private void updateSubPreferences(boolean enable) {
        if (mTimePref == null) return;
        mTimePref.setEnabled(enable);
        mTimePref.setIcon(Utils.tintDrawable(mActivity, R.drawable.schedule_24,
                enable ? R.color.md_theme_onSurfaceVariant : R.color.md_theme_surfaceVariant));

        if (enable) {
            updateSummary(mViewModel.getLockTimeInterval());
        } else {
            mTimePref.setSummary("");
        }
    }

    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
        if (preference.getKey().equals(Preferences.SETTINGS_APP_LOCK)) {
            boolean checked = mLockPref.isChecked();
            if (checked && !mViewModel.isBiometricAvailable()) {
                launchEnrollment();
            }
            updateSubPreferences(checked);
        }
        return true;
    }

    private void launchEnrollment() {
        Intent intent = new Intent(BuildUtils.hasAndroidR() ? Settings.ACTION_BIOMETRIC_ENROLL : Settings.ACTION_SECURITY_SETTINGS);
        if (BuildUtils.hasAndroidR()) {
            intent.putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        }
        mSettingsLauncher.launch(intent);
    }

    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
        updateSummary((String) newValue);
        return true;
    }

    private void updateSummary(String value) {
        int val = Integer.parseInt(value);
        int resId = switch (val) {
            case (int) Preferences.FIVE_MINUTES_INTERVAL -> R.string.settings_lock_5_min;
            case (int) Preferences.FIFTEEN_MINUTES_INTERVAL -> R.string.settings_lock_15_min;
            case (int) Preferences.ONE_HOUR_INTERVAL -> R.string.settings_lock_1_hour;
            case (int) Preferences.ONE_DAY_INTERVAL -> R.string.settings_lock_1_day;
            default -> R.string.settings_lock_immediately;
        };
        mTimePref.setSummary(resId);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mLockPref = null;
        mTimePref = null;
    }
}
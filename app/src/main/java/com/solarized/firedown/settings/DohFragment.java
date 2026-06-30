package com.solarized.firedown.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.ListPreference;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.models.EditPreferenceViewModel;
import com.solarized.firedown.settings.ui.DohEditPreference;
import com.solarized.firedown.utils.Utils;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class DohFragment extends BasePreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = DohFragment.class.getSimpleName();
    private EditPreferenceViewModel mEditPreferenceViewModel;
    private ListPreference dohPreference;
    private DohEditPreference dohEditPreference;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mEditPreferenceViewModel = new ViewModelProvider(this).get(EditPreferenceViewModel.class);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // UI -> ViewModel: Trigger validation
        if (dohEditPreference != null) {
            dohEditPreference.setOnValidationRequestedListener(url ->
                    mEditPreferenceViewModel.validateDohProvider(url)
            );
        }

        // ViewModel -> UI: Handle results
        mEditPreferenceViewModel.getStatus().observe(getViewLifecycleOwner(), result -> {
            if (dohEditPreference == null) return;

            switch (result.status) {
                case SUCCESS -> {
                    dohEditPreference.showSuccess(getString(R.string.settings_doh_server_working), result.url);
                    applyDohToGecko(); // Update Gecko with the newly verified URL
                }
                case ERROR -> dohEditPreference.showError(getString(R.string.settings_doh_server_error_provider));
                case LOADING -> { /* Optional: show loading in pref */ }
            }
        });
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.settings_doh, rootKey);

        dohEditPreference = findPreference(Preferences.SETTINGS_DOH_CUSTOM);
        dohPreference = findPreference(Preferences.SETTINGS_DOH);

        refreshUiState();
        tintIcons();
    }

    private void refreshUiState() {
        boolean isDohEnabled = mSharedPreferences.getBoolean(Preferences.SETTINGS_DOH_SWITCH, false);
        String dohValue = mSharedPreferences.getString(Preferences.SETTINGS_DOH, Preferences.DEFAULT_SETTINGS_DOH);
        boolean isCustom = Preferences.SETTINGS_DOH_CUSTOM_VALUE.equals(dohValue);

        // Update Main DNS Preference
        if (dohPreference != null) {
            dohPreference.setEnabled(isDohEnabled);
            dohPreference.setIcon(Utils.tintDrawable(mActivity, R.drawable.dns_24,
                    isDohEnabled ? R.color.md_theme_onSurfaceVariant : R.color.md_theme_surfaceVariant));

            // entries/entryValues are aligned, so the ListPreference resolves
            // the display name for the current value itself.
            CharSequence entry = dohPreference.getEntry();
            if (entry != null) {
                dohPreference.setSummary(entry);
            }
        }

        // Update Custom URL Preference
        if (dohEditPreference != null) {
            dohEditPreference.setEnabled(isDohEnabled && isCustom);

            // Custom → show the user's saved URL; preset → the selected
            // value already IS the endpoint URL, shown in the disabled field.
            dohEditPreference.setTextInputText(isCustom
                    ? mSharedPreferences.getString(Preferences.SETTINGS_DOH_CUSTOM, "")
                    : dohValue);
        }
    }

    private void applyDohToGecko() {
        // Delegate to the shared helper so the boot-time application
        // (GeckoRuntimeHelper.applyDoh) and this on-change application can
        // never diverge.
        mGeckoRuntimeHelper.applyDoh(mSharedPreferences);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
        if (key == null) return;

        switch (key) {
            case Preferences.SETTINGS_DOH_SWITCH, Preferences.SETTINGS_DOH, Preferences.SETTINGS_DOH_CUSTOM -> {
                refreshUiState();
                applyDohToGecko();
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mSharedPreferences.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        dohEditPreference = null;
        dohPreference = null;
    }
}
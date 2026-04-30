package com.solarized.firedown.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.Preference;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.models.EditPreferenceViewModel;
import com.solarized.firedown.settings.ui.DohEditPreference;
import com.solarized.firedown.utils.Utils;

import org.mozilla.geckoview.GeckoRuntimeSettings;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class DohFragment extends BasePreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = DohFragment.class.getSimpleName();
    private EditPreferenceViewModel mEditPreferenceViewModel;
    private Preference dohPreference;
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
        int dohIndex = getDohInt(dohValue);

        // Update Main DNS Preference
        if (dohPreference != null) {
            dohPreference.setEnabled(isDohEnabled);
            dohPreference.setIcon(Utils.tintDrawable(mActivity, R.drawable.dns_24,
                    isDohEnabled ? R.color.md_theme_onSurfaceVariant : R.color.md_theme_surfaceVariant));

            String[] names = getResources().getStringArray(R.array.settings_doh);
            if (dohIndex < names.length) {
                dohPreference.setSummary(names[dohIndex]);
            }
        }

        // Update Custom URL Preference
        if (dohEditPreference != null) {
            boolean isCustom = (dohIndex == Preferences.SETTINGS_DOH_CUSOTM_INT);
            dohEditPreference.setEnabled(isDohEnabled && isCustom);

            String[] servers = getResources().getStringArray(R.array.settings_doh_servers);
            if (dohIndex < servers.length) {
                dohEditPreference.setTextInputText(servers[dohIndex]);
            }
        }
    }

    private void applyDohToGecko() {
        boolean enabled = mSharedPreferences.getBoolean(Preferences.SETTINGS_DOH_SWITCH, false);
        GeckoRuntimeSettings settings = mGeckoRuntimeHelper.getGeckoRuntime().getSettings();

        settings.setTrustedRecursiveResolverMode(enabled ?
                GeckoRuntimeSettings.TRR_MODE_FIRST : GeckoRuntimeSettings.TRR_MODE_OFF);

        if (enabled) {
            String dohValue = mSharedPreferences.getString(Preferences.SETTINGS_DOH, Preferences.DEFAULT_SETTINGS_DOH);
            int dohInt = getDohInt(dohValue);

            if (dohInt == Preferences.SETTINGS_DOH_CUSOTM_INT) {
                settings.setTrustedRecursiveResolverUri(mSharedPreferences.getString(
                        Preferences.SETTINGS_DOH_CUSTOM, ""));
            } else {
                String[] servers = getResources().getStringArray(R.array.settings_doh_servers);
                settings.setTrustedRecursiveResolverUri(servers[dohInt]);
            }
        }
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

    private int getDohInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
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
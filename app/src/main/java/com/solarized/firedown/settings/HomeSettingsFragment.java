package com.solarized.firedown.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;

import com.solarized.firedown.R;

/**
 * Per-user toggles for what renders on the home page. Currently a
 * single switch — show / hide the recent-downloads card — plus space
 * for future home customisation. When all toggles are off, the home
 * page falls back to the brand-only empty state.
 */
public class HomeSettingsFragment extends BasePreferenceFragment {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.settings_home, rootKey);
        tintIcons();
    }
}

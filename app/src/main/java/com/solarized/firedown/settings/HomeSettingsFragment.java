package com.solarized.firedown.settings;

import android.os.Bundle;

import com.solarized.firedown.R;

/**
 * Loads the {@code settings_home.xml} preference graph — two toggles
 * for the home-page customisation knobs (show recent downloads / show
 * shortcuts). Plumbing only; the actual behaviour lives in
 * HomeFragment#applyHomeCustomisation which observes the same prefs.
 */
public class HomeSettingsFragment extends BasePreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.settings_home, rootKey);
        tintIcons();
    }
}

package com.solarized.firedown.settings;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.Preference;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.settings.ui.RadioButtonPreference;


public class ThemeFragment extends BasePreferenceFragment implements Preference.OnPreferenceClickListener {

    private static final String TAG = ThemeFragment.class.getName();

    private RadioButtonPreference mDefaultPreference;

    private RadioButtonPreference mLightPreference;

    private RadioButtonPreference mDarkPreference;

    private RadioButtonPreference mOledPreference;


    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_theme, rootKey);

        mDefaultPreference = getPreferenceScreen().findPreference(Preferences.SETTINGS_THEME_DEFAULT);

        mDarkPreference = getPreferenceScreen().findPreference(Preferences.SETTINGS_THEME_DARK);

        mLightPreference = getPreferenceScreen().findPreference(Preferences.SETTINGS_THEME_LIGHT);

        mOledPreference = getPreferenceScreen().findPreference(Preferences.SETTINGS_THEME_OLED);

        mDefaultPreference.addToRadioGroup(mDarkPreference);
        mDefaultPreference.addToRadioGroup(mLightPreference);
        mDefaultPreference.addToRadioGroup(mOledPreference);

        mDarkPreference.addToRadioGroup(mDefaultPreference);
        mDarkPreference.addToRadioGroup(mLightPreference);
        mDarkPreference.addToRadioGroup(mOledPreference);

        mLightPreference.addToRadioGroup(mDefaultPreference);
        mLightPreference.addToRadioGroup(mDarkPreference);
        mLightPreference.addToRadioGroup(mOledPreference);

        mOledPreference.addToRadioGroup(mDefaultPreference);
        mOledPreference.addToRadioGroup(mDarkPreference);
        mOledPreference.addToRadioGroup(mLightPreference);

        if(mDefaultPreference != null)
            mDefaultPreference.setOnPreferenceClickListener(this);

        if(mDarkPreference != null)
            mDarkPreference.setOnPreferenceClickListener(this);

        if(mLightPreference != null)
            mLightPreference.setOnPreferenceClickListener(this);

        if(mOledPreference != null)
            mOledPreference.setOnPreferenceClickListener(this);


        tintIcons();

    }


    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
        SharedPreferences sharedPreferences = getPreferenceManager().getSharedPreferences();
        if((Preferences.SETTINGS_THEME_DEFAULT).equals(preference.getKey())){
            mDefaultPreference.toggleRadioButton();
            if(sharedPreferences != null)
                sharedPreferences.edit().putInt(Preferences.SETTINGS_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM).apply();
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            recreateActivity();
        }else if(Preferences.SETTINGS_THEME_DARK.equals(preference.getKey())){
            mDarkPreference.toggleRadioButton();
            if(sharedPreferences != null)
                sharedPreferences.edit().putInt(Preferences.SETTINGS_THEME, AppCompatDelegate.MODE_NIGHT_YES).apply();
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            recreateActivity();
        }else if(Preferences.SETTINGS_THEME_LIGHT.equals(preference.getKey())){
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            if(sharedPreferences != null)
                sharedPreferences.edit().putInt(Preferences.SETTINGS_THEME, AppCompatDelegate.MODE_NIGHT_NO).apply();
            mLightPreference.toggleRadioButton();
            recreateActivity();
        }else if(Preferences.SETTINGS_THEME_OLED.equals(preference.getKey())){
            // Force night mode to YES so the dark theme loads first; the
            // OLED overlay then layers pure-black surfaces on top via
            // BaseActivity.onCreate when it sees the OLED sentinel.
            mOledPreference.toggleRadioButton();
            if(sharedPreferences != null)
                sharedPreferences.edit().putInt(Preferences.SETTINGS_THEME, Preferences.THEME_OLED).apply();
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            recreateActivity();
        }
        return false;
    }

    /**
     * Re-inflate the host activity so the new theme / overlay takes
     * effect immediately. setDefaultNightMode triggers a recreate on
     * its own when the night-mode value actually changed, but
     * switching between Dark and OLED keeps night mode at YES — the
     * platform doesn't recreate, and the user would have to leave +
     * re-enter settings to see the surface flip. Force it.
     */
    private void recreateActivity() {
        if (getActivity() != null) {
            getActivity().recreate();
        }
    }
}
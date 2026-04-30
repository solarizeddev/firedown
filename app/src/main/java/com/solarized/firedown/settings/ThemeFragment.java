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


    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_theme, rootKey);

        mDefaultPreference = getPreferenceScreen().findPreference(Preferences.SETTINGS_THEME_DEFAULT);

        mDarkPreference = getPreferenceScreen().findPreference(Preferences.SETTINGS_THEME_DARK);

        mLightPreference = getPreferenceScreen().findPreference(Preferences.SETTINGS_THEME_LIGHT);

        mDefaultPreference.addToRadioGroup(mDarkPreference);
        mDefaultPreference.addToRadioGroup(mLightPreference);

        mDarkPreference.addToRadioGroup(mDefaultPreference);
        mDarkPreference.addToRadioGroup(mLightPreference);

        mLightPreference.addToRadioGroup(mDefaultPreference);
        mLightPreference.addToRadioGroup(mDarkPreference);

        if(mDefaultPreference != null)
            mDefaultPreference.setOnPreferenceClickListener(this);

        if(mDarkPreference != null)
            mDarkPreference.setOnPreferenceClickListener(this);

        if(mLightPreference != null)
            mLightPreference.setOnPreferenceClickListener(this);


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
        }else if(Preferences.SETTINGS_THEME_DARK.equals(preference.getKey())){
            mDarkPreference.toggleRadioButton();
            if(sharedPreferences != null)
                sharedPreferences.edit().putInt(Preferences.SETTINGS_THEME, AppCompatDelegate.MODE_NIGHT_YES).apply();
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        }else if(Preferences.SETTINGS_THEME_LIGHT.equals(preference.getKey())){
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            if(sharedPreferences != null)
                sharedPreferences.edit().putInt(Preferences.SETTINGS_THEME, AppCompatDelegate.MODE_NIGHT_NO).apply();
            mLightPreference.toggleRadioButton();
        }
        return false;
    }
}
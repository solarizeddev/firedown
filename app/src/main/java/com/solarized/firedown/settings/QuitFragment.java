package com.solarized.firedown.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;


public class QuitFragment extends BasePreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = QuitFragment.class.getName();


    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_quit, rootKey);

        Preference preference = getPreferenceScreen();

        boolean enabled = mSharedPreferences.getBoolean(Preferences.SETTINGS_QUIT_PREF, false);

        if(enabled) {
            if (preference instanceof PreferenceGroup group) {
                for (int i = 0; i < group.getPreferenceCount(); i++) {
                    Preference groupPreference = group.getPreference(i);
                    if(groupPreference instanceof CheckBoxPreference) {
                        groupPreference.setEnabled(true);
                    }
                }
            }
        }

        tintIcons();


    }

    @Override
    public void onResume() {
        super.onResume();
        SharedPreferences sharedPreferences = mSharedPreferences;
        if(sharedPreferences != null)
            sharedPreferences.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        SharedPreferences sharedPreferences = mSharedPreferences;
        if(sharedPreferences != null)
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
    }





    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
        if(Preferences.SETTINGS_QUIT_PREF.equals(key)){
            boolean value = sharedPreferences.getBoolean(key, false);

            Preference preference = getPreferenceScreen();

            if (preference instanceof PreferenceGroup group) {
                for (int i = 0; i < group.getPreferenceCount(); i++) {
                    Preference groupPreference = group.getPreference(i);
                    if(groupPreference instanceof CheckBoxPreference) {
                        Log.d(TAG, "onSharedPreferencesChanged key: + "  +group.getPreference(i).getKey());
                        groupPreference.setEnabled(value);
                        ((CheckBoxPreference) groupPreference).setChecked(value);
                    }
                }
            }

        }
    }


}
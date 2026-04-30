package com.solarized.firedown.settings;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.settings.ui.RadioButtonPreference;
import org.mozilla.geckoview.ContentBlocking;


public class TrackingFragment extends BasePreferenceFragment implements Preference.OnPreferenceClickListener {

    private static final String TAG = TrackingFragment.class.getName();

    private RadioButtonPreference mDefaultPreference;

    private RadioButtonPreference mStrictPreference;


    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_tracking, rootKey);

        mDefaultPreference = getPreferenceScreen().findPreference(Preferences.SETTINGS_ANTI_TRACKING_DEFAULT);

        mStrictPreference = getPreferenceScreen().findPreference(Preferences.SETTINGS_ANTI_TRACKING_STRICT);

        mDefaultPreference.addToRadioGroup(mStrictPreference);
        mStrictPreference.addToRadioGroup(mDefaultPreference);

        if(mDefaultPreference != null)
            mDefaultPreference.setOnPreferenceClickListener(this);

        if(mStrictPreference != null)
            mStrictPreference.setOnPreferenceClickListener(this);

        setTrackingRadio();

        tintIcons();

    }


    private void setTrackingRadio() {
        if(mSharedPreferences.getBoolean(Preferences.SETTINGS_ANTI_TRACKING_DEFAULT, true)){
            mDefaultPreference.setChecked(true);
        }else if(mSharedPreferences.getBoolean(Preferences.SETTINGS_ANTI_TRACKING_STRICT, false)){
            mStrictPreference.setChecked(true);
        }else{
            mDefaultPreference.setChecked(true);
        }
    }



    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
        if((Preferences.SETTINGS_ANTI_TRACKING_DEFAULT).equals(preference.getKey())){
            mDefaultPreference.toggleRadioButton();
            mGeckoRuntimeHelper.getGeckoRuntime().getSettings().getContentBlocking().setAntiTracking(ContentBlocking.AntiTracking.DEFAULT);
            mGeckoRuntimeHelper.getGeckoRuntime().getSettings().getContentBlocking().setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT);
        }else if(Preferences.SETTINGS_ANTI_TRACKING_STRICT.equals(preference.getKey())){
            mStrictPreference.toggleRadioButton();
            mGeckoRuntimeHelper.getGeckoRuntime().getSettings().getContentBlocking().setAntiTracking(ContentBlocking.AntiTracking.STRICT | ContentBlocking.AntiTracking.STP);
            mGeckoRuntimeHelper.getGeckoRuntime().getSettings().getContentBlocking().setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT);
        }
        return false;
    }
}
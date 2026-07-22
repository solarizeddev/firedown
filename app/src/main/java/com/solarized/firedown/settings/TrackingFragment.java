package com.solarized.firedown.settings;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.settings.ui.RadioButtonPreference;
import com.solarized.firedown.utils.NavigationUtils;
import dagger.hilt.android.AndroidEntryPoint;
import org.mozilla.geckoview.ContentBlocking;


@AndroidEntryPoint
public class TrackingFragment extends BasePreferenceFragment implements Preference.OnPreferenceClickListener {

    private static final String TAG = TrackingFragment.class.getName();

    private static final String KEY_STRIP_LIST_NAV =
            "com.solarized.firedown.preferences.browser.tracking.strip.list.nav";

    private RadioButtonPreference mDefaultPreference;

    private RadioButtonPreference mStrictPreference;

    private RadioButtonPreference mCustomPreference;

    private Preference mStripListNavPreference;


    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_tracking, rootKey);

        mDefaultPreference = getPreferenceScreen().findPreference(Preferences.SETTINGS_ANTI_TRACKING_DEFAULT);

        mStrictPreference = getPreferenceScreen().findPreference(Preferences.SETTINGS_ANTI_TRACKING_STRICT);

        mCustomPreference = getPreferenceScreen().findPreference(Preferences.SETTINGS_ANTI_TRACKING_CUSTOM);

        mStripListNavPreference = getPreferenceScreen().findPreference(KEY_STRIP_LIST_NAV);

        mDefaultPreference.addToRadioGroup(mStrictPreference);
        mDefaultPreference.addToRadioGroup(mCustomPreference);
        mStrictPreference.addToRadioGroup(mDefaultPreference);
        mStrictPreference.addToRadioGroup(mCustomPreference);
        mCustomPreference.addToRadioGroup(mDefaultPreference);
        mCustomPreference.addToRadioGroup(mStrictPreference);

        if(mDefaultPreference != null)
            mDefaultPreference.setOnPreferenceClickListener(this);

        if(mStrictPreference != null)
            mStrictPreference.setOnPreferenceClickListener(this);

        if(mCustomPreference != null)
            mCustomPreference.setOnPreferenceClickListener(this);

        if(mStripListNavPreference != null) {
            mStripListNavPreference.setOnPreferenceClickListener(p -> {
                NavigationUtils.navigateSafe(mNavController, R.id.action_tracking_to_query_params);
                return true;
            });
        }

        setTrackingRadio();

        tintIcons();

    }


    private void setTrackingRadio() {
        if(mSharedPreferences.getBoolean(Preferences.SETTINGS_ANTI_TRACKING_STRICT, false)){
            mStrictPreference.setChecked(true);
        }else if(mSharedPreferences.getBoolean(Preferences.SETTINGS_ANTI_TRACKING_CUSTOM, false)){
            mCustomPreference.setChecked(true);
        }else{
            mDefaultPreference.setChecked(true);
        }
        updateStripListEnabled();
    }


    private void updateStripListEnabled() {
        if(mStripListNavPreference != null) {
            mStripListNavPreference.setEnabled(
                    mSharedPreferences.getBoolean(Preferences.SETTINGS_ANTI_TRACKING_CUSTOM, false));
        }
    }


    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
        ContentBlocking.Settings cb = mGeckoRuntimeHelper.getGeckoRuntime().getSettings().getContentBlocking();
        if((Preferences.SETTINGS_ANTI_TRACKING_DEFAULT).equals(preference.getKey())){
            mDefaultPreference.toggleRadioButton();
            cb.setAntiTracking(ContentBlocking.AntiTracking.DEFAULT);
            cb.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT);
            cb.setQueryParameterStrippingEnabled(false);
            cb.setQueryParameterStrippingPrivateBrowsingEnabled(false);
        }else if(Preferences.SETTINGS_ANTI_TRACKING_STRICT.equals(preference.getKey())){
            mStrictPreference.toggleRadioButton();
            cb.setAntiTracking(ContentBlocking.AntiTracking.STRICT | ContentBlocking.AntiTracking.STP);
            cb.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT);
            cb.setQueryParameterStrippingEnabled(false);
            cb.setQueryParameterStrippingPrivateBrowsingEnabled(false);
        }else if(Preferences.SETTINGS_ANTI_TRACKING_CUSTOM.equals(preference.getKey())){
            mCustomPreference.toggleRadioButton();
            cb.setAntiTracking(ContentBlocking.AntiTracking.DEFAULT);
            cb.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT);
            cb.setQueryParameterStrippingEnabled(true);
            cb.setQueryParameterStrippingPrivateBrowsingEnabled(true);
            cb.setQueryParameterStrippingStripList(
                    Preferences.getQueryParameterStripList(mSharedPreferences));
        }
        updateStripListEnabled();
        return false;
    }
}

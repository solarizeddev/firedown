package com.solarized.firedown.settings;


import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.preference.Preference;

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.App;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;


public class AboutFragment extends BasePreferenceFragment implements Preference.OnPreferenceClickListener {

    private static final String TAG = AboutFragment.class.getName();


    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_about, rootKey);

        Preference versionPreference = getPreferenceManager().findPreference(Preferences.SETTINGS_VERSION);

        if(versionPreference != null) {
            versionPreference.setSummary(App.getVersionName());
            versionPreference.setOnPreferenceClickListener(this);
        }

        Preference geckoVersion = getPreferenceManager().findPreference(Preferences.SETTINGS_GECKO);

        if(geckoVersion != null){
            geckoVersion.setSummary(String.format("Build #%s", org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION + "-" + org.mozilla.geckoview.BuildConfig.MOZ_APP_BUILDID));
            geckoVersion.setOnPreferenceClickListener(this);
        }

        Preference contactPreference = getPreferenceManager().findPreference(Preferences.SETTINGS_CONTACT);

        if(contactPreference != null) {
            contactPreference.setOnPreferenceClickListener(this);
        }

        Preference websitePreference = getPreferenceManager().findPreference(Preferences.SETTINGS_WEBSITE);

        if(websitePreference != null){
            websitePreference.setOnPreferenceClickListener(this);
        }

    }



    @Override
    public boolean onPreferenceClick(Preference preference) {
        if(preference.getKey().equals(Preferences.SETTINGS_VERSION)){
            ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("version", String.valueOf(App.getVersionCode()));
            if(clipboard != null){
                clipboard.setPrimaryClip(clip);
            }
            Snackbar snackbar = Snackbar.make(mActivity.getSnackAnchorView(), R.string.clipboard, Snackbar.LENGTH_LONG);
            snackbar.show();
        } else if (preference.getKey().equals(Preferences.SETTINGS_GECKO)) {
            Intent resultIntent = new Intent(IntentActions.OPEN_URI);
            String uri = getString(R.string.settings_mozilla_geckoview);
            resultIntent.putExtra(Keys.ITEM_URL, uri);
            mActivity.setResult(Activity.RESULT_OK, resultIntent);
            mActivity.finish();
        }

        return false;
    }


}
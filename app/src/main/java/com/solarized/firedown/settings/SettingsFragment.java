package com.solarized.firedown.settings;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.autofill.AutofillManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.App;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.utils.NavigationUtils;

import org.mozilla.geckoview.ContentBlocking;

import dagger.hilt.android.AndroidEntryPoint;


@AndroidEntryPoint
public class SettingsFragment extends BasePreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener,
        Preference.OnPreferenceClickListener {

    private static final String TAG = SettingsFragment.class.getName();

    private static final int QUIT_DELAY = 2000;

    private Preference cookiePreference;
    private Preference dohPreference;
    private Preference searchPreference;
    private Preference downloadsPreference;
    private Preference quitPreference;
    private Preference themePreference;
    private Preference tabsPreference;
    private Preference autoFillPreference;

    private GeckoStateViewModel mGeckoStateViewModel;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mGeckoStateViewModel = new ViewModelProvider(this).get(GeckoStateViewModel.class);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.settings, rootKey);
        PreferenceScreen preferenceScreen = getPreferenceScreen();
        dohPreference = preferenceScreen.findPreference(Preferences.SETTINGS_DOH_PREF);
        cookiePreference = preferenceScreen.findPreference(Preferences.SETTINGS_COOKIES);
        searchPreference = preferenceScreen.findPreference(Preferences.SETTINGS_SEARCH_ENGINE);
        downloadsPreference = preferenceScreen.findPreference(Preferences.SETTINGS_DOWNLOADS);
        themePreference = preferenceScreen.findPreference(Preferences.SETTINGS_THEME);
        quitPreference = preferenceScreen.findPreference(Preferences.SETTINGS_QUIT);
        tabsPreference = preferenceScreen.findPreference(Preferences.SETTINGS_TABS);
        autoFillPreference = preferenceScreen.findPreference(Preferences.SETTINGS_AUTOFILL);

        if (downloadsPreference != null) {
            downloadsPreference.setSummary(StoragePaths.getDownloadPath(mActivity));
        }

        setListenersRecursively(preferenceScreen);

        tintIcons();

        updateSummaries();

        updateTabsSummary();
    }


    private void setListenersRecursively(PreferenceGroup group) {
        int count = group.getPreferenceCount();

        for (int i = 0; i < count; i++) {
            Preference pref = group.getPreference(i);

            if (pref instanceof PreferenceGroup) {
                // If it's a category or another screen, dive deeper
                setListenersRecursively((PreferenceGroup) pref);
            } else {
                // It's a leaf preference, set the listener
                pref.setOnPreferenceClickListener(this);
            }
        }
    }


    private void updateTabsSummary() {
        if (tabsPreference == null) return;

        boolean enabled = mSharedPreferences.getBoolean(
                Preferences.SETTINGS_TABS_ARCHIVE, true);

        if (!enabled) {
            tabsPreference.setSummary(R.string.close_tabs_manually);
            return;
        }

        long interval = mSharedPreferences.getLong(
                Preferences.SETTINGS_TABS_ARCHIVE_INTERVAL,
                Preferences.ONE_WEEK_INTERVAL);

        int resId;
        if (interval == Preferences.ONE_DAY_INTERVAL) {
            resId = R.string.close_tabs_after_one_day;
        } else if (interval == Preferences.ONE_WEEK_INTERVAL) {
            resId = R.string.close_tabs_after_one_week;
        } else if (interval == Preferences.THIRTY_DAYS_INTERVAL) {
            resId = R.string.close_tabs_after_one_month;
        } else {
            // NEVER_INTERVAL (-1) or any unrecognised value
            resId = R.string.close_tabs_manually;
        }
        tabsPreference.setSummary(resId);
    }

    private void updateSummaries() {

        if(autoFillPreference != null){
            Boolean enabled = isSystemAutofillActive();
            if (enabled == null) {
                autoFillPreference.setSummary(R.string.settings_autofill_not_supported);
            } else if (enabled) {
                autoFillPreference.setSummary(R.string.settings_autofill_active);
            } else {
                autoFillPreference.setSummary(R.string.settings_autofill_none_configured);
            }
        }

        if (quitPreference != null) {
            boolean enabled = mSharedPreferences.getBoolean(Preferences.SETTINGS_QUIT_PREF, false);
            quitPreference.setSummary(enabled
                    ? R.string.delete_browsing_data_quit_on
                    : R.string.delete_browsing_data_quit_off);
        }

        String searchValue = mSharedPreferences.getString(
                Preferences.SETTINGS_SEARCH_ENGINE, Preferences.DEFAULT_SEARCH_ENGINE);

        if (searchPreference != null)
            searchPreference.setSummary(searchValue);

        if (dohPreference != null) {
            boolean enabled = mSharedPreferences.getBoolean(Preferences.SETTINGS_DOH_SWITCH, false);
            if (enabled) {
                String dohValue = mSharedPreferences.getString(
                        Preferences.SETTINGS_DOH, Preferences.DEFAULT_SETTINGS_DOH);
                switch (dohValue) {
                    case "0":
                        dohPreference.setSummary(R.string.settings_doh_server_mozilla);
                        break;
                    case "1":
                        dohPreference.setSummary(R.string.settings_doh_server_cloudflare);
                        break;
                    case "2":
                        dohPreference.setSummary(R.string.settings_doh_server_google);
                        break;
                    case "3":
                        dohPreference.setSummary(R.string.settings_doh_server_quad9);
                        break;
                    case "4":
                        dohPreference.setSummary(R.string.settings_doh_server_adguard);
                        break;
                    case "5":
                        dohPreference.setSummary(R.string.settings_doh_server_custom);
                        break;
                    default:
                        dohPreference.setSummary(R.string.settings_doh_server_off);
                        break;
                }
            } else {
                dohPreference.setSummary(R.string.settings_doh_server_off);
            }
        }

        if (cookiePreference != null) {
            String cookieValue = mSharedPreferences.getString(
                    Preferences.SETTINGS_COOKIES, Preferences.DEFAULT_SETTINGS_COOKIES);

            int resId = switch (cookieValue) {
                case "3" -> R.string.settings_cookies_accept_visited;
                case "1" -> R.string.settings_cookies_accept_first_party;
                case "4" -> R.string.settings_cookies_accept_non_tracking;
                case "5" -> R.string.settings_cookies_isolate;
                case "2" -> R.string.settings_cookies_accept_none;
                default -> R.string.settings_cookies_accept_non_tracking;
            };

            cookiePreference.setSummary(resId);
        }

        if (themePreference != null) {

            int themeId = mSharedPreferences.getInt(
                    Preferences.SETTINGS_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

            if (themeId == AppCompatDelegate.MODE_NIGHT_YES) {
                themePreference.setSummary(R.string.settings_dark_theme);
            } else if (themeId == AppCompatDelegate.MODE_NIGHT_NO) {
                themePreference.setSummary(R.string.settings_light_theme);
            } else {
                themePreference.setSummary(R.string.settings_follow_device_theme);
            }
        }
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        cookiePreference = null;
        dohPreference = null;
        searchPreference = null;
        downloadsPreference = null;
        quitPreference = null;
        themePreference = null;
        tabsPreference = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateSummaries();
        SharedPreferences sharedPreferences = mSharedPreferences;
        if (sharedPreferences != null)
            sharedPreferences.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        SharedPreferences sharedPreferences = mSharedPreferences;
        if (sharedPreferences != null)
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
    }


    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {

        if (Preferences.SETTINGS_BLOCK_JAVASCRIPT.equals(key)) {

            boolean value = sharedPreferences.getBoolean(key, true);

            mGeckoRuntimeHelper.getGeckoRuntime().getSettings().setJavaScriptEnabled(!value);

            GeckoState geckoState = mGeckoStateViewModel.getCurrentGeckoState();

            if (geckoState != null) {
                geckoState.closeGeckoSession();
            }

        } else if (Preferences.SETTINGS_BLOCK_COOKIE_NOTICES.equals(key)) {

            boolean value = sharedPreferences.getBoolean(key, false);

            mGeckoRuntimeHelper.setCookies(value);

            // firedown.js' toggleCookieNotices already reloads the active
            // tab after applyFilterListSelection + loadFilterLists. Skip the
            // generic fall-through reload below to avoid a race between the
            // two reloads while uBO is still recompiling cosmetic rules.
            return;

        } else if (Preferences.SETTINGS_COOKIES.equals(key)) {

            String cookieValue = sharedPreferences.getString(key, Preferences.DEFAULT_SETTINGS_COOKIES);

            int cookieBehavior = switch (cookieValue) {
                case "3" -> ContentBlocking.CookieBehavior.ACCEPT_VISITED;
                case "1" -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY;
                case "4" -> ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS;
                case "5" -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS;
                case "2" -> ContentBlocking.CookieBehavior.ACCEPT_NONE;
                default -> ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS;
            };

            mGeckoRuntimeHelper.getGeckoRuntime().getSettings()
                    .getContentBlocking().setCookieBehavior(cookieBehavior);

            updateSummaries();

        } else if (Preferences.SETTINGS_SEARCH_ENGINE.equals(key)) {

            String searchValue = sharedPreferences.getString(key, Preferences.DEFAULT_SEARCH_ENGINE);

            if (searchPreference != null)
                searchPreference.setSummary(searchValue);

        } else if (Preferences.SETTINGS_DOWNLOADS.equals(key)) {

            int value = sharedPreferences.getInt(key, Preferences.DEFAULT_DOWNLOADS);

            if (downloadsPreference != null)
                downloadsPreference.setSummary(value == 0
                        ? StoragePaths.getDownloadPath(mActivity)
                        : StoragePaths.getSDCardPath(mActivity));

        } else if (Preferences.SETTINGS_ENABLE_WEBRTC.equals(key)) {

            boolean value = sharedPreferences.getBoolean(key, false);

            mGeckoRuntimeHelper.setWebRTC(value);

        } else if (Preferences.SETTINGS_DISABLE_WEBGL.equals(key)) {

            boolean value = sharedPreferences.getBoolean(key, false);

            mGeckoRuntimeHelper.setWebGL(value);

        } else if (Preferences.SETTINGS_ENABLE_JIT.equals(key)) {

            boolean value = sharedPreferences.getBoolean(key, false);

            mGeckoRuntimeHelper.setJITCompiler(value);

            Snackbar snackbar = Snackbar.make(
                    mActivity.getSnackAnchorView(),
                    getString(R.string.quit_application),
                    Snackbar.LENGTH_LONG);

            snackbar.show();

            // Your code to run after the delay
            new Handler(Looper.getMainLooper()).postDelayed(App::quitAndRestart, QUIT_DELAY);

            // App.triggerRebirth(mActivity);

        } else if (Preferences.SETTINGS_ENABLE_RESIST_FINGERPRINTING.equals(key)) {

            boolean value = sharedPreferences.getBoolean(key, false);

            mGeckoRuntimeHelper.setResistFingerPrinting(value);
        } else if (Preferences.SETTINGS_BLOCK_LOCATION.equals(key)) {

            boolean block = sharedPreferences.getBoolean(key, false);

            mGeckoRuntimeHelper.setGeo(block);

        }

        GeckoState geckoState = mGeckoStateViewModel.getCurrentGeckoState();

        if (geckoState != null) {
            geckoState.reload();
        }
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {

        final String key = preference.getKey();

        switch (key) {
            case Preferences.SETTINGS_CLEAR_DATA ->
                    NavigationUtils.navigateSafe(mNavController, R.id.dialog_delete_browsing);
            case Preferences.SETTINGS_ABOUT ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_settings_to_about);
            case Preferences.SETTINGS_THEME ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_settings_to_theme);
            case Preferences.SETTINGS_LICENSE ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_settings_to_license);
            case Preferences.SETTINGS_DOWNLOADS ->
                    NavigationUtils.navigateSafe(mNavController, R.id.dialog_downloads_free);
            case Preferences.SETTINGS_DOH_PREF ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_settings_to_doh);
            case Preferences.SETTINGS_ANTI_TRACKING ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_settings_to_tracking);
            case Preferences.SETTINGS_APP_LOCK_MAIN ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_settings_to_lock);
            case Preferences.SETTINGS_SEARCH_ENGINE ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_settings_to_search);
            case Preferences.SETTINGS_QUIT ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_settings_to_quit);
            case Preferences.SETTINGS_TABS ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_settings_to_tabs);
            case Preferences.SETTINGS_DONATE ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_settings_to_donate);
            case Preferences.SETTINGS_AUTOFILL ->
                openAutofillSettings();
            case Preferences.SETTINGS_SUPPORT -> {
                Intent resultIntent = new Intent(IntentActions.OPEN_URI);
                String uri = getString(R.string.settings_solarized_support);
                resultIntent.putExtra(Keys.ITEM_URL, uri);
                mActivity.setResult(Activity.RESULT_OK, resultIntent);
                mActivity.finish();
            }
        }
        return false;
    }



    @Nullable
    private Boolean isSystemAutofillActive() {
        AutofillManager afm = requireContext().getSystemService(AutofillManager.class);
        if (afm == null || !afm.isAutofillSupported()) return null;
        return afm.isEnabled();
    }

    private void openAutofillSettings() {
        Intent intent;

        intent = new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE);
        intent.setData(Uri.parse("package:" + mActivity.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(mActivity.getPackageManager()) == null) {
            // Some OEMs ship without this intent resolving — Huawei
            // and a few Chinese forks have been spotted missing it.
            intent = null;
        }

        // Fallback 1: general Settings, user navigates to the autofill
        // section themselves. Works on older Android and the rare
        // OEM that strips the autofill intent.
        if (intent == null) {
            intent = new Intent(Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // Shouldn't happen — ACTION_SETTINGS is universally
            // supported — but log just in case.
            Log.w(TAG, "Could not open settings", e);
            View anchor = requireView();
            Snackbar.make(anchor, R.string.error_open_settings,
                    Snackbar.LENGTH_LONG).show();
        }
    }
}
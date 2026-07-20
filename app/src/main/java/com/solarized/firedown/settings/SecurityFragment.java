package com.solarized.firedown.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.Preference;

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.App;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.utils.NavigationUtils;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Settings sub-screen for the harden-at-a-cost toggles (Block JavaScript,
 * Disable JIT, Enable DRM, Disable WebGL) plus the WebAssembly door — moved
 * off the root Settings list so the once-ever expert switches stop occupying
 * prime scroll estate (frequency-first, the chip-rail convention applied to
 * Settings).
 *
 * <p>Runtime application happens HERE, not in SettingsFragment: its
 * SharedPreferenceChangeListener is unregistered while this fragment is in
 * the foreground (the WasmFragment pattern), so each toggle is applied to
 * Gecko by this fragment's own listener. SettingsFragment keeps its matching
 * branches as the defensive twin — only one listener is registered at a time,
 * so a change can never double-apply.</p>
 */
@AndroidEntryPoint
public class SecurityFragment extends BasePreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final int QUIT_DELAY = 2000;

    private GeckoStateViewModel mGeckoStateViewModel;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mGeckoStateViewModel = new ViewModelProvider(this).get(GeckoStateViewModel.class);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.settings_security, rootKey);

        Preference wasm = findPreference(Preferences.SETTINGS_WASM);
        if (wasm != null) {
            wasm.setOnPreferenceClickListener(p -> {
                NavigationUtils.navigateSafe(mNavController, R.id.action_security_to_wasm);
                return true;
            });
        }

        tintIcons();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mSharedPreferences != null) {
            mSharedPreferences.registerOnSharedPreferenceChangeListener(this);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mSharedPreferences != null) {
            mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
        }
    }

    /**
     * Mirrors the matching branches in {@code SettingsFragment
     * .onSharedPreferenceChanged} — same semantics, incl. the JS
     * session-close, the JIT restart snackbar, and the fall-through reload of
     * the current tab. Keep the two in lockstep.
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {

        if (Preferences.SETTINGS_BLOCK_JAVASCRIPT.equals(key)) {

            boolean value = sharedPreferences.getBoolean(key, true);

            mGeckoRuntimeHelper.getGeckoRuntime().getSettings().setJavaScriptEnabled(!value);

            GeckoState geckoState = mGeckoStateViewModel.getCurrentGeckoState();

            if (geckoState != null) {
                geckoState.closeGeckoSession();
            }

        } else if (Preferences.SETTINGS_DISABLE_WEBGL.equals(key)) {

            boolean value = sharedPreferences.getBoolean(key, false);

            mGeckoRuntimeHelper.setWebGL(value);

        } else if (Preferences.SETTINGS_ENABLE_DRM.equals(key)) {

            boolean enabled = sharedPreferences.getBoolean(key, false);

            mGeckoRuntimeHelper.setDRM(!enabled);

        } else if (Preferences.SETTINGS_DISABLE_JIT.equals(key)) {

            boolean disabled = sharedPreferences.getBoolean(key, Preferences.DEFAULT_DISABLE_JIT);

            mGeckoRuntimeHelper.setJITCompiler(!disabled);

            Snackbar snackbar = Snackbar.make(
                    mActivity.getSnackAnchorView(),
                    getString(R.string.quit_application),
                    Snackbar.LENGTH_LONG);

            snackbar.show();

            new Handler(Looper.getMainLooper()).postDelayed(App::quitAndRestart, QUIT_DELAY);

        } else {
            // Not one of this screen's toggles (e.g. the WASM switch lives in
            // its own sub-screen) — don't reload the tab for foreign writes.
            return;
        }

        GeckoState geckoState = mGeckoStateViewModel.getCurrentGeckoState();

        if (geckoState != null) {
            geckoState.reload();
        }
    }
}

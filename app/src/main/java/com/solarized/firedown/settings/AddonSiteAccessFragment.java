package com.solarized.firedown.settings;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavDestination;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.addons.AddonInfo;
import com.solarized.firedown.addons.AddonManager;
import com.solarized.firedown.addons.AddonPermissions;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * One add-on's site access, Firefox for Android's shape: one "Allow on all
 * websites" switch for every all-sites pattern, then a switch per host,
 * declared or granted while the add-on ran (an add-on declaring all sites
 * asks for one site at a time, and those grants are in no manifest). Its
 * own screen because the list can run to hundreds of rows: on the add-on's
 * page it pushed Remove out of reach. The host alone is the title. While
 * all sites is allowed the site switches are disabled and keep their own
 * state: turning one off would not stop a site the all-sites grant covers.
 * Rows are kept and updated in place, so a site granted at run time adds
 * one row, not a rebuild of hundreds.
 */
@AndroidEntryPoint
public class AddonSiteAccessFragment extends BasePreferenceFragment {

    private static final String KEY_ALL_SITES = "com.solarized.firedown.preferences.addon.sites.all";
    private static final String SITE_PREFIX = "com.solarized.firedown.preferences.addon.site.";
    /** PreferenceFragmentCompat's key for its rows' saved state. */
    private static final String PREFERENCES_STATE = "android:preferences";

    @Inject
    AddonManager mAddonManager;

    private String mId;
    private PreferenceScreen mSites;
    /** Each switch's patterns, by key, as of the last bind; its listener reads them here. */
    private final Map<String, List<String>> mSitePatterns = new HashMap<>();

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        mSites = getPreferenceManager().createPreferenceScreen(requireContext());
        mSites.setOrderingAsAdded(false);
        setPreferenceScreen(mSites);
        mId = getArguments() == null ? null : getArguments().getString(Keys.ADDON_ID);
        // Drawn with its rows in the first frame (the add-on page's rule).
        AddonInfo info = mId == null ? null : mAddonManager.find(mId);
        if (info != null) {
            bind(info);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mAddonManager.getAddons().observe(getViewLifecycleOwner(), addons -> {
            AddonInfo info = mAddonManager.find(mId);
            boolean loaded = Boolean.TRUE.equals(mAddonManager.getLoaded().getValue());
            if (info == null) {
                // Removed elsewhere: its page closes with this one, in one
                // step (popping only this would slide the stale page in).
                if (loaded && isCurrentDestination()) {
                    mNavController.popBackStack(R.id.settings_addon_detail, true);
                }
                return;
            }
            bind(info);
            setSubtitle(info.name);
            // Nothing left to switch (an update dropped every optional site):
            // back to the add-on page, whose door hides, not a blank screen.
            if (loaded && mSites.getPreferenceCount() == 0 && isCurrentDestination()) {
                mNavController.popBackStack();
            }
        });
    }

    /** The toolbar says "Site access"; the subtitle says whose. */
    @Override
    public void onStart() {
        super.onStart();
        AddonInfo info = mId == null ? null : mAddonManager.find(mId);
        setSubtitle(info == null ? null : info.name);
    }

    /** No other settings screen has a subtitle, so it leaves with this one. */
    @Override
    public void onStop() {
        setSubtitle(null);
        super.onStop();
    }

    private void setSubtitle(@Nullable CharSequence subtitle) {
        if (!(getActivity() instanceof AppCompatActivity)) {
            return;
        }
        ActionBar bar = ((AppCompatActivity) getActivity()).getSupportActionBar();
        if (bar != null) {
            bar.setSubtitle(subtitle);
        }
    }

    private boolean isCurrentDestination() {
        NavDestination current = mNavController.getCurrentDestination();
        return current != null && current.getId() == R.id.settings_addon_sites;
    }

    private void bind(@NonNull AddonInfo info) {
        if (!isAdded()) {
            return;
        }
        AddonPermissions.SiteAccess access =
                AddonPermissions.siteAccess(info.optionalOrigins, info.grantedOptionalOrigins);
        Map<String, Preference> existing = new HashMap<>();
        for (int i = 0; i < mSites.getPreferenceCount(); i++) {
            Preference p = mSites.getPreference(i);
            existing.put(p.getKey(), p);
        }
        mSitePatterns.clear();
        int order = 0;
        if (!access.allSitesPatterns.isEmpty()) {
            SwitchPreferenceCompat all = siteSwitch(existing, KEY_ALL_SITES);
            all.setTitle(R.string.addon_allow_all_sites);
            all.setSummary(R.string.addon_allow_all_sites_summary);
            all.setOrder(order++);
            all.setChecked(access.allSitesGranted);
            mSitePatterns.put(KEY_ALL_SITES, access.allSitesPatterns);
        }
        for (AddonPermissions.Site site : access.sites) {
            String key = SITE_PREFIX + site.label.toLowerCase(Locale.ROOT);
            SwitchPreferenceCompat sw = siteSwitch(existing, key);
            sw.setTitle(site.label);
            sw.setOrder(order++);
            sw.setChecked(site.granted);
            sw.setEnabled(!access.allSitesGranted);
            mSitePatterns.put(key, site.patterns);
        }
        for (Map.Entry<String, Preference> stale : existing.entrySet()) {
            if (!mSitePatterns.containsKey(stale.getKey())) {
                mSites.removePreference(stale.getValue());
            }
        }
    }

    /**
     * Without the switches' state: one entry per keyed row, hundreds of
     * them, is the size that ends in a TransactionTooLargeException, and
     * {@link #bind} rebuilds every row from the add-on anyway.
     */
    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.remove(PREFERENCES_STATE);
    }

    /** The site switch with this key, made and added the first time. */
    @NonNull
    private SwitchPreferenceCompat siteSwitch(@NonNull Map<String, Preference> existing, @NonNull String key) {
        Preference found = existing.get(key);
        if (found instanceof SwitchPreferenceCompat) {
            return (SwitchPreferenceCompat) found;
        }
        SwitchPreferenceCompat sw = new SwitchPreferenceCompat(requireContext());
        sw.setKey(key);
        sw.setPersistent(false);
        sw.setSingleLineTitle(false);
        sw.setIconSpaceReserved(false);
        sw.setOnPreferenceChangeListener((p, value) -> {
            List<String> patterns = mSitePatterns.get(p.getKey());
            // setChecked in bind does not call this: only the user's taps do.
            if (mId != null && patterns != null) {
                mAddonManager.setOptionalOrigins(mId, patterns, Boolean.TRUE.equals(value));
            }
            return true;
        });
        mSites.addPreference(sw);
        return sw;
    }
}

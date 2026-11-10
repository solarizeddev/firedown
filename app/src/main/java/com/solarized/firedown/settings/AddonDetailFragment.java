package com.solarized.firedown.settings;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.addons.AddonIcons;
import com.solarized.firedown.addons.AddonInfo;
import com.solarized.firedown.addons.AddonManager;
import com.solarized.firedown.addons.AddonPermissionText;
import com.solarized.firedown.addons.AddonPermissions;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * One installed add-on: on/off, private tabs, its settings page, an update
 * waiting for the user, its page, what it may do, what it collects, Remove. Follows
 * {@link AddonManager#getAddons()}, so a change made anywhere (the add-on
 * updated, Gecko blocked it) repaints here, and a removed add-on closes it.
 */
@AndroidEntryPoint
public class AddonDetailFragment extends BasePreferenceFragment {

    private static final String KEY_HEADER = "com.solarized.firedown.preferences.addon.header";
    private static final String KEY_ENABLED = "com.solarized.firedown.preferences.addon.enabled";
    private static final String KEY_PRIVATE = "com.solarized.firedown.preferences.addon.private";
    private static final String KEY_SHIELD = "com.solarized.firedown.preferences.addon.shield";
    private static final String KEY_OPTIONS = "com.solarized.firedown.preferences.addon.options";
    private static final String KEY_REVIEW = "com.solarized.firedown.preferences.addon.review";
    private static final String KEY_UPDATE = "com.solarized.firedown.preferences.addon.update";
    private static final String KEY_PAGE = "com.solarized.firedown.preferences.addon.page";
    private static final String KEY_PERMISSIONS = "com.solarized.firedown.preferences.addon.permissions.category";
    private static final String PERMISSION_PREFIX = "com.solarized.firedown.preferences.addon.permission.";
    private static final String KEY_DATA_CATEGORY = "com.solarized.firedown.preferences.addon.data.category";
    private static final String KEY_DATA = "com.solarized.firedown.preferences.addon.data";
    private static final String KEY_REMOVE = "com.solarized.firedown.preferences.addon.remove";
    private static final String KEY_OPTIONAL = "com.solarized.firedown.preferences.addon.optional.category";
    private static final String OPTIONAL_PREFIX = "com.solarized.firedown.preferences.addon.optional.";
    private static final String KEY_SITES = "com.solarized.firedown.preferences.addon.sites.category";
    private static final String KEY_ALL_SITES = "com.solarized.firedown.preferences.addon.sites.all";
    private static final String SITE_PREFIX = "com.solarized.firedown.preferences.addon.site.";
    private static final String USER_SCRIPTS = "userScripts";

    @Inject
    AddonManager mAddonManager;

    private String mId;
    private AddonHeaderPreference mHeader;
    private SwitchPreferenceCompat mEnabled;
    private SwitchPreferenceCompat mPrivate;
    private SwitchPreferenceCompat mShield;
    private Preference mOptions;
    private Preference mReview;
    private Preference mUpdate;
    private Preference mPage;
    private PreferenceCategory mPermissions;
    /** The permission rows now shown, so a repaint only rebuilds when they change. */
    private List<String> mPermissionTexts = new ArrayList<>();
    private PreferenceCategory mDataCategory;
    private Preference mData;
    private int mIconInk;
    private PreferenceCategory mOptional;
    /** The rows now in {@link #mOptional}, so a repaint only rebuilds when they change. */
    private List<String> mOptionalKeys = new ArrayList<>();
    private PreferenceCategory mSites;
    /** Each site switch's patterns, by key, as of the last bind; its listener reads them here. */
    private final Map<String, List<String>> mSitePatterns = new HashMap<>();
    private boolean mIconLoaded;
    private boolean mBinding;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.settings_addon_detail, rootKey);
        mId = getArguments() == null ? null : getArguments().getString(Keys.ADDON_ID);

        mHeader = findPreference(KEY_HEADER);
        mEnabled = findPreference(KEY_ENABLED);
        mPrivate = findPreference(KEY_PRIVATE);
        mShield = findPreference(KEY_SHIELD);
        mOptions = findPreference(KEY_OPTIONS);
        mReview = findPreference(KEY_REVIEW);
        mUpdate = findPreference(KEY_UPDATE);
        mPage = findPreference(KEY_PAGE);
        mPermissions = findPreference(KEY_PERMISSIONS);
        mDataCategory = findPreference(KEY_DATA_CATEGORY);
        mData = findPreference(KEY_DATA);
        mOptional = findPreference(KEY_OPTIONAL);
        mSites = findPreference(KEY_SITES);
        if (mSites != null) {
            mSites.setOrderingAsAdded(false);
        }
        mIconInk = MaterialColors.getColor(requireContext(), android.R.attr.textColorSecondary, Color.GRAY);
        if (mData != null && mData.getIcon() != null) {
            mData.getIcon().mutate().setTint(mIconInk);
        }
        Preference remove = findPreference(KEY_REMOVE);

        if (mEnabled != null) {
            mEnabled.setOnPreferenceChangeListener((p, value) -> {
                if (!mBinding && mId != null) {
                    mAddonManager.setEnabled(mId, Boolean.TRUE.equals(value));
                }
                return true;
            });
        }
        if (mPrivate != null) {
            mPrivate.setOnPreferenceChangeListener((p, value) -> {
                if (!mBinding && mId != null) {
                    mAddonManager.setAllowedInPrivate(mId, Boolean.TRUE.equals(value));
                }
                return true;
            });
        }
        if (mShield != null) {
            mShield.setOnPreferenceChangeListener((p, value) -> {
                if (!mBinding && mId != null) {
                    mAddonManager.setShowBehindShield(mId, Boolean.TRUE.equals(value));
                }
                return true;
            });
        }
        if (mOptions != null) {
            mOptions.setOnPreferenceClickListener(p -> {
                Bundle args = new Bundle();
                args.putString(Keys.ADDON_ID, mId);
                NavigationUtils.navigateSafe(mNavController, R.id.action_addon_detail_to_options, args);
                return true;
            });
        }
        if (mReview != null) {
            mReview.setOnPreferenceClickListener(p -> {
                mAddonManager.checkForUpdates(true, mId);
                return true;
            });
        }
        if (mUpdate != null) {
            mUpdate.setOnPreferenceClickListener(p -> {
                mAddonManager.checkForUpdates(true, mId);
                return true;
            });
        }
        if (remove != null) {
            remove.setOnPreferenceClickListener(p -> {
                confirmRemove();
                return true;
            });
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mAddonManager.getAddons().observe(getViewLifecycleOwner(), addons -> {
            AddonInfo info = mAddonManager.find(mId);
            if (info == null) {
                // Removed (here or elsewhere): nothing left to show.
                if (Boolean.TRUE.equals(mAddonManager.getLoaded().getValue())) {
                    NavigationUtils.popBackStackSafe(mNavController, R.id.settings_addon_detail);
                }
                return;
            }
            bind(info);
        });
    }

    private void bind(@NonNull AddonInfo info) {
        if (!isAdded()) {
            return;
        }
        ActionBar actionBar = mActivity == null ? null : mActivity.getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(info.name);
        }
        mBinding = true;
        if (mHeader != null) {
            List<String> facts = new ArrayList<>();
            if (!TextUtils.isEmpty(info.creator)) {
                facts.add(getString(R.string.addon_by, info.creator));
            }
            facts.add(getString(R.string.addon_version, info.version));
            mHeader.set(info.name, TextUtils.join("\n", facts), info.description);
            if (!mIconLoaded) {
                mIconLoaded = true;
                int px = getResources().getDimensionPixelSize(R.dimen.addon_prompt_icon_size);
                AddonIcons.load(requireContext(), info.icon, px, icon -> {
                    if (isAdded() && mHeader != null) {
                        mHeader.setAddonIcon(icon);
                    }
                });
            }
        }
        if (mEnabled != null) {
            mEnabled.setChecked(info.enabled);
            mEnabled.setEnabled(!info.disabledBySystem());
        }
        if (mPrivate != null) {
            boolean canRun = info.canRunInPrivate();
            mPrivate.setEnabled(canRun);
            mPrivate.setChecked(canRun && info.allowedInPrivateBrowsing);
            mPrivate.setSummary(canRun ? R.string.addon_private_summary : R.string.addon_private_not_allowed);
        }
        if (mShield != null) {
            // Add-ons with a toolbar button; uBlock Origin is always behind the shield.
            mShield.setVisible(mAddonManager.canChooseShield(info));
            mShield.setChecked(mAddonManager.isBlocker(info));
        }
        if (mOptions != null) {
            mOptions.setVisible(info.optionsPageUrl != null && info.enabled);
        }
        if (mReview != null) {
            mReview.setVisible(mAddonManager.isUpdateWaitingForReview(info.id));
        }
        if (mUpdate != null) {
            mUpdate.setVisible(!mAddonManager.isUpdateWaitingForReview(info.id));
        }
        String page = !TextUtils.isEmpty(info.amoListingUrl) ? info.amoListingUrl : info.homepageUrl;
        if (mPage != null) {
            mPage.setVisible(isWebUrl(page));
            mPage.setOnPreferenceClickListener(p -> {
                if (mActivity != null && isWebUrl(page)) {
                    SettingsActivity.openInBrowser(mActivity, page);
                }
                return true;
            });
        }
        bindPermissions(info);
        if (mDataCategory != null && mData != null) {
            String data = AddonPermissionText.dataCollection(requireContext(), info.requiredDataCollection);
            mDataCategory.setVisible(data != null);
            mData.setTitle(data);
        }
        bindOptional(info);
        bindSites(info);
        mBinding = false;
    }

    /**
     * What it may do, a row each with its glyph (Firefox lists the same
     * lines; a glyph per kind lets the eye find "all websites" among
     * them). Rebuilt only when the lines change: an update adding a
     * permission does, a toggle elsewhere on the page does not.
     */
    private void bindPermissions(@NonNull AddonInfo info) {
        if (mPermissions == null) {
            return;
        }
        List<AddonPermissionText.Row> rows = AddonPermissionText.rows(requireContext(),
                info.requiredPermissions, info.requiredOrigins);
        List<String> texts = new ArrayList<>();
        for (AddonPermissionText.Row row : rows) {
            texts.add(row.text);
        }
        if (texts.equals(mPermissionTexts) && mPermissions.getPreferenceCount() > 0) {
            return;
        }
        mPermissions.removeAll();
        if (rows.isEmpty()) {
            mPermissions.addPreference(permissionRow(0, getString(R.string.addon_permissions_none), 0));
        }
        for (int i = 0; i < rows.size(); i++) {
            AddonPermissionText.Row row = rows.get(i);
            mPermissions.addPreference(permissionRow(i, row.text, row.icon));
        }
        mPermissionTexts = texts;
    }

    @NonNull
    private Preference permissionRow(int index, @NonNull String text, @DrawableRes int icon) {
        Preference p = new Preference(requireContext());
        p.setKey(PERMISSION_PREFIX + index);
        p.setPersistent(false);
        p.setSelectable(false);
        p.setTitle(text);
        p.setSingleLineTitle(false);
        if (icon != 0) {
            Drawable d = AppCompatResources.getDrawable(requireContext(), icon);
            if (d != null) {
                d = d.mutate();
                d.setTint(mIconInk);
                p.setIcon(d);
            }
        } else {
            p.setIconSpaceReserved(false);
        }
        return p;
    }

    /** One optional thing the add-on may ask for: its words, and whether it has it now. */
    private static final class OptionalRow {
        final String key;
        final String title;
        final boolean granted;
        final String permission;
        final String data;

        OptionalRow(String key, String title, boolean granted, String permission, String data) {
            this.key = key;
            this.title = title;
            this.granted = granted;
            this.permission = permission;
            this.data = data;
        }
    }

    /**
     * Firefox's permission switches: each optional API permission or data
     * kind the add-on declares, on when granted. Turning one off takes it
     * back; turning one on grants it without the add-on asking. Sites are
     * their own group ({@link #bindSites}).
     */
    private void bindOptional(@NonNull AddonInfo info) {
        if (mOptional == null) {
            return;
        }
        List<OptionalRow> rows = new ArrayList<>();
        for (String permission : info.optionalPermissions) {
            String title = AddonPermissionText.permission(requireContext(), permission);
            if (title != null) {
                rows.add(new OptionalRow(OPTIONAL_PREFIX + "p." + permission, title,
                        info.grantedOptionalPermissions.contains(permission), permission, null));
            }
        }
        for (String kind : info.optionalDataCollection) {
            String title = AddonPermissionText.data(requireContext(), kind);
            if (title != null) {
                rows.add(new OptionalRow(OPTIONAL_PREFIX + "d." + kind, title,
                        info.grantedOptionalDataCollection.contains(kind), null, kind));
            }
        }
        List<String> keys = new ArrayList<>();
        for (OptionalRow row : rows) {
            keys.add(row.key);
        }
        if (!keys.equals(mOptionalKeys)) {
            mOptional.removeAll();
            for (OptionalRow row : rows) {
                SwitchPreferenceCompat sw = new SwitchPreferenceCompat(requireContext());
                sw.setKey(row.key);
                sw.setPersistent(false);
                sw.setTitle(row.title);
                sw.setSingleLineTitle(false);
                sw.setIconSpaceReserved(false);
                if (USER_SCRIPTS.equals(row.permission)) {
                    // Firefox puts the same warning under this switch.
                    sw.setSummary(R.string.addon_user_scripts_warning);
                }
                sw.setOnPreferenceChangeListener((p, value) -> {
                    if (!mBinding && mId != null) {
                        mAddonManager.setOptionalPermission(mId, row.permission, row.data,
                                Boolean.TRUE.equals(value));
                    }
                    return true;
                });
                mOptional.addPreference(sw);
            }
            mOptionalKeys = keys;
        }
        for (OptionalRow row : rows) {
            SwitchPreferenceCompat sw = mOptional.findPreference(row.key);
            if (sw != null) {
                sw.setChecked(row.granted);
            }
        }
        mOptional.setVisible(!rows.isEmpty());
    }

    /**
     * Site access, Firefox for Android's shape: one "Allow on all websites"
     * switch for every all-sites pattern, then a switch per host, declared
     * or granted while the add-on ran (an add-on declaring all sites asks
     * for one site at a time, and those grants are in no manifest). The
     * host alone is the title: hundreds of "Access your data for sites in
     * the … domain" rows read as a wall. While all sites is allowed the
     * site switches are disabled and keep their own state: turning one off
     * would not stop a site the all-sites grant covers. Rows are kept and
     * updated in place, so a site granted at run time adds one row, not a
     * rebuild of hundreds.
     */
    private void bindSites(@NonNull AddonInfo info) {
        if (mSites == null) {
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
        mSites.setVisible(!mSitePatterns.isEmpty());
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
            if (!mBinding && mId != null && patterns != null) {
                mAddonManager.setOptionalOrigins(mId, patterns, Boolean.TRUE.equals(value));
            }
            return true;
        });
        mSites.addPreference(sw);
        return sw;
    }

    private static boolean isWebUrl(@Nullable String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://"));
    }

    private void confirmRemove() {
        AddonInfo info = mAddonManager.find(mId);
        if (info == null || mActivity == null) {
            return;
        }
        new MaterialAlertDialogBuilder(mActivity)
                .setTitle(getString(R.string.addon_remove_title, info.name))
                .setMessage(R.string.addon_remove_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.addon_remove, (d, w) -> mAddonManager.uninstall(info.id))
                .show();
    }
}

package com.solarized.firedown.settings;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.addons.AddonIcons;
import com.solarized.firedown.addons.AddonInfo;
import com.solarized.firedown.addons.AddonManager;
import com.solarized.firedown.addons.AddonPermissionText;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * One installed add-on: on/off, private tabs, its settings page, an update
 * waiting for the user, what it may do, its page, Remove. Follows
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
    private static final String KEY_PERMISSIONS = "com.solarized.firedown.preferences.addon.permissions";
    private static final String KEY_REMOVE = "com.solarized.firedown.preferences.addon.remove";
    private static final String KEY_OPTIONAL = "com.solarized.firedown.preferences.addon.optional.category";
    private static final String OPTIONAL_PREFIX = "com.solarized.firedown.preferences.addon.optional.";

    @Inject
    AddonManager mAddonManager;

    private String mId;
    private Preference mHeader;
    private SwitchPreferenceCompat mEnabled;
    private SwitchPreferenceCompat mPrivate;
    private SwitchPreferenceCompat mShield;
    private Preference mOptions;
    private Preference mReview;
    private Preference mUpdate;
    private Preference mPage;
    private Preference mPermissions;
    private PreferenceCategory mOptional;
    /** The rows now in {@link #mOptional}, so a repaint only rebuilds when they change. */
    private List<String> mOptionalKeys = new ArrayList<>();
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
        mOptional = findPreference(KEY_OPTIONAL);
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
            mHeader.setTitle(info.name);
            List<String> facts = new ArrayList<>();
            if (!TextUtils.isEmpty(info.creator)) {
                facts.add(getString(R.string.addon_by, info.creator));
            }
            facts.add(getString(R.string.addon_version, info.version));
            String summary = TextUtils.join(" · ", facts);
            if (!info.description.isEmpty()) {
                summary = info.description + "\n" + summary;
            }
            mHeader.setSummary(summary);
            if (!mIconLoaded) {
                mIconLoaded = true;
                int px = getResources().getDimensionPixelSize(R.dimen.addon_icon_size);
                AddonIcons.load(requireContext(), info.icon, px, icon -> {
                    if (isAdded() && mHeader != null) {
                        mHeader.setIcon(icon);
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
        if (mPermissions != null) {
            List<String> lines = AddonPermissionText.lines(requireContext(),
                    info.requiredPermissions, info.requiredOrigins);
            String data = AddonPermissionText.dataCollection(requireContext(), info.requiredDataCollection);
            if (data != null) {
                lines.add(data);
            }
            mPermissions.setSummary(lines.isEmpty()
                    ? getString(R.string.addon_permissions_none)
                    : TextUtils.join("\n", lines));
        }
        bindOptional(info);
        mBinding = false;
    }

    /** One optional thing the add-on may ask for: its words, and whether it has it now. */
    private static final class OptionalRow {
        final String key;
        final String title;
        final boolean granted;
        final String permission;
        final String origin;
        final String data;

        OptionalRow(String key, String title, boolean granted, String permission, String origin, String data) {
            this.key = key;
            this.title = title;
            this.granted = granted;
            this.permission = permission;
            this.origin = origin;
            this.data = data;
        }
    }

    /**
     * Firefox's permission switches: each optional permission, site or data
     * kind the add-on declares, on when granted. Turning one off takes it
     * back; turning one on grants it without the add-on asking.
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
                        info.grantedOptionalPermissions.contains(permission), permission, null, null));
            }
        }
        for (String origin : info.optionalOrigins) {
            String title = AddonPermissionText.origin(requireContext(), origin);
            if (title != null) {
                rows.add(new OptionalRow(OPTIONAL_PREFIX + "o." + origin, title,
                        info.grantedOptionalOrigins.contains(origin), null, origin, null));
            }
        }
        for (String kind : info.optionalDataCollection) {
            String title = AddonPermissionText.data(requireContext(), kind);
            if (title != null) {
                rows.add(new OptionalRow(OPTIONAL_PREFIX + "d." + kind, title,
                        info.grantedOptionalDataCollection.contains(kind), null, null, kind));
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
                sw.setOnPreferenceChangeListener((p, value) -> {
                    if (!mBinding && mId != null) {
                        mAddonManager.setOptionalPermission(mId, row.permission, row.origin, row.data,
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

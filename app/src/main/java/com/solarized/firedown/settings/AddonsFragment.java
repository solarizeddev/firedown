package com.solarized.firedown.settings;

import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.addons.AddonIcons;
import com.solarized.firedown.addons.AddonInfo;
import com.solarized.firedown.addons.AddonManager;
import com.solarized.firedown.addons.AddonPolicy;
import com.solarized.firedown.addons.AmoCatalog;
import com.solarized.firedown.addons.AmoCatalogClient;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.utils.NavigationUtils;

import org.mozilla.geckoview.WebExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Settings → Add-ons: the add-ons installed, then Mozilla's list of
 * add-ons for Android ({@link AmoCatalog}), then "Install from file" for a
 * signed add-on from elsewhere. A tap on a listed add-on starts the install;
 * the permission prompt (shown by the activity) is where the user decides.
 * The list is fetched when this screen opens, never in the background.
 */
@AndroidEntryPoint
public class AddonsFragment extends BasePreferenceFragment {

    private static final String KEY_INSTALLED_CATEGORY = "com.solarized.firedown.preferences.addons.installed.category";
    private static final String KEY_INSTALLED_EMPTY = "com.solarized.firedown.preferences.addons.installed.empty";
    private static final String KEY_INSTALLING = "com.solarized.firedown.preferences.addons.installing";
    private static final String KEY_CATALOG_CATEGORY = "com.solarized.firedown.preferences.addons.catalog.category";
    private static final String KEY_CATALOG_STATUS = "com.solarized.firedown.preferences.addons.catalog.status";
    private static final String KEY_CATALOG_RETRY = "com.solarized.firedown.preferences.addons.catalog.retry";
    private static final String KEY_UPDATES = "com.solarized.firedown.preferences.addons.updates";
    private static final String KEY_FILE = "com.solarized.firedown.preferences.addons.file";
    private static final String KEY_FIND_MORE = "com.solarized.firedown.preferences.addons.more";
    /**
     * Mozilla's add-on site for Android: only add-ons whose developers mark
     * them as working on Android (Firefox for Android's "Find more
     * add-ons"). Installing there is Gecko's own web install, allowed from
     * addons.mozilla.org only, and asks through the same prompt.
     */
    private static final String AMO_ANDROID_URL = "https://addons.mozilla.org/android/";
    private static final String KEY_INSTALLED_PREFIX = "com.solarized.firedown.preferences.addons.installed.row.";
    private static final String KEY_CATALOG_PREFIX = "com.solarized.firedown.preferences.addons.catalog.row.";

    @Inject
    AddonManager mAddonManager;

    @Inject
    AmoCatalogClient mCatalog;

    private PreferenceCategory mInstalledCategory;
    private Preference mInstalledEmpty;
    private Preference mInstalling;
    private PreferenceCategory mCatalogCategory;
    private Preference mCatalogStatus;
    private Preference mCatalogRetry;
    private Preference mUpdates;
    private int mIconPx;

    /** The file picker shows every type: few file apps know the .xpi type. The file itself is checked. */
    private final ActivityResultLauncher<String[]> mPickFile = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onFilePicked);

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.settings_addons, rootKey);
        mIconPx = getResources().getDimensionPixelSize(R.dimen.addon_icon_size);

        mInstalledCategory = findPreference(KEY_INSTALLED_CATEGORY);
        mInstalledEmpty = findPreference(KEY_INSTALLED_EMPTY);
        mInstalling = findPreference(KEY_INSTALLING);
        mCatalogCategory = findPreference(KEY_CATALOG_CATEGORY);
        mCatalogStatus = findPreference(KEY_CATALOG_STATUS);
        mCatalogRetry = findPreference(KEY_CATALOG_RETRY);
        mUpdates = findPreference(KEY_UPDATES);

        if (mCatalogRetry != null) {
            mCatalogRetry.setOnPreferenceClickListener(p -> {
                mCatalog.load(true);
                return true;
            });
        }
        if (mUpdates != null) {
            mUpdates.setOnPreferenceClickListener(p -> {
                mAddonManager.checkForUpdates(true, null);
                return true;
            });
        }
        Preference findMore = findPreference(KEY_FIND_MORE);
        if (findMore != null) {
            findMore.setOnPreferenceClickListener(p -> {
                if (mActivity != null) {
                    SettingsActivity.openInBrowser(mActivity, AMO_ANDROID_URL);
                }
                return true;
            });
        }
        Preference file = findPreference(KEY_FILE);
        if (file != null) {
            file.setOnPreferenceClickListener(p -> {
                pickFile();
                return true;
            });
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mAddonManager.getAddons().observe(getViewLifecycleOwner(), addons -> {
            renderInstalled(addons);
            AmoCatalogClient.State state = mCatalog.getState().getValue();
            if (state != null) {
                renderCatalog(state);
            }
        });
        mAddonManager.getInstallsInFlight().observe(getViewLifecycleOwner(), count -> {
            if (mInstalling != null) {
                mInstalling.setVisible(count != null && count > 0);
            }
        });
        mCatalog.getState().observe(getViewLifecycleOwner(), this::renderCatalog);
        mCatalog.load(false);
    }

    private void pickFile() {
        try {
            mPickFile.launch(new String[]{"*/*"});
        } catch (ActivityNotFoundException e) {
            // No file app: nothing to pick from.
        }
    }

    private void onFilePicked(@Nullable Uri uri) {
        if (uri != null) {
            mAddonManager.installFromUri(uri);
        }
    }

    private void renderInstalled(@Nullable List<AddonInfo> addons) {
        if (mInstalledCategory == null || !isAdded()) {
            return;
        }
        removeDynamicRows(mInstalledCategory, KEY_INSTALLED_PREFIX);
        List<AddonInfo> list = addons == null ? Collections.emptyList() : addons;
        if (mInstalledEmpty != null) {
            mInstalledEmpty.setVisible(list.isEmpty());
        }
        if (mUpdates != null) {
            mUpdates.setVisible(!list.isEmpty());
        }
        int order = 100;
        for (AddonInfo info : list) {
            Preference p = new Preference(requireContext());
            p.setKey(KEY_INSTALLED_PREFIX + info.id);
            p.setPersistent(false);
            p.setTitle(info.name);
            p.setSummary(summaryFor(info));
            p.setSingleLineTitle(false);
            p.setIconSpaceReserved(true);
            p.setOrder(order++);
            final String id = info.id;
            p.setOnPreferenceClickListener(pref -> {
                openDetail(id);
                return true;
            });
            mInstalledCategory.addPreference(p);
            AddonIcons.load(requireContext(), info.icon, mIconPx, icon -> {
                if (isAdded()) {
                    p.setIcon(icon);
                }
            });
        }
        if (mInstalling != null) {
            mInstalling.setOrder(order + 1);
        }
    }

    @NonNull
    private String summaryFor(@NonNull AddonInfo info) {
        if (info.disabledBySystem()) {
            boolean blocked = (info.disabledFlags & WebExtension.DisabledFlags.BLOCKLIST) != 0;
            return getString(blocked ? R.string.addons_disabled_blocked : R.string.addons_disabled_unsupported);
        }
        if (mAddonManager.isUpdateWaitingForReview(info.id)) {
            return getString(R.string.addons_update_review);
        }
        if (!info.enabled) {
            return getString(R.string.addons_off);
        }
        return getString(R.string.addon_version, info.version);
    }

    private void renderCatalog(@NonNull AmoCatalogClient.State state) {
        if (mCatalogCategory == null || !isAdded()) {
            return;
        }
        removeDynamicRows(mCatalogCategory, KEY_CATALOG_PREFIX);
        Set<String> installed = new HashSet<>();
        List<AddonInfo> addons = mAddonManager.getAddons().getValue();
        if (addons != null) {
            for (AddonInfo info : addons) {
                installed.add(info.id.toLowerCase(Locale.ROOT));
            }
        }
        List<AmoCatalog.Entry> shown = new ArrayList<>();
        for (AmoCatalog.Entry entry : state.entries) {
            if (!installed.contains(entry.id.toLowerCase(Locale.ROOT))) {
                shown.add(entry);
            }
        }
        if (mCatalogStatus != null) {
            boolean showStatus = shown.isEmpty() && (state.loading || state.failed);
            mCatalogStatus.setVisible(showStatus);
            mCatalogStatus.setSummary(state.failed
                    ? R.string.addons_catalog_failed : R.string.addons_catalog_loading);
        }
        if (mCatalogRetry != null) {
            mCatalogRetry.setVisible(state.failed && !state.loading);
        }
        int order = 100;
        for (AmoCatalog.Entry entry : shown) {
            Preference p = new Preference(requireContext());
            p.setKey(KEY_CATALOG_PREFIX + entry.id);
            p.setPersistent(false);
            p.setTitle(entry.name);
            p.setSingleLineTitle(false);
            p.setIconSpaceReserved(true);
            p.setOrder(order++);
            if (AddonPolicy.isBuiltInId(entry.id)) {
                p.setSummary(R.string.addons_built_in);
                p.setSelectable(false);
            } else {
                p.setSummary(entry.summary);
                p.setOnPreferenceClickListener(pref -> {
                    mAddonManager.installFromCatalog(entry);
                    return true;
                });
            }
            mCatalogCategory.addPreference(p);
            AddonIcons.load(requireContext(), entry.iconUrl, mIconPx, icon -> {
                if (isAdded()) {
                    p.setIcon(icon);
                }
            });
        }
        if (mCatalogRetry != null) {
            mCatalogRetry.setOrder(order + 1);
        }
    }

    private void openDetail(@NonNull String id) {
        Bundle args = new Bundle();
        args.putString(Keys.ADDON_ID, id);
        NavigationUtils.navigateSafe(mNavController, R.id.action_addons_to_detail, args);
    }

    /** Sweeps the rows this fragment added (keyed by prefix); the XML placeholders stay. */
    private static void removeDynamicRows(@NonNull PreferenceCategory category, @NonNull String prefix) {
        for (int i = category.getPreferenceCount() - 1; i >= 0; i--) {
            Preference p = category.getPreference(i);
            String key = p.getKey();
            if (key != null && key.startsWith(prefix)) {
                category.removePreference(p);
            }
        }
    }
}

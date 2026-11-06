package com.solarized.firedown.settings;

import android.content.ActivityNotFoundException;
import android.icu.text.CompactDecimalFormat;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
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
import com.solarized.firedown.settings.ui.BusyPreference;
import com.solarized.firedown.settings.ui.CatalogPreference;
import com.solarized.firedown.utils.NavigationUtils;

import org.mozilla.geckoview.WebExtension;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** The start of the description: Mozilla's summaries run to five lines. */
    private static final int CATALOG_DESCRIPTION_LINES = 2;
    /** The rating · users line above it, a little smaller than the description. */
    private static final float CATALOG_META_SCALE = 12f / 14f;
    /** From here CompactDecimalFormat rounds ("1.2K"): no exact count to agree with. */
    private static final int COMPACT_FROM = 1000;

    private static final String KEY_INSTALLED_CATEGORY = "com.solarized.firedown.preferences.addons.installed.category";
    private static final String KEY_INSTALLED_EMPTY = "com.solarized.firedown.preferences.addons.installed.empty";
    private static final String KEY_CATALOG_CATEGORY = "com.solarized.firedown.preferences.addons.catalog.category";
    private static final String KEY_CATALOG_STATUS = "com.solarized.firedown.preferences.addons.catalog.status";
    private static final String KEY_CATALOG_RETRY = "com.solarized.firedown.preferences.addons.catalog.retry";
    private static final String KEY_UPDATES = "com.solarized.firedown.preferences.addons.updates";
    private static final String KEY_FILE = "com.solarized.firedown.preferences.addons.file";
    private static final String KEY_FIND_MORE = "com.solarized.firedown.preferences.addons.more";
    private static final String KEY_REINSTALL_UBLOCK = "com.solarized.firedown.preferences.addons.reinstall.ublock";
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
    private BusyPreference mFile;
    /** Shown while uBlock Origin is not installed: the app's copy, no download. */
    private BusyPreference mReinstallUblock;
    /** The rows on screen, by add-on id, so a repaint updates them in place (no icon flicker). */
    private final Map<String, Preference> mInstalledRows = new LinkedHashMap<>();
    private final Map<String, CatalogPreference> mCatalogRows = new LinkedHashMap<>();
    private final Map<String, AmoCatalog.Entry> mCatalogEntries = new HashMap<>();
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
        mReinstallUblock = findPreference(KEY_REINSTALL_UBLOCK);
        if (mReinstallUblock != null) {
            mReinstallUblock.setOnPreferenceClickListener(p -> {
                if (!mReinstallUblock.isBusy()) {
                    mAddonManager.installBundledUblock(true);
                }
                return true;
            });
        }
        mFile = findPreference(KEY_FILE);
        if (mFile != null) {
            mFile.setOnPreferenceClickListener(p -> {
                if (!mFile.isBusy()) {
                    pickFile();
                }
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
        mAddonManager.getInstalling().observe(getViewLifecycleOwner(), this::applyInstalling);
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
        List<AddonInfo> list = addons == null ? Collections.emptyList() : addons;
        boolean ublockMissing = mAddonManager.findUblockInfo() == null;
        if (mReinstallUblock != null) {
            mReinstallUblock.setVisible(ublockMissing);
        }
        if (mInstalledEmpty != null) {
            mInstalledEmpty.setVisible(list.isEmpty() && !ublockMissing);
        }
        if (mUpdates != null) {
            mUpdates.setVisible(!list.isEmpty());
        }
        List<String> ids = new ArrayList<>();
        for (AddonInfo info : list) {
            ids.add(info.id);
        }
        if (ids.equals(new ArrayList<>(mInstalledRows.keySet()))) {
            // Same add-ons (one turned off, updated…): words only, icons kept.
            for (AddonInfo info : list) {
                Preference p = mInstalledRows.get(info.id);
                if (p != null) {
                    p.setTitle(info.name);
                    p.setSummary(summaryFor(info));
                }
            }
            return;
        }
        removeDynamicRows(mInstalledCategory, KEY_INSTALLED_PREFIX);
        mInstalledRows.clear();
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
            mInstalledRows.put(info.id, p);
            AddonIcons.load(requireContext(), info.icon, mIconPx, icon -> {
                if (isAdded()) {
                    p.setIcon(icon);
                }
            });
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
        Set<String> installed = new HashSet<>();
        List<AddonInfo> addons = mAddonManager.getAddons().getValue();
        if (addons != null) {
            for (AddonInfo info : addons) {
                installed.add(info.id.toLowerCase(Locale.ROOT));
            }
        }
        List<AmoCatalog.Entry> shown = new ArrayList<>();
        for (AmoCatalog.Entry entry : state.entries) {
            // uBlock Origin has its own row (the app's copy, no download).
            if (!installed.contains(entry.id.toLowerCase(Locale.ROOT)) && !AddonPolicy.isUblockId(entry.id)) {
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
        List<String> ids = new ArrayList<>();
        for (AmoCatalog.Entry entry : shown) {
            ids.add(entry.id);
        }
        if (!ids.equals(new ArrayList<>(mCatalogRows.keySet()))) {
            removeDynamicRows(mCatalogCategory, KEY_CATALOG_PREFIX);
            mCatalogRows.clear();
            mCatalogEntries.clear();
            int order = 100;
            for (AmoCatalog.Entry entry : shown) {
                CatalogPreference p = new CatalogPreference(requireContext());
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
                    CharSequence summary = catalogSummary(entry);
                    p.setSummary(summary);
                    p.setSummaryMaxLines(catalogSummaryLines(entry));
                    p.setActionDescription(getString(R.string.addons_add_description, entry.name));
                    p.setOnPreferenceClickListener(pref -> {
                        // The row and its Add button both start it; the
                        // spinner replaces the button until Gecko is done.
                        if (!p.isBusy()) {
                            mAddonManager.installFromCatalog(entry);
                        }
                        return true;
                    });
                }
                mCatalogCategory.addPreference(p);
                mCatalogRows.put(entry.id, p);
                mCatalogEntries.put(entry.id, entry);
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
        applyInstalling(mAddonManager.getInstalling().getValue());
    }

    /**
     * The spinners: a row from Mozilla's list being installed reads
     * "Installing…" with a spinner and ignores taps until Gecko is done
     * (installed, declined or failed); "Install from file" the same.
     */
    private void applyInstalling(@Nullable Set<String> installing) {
        if (!isAdded()) {
            return;
        }
        Set<String> busy = installing == null ? Collections.emptySet() : installing;
        for (Map.Entry<String, CatalogPreference> row : mCatalogRows.entrySet()) {
            AmoCatalog.Entry entry = mCatalogEntries.get(row.getKey());
            if (entry == null || AddonPolicy.isBuiltInId(entry.id)) {
                continue;
            }
            boolean on = busy.contains(entry.id);
            CatalogPreference p = row.getValue();
            if (p.isBusy() != on) {
                CharSequence summary = on ? getString(R.string.addons_installing) : catalogSummary(entry);
                p.setSummaryMaxLines(on ? 1 : catalogSummaryLines(entry));
                p.setSummary(summary);
                p.setBusy(on);
            }
        }
        if (mReinstallUblock != null) {
            boolean on = busy.contains(AddonPolicy.UBLOCK_ID);
            if (mReinstallUblock.isBusy() != on) {
                mReinstallUblock.setBusy(on);
                mReinstallUblock.setSummary(on ? R.string.addons_installing : R.string.addons_reinstall_ublock_summary);
            }
        }
        if (mFile != null) {
            boolean on = busy.contains(AddonManager.INSTALLING_FILE);
            if (mFile.isBusy() != on) {
                mFile.setBusy(on);
                mFile.setSummary(on ? R.string.addons_installing : R.string.addons_install_from_file_summary);
            }
        }
    }

    /**
     * "★ 4.6 · 1.2M users" over the start of the description: what people
     * think of it and how many use it, the two facts a list of strangers'
     * add-ons is picked by (Firefox shows them too). Either half is left
     * out when Mozilla's list doesn't say.
     */
    @NonNull
    private CharSequence catalogSummary(@NonNull AmoCatalog.Entry entry) {
        String meta = catalogMeta(entry);
        if (meta == null) {
            return entry.summary;
        }
        SpannableStringBuilder out = new SpannableStringBuilder(meta);
        out.setSpan(new RelativeSizeSpan(CATALOG_META_SCALE), 0, meta.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!entry.summary.isEmpty()) {
            out.append('\n').append(entry.summary);
        }
        return out;
    }

    /** The meta line takes one of the clamp's lines; the description keeps two. */
    private int catalogSummaryLines(@NonNull AmoCatalog.Entry entry) {
        return CATALOG_DESCRIPTION_LINES + (catalogMeta(entry) != null ? 1 : 0);
    }

    @Nullable
    private String catalogMeta(@NonNull AmoCatalog.Entry entry) {
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        List<String> parts = new ArrayList<>();
        if (entry.rating > 0) {
            NumberFormat rating = NumberFormat.getInstance(locale);
            rating.setMinimumFractionDigits(1);
            rating.setMaximumFractionDigits(1);
            parts.add(getString(R.string.addons_rating, rating.format(entry.rating)));
        }
        if (entry.users > 0) {
            String users = CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT)
                    .format(entry.users);
            // A rounded count ("1,2 тыс.") takes the plural for many whatever
            // its digits, so the form is never chosen from the raw count.
            parts.add(entry.users >= COMPACT_FROM
                    ? getString(R.string.addons_users_compact, users)
                    : getResources().getQuantityString(R.plurals.addons_users, entry.users, users));
        }
        return parts.isEmpty() ? null : TextUtils.join(" · ", parts);
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

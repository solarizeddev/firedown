package com.solarized.firedown.settings;

import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.geckoview.TranslationLanguages;

import org.mozilla.geckoview.TranslationsController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Settings sub-screen for Gecko's built-in translator. Hosts the two
 * switches (master pref + offer prompt — applied to Gecko HERE, because
 * SettingsFragment's SharedPreferenceChangeListener is unregistered while a
 * sub-screen is in the foreground) and two lists read live from GeckoView's
 * {@link TranslationsController.RuntimeTranslation}: the language models
 * on the device (the feature's only storage cost — tens of MB per language,
 * downloaded from Mozilla the first time a language is translated; tap to
 * delete, and they download again on the next translate) and the sites the
 * user marked "never translate" from the sheet (tap to un-mark).
 *
 * <p>Both lists are re-read on every {@code onResume} rather than cached:
 * the API is cheap (an in-process query), and a stale list here would be
 * a lie about what is on disk. Every GeckoResult callback re-checks
 * {@code isAdded()} — the screen can be left while a query is in flight.</p>
 */
@AndroidEntryPoint
public class TranslationsFragment extends BasePreferenceFragment {

    private static final String TAG = TranslationsFragment.class.getName();

    private static final String KEY_MODELS_CATEGORY =
            "com.solarized.firedown.preferences.translations.models.category";
    private static final String KEY_MODELS_EMPTY =
            "com.solarized.firedown.preferences.translations.models.empty";
    private static final String KEY_MODELS_CLEAR =
            "com.solarized.firedown.preferences.translations.models.clear";
    private static final String KEY_MODEL_PREFIX =
            "com.solarized.firedown.preferences.translations.model.";
    private static final String KEY_SITES_CATEGORY =
            "com.solarized.firedown.preferences.translations.sites.category";
    private static final String KEY_SITES_EMPTY =
            "com.solarized.firedown.preferences.translations.sites.empty";
    private static final String KEY_SITE_PREFIX =
            "com.solarized.firedown.preferences.translations.site.";

    private PreferenceCategory mModelsCategory;
    private Preference mModelsEmpty;
    private Preference mModelsClear;
    private PreferenceCategory mSitesCategory;
    private Preference mSitesEmpty;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.settings_translations, rootKey);

        SwitchPreferenceCompat enabled = findPreference(Preferences.SETTINGS_TRANSLATIONS_ENABLED);
        if (enabled != null) {
            enabled.setOnPreferenceChangeListener((p, value) -> {
                mGeckoRuntimeHelper.setTranslationsEnabled(Boolean.TRUE.equals(value));
                return true;
            });
        }
        SwitchPreferenceCompat offer = findPreference(Preferences.SETTINGS_TRANSLATIONS_OFFER);
        if (offer != null) {
            offer.setOnPreferenceChangeListener((p, value) -> {
                mGeckoRuntimeHelper.setTranslationsOffer(Boolean.TRUE.equals(value));
                return true;
            });
        }

        mModelsCategory = findPreference(KEY_MODELS_CATEGORY);
        mModelsEmpty = findPreference(KEY_MODELS_EMPTY);
        mModelsClear = findPreference(KEY_MODELS_CLEAR);
        mSitesCategory = findPreference(KEY_SITES_CATEGORY);
        mSitesEmpty = findPreference(KEY_SITES_EMPTY);

        if (mModelsClear != null) {
            mModelsClear.setOnPreferenceClickListener(p -> {
                confirmDeleteAllModels();
                return true;
            });
        }

        tintIcons();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        TranslationsController.RuntimeTranslation.listModelDownloadStates().accept(
                models -> {
                    if (!isAdded()) return;
                    renderModels(models == null ? Collections.emptyList() : models);
                },
                error -> {
                    if (!isAdded()) return;
                    Log.d(TAG, "listModelDownloadStates failed", error);
                    renderModelsUnavailable();
                });
        TranslationsController.RuntimeTranslation.getNeverTranslateSiteList().accept(
                sites -> {
                    if (!isAdded()) return;
                    renderSites(sites == null ? Collections.emptyList() : sites);
                },
                error -> {
                    if (!isAdded()) return;
                    Log.d(TAG, "getNeverTranslateSiteList failed", error);
                    renderSites(Collections.emptyList());
                });
    }

    // ── Downloaded language models ───────────────────────────────────────

    private void renderModels(@NonNull List<TranslationsController.RuntimeTranslation.LanguageModel> models) {
        if (mModelsCategory == null) return;
        removeDynamicRows(mModelsCategory, KEY_MODEL_PREFIX);

        List<TranslationsController.RuntimeTranslation.LanguageModel> downloaded = new ArrayList<>();
        for (TranslationsController.RuntimeTranslation.LanguageModel model : models) {
            if (model.language != null && Boolean.TRUE.equals(model.isDownloaded)) {
                downloaded.add(model);
            }
        }

        boolean empty = downloaded.isEmpty();
        if (mModelsEmpty != null) {
            mModelsEmpty.setSummary(R.string.settings_translations_models_empty);
            mModelsEmpty.setVisible(empty);
        }
        if (mModelsClear != null) mModelsClear.setVisible(!empty);

        // Rows land AFTER the placeholder and BEFORE "Delete all", which is
        // pushed below them by order (addPreference appends).
        int order = 100;
        for (TranslationsController.RuntimeTranslation.LanguageModel model : downloaded) {
            final String code = model.language.code;
            final String name = TranslationLanguages.displayName(model.language);
            Preference p = new Preference(requireContext());
            p.setKey(KEY_MODEL_PREFIX + code);
            p.setTitle(name);
            if (model.size > 0) {
                p.setSummary(Formatter.formatShortFileSize(requireContext(), model.size));
            }
            p.setIcon(R.drawable.translate_24);
            p.setOrder(order++);
            p.setOnPreferenceClickListener(pref -> {
                confirmDeleteModel(code, name);
                return true;
            });
            mModelsCategory.addPreference(p);
        }
        if (mModelsClear != null) mModelsClear.setOrder(order + 1);

        // Re-tint icons for the freshly-added preferences.
        tintIcons();
    }

    /**
     * The engine isn't usable on this device (or the master switch is off,
     * which makes Gecko answer the same way) — say so in the placeholder
     * instead of showing an empty list that reads as "nothing downloaded".
     */
    private void renderModelsUnavailable() {
        if (mModelsCategory == null) return;
        removeDynamicRows(mModelsCategory, KEY_MODEL_PREFIX);
        if (mModelsEmpty != null) {
            mModelsEmpty.setSummary(R.string.settings_translations_unavailable);
            mModelsEmpty.setVisible(true);
        }
        if (mModelsClear != null) mModelsClear.setVisible(false);
    }

    private void confirmDeleteModel(@NonNull String code, @NonNull String name) {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.settings_translations_model_delete_message, name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> manageModel(
                        new TranslationsController.RuntimeTranslation.ModelManagementOptions.Builder()
                                .languageToManage(code)
                                .operation(TranslationsController.RuntimeTranslation.DELETE)
                                .operationLevel(TranslationsController.RuntimeTranslation.LANGUAGE)
                                .build()))
                .show();
    }

    private void confirmDeleteAllModels() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_translations_models_delete_all)
                .setMessage(R.string.settings_translations_models_delete_all_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> manageModel(
                        new TranslationsController.RuntimeTranslation.ModelManagementOptions.Builder()
                                .operation(TranslationsController.RuntimeTranslation.DELETE)
                                .operationLevel(TranslationsController.RuntimeTranslation.ALL)
                                .build()))
                .show();
    }

    private void manageModel(@NonNull TranslationsController.RuntimeTranslation.ModelManagementOptions options) {
        TranslationsController.RuntimeTranslation.manageLanguageModel(options).accept(
                unused -> {
                    if (!isAdded()) return;
                    refresh();
                },
                error -> {
                    if (!isAdded()) return;
                    Log.d(TAG, "manageLanguageModel failed", error);
                    // Whatever Gecko did or didn't delete, the list re-read
                    // is the truth.
                    refresh();
                });
    }

    // ── Never-translate sites ────────────────────────────────────────────

    private void renderSites(@NonNull List<String> sites) {
        if (mSitesCategory == null) return;
        removeDynamicRows(mSitesCategory, KEY_SITE_PREFIX);

        if (mSitesEmpty != null) mSitesEmpty.setVisible(sites.isEmpty());

        int order = 100;
        for (String site : sites) {
            if (site == null || site.isEmpty()) continue;
            final String origin = site;
            Preference p = new Preference(requireContext());
            p.setKey(KEY_SITE_PREFIX + origin);
            p.setTitle(origin);
            p.setIcon(R.drawable.ic_globe_24);
            p.setOrder(order++);
            p.setOnPreferenceClickListener(pref -> {
                confirmRemoveSite(origin);
                return true;
            });
            mSitesCategory.addPreference(p);
        }
        tintIcons();
    }

    private void confirmRemoveSite(@NonNull String origin) {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.settings_translations_site_remove_message, origin))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_translations_site_remove_confirm, (d, w) ->
                        TranslationsController.RuntimeTranslation
                                .setNeverTranslateSpecifiedSite(false, origin).accept(
                                        unused -> {
                                            if (!isAdded()) return;
                                            refresh();
                                        },
                                        error -> {
                                            if (!isAdded()) return;
                                            Log.d(TAG, "setNeverTranslateSpecifiedSite failed", error);
                                            refresh();
                                        }))
                .show();
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

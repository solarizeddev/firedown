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
import com.solarized.firedown.geckoview.TranslationLanguageSettings;
import com.solarized.firedown.geckoview.TranslationLanguages;

import org.mozilla.geckoview.TranslationsController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Settings sub-screen for Gecko's built-in translator. Hosts the two
 * switches (master pref + offer card — applied to Gecko HERE, because
 * SettingsFragment's SharedPreferenceChangeListener is unregistered while a
 * sub-screen is in the foreground) and four lists read live from GeckoView's
 * {@link TranslationsController.RuntimeTranslation}: the languages marked
 * "always translate" and "never translate" (Gecko's per-language store,
 * {@link TranslationLanguageSettings} — the same rows the offer card's ⋮
 * and the sheet's switches write; tap to un-mark, "Add language" to mark
 * one from the supported list), the language models on the device (the
 * feature's only storage cost — tens of MB per language, downloaded from
 * Mozilla the first time a language is translated; tap to delete, and they
 * download again on the next translate) and the sites the user marked
 * "never translate" from the sheet (tap to un-mark).
 *
 * <p>All lists are re-read on every {@code onResume} rather than cached:
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
    private static final String KEY_ALWAYS_CATEGORY =
            "com.solarized.firedown.preferences.translations.always.category";
    private static final String KEY_ALWAYS_EMPTY =
            "com.solarized.firedown.preferences.translations.always.empty";
    private static final String KEY_ALWAYS_ADD =
            "com.solarized.firedown.preferences.translations.always.add";
    private static final String KEY_ALWAYS_PREFIX =
            "com.solarized.firedown.preferences.translations.always.lang.";
    private static final String KEY_NEVER_CATEGORY =
            "com.solarized.firedown.preferences.translations.never.category";
    private static final String KEY_NEVER_EMPTY =
            "com.solarized.firedown.preferences.translations.never.empty";
    private static final String KEY_NEVER_ADD =
            "com.solarized.firedown.preferences.translations.never.add";
    private static final String KEY_NEVER_PREFIX =
            "com.solarized.firedown.preferences.translations.never.lang.";
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
    private PreferenceCategory mAlwaysCategory;
    private Preference mAlwaysEmpty;
    private Preference mAlwaysAdd;
    private PreferenceCategory mNeverCategory;
    private Preference mNeverEmpty;
    private Preference mNeverAdd;

    /** The from-languages Gecko supports, for the "Add language" pickers and row names. */
    private final List<TranslationsController.Language> mSupported = new ArrayList<>();

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
        mAlwaysCategory = findPreference(KEY_ALWAYS_CATEGORY);
        mAlwaysEmpty = findPreference(KEY_ALWAYS_EMPTY);
        mAlwaysAdd = findPreference(KEY_ALWAYS_ADD);
        mNeverCategory = findPreference(KEY_NEVER_CATEGORY);
        mNeverEmpty = findPreference(KEY_NEVER_EMPTY);
        mNeverAdd = findPreference(KEY_NEVER_ADD);

        if (mAlwaysAdd != null) {
            mAlwaysAdd.setOnPreferenceClickListener(p -> {
                pickLanguage(TranslationLanguageSettings.ALWAYS);
                return true;
            });
        }
        if (mNeverAdd != null) {
            mNeverAdd.setOnPreferenceClickListener(p -> {
                pickLanguage(TranslationLanguageSettings.NEVER);
                return true;
            });
        }

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
        refreshLanguageSettings();
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

    // ── Always / never translate these languages ─────────────────────────

    /**
     * Reads Gecko's per-language store and the supported list together (the
     * store is keyed by bare code; the supported list gives names and the
     * pick-list for "Add language"), then renders the two categories. A
     * failure of either read leaves both lists empty rather than half
     * painted.
     */
    private void refreshLanguageSettings() {
        TranslationsController.RuntimeTranslation.listSupportedLanguages().accept(
                support -> {
                    if (!isAdded()) return;
                    mSupported.clear();
                    if (support != null && support.fromLanguages != null) {
                        mSupported.addAll(support.fromLanguages);
                    }
                    Collections.sort(mSupported);
                    TranslationLanguageSettings.all().accept(
                            settings -> {
                                if (!isAdded()) return;
                                renderLanguageSettings(settings == null
                                        ? Collections.emptyMap() : settings);
                            },
                            error -> {
                                if (!isAdded()) return;
                                Log.d(TAG, "getLanguageSettings failed", error);
                                renderLanguageSettings(Collections.emptyMap());
                            });
                },
                error -> {
                    if (!isAdded()) return;
                    Log.d(TAG, "listSupportedLanguages failed", error);
                    mSupported.clear();
                    renderLanguageSettings(Collections.emptyMap());
                });
    }

    private void renderLanguageSettings(@NonNull Map<String, String> settings) {
        List<String> always = new ArrayList<>();
        List<String> never = new ArrayList<>();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String code = TranslationLanguageSettings.languageCode(entry.getKey());
            if (code == null) continue;
            if (TranslationLanguageSettings.isAlways(entry.getValue())) {
                always.add(code);
            } else if (TranslationLanguageSettings.isNever(entry.getValue())) {
                never.add(code);
            }
        }
        renderLanguageList(mAlwaysCategory, mAlwaysEmpty, mAlwaysAdd, KEY_ALWAYS_PREFIX, always,
                TranslationLanguageSettings.ALWAYS);
        renderLanguageList(mNeverCategory, mNeverEmpty, mNeverAdd, KEY_NEVER_PREFIX, never,
                TranslationLanguageSettings.NEVER);
        tintIcons();
    }

    private void renderLanguageList(@Nullable PreferenceCategory category,
                                    @Nullable Preference empty,
                                    @Nullable Preference add,
                                    @NonNull String prefix,
                                    @NonNull List<String> codes,
                                    @NonNull String state) {
        if (category == null) return;
        removeDynamicRows(category, prefix);
        Collections.sort(codes, (a, b) -> TranslationLanguages.displayName(a)
                .compareToIgnoreCase(TranslationLanguages.displayName(b)));
        if (empty != null) empty.setVisible(codes.isEmpty());
        // Rows land AFTER the placeholder and BEFORE "Add language", which
        // is pushed below them by order (addPreference appends).
        int order = 100;
        for (String code : codes) {
            final String name = TranslationLanguages.displayName(code);
            Preference p = new Preference(requireContext());
            p.setKey(prefix + code);
            p.setTitle(name);
            p.setIcon(TranslationLanguageSettings.ALWAYS.equals(state)
                    ? R.drawable.ic_autorenew_24 : R.drawable.ic_block_24);
            p.setOrder(order++);
            p.setOnPreferenceClickListener(pref -> {
                confirmResetLanguage(code, name, state);
                return true;
            });
            category.addPreference(p);
        }
        if (add != null) add.setOrder(order + 1);
    }

    /** Tapping a listed language offers to put it back to Gecko's default ("offer"). */
    private void confirmResetLanguage(@NonNull String code, @NonNull String name,
                                      @NonNull String state) {
        int message = TranslationLanguageSettings.ALWAYS.equals(state)
                ? R.string.settings_translations_always_remove_message
                : R.string.settings_translations_never_remove_message;
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(message, name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_translations_site_remove_confirm, (d, w) ->
                        writeLanguageSetting(code, TranslationLanguageSettings.OFFER))
                .show();
    }

    /**
     * "Add language": a single-choice list of every supported source
     * language; picking one writes the category's state for it. Gecko
     * holds ONE state per language, so adding to "always" a language that
     * sat under "never" moves it — the next refresh shows it once.
     */
    private void pickLanguage(@NonNull String state) {
        if (mSupported.isEmpty()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.settings_translations_unavailable)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        final List<TranslationsController.Language> languages = new ArrayList<>(mSupported);
        String[] names = new String[languages.size()];
        for (int i = 0; i < languages.size(); i++) {
            names[i] = TranslationLanguages.displayName(languages.get(i));
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_translations_add_language)
                .setItems(names, (d, which) ->
                        writeLanguageSetting(languages.get(which).code, state))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void writeLanguageSetting(@NonNull String code, @NonNull String state) {
        TranslationLanguageSettings.set(code, state).accept(
                unused -> {
                    if (!isAdded()) return;
                    refresh();
                },
                error -> {
                    if (!isAdded()) return;
                    Log.d(TAG, "setLanguageSettings failed", error);
                    // Whatever Gecko did or didn't write, the re-read is the truth.
                    refresh();
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

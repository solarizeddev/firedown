package com.solarized.firedown.geckoview;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.TranslationsController;

import java.util.Locale;
import java.util.Map;

/**
 * The per-LANGUAGE translation preference — "always translate Spanish" /
 * "never translate Spanish" — read and written through Gecko's own store
 * ({@link TranslationsController.RuntimeTranslation#getLanguageSettings()} /
 * {@code setLanguageSettings}), never a pref of ours.
 *
 * <p>Gecko keeps three states per language: {@code always}, {@code offer}
 * (the default) and {@code never}, and it ACTS on them itself — a language
 * marked {@code never} stops firing {@code onOfferTranslate} for its pages,
 * a language marked {@code always} gets its pages translated as soon as they
 * load (the state change arrives on the tab like a user-requested one). So
 * the offer card, the sheet's switches and the Settings lists are three
 * views of one store, with no logic of ours behind any of them: turning a
 * switch on writes the state, and the next page in that language behaves
 * accordingly with nothing else to keep in step. Keep it that way — a
 * shadow copy in SharedPreferences would drift from what Gecko actually
 * does the moment one of the four surfaces forgot to write both.</p>
 *
 * <p>Always and never are mutually exclusive by construction (one value per
 * language), which is what makes the sheet's two switches a three-way
 * choice rather than two booleans: turning one on is a write of that value,
 * turning it off is a write of {@code offer}.</p>
 *
 * <p>Keys are the bare language codes Gecko's model list uses ({@code es},
 * not {@code es-ES}); {@link #languageCode(String)} reduces a document tag
 * to that so a detected {@code pt-BR} reads and writes the {@code pt} row.</p>
 */
public final class TranslationLanguageSettings {

    public static final String ALWAYS = "always";
    public static final String OFFER = "offer";
    public static final String NEVER = "never";

    private TranslationLanguageSettings() {
    }

    /** The bare language subtag of a BCP 47 tag ({@code pt-BR} → {@code pt}); null when unusable. */
    @Nullable
    public static String languageCode(@Nullable String tag) {
        if (TextUtils.isEmpty(tag)) return null;
        String language = Locale.forLanguageTag(tag).getLanguage();
        return TextUtils.isEmpty(language) ? null : language;
    }

    /**
     * The stored state for a language: one of {@link #ALWAYS}, {@link #OFFER},
     * {@link #NEVER}. Reads the whole map and picks the row rather than the
     * single-key call, so a language Gecko has no row for (the common case)
     * answers {@code offer} instead of an error.
     */
    @NonNull
    public static GeckoResult<String> get(@Nullable String tag) {
        final String code = languageCode(tag);
        if (code == null) return GeckoResult.fromValue(OFFER);
        return TranslationsController.RuntimeTranslation.getLanguageSettings().map(
                settings -> normalize(lookup(settings, code)));
    }

    /** Writes one of the three states for the language of {@code tag}. */
    @NonNull
    public static GeckoResult<Void> set(@Nullable String tag, @NonNull String state) {
        final String code = languageCode(tag);
        if (code == null) return GeckoResult.fromValue(null);
        return TranslationsController.RuntimeTranslation.setLanguageSettings(code, normalize(state));
    }

    /** Every language Gecko holds a non-default state for, keyed by bare code. */
    @NonNull
    public static GeckoResult<Map<String, String>> all() {
        return TranslationsController.RuntimeTranslation.getLanguageSettings();
    }

    public static boolean isAlways(@Nullable String state) {
        return ALWAYS.equals(normalize(state));
    }

    public static boolean isNever(@Nullable String state) {
        return NEVER.equals(normalize(state));
    }

    /**
     * Gecko spells the values in lower case; tolerate any casing (and null)
     * on the way in, so a comparison against our constants can't miss on a
     * cosmetic difference.
     */
    @NonNull
    public static String normalize(@Nullable String state) {
        if (state == null) return OFFER;
        String s = state.trim().toLowerCase(Locale.ROOT);
        if (ALWAYS.equals(s) || NEVER.equals(s)) return s;
        return OFFER;
    }

    @Nullable
    private static String lookup(@Nullable Map<String, String> settings, @NonNull String code) {
        if (settings == null) return null;
        String direct = settings.get(code);
        if (direct != null) return direct;
        // A row keyed by a fuller tag (es-ES) still belongs to this language.
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            if (code.equals(languageCode(entry.getKey()))) return entry.getValue();
        }
        return null;
    }
}

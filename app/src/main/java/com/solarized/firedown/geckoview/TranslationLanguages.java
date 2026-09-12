package com.solarized.firedown.geckoview;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.geckoview.TranslationsController;

import java.util.Locale;

/**
 * Small pure helpers over the language values Gecko's built-in translator
 * hands out (BCP 47 tags, {@link TranslationsController.Language} entries).
 * Shared by the offer snackbar, the translate sheet and the Translations
 * settings screen so the three surfaces name a language the same way.
 */
public final class TranslationLanguages {

    private TranslationLanguages() {}

    /**
     * Human-readable name for a BCP 47 tag in the device locale ("German",
     * "Deutsch"…). Gecko's {@code Language.localizedDisplayName} is preferred
     * when a {@link TranslationsController.Language} is at hand (see
     * {@link #displayName(TranslationsController.Language)}); this form is for
     * the bare tags on a {@code TranslationState}. Falls back to the tag
     * itself, never to an empty string, so a snackbar can always be composed.
     */
    @NonNull
    public static String displayName(@Nullable String tag) {
        if (TextUtils.isEmpty(tag)) return "";
        String name = Locale.forLanguageTag(tag).getDisplayName();
        return TextUtils.isEmpty(name) ? tag : name;
    }

    @NonNull
    public static String displayName(@Nullable TranslationsController.Language language) {
        if (language == null) return "";
        if (!TextUtils.isEmpty(language.localizedDisplayName)) {
            return language.localizedDisplayName;
        }
        return displayName(language.code);
    }
}

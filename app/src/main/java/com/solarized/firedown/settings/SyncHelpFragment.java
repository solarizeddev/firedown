package com.solarized.firedown.settings;

import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.google.android.material.color.MaterialColors;
import com.solarized.firedown.R;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Offline "How sync works" help screen — the recovery-code / encryption FAQ,
 * promoted from a dialog ({@code SyncSettingsFragment#showHelpDialog}) to a full
 * NavController destination so the content (which grows once downloads sync /
 * paid storage are explained) scrolls naturally and handles large fonts and
 * TalkBack better than a modal. Static Q/A from string resources; no network.
 *
 * <p>{@code @AndroidEntryPoint} is required because {@link BasePreferenceFragment}
 * declares an {@code @Inject} field — Hilt members-injection runs through the
 * concrete leaf's generated injector.</p>
 */
@AndroidEntryPoint
public class SyncHelpFragment extends BasePreferenceFragment {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.settings_sync_help, rootKey);
        colorQuestionTitles();
    }

    /**
     * Tints each FAQ question (the preference titles) with the brand colour
     * ({@code colorPrimary}); the answers (summaries) stay the muted secondary
     * colour, so the screen reads as Q (brand) / A (body). Done in code because
     * a plain {@link Preference} has no title-colour attribute.
     */
    private void colorQuestionTitles() {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return;
        }
        int brand = MaterialColors.getColor(requireContext(),
                com.google.android.material.R.attr.colorPrimary, Color.RED);
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference preference = screen.getPreference(i);
            CharSequence title = preference.getTitle();
            if (title == null) {
                continue;
            }
            SpannableString colored = new SpannableString(title);
            colored.setSpan(new ForegroundColorSpan(brand), 0, colored.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            preference.setTitle(colored);
        }
    }
}

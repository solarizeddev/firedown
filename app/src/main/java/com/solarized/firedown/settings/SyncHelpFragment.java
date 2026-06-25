package com.solarized.firedown.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;

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
    }
}

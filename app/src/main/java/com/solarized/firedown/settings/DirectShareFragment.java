package com.solarized.firedown.settings;

import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.preference.Preference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Settings sub-screen for Direct share (WebRTC P2P) networking — the STUN
 * chooser and the optional custom TURN relay, moved off the root Settings
 * list (expert set-once config; the STUN row's summary is a raw {@code stun:}
 * URL that didn't belong on the root). The dialog logic moved here verbatim
 * from SettingsFragment; see the P2P section of CLAUDE.md for why the STUN
 * row is a click-row chooser and why the custom URL is scheme-validated.
 */
@AndroidEntryPoint
public class DirectShareFragment extends BasePreferenceFragment
        implements Preference.OnPreferenceClickListener {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.settings_direct_share, rootKey);

        Preference stun = findPreference(Preferences.SETTINGS_P2P_STUN);
        if (stun != null) {
            stun.setOnPreferenceClickListener(this);
        }
        Preference turn = findPreference(Preferences.SETTINGS_P2P_TURN_URL);
        if (turn != null) {
            turn.setOnPreferenceClickListener(this);
        }

        tintIcons();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateStunSummary();
        updateTurnSummary();
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        if (Preferences.SETTINGS_P2P_STUN.equals(key)) {
            showStunChooser();
            return true;
        }
        if (Preferences.SETTINGS_P2P_TURN_URL.equals(key)) {
            showTurnDialog();
            return true;
        }
        return false;
    }

    /**
     * P2P STUN chooser — a single-choice dialog over the shipped servers plus
     * "Custom…". A click-row (not a ListPreference) so re-picking the
     * already-selected "Custom…" still reopens the URL editor.
     */
    private void showStunChooser() {
        String[] labels = getResources().getStringArray(R.array.settings_p2p_stun_entries);
        String[] values = getResources().getStringArray(R.array.settings_p2p_stun_values);
        String current = mSharedPreferences.getString(
                Preferences.SETTINGS_P2P_STUN, Preferences.DEFAULT_P2P_STUN);
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            boolean isCustom = Preferences.P2P_STUN_CUSTOM_VALUE.equals(values[i]);
            if ((isCustom && Preferences.P2P_STUN_CUSTOM_VALUE.equals(current))
                    || values[i].equals(current)) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_p2p_stun)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (Preferences.P2P_STUN_CUSTOM_VALUE.equals(values[which])) {
                        showCustomStunDialog();
                    } else {
                        mSharedPreferences.edit()
                                .putString(Preferences.SETTINGS_P2P_STUN, values[which])
                                .apply();
                        updateStunSummary();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * "Custom…" STUN URL editor. Validates the {@code stun:}/{@code turn:}
     * scheme before persisting — an invalid value would throw in the engine's
     * RTCPeerConnection constructor and fail every share with a generic error.
     * An empty/cancelled entry leaves the previous choice untouched.
     */
    private void showCustomStunDialog() {
        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("stun:host:3478");
        input.setText(mSharedPreferences.getString(Preferences.SETTINGS_P2P_STUN_CUSTOM, ""));
        int padding = getResources().getDimensionPixelSize(R.dimen.address_bar_inset);
        FrameLayout container = new FrameLayout(requireContext());
        container.setPadding(padding, 0, padding, 0);
        container.addView(input);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_p2p_stun_custom_title)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (!url.matches("(?i)^(stun|turn)s?:.+")) {
                        Snackbar.make(requireView(), R.string.settings_p2p_stun_invalid,
                                Snackbar.LENGTH_LONG).show();
                        return;
                    }
                    mSharedPreferences.edit()
                            .putString(Preferences.SETTINGS_P2P_STUN_CUSTOM, url)
                            .putString(Preferences.SETTINGS_P2P_STUN,
                                    Preferences.P2P_STUN_CUSTOM_VALUE)
                            .apply();
                    updateStunSummary();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateStunSummary() {
        Preference pref = findPreference(Preferences.SETTINGS_P2P_STUN);
        if (pref == null) {
            return;
        }
        String value = mSharedPreferences.getString(
                Preferences.SETTINGS_P2P_STUN, Preferences.DEFAULT_P2P_STUN);
        if (Preferences.P2P_STUN_CUSTOM_VALUE.equals(value)) {
            pref.setSummary(Preferences.getP2pStunServer(mSharedPreferences));
        } else {
            pref.setSummary(value);
        }
    }

    /**
     * Optional TURN relay editor — url + username + password, all in one
     * dialog. Empty url = no relay (the default); a "Clear" action wipes all
     * three. The {@code turn:}/{@code turns:} scheme is validated before
     * persisting so a typo can't fail every future share in the engine's
     * RTCPeerConnection constructor.
     */
    private void showTurnDialog() {
        int padding = getResources().getDimensionPixelSize(R.dimen.address_bar_inset);
        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, 0, padding, 0);

        final EditText urlInput = new EditText(requireContext());
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setHint(R.string.settings_p2p_turn_url_hint);
        urlInput.setText(mSharedPreferences.getString(Preferences.SETTINGS_P2P_TURN_URL, ""));
        container.addView(urlInput);

        final EditText userInput = new EditText(requireContext());
        userInput.setInputType(InputType.TYPE_CLASS_TEXT);
        userInput.setHint(R.string.settings_p2p_turn_user_hint);
        userInput.setText(mSharedPreferences.getString(Preferences.SETTINGS_P2P_TURN_USER, ""));
        container.addView(userInput);

        final EditText credInput = new EditText(requireContext());
        credInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        credInput.setHint(R.string.settings_p2p_turn_cred_hint);
        credInput.setText(mSharedPreferences.getString(Preferences.SETTINGS_P2P_TURN_CRED, ""));
        container.addView(credInput);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_p2p_turn)
                .setMessage(R.string.settings_p2p_turn_message)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String url = urlInput.getText().toString().trim();
                    if (!url.matches("(?i)^turns?:.+")) {
                        Snackbar.make(requireView(), R.string.settings_p2p_turn_invalid,
                                Snackbar.LENGTH_LONG).show();
                        return;
                    }
                    mSharedPreferences.edit()
                            .putString(Preferences.SETTINGS_P2P_TURN_URL, url)
                            .putString(Preferences.SETTINGS_P2P_TURN_USER,
                                    userInput.getText().toString().trim())
                            .putString(Preferences.SETTINGS_P2P_TURN_CRED,
                                    credInput.getText().toString().trim())
                            .apply();
                    updateTurnSummary();
                })
                .setNeutralButton(R.string.settings_p2p_turn_clear, (dialog, which) -> {
                    mSharedPreferences.edit()
                            .remove(Preferences.SETTINGS_P2P_TURN_URL)
                            .remove(Preferences.SETTINGS_P2P_TURN_USER)
                            .remove(Preferences.SETTINGS_P2P_TURN_CRED)
                            .apply();
                    updateTurnSummary();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateTurnSummary() {
        Preference pref = findPreference(Preferences.SETTINGS_P2P_TURN_URL);
        if (pref == null) {
            return;
        }
        Preferences.P2pTurn turn = Preferences.getP2pTurn(mSharedPreferences);
        pref.setSummary(turn != null ? turn.url
                : getString(R.string.settings_p2p_turn_summary));
    }
}

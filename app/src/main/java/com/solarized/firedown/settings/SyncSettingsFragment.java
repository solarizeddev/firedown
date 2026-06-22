package com.solarized.firedown.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.SyncManager;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Bookmark-sync settings sub-screen: enable/disable, show the recovery code,
 * sync now, choose a backend, sign out. All network/crypto work lives in
 * {@link SyncManager}; this fragment only drives the UI state and the dialogs.
 *
 * <p>The enable switch is intercepted (never self-persists): turning it ON opens
 * the setup chooser (create a new identity vs. restore from a code), turning it
 * OFF confirms sign-out. {@link SyncManager} owns the {@code SYNC_ENABLED} pref,
 * so the switch is re-synced from {@link SyncManager#isEnabled()} after every
 * flow rather than letting the preference framework write it.
 */
@AndroidEntryPoint
public class SyncSettingsFragment extends BasePreferenceFragment
        implements Preference.OnPreferenceClickListener {

    @Inject
    SyncManager mSyncManager;

    private SwitchPreferenceCompat mEnableSwitch;
    private Preference mShowCode;
    private Preference mSyncNow;
    private EditTextPreference mBackend;
    private Preference mSignOut;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_sync, rootKey);

        mEnableSwitch = findPreference(Preferences.SYNC_ENABLED);
        mShowCode = findPreference(Preferences.SETTINGS_SYNC_SHOW_CODE);
        mSyncNow = findPreference(Preferences.SETTINGS_SYNC_NOW);
        mBackend = findPreference(Preferences.SYNC_BACKEND_URL);
        mSignOut = findPreference(Preferences.SETTINGS_SYNC_SIGN_OUT);

        if (mEnableSwitch != null) {
            // Never let the switch self-persist — we drive SYNC_ENABLED through
            // SyncManager and re-sync the switch in updateState().
            mEnableSwitch.setOnPreferenceChangeListener((pref, newValue) -> {
                boolean want = Boolean.TRUE.equals(newValue);
                if (want && !mSyncManager.isEnabled()) {
                    showSetupDialog();
                } else if (!want && mSyncManager.isEnabled()) {
                    showSignOutDialog();
                }
                return false;
            });
        }

        if (mBackend != null) {
            mBackend.setText(mSyncManager.backendUrl());
            mBackend.setOnPreferenceChangeListener((pref, newValue) -> {
                mSyncManager.setBackendUrl(newValue == null ? null : newValue.toString());
                // Reflect the resolved value (blank falls back to the default).
                mBackend.setText(mSyncManager.backendUrl());
                return false;
            });
        }

        if (mShowCode != null) {
            mShowCode.setOnPreferenceClickListener(this);
        }
        if (mSyncNow != null) {
            mSyncNow.setOnPreferenceClickListener(this);
        }
        if (mSignOut != null) {
            mSignOut.setOnPreferenceClickListener(this);
        }

        tintIcons();
        updateState();
    }

    @Override
    public void onResume() {
        super.onResume();
        // The worker can flip last-synced state while we're away.
        updateState();
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        switch (key) {
            case Preferences.SETTINGS_SYNC_SHOW_CODE -> showCodeDialog(
                    mSyncManager.recoveryCodeForDisplay(), false);
            case Preferences.SETTINGS_SYNC_NOW -> {
                mSyncManager.syncNow();
                snackbar(getString(R.string.settings_sync_now_started));
            }
            case Preferences.SETTINGS_SYNC_SIGN_OUT -> showSignOutDialog();
        }
        return true;
    }

    /** Reflects persisted state into the switch + dependent-row visibility. */
    private void updateState() {
        boolean on = mSyncManager.isEnabled();
        if (mEnableSwitch != null) {
            mEnableSwitch.setChecked(on);
            mEnableSwitch.setSummary(on
                    ? lastSyncedSummary()
                    : getString(R.string.settings_sync_switch_summary));
        }
        if (mShowCode != null) {
            mShowCode.setVisible(on);
        }
        if (mSyncNow != null) {
            mSyncNow.setVisible(on);
        }
        if (mBackend != null) {
            mBackend.setVisible(on);
        }
        if (mSignOut != null) {
            mSignOut.setVisible(on);
        }
    }

    private String lastSyncedSummary() {
        long at = mSyncManager.lastSyncedAt();
        if (at <= 0) {
            return getString(R.string.settings_sync_never_synced);
        }
        CharSequence rel = DateUtils.getRelativeTimeSpanString(
                at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        return getString(R.string.settings_sync_last_synced, rel);
    }

    /** First-run chooser: start a brand-new identity, or restore from a code. */
    private void showSetupDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_sync_setup_title)
                .setMessage(R.string.settings_sync_setup_message)
                .setPositiveButton(R.string.settings_sync_setup_create,
                        (dialog, which) -> mSyncManager.enableWithNewCode(
                                code -> {
                                    updateState();
                                    showCodeDialog(code, true);
                                }))
                .setNeutralButton(R.string.settings_sync_setup_restore,
                        (dialog, which) -> showRestoreDialog())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Recovery-code entry (new device). On success a pull+merge runs. */
    private void showRestoreDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_sync_restore, null);
        TextInputEditText input = view.findViewById(R.id.sync_code);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_sync_restore_title)
                .setMessage(R.string.settings_sync_restore_message)
                .setView(view)
                .setPositiveButton(R.string.settings_sync_restore_action, (dialog, which) -> {
                    String code = input.getText() == null ? "" : input.getText().toString().trim();
                    if (TextUtils.isEmpty(code)) {
                        return;
                    }
                    mSyncManager.restoreWithCode(code, ok -> {
                        if (!isAdded()) {
                            return;
                        }
                        updateState();
                        snackbar(getString(ok
                                ? R.string.settings_sync_restore_ok
                                : R.string.settings_sync_restore_bad));
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Shows the grouped recovery code with a copy action and the no-recovery warning. */
    private void showCodeDialog(@Nullable String grouped, boolean justCreated) {
        if (grouped == null) {
            snackbar(getString(R.string.settings_sync_code_unavailable));
            return;
        }
        View view = getLayoutInflater().inflate(R.layout.dialog_sync_show_code, null);
        TextView codeText = view.findViewById(R.id.sync_code_text);
        codeText.setText(grouped);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(justCreated
                        ? R.string.settings_sync_code_created_title
                        : R.string.settings_sync_show_code_title)
                .setView(view)
                .setPositiveButton(R.string.settings_sync_code_copy,
                        (dialog, which) -> copyToClipboard(grouped))
                .setNegativeButton(R.string.settings_sync_code_done, null)
                .show();
    }

    private void showSignOutDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_sync_sign_out_title)
                .setMessage(R.string.settings_sync_sign_out_message)
                .setPositiveButton(R.string.settings_sync_sign_out_action, (dialog, which) -> {
                    mSyncManager.disable();
                    updateState();
                    snackbar(getString(R.string.settings_sync_signed_out));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void copyToClipboard(String text) {
        // A clipboard write is a binder call into system_server; never let a
        // dying clipboard service crash the app (see AutoCompleteView hardening).
        try {
            ClipboardManager cm = (ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(
                        getString(R.string.settings_sync_title), text));
                snackbar(getString(R.string.settings_sync_code_copied));
            }
        } catch (RuntimeException e) {
            // Clipboard unavailable — the code is still shown for manual copy.
        }
    }

    private void snackbar(String text) {
        View view = getView();
        if (view != null) {
            Snackbar.make(view, text, Snackbar.LENGTH_LONG).show();
        }
    }
}

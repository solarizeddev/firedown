package com.solarized.firedown.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.AppLock;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.CloudBackupManager;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Cloud Backup settings sub-screen: status, show the recovery code, delete all
 * cloud data. The encrypted backup of finished downloads (to
 * {@code storage.firedown.app}) is a SEPARATE feature from the local "Safe
 * Folder" ({@code file_safe}); copy here never says "vault".
 *
 * <p>There is intentionally NO enable switch — Cloud Backup is action-driven (a
 * finished download's options sheet has a "Back up" action that sets it up on
 * demand, reusing the bookmark-sync recovery code). This screen is the manager.
 * The Manage category is hidden until Cloud Backup is set up, so the not-set-up
 * screen is just the status line + the FAQ.
 */
@AndroidEntryPoint
public class CloudBackupSettingsFragment extends BasePreferenceFragment
        implements Preference.OnPreferenceClickListener {

    @Inject
    CloudBackupManager mCloudBackup;

    @Inject
    AppLock mAppLock;

    private Preference mStatus;
    private Preference mHelp;
    private Preference mShowCode;
    private Preference mDeleteData;
    private PreferenceCategory mCatManage;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_cloud_backup, rootKey);

        mStatus = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_STATUS);
        mHelp = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_HELP);
        mShowCode = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_SHOW_CODE);
        mDeleteData = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_DELETE_DATA);
        mCatManage = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_CAT_MANAGE);

        if (mHelp != null) {
            mHelp.setOnPreferenceClickListener(this);
        }
        if (mShowCode != null) {
            mShowCode.setOnPreferenceClickListener(this);
        }
        if (mDeleteData != null) {
            mDeleteData.setOnPreferenceClickListener(this);
        }

        tintIcons();
        updateState();
    }

    @Override
    public void onResume() {
        super.onResume();
        // A backup/restore/delete elsewhere can change usage while we're away.
        updateState();
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        switch (key) {
            case Preferences.SETTINGS_CLOUD_BACKUP_HELP -> showHelpDialog();
            case Preferences.SETTINGS_CLOUD_BACKUP_SHOW_CODE -> authThenShowCode();
            case Preferences.SETTINGS_CLOUD_BACKUP_DELETE_DATA -> showDeleteDataDialog();
        }
        return true;
    }

    /** Reflects set-up state + usage into the status line and section visibility. */
    private void updateState() {
        boolean setUp = mCloudBackup.isSetUp();
        if (mCatManage != null) {
            mCatManage.setVisible(setUp);
        }
        if (mStatus == null) {
            return;
        }
        if (!setUp) {
            mStatus.setSummary(getString(R.string.settings_cloud_backup_not_set_up));
            return;
        }
        // Set up — fill in usage asynchronously (network), guarding against the
        // view going away before the disk-executor callback returns.
        mCloudBackup.loadUsage(usage -> {
            if (!isAdded() || mStatus == null) {
                return;
            }
            if (usage.fileCount < 0 || usage.totalBytes < 0) {
                mStatus.setSummary(getString(R.string.settings_cloud_backup_usage_unavailable));
                return;
            }
            String files = getResources().getQuantityString(
                    R.plurals.settings_cloud_backup_file_count, usage.fileCount, usage.fileCount);
            String size = Formatter.formatShortFileSize(requireContext(), usage.totalBytes);
            mStatus.setSummary(getString(R.string.settings_cloud_backup_usage, files, size));
        });
    }

    private void showHelpDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_cloud_backup_help_title)
                .setMessage(R.string.settings_cloud_backup_help_message)
                .setPositiveButton(R.string.settings_cloud_backup_help_close, null)
                .show();
    }

    /**
     * Reveals the shared recovery code behind a device-auth check (biometric / PIN
     * / pattern) when the device has a secure lock — the code is the master key for
     * both bookmark sync and Cloud Backup, so a borrowed/unlocked phone shouldn't
     * surface it from a tap. With nothing enrolled there's nothing to authenticate
     * against, so it reveals directly.
     */
    private void authThenShowCode() {
        authThenReveal(() -> showCodeDialog(mCloudBackup.recoveryCodeForDisplay()));
    }

    private void authThenReveal(Runnable onAuth) {
        if (!mAppLock.isBiometricEnabled()) {
            onAuth.run();
            return;
        }
        BiometricPrompt prompt = new BiometricPrompt(this,
                ContextCompat.getMainExecutor(requireContext()),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        if (isAdded()) {
                            onAuth.run();
                        }
                    }
                });
        // DEVICE_CREDENTIAL is allowed (PIN/pattern fallback); with it set, no
        // negative button may be configured (the API rejects it) — mirror LockActivity.
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.settings_cloud_backup_show_code_title))
                .setSubtitle(getString(R.string.settings_cloud_backup_auth_subtitle))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();
        prompt.authenticate(info);
    }

    /** Shows the grouped recovery code with a copy action (shared show-code layout). */
    private void showCodeDialog(@Nullable String grouped) {
        if (grouped == null) {
            snackbar(getString(R.string.settings_cloud_backup_code_unavailable));
            return;
        }
        View view = getLayoutInflater().inflate(R.layout.dialog_sync_show_code, null);
        TextView codeText = view.findViewById(R.id.sync_code_text);
        codeText.setText(grouped);
        // The "I've saved my code" gate is only for first-create (bookmark sync's
        // flow); here the account already exists, so it's hidden and Done is free.
        view.findViewById(R.id.sync_code_saved_check).setVisibility(View.GONE);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_cloud_backup_show_code_title)
                .setView(view)
                .setNeutralButton(R.string.settings_sync_code_copy, null)
                .setPositiveButton(R.string.settings_sync_code_done, null)
                .create();
        dialog.show();
        // Copy without closing the dialog (suppress the default dismiss).
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> copyToClipboard(grouped));
    }

    private void showDeleteDataDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_cloud_backup_delete_title)
                .setMessage(R.string.settings_cloud_backup_delete_message)
                .setPositiveButton(R.string.settings_cloud_backup_delete_action, (dialog, which) -> {
                    snackbar(getString(R.string.settings_cloud_backup_delete_started));
                    mCloudBackup.deleteAllData(ok -> {
                        if (!isAdded()) {
                            return;
                        }
                        updateState();
                        snackbar(getString(ok
                                ? R.string.settings_cloud_backup_delete_done
                                : R.string.settings_cloud_backup_delete_failed));
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void copyToClipboard(String text) {
        // A clipboard write is a binder call into system_server; never let a dying
        // clipboard service crash the app (see AutoCompleteView hardening).
        try {
            ClipboardManager cm = (ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(
                        getString(R.string.settings_cloud_backup_title), text));
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

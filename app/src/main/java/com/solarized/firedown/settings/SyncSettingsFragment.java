package com.solarized.firedown.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.text.TextUtils;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.AppLock;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.SyncManager;
import com.solarized.firedown.utils.NavigationUtils;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Sync hub — a THIN account page. It routes to each feature's own screen and owns
 * the SHARED recovery code (one code derives both bookmark sync and the encrypted
 * downloads backup). The per-feature bookmark actions — the master toggle, Sync
 * now, Delete-from-server — deliberately do NOT live here; they moved to the
 * focused {@link BookmarksSyncFragment} so the hub stays uncluttered (the
 * bookmarks-list overflow and this hub's Bookmarks row land on that same screen).
 *
 * <p>What the hub keeps: a Bookmarks nav row (with a live on/off summary), a
 * Downloads-backup nav row (with a live usage / "backing up…" summary), the shared
 * recovery-code reveal/export behind a device-auth gate, and the offline
 * encryption FAQ. All network/crypto work lives in {@link SyncManager} /
 * {@link CloudBackupManager}.
 *
 * <p>There is intentionally NO backend-server picker here: bookmark sync is a
 * free, end-to-end-encrypted feature pinned to the hosted server. A configurable /
 * self-hosted backend belongs to the (paid) downloads backup, where pointing at
 * your own object storage is the point.
 */
@AndroidEntryPoint
public class SyncSettingsFragment extends BasePreferenceFragment
        implements Preference.OnPreferenceClickListener {

    @Inject
    SyncManager mSyncManager;

    @Inject
    AppLock mAppLock;

    @Inject
    CloudBackupManager mCloudBackup;

    private Preference mBookmarks;
    private Preference mDownloadsBackup;
    private Preference mHelp;
    private Preference mShowCode;
    private Preference mExportCode;
    private Preference mLinkCode;
    // The Recovery-code category is SHARED (bookmarks + downloads) and shown once
    // the account exists — bookmarks on OR a download has been backed up.
    private Preference mCatCode;

    /** SAF "create document" for exporting the recovery code to a text file. */
    private final ActivityResultLauncher<String> mExportCodePicker =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"),
                    this::onExportFilePicked);

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Live "Downloads backup" row status: reflect a running upload/restore so
        // the row reads "Backing up your downloads…" while a transfer is active.
        WorkManager.getInstance(requireContext().getApplicationContext())
                .getWorkInfosByTagLiveData(CloudBackupManager.WORK_TAG)
                .observe(getViewLifecycleOwner(), infos -> {
                    boolean active = false;
                    if (infos != null) {
                        for (WorkInfo wi : infos) {
                            WorkInfo.State s = wi.getState();
                            if (s == WorkInfo.State.RUNNING || s == WorkInfo.State.ENQUEUED) {
                                active = true;
                                break;
                            }
                        }
                    }
                    updateDownloadsSummary(active);
                });
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_sync, rootKey);

        mBookmarks = findPreference(Preferences.SETTINGS_SYNC_BOOKMARKS_LINK);
        mDownloadsBackup = findPreference(Preferences.SETTINGS_CLOUD_BACKUP);
        mHelp = findPreference(Preferences.SETTINGS_SYNC_HELP);
        mShowCode = findPreference(Preferences.SETTINGS_SYNC_SHOW_CODE);
        mExportCode = findPreference(Preferences.SETTINGS_SYNC_EXPORT_CODE);
        mLinkCode = findPreference(Preferences.SETTINGS_SYNC_LINK_CODE);
        mCatCode = findPreference(Preferences.SETTINGS_SYNC_CAT_CODE);

        if (mBookmarks != null) {
            mBookmarks.setOnPreferenceClickListener(this);
        }
        if (mDownloadsBackup != null) {
            mDownloadsBackup.setOnPreferenceClickListener(this);
        }
        if (mHelp != null) {
            mHelp.setOnPreferenceClickListener(this);
        }
        if (mShowCode != null) {
            mShowCode.setOnPreferenceClickListener(this);
        }
        if (mExportCode != null) {
            mExportCode.setOnPreferenceClickListener(this);
        }
        if (mLinkCode != null) {
            mLinkCode.setOnPreferenceClickListener(this);
        }

        tintIcons();
        updateState();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Bookmark sync can be turned on/off (and run) from the focused screen we
        // navigate to, so refresh the row summaries + code visibility on return.
        updateState();
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        switch (key) {
            case Preferences.SETTINGS_SYNC_BOOKMARKS_LINK ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_sync_to_bookmarks_sync);
            case Preferences.SETTINGS_CLOUD_BACKUP ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_sync_to_cloud_backup);
            case Preferences.SETTINGS_SYNC_HELP ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_sync_to_help);
            case Preferences.SETTINGS_SYNC_SHOW_CODE -> authThenShowCode();
            case Preferences.SETTINGS_SYNC_EXPORT_CODE -> showExportCaveatDialog();
            case Preferences.SETTINGS_SYNC_LINK_CODE -> showLinkDialog();
        }
        return true;
    }

    /**
     * Reveals the recovery code behind a device-auth check (biometric / PIN /
     * pattern) when the device has a secure lock — the code is the master secret
     * for the whole synced vault, so a borrowed/unlocked phone shouldn't surface
     * it from a tap. When no biometric or device credential is enrolled there is
     * nothing to authenticate against, so it reveals directly.
     */
    private void authThenShowCode() {
        authThenReveal(() -> showCodeDialog(mSyncManager.recoveryCodeForDisplay()));
    }

    /**
     * Runs {@code onAuth} behind a device-auth check (biometric / PIN / pattern)
     * when the device has a secure lock. Both revealing the recovery code and
     * exporting it to a file expose the master key for the whole synced vault, so
     * both gate through here. With no biometric / device credential enrolled there
     * is nothing to authenticate against, so {@code onAuth} runs directly.
     */
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
                .setTitle(getString(R.string.settings_sync_show_code_title))
                .setSubtitle(getString(R.string.settings_sync_auth_subtitle))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();
        prompt.authenticate(info);
    }

    /**
     * Point-of-action caveat before exporting: the file is plaintext, so it's a
     * transport to the user's password manager, not a vault. Explained BEFORE the
     * file lands (the in-file caveat is only read afterwards). On Continue →
     * device-auth → SAF picker.
     */
    private void showExportCaveatDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_sync_export_title)
                .setMessage(R.string.settings_sync_export_caveat_message)
                .setPositiveButton(R.string.settings_sync_export_continue,
                        (dialog, which) -> authThenExportCode())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Auth-gated export of the recovery code to a user-chosen text file (SAF). */
    private void authThenExportCode() {
        authThenReveal(() ->
                mExportCodePicker.launch(getString(R.string.settings_sync_export_file_name)));
    }

    /**
     * SAF create-document result: write the recovery code (with a short "this is
     * the only key" preamble) to the chosen file. The write runs off the main
     * thread; the result is reported in a snackbar. The plaintext-on-disk exposure
     * is the same as the existing Copy action and is a deliberate user action,
     * already behind the device-auth gate above.
     */
    private void onExportFilePicked(@Nullable Uri uri) {
        if (uri == null) {
            return; // user cancelled the picker
        }
        String grouped = mSyncManager.recoveryCodeForDisplay();
        if (grouped == null) {
            snackbar(getString(R.string.settings_sync_code_unavailable));
            return;
        }
        Context appContext = requireContext().getApplicationContext();
        String body = getString(R.string.settings_sync_export_file_body, grouped);
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            boolean ok = writeTextToUri(appContext, uri, body);
            main.post(() -> {
                if (isAdded()) {
                    snackbar(getString(ok
                            ? R.string.settings_sync_export_done
                            : R.string.settings_sync_export_failed));
                }
            });
        }, "sync-code-export").start();
    }

    private static boolean writeTextToUri(Context ctx, Uri uri, String text) {
        try (OutputStream os = ctx.getContentResolver().openOutputStream(uri, "wt")) {
            if (os == null) {
                return false;
            }
            os.write(text.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Reflects persisted state into the per-feature row summaries + the shared
     *  recovery-code visibility. */
    /**
     * "I have a recovery code" — links this device to an existing account (restore
     * downloads backup + storage credit) via {@link SyncManager#linkWithCode},
     * which stores the code WITHOUT enabling bookmark sync. Reuses the code-input
     * dialog layout.
     */
    private void showLinkDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_sync_restore, null);
        TextInputEditText input = view.findViewById(R.id.sync_code);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_sync_link_dialog_title)
                .setMessage(R.string.settings_sync_link_dialog_message)
                .setView(view)
                .setPositiveButton(R.string.settings_sync_link_action, (dialog, which) -> {
                    String code = input.getText() == null ? "" : input.getText().toString().trim();
                    if (TextUtils.isEmpty(code)) {
                        return;
                    }
                    mSyncManager.linkWithCode(code, ok -> {
                        if (!isAdded()) {
                            return;
                        }
                        updateState();
                        snackbar(getString(ok
                                ? R.string.settings_sync_link_ok
                                : R.string.settings_sync_link_bad));
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateState() {
        boolean on = mSyncManager.isEnabled();
        // The account exists once EITHER feature is set up — bookmarks on, or a
        // download has been backed up. The shared Recovery-code section keys off
        // this, not bookmarks alone (a downloads-only user still needs the code).
        boolean accountExists = on || mCloudBackup.isSetUp();
        // Bookmarks row reads its on/off state inline (the toggle itself lives on
        // the focused screen this row opens).
        if (mBookmarks != null) {
            mBookmarks.setSummary(on
                    ? lastSyncedSummary()
                    : getString(R.string.settings_sync_switch_summary));
        }
        // Recovery code is SHARED → shown once the account exists.
        if (mCatCode != null) {
            mCatCode.setVisible(accountExists);
        }
        if (mShowCode != null) {
            mShowCode.setVisible(accountExists);
        }
        if (mExportCode != null) {
            mExportCode.setVisible(accountExists);
        }
        // The complement of the recovery-code section: offer "I have a recovery
        // code" only when this device has NO code yet (fresh install / new device).
        if (mLinkCode != null) {
            mLinkCode.setVisible(!mCloudBackup.hasAccount());
        }
        updateDownloadsSummary(false);
    }

    /**
     * Updates the "Downloads backup" row summary: a live "Backing up…" while a
     * transfer runs, else the backed-up usage ("N files · X MB") once set up, else
     * the generic invitation. The usage read is async (network), guarded against
     * the view going away.
     */
    private void updateDownloadsSummary(boolean active) {
        if (mDownloadsBackup == null) {
            return;
        }
        if (active) {
            mDownloadsBackup.setSummary(getString(R.string.settings_sync_downloads_active));
            return;
        }
        if (!mCloudBackup.isSetUp()) {
            mDownloadsBackup.setSummary(getString(R.string.settings_sync_downloads_summary));
            return;
        }
        mCloudBackup.loadUsage(usage -> {
            if (!isAdded() || mDownloadsBackup == null) {
                return;
            }
            if (!usage.setUp || usage.fileCount < 0 || usage.totalBytes < 0) {
                mDownloadsBackup.setSummary(getString(R.string.settings_sync_downloads_summary));
                return;
            }
            String files = getResources().getQuantityString(
                    R.plurals.settings_cloud_backup_file_count, usage.fileCount, usage.fileCount);
            String size = Formatter.formatShortFileSize(requireContext(), usage.totalBytes);
            mDownloadsBackup.setSummary(getString(R.string.settings_cloud_backup_usage, files, size));
        });
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

    /** Shows the grouped recovery code with a copy action and the no-recovery
     *  warning. The hub only ever VIEWS an existing code (behind the device-auth
     *  gate); the just-created reveal is owned by the setup flow on the focused
     *  Bookmarks screen, so there is no "just created" checkbox path here. */
    private void showCodeDialog(@Nullable String grouped) {
        if (grouped == null) {
            snackbar(getString(R.string.settings_sync_code_unavailable));
            return;
        }
        View view = getLayoutInflater().inflate(R.layout.dialog_sync_show_code, null);
        TextView codeText = view.findViewById(R.id.sync_code_text);
        codeText.setText(grouped);

        // Viewing an existing code (account already set up), so the "I've saved it"
        // gate is hidden and Done is always enabled.
        CheckBox savedCheck = view.findViewById(R.id.sync_code_saved_check);
        savedCheck.setVisibility(View.GONE);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_sync_show_code_title)
                .setView(view)
                // Copy is the NEUTRAL action so it copies WITHOUT closing the
                // dialog (its click listener is overridden below to suppress the
                // default dismiss).
                .setNeutralButton(R.string.settings_sync_code_copy, null)
                .setPositiveButton(R.string.settings_sync_code_done, null)
                .create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> copyToClipboard(grouped));
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

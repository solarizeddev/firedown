package com.solarized.firedown.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
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
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.solarized.firedown.AppLock;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.SyncManager;
import com.solarized.firedown.sync.SyncWorker;
import com.solarized.firedown.sync.VaultSmokeTest;
import com.solarized.firedown.utils.NavigationUtils;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.OkHttpClient;

/**
 * Bookmark-sync settings sub-screen: enable/disable, show the recovery code,
 * sync now, sign out. All network/crypto work lives in {@link SyncManager};
 * this fragment only drives the UI state and the dialogs.
 *
 * <p>There is intentionally NO backend-server picker here: bookmark sync is a
 * free, end-to-end-encrypted feature pinned to the hosted server, and anyone who
 * doesn't want to use it has the manual Netscape import/export as the escape
 * hatch. A configurable / self-hosted backend belongs to the (paid) downloads
 * vault, where pointing at your own object storage is the point.
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

    @Inject
    AppLock mAppLock;

    /** Shared OkHttp client — used only by the debug vault smoke test below. */
    @Inject
    OkHttpClient mHttpClient;

    private SwitchPreferenceCompat mEnableSwitch;
    private Preference mHelp;
    private Preference mShowCode;
    private Preference mExportCode;
    private Preference mSyncNow;
    private Preference mDeleteData;
    // Section headers — hidden while sync is off so the off-state screen is just
    // the switch + FAQ. Turn-off lives on the master switch (no separate row).
    private Preference mCatCode;
    private Preference mCatManage;

    /** Last sync state seen, so the failure snackbar fires only on a fresh
     *  SYNCING -> ERROR transition (not on every entry with a stale error). */
    private int mSyncState = SyncManager.STATE_OFF;

    /** SAF "create document" for exporting the recovery code to a text file. */
    private final ActivityResultLauncher<String> mExportCodePicker =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"),
                    this::onExportFilePicked);

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Mirror the bookmarks screen: a sync that fails while this screen is open
        // (manual "Sync now" OR a background/automatic run) surfaces a snackbar,
        // gated on the SYNCING -> ERROR transition so a stale error on entry shows
        // nothing. This is now the single source of the failure snackbar — the
        // manual "Sync now" handler no longer fires its own (it would double here).
        mSyncManager.observeState().observe(getViewLifecycleOwner(), state -> {
            int next = state == null ? SyncManager.STATE_OFF : state;
            if (next == SyncManager.STATE_ERROR && mSyncState == SyncManager.STATE_SYNCING) {
                snackbar(getString(R.string.settings_sync_now_failed));
            }
            mSyncState = next;
        });
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_sync, rootKey);

        mEnableSwitch = findPreference(Preferences.SYNC_ENABLED);
        mHelp = findPreference(Preferences.SETTINGS_SYNC_HELP);
        mShowCode = findPreference(Preferences.SETTINGS_SYNC_SHOW_CODE);
        mExportCode = findPreference(Preferences.SETTINGS_SYNC_EXPORT_CODE);
        mSyncNow = findPreference(Preferences.SETTINGS_SYNC_NOW);
        mDeleteData = findPreference(Preferences.SETTINGS_SYNC_DELETE_DATA);
        mCatCode = findPreference(Preferences.SETTINGS_SYNC_CAT_CODE);
        mCatManage = findPreference(Preferences.SETTINGS_SYNC_CAT_MANAGE);

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

        if (mHelp != null) {
            mHelp.setOnPreferenceClickListener(this);
        }
        if (mShowCode != null) {
            mShowCode.setOnPreferenceClickListener(this);
        }
        if (mExportCode != null) {
            mExportCode.setOnPreferenceClickListener(this);
        }
        if (mSyncNow != null) {
            mSyncNow.setOnPreferenceClickListener(this);
        }
        if (mDeleteData != null) {
            mDeleteData.setOnPreferenceClickListener(this);
        }

        tintIcons();
        updateState();
        addVaultSmokeTestRow();
    }

    /**
     * Debug-only row that runs the encrypted-storage vault round-trip against the
     * live {@code storage.firedown.app} server, reusing the SAME recovery code the
     * bookmark sync stores (so the vault account is the same identity). Never added
     * in a release build. There is no real vault UI yet; this is the on-device
     * smoke test for the storage client before that exists.
     */
    private void addVaultSmokeTestRow() {
        if (!BuildConfig.DEBUG) {
            return;
        }
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return;
        }
        Preference row = new Preference(requireContext());
        row.setKey("debug.vault.smoke");
        row.setPersistent(false);
        row.setTitle("Debug: test storage vault");
        row.setSummary("Round-trip a file through storage.firedown.app");
        row.setOnPreferenceClickListener(pref -> {
            runVaultSmokeTest(pref);
            return true;
        });
        screen.addPreference(row);
    }

    private void runVaultSmokeTest(Preference row) {
        row.setEnabled(false);
        row.setSummary("Running…");
        snackbar("Vault smoke test started…");
        Context appContext = requireContext().getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            VaultSmokeTest.Result result = VaultSmokeTest.run(
                    appContext, mHttpClient, Preferences.STORAGE_DEFAULT_BACKEND);
            main.post(() -> {
                if (!isAdded()) {
                    return;
                }
                row.setEnabled(true);
                row.setSummary(result.message);
                snackbar(result.message);
            });
        }, "vault-smoke-test").start();
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
            case Preferences.SETTINGS_SYNC_HELP ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_sync_to_help);
            case Preferences.SETTINGS_SYNC_SHOW_CODE -> authThenShowCode();
            case Preferences.SETTINGS_SYNC_EXPORT_CODE -> showExportCaveatDialog();
            case Preferences.SETTINGS_SYNC_NOW -> startSyncNow();
            case Preferences.SETTINGS_SYNC_DELETE_DATA -> showDeleteDataDialog();
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
        authThenReveal(() -> showCodeDialog(mSyncManager.recoveryCodeForDisplay(), false));
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

    /**
     * Runs "Sync now" and reports the terminal result — synced-count or failure —
     * by observing the one-shot work, instead of leaving the user with only the
     * "syncing…" hint. A transient (network) failure stays in WorkManager's retry
     * backoff and simply doesn't reach a terminal state here, which is fine.
     */
    private void startSyncNow() {
        UUID id = mSyncManager.syncNow();
        if (id == null) {
            return; // disabled (row is hidden in that state) — guard anyway
        }
        snackbar(getString(R.string.settings_sync_now_started));
        WorkManager wm = WorkManager.getInstance(requireContext().getApplicationContext());
        final LiveData<WorkInfo> live = wm.getWorkInfoByIdLiveData(id);
        live.observe(getViewLifecycleOwner(), new Observer<WorkInfo>() {
            @Override
            public void onChanged(WorkInfo info) {
                if (info == null || !info.getState().isFinished()) {
                    return;
                }
                live.removeObserver(this);
                if (!isAdded()) {
                    return;
                }
                String status = info.getOutputData().getString(SyncWorker.KEY_STATUS);
                if (SyncWorker.STATUS_OK.equals(status)) {
                    int count = info.getOutputData().getInt(SyncWorker.KEY_COUNT, 0);
                    snackbar(getResources().getQuantityString(
                            R.plurals.settings_sync_now_done, count, count));
                    updateState(); // refresh the "last synced" summary
                }
                // A terminal FAILURE surfaces via the SYNCING -> ERROR snackbar in
                // the onViewCreated state observer (shared with the bookmarks
                // screen), so it is not duplicated here.
            }
        });
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
        // The whole Recovery-code + Manage-sync sections appear only once sync is
        // set up; off-state = switch + FAQ only. Hiding the category headers (and,
        // belt-and-suspenders, their rows) keeps an empty header from showing.
        if (mCatCode != null) {
            mCatCode.setVisible(on);
        }
        if (mCatManage != null) {
            mCatManage.setVisible(on);
        }
        if (mShowCode != null) {
            mShowCode.setVisible(on);
        }
        if (mExportCode != null) {
            mExportCode.setVisible(on);
        }
        if (mSyncNow != null) {
            mSyncNow.setVisible(on);
        }
        if (mDeleteData != null) {
            mDeleteData.setVisible(on);
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

        // "I've saved my recovery code" gate — only the FIRST time the code is
        // created. There's no server-side recovery, so make the user actively
        // acknowledge they saved the only key before they can leave the screen
        // (the dialog is also non-cancelable then, so a stray back/outside tap
        // can't skip it). When merely viewing the code later the account is
        // already set up, so the checkbox is hidden and Done is always enabled.
        CheckBox savedCheck = view.findViewById(R.id.sync_code_saved_check);
        savedCheck.setVisibility(justCreated ? View.VISIBLE : View.GONE);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(justCreated
                        ? R.string.settings_sync_code_created_title
                        : R.string.settings_sync_show_code_title)
                .setView(view)
                // Copy is the NEUTRAL action so it copies WITHOUT closing the
                // dialog (its click listener is overridden below to suppress the
                // default dismiss) — the user can copy, then tick the box.
                .setNeutralButton(R.string.settings_sync_code_copy, null)
                .setPositiveButton(R.string.settings_sync_code_done, null)
                .setCancelable(!justCreated)
                .create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> copyToClipboard(grouped));

        if (justCreated) {
            Button done = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            done.setEnabled(false);
            savedCheck.setOnCheckedChangeListener((b, checked) -> done.setEnabled(checked));
        }
    }

    /**
     * Confirms and runs the server-side erasure. Distinct from "Turn off sync":
     * this deletes the encrypted document from the server (right-to-erasure) and,
     * on success, also turns sync off locally — see {@link SyncManager#deleteServerData}.
     */
    private void showDeleteDataDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_sync_delete_title)
                .setMessage(R.string.settings_sync_delete_message)
                .setPositiveButton(R.string.settings_sync_delete_action, (dialog, which) -> {
                    snackbar(getString(R.string.settings_sync_delete_started));
                    mSyncManager.deleteServerData(ok -> {
                        if (!isAdded()) {
                            return;
                        }
                        updateState();
                        snackbar(getString(ok
                                ? R.string.settings_sync_delete_done
                                : R.string.settings_sync_delete_failed));
                    });
                })
                .setNegativeButton(R.string.cancel, null)
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

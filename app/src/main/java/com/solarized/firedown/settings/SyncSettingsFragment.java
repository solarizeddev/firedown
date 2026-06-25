package com.solarized.firedown.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.solarized.firedown.AppLock;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.SyncManager;
import com.solarized.firedown.sync.SyncWorker;

import java.util.UUID;

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

    @Inject
    AppLock mAppLock;

    private SwitchPreferenceCompat mEnableSwitch;
    private Preference mShowCode;
    private Preference mSyncNow;
    private EditTextPreference mBackend;
    private Preference mTest;
    private Preference mSignOut;
    private Preference mDeleteData;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_sync, rootKey);

        mEnableSwitch = findPreference(Preferences.SYNC_ENABLED);
        mShowCode = findPreference(Preferences.SETTINGS_SYNC_SHOW_CODE);
        mSyncNow = findPreference(Preferences.SETTINGS_SYNC_NOW);
        mBackend = findPreference(Preferences.SYNC_BACKEND_URL);
        mTest = findPreference(Preferences.SETTINGS_SYNC_TEST);
        mSignOut = findPreference(Preferences.SETTINGS_SYNC_SIGN_OUT);
        mDeleteData = findPreference(Preferences.SETTINGS_SYNC_DELETE_DATA);

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
            // URI keyboard + a default hint so the field reads as a URL.
            mBackend.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
                editText.setHint(Preferences.SYNC_DEFAULT_BACKEND);
            });
            mBackend.setOnPreferenceChangeListener((pref, newValue) -> {
                // Normalize first (trim, strip path/trailing slash, lowercase
                // scheme+host) so a pasted '.../v1/health' or a trailing slash
                // doesn't read as invalid or double-up the path.
                String normalized = SyncManager.normalizeBackendUrl(
                        newValue == null ? "" : newValue.toString());
                // Blank is allowed (resets to the hosted default); a non-blank
                // value must be a valid https URL or we reject it and keep the old.
                if (!normalized.isEmpty() && !SyncManager.isValidBackendUrl(normalized)) {
                    // Distinguish the common "typed http://" mistake from generic junk
                    // so the message is actionable (https is mandatory — see SyncManager).
                    boolean httpScheme = normalized.regionMatches(true, 0, "http://", 0, 7);
                    snackbar(getString(httpScheme
                            ? R.string.settings_sync_backend_not_https
                            : R.string.settings_sync_backend_invalid));
                    return false;
                }
                mSyncManager.setBackendUrl(normalized);
                // Reflect the resolved value (blank falls back to the default) in
                // both the dialog field and the row summary.
                mBackend.setText(mSyncManager.backendUrl());
                mBackend.setSummary(backendSummary());
                return false;
            });
        }

        if (mTest != null) {
            mTest.setOnPreferenceClickListener(this);
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
        if (mDeleteData != null) {
            mDeleteData.setOnPreferenceClickListener(this);
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
            case Preferences.SETTINGS_SYNC_SHOW_CODE -> authThenShowCode();
            case Preferences.SETTINGS_SYNC_NOW -> startSyncNow();
            case Preferences.SETTINGS_SYNC_TEST -> runConnectionTest();
            case Preferences.SETTINGS_SYNC_SIGN_OUT -> showSignOutDialog();
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
        if (!mAppLock.isBiometricEnabled()) {
            showCodeDialog(mSyncManager.recoveryCodeForDisplay(), false);
            return;
        }
        BiometricPrompt prompt = new BiometricPrompt(this,
                ContextCompat.getMainExecutor(requireContext()),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        if (isAdded()) {
                            showCodeDialog(mSyncManager.recoveryCodeForDisplay(), false);
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
                } else {
                    snackbar(getString(R.string.settings_sync_now_failed));
                }
            }
        });
    }

    /**
     * Row summary for the backend: the active server host, tagged as the hosted
     * Default or a Custom BYO server — so the current server is visible at a
     * glance without opening the dialog.
     */
    private String backendSummary() {
        String host = mSyncManager.backendHost();
        return getString(mSyncManager.isDefaultBackend()
                ? R.string.settings_sync_backend_value_default
                : R.string.settings_sync_backend_value_custom, host);
    }

    /**
     * Pings the configured backend's /v1/health and reports whether it's a
     * reachable Firedown sync server. The probe is unauthenticated, so it
     * validates the ADDRESS independently of the account/key flow. The row's
     * summary shows a transient "testing…" line; the terminal result lands in a
     * snackbar (and the summary is restored on resume / next state refresh).
     */
    private void runConnectionTest() {
        if (mTest != null) {
            mTest.setSummary(getString(R.string.settings_sync_test_running));
        }
        mSyncManager.testBackend(result -> {
            if (!isAdded()) {
                return;
            }
            String message;
            switch (result.status) {
                case OK -> message = TextUtils.isEmpty(result.version)
                        ? getString(R.string.settings_sync_test_ok_no_version)
                        : getString(R.string.settings_sync_test_ok, result.version);
                case NOT_FIREDOWN -> message = getString(R.string.settings_sync_test_not_server);
                default -> message = getString(R.string.settings_sync_test_unreachable);
            }
            snackbar(message);
            if (mTest != null) {
                mTest.setSummary(getString(R.string.settings_sync_test_summary));
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
        if (mShowCode != null) {
            mShowCode.setVisible(on);
        }
        if (mSyncNow != null) {
            mSyncNow.setVisible(on);
        }
        if (mBackend != null) {
            mBackend.setVisible(on);
            mBackend.setSummary(backendSummary());
        }
        if (mTest != null) {
            mTest.setVisible(on);
        }
        if (mSignOut != null) {
            mSignOut.setVisible(on);
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

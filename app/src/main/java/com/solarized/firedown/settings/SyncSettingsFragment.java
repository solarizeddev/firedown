package com.solarized.firedown.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.text.TextUtils;
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
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.AppLock;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.SyncManager;
import com.solarized.firedown.sync.VaultBackupWorker;
import com.solarized.firedown.sync.VaultSmokeTest;
import com.solarized.firedown.utils.NavigationUtils;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.OkHttpClient;

/**
 * The MERGED Cloud screen (toolbar title "Cloud") — pay-per-use downloads backup
 * front and center, bookmarks + account plumbing secondary. This absorbed the old
 * {@code CloudBackupSettingsFragment} sub-screen: the hub-then-sub-screen IA made
 * the plan/status card and the backed-up-files list a four-tap trek from home, and
 * the thing a paying user cares about (the plan, the buy door, the files) was one
 * level deeper than the free bookmarks toggle.
 *
 * <p>Top to bottom: the {@link CloudStatusPreference} status hero (whose
 * not-set-up state is the three-step onboarding roadmap), the MORPHING filled
 * CTA ("Create recovery code" pre-key, "Add storage credit" after), the
 * adopt-a-code door, the Manage-backup Backups row, one secondary inline
 * Bookmarks switch (the focused BookmarksSyncFragment was retired — sync runs
 * automatically once on, so its "Sync now" went with it), the shared recovery
 * code behind a device-auth gate, the offline encryption FAQ, and LAST the two
 * SCOPED erasure rows (bookmarks-from-server / backed-up-files). All
 * network/crypto work lives in {@link SyncManager} / {@link CloudBackupManager}.
 *
 * <p>The hero binds {@link CloudBackupManager#lastStatus()} synchronously on
 * entry (the singleton's last good snapshot) and the async
 * {@link CloudBackupManager#loadStatus} result then updates it in place — so
 * re-entering the screen never flashes the empty state while the network
 * round-trip is in flight.
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

    /** Shared OkHttp client — used only by the debug smoke-test row below. */
    @Inject
    OkHttpClient mHttpClient;

    private CloudStatusPreference mStatus;
    private Preference mBuy;
    private Preference mFiles;
    private Preference mDeleteData;
    private Preference mCatManage;
    private SwitchPreferenceCompat mBookmarksSwitch;
    private Preference mDeleteBookmarks;
    private Preference mHelp;
    private Preference mShowCode;
    private Preference mExportCode;
    private Preference mLinkCode;
    // The Recovery-code category is SHARED (bookmarks + downloads) and shown once
    // the account exists — a recovery code has been created or adopted.
    private Preference mCatCode;

    /** True while a transfer is running, so a usage refresh doesn't clobber the
     *  live "Transfer in progress…" status. */
    private boolean mTransferActive;

    /** Last bookmark-sync state seen, so the failure snackbar fires only on a
     *  fresh SYNCING -> ERROR transition (not on every entry with a stale
     *  error). Ported from the retired focused Bookmarks screen. */
    private int mSyncState = SyncManager.STATE_OFF;

    /** SAF "create document" for exporting the recovery code to a text file. */
    private final ActivityResultLauncher<String> mExportCodePicker =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"),
                    this::onExportFilePicked);

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Live status: while an upload/restore runs, the hero caption reads
        // "Transfer in progress…" instead of the static usage.
        WorkManager.getInstance(requireContext().getApplicationContext())
                .getWorkInfosByTagLiveData(CloudBackupManager.WORK_TAG)
                .observe(getViewLifecycleOwner(), infos -> {
                    boolean active = false;
                    if (infos != null) {
                        for (WorkInfo wi : infos) {
                            WorkInfo.State s = wi.getState();
                            // RUNNING always counts (backup or restore, both are
                            // transfers). ENQUEUED counts only for IDENTIFIED
                            // backups: a legacy pre-tag WorkSpec parked in retry
                            // backoff is not transferring anything — it held
                            // "Transfer in progress…" on this screen for hours
                            // with no actual transfer (on-device ghost).
                            if (s == WorkInfo.State.RUNNING
                                    || (s == WorkInfo.State.ENQUEUED && hasBackupTag(wi))) {
                                active = true;
                                break;
                            }
                        }
                    }
                    // Only react to a TRANSITION: this LiveData also emits on
                    // every worker progress tick, and an unconditional
                    // updateState() here re-fired the network loadStatus per
                    // percent — the idle→active and active→idle edges are the
                    // only ones that change what this screen shows.
                    if (active != mTransferActive) {
                        mTransferActive = active;
                        updateState();
                    }
                });
        // A bookmark sync that fails while this screen is open (a toggle-on run
        // OR a background run) surfaces a snackbar, gated on SYNCING -> ERROR so
        // a stale error on entry shows nothing (ported from the retired
        // BookmarksSyncFragment).
        mSyncManager.observeState().observe(getViewLifecycleOwner(), state -> {
            int next = state == null ? SyncManager.STATE_OFF : state;
            if (next == SyncManager.STATE_ERROR && mSyncState == SyncManager.STATE_SYNCING) {
                snackbar(getString(R.string.settings_sync_now_failed));
            }
            if (next != mSyncState) {
                mSyncState = next;
                updateState(); // reflect a finished sync in the last-synced summary
            }
        });
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_sync, rootKey);

        mStatus = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_STATUS);
        mBuy = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_BUY);
        mFiles = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_FILES);
        mDeleteData = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_DELETE_DATA);
        mCatManage = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_CAT_MANAGE);
        mBookmarksSwitch = findPreference(Preferences.SYNC_ENABLED);
        mDeleteBookmarks = findPreference(Preferences.SETTINGS_SYNC_DELETE_DATA);
        mHelp = findPreference(Preferences.SETTINGS_SYNC_HELP);
        mShowCode = findPreference(Preferences.SETTINGS_SYNC_SHOW_CODE);
        mExportCode = findPreference(Preferences.SETTINGS_SYNC_EXPORT_CODE);
        mLinkCode = findPreference(Preferences.SETTINGS_SYNC_LINK_CODE);
        mCatCode = findPreference(Preferences.SETTINGS_SYNC_CAT_CODE);

        if (mBuy != null) {
            mBuy.setOnPreferenceClickListener(this);
        }
        if (mFiles != null) {
            mFiles.setOnPreferenceClickListener(this);
        }
        if (mDeleteData != null) {
            mDeleteData.setOnPreferenceClickListener(this);
        }
        if (mBookmarksSwitch != null) {
            // Never let the switch self-persist — SyncManager owns SYNC_ENABLED
            // and the switch is re-synced from isEnabled() in updateState().
            mBookmarksSwitch.setOnPreferenceChangeListener((pref, newValue) -> {
                boolean want = Boolean.TRUE.equals(newValue);
                if (want && !mSyncManager.isEnabled()) {
                    // The switch is disabled pre-key (key-first gate), so a code
                    // always exists here — enable with it directly; no setup
                    // chooser (one code, both features, by design).
                    if (mSyncManager.hasCode()) {
                        mSyncManager.enableWithExistingCode();
                        updateState();
                    }
                } else if (!want && mSyncManager.isEnabled()) {
                    showSignOutDialog();
                }
                return false;
            });
        }
        if (mDeleteBookmarks != null) {
            mDeleteBookmarks.setOnPreferenceClickListener(this);
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
        addDebugSmokeTestRow();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Bookmark sync can be toggled on its focused screen, a purchase can land
        // from the buy flow, and a backup/restore/delete elsewhere can change
        // usage — refresh rows + hero on every return.
        updateState();
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        switch (key) {
            case Preferences.SETTINGS_CLOUD_BACKUP_BUY -> {
                // The MORPHING CTA: the next onboarding step. Pre-key it reads
                // "Create recovery code" and runs the create flow (mandatory
                // "I've saved it" gate); with a key it opens the buy flow.
                if (mCloudBackup.hasAccount()) {
                    NavigationUtils.navigateSafe(mNavController, R.id.action_sync_to_buy);
                } else {
                    createCode();
                }
            }
            case Preferences.SETTINGS_CLOUD_BACKUP_FILES ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_sync_to_files);
            case Preferences.SETTINGS_CLOUD_BACKUP_DELETE_DATA -> showDeleteDataDialog();
            case Preferences.SETTINGS_SYNC_DELETE_DATA -> showDeleteBookmarksDialog();
            case Preferences.SETTINGS_SYNC_HELP ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_sync_to_help);
            case Preferences.SETTINGS_SYNC_SHOW_CODE -> authThenShowCode();
            case Preferences.SETTINGS_SYNC_EXPORT_CODE -> showExportCaveatDialog();
            case Preferences.SETTINGS_SYNC_LINK_CODE -> showLinkDialog();
        }
        return true;
    }

    /**
     * Reflects persisted state into every section. The KEY is the account
     * gateway: a recovery code must exist before EITHER feature can be used, so
     * pre-key the CTA morphs to "Create recovery code", the Manage rows are
     * hidden and the Bookmarks switch is disabled. This is what stops a keyless
     * purchase (the buy flow used to mint a code silently mid-checkout — see
     * BuyCreditViewModel).
     */
    private void updateState() {
        boolean hasKey = mCloudBackup.hasAccount();
        boolean setUp = mCloudBackup.isSetUp();
        boolean on = mSyncManager.isEnabled();

        // The MORPHING CTA always shows the next step: "Create recovery code"
        // until a key exists, then "Add storage credit" (the layout binds the
        // title into the filled button).
        if (mBuy != null) {
            mBuy.setTitle(hasKey
                    ? R.string.buy_credit_title
                    : R.string.settings_sync_create_title);
        }
        // The adopt-an-existing-account door: only offered pre-key.
        if (mLinkCode != null) {
            mLinkCode.setVisible(!hasKey);
        }
        applyManageVisibility(hasKey && setUp);
        // Bookmarks switch: disabled without a key (key-first gate); checked
        // state mirrors SyncManager (the switch never self-persists).
        if (mBookmarksSwitch != null) {
            mBookmarksSwitch.setEnabled(hasKey);
            mBookmarksSwitch.setChecked(on);
            mBookmarksSwitch.setSummary(!hasKey
                    ? getString(R.string.settings_sync_needs_key)
                    : on ? lastSyncedSummary()
                         : getString(R.string.settings_sync_switch_summary));
        }
        // Bookmark erasure is meaningful only while sync is on.
        if (mDeleteBookmarks != null) {
            mDeleteBookmarks.setVisible(on);
        }
        // Recovery-code section is SHARED → shown once a key exists.
        if (mCatCode != null) {
            mCatCode.setVisible(hasKey);
        }
        if (mShowCode != null) {
            mShowCode.setVisible(hasKey);
        }
        if (mExportCode != null) {
            mExportCode.setVisible(hasKey);
        }

        updateStatusHero(hasKey, setUp);
    }

    /**
     * Binds the status hero: last cached snapshot FIRST (synchronous — no flash
     * of the empty state on re-entry), then the fresh {@link
     * CloudBackupManager#loadStatus} result updates it in place. The load also
     * carries the guarded reconcile BOTH ways: a reconciled-empty account
     * (metered, spent, zero files) retires Cloud Backup; a reconciled-LIVE one
     * (files or balance) heals the local flag. Offline serves the cached
     * snapshot, so nothing blanks out on a network blip.
     */
    private void updateStatusHero(boolean hasKey, boolean setUp) {
        if (mStatus == null) {
            return;
        }
        // The purchased plan shape ("Up to X GB · 1 year") is client-side only —
        // stored by BuyCreditViewModel at purchase; the server just holds the
        // anonymous balance. 0/0 = unknown → the hero degrades gracefully.
        mStatus.setPlan(
                mSharedPreferences.getInt(Preferences.CLOUD_PLAN_SIZE_GB, 0),
                mSharedPreferences.getInt(Preferences.CLOUD_PLAN_DURATION_MONTHS, 0));
        mStatus.setActive(mTransferActive);
        mStatus.setHasKey(hasKey); // onboarding step ① check-off
        CloudBackupManager.Status cached = mCloudBackup.lastStatus();
        if (cached != null) {
            mStatus.setSetUp(cached.setUp);
            mStatus.setUsage(cached.fileCount, cached.totalBytes);
            mStatus.setQuota(cached.quota);
        } else {
            mStatus.setSetUp(setUp);
            mStatus.setUsage(-1, -1);
            mStatus.setQuota(null);
        }
        // Only a code-less device has nothing to ask the server about. A code
        // that's not marked set up may still front a FUNDED account (credit
        // bought, nothing backed up yet) — loadStatus reconciles that.
        if (!hasKey) {
            return;
        }
        mCloudBackup.loadStatus(status -> {
            if (!isAdded() || mStatus == null) {
                return;
            }
            applyManageVisibility(mCloudBackup.hasAccount() && status.setUp);
            mStatus.setSetUp(status.setUp);
            mStatus.setUsage(status.fileCount, status.totalBytes);
            mStatus.setQuota(status.quota);
        });
    }

    /** The Manage-backup category (Backups list + right-to-erasure) waits for
     *  set-up; the buy CTA above it is governed by the key gate alone (a metered
     *  user buys credit BEFORE their first backup). */
    private void applyManageVisibility(boolean show) {
        if (mCatManage != null) {
            mCatManage.setVisible(show);
        }
        if (mFiles != null) {
            mFiles.setVisible(show);
        }
        if (mDeleteData != null) {
            mDeleteData.setVisible(show);
        }
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
     * "Create recovery code" — the fresh-install account gateway (the mirror of
     * "I have a recovery code"). Mints the shared key WITHOUT enabling any feature
     * ({@link SyncManager#createRecoveryCode}), then shows it behind the MANDATORY
     * saved-gate: the dialog's Done stays disabled until the user checks "I've
     * saved it", so a key can't be created and forgotten. On dismiss the rows
     * re-evaluate ({@link #updateState}) — the buy CTA and bookmarks enable now
     * that a key exists. Guarded on {@link SyncManager#hasCode()} so a stray tap
     * can never overwrite an existing key.
     */
    private void createCode() {
        if (mSyncManager.hasCode()) {
            updateState();
            return;
        }
        mSyncManager.createRecoveryCode(grouped -> {
            if (isAdded()) {
                showCodeDialog(grouped, true);
                updateState();
            }
        });
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

    /** Turning the bookmarks switch OFF = sign out of bookmark sync (the doc
     *  stays on the server; the shared code is wiped only if Cloud Backup no
     *  longer needs it — see SyncManager.disable). */
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

    /**
     * Confirms and runs the bookmark server-side erasure (right-to-erasure) —
     * distinct from turning sync off; on success it also turns sync off locally.
     * SCOPED: bookmarks only, the sibling of "Delete backed-up files" below it.
     */
    private void showDeleteBookmarksDialog() {
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

    private String lastSyncedSummary() {
        long at = mSyncManager.lastSyncedAt();
        if (at <= 0) {
            return getString(R.string.settings_sync_never_synced);
        }
        CharSequence rel = DateUtils.getRelativeTimeSpanString(
                at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        return getString(R.string.settings_sync_last_synced, rel);
    }

    /**
     * Shows the grouped recovery code with a copy action and the no-recovery
     * warning. Two modes via {@code requireSaved}: VIEWING an existing code
     * (account already set up, behind the device-auth gate) hides the checkbox and
     * Done is always enabled; CREATING a fresh key ({@link #createCode}) shows the
     * "I've saved it" checkbox and gates Done on it — the key is the only copy, so
     * it must not be created and dismissed unsaved. The dialog is non-cancelable in
     * the create mode so Done (post-check) is the only exit, not a stray outside-tap.
     */
    private void showCodeDialog(@Nullable String grouped, boolean requireSaved) {
        if (grouped == null) {
            snackbar(getString(R.string.settings_sync_code_unavailable));
            return;
        }
        View view = getLayoutInflater().inflate(R.layout.dialog_sync_show_code, null);
        TextView codeText = view.findViewById(R.id.sync_code_text);
        codeText.setText(grouped);

        CheckBox savedCheck = view.findViewById(R.id.sync_code_saved_check);
        savedCheck.setVisibility(requireSaved ? View.VISIBLE : View.GONE);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setCancelable(!requireSaved)
                .setTitle(requireSaved
                        ? R.string.settings_sync_code_created_title   // "Save your recovery code"
                        : R.string.settings_sync_show_code_title)
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

        // Create mode: Done stays disabled until the user acknowledges they saved
        // the only key (the "check step").
        if (requireSaved) {
            Button done = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            done.setEnabled(false);
            savedCheck.setOnCheckedChangeListener((b, checked) -> done.setEnabled(checked));
        }
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

    /**
     * Debug-only row that round-trips a file through the live storage server,
     * reusing the shared recovery code. Never added in a release build. This is
     * the on-device smoke test for the storage client; it lands at the very
     * bottom of the screen, out of the way.
     */
    private void addDebugSmokeTestRow() {
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
        row.setTitle("Test storage vault (debug)");
        row.setSummary("Round-trip a file through storage.firedown.app");
        row.setOnPreferenceClickListener(pref -> {
            runDebugSmokeTest(pref);
            return true;
        });
        screen.addPreference(row);
    }

    private void runDebugSmokeTest(Preference row) {
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

    /** Whether a WorkInfo carries the backup identity tags stamped at enqueue
     *  (restores and legacy pre-tag WorkSpecs don't). */
    private static boolean hasBackupTag(WorkInfo wi) {
        for (String tag : wi.getTags()) {
            if (tag.startsWith(VaultBackupWorker.TAG_NAME)) {
                return true;
            }
        }
        return false;
    }

    private void snackbar(String text) {
        View view = getView();
        if (view != null) {
            Snackbar.make(view, text, Snackbar.LENGTH_LONG).show();
        }
    }
}

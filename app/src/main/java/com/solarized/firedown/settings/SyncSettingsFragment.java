package com.solarized.firedown.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
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
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.work.WorkManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.AppLock;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.phone.fragments.P2pScanFragment;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.PairClient;
import com.solarized.firedown.sync.StorageApiClient;
import com.solarized.firedown.sync.SyncManager;
import com.solarized.firedown.sync.SyncSecrets;
import com.solarized.firedown.sync.VaultBackupWorker;
import com.solarized.firedown.sync.VaultSmokeTest;
import com.solarized.firedown.sync.crypto.PairSeal;
import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.QrCodes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private CloudBuyButtonPreference mBuy;
    private Preference mFiles;
    /** Scan a browser pairing QR — rides the same set-up gate as Backups. */
    private Preference mPair;
    /** Display-only toggle for the home pill/card — no click handling, no cloud
     *  state; see settings_sync.xml for why it is not a "disable" switch. */
    private Preference mHomeStatus;
    private Preference mDeleteData;
    private SwitchPreferenceCompat mBookmarksSwitch;
    private Preference mDeleteBookmarks;
    private Preference mHelp;
    // The ONE recovery-code row — SHARED (bookmarks + downloads), shown once
    // the account exists; its reveal dialog carries Copy AND Save-to-file
    // behind the single device-auth (was two rows / two auth prompts).
    private Preference mShowCode;
    private Preference mLinkCode;

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
        // A recovery code scanned off another device's QR comes back through this
        // fragment's own back-stack entry — see observeScanResult. Registered
        // here (not lazily when the scanner opens) so the result still lands
        // after a process death or config change while the scanner was up.
        observeScanResult();
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
        mPair = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_PAIR);
        mHomeStatus = findPreference(Preferences.SETTINGS_CLOUD_HOME_STATUS);
        mDeleteData = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_DELETE_DATA);
        mBookmarksSwitch = findPreference(Preferences.SYNC_ENABLED);
        mDeleteBookmarks = findPreference(Preferences.SETTINGS_SYNC_DELETE_DATA);
        mHelp = findPreference(Preferences.SETTINGS_SYNC_HELP);
        mShowCode = findPreference(Preferences.SETTINGS_SYNC_SHOW_CODE);
        mLinkCode = findPreference(Preferences.SETTINGS_SYNC_LINK_CODE);

        if (mBuy != null) {
            mBuy.setOnPreferenceClickListener(this);
        }
        if (mFiles != null) {
            mFiles.setOnPreferenceClickListener(this);
        }
        if (mPair != null) {
            mPair.setOnPreferenceClickListener(this);
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
        if (mLinkCode != null) {
            mLinkCode.setOnPreferenceClickListener(this);
        }

        tintIcons();
        updateState();
        addDebugSmokeTestRow();
        addDebugAccountIdRow();
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
            case Preferences.SETTINGS_CLOUD_BACKUP_PAIR -> openPairScanner();
            case Preferences.SETTINGS_CLOUD_BACKUP_DELETE_DATA -> showDeleteDataDialog();
            case Preferences.SETTINGS_SYNC_DELETE_DATA -> showDeleteBookmarksDialog();
            case Preferences.SETTINGS_SYNC_HELP ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_sync_to_help);
            case Preferences.SETTINGS_SYNC_SHOW_CODE -> authThenShowCode();
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
        // The adopt-an-existing-account door, shown in BOTH states — the second
        // device in a two-device pair usually has a code of its OWN (it created
        // one, or backed a file up, before being pointed at the first device's
        // account), and gating this row on !hasKey left that user no way in at
        // all: the only route to one shared account was clearing app data.
        // Post-key it is a REPLACE, so showLinkDialog warns first (the current
        // code is the only key to whatever sits under it server-side). The
        // key-first gate is unaffected — that exists to stop a KEYLESS purchase,
        // and adopting a code produces a key rather than bypassing one.
        if (mLinkCode != null) {
            mLinkCode.setVisible(true);
            mLinkCode.setSummary(hasKey
                    ? R.string.settings_sync_link_replace_summary
                    : R.string.settings_sync_link_summary);
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
        // The recovery-code row is SHARED → shown once a key exists.
        if (mShowCode != null) {
            mShowCode.setVisible(hasKey);
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
        applyBuyEmphasis(hasKey, cached != null ? cached.quota : null);
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
            applyBuyEmphasis(mCloudBackup.hasAccount(), status.quota);
        });
    }

    /**
     * State-dependent CTA emphasis (see {@link CloudBuyButtonPreference}):
     * FILLED while the next step is genuinely "pay" — no key yet ("Create
     * recovery code"), quota unknown-with-key (a fresh account whose starter/
     * purchase state hasn't loaded — the roadmap's step ② default), unfunded,
     * or in grace — and OUTLINED once the account is affirmatively funded
     * (metered, positive balance, not read-only) or on the unmetered beta,
     * where there is nothing to sell. Bound from the cached snapshot first and
     * again from the fresh load, the same two-phase bind as the hero.
     */
    private void applyBuyEmphasis(boolean hasKey, @Nullable StorageApiClient.Quota quota) {
        if (mBuy == null) {
            return;
        }
        boolean calm = false;
        if (hasKey && quota != null) {
            calm = !quota.metered
                    || (quota.balanceMicroGbMonths > 0 && !quota.readOnly);
        }
        mBuy.setEmphasized(!calm);
    }

    /** The Manage-backup category (Backups list + right-to-erasure) waits for
     *  set-up; the buy CTA above it is governed by the key gate alone (a metered
     *  user buys credit BEFORE their first backup). */
    private void applyManageVisibility(boolean show) {
        if (mFiles != null) {
            mFiles.setVisible(show);
        }
        // Same gate: with no account there is nothing for a browser to open.
        if (mPair != null) {
            mPair.setVisible(show);
        }
        // Rides the same gate as the Backups row: with nothing backed up there
        // is no home status to show or hide, so the toggle would be a control
        // over nothing. Unlike its neighbours it needs no click handling — a
        // plain self-persisting SwitchPreferenceCompat, read live by
        // HomeFragment.applyBackupPill on the next resume.
        if (mHomeStatus != null) {
            mHomeStatus.setVisible(show);
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
                // Register the storage account NOW (not at first backup) so the
                // server's one-time starter credit lands and the next status
                // load shows the granted runway — see registerInBackground.
                mCloudBackup.registerInBackground(() -> {
                    if (isAdded()) {
                        updateState();
                    }
                });
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
     * file lands (the in-file caveat is only read afterwards). On Continue → the
     * SAF picker directly — the ONLY entry is the reveal dialog's Save-to-file
     * button, which already sits behind the device auth (view mode) or the
     * just-created key (create mode), so the old second auth here was a
     * double-prompt.
     */
    private void showExportCaveatDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_sync_export_title)
                .setMessage(R.string.settings_sync_export_caveat_message)
                .setPositiveButton(R.string.settings_sync_export_continue, (dialog, which) ->
                        mExportCodePicker.launch(getString(R.string.settings_sync_export_file_name)))
                .setNegativeButton(R.string.cancel, null)
                .show();
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
     *
     * <p>When this device ALREADY has a code, adopting another one is a REPLACE
     * and is confirmed first. The warning is not ceremony: the recovery code is
     * the ONLY key to what sits under it, so a user who never saved the current
     * one is about to lose reach of their own backed-up files and any paid
     * credit — nothing is deleted server-side, it simply becomes unreachable
     * from here. The confirm step therefore points at the Recovery code row
     * (save it first) rather than trying to be clever, and it is the one place
     * this screen asks twice.
     */
    private void showLinkDialog() {
        if (mCloudBackup.hasAccount()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_sync_link_replace_title)
                    .setMessage(R.string.settings_sync_link_replace_message)
                    .setPositiveButton(R.string.settings_sync_link_replace_action,
                            (d, w) -> showLinkCodeInput())
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        showLinkCodeInput();
    }

    /** The code-input half, reached directly pre-key and via the replace
     *  confirmation once a code already exists. */
    private void showLinkCodeInput() {
        showLinkCodeInput(null);
    }

    /**
     * The code-input half. {@code prefill} carries a code that arrived by QR
     * scan, so the scan lands back HERE rather than linking straight through:
     * the user sees what was decoded and still presses Restore. A QR is a
     * bearer secret pointed at a camera — an accidental frame (a sticker, a
     * screenshot on someone's screen, the wrong device's code) should not be
     * able to swap the account with no confirmation step, and the review is
     * free because this dialog already exists.
     *
     * @param prefill decoded scan result, or null when opened normally
     */
    private void showLinkCodeInput(@Nullable String prefill) {
        View view = getLayoutInflater().inflate(R.layout.dialog_sync_restore, null);
        TextInputEditText input = view.findViewById(R.id.sync_code);
        if (prefill != null) {
            input.setText(prefill);
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_sync_link_dialog_title)
                .setMessage(R.string.settings_sync_link_dialog_message)
                .setView(view)
                // Scan is the NEUTRAL action: it dismisses this dialog, opens the
                // shared scanner, and the result re-opens it prefilled (see
                // observeScanResult). It does not replace typing — a camera-less
                // device, or one scanning a screen it cannot focus on, still has
                // the field and the clipboard.
                .setNeutralButton(R.string.settings_sync_link_scan,
                        (dialog, which) -> openRecoveryCodeScanner())
                .setPositiveButton(R.string.settings_sync_link_action, (dialog, which) -> {
                    String code = input.getText() == null ? "" : input.getText().toString().trim();
                    if (TextUtils.isEmpty(code)) {
                        return;
                    }
                    // The account is changing, so the cached status snapshot is
                    // about a DIFFERENT account (SyncManager clears the durable
                    // half of the same problem). Dropped BEFORE updateState so
                    // no surface can repaint the old balance in between.
                    mCloudBackup.forgetCachedStatus();
                    mSyncManager.linkWithCode(code, ok -> {
                        if (!isAdded()) {
                            return;
                        }
                        updateState();
                        snackbar(getString(ok
                                ? R.string.settings_sync_link_ok
                                : R.string.settings_sync_link_bad));
                        if (ok) {
                            // Adopting a code on the Cloud screen is cloud
                            // intent too: register so a pre-feature account
                            // self-heals its starter grant (granted-once
                            // server-side, so an already-granted account is a
                            // no-op) and the hero reflects the balance.
                            mCloudBackup.registerInBackground(() -> {
                                if (isAdded()) {
                                    updateState();
                                }
                            });
                        }
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
        bindCodeQr(view, grouped);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setCancelable(!requireSaved)
                .setTitle(requireSaved
                        ? R.string.settings_sync_code_created_title   // "Save your recovery code"
                        : R.string.settings_sync_show_code_title)
                .setView(view)
                // Copy and Save-to-file are the NEUTRAL/NEGATIVE actions so they
                // act WITHOUT closing the dialog (their click listeners are
                // overridden below to suppress the default dismiss). Save-to-file
                // lives HERE — inside the one auth-gated reveal — instead of as
                // its own row + second auth prompt on the Cloud screen (the old
                // two-rows-for-one-object shape); it matters most in CREATE mode,
                // where saving the only key is exactly the step being gated.
                .setNeutralButton(R.string.settings_sync_code_copy, null)
                .setNegativeButton(R.string.settings_sync_code_save_file, null)
                .setPositiveButton(R.string.settings_sync_code_done, null)
                .create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> copyToClipboard(grouped));
        // Non-dismissing on purpose: the SAF picker opens over the dialog and
        // the user returns to it (in create mode the "I've saved it" gate must
        // survive the round-trip — a dismissing button would be a silent escape
        // from the non-cancelable create dialog).
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener(v -> showExportCaveatDialog());

        // Create mode: Done stays disabled until the user acknowledges they saved
        // the only key (the "check step").
        if (requireSaved) {
            Button done = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            done.setEnabled(false);
            savedCheck.setOnCheckedChangeListener((b, checked) -> done.setEnabled(checked));
        }
    }

    /**
     * Opens the SHARED scanner ({@code P2pScanFragment}) for a recovery code.
     * Reuses that whole screen — CameraX bind, the lazy camera-permission
     * request, the zxing Y-plane decode, the paste fallback — with only a title
     * override; its {@code ARG_PREFIX} already defaults to "" (accept any
     * decoded text), which is right here because a recovery code carries no
     * prefix and is verifiable only by trying to decode it. Registered as a
     * {@code <dialog>} in nav_graph_settings for the same reason it is one in
     * nav_graph_downloads, plus one specific to this caller: the result is
     * delivered to the PREVIOUS back-stack entry, so this fragment must still be
     * alive when the scan lands.
     */
    private void openRecoveryCodeScanner() {
        NavController nav = NavHostFragment.findNavController(this);
        Bundle args = new Bundle();
        args.putInt(P2pScanFragment.ARG_TITLE_RES, R.string.settings_sync_scan_title);
        nav.navigate(R.id.action_sync_to_scan, args);
    }

    /**
     * Consumes a scanned code off this fragment's OWN back-stack entry (the
     * scanner sets it on the previous entry, which is us).
     *
     * <p>Consumed with {@code set(key, null)}, NEVER {@code remove(key)} —
     * remove() DETACHES the handle's cached LiveData for that key, so a second
     * scan in the same session would silently never arrive. That trap is
     * documented from the Cloud Backup item sheet; it is the same handle
     * mechanism here.
     *
     * <p>An unparseable payload is reported and dropped rather than prefilled:
     * a QR that decodes to some other app's text is not a typo to correct, and
     * putting it in the field would invite pressing Restore on it.
     */
    private void observeScanResult() {
        NavBackStackEntry entry = NavHostFragment.findNavController(this).getCurrentBackStackEntry();
        if (entry == null) {
            return;
        }
        entry.getSavedStateHandle()
                .getLiveData(P2pScanFragment.RESULT_CODE, (String) null)
                .observe(getViewLifecycleOwner(), code -> {
                    if (code == null) {
                        return;
                    }
                    entry.getSavedStateHandle().set(P2pScanFragment.RESULT_CODE, (String) null);
                    String trimmed = code.trim();

                    // ONE scanner serves two rows, told apart by the PAYLOAD
                    // rather than by a mode flag: a browser pairing code is
                    // self-identifying (scheme + FDP1 prefix + a valid P-256
                    // point) while a recovery code has no prefix at all. A flag
                    // would have to be threaded through the scanner's
                    // saved-state round trip and could go stale across process
                    // death; the payload cannot.
                    PairSeal.Ref pairing = PairSeal.parse(trimmed);
                    if (pairing != null) {
                        confirmPairing(pairing);
                        return;
                    }
                    if (!mSyncManager.looksLikeRecoveryCode(trimmed)) {
                        snackbar(getString(R.string.settings_sync_scan_bad));
                        return;
                    }
                    showLinkCodeInput(trimmed);
                });
    }

    /* ------------------------------------------------------ browser pairing */

    /** Opens the shared scanner for a browser pairing QR. */
    private void openPairScanner() {
        Bundle args = new Bundle();
        args.putInt(P2pScanFragment.ARG_TITLE_RES, R.string.pair_scan_title);
        NavHostFragment.findNavController(this).navigate(R.id.action_sync_to_scan, args);
    }

    /**
     * Resolves the scanned pairing against OUR OWN backend and asks the user to
     * approve it.
     *
     * <p>Three checks stand between a scan and handing over keys, and they are
     * layered because each covers what the others cannot:
     * <ol>
     *   <li>The lookup goes to {@link Preferences#STORAGE_DEFAULT_BACKEND}, never
     *       to anything the QR named — so a hostile QR cannot serve its own
     *       answer.</li>
     *   <li>The public key the server recorded is compared to the SCANNED one.
     *       A server that swapped it (to read the sealed blob itself) fails this
     *       comparison, because the scan is out-of-band.</li>
     *   <li>The user is shown the server-attested site AND a six-digit code that
     *       must match what their browser is displaying. This is the one that
     *       catches a convincing fake: the server cannot tell a real browser
     *       from a program claiming to be one, so an attacker CAN mint a session
     *       that truthfully reports firedown.app — but they cannot make the
     *       victim's own screen show that session's code.</li>
     * </ol>
     */
    private void confirmPairing(PairSeal.Ref ref) {
        if (!mCloudBackup.hasAccount()) {
            snackbar(getString(R.string.pair_error_no_account));
            return;
        }
        snackbar(getString(R.string.pair_working));
        Context appContext = requireContext().getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            PairClient client = new PairClient(mHttpClient);
            PairClient.Pairing pairing;
            try {
                pairing = client.lookup(ref.pairId);
            } catch (PairClient.GoneException e) {
                main.post(() -> snackbarIfAdded(getString(R.string.pair_error_expired)));
                return;
            } catch (IOException e) {
                main.post(() -> snackbarIfAdded(getString(R.string.pair_error_generic)));
                return;
            }
            if (pairing == null) {
                main.post(() -> snackbarIfAdded(getString(R.string.pair_error_generic)));
                return;
            }
            // The out-of-band check. Compared as the exact base64url string the
            // server echoes, so there is no re-encoding step to disagree about.
            if (!pairing.pubkey.equals(ref.pubkeyB64)) {
                main.post(() -> snackbarIfAdded(getString(R.string.pair_error_key_mismatch)));
                return;
            }
            String site = pairing.origin;
            // Minted HERE, before the dialog, and reused verbatim by the seal:
            // the verification code covers this key, so showing digits for a
            // key we had not committed to would be showing digits for a
            // handshake that never happens.
            PairSeal.Ephemeral eph = PairSeal.newEphemeral();
            String code = PairSeal.verificationCode(ref.pairId, ref.pubkey, eph.publicKey);
            main.post(() -> {
                if (!isAdded()) {
                    return;
                }
                showPairApproval(appContext, ref, eph, site, code);
            });
        }, "pair-lookup").start();
    }

    /**
     * Shows the approval sheet for a resolved pairing.
     *
     * <p>A CUSTOM VIEW, not {@code setMessage}: the six digits are the one
     * thing the user has to act on, and a concatenated message can only set
     * them at body size inside a sentence — which is what this replaced. See
     * {@code dialog_pair_confirm.xml} for the ranking and the colour rules.
     */
    private void showPairApproval(Context appContext, PairSeal.Ref ref,
                                  PairSeal.Ephemeral eph, String site, String code) {
        View view = getLayoutInflater().inflate(R.layout.dialog_pair_confirm, null);
        ((TextView) view.findViewById(R.id.pair_site)).setText(site);

        TextView codeView = view.findViewById(R.id.pair_code);
        codeView.setText(groupPairCode(code));
        // The task is a digit-by-digit comparison against another screen, so
        // TalkBack must not read the code as a NUMBER ("eight hundred
        // fifty-three thousand…") — it has to speak the digits, in order.
        codeView.setContentDescription(spokenPairCode(code));

        // One-shot: a rapid double-tap can invoke the listener twice before the
        // dialog dismisses, which would start two seal-and-deliver threads. Both
        // succeed locally, but the server's first-delivery-wins turns the second
        // into a 409 — so the user sees "paired", then "expired".
        AtomicBoolean fired = new AtomicBoolean();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pair_confirm_title)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.pair_confirm_action, (d, w) -> {
                    if (fired.compareAndSet(false, true)) {
                        deliverPairing(appContext, ref, eph, code);
                    }
                })
                .show();
    }

    /**
     * Splits the verification code 3+3 for a quicker visual compare. Anything
     * that is not the expected six digits is returned verbatim rather than cut
     * blindly — the length is {@link PairSeal}'s contract, not this method's.
     */
    private static String groupPairCode(String code) {
        if (code == null || code.length() != PairSeal.CODE_DIGITS) {
            return code;
        }
        int half = PairSeal.CODE_DIGITS / 2;
        return code.substring(0, half) + " " + code.substring(half);
    }

    /** The same code as separated digits, so a screen reader spells it out. */
    private static String spokenPairCode(String code) {
        if (code == null) {
            return null;
        }
        return TextUtils.join(" ", code.split(""));
    }

    /**
     * Success message for a delivered pairing, carrying the verification code.
     *
     * <p>INDEFINITE, unlike every other snackbar here: the browser is about to
     * ask the user to confirm these six digits, and they have to be readable off
     * the phone while the user is looking at the other screen. A LENGTH_LONG
     * message is gone in ~3.5 s, which would leave nothing to compare against
     * and train people to click "they match" without checking.
     */
    private void snackbarPairCode(String code) {
        if (!isAdded()) {
            return;
        }
        Snackbar.make(requireView(), getString(R.string.pair_success_code, groupPairCode(code)),
                        Snackbar.LENGTH_INDEFINITE)
                .setAction(android.R.string.ok, v -> { })
                .show();
    }

    /** Seals this account's storage keys to the browser's key and delivers them. */
    private void deliverPairing(Context appContext, PairSeal.Ref ref,
                                PairSeal.Ephemeral eph, String code) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            byte[] code = new SyncSecrets(appContext).load();
            if (code == null) {
                main.post(() -> snackbarIfAdded(getString(R.string.pair_error_no_account)));
                return;
            }
            SyncIdentity.PairingKeys keys = null;
            byte[] sealed = null;
            try {
                keys = SyncIdentity.pairingKeys(code);
                sealed = PairSeal.seal(ref.pairId, ref.pubkey, eph,
                        keys.accountId, keys.authSeed, keys.storageKey);
                new PairClient(mHttpClient).complete(ref.pairId, sealed);
                main.post(() -> snackbarPairCode(code));
            } catch (PairClient.GoneException e) {
                main.post(() -> snackbarIfAdded(getString(R.string.pair_error_expired)));
            } catch (IOException e) {
                main.post(() -> snackbarIfAdded(getString(R.string.pair_error_generic)));
            } finally {
                // The sealed blob is ciphertext, but the inputs are the account
                // itself — wipe them rather than leave them for the GC.
                SyncSecrets.wipe(code);
                if (keys != null) {
                    keys.wipe();
                }
            }
        }, "pair-complete").start();
    }

    private void snackbarIfAdded(String text) {
        if (isAdded()) {
            snackbar(text);
        }
    }

    /**
     * Wires the reveal dialog's collapsed QR — the same recovery code in
     * scannable form, so pairing a second device is "point camera at phone"
     * instead of typing 26 base32 characters (or trusting a clipboard round-trip
     * between two devices, which there is no path for).
     *
     * <p>The QR panel SWAPS with the code panel inside this one dialog — it is
     * neither added below it nor opened as a second dialog. Both of those
     * shipped and were wrong:
     * <ul>
     *   <li><b>Adding it below</b> made the dialog taller than the window. Its
     *       button panel is stacked VERTICALLY (Material stacks when the labels
     *       don't fit a row, and there are three — Copy / Save to file / Done),
     *       so the overflow clipped "Save to file" off the bottom and made it
     *       unreachable. Shrinking the image only defers that to a large font
     *       scale.</li>
     *   <li><b>A second dialog</b> fixed the height but read as a dialog stacked
     *       on a dialog — and dismissing this one to avoid that is NOT available:
     *       in create mode it is non-cancelable with Done gated on the checkbox,
     *       so dismissing would let the user leave a freshly minted key without
     *       ever acknowledging they saved it (the hole that gate closes), and it
     *       would take Copy and Save-to-file away while the QR is up.</li>
     * </ul>
     * Swapping keeps the height at max(code, QR) rather than their sum, which is
     * what the clipping was, and dismisses nothing.
     *
     * <p>Encoded from the GROUPED display form, which is what the scanner side
     * feeds back to {@code decodeRecoveryCode} — that decode strips the group
     * hyphens (and is case-insensitive), so the two halves agree without a
     * second, differently-normalised representation to keep in sync.
     *
     * <p>A null bitmap (zxing declined the payload) HIDES the toggle rather than
     * offering a swap to an empty frame that reads as a broken scan target — the
     * code text is unaffected and remains the fallback. Encoded ONCE here, so
     * that check happens before the affordance is offered.
     *
     * <p>EXPOSURE is unchanged by the swap: the QR starts hidden, so this
     * payload is only on screen once asked for, one deliberate step past the
     * device-auth gate that opened the dialog.
     */
    private void bindCodeQr(@NonNull View view, @NonNull String grouped) {
        MaterialButton toggle = view.findViewById(R.id.sync_code_qr_toggle);
        View codePanel = view.findViewById(R.id.sync_code_panel);
        View qrPanel = view.findViewById(R.id.sync_code_qr_panel);
        ImageView image = view.findViewById(R.id.sync_code_qr);
        if (toggle == null || codePanel == null || qrPanel == null || image == null) {
            return;
        }
        Bitmap qr = QrCodes.encode(grouped);
        if (qr == null) {
            toggle.setVisibility(View.GONE);
            qrPanel.setVisibility(View.GONE);
            return;
        }
        image.setImageBitmap(qr);
        toggle.setOnClickListener(v -> {
            boolean showingQr = qrPanel.getVisibility() == View.VISIBLE;
            // Exactly one panel visible, both set on every tap — the same
            // one-sided-set trap the home cloud slot documents.
            qrPanel.setVisibility(showingQr ? View.GONE : View.VISIBLE);
            codePanel.setVisibility(showingQr ? View.VISIBLE : View.GONE);
            toggle.setText(showingQr
                    ? R.string.settings_sync_code_show_qr
                    : R.string.settings_sync_code_hide_qr);
        });
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
    /**
     * Debug-only row showing the storage ACCOUNT ID (Crockford base32, the
     * {@code X-Firedown-Account} wire form); tap copies it. This is the dev
     * free-testing handshake with the server's operator grant: copy here, then
     * on the VPS {@code storage-api --grant <base32> --grant-gbm 100000} funds
     * the account so uploads test freely THROUGH the real metered pipeline —
     * deliberately not a client/server "uploads free" bypass, which would be a
     * standing backdoor and would skip the billing paths a test should
     * exercise. Never added in a release build; absent until a code exists.
     */
    private void addDebugAccountIdRow() {
        if (!BuildConfig.DEBUG) {
            return;
        }
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null || !mSyncManager.hasCode()) {
            return;
        }
        String base32;
        try {
            byte[] code = new SyncSecrets(requireContext()).load();
            if (code == null) {
                return;
            }
            base32 = SyncIdentity.fromCode(code).accountBase32();
        } catch (RuntimeException e) {
            return; // debug convenience only — never let it break the screen
        }
        Preference row = new Preference(requireContext());
        row.setKey("debug.account.id");
        row.setPersistent(false);
        row.setTitle("Account id (debug)");
        row.setSummary(base32 + " — tap to copy; grant test credit with: storage-api --grant <id>");
        row.setOnPreferenceClickListener(pref -> {
            copyToClipboard(base32);
            return true;
        });
        screen.addPreference(row);
    }

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

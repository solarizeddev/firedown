package com.solarized.firedown.settings;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
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
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import androidx.work.WorkInfo;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.work.WorkManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.AppLock;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.phone.fragments.P2pScanFragment;
import com.solarized.firedown.sync.BackupPlaces;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.FolderStore;
import com.solarized.firedown.sync.SyncManager;
import com.solarized.firedown.sync.SyncSecrets;
import com.solarized.firedown.sync.VaultBackupWorker;
import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.QrCodes;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * The MERGED Backup screen (toolbar title "Backup") — the encrypted downloads
 * backup into a folder of the user's choosing front and center, bookmarks +
 * key plumbing secondary. The folder is any storage the system's file picker
 * can offer (Google Drive, Dropbox, Nextcloud, an SD card, the phone itself):
 * the app encrypts into it and reads back from it, and no Firedown server is
 * involved (the hosted service this replaced is gone — see CLAUDE.md).
 *
 * <p>Top to bottom: the {@link CloudStatusPreference} status hero (whose
 * not-set-up state is the three-step onboarding roadmap), the MORPHING filled
 * CTA — "Create recovery code" pre-key, "Choose backup folder" with a key but
 * no folder, "Choose the folder again" when the folder stopped answering, and
 * GONE once the backup is healthy — the adopt-a-code door, the "Backup
 * folder" row (where it is: "Google Drive › Backups"; tap = change it), the
 * Backups row, one secondary inline Bookmarks switch, the shared recovery
 * code behind a device-auth gate, the website's how-it-works page, and LAST
 * the erasure door. All crypto/IO work lives in {@link SyncManager} /
 * {@link CloudBackupManager}.
 *
 * <p>The hero binds {@link CloudBackupManager#lastStatus()} synchronously on
 * entry (the singleton's last good snapshot) and the async
 * {@link CloudBackupManager#loadStatus} result then updates it in place — so
 * re-entering the screen never flashes the empty state while the folder
 * round-trip is in flight. The one fact the cached snapshot cannot carry is a
 * LOST folder (the cache keeps only successful reads), so the fragment keeps
 * the latest loaded status itself ({@link #mLastLoaded}) and the CTA follows
 * it.
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

    private CloudStatusPreference mStatus;
    private CloudBuyButtonPreference mCta;
    private Preference mFiles;
    /** "Backup folder" — WHERE as the summary, tap = change (changeFolder). */
    private Preference mFolderRow;
    /** The ONE erasure door (see settings_sync.xml) → CloudDeleteFragment, the
     *  two-row sub-screen; hidden while neither scoped deletion applies. */
    private Preference mDeleteDoor;
    /** What the door can offer right now — set by the two visibility paths
     *  (bookmarks sync on; backup set up) and read by applyDeleteDoor(). */
    private boolean mBookmarksDeletable;
    private boolean mBackupsDeletable;
    private SwitchPreferenceCompat mBookmarksSwitch;
    private Preference mHelp;
    // The ONE recovery-code row — SHARED (bookmarks + downloads), shown once
    // the account exists; its reveal dialog carries Copy AND Save-to-file
    // behind the single device-auth (was two rows / two auth prompts).
    private Preference mShowCode;
    private Preference mLinkCode;
    // The two title-less card groups updateState() swaps by life stage: the
    // KEY group (Recovery code + the adopt door) and the USE group (Backups,
    // Sync bookmarks). XML-inflate orders captured on first use so the
    // not-set-up state restores the onboarding order exactly.
    private PreferenceCategory mKeyGroup;
    private PreferenceCategory mUseGroup;
    private int mKeyGroupBaseOrder = Integer.MIN_VALUE;
    private int mUseGroupBaseOrder = Integer.MIN_VALUE;

    /** True while a transfer is running, so a usage refresh doesn't clobber the
     *  live "Transfer in progress…" status. */
    private boolean mTransferActive;

    /**
     * The latest {@link CloudBackupManager#loadStatus} answer this screen
     * received, reachable or not. {@link CloudBackupManager#lastStatus()} keeps
     * only SUCCESSFUL reads, so the folder-lost fact lives here; cleared
     * whenever the manager forgets its own cache (a new folder, an erase, a
     * code swap) so a stale "lost" can't outlive the folder that was lost.
     */
    @Nullable
    private CloudBackupManager.Status mLastLoaded;

    /** "Checking the folder…" while a picked folder's write check runs;
     *  dismissed by the check's answer. */
    @Nullable
    private Snackbar mCheckingSnackbar;

    /** Last bookmark-sync state seen, so the failure snackbar fires only on a
     *  fresh SYNCING -> ERROR transition (not on every entry with a stale
     *  error). Ported from the retired focused Bookmarks screen. */
    private int mSyncState = SyncManager.STATE_OFF;

    /** SAF "create document" for exporting the recovery code to a text file. */
    private final ActivityResultLauncher<String> mExportCodePicker =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"),
                    this::onExportFilePicked);

    /**
     * SAF "open document tree" — the backup folder picker. The user picks ANY
     * folder the system offers (a cloud account via its documents provider,
     * the SD card, the phone); a write check runs on it, then the app takes a
     * persistable read+write grant and keeps everything under
     * {@code <picked>/Firedown Backup/<account>/}. The same launcher serves
     * first choice, change and re-choose-after-lost.
     */
    private final ActivityResultLauncher<Uri> mFolderPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                    this::onFolderPicked);

    /**
     * A removable volume's OWN picker intent
     * ({@code StorageVolume.createOpenDocumentTreeIntent()}, API 29+): the
     * same ACTION_OPEN_DOCUMENT_TREE pre-pointed at the card, so its result
     * is the same tree grant and lands in the same {@link #onFolderPicked}.
     */
    private final ActivityResultLauncher<Intent> mVolumePicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> onFolderPicked(result.getResultCode() == Activity.RESULT_OK
                            && result.getData() != null ? result.getData().getData() : null));

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // A recovery code scanned off another device's QR comes back through this
        // fragment's own back-stack entry — see observeScanResult. Registered
        // here (not lazily when the scanner opens) so the result still lands
        // after a process death or config change while the scanner was up.
        observeScanResult();
        // The "Where to back up" sheet's pick, the same mechanism and for the
        // same reason (a pick made just before a rotation still lands).
        observePlaceResult();
        // Live status: while a backup/restore runs, the hero caption reads
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
                    // updateState() here re-read the folder per percent — the
                    // idle→active and active→idle edges are the only ones that
                    // change what this screen shows.
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
        mCta = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_CTA);
        mFiles = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_FILES);
        mFolderRow = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_FOLDER);
        mDeleteDoor = findPreference(Preferences.SETTINGS_CLOUD_DELETE);
        mBookmarksSwitch = findPreference(Preferences.SYNC_ENABLED);
        mHelp = findPreference(Preferences.SETTINGS_SYNC_HELP);
        mShowCode = findPreference(Preferences.SETTINGS_SYNC_SHOW_CODE);
        mLinkCode = findPreference(Preferences.SETTINGS_SYNC_LINK_CODE);
        mKeyGroup = findPreference(Preferences.SETTINGS_CLOUD_GROUP_KEY);
        mUseGroup = findPreference(Preferences.SETTINGS_CLOUD_GROUP_USE);

        if (mCta != null) {
            mCta.setOnPreferenceClickListener(this);
        }
        if (mFiles != null) {
            mFiles.setOnPreferenceClickListener(this);
        }
        if (mFolderRow != null) {
            mFolderRow.setOnPreferenceClickListener(this);
        }
        if (mDeleteDoor != null) {
            mDeleteDoor.setOnPreferenceClickListener(this);
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
        addDebugAccountIdRow();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Bookmark sync can be toggled on its focused screen, and a backup /
        // restore / delete elsewhere can change usage — refresh rows + hero on
        // every return. A manager that forgot its cache (an erase, a code swap)
        // is the signal that OUR last answer is about a different folder/account
        // too, so it goes with it.
        if (mCloudBackup.lastStatus() == null) {
            mLastLoaded = null;
        }
        updateState();
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        switch (key) {
            case Preferences.SETTINGS_CLOUD_BACKUP_CTA -> {
                // The MORPHING CTA: the next onboarding step. Pre-key it reads
                // "Create recovery code" and runs the create flow (mandatory
                // "I've saved it" gate); with a key the other labels — choose,
                // choose again — open the "Where to back up" chooser, and
                // "Reconnect" (a WebDAV server that stopped answering) opens
                // the sign-in page prefilled with the saved server.
                if (!mCloudBackup.hasAccount()) {
                    createCode();
                } else if (mCloudBackup.usesWebDav() && mCloudBackup.hasFolder()) {
                    openWebDav();
                } else {
                    showPlaceChooser();
                }
            }
            case Preferences.SETTINGS_CLOUD_BACKUP_FOLDER -> changeFolder();
            case Preferences.SETTINGS_CLOUD_BACKUP_FILES ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_sync_to_files);
            case Preferences.SETTINGS_CLOUD_DELETE ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_sync_to_delete);
            case Preferences.SETTINGS_SYNC_HELP -> openHelpPage();
            case Preferences.SETTINGS_SYNC_SHOW_CODE -> authThenShowCode();
            case Preferences.SETTINGS_SYNC_LINK_CODE -> showLinkDialog();
        }
        return true;
    }

    /**
     * Reflects persisted state into every section. The KEY is the account
     * gateway: a recovery code must exist before EITHER feature can be used, so
     * pre-key the CTA morphs to "Create recovery code", the Backups row is
     * hidden and the Bookmarks switch is disabled — the folder can only be
     * chosen once there is a key to encrypt into it with.
     */
    private void updateState() {
        boolean hasKey = mCloudBackup.hasAccount();
        boolean hasFolder = mCloudBackup.hasFolder();
        boolean setUp = mCloudBackup.isSetUp();
        boolean on = mSyncManager.isEnabled();

        // The adopt-an-existing-account door, shown in BOTH states — the second
        // device in a two-device pair usually has a code of its OWN (it created
        // one, or backed a file up, before being pointed at the first device's
        // folder), and gating this row on !hasKey left that user no way in at
        // all: the only route to one shared account was clearing app data.
        // Post-key it is a REPLACE, so showLinkDialog warns first (the current
        // code is the only key to whatever sits under it in the folder). The
        // key-first gate is unaffected — adopting a code produces a key rather
        // than bypassing one.
        if (mLinkCode != null) {
            mLinkCode.setVisible(true);
            mLinkCode.setSummary(hasKey
                    ? R.string.settings_sync_link_replace_summary
                    : R.string.settings_sync_link_summary);
        }
        // Group order follows the account's LIFE STAGE. The XML order (the
        // KEY group — Recovery code + the adopt door — above the USE group)
        // is the ONBOARDING shape: the adopt door is the second way in, so it
        // belongs right under the create-CTA while there is nothing else to
        // reach. Once the backup is SET UP the priorities invert: Backups is
        // the daily-use row of the whole screen and the key rows once-ever
        // actions, so the USE group (Backups + Sync bookmarks) swaps above the
        // KEY group. The GROUPS swap, not rows, so each card group stays whole
        // in both stages. Base orders are captured once (orderingAsAdded
        // assigns them at inflate); the not-set-up branch restores them, so the
        // erase path flips the screen back.
        if (mKeyGroup != null && mUseGroup != null) {
            if (mKeyGroupBaseOrder == Integer.MIN_VALUE) {
                mKeyGroupBaseOrder = mKeyGroup.getOrder();
                mUseGroupBaseOrder = mUseGroup.getOrder();
            }
            mUseGroup.setOrder(setUp ? mKeyGroupBaseOrder : mUseGroupBaseOrder);
            mKeyGroup.setOrder(setUp ? mUseGroupBaseOrder : mKeyGroupBaseOrder);
        }
        applyManageVisibility(setUp);
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
        mBookmarksDeletable = on;
        applyDeleteDoor();
        // The recovery-code row is SHARED → shown once a key exists.
        if (mShowCode != null) {
            mShowCode.setVisible(hasKey);
        }

        updateStatusHero(hasKey, hasFolder, setUp);
    }

    /**
     * Binds the status hero: the last cached snapshot FIRST (synchronous — no
     * flash of the empty state on re-entry), then the fresh {@link
     * CloudBackupManager#loadStatus} result updates it in place. The CTA
     * follows the same two-phase bind, because its label depends on whether
     * the folder answered ({@link #applyCta}).
     */
    private void updateStatusHero(boolean hasKey, boolean hasFolder, boolean setUp) {
        if (mStatus == null) {
            return;
        }
        mStatus.setActive(mTransferActive);
        mStatus.setHasKey(hasKey);       // roadmap step ① check-off
        mStatus.setHasFolder(hasFolder); // roadmap step ② check-off
        mStatus.setServer(mCloudBackup.usesWebDav());
        mStatus.setProvider(mCloudBackup.locationLabel());
        applyFolderRow(hasKey && hasFolder);
        CloudBackupManager.Status known = knownStatus();
        if (known != null) {
            mStatus.setSetUp(known.setUp);
            mStatus.setReachable(known.reachable);
            mStatus.setUsage(known.fileCount, known.totalBytes);
        } else {
            mStatus.setSetUp(setUp);
            mStatus.setReachable(true);
            mStatus.setUsage(mCloudBackup.lastKnownFileCount(),
                    mCloudBackup.lastKnownTotalBytes());
        }
        applyCta(hasKey, hasFolder, setUp, known);
        if (!setUp) {
            return;
        }
        mCloudBackup.loadStatus(status -> {
            if (!isAdded() || mStatus == null) {
                return;
            }
            mLastLoaded = status;
            applyManageVisibility(status.setUp);
            mStatus.setSetUp(status.setUp);
            mStatus.setReachable(status.reachable);
            mStatus.setUsage(status.fileCount, status.totalBytes);
            // The read that just landed also named the folder (FolderStore
            // remembers it from the reachability query), so the hero and
            // the row can say "Google Drive › Backups" from here on.
            mStatus.setProvider(status.location);
            applyFolderRow(mCloudBackup.hasAccount() && mCloudBackup.hasFolder());
            applyCta(mCloudBackup.hasAccount(), mCloudBackup.hasFolder(),
                    status.setUp, status);
        });
    }

    /**
     * The "Backup folder" row: WHERE the backup is, as the summary
     * ("Google Drive › Backups" — the provider, then the picked folder's own
     * name, {@link CloudBackupManager#locationLabel()}), and the ONE door to
     * change it. Shown once a KEY and a folder exist, in every folder state:
     * a lost folder still has a name worth stating, and the row's tap confirms
     * before the chooser whenever the folder holds files. Gated on the key
     * like the CTA (a restored install can carry a folder URI with no code;
     * the key-first gate must hold on this door too).
     */
    /** Which glyph the folder row wears now (null = the XML's), so the icon
     *  is swapped only when the place's kind changes. */
    @Nullable
    private Boolean mFolderRowServer;

    private void applyFolderRow(boolean hasFolder) {
        if (mFolderRow == null) {
            return;
        }
        mFolderRow.setVisible(hasFolder);
        if (!hasFolder) {
            return;
        }
        boolean server = mCloudBackup.usesWebDav();
        if (mFolderRowServer == null || mFolderRowServer != server) {
            // Only on a change: setIcon rebinds the row, and this runs twice
            // per screen update. Tinted like every other settings icon
            // (tintIcons ran at inflate, before this drawable existed).
            mFolderRowServer = server;
            mFolderRow.setIcon(server ? R.drawable.dns_24 : R.drawable.folder_24);
            Drawable icon = mFolderRow.getIcon();
            if (icon != null) {
                DrawableCompat.setTint(icon, MaterialColors.getColor(requireContext(),
                        android.R.attr.textColorSecondary, 0));
            }
        }
        mFolderRow.setSummary(server
                ? getString(R.string.webdav_folder_row, mCloudBackup.locationLabel())
                : mCloudBackup.locationLabel());
    }

    /**
     * The status to paint before the fresh read lands: this screen's own last
     * answer when it has one (it may say LOST, which the manager's cache never
     * does), else the manager's last successful snapshot.
     */
    @Nullable
    private CloudBackupManager.Status knownStatus() {
        if (mLastLoaded != null) {
            return mLastLoaded;
        }
        return mCloudBackup.lastStatus();
    }

    /**
     * The MORPHING CTA shows the next step while one is OWED — no key yet
     * ("Create recovery code"), no folder yet ("Choose backup folder"), or a
     * folder that stopped answering ("Choose the folder again") — FILLED
     * (see {@link CloudBuyButtonPreference}), and is HIDDEN once the backup
     * is set up and the folder answers. It used to stay as an outlined
     * "Change folder" there, and that was the screen's one visible action on
     * a healthy backup: a verb with nothing it pointed at, which asked "change
     * it to what, from what?" (the maintainer's "open folder or change folder
     * is confusing"). The folder row answers that by stating the folder and
     * carrying the change; a backup that needs nothing shows no button.
     */
    private void applyCta(boolean hasKey, boolean hasFolder, boolean setUp,
                          @Nullable CloudBackupManager.Status status) {
        if (mCta == null) {
            return;
        }
        boolean lost = setUp && status != null && status.setUp && !status.reachable;
        int title;
        if (!hasKey) {
            title = R.string.settings_sync_create_title;
        } else if (!hasFolder) {
            title = R.string.folder_backup_choose_cta;
        } else if (lost) {
            title = mCloudBackup.usesWebDav()
                    ? R.string.webdav_reconnect_cta : R.string.folder_backup_rechoose_cta;
        } else {
            mCta.setVisible(false);
            return;
        }
        mCta.setTitle(title);
        mCta.setEmphasized(true);
        mCta.setVisible(true);
    }

    /** The Backups row (+ the backed-up-files erasure) waits for set-up; the
     *  CTA above it is governed by the key gate alone. */
    private void applyManageVisibility(boolean show) {
        if (mFiles != null) {
            mFiles.setVisible(show);
        }
        mBackupsDeletable = show;
        applyDeleteDoor();
    }

    /** The erasure door shows while at least one scoped deletion applies. */
    private void applyDeleteDoor() {
        if (mDeleteDoor != null) {
            mDeleteDoor.setVisible(mBookmarksDeletable || mBackupsDeletable);
        }
    }

    /**
     * The folder row's tap. A folder that HOLDS files confirms first, because
     * the change is not what it looks like: nothing moves, the old folder
     * keeps its files under the same code, and they leave the Backups list
     * until that folder is chosen again (restore them first to bring them
     * along). That consequence used to live only in CLAUDE.md; it belongs at
     * the decision. An empty folder, or one never read, goes straight to the
     * chooser.
     */
    private void changeFolder() {
        CloudBackupManager.Status known = knownStatus();
        int files = known != null && known.fileCount >= 0
                ? known.fileCount : mCloudBackup.lastKnownFileCount();
        if (files <= 0) {
            showPlaceChooser();
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.folder_backup_change_title)
                .setMessage(getString(R.string.folder_backup_change_message,
                        mCloudBackup.locationLabel()))
                .setPositiveButton(R.string.folder_backup_change_confirm,
                        (dialog, which) -> showPlaceChooser())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * "Where to back up": a sheet with one card per KIND of place, always
     * shown ({@link BackupPlacesSheetDialogFragment}): cloud storage, a WebDAV
     * server, the SD card / USB drive while one is in (its own picker intent
     * opens right on it), "Somewhere else". Cloud storage first opens the
     * sheet's "Find your cloud app" page, which shows the ☰ the picker hides
     * cloud apps behind before the user is on a screen that is not ours. The
     * pick comes back through {@link #observePlaceResult} and opens from
     * here, where the pickers are registered. The cloud card names only apps
     * verified to take a folder pick and needs no detection, which is what
     * the per-app rows it replaced could not do (see {@link BackupPlaces}).
     */
    private void showPlaceChooser() {
        NavigationUtils.navigateSafe(mNavController, R.id.action_sync_to_places);
    }

    /**
     * The place picked in the sheet, delivered to this fragment's own
     * back-stack entry (the scanner's pattern, {@link #observeScanResult}).
     * Consumed with {@code set(null)}, never {@code remove()}, which would
     * detach this LiveData from the handle. Handled on the next frame: the
     * sheet hands the pick over from inside its own pop, and the WebDAV
     * branch navigates.
     */
    private void observePlaceResult() {
        NavBackStackEntry entry = NavHostFragment.findNavController(this).getCurrentBackStackEntry();
        if (entry == null) {
            return;
        }
        entry.getSavedStateHandle()
                .getLiveData(BackupPlacesSheetDialogFragment.RESULT, (Integer) null)
                .observe(getViewLifecycleOwner(), place -> {
                    if (place == null) {
                        return;
                    }
                    entry.getSavedStateHandle().set(BackupPlacesSheetDialogFragment.RESULT,
                            (Integer) null);
                    View view = getView();
                    if (view != null) {
                        view.post(() -> openPlace(place));
                    }
                });
    }

    private void openPlace(int place) {
        Context context = getContext();
        if (context == null || !isAdded()) {
            return;
        }
        switch (place) {
            case BackupPlacesSheetDialogFragment.PLACE_WEBDAV -> openWebDav();
            case BackupPlacesSheetDialogFragment.PLACE_VOLUME -> {
                // Asked again here: a card pulled since the sheet opened
                // falls back to the plain picker.
                BackupPlaces.Volume volume = BackupPlaces.removable(context);
                if (volume == null) {
                    launchPicker(null, null);
                    return;
                }
                String label = TextUtils.isEmpty(volume.label)
                        ? getString(R.string.folder_backup_provider_sd) : volume.label;
                launchPicker(volume.intent, volume.intent != null
                        ? null : getString(R.string.folder_backup_place_volume_sub, label));
            }
            // Cloud and "Somewhere else" open the plain picker with no toast:
            // for cloud the sheet's guide page showed the ☰ a moment ago.
            default -> launchPicker(null, null);
        }
    }

    /** The WebDAV sign-in page; prefilled from the saved server, if any. */
    private void openWebDav() {
        NavigationUtils.navigateSafe(mNavController, R.id.action_sync_to_webdav);
    }

    /**
     * Opens the system folder picker: a volume's own intent when the chooser
     * row carried one (the picker opens on the card), else the plain picker
     * pre-pointed at the current folder when there is one (a hint the picker
     * is free to ignore). {@code hint} is toasted as the picker opens, for
     * the one row that needs it there: a removable volume with no picker
     * intent of its own (before API 29), found under the picker's ☰.
     */
    private void launchPicker(@Nullable Intent intent, @Nullable String hint) {
        try {
            if (intent != null) {
                mVolumePicker.launch(intent);
            } else {
                mFolderPicker.launch(mCloudBackup.folderStore().treeUri());
            }
        } catch (ActivityNotFoundException e) {
            snackbar(getString(R.string.folder_backup_pick_unavailable));
            return;
        }
        Context context = getContext();
        if (hint != null && context != null) {
            Toast.makeText(context, hint, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The folder picker's result, GUARDED: a tree that is the app's own
     * download directory or anything under it (the phone's
     * {@code Download/Firedown}, which also holds the {@code backup/} folder
     * the download-list mirror writes — a folder literally named backup, the
     * natural wrong pick) is a second copy on the same storage as the
     * originals. It survives a deleted file and not a lost phone, which is
     * what the feature is for, so the pick is questioned once: choose another
     * (back to the picker) or use it anyway (adopted like any other). The
     * guard knows only the external-storage provider's paths; a cloud
     * provider's tree has no path and is never questioned.
     */
    private void onFolderPicked(@Nullable Uri treeUri) {
        if (treeUri == null) {
            return; // user backed out of the picker
        }
        Context context = getContext();
        if (context == null) {
            return;
        }
        File downloads = new File(StoragePaths.getDownloadPath(context));
        if (FolderStore.treeIsWithin(treeUri, downloads)) {
            String label = FolderStore.treePathLabel(treeUri);
            if (TextUtils.isEmpty(label)) {
                label = downloads.getName();
            }
            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.folder_backup_guard_title)
                    .setMessage(getString(R.string.folder_backup_guard_message, label))
                    .setPositiveButton(R.string.folder_backup_guard_choose_other,
                            (dialog, which) -> showPlaceChooser())
                    .setNegativeButton(R.string.folder_backup_guard_use_anyway,
                            (dialog, which) -> adoptPickedFolder(treeUri))
                    .show();
            return;
        }
        adoptPickedFolder(treeUri);
    }

    /**
     * Adopts a picked tree once it passes the write check
     * ({@link CloudBackupManager#checkAndAdoptFolder}): a folder the provider
     * will not let us create, write and delete in is refused HERE, at the
     * choice, instead of surfacing as the first backup failing later. On
     * success the store takes the persistable grant and remembers the tree;
     * the cached status is about the PREVIOUS folder, so this screen's own
     * last answer is dropped before the re-read, and the hero rebinds from
     * the fresh listing (a folder that already holds this account's backups,
     * a reinstall pointed back at its Drive folder, lists them straight
     * away). The adopt happens in the manager, so a pick still lands if the
     * user left this screen while the check ran.
     */
    private void adoptPickedFolder(@NonNull Uri treeUri) {
        View view = getView();
        if (view != null) {
            mCheckingSnackbar = Snackbar.make(view, R.string.folder_backup_checking,
                    Snackbar.LENGTH_INDEFINITE);
            mCheckingSnackbar.show();
        }
        mCloudBackup.checkAndAdoptFolder(treeUri, adopted -> {
            if (mCheckingSnackbar != null) {
                mCheckingSnackbar.dismiss();
                mCheckingSnackbar = null;
            }
            if (!isAdded()) {
                return;
            }
            if (!adopted) {
                showUnwritable(treeUri);
                return;
            }
            mLastLoaded = null;
            updateState();
            String provider = mCloudBackup.providerLabel();
            if (!TextUtils.isEmpty(provider)) {
                snackbar(getString(R.string.folder_backup_folder_set, provider));
            }
        });
    }

    /** The write check refused the pick: say where, and offer the chooser
     *  again. The current folder, if any, was left as it was. */
    private void showUnwritable(@NonNull Uri treeUri) {
        String where = mCloudBackup.folderStore().providerLabelOf(treeUri);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.folder_backup_unwritable_title)
                .setMessage(getString(R.string.folder_backup_unwritable_message, where))
                .setPositiveButton(R.string.folder_backup_guard_choose_other,
                        (dialog, which) -> showPlaceChooser())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * "How sync & encryption work" → the website page, in a Firedown tab, the
     * same way the root screen's Donate / Help rows open theirs
     * ({@link SettingsActivity#openInBrowser}). Replaced the offline FAQ
     * sub-screen — see settings_sync.xml.
     */
    private void openHelpPage() {
        SettingsActivity.openInBrowser(mActivity, getString(R.string.settings_sync_help_url));
    }

    /**
     * Reveals the recovery code behind a device-auth check (biometric / PIN /
     * pattern) when the device has a secure lock — the code is the master secret
     * for everything backed up, so a borrowed/unlocked phone shouldn't surface
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
     * re-evaluate ({@link #updateState}) — the CTA morphs to "Choose backup
     * folder" and bookmarks enable now that a key exists. Guarded on
     * {@link SyncManager#hasCode()} so a stray tap can never overwrite an
     * existing key.
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
     * exporting it to a file expose the master key for everything backed up, so
     * both gate through here. With no biometric / device credential enrolled
     * there is nothing to authenticate against, so {@code onAuth} runs directly.
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
     *  stays on the server; the shared code is wiped only if the downloads
     *  backup no longer needs it — see SyncManager.disable). */
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
     * "I have a recovery code" — links this device to an existing account (the
     * key that decrypts a backup folder another install wrote) via
     * {@link SyncManager#linkWithCode}, which stores the code WITHOUT enabling
     * bookmark sync. Reuses the code-input dialog layout.
     *
     * <p>The code alone joins nothing. Under the hosted service one code WAS
     * one backup; now it is the key plus the NAME of the account directory
     * inside whatever folder each device chose, so two devices meet only when
     * they also use the same folder. Every string on this door therefore ends
     * in "then choose the same backup folder" — the copy shipped for a cycle
     * as "both devices will share one backup", which sent a second phone to
     * an empty Backups list expecting files that live in a folder it had
     * never picked.
     *
     * <p>When this device ALREADY has a code, adopting another one is a REPLACE
     * and is confirmed first. The warning is not ceremony: the recovery code is
     * the ONLY key to what sits under it, so a user who never saved the current
     * one is about to lose reach of their own backed-up files — nothing is
     * deleted in the folder, it simply becomes unreadable from here. The
     * confirm step therefore points at the Recovery code row (save it first)
     * rather than trying to be clever, and it is the one place this screen
     * asks twice.
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
                    // no surface can repaint the old figures in between.
                    mCloudBackup.forgetCachedStatus();
                    mLastLoaded = null;
                    mSyncManager.linkWithCode(code, ok -> {
                        if (!isAdded()) {
                            return;
                        }
                        updateState();
                        // The code is the KEY and the directory NAME in the
                        // folder, not the backup: the other device's files are
                        // readable here only once this device also uses the
                        // folder they sit in. With no folder chosen yet the
                        // next step is choosing it, so the snackbar says so
                        // rather than claiming the backups are readable now.
                        int message;
                        if (!ok) {
                            message = R.string.settings_sync_link_bad;
                        } else if (mCloudBackup.hasFolder()) {
                            message = R.string.settings_sync_link_ok;
                        } else {
                            message = R.string.settings_sync_link_ok_choose_folder;
                        }
                        snackbar(getString(message));
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
                // its own row + second auth prompt on the Backup screen (the old
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
     * documented from the Backups item sheet; it is the same handle mechanism
     * here.
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
                    if (!mSyncManager.looksLikeRecoveryCode(trimmed)) {
                        snackbar(getString(R.string.settings_sync_scan_bad));
                        return;
                    }
                    showLinkCodeInput(trimmed);
                });
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
            // Exactly one panel visible, both set on every tap — the
            // one-sided-set trap.
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
     * Debug-only row showing the ACCOUNT ID (Crockford base32) — the name of
     * this account's directory under {@code <folder>/Firedown Backup/}, so a
     * dev can find the right subtree in a file manager; tap copies it. Never
     * added in a release build; absent until a code exists.
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
        row.setSummary(base32 + " — tap to copy; the account directory under Firedown Backup/");
        row.setOnPreferenceClickListener(pref -> {
            copyToClipboard(base32);
            return true;
        });
        screen.addPreference(row);
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

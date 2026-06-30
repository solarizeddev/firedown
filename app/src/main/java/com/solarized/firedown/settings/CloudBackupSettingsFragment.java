package com.solarized.firedown.settings;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.VaultSmokeTest;
import com.solarized.firedown.utils.NavigationUtils;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.OkHttpClient;

/**
 * Downloads-backup (Cloud Backup) management sub-screen, reached from the unified
 * Sync screen's "Downloads backup" row. PURELY downloads management — status, the
 * backed-up files list, and right-to-erasure. The recovery code + FAQ are shared
 * and live on the parent Sync screen, not here.
 *
 * <p>The encrypted cloud backup of finished downloads (to
 * {@code storage.firedown.app}) is a SEPARATE feature from the local "Safe
 * Folder" ({@code file_safe}); copy here never says "vault". It's action-driven
 * (a download's options sheet backs it up on demand), so there is no on/off
 * switch — the Manage category is hidden until it's set up.
 */
@AndroidEntryPoint
public class CloudBackupSettingsFragment extends BasePreferenceFragment
        implements Preference.OnPreferenceClickListener {

    @Inject
    CloudBackupManager mCloudBackup;

    /** Shared OkHttp client — used only by the debug smoke-test row below. */
    @Inject
    OkHttpClient mHttpClient;

    private Preference mStatus;
    private Preference mFiles;
    private Preference mDeleteData;
    private PreferenceCategory mCatManage;

    /** True while a transfer is running, so a usage refresh doesn't clobber the
     *  live "Transfer in progress…" status. */
    private boolean mTransferActive;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_cloud_backup, rootKey);

        mStatus = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_STATUS);
        mFiles = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_FILES);
        mDeleteData = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_DELETE_DATA);
        mCatManage = findPreference(Preferences.SETTINGS_CLOUD_BACKUP_CAT_MANAGE);

        if (mFiles != null) {
            mFiles.setOnPreferenceClickListener(this);
        }
        if (mDeleteData != null) {
            mDeleteData.setOnPreferenceClickListener(this);
        }

        tintIcons();
        updateState();
        addDebugSmokeTestRow();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Live status: while an upload/restore runs, the status line reads
        // "Transfer in progress…" instead of the static usage.
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
                    mTransferActive = active;
                    updateState();
                });
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
            case Preferences.SETTINGS_CLOUD_BACKUP_FILES ->
                    NavigationUtils.navigateSafe(mNavController, R.id.action_cloud_backup_to_files);
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
        if (mTransferActive) {
            mStatus.setSummary(getString(R.string.settings_cloud_backup_active));
            return;
        }
        if (!setUp) {
            mStatus.setSummary(getString(R.string.settings_cloud_backup_not_set_up));
            return;
        }
        mCloudBackup.loadUsage(usage -> {
            if (!isAdded() || mStatus == null || mTransferActive) {
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
     * Debug-only row that round-trips a file through the live storage server,
     * reusing the shared recovery code. Never added in a release build. This is
     * the on-device smoke test for the storage client; it lives on the downloads
     * sub-screen (not the main Sync screen) so it stays out of the way.
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

    private void snackbar(String text) {
        View view = getView();
        if (view != null) {
            Snackbar.make(view, text, Snackbar.LENGTH_LONG).show();
        }
    }
}

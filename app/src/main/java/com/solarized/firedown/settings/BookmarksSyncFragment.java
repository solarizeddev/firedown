package com.solarized.firedown.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.SyncManager;
import com.solarized.firedown.sync.SyncWorker;

import java.util.UUID;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Focused Bookmarks-sync screen: the master toggle, Sync now, and
 * Delete-from-server — the bookmark-only actions, lifted off the Sync hub so it
 * stays a thin account page. The shared recovery code lives on the hub
 * ({@link SyncSettingsFragment}); setup here only reveals the freshly-minted code
 * once (no auth — it was just created). All network/crypto work is in
 * {@link SyncManager}.
 */
@AndroidEntryPoint
public class BookmarksSyncFragment extends BasePreferenceFragment
        implements Preference.OnPreferenceClickListener {

    @Inject
    SyncManager mSyncManager;

    private SwitchPreferenceCompat mEnableSwitch;
    private Preference mSyncNow;
    private Preference mDeleteData;

    /** Last sync state seen, so the failure snackbar fires only on a fresh
     *  SYNCING -> ERROR transition (not on every entry with a stale error). */
    private int mSyncState = SyncManager.STATE_OFF;

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // A sync that fails while this screen is open (manual "Sync now" OR a
        // background run) surfaces a snackbar, gated on SYNCING -> ERROR so a stale
        // error on entry shows nothing.
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
        setPreferencesFromResource(R.xml.settings_bookmarks_sync, rootKey);

        mEnableSwitch = findPreference(Preferences.SYNC_ENABLED);
        mSyncNow = findPreference(Preferences.SETTINGS_SYNC_NOW);
        mDeleteData = findPreference(Preferences.SETTINGS_SYNC_DELETE_DATA);

        if (mEnableSwitch != null) {
            // Never let the switch self-persist — SyncManager owns SYNC_ENABLED and
            // the switch is re-synced from isEnabled() in updateState().
            mEnableSwitch.setOnPreferenceChangeListener((pref, newValue) -> {
                boolean want = Boolean.TRUE.equals(newValue);
                if (want && !mSyncManager.isEnabled()) {
                    if (mSyncManager.hasCode()) {
                        // The shared account key already exists (Cloud Backup
                        // set up first, or a key created on the hub) — enable
                        // with it directly, no dialog. The setup chooser here
                        // offered "start new" over a key that may front a PAID
                        // storage balance; minting would have overwritten the
                        // only key to it (SyncManager.enableWithNewCode now
                        // also backstops this, but this path shouldn't ask at
                        // all — one code, both features, by design).
                        mSyncManager.enableWithExistingCode();
                        updateState();
                    } else {
                        showSetupDialog();
                    }
                } else if (!want && mSyncManager.isEnabled()) {
                    showSignOutDialog();
                }
                return false;
            });
        }
        if (mSyncNow != null) {
            mSyncNow.setOnPreferenceClickListener(this);
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
        updateState();
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        switch (key) {
            case Preferences.SETTINGS_SYNC_NOW -> startSyncNow();
            case Preferences.SETTINGS_SYNC_DELETE_DATA -> showDeleteDataDialog();
        }
        return true;
    }

    /** Reflects persisted state into the switch + row visibility. */
    private void updateState() {
        boolean on = mSyncManager.isEnabled();
        if (mEnableSwitch != null) {
            mEnableSwitch.setChecked(on);
            mEnableSwitch.setSummary(on
                    ? lastSyncedSummary()
                    : getString(R.string.settings_sync_switch_summary));
        }
        // Sync now + Delete are meaningful only when sync is on.
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

    /**
     * Runs "Sync now" and reports the terminal result — synced-count or failure —
     * by observing the one-shot work.
     */
    private void startSyncNow() {
        UUID id = mSyncManager.syncNow();
        if (id == null) {
            return; // disabled (row hidden) — guard anyway
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
                    updateState();
                }
                // A terminal FAILURE surfaces via the SYNCING -> ERROR snackbar in
                // the onViewCreated state observer, so it isn't duplicated here.
            }
        });
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

    /** Shows the grouped recovery code with a copy action and the no-recovery
     *  warning. Only the just-created path is reached here (setup); viewing the
     *  code later lives on the Sync hub behind a device-auth gate. */
    private void showCodeDialog(@Nullable String grouped, boolean justCreated) {
        if (grouped == null) {
            snackbar(getString(R.string.settings_sync_code_unavailable));
            return;
        }
        View view = getLayoutInflater().inflate(R.layout.dialog_sync_show_code, null);
        TextView codeText = view.findViewById(R.id.sync_code_text);
        codeText.setText(grouped);

        CheckBox savedCheck = view.findViewById(R.id.sync_code_saved_check);
        savedCheck.setVisibility(justCreated ? View.VISIBLE : View.GONE);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(justCreated
                        ? R.string.settings_sync_code_created_title
                        : R.string.settings_sync_show_code_title)
                .setNeutralButton(R.string.settings_sync_code_copy, null)
                .setPositiveButton(R.string.settings_sync_code_done, null)
                .setCancelable(!justCreated)
                .setView(view)
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
     * Confirms and runs the server-side erasure (right-to-erasure) — distinct from
     * "Turn off sync"; on success it also turns sync off locally.
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

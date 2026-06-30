package com.solarized.firedown.settings;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.VaultRestoreWorker;
import com.solarized.firedown.sync.VaultThumbnail;
import com.solarized.firedown.sync.model.VaultEntry;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * The "Backed-up files" list — every file currently in Cloud Backup, with restore
 * and remove-from-cloud per item. Built as a dynamic preference screen (one row
 * per manifest entry) so it inherits the settings chrome; the file count is small
 * (personal backups), so a full RecyclerView isn't warranted.
 *
 * <p>Restore enqueues {@link VaultRestoreWorker} (download → decrypt → register as
 * a FINISHED download); remove deletes the object + drops it from the manifest.
 */
@AndroidEntryPoint
public class CloudBackupListFragment extends BasePreferenceFragment {

    @Inject
    CloudBackupManager mCloudBackup;

    private final List<VaultEntry> mEntries = new ArrayList<>();
    private boolean mLoading = true;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
        load();
    }

    private void load() {
        mLoading = true;
        rebuild();
        mCloudBackup.loadEntries(entries -> {
            if (!isAdded()) {
                return;
            }
            mLoading = false;
            mEntries.clear();
            mEntries.addAll(entries);
            rebuild();
        }, () -> {
            if (!isAdded()) {
                return;
            }
            mLoading = false;
            rebuild();
            snackbar(getString(R.string.cloud_backup_list_error));
        });
    }

    /** Rebuilds the row list from {@link #mEntries} (placeholder while loading/empty). */
    private void rebuild() {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return;
        }
        screen.removeAll();
        if (mLoading) {
            screen.addPreference(placeholder(getString(R.string.cloud_backup_list_loading)));
            return;
        }
        if (mEntries.isEmpty()) {
            screen.addPreference(placeholder(getString(R.string.cloud_backup_list_empty)));
            return;
        }
        List<Preference> rows = new ArrayList<>(mEntries.size());
        for (VaultEntry entry : mEntries) {
            Preference row = new Preference(requireContext());
            row.setPersistent(false);
            row.setIcon(R.drawable.cloud_done_24); // fallback glyph (tinted below)
            row.setTitle(entry.name);
            row.setSummary(summaryFor(entry));
            row.setOnPreferenceClickListener(p -> {
                showItemActions(entry);
                return true;
            });
            screen.addPreference(row);
            rows.add(row);
        }
        // Tint the fallback glyphs FIRST, then overlay the real thumbnails so the
        // theme tint never colorizes a photo (tintIcons mutates the icon drawable).
        tintIcons();
        for (int i = 0; i < mEntries.size(); i++) {
            Bitmap bmp = VaultThumbnail.decode(mEntries.get(i).thumb);
            if (bmp != null) {
                RoundedBitmapDrawable d = RoundedBitmapDrawableFactory.create(getResources(), bmp);
                d.setCornerRadius(bmp.getWidth() * 0.12f);
                rows.get(i).setIcon(d);
            }
        }
    }

    private Preference placeholder(String text) {
        Preference p = new Preference(requireContext());
        p.setPersistent(false);
        p.setSelectable(false);
        p.setTitle(text);
        return p;
    }

    private String summaryFor(VaultEntry entry) {
        String size = Formatter.formatShortFileSize(requireContext(), entry.size);
        if (entry.downloadedAt <= 0) {
            return size;
        }
        CharSequence date = DateUtils.getRelativeTimeSpanString(
                entry.downloadedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        return getString(R.string.cloud_backup_item_summary, size, date);
    }

    /** Per-file actions: restore to Downloads, or remove from the cloud. */
    private void showItemActions(VaultEntry entry) {
        CharSequence[] actions = {
                getString(R.string.cloud_backup_item_restore),
                getString(R.string.cloud_backup_item_remove),
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(entry.name)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        restore(entry);
                    } else {
                        confirmRemove(entry);
                    }
                })
                .show();
    }

    private void restore(VaultEntry entry) {
        Data input = new Data.Builder()
                .putString(VaultRestoreWorker.KEY_OBJECT_ID, entry.objectId)
                .putString(VaultRestoreWorker.KEY_WRAPPED_DEK, entry.wrappedDek)
                .putString(VaultRestoreWorker.KEY_NAME, entry.name)
                .putString(VaultRestoreWorker.KEY_MIME, entry.mime)
                .putLong(VaultRestoreWorker.KEY_SIZE, entry.size)
                .putLong(VaultRestoreWorker.KEY_DOWNLOADED_AT, entry.downloadedAt)
                .putInt(VaultRestoreWorker.KEY_CHUNK_COUNT, entry.chunkCount)
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(VaultRestoreWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .addTag(CloudBackupManager.WORK_TAG)
                .build();
        WorkManager wm = WorkManager.getInstance(requireContext().getApplicationContext());
        wm.enqueue(request);
        snackbar(getString(R.string.cloud_restore_started));

        final LiveData<WorkInfo> live = wm.getWorkInfoByIdLiveData(request.getId());
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
                boolean ok = info.getState() == WorkInfo.State.SUCCEEDED;
                snackbar(getString(ok
                        ? R.string.cloud_restore_done
                        : R.string.cloud_restore_failed));
            }
        });
    }

    private void confirmRemove(VaultEntry entry) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.cloud_backup_remove_title)
                .setMessage(getString(R.string.cloud_backup_remove_message, entry.name))
                .setPositiveButton(R.string.cloud_backup_remove_action, (dialog, which) ->
                        mCloudBackup.deleteEntry(entry, ok -> {
                            if (!isAdded()) {
                                return;
                            }
                            if (ok) {
                                mEntries.remove(entry);
                                rebuild();
                            }
                            snackbar(getString(ok
                                    ? R.string.cloud_backup_remove_done
                                    : R.string.cloud_backup_remove_failed));
                        }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void snackbar(String text) {
        View view = getView();
        if (view != null) {
            Snackbar.make(view, text, Snackbar.LENGTH_LONG).show();
        }
    }
}

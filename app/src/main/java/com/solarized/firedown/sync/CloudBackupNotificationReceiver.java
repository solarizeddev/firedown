package com.solarized.firedown.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationManagerCompat;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;

import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Handles the "Back up to cloud" action on the download-finished notification
 * ({@code RunnableManager.startNotificationFinish}). The notification is the one
 * surface that fires at the moment the user just saved a file they care about,
 * so it carries the same per-item backup door the options sheet has — but a
 * notification action has no fragment, so the entity is re-loaded here by row id
 * and the shared {@link VaultBackupWorker#enqueue} does the rest (identical
 * request shape + unique-work key, so a notification tap and a sheet tap for the
 * same file collapse to one worker).
 *
 * <p>The intent carries only the ROW ID, not the entity fields: the row is
 * re-read at tap time so a download deleted (or vaulted) between finish and tap
 * degrades to a silent no-op instead of enqueueing a backup for a file that is
 * gone. All guards re-run here — the notification-build gates are advisory only,
 * since a notification can outlive any state it was built against.
 *
 * <p>Feedback needs no extra UI: {@link VaultBackupWorker} runs foreground, so
 * its own "Backing up…" notification replaces the finished one (cancelled below)
 * within moments, and the Backups list shows the live transfer row.
 */
@AndroidEntryPoint
public class CloudBackupNotificationReceiver extends BroadcastReceiver {

    public static final String ACTION_BACKUP = "com.solarized.firedown.ACTION_NOTIFICATION_CLOUD_BACKUP";
    public static final String EXTRA_DOWNLOAD_ID = "download_id";
    public static final String EXTRA_NOTIFICATION_ID = "notification_id";

    @Inject
    DownloadDataRepository mRepository;

    @Inject
    CloudBackupManager mCloudBackup;

    @Inject
    @Qualifiers.DiskIO
    Executor mDiskExecutor;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_BACKUP.equals(intent.getAction())) {
            return;
        }
        final int downloadId = intent.getIntExtra(EXTRA_DOWNLOAD_ID, -1);
        final int notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);
        if (downloadId < 0) {
            return;
        }
        // The row read is a DB call — off the main thread, with goAsync() keeping
        // the process alive until the enqueue lands (WorkManager then owns it).
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        mDiskExecutor.execute(() -> {
            try {
                backup(app, downloadId, notificationId);
            } finally {
                pending.finish();
            }
        });
    }

    private void backup(Context context, int downloadId, int notificationId) {
        // Re-check every gate against CURRENT state (see the class doc): the
        // entity may have been deleted, re-downloaded, or moved to the vault
        // since the notification was posted, and cloud backup may have been
        // erased. The vault gate is a contract, not a convenience — vault
        // content never leaves the device.
        if (!mCloudBackup.isSetUp()) {
            return;
        }
        DownloadEntity entity = mRepository.findByIdSync(downloadId);
        if (entity == null
                || entity.getFileStatus() != Download.FINISHED
                || entity.isFileSafe()) {
            return;
        }
        VaultBackupWorker.enqueue(context, entity);
        if (notificationId >= 0) {
            // The worker's own foreground notification takes over as feedback.
            NotificationManagerCompat.from(context).cancel(notificationId);
        }
    }
}

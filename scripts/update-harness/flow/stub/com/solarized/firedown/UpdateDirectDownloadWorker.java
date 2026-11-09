package com.solarized.firedown;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

/**
 * The real worker's two static doors (its OkHttp transport is not part of the
 * flow: the harness runs UpdateDirectDownload.run with a fake server).
 */
public class UpdateDirectDownloadWorker extends androidx.work.Worker {
    public UpdateDirectDownloadWorker(android.content.Context c, androidx.work.WorkerParameters p) { super(c, p); }
    public Result doWork() { return null; }

    static String workName(long id) { return "update_direct_" + id; }

    public static void enqueue(android.content.Context c, long id) {
        WorkManager.getInstance(c).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(UpdateDirectDownloadWorker.class)
                        .setInputData(new Data.Builder().putLong("download_id", id).build()).build());
    }

    public static void cancel(android.content.Context c, long id) {
        WorkManager.getInstance(c).cancelUniqueWork(workName(id));
    }
}

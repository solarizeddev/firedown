package com.solarized.firedown;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.solarized.firedown.utils.NotificationID;

/**
 * The install the user started from the "Tap to update" notification.
 *
 * A worker, not the receiver the notification calls: the copy of the ~200 MB
 * APK into Android's installer takes seconds to tens of seconds, and a
 * receiver that holds the broadcast past 60 s is killed mid-copy, before
 * the installer's finally can abandon its session. Expedited, so it starts at
 * once; on Android 11 and older that runs it as a foreground service, whose
 * notice is the install's own progress notification.
 *
 * Unique and KEEP: a second tap while one runs adds nothing (the installer
 * also allows one install at a time).
 */
public class UpdateInstallWorker extends Worker {

    static final String KEY_NAME = "name";

    public UpdateInstallWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void enqueue(Context context, String versionName) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(UpdateInstallWorker.class)
                .setInputData(new Data.Builder().putString(KEY_NAME, versionName).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                "update_install", ExistingWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        // install() catches every failure and reports it itself.
        UpdateInstaller.install(getApplicationContext(), getInputData().getString(KEY_NAME), true);
        return Result.success();
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        return foregroundInfo(NotificationID.APK_UPDATE_PROMPT_INSTALL,
                UpdateNotification.buildInstallProgress(getApplicationContext(), 0));
    }

    /** A foreground notice of the dataSync type the manifest gives WorkManager's service. */
    static ForegroundInfo foregroundInfo(int id, Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        return new ForegroundInfo(id, notification);
    }
}

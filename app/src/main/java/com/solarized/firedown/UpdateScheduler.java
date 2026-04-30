package com.solarized.firedown;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.qualifiers.ApplicationContext;

public class UpdateScheduler {

    private final Context context;

    @Inject
    public UpdateScheduler(@ApplicationContext Context context) {
        this.context = context;
    }


    public void schedulePeriodicUpdateCheck() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED) // Wi-Fi only to save data
                .build();

        PeriodicWorkRequest periodicUpdate = new PeriodicWorkRequest.Builder(
                UpdateWorker.class,
                24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag("periodic_update_tag")
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "periodic_update_check",
                ExistingPeriodicWorkPolicy.KEEP, // Don't restart the 24h timer if already set
                periodicUpdate
        );
    }

    public void setupOneTimeCheck(){
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(UpdateWorker.class)
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                "startup_update_check",
                ExistingWorkPolicy.KEEP,
                request
        );
    }
}

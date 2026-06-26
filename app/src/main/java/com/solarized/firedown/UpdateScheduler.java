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

        // 6h interval with a 2h flex window (was a flat 24h). The background
        // periodic check is heavily deferred in Doze / app-standby and won't
        // run at all after the app is force-stopped (the common OEM
        // swipe-to-kill case), so the only check that reliably fired was the
        // one-time check on app open — hence "I only see updates when I open
        // the app". A shorter interval plus a flex window gives the scheduler
        // far more opportunities to slot the job into a Doze maintenance
        // window. (This is not a cure for force-stop; only opening the app or
        // a battery-optimization exemption fixes that.)
        PeriodicWorkRequest periodicUpdate = new PeriodicWorkRequest.Builder(
                UpdateWorker.class,
                6, TimeUnit.HOURS,
                2, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag("periodic_update_tag")
                .build();

        // UPDATE (not KEEP): KEEP would leave already-scheduled installs on the
        // old 24h job forever, so the shorter interval would only ever apply to
        // fresh installs. UPDATE re-applies the new spec to the existing job.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "periodic_update_check",
                ExistingPeriodicWorkPolicy.UPDATE,
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

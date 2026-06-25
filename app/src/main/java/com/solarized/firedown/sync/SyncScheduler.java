package com.solarized.firedown.sync;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * WorkManager scheduling for bookmark sync — mirrors {@code UpdateScheduler}.
 * A periodic job keeps devices converged; a one-time job runs an immediate sync
 * (change-triggered or "Sync now"). Both require network.
 */
public class SyncScheduler {

    private static final String PERIODIC = "bookmarks_sync_periodic";
    private static final String ONESHOT = "bookmarks_sync_oneshot";

    private final Context context;

    @Inject
    public SyncScheduler(@ApplicationContext Context context) {
        this.context = context;
    }

    private static Constraints networkConstraint() {
        return new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
    }

    /** Periodic convergence sync (every ~6h). KEEP so it isn't restarted each call. */
    public void schedulePeriodic() {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                SyncWorker.class, 6, TimeUnit.HOURS)
                .setConstraints(networkConstraint())
                .addTag(PERIODIC)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    /**
     * Runs a sync as soon as the network allows. REPLACE so a newer change
     * supersedes an already-queued (debounced) push rather than stacking.
     */
    public UUID syncNow() {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(networkConstraint())
                .addTag(ONESHOT)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT, ExistingWorkPolicy.REPLACE, req);
        return req.getId();
    }

    /** Cancels all sync work (sign-out / disable). */
    public void cancel() {
        WorkManager wm = WorkManager.getInstance(context);
        wm.cancelUniqueWork(PERIODIC);
        wm.cancelUniqueWork(ONESHOT);
    }
}

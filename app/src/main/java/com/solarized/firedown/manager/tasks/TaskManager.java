package com.solarized.firedown.manager.tasks;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.solarized.firedown.App;
import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.data.repository.TaskRepository;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.utils.NotificationID;

import java.util.ArrayList;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;


/**
 * Runs the media and vault tasks (GIF, audio, frame, archive, vault lock and
 * unlock) one at a time, foreground while one runs.
 *
 * <p>It used to be a plain started service. Android stops one of those about
 * a minute after its app leaves the screen, and caches and freezes the
 * process. The task's thread outlived the stop, so the task kept running in
 * a service Android had already destroyed: Cancel and Stop then started a NEW
 * TaskManager, with its own thread and no running task, and did nothing, and
 * the task stalled whenever the process was frozen. Now:
 * <ul>
 * <li>one worker thread for the process, and the running task in a static,
 *     so any TaskManager instance reaches it;</li>
 * <li>foreground while a task runs, with a progress notification and a
 *     Cancel, and a partial wake lock, so it keeps going with the screen
 *     off. The start comes from the user's tap, so the app is in the
 *     foreground and Android 12's background-start ban does not apply; a
 *     refused startForeground leaves the task running as before;</li>
 * <li>each start carries its own copy of its inputs.</li>
 * </ul>
 */
@AndroidEntryPoint
public class TaskManager extends Service {

    private static final String TAG = TaskManager.class.getName();

    private static final int MSG_START = 1;

    /* The notification's Cancel: stops whatever task runs now. */
    private static final String ACTION_CANCEL_CURRENT = "com.solarized.firedown.tasks.CANCEL_CURRENT";

    /* Android caps a dataSync or mediaProcessing service at 6 hours a day;
     * the wake lock never outlives that. */
    private static final long WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L;

    private static final Object sWorkerLock = new Object();
    /* Guarded by sWorkerLock. Never quit: one thread for the process. */
    private static Handler sWorker;

    /* Written on the worker thread, read from the main thread to cancel. */
    private static volatile TaskRunnable sCurrentRunnable;

    /* The inputs of the task running now. Written on the worker; onDestroy
     * reads it from the main thread. */
    private static volatile Job sCurrentJob;

    /* The percent the notification shows, so a tick that changes nothing
     * re-posts nothing. Worker thread only. */
    private static int sShownPercent = -1;

    private final Object mLifecycleLock = new Object();
    /* Starts this instance accepted and not yet finished. Guarded by
     * mLifecycleLock. */
    private int mPendingJobs;
    /* The newest startId seen. Guarded by mLifecycleLock. */
    private int mLatestStartId;
    /* Whether startForeground took. Guarded by mLifecycleLock. */
    private boolean mForeground;
    /* Guarded by mLifecycleLock. */
    private boolean mDestroyed;

    private PowerManager.WakeLock mWakeLock;

    @Inject
    DownloadDataRepository mDownloadRepository; // Injected by Hilt
    @Inject
    TaskRepository mTaskRepository; // Injected by Hilt


    /* One start request with its own copy of the inputs. They used to live in
     * service fields that every start overwrote at once on the main thread,
     * while the running task still read them: a second start mid-task
     * changed the first task's file list and GIF settings under it. */
    private static final class Job {
        final TaskManager service;
        final String action;
        final ArrayList<DownloadEntity> entities;
        final GifMakerArgs gifMakerArgs;
        final long framePosMs;
        /* Read before the task runs: tasks rename the entities they are
         * handed (a GIF's row becomes the .gif), and the line must not
         * change mid-task. */
        final CharSequence notificationText;

        Job(TaskManager service, String action, ArrayList<DownloadEntity> entities,
            GifMakerArgs gifMakerArgs, long framePosMs) {
            this.service = service;
            this.action = action;
            this.entities = entities;
            this.gifMakerArgs = gifMakerArgs;
            this.framePosMs = framePosMs;
            this.notificationText = service.notificationTextFor(action, entities);
        }
    }

    @NonNull
    private static Handler worker() {
        synchronized (sWorkerLock) {
            if (sWorker == null) {
                // Background priority so CPU-heavy work doesn't disrupt the UI.
                HandlerThread thread = new HandlerThread("TaskManager",
                        Process.THREAD_PRIORITY_BACKGROUND);
                thread.start();
                sWorker = new Handler(thread.getLooper(), TaskManager::handleWorkerMessage);
            }
            return sWorker;
        }
    }

    private static boolean handleWorkerMessage(@NonNull Message msg) {
        if (msg.what == MSG_START && msg.obj instanceof Job job) {
            job.service.runJob(job);
        }
        return true;
    }

    /* Runs on the worker, synchronously. The task used to be post()ed from
     * here, so it ran after any start already queued, by which time the
     * "current task" named the next one and Cancel stopped the wrong task. */
    private void runJob(@NonNull Job job) {
        Log.d(TAG, "runJob: " + job.action);
        boolean destroyed;
        synchronized (mLifecycleLock) {
            destroyed = mDestroyed;
        }
        TaskRunnable runnable = destroyed ? null : createTask(job.action);
        try {
            if (runnable != null) {
                sCurrentJob = job;
                sCurrentRunnable = runnable;
                sShownPercent = -1;
                goForeground(job, -1);
                acquireWakeLock();
                runnable.run();
            }
        } finally {
            releaseWakeLock();
            sCurrentRunnable = null;
            sCurrentJob = null;
            mTaskRepository.onTaskEnded();
            /* Tasks used to call stopSelf() themselves, which ended the
             * service under a start already queued behind them. */
            onJobEnded();
        }
        Log.d(TAG, "runJob end");
    }

    @Nullable
    private TaskRunnable createTask(String action) {
        if (IntentActions.DOWNLOAD_START_AUDIO_ENCODE.equals(action)) {
            return new AudioEncodeTask(this, mDownloadRepository);
        } else if (IntentActions.DOWNLOAD_START_MAKE_GIF.equals(action)) {
            return new GifMakerTask(this, mDownloadRepository);
        } else if (IntentActions.START_ENCRYPTION.equals(action)) {
            return new InSafeTask(this, mDownloadRepository);
        } else if (IntentActions.START_DECRYPTION.equals(action)) {
            return new OutSafeTask(this, mDownloadRepository);
        } else if (IntentActions.DOWNLOAD_START_COMPRESS.equals(action)) {
            return new CompressTask(this, mDownloadRepository);
        } else if (IntentActions.DOWNLOAD_START_EXTRACT.equals(action)) {
            return new DecompressTask(this, mDownloadRepository);
        } else if (IntentActions.DOWNLOAD_START_SAVE_FRAME.equals(action)) {
            return new SaveFrameTask(this, mDownloadRepository);
        }
        return null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        String action = intent != null ? intent.getAction() : null;

        Log.d(TAG, "onStartCommand: " + action);

        if (isStartAction(action)) {
            Job job = new Job(this, action, entitiesOf(intent),
                    IntentActions.DOWNLOAD_START_MAKE_GIF.equals(action)
                            ? new GifMakerArgs(
                                    intent.getLongExtra(Keys.GIF_START_MS, 0L),
                                    intent.getLongExtra(Keys.GIF_END_MS, 0L),
                                    intent.getIntExtra(Keys.GIF_FPS, 0),
                                    intent.getIntExtra(Keys.GIF_WIDTH, 0))
                            : null,
                    intent.getLongExtra(Keys.FRAME_POSITION_MS, 0L));
            synchronized (mLifecycleLock) {
                mLatestStartId = startId;
                mPendingJobs++;
            }
            mTaskRepository.onTaskQueued();
            // Foreground now, while the user's tap still puts the app in the
            // foreground (the worker may only reach this task later). Not
            // over this instance's own running task: its notification stays
            // as it is. A task still unwinding from an instance the system
            // destroyed is not this one's, and that instance's foreground
            // went with it, so this one must ask now; the notification
            // names the task that runs.
            Job running = sCurrentJob;
            if (sCurrentRunnable == null || running == null || running.service != this) {
                goForeground(running != null ? running : job, -1);
            }
            Handler worker = worker();
            worker.sendMessage(worker.obtainMessage(MSG_START, job));
            return START_NOT_STICKY;
        }

        synchronized (mLifecycleLock) {
            mLatestStartId = startId;
        }

        if (isCancelAction(action)) {
            Job running = sCurrentJob;
            TaskRunnable runnable = sCurrentRunnable;
            if (runnable != null && cancels(action, running)) {
                runnable.stop();
            }
        }

        // A cancel or an unknown/null intent starts nothing: stop now unless
        // this instance still has a task running or waiting.
        stopIfIdle();
        return START_NOT_STICKY;
    }

    private void stopIfIdle() {
        synchronized (mLifecycleLock) {
            if (mPendingJobs == 0) {
                leaveForegroundLocked();
                stopSelfResult(mLatestStartId);
            }
        }
    }

    private void onJobEnded() {
        synchronized (mLifecycleLock) {
            mPendingJobs--;
            /* Under the lock: a start that arrives after this still holds a
             * newer startId than mLatestStartId, so stopSelfResult is a no-op
             * for it and the service stays up to run it. */
            if (mPendingJobs == 0) {
                leaveForegroundLocked();
                stopSelfResult(mLatestStartId);
            }
        }
    }

    /* Nothing should destroy this service while a task runs, but if the
     * system does, the task ends with it instead of running on, orphaned;
     * a start still queued for this instance is skipped (runJob). */
    @Override
    public void onDestroy() {
        boolean hadWork;
        synchronized (mLifecycleLock) {
            mDestroyed = true;
            hadWork = mPendingJobs > 0;
        }
        Job job = sCurrentJob;
        TaskRunnable runnable = sCurrentRunnable;
        if (hadWork && runnable != null && job != null && job.service == this) {
            runnable.stop();
        }
        super.onDestroy();
    }

    /* The 6-hour daily cap (Android 15, onTimeout(int, int); Android 14,
     * onTimeout(int)). The base methods are empty, and missing the few
     * seconds Android grants ends the process: stop the task and leave. */
    @RequiresApi(api = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    @Override
    public void onTimeout(int startId, int fgsType) {
        stopForSystemTimeout();
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    public void onTimeout(int startId) {
        stopForSystemTimeout();
    }

    private void stopForSystemTimeout() {
        TaskRunnable runnable = sCurrentRunnable;
        if (runnable != null) {
            runnable.stop();
        }
        synchronized (mLifecycleLock) {
            leaveForegroundLocked();
        }
        stopSelf();
    }

    /* ── foreground ────────────────────────────────────────────────────── */

    private void goForeground(@NonNull Job job, int percent) {
        Notification notification = buildNotification(job, percent);
        int type = foregroundTypeFor(job.action);
        synchronized (mLifecycleLock) {
            if (mDestroyed) {
                return;
            }
            try {
                ServiceCompat.startForeground(this, NotificationID.TASK_ID, notification, type);
                mForeground = true;
            } catch (RuntimeException e) {
                // ForegroundServiceStartNotAllowedException and relatives: the
                // task still runs, as a background service, as it always did.
                Log.w(TAG, "startForeground refused", e);
            }
        }
    }

    private void leaveForegroundLocked() {
        if (!mForeground) {
            return;
        }
        mForeground = false;
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException e) {
            Log.w(TAG, "stopForeground failed", e);
        }
    }

    /** Called by the running task with its percent, already rate limited. */
    void onTaskProgress(int percent) {
        Job job = sCurrentJob;
        if (job == null || percent == sShownPercent) {
            return;
        }
        sShownPercent = percent;
        boolean foreground;
        synchronized (mLifecycleLock) {
            foreground = mForeground;
        }
        // startForeground again rather than notify(): it updates in place and
        // needs no POST_NOTIFICATIONS grant.
        if (foreground) {
            goForeground(job, percent);
        }
    }

    @NonNull
    private Notification buildNotification(@NonNull Job job, int percent) {
        // The Downloads screen already in the task, not a second copy on top
        // of whatever is showing (SINGLE_TOP alone only reused it on top).
        Intent open = BaseActivity.screenIntent(this, DownloadsActivity.class);
        // Request codes of their own, so FLAG_UPDATE_CURRENT never rewrites
        // another notification's PendingIntent.
        PendingIntent content = PendingIntent.getActivity(this, NotificationID.TASK_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent cancel = new Intent(this, TaskManager.class).setAction(ACTION_CANCEL_CURRENT);
        PendingIntent cancelPending = PendingIntent.getService(this, NotificationID.TASK_ID, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, App.DOWNLOADS_NOTIFICATION_ID)
                .setSmallIcon(iconFor(job.action))
                .setContentTitle(getString(titleFor(job.action)))
                .setContentText(job.notificationText)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .addAction(R.drawable.ic_baseline_close_24, getString(R.string.cancel), cancelPending);
        if (percent >= 0) {
            builder.setProgress(100, percent, false);
        } else {
            builder.setProgress(0, 0, true);
        }
        return builder.build();
    }

    /* The other ongoing notifications' shape: what is happening as the
     * title ("Backing up"), the file under it. The in-app "…, please wait"
     * strings read oddly in the shade, so the notification has its own. */
    @StringRes
    private static int titleFor(String action) {
        if (IntentActions.DOWNLOAD_START_AUDIO_ENCODE.equals(action)) {
            return R.string.task_notification_audio;
        } else if (IntentActions.DOWNLOAD_START_MAKE_GIF.equals(action)) {
            return R.string.task_notification_gif;
        } else if (IntentActions.START_ENCRYPTION.equals(action)) {
            return R.string.task_notification_lock;
        } else if (IntentActions.START_DECRYPTION.equals(action)) {
            return R.string.task_notification_unlock;
        } else if (IntentActions.DOWNLOAD_START_COMPRESS.equals(action)) {
            return R.string.task_notification_compress;
        } else if (IntentActions.DOWNLOAD_START_EXTRACT.equals(action)) {
            return R.string.task_notification_extract;
        }
        return R.string.task_notification_frame;
    }

    /* The file's name, or "3 files". A Safe Folder file's name never goes to
     * the shade or the lock screen, the background-playback rule: the vault's
     * own tasks and a task on a vault file show the count. */
    @Nullable
    private CharSequence notificationTextFor(String action, @NonNull ArrayList<DownloadEntity> entities) {
        int count = entities.size();
        if (count == 0) {
            return null;
        }
        if (count == 1 && !namesArePrivate(action, entities)) {
            String name = entities.get(0).getFileName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return getResources().getQuantityString(R.plurals.downloads_group_files, count, count);
    }

    private static boolean namesArePrivate(String action, @NonNull ArrayList<DownloadEntity> entities) {
        if (IntentActions.START_ENCRYPTION.equals(action)
                || IntentActions.START_DECRYPTION.equals(action)) {
            return true;
        }
        for (DownloadEntity entity : entities) {
            if (entity.isFileSafe()) {
                return true;
            }
        }
        return false;
    }

    @DrawableRes
    private static int iconFor(String action) {
        if (IntentActions.DOWNLOAD_START_AUDIO_ENCODE.equals(action)) {
            return R.drawable.ic_headphones_24;
        } else if (IntentActions.DOWNLOAD_START_MAKE_GIF.equals(action)) {
            return R.drawable.ic_gif_box_24;
        } else if (IntentActions.START_ENCRYPTION.equals(action)) {
            return R.drawable.ic_lock_24;
        } else if (IntentActions.START_DECRYPTION.equals(action)) {
            return R.drawable.ic_lock_open_right_24;
        } else if (IntentActions.DOWNLOAD_START_COMPRESS.equals(action)) {
            return R.drawable.ic_baseline_archive_24;
        } else if (IntentActions.DOWNLOAD_START_EXTRACT.equals(action)) {
            return R.drawable.ic_baseline_unarchive_24;
        }
        return R.drawable.ic_photo_camera_24;
    }

    /* mediaProcessing (Android 15) is the type for transcoding, with its own
     * daily budget beside the downloads' dataSync one; the vault and archive
     * tasks move bytes, which is dataSync. */
    private static int foregroundTypeFor(String action) {
        boolean media = IntentActions.DOWNLOAD_START_AUDIO_ENCODE.equals(action)
                || IntentActions.DOWNLOAD_START_MAKE_GIF.equals(action)
                || IntentActions.DOWNLOAD_START_SAVE_FRAME.equals(action);
        if (media && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING;
        }
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
    }

    /* Worker thread only. */
    private void acquireWakeLock() {
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Firedown:task");
            mWakeLock.setReferenceCounted(false);
        }
        mWakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
    }

    /* Worker thread only. */
    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    /* ── intents ───────────────────────────────────────────────────────── */

    private static boolean isStartAction(@Nullable String action) {
        return IntentActions.DOWNLOAD_START_AUDIO_ENCODE.equals(action)
                || IntentActions.DOWNLOAD_START_MAKE_GIF.equals(action)
                || IntentActions.START_DECRYPTION.equals(action)
                || IntentActions.START_ENCRYPTION.equals(action)
                || IntentActions.DOWNLOAD_START_COMPRESS.equals(action)
                || IntentActions.DOWNLOAD_START_EXTRACT.equals(action)
                || IntentActions.DOWNLOAD_START_SAVE_FRAME.equals(action);
    }

    private static boolean isCancelAction(@Nullable String action) {
        return ACTION_CANCEL_CURRENT.equals(action)
                || IntentActions.DOWNLOAD_CANCEL_AUDIO_ENCODE.equals(action)
                || IntentActions.DOWNLOAD_CANCEL_MAKE_GIF.equals(action)
                || IntentActions.CANCEL_DECRYPTION.equals(action)
                || IntentActions.CANCEL_ENCRYPTION.equals(action)
                || IntentActions.DOWNLOAD_CANCEL_COMPRESS.equals(action)
                || IntentActions.DOWNLOAD_CANCEL_EXTRACT.equals(action)
                || IntentActions.DOWNLOAD_CANCEL_SAVE_FRAME.equals(action);
    }

    /* The notification's Cancel stops whatever runs; a task's own Cancel
     * (its bottom bar, its confirm dialog) only that kind of task. Any cancel
     * used to stop the current task, so a stale one from an earlier task's
     * bar stopped the next. */
    private static boolean cancels(@NonNull String cancelAction, @Nullable Job running) {
        if (ACTION_CANCEL_CURRENT.equals(cancelAction)) {
            return true;
        }
        return running != null && running.action.equals(startActionFor(cancelAction));
    }

    @Nullable
    private static String startActionFor(@NonNull String cancelAction) {
        switch (cancelAction) {
            case IntentActions.DOWNLOAD_CANCEL_AUDIO_ENCODE:
                return IntentActions.DOWNLOAD_START_AUDIO_ENCODE;
            case IntentActions.DOWNLOAD_CANCEL_MAKE_GIF:
                return IntentActions.DOWNLOAD_START_MAKE_GIF;
            case IntentActions.CANCEL_DECRYPTION:
                return IntentActions.START_DECRYPTION;
            case IntentActions.CANCEL_ENCRYPTION:
                return IntentActions.START_ENCRYPTION;
            case IntentActions.DOWNLOAD_CANCEL_COMPRESS:
                return IntentActions.DOWNLOAD_START_COMPRESS;
            case IntentActions.DOWNLOAD_CANCEL_EXTRACT:
                return IntentActions.DOWNLOAD_START_EXTRACT;
            case IntentActions.DOWNLOAD_CANCEL_SAVE_FRAME:
                return IntentActions.DOWNLOAD_START_SAVE_FRAME;
            default:
                return null;
        }
    }

    /* A copy without null entries: every task reads get(0) or walks the
     * list, and an exception on the worker thread ends the process. */
    @NonNull
    private static ArrayList<DownloadEntity> entitiesOf(@NonNull Intent intent) {
        ArrayList<DownloadEntity> entities = intent.getParcelableArrayListExtra(Keys.ITEM_LIST_ID);
        ArrayList<DownloadEntity> copy = new ArrayList<>();
        if (entities != null) {
            for (DownloadEntity entity : entities) {
                if (entity != null) {
                    copy.add(entity);
                }
            }
        }
        return copy;
    }


    public void deliverMessage(TaskEvent taskEvent) {
        mTaskRepository.sendEvent(taskEvent);
    }

    /* The three below are read by the running task, on the worker thread. */

    public ArrayList<DownloadEntity> getQueueList() {
        Job job = sCurrentJob;
        return job != null ? job.entities : new ArrayList<>();
    }

    public GifMakerArgs getGifMakerArgs() {
        Job job = sCurrentJob;
        return job != null ? job.gifMakerArgs : null;
    }

    public long getFramePosMs() {
        Job job = sCurrentJob;
        return job != null ? job.framePosMs : 0L;
    }

}

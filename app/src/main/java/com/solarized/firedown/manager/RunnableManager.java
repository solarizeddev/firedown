package com.solarized.firedown.manager;

import android.Manifest;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.TaskStackBuilder;

import com.solarized.firedown.App;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.R;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.data.repository.TaskRepository;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.VaultActivity;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.CloudBackupNotificationReceiver;
import com.solarized.firedown.utils.DebugLog;
import com.solarized.firedown.utils.NotificationID;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.OkHttpClient;


/**
 * The Android host of the download queue: a {@code dataSync} foreground
 * service that forwards intents to a {@link DownloadEngine} and owns the two
 * things only a Service can — the foreground notification and the
 * download-finished notifications. Everything about the queue itself (the
 * pool, the task lists, add/restart/finish/delete/cancel) lives in the engine;
 * this class is deliberately thin so the host mechanism can change without
 * touching the queue (see {@link DownloadEngine}'s class doc).
 */
@AndroidEntryPoint
public class RunnableManager extends Service implements DownloadEngine.Host {

	private static final String TAG = RunnableManager.class.getName();

	private static volatile boolean isRunning = false;

	private DownloadEngine mEngine;

	private NotificationManagerCompat mNotificationManager;

	/**
	 * Set once the system has withdrawn our foreground allowance (a
	 * {@link #onTimeout} callback, or a refused {@code startForeground}).
	 * From then on {@link #startNotification} must not try to go foreground
	 * again: the quota is spent for the rest of the 24-hour window, so every
	 * further attempt throws {@code ForegroundServiceStartNotAllowedException}
	 * — swapping one crash for another. Volatile: written on the main thread
	 * (onTimeout), read on the engine thread.
	 */
	private volatile boolean mForegroundWithdrawn = false;

	@Inject
	DownloadDataRepository mDownloadRepository; // Injected by Hilt

	@Inject
	TaskRepository mTaskRepository;

	@Inject
	OkHttpClient mOkHttpClient;

	@Inject
	GeckoRuntimeHelper mGeckoRuntimeHelper;

	@Inject
	CloudBackupManager mCloudBackupManager;

	@Override
	public boolean onUnbind(Intent intent) {
		Log.d(TAG, "onUnBind");
		return super.onUnbind(intent);
	}

	@Override
	public IBinder onBind(Intent intent) {
		Log.d(TAG, "onBind");
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		Log.d(TAG, "onCreate");
		mEngine = new DownloadEngine(this, this, mDownloadRepository, mTaskRepository,
				mOkHttpClient, mGeckoRuntimeHelper);
		mNotificationManager = NotificationManagerCompat.from(this);
		isRunning = true;
		// Foreground from the first moment: a started service that does not
		// promote itself promptly is an ANR on API 26+. No tasks yet, so the
		// split is 0/0 and the tap target is Downloads.
		startNotification(0, 0);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(TAG, "onStartCommand");
		if(intent == null || intent.getAction() == null) {
			stopSelf();
			return START_NOT_STICKY;
		}

		mEngine.dispatch(intent, startId);

		// If we get killed, after returning from here, restart
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		Log.d(TAG, "onDestroy");
		mNotificationManager.cancel(NotificationID.RUNNABLE_ID);
		mEngine.cancelAll();
		isRunning = false;
		super.onDestroy();
	}

	public static boolean isRunning(){
		return isRunning;
	}

	// ========================================================================
	// DownloadEngine.Host — invoked on the engine thread
	// ========================================================================

	@Override
	public void onForegroundNeeded(int safeCount, int regularCount) {
		startNotification(safeCount, regularCount);
	}

	@Override
	public void onDownloadFinished(DownloadTask task) {
		startNotificationFinish(task);
	}

	@Override
	public void onIdle() {
		stopForeground(true);
		stopSelf();
	}

	// ========================================================================
	// Foreground-service timeout (Android 15+)
	// ========================================================================

	/**
	 * Android 15+ (API 35) caps a {@code dataSync} foreground service at a
	 * cumulative 6 hours per 24-hour window. When the quota runs out the system
	 * calls this on the MAIN thread and gives the app a few seconds to leave
	 * the foreground; miss that window and the process is killed with
	 * {@code RemoteServiceException$ForegroundServiceDidNotStopInTimeException}
	 * — which is exactly the crash this override exists to stop. A download
	 * queue reaches it two ways: a genuinely long transfer, and a task that
	 * never reported completion (nothing sends MSG_STOP, so the service sits
	 * foreground with an "ongoing" notification indefinitely).
	 *
	 * <p>Not overriding it is NOT a no-op: the base implementation is empty, so
	 * the deadline simply expires.
	 *
	 * <p>The two-argument form is the one the platform calls for a timed-out
	 * FGS type; the one-argument form (API 34) is the {@code shortService}
	 * hook. This service is not a shortService, but the override costs nothing
	 * and keeps the two paths from diverging if the type ever changes.
	 */
	@RequiresApi(api = Build.VERSION_CODES.VANILLA_ICE_CREAM)
	@Override
	public void onTimeout(int startId, int fgsType) {
		DebugLog.d(TAG, "onTimeout startId=" + startId + " fgsType=" + fgsType);
		stopForForegroundLimit();
	}

	@RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
	@Override
	public void onTimeout(int startId) {
		DebugLog.d(TAG, "onTimeout startId=" + startId);
		stopForForegroundLimit();
	}

	/**
	 * Leaves the foreground and stops, after sealing every in-flight download
	 * as a RETRYABLE error. Ordering is the whole point:
	 *
	 * <ol>
	 *   <li>Seal first, synchronously on this thread
	 *       ({@link DownloadEngine#sealTasksAsSystemStopped()}). The seal must
	 *       land BEFORE {@link #onDestroy()} runs the engine's
	 *       {@code cancelAll}, which seals what is left as FINISHED — a partial
	 *       file wearing a FINISHED row is the dishonest outcome, and it is
	 *       unrecoverable (nothing offers to resume a finished download).
	 *       Posting the seal to the engine thread would race that.</li>
	 *   <li>Then drop the foreground state and stop, which is what the system
	 *       is waiting for.</li>
	 * </ol>
	 *
	 * <p>No notification is posted: a stopped download surfaces as an ERROR row
	 * carrying {@code MessageHelper.SYSTEM_TIMEOUT}, the same channel every
	 * other download failure uses (only FINISHED downloads notify).
	 */
	private void stopForForegroundLimit() {
		mForegroundWithdrawn = true;
		mEngine.sealTasksAsSystemStopped();
		try {
			stopForeground(STOP_FOREGROUND_REMOVE);
		} catch (RuntimeException e) {
			DebugLog.d(TAG, "stopForeground failed: " + e);
		}
		stopSelf();
	}

	// ========================================================================
	// Notifications
	// ========================================================================

	private void startNotificationFinish(DownloadTask runnableTask) {
		// Send the notification.
		if (runnableTask.getFileStatus() != Download.FINISHED) {
			Log.w(TAG, "startNotificationFinish with status: " + runnableTask.getFileStatus());
			return;
		}

		if (runnableTask.isFileSafe()) {
			Log.d(TAG, "startNotification skip incognito");
			return;
		}

		String title = runnableTask.getName();
		Intent intent = new Intent(this, DownloadsActivity.class);
		intent.setAction(IntentActions.DOWNLOAD_FINISH);
		// Create the TaskStackBuilder and add the intent, which inflates the back
		// stack.
		TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
		stackBuilder.addNextIntentWithParentStack(intent);
		PendingIntent contentIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this, App.DOWNLOADS_NOTIFICATION_ID);
		mBuilder.setSmallIcon(R.drawable.ic_firedown_notification);  // the status icon
		mBuilder.setAutoCancel(true);
		mBuilder.setWhen(System.currentTimeMillis());  // the time stamp
		mBuilder.setContentTitle(title);  // the label of the entry
		mBuilder.setContentText( getText(R.string.download_finished));  // the label of the entry
		mBuilder.setContentIntent(contentIntent);  // The intent to send when the entry is clicked
		mBuilder.setOngoing(false);
		// The id is minted up front so the "Back up" action can carry it — the
		// receiver cancels this notification once the backup worker (whose own
		// foreground notification takes over as feedback) is enqueued.
		int notificationId = NotificationID.getID();
		// "Back up to cloud" action — the one surface that fires at the moment
		// the user just saved a file they care about. Gated on Cloud Backup
		// being SET UP: a notification action cannot run the first-time setup
		// flow (mint a code + the mandatory "I've saved it" dialog), so a
		// not-set-up user keeps the plain notification and the Downloads-list
		// activation banner remains their pitch. The vault gate is the
		// isFileSafe() early-return above. The receiver re-checks everything
		// against current state — these gates are advisory, the notification
		// can outlive them.
		if (mCloudBackupManager.isSetUp()) {
			Intent backupIntent = new Intent(this, CloudBackupNotificationReceiver.class);
			backupIntent.setAction(CloudBackupNotificationReceiver.ACTION_BACKUP);
			backupIntent.putExtra(CloudBackupNotificationReceiver.EXTRA_DOWNLOAD_ID,
					runnableTask.getFileId());
			backupIntent.putExtra(CloudBackupNotificationReceiver.EXTRA_NOTIFICATION_ID,
					notificationId);
			// Request code = the download's row id, so simultaneous finishes
			// don't collapse to one PendingIntent (extras alone don't
			// distinguish two PendingIntents with equal request codes).
			PendingIntent backupPending = PendingIntent.getBroadcast(
					this, runnableTask.getFileId(), backupIntent,
					PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
			mBuilder.addAction(R.drawable.cloud_outline_24,
					getString(R.string.cloud_backup_action), backupPending);
		}
		if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			// TODO: Consider calling
			//    ActivityCompat#requestPermissions
			// here to request the missing permissions, and then overriding
			//   public void onRequestPermissionsResult(int requestCode, String[] permissions,
			//                                          int[] grantResults)
			// to handle the case where the user grants the permission. See the documentation
			// for ActivityCompat#requestPermissions for more details.
			return;
		}
		mNotificationManager.notify(notificationId, mBuilder.build());
		// Set the info for the views that show in the notification panel.
	}



	private void startNotification(int safeCount, int regularCount) {
		// Route the notification click to whichever Activity actually
		// has content to show: VaultActivity if only incognito-tab
		// (vault) downloads are in flight, DownloadsActivity otherwise.
		// Mixed-mode falls through to DownloadsActivity — the
		// DownloadFragment hint banner surfaces the vault count so the
		// user can hop over. The engine recomputes the split every time
		// it calls in (on add / resume / recycle), so when the last
		// regular download completes leaving only vault ones, the next
		// click correctly opens Vault.
		Class<?> target = (safeCount > 0 && regularCount == 0)
				? VaultActivity.class
				: DownloadsActivity.class;
		// Plain PendingIntent.getActivity instead of TaskStackBuilder.
		// VaultActivity declares no parentActivityName in the manifest,
		// and addNextIntentWithParentStack against a parent-less
		// Activity can produce a PendingIntent that some Android
		// versions reject as having no resolvable target — leaving the
		// foreground notification with a null content intent that
		// suppresses rendering. A direct getActivity sidesteps the
		// resolver, and the FLAG_ACTIVITY_NEW_TASK | CLEAR_TOP combo
		// gets us the same "open the activity, clear above it" behavior
		// the back-stack builder used to provide.
		Intent intent = new Intent(this, target);
		intent.setAction(IntentActions.DOWNLOAD_FINISH);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		PendingIntent contentIntent = PendingIntent.getActivity(
				this, 0, intent,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		// Set the info for the views that show in the notification panel.
		NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this, App.DOWNLOADS_NOTIFICATION_ID);
		mBuilder.setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher_round));
		mBuilder.setSmallIcon(R.drawable.stat_sys_download);  // the status icon
		mBuilder.setWhen(System.currentTimeMillis());  // the time stamp
		mBuilder.setProgress(100, 0, true);
		mBuilder.setContentTitle(getString(R.string.download_ongoing_notification));  // the label of the entry
		mBuilder.setContentIntent(contentIntent);  // The intent to send when the entry is clicked
		mBuilder.setOngoing(true);
		mBuilder.build();
		// Send the notification.
		// We use a string id because it is a unique number.  We use it later to cancel.
		Log.d(TAG, "onCreate startForeground");
		if (mForegroundWithdrawn) {
			// The system already timed this service out of the foreground (see
			// onTimeout): the dataSync quota is spent for the rest of the
			// 24-hour window, so calling startForeground again throws
			// ForegroundServiceStartNotAllowedException. A queued handler
			// message landing after the timeout must not turn one crash into
			// another.
			DebugLog.d(TAG, "startNotification skipped: foreground withdrawn");
			return;
		}
		try {
			startForeground(NotificationID.RUNNABLE_ID, mBuilder.build());
		} catch (RuntimeException e) {
			// ForegroundServiceStartNotAllowedException (API 31+) and its
			// relatives are IllegalStateExceptions the platform throws for
			// reasons outside this service's control — a spent FGS quota, a
			// background start restriction. There is nothing to recover: run
			// the same teardown the timeout runs, so the downloads end as
			// retryable rows instead of the process dying here.
			// DebugLog, not Log.e: the project ships silent release builds. The
			// exception's toString names the class, which is the diagnostic part.
			DebugLog.d(TAG, "startForeground refused: " + e);
			stopForForegroundLimit();
		}

	}

}

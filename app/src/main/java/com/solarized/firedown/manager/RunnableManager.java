package com.solarized.firedown.manager;

import java.io.File;

import android.Manifest;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.TaskStackBuilder;

import com.solarized.firedown.App;
import com.solarized.firedown.R;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.data.repository.TaskRepository;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.NotificationID;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.utils.WebUtils;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.OkHttpClient;


@AndroidEntryPoint
public class RunnableManager extends Service {

	private static final String TAG = RunnableManager.class.getName();


	private static boolean isRunning = false;

	public static final int MSG_ERROR = -1;
	public static final int MSG_STARTED = 1;
	public static final int MSG_FINISH = 2;
	public static final int MSG_CANCEL = 3;
	public static final int MSG_STOP = 4;
	public static final int MSG_START_DOWNLOAD = 5;
	public static final int MSG_RESTART_DOWNLOAD = 6;
	public static final int MSG_FINISH_DOWNLOAD = 7;
	public static final int MSG_DELETE_DOWNLOAD = 8;


	/**
	 * Command to PhoneActivity to send snackbar
	 */

	// Sets the amount of time an idle thread will wait for a task before
	// terminating
	private static final int KEEP_ALIVE_TIME = 30;

	// Sets the Time Unit to seconds
	private static final TimeUnit KEEP_ALIVE_TIME_UNIT = TimeUnit.SECONDS;

	/**
	 * NOTE: This is the number of total available cores. On current versions of
	 * Android, with devices that use plug-and-play cores, this will return less
	 * than the total number of cores. The total number of cores is not
	 * available in current Android implementations.
	 */
	private static final int NUMBER_OF_CORES = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

	private final List<DownloadTask> mActiveTasks = new ArrayList<>();

	private final List<DownloadTask> mQueueTasks = new ArrayList<>();

	private NotificationManagerCompat mNotificationManager;

	private final BlockingQueue<Runnable> mDownloadWorkQueue = new LinkedBlockingQueue<>();

	private final Queue<DownloadTask> mDownloadTaskWorkQueue = new LinkedBlockingQueue<>();

	public final Set<String> mQueuedFileTasks = new LinkedHashSet<>();

	private ServiceHandler serviceHandler;

	@Inject
	DownloadDataRepository mDownloadRepository; // Injected by Hilt

	@Inject
	TaskRepository mTaskRepository;

	@Inject
	OkHttpClient mOkHttpClient;

	// A managed pool of background decoder threads
	private final ThreadPoolExecutor mDownloadThreadPool = new ThreadPoolExecutor(
			NUMBER_OF_CORES, NUMBER_OF_CORES, KEEP_ALIVE_TIME,
			KEEP_ALIVE_TIME_UNIT, mDownloadWorkQueue);


	private final class ServiceHandler extends Handler {
		public ServiceHandler(Looper looper) {
			super(looper);
		}

		@Override
		public void handleMessage(@NonNull Message msg) {

			int action = msg.arg2;

			if(action == MSG_STOP){
				/* The authoritative check is the task lists, not the thread pool's
				 * getActiveCount(). getActiveCount() is an estimate — a download
				 * thread may still be unwinding from its finally block (after
				 * onRunComplete sent MSG_FINISH) when this runs, causing the pool
				 * to report activeCount > 0 even though all logical tasks are done.
				 * This left the foreground notification stuck. */
				if (mActiveTasks.isEmpty() && mQueueTasks.isEmpty()) {
					stopForeground(true);
					stopSelf();
				}
			}else if(action == MSG_STARTED) {
				DownloadTask downloadTask = (DownloadTask) msg.obj;
				addTaskToActive(downloadTask);
				startNotification();
			}else if(action ==  MSG_ERROR || action == MSG_FINISH || action == MSG_CANCEL){
				DownloadTask downloadTask = (DownloadTask) msg.obj;
				startNotificationFinish(downloadTask);
				recycleTask(downloadTask);
			}else if(action == MSG_START_DOWNLOAD){
				Intent intent = (Intent) msg.obj;
				addDownloadToExecutor(intent);
			}else if(action == MSG_RESTART_DOWNLOAD){
				Intent intent = (Intent) msg.obj;
				restartDownloadToExecutor(intent);
			}else if(action == MSG_FINISH_DOWNLOAD){
				Intent intent = (Intent) msg.obj;
				finishDownloadToExecutor(intent);
			}else if(action == MSG_DELETE_DOWNLOAD){
				Intent intent = (Intent) msg.obj;
				addDeleteTaskToExecutor(intent);
			}
		}
	}

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

	public List<DownloadTask> getTasks(){
		List<DownloadTask> tasks = new ArrayList<>();
		tasks.addAll(mActiveTasks);
		tasks.addAll(mQueueTasks);
		return tasks;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		Log.d(TAG, "onCreate");
		// Start up the thread running the service. Note that we create a
		// separate thread because the service normally runs in the process's
		// main thread, which we don't want to block. We also make it
		// background priority so CPU-intensive work doesn't disrupt our UI.
		HandlerThread thread = new HandlerThread("RunnableManagerArguments",
				Process.THREAD_PRIORITY_BACKGROUND);
		thread.start();
		// Get the HandlerThread's Looper and use it for our Handler
		Looper serviceLooper = thread.getLooper();
		serviceHandler = new ServiceHandler(serviceLooper);
		mNotificationManager = NotificationManagerCompat.from(this);
		isRunning = true;
		startNotification();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(TAG, "onStartCommand");
		if(intent == null || intent.getAction() == null) {
			stopSelf();
			return START_NOT_STICKY;
		}


		Message msg = serviceHandler.obtainMessage();
		msg.arg1 = startId;
		msg.obj = intent;

		Log.d(TAG, "Action: "  + intent.getAction());
		//DownloadEntity downloadEntity =  null;
		String mAction = intent.getAction();
		switch (mAction) {
			case IntentActions.DOWNLOAD_START:
				msg.arg2 = MSG_START_DOWNLOAD;
				break;
			case IntentActions.DOWNLOAD_RESTART:
				msg.arg2 = MSG_RESTART_DOWNLOAD;
				break;
			case IntentActions.DOWNLOAD_FINISH:
				msg.arg2 = MSG_FINISH_DOWNLOAD;
				break;
			case IntentActions.DOWNLOAD_DELETE:
				msg.arg2 = MSG_DELETE_DOWNLOAD;
				break;
			default:
				break;
		}

		serviceHandler.sendMessage(msg);

		// If we get killed, after returning from here, restart
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		Log.d(TAG, "onDestroy");
		mNotificationManager.cancel(NotificationID.RUNNABLE_ID);
		cancelAll();
		isRunning = false;
		super.onDestroy();
	}

	public static boolean isRunning(){
		return isRunning;
	}

	private String getStateString(int state){
		return switch (state) {
			case MSG_CANCEL -> "Cancel";
			case MSG_FINISH -> "Finish";
			case MSG_ERROR -> "Error";
			case MSG_STARTED -> "Started";
			default -> String.valueOf(state);
		};
	}


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
		mBuilder.build();
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
		mNotificationManager.notify(NotificationID.getID(), mBuilder.build());
		// Set the info for the views that show in the notification panel.
	}



	private void startNotification( ) {
		// The PendingIntent to launch our activity if the user selects this notification
		Intent intent = new Intent(this, DownloadsActivity.class);
		intent.setAction(IntentActions.DOWNLOAD_FINISH);
		// Create the TaskStackBuilder and add the intent, which inflates the back
		// stack.
		TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
		stackBuilder.addNextIntentWithParentStack(intent);
		PendingIntent contentIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
		startForeground(NotificationID.RUNNABLE_ID, mBuilder.build());

	}

	public void handleState(final DownloadTask task, int state) {
		Log.d(TAG, "Executor Service handleState: " + state + " IN");
		Message msg = serviceHandler.obtainMessage();
		msg.arg2 = state;
		msg.obj = task;
		serviceHandler.sendMessage(msg);
		Log.d(TAG, "Executor Service handleState: " + state + " OUT");
	}

	private String getFilePathForUrl(String mUrl, String mimeType, String mFileName) {
		if(TextUtils.isEmpty(mFileName)){
			mFileName = WebUtils.getFileNameFromURL(mUrl);
		}
		String fileName = TextUtils.isEmpty(mFileName) ? WebUtils.getFileNameFromURL(mUrl) : mFileName;
		fileName = FileUriHelper.decodeName(fileName);
		fileName = FileUriHelper.sanitizeFileName(fileName);
		fileName = FileUriHelper.checkFileExtension(fileName, mimeType);
		File file  = new File(StoragePaths.getDownloadPath(this), fileName);
		return file.getAbsolutePath();
	}

	/**
	 * Checks whether a file path is already claimed by:
	 * 1. A physical file on disk
	 * 2. An active or queued download task
	 * 3. An existing DB record (e.g. an errored download that still owns this path)
	 *
	 * This prevents a new download from reusing a path that an errored download
	 * still references — deleting the errored download would then destroy the
	 * new download's file.
	 *
	 * Must be called from a worker thread — performs synchronous DB I/O.
	 */
	@WorkerThread
	public boolean filePathInTasks(String filepath) {
		return filePathInTasks(filepath, -1);
	}

	/**
	 * @param excludeId entity ID to exclude from the DB check (the current download's own record)
	 */
	@WorkerThread
	public boolean filePathInTasks(String filepath, int excludeId) {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			throw new IllegalStateException("filePathInTasks must not be called on the main thread");
		}
		if (new File(filepath).exists())
			return true;
		synchronized (mQueuedFileTasks) {
			if (mQueuedFileTasks.contains(filepath))
				return true;
		}
		// Check if any existing DB record (e.g. errored download) already owns this path
		DownloadEntity existing = mDownloadRepository.findByFilePath(filepath);
		return existing != null && existing.getId() != excludeId;
	}

	private boolean isExecutorFull(){
		return mDownloadThreadPool.getActiveCount() == mDownloadThreadPool.getCorePoolSize();
	}

	private boolean checkTaskExists(int id) {
		for (DownloadTask task : mActiveTasks) {
			if (id == task.getFileId()) {
				Log.d(TAG, "Task Already in Active Tasks");
				return true;
			}
		}
		for (DownloadTask task : mQueueTasks) {
			if (id == task.getFileId()) {
				Log.d(TAG, "Task Already in Queue Tasks");
				return true;
			}
		}
		return false;
	}



	private void addDeleteTaskToExecutor(Intent intent) {

		Log.d(TAG, "Executor addDeleteTaskToExecutor IN");

		ArrayList<DownloadEntity> downloadEntities = intent.getParcelableArrayListExtra(Keys.ITEM_LIST_ID);

		if (downloadEntities == null || downloadEntities.isEmpty()) {
			Log.w(TAG, "addDeleteTaskToExecutor: no entities");
			return;
		}

		int count = downloadEntities.size();

		// 1. Build lookup maps for O(1) task matching instead of O(n×m) nested loops
		Map<Integer, DownloadTask> activeById = new HashMap<>(mActiveTasks.size());
		for (DownloadTask task : mActiveTasks) {
			activeById.put(task.getFileId(), task);
		}
		Map<Integer, DownloadTask> queuedById = new HashMap<>(mQueueTasks.size());
		for (DownloadTask task : mQueueTasks) {
			queuedById.put(task.getFileId(), task);
		}

		// 2. Cancel active/queued tasks using the maps
		for (DownloadEntity entity : downloadEntities) {
			cancelDownloadTask(entity, activeById, queuedById);
		}

		// 3. Batch-delete from repository; fire TaskEvent on completion
		mDownloadRepository.deleteDownloads(downloadEntities, () ->
				mTaskRepository.sendEvent(new TaskEvent.Deleted(count))
		);

		// 4. Stop service if empty
		Message msg = serviceHandler.obtainMessage();
		msg.arg2 = MSG_STOP;
		serviceHandler.sendMessage(msg);
	}

	/**
	 * Cancels an active or queued task for the given entity using pre-built lookup maps.
	 * Does NOT delete from the repository — the caller handles batch deletion.
	 */
	private void cancelDownloadTask(DownloadEntity entity,
									Map<Integer, DownloadTask> activeById,
									Map<Integer, DownloadTask> queuedById) {

		if (entity == null) {
			Log.w(TAG, "cancelDownloadTask NULL");
			return;
		}

		int id = entity.getId();
		int status = entity.getFileStatus();

		Log.d(TAG, "cancelDownloadTask id: " + id + " status: " + status);

		// Already finished — only needs repo deletion (handled by caller)
		if (status == Download.FINISHED) {
			return;
		}

		// Check active tasks via map
		DownloadTask activeTask = activeById.get(id);
		if (activeTask != null) {
			Log.d(TAG, "cancelDownloadTask stopping active: " + activeTask.getName());
			activeTask.sealWithStatus(Download.ERROR);
			DownloadRunnable runnable = activeTask.getRunnable();
			if (runnable != null) {
				runnable.delete();
				mDownloadThreadPool.remove(runnable);
			}
			Thread downloadThread = activeTask.getCurrentThread();
			if (downloadThread != null) {
				downloadThread.interrupt();
			}
			synchronized (mQueuedFileTasks) {
				mQueuedFileTasks.remove(activeTask.getFilePath());
			}
			return;
		}

		// Check queued tasks via map
		DownloadTask queuedTask = queuedById.get(id);
		if (queuedTask != null) {
			Log.d(TAG, "cancelDownloadTask stopping queued: " + queuedTask.getName());
			queuedTask.sealWithStatus(Download.ERROR);
			DownloadRunnable runnable = queuedTask.getRunnable();
			if (runnable != null) {
				runnable.delete();
				mDownloadThreadPool.remove(runnable);
			}
			handleState(queuedTask, MSG_FINISH);
			synchronized (mQueuedFileTasks) {
				mQueuedFileTasks.remove(queuedTask.getFilePath());
			}
			return;
		}

		// Orphan — not in any task list
		Log.d(TAG, "cancelDownloadTask orphan id: " + id);
		synchronized (mQueuedFileTasks) {
			mQueuedFileTasks.remove(entity.getFilePath());
		}
	}

	private void resumeDownloadTaskToExecutor(DownloadEntity downloadEntity){

		if(downloadEntity == null) {
			Log.w(TAG, "resumeDownloadTaskToExecutor NULL");
			return;
		}

		String filePath = downloadEntity.getFilePath();
		String mUrl = downloadEntity.getFileUrl();
		int id = DownloadTask.generateId();

		Log.d(TAG, "resumeDownloadTaskToExecutor mUrl: " + mUrl + " filePath: " + filePath);
		DownloadTask task = mDownloadTaskWorkQueue.poll();
		if (task == null) {
			task = new DownloadTask(RunnableManager.this, mDownloadRepository, mOkHttpClient);
		}

		Log.d(TAG, "resumeDownloadTaskToExecutor id: " + id);

		if (checkTaskExists(id)) {
			Log.w(TAG, "resumeDownloadTaskToExecutor Task Already Exists");
			return;
		}

		Log.d(TAG, "resumeDownloadTaskToExecutor filePath: " + filePath);

		synchronized (mQueuedFileTasks) {
			mQueuedFileTasks.add(filePath);
		}

		downloadEntity.setFileStatus(Download.PROGRESS);
		downloadEntity.setFilePath(filePath);

		task.resume(downloadEntity);

		if (isExecutorFull()) {
			addTaskToQueue(task);
		} else {
			addTaskToActive(task);
		}

		mDownloadThreadPool.execute(task.getRunnable());


	}

	private void addDownloadRequestToExecutor(DownloadRequest request) {

		if (request == null) {
			Log.w(TAG, "addDownloadRequestToExecutor NULL");
			return;
		}

		String mUrl = request.getUrl();
		String mimeType = request.getMimeType();
		String mFileName = UrlParser.decodeUrl(request.getName());
		String filePath = getFilePathForUrl(mUrl, mimeType, mFileName);

		Log.d(TAG, "addDownloadRequestToExecutor url: " + mUrl + " filePath: " + filePath);

		DownloadTask task = mDownloadTaskWorkQueue.poll();
		if (task == null) {
			task = new DownloadTask(RunnableManager.this, mDownloadRepository, mOkHttpClient);
		}

		int id = DownloadTask.generateId();

		if (checkTaskExists(id)) {
			Log.w(TAG, "addDownloadRequestToExecutor Task Already Exists");
			return;
		}

		synchronized (mQueuedFileTasks) {
			if (filePathInTasks(filePath)) {
				do {
					filePath = UrlParser.parseFilePath(filePath);
				} while (filePathInTasks(filePath));
			}
			mQueuedFileTasks.add(filePath);
		}

		task.initialize(id, request, filePath);

		if (isExecutorFull()) {
			addTaskToQueue(task);
			Log.d(TAG, "addDownloadRequestToExecutor added to Queue: " + task.getName());
		} else {
			addTaskToActive(task);
		}

		mDownloadThreadPool.execute(task.getRunnable());
	}


	private void restartDownloadToExecutor(Intent intent) {

		Log.d(TAG, "Executor addDownloadToExecutor IN");

		if (intent.hasExtra(Keys.ITEM_ID)) {

			DownloadEntity downloadEntity = intent.getParcelableExtra(Keys.ITEM_ID);

			if (downloadEntity == null)
				return;

			resumeDownloadTaskToExecutor(downloadEntity);

		} else if (intent.hasExtra(Keys.ITEM_LIST_ID)) {

			ArrayList<DownloadEntity> downloadEntities = intent.getParcelableArrayListExtra(Keys.ITEM_LIST_ID);

			if (downloadEntities == null)
				return;

			for (DownloadEntity downloadEntity : downloadEntities) {

				resumeDownloadTaskToExecutor(downloadEntity);
			}
		}

		Log.d(TAG, "addDownloadToExecutor count: " + (mActiveTasks.size() + mQueueTasks.size()));

		mTaskRepository.updateCount(mActiveTasks.size() + mQueueTasks.size());

	}

	private void addDownloadToExecutor(Intent intent) {

		Log.d(TAG, "Executor addDownloadToExecutor IN");

		// New path: DownloadRequest (from variant picker or direct download)
		if (intent.hasExtra(Keys.DOWNLOAD_REQUEST)) {
			DownloadRequest request = intent.getParcelableExtra(Keys.DOWNLOAD_REQUEST);
			if (request != null) {
				addDownloadRequestToExecutor(request);
			}
		} else if (intent.hasExtra(Keys.DOWNLOAD_REQUEST_LIST)) {
			ArrayList<DownloadRequest> requests = intent.getParcelableArrayListExtra(Keys.DOWNLOAD_REQUEST_LIST);
			if (requests != null) {
				for (DownloadRequest request : requests) {
					addDownloadRequestToExecutor(request);
				}
			}
		}
		// Legacy path: BrowserDownloadEntity (for non-variant direct downloads until fully migrated)
		else if (intent.hasExtra(Keys.ITEM_ID)) {
			BrowserDownloadEntity entity = intent.getParcelableExtra(Keys.ITEM_ID);
			if (entity != null) {
				addDownloadRequestToExecutor(DownloadRequest.from(entity));
			}
		} else if (intent.hasExtra(Keys.ITEM_LIST_ID)) {
			ArrayList<BrowserDownloadEntity> entities = intent.getParcelableArrayListExtra(Keys.ITEM_LIST_ID);
			if (entities != null) {
				for (BrowserDownloadEntity entity : entities) {
					addDownloadRequestToExecutor(DownloadRequest.from(entity));
				}
			}
		}

		Log.d(TAG, "addDownloadToExecutor count: " + (mActiveTasks.size() + mQueueTasks.size()));
		mTaskRepository.updateCount(mActiveTasks.size() + mQueueTasks.size());
	}


	private void finishDownloadToExecutor(Intent intent) {

		Log.d(TAG, "Executor finishDownloadToExecutor IN");

		DownloadEntity entity = null;

		if (intent.hasExtra(Keys.ITEM_ID)) {
			entity = intent.getParcelableExtra(Keys.ITEM_ID);
		} else if (intent.hasExtra(Keys.ITEM_LIST_ID)) {
			ArrayList<DownloadEntity> downloadEntities = intent.getParcelableArrayListExtra(Keys.ITEM_LIST_ID);
			entity = downloadEntities != null && !downloadEntities.isEmpty() ? downloadEntities.get(0) : null;
		}

		if (entity == null) {
			Log.w(TAG, "finishDownloadToExecutor: no entity");
			return;
		}

		int id = entity.getId();

		// Active tasks
		for (DownloadTask task : mActiveTasks) {
			if (id == task.getFileId()) {
				Log.d(TAG, "finishDownload stopping active: " + id);
				task.sealWithStatus(Download.FINISHED);
				// Don't updateRepository() — the download thread is still running
				// (muxing for SABR/FFmpeg). onRunComplete will do the final DB write
				// after mux completes with correct size and thumbnail.
				// If the app crashes mid-mux, the DB still shows PROGRESS —
				// the user can delete or retry on reopen.
				DownloadRunnable runnable = task.getRunnable();
				if (runnable != null) {
					runnable.stop();
					mDownloadThreadPool.remove(runnable);
				}
				Thread downloadThread = task.getCurrentThread();
				if (downloadThread != null) {
					downloadThread.interrupt();
				}
				synchronized (mQueuedFileTasks) {
					mQueuedFileTasks.remove(task.getFilePath());
				}
				break;
			}
		}

		// Queued tasks — not running, safe to recycle immediately
		for (DownloadTask task : mQueueTasks) {
			if (id == task.getFileId()) {
				Log.d(TAG, "finishDownload stopping queued: " + id);
				task.sealWithStatus(Download.FINISHED);
				task.updateRepository();
				DownloadRunnable runnable = task.getRunnable();
				if (runnable != null) {
					runnable.stop();
					mDownloadThreadPool.remove(runnable);
				}
				synchronized (mQueuedFileTasks) {
					mQueuedFileTasks.remove(task.getFilePath());
				}
				recycleTask(task);
				break;
			}
		}

		// Orphan — no matching task, update entity directly
		if (!isTaskInLists(id)) {
			entity.setFileStatus(Download.FINISHED);
			mDownloadRepository.add(entity);
			synchronized (mQueuedFileTasks) {
				mQueuedFileTasks.remove(entity.getFilePath());
			}
		}

		Log.d(TAG, "Executor finishDownloadToExecutor OUT");
	}


	private void cancelAll() {

		Log.d(TAG, "Executor cancelAll IN");

		for (DownloadTask task : mActiveTasks) {
			Log.d(TAG, "cancelAll active: " + task.getFileId());
			task.sealWithStatus(Download.FINISHED);
			DownloadRunnable runnable = task.getRunnable();
			if (runnable != null) {
				runnable.stop();
				mDownloadThreadPool.remove(runnable);
			}
			synchronized (mQueuedFileTasks) {
				mQueuedFileTasks.remove(task.getFilePath());
			}
		}

		for (DownloadTask task : mQueueTasks) {
			Log.d(TAG, "cancelAll queued: " + task.getFileId());
			task.sealWithStatus(Download.FINISHED);
			DownloadRunnable runnable = task.getRunnable();
			if (runnable != null) {
				runnable.stop();
				mDownloadThreadPool.remove(runnable);
			}
			synchronized (mQueuedFileTasks) {
				mQueuedFileTasks.remove(task.getFilePath());
			}
		}

		// Drain the recycled task pool — any leftover tasks with running threads
		DownloadTask task;
		while ((task = mDownloadTaskWorkQueue.poll()) != null) {
			Thread thread = task.getCurrentThread();
			if (thread != null) {
				DownloadRunnable runnable = task.getRunnable();
				if (runnable != null) {
					runnable.stop();
				}
			}
		}

		Log.d(TAG, "Executor cancelAll OUT");
	}

	private void addTaskToQueue(DownloadTask addtask) {

		Log.w(TAG, "Executor addTaskToQueue IN");
		boolean value = true;

		for (DownloadTask task : mQueueTasks) {
			if (addtask.getFileId() == task.getFileId()) {
				value = false;
				break;
			}
		}
		if (value) {
			Log.d(TAG, "addTask Id: " + addtask.getFileId() + " Name: " + addtask.getName());
			mQueueTasks.add(addtask);
		}

		addtask.setFileStatus(Download.QUEUED);
		addtask.updateRepository();

		synchronized (mQueuedFileTasks){
			mQueuedFileTasks.add(addtask.getFilePath());
		}
		Log.w(TAG, "Executor addTaskToQueue OUT");

	}


	private void addTaskToActive(DownloadTask addtask) {
		Log.w(TAG, "Executor addTaskToActive IN");
		boolean value = true;

		for (DownloadTask task : mActiveTasks) {
			if (addtask.getFileId() == task.getFileId()) {
				value = false;
				break;
			}
		}
		if (value) {
			Log.d(TAG, "addTask Id: " + addtask.getFileId() + " Name: " + addtask.getName());
			mActiveTasks.add(addtask);
		}

		// Update status from QUEUED to PROGRESS so the UI shows the download running
		if (addtask.getFileStatus() == Download.QUEUED) {
			addtask.setFileStatus(Download.PROGRESS);
			addtask.updateRepository();
		}

		synchronized (mQueuedFileTasks){
			mQueuedFileTasks.add(addtask.getFilePath());
		}

		for (DownloadTask task : mQueueTasks) {
			if (addtask.getFileId() == task.getFileId()) {
				mQueueTasks.remove(task);
				break;
			}
		}

		Log.w(TAG, "Executor addTaskToActive OUT");
	}



	public void recycleTask(DownloadTask runnableTask) {
		Log.d(TAG, "recycleTask: " + runnableTask.getFileId() + " name: " + runnableTask.getName());

		// Remove from active/queued lists
		mActiveTasks.removeIf(t -> t.getFileId() == runnableTask.getFileId());
		mQueueTasks.removeIf(t -> t.getFileId() == runnableTask.getFileId());

		synchronized (mQueuedFileTasks) {
			mQueuedFileTasks.remove(runnableTask.getFilePath());
		}

		runnableTask.recycle();
		mDownloadTaskWorkQueue.offer(runnableTask);

		Log.d(TAG, "recycleTask count: " + (mActiveTasks.size() + mQueueTasks.size()));

		mTaskRepository.updateCount(mActiveTasks.size() + mQueueTasks.size());

		// Stop service if empty
		Message message = serviceHandler.obtainMessage();
		message.arg2 = MSG_STOP;
		serviceHandler.sendMessage(message);
	}

	private boolean isTaskInLists(int id) {
		for (DownloadTask task : mActiveTasks) {
			if (task.getFileId() == id) return true;
		}
		for (DownloadTask task : mQueueTasks) {
			if (task.getFileId() == id) return true;
		}
		return false;
	}

}
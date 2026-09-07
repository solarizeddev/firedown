package com.solarized.firedown.manager;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.data.repository.TaskRepository;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.utils.DebugLog;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.MessageHelper;
import com.solarized.firedown.utils.WebUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * The download queue: the thread pool, the active/queued task lists, the
 * {@code MSG_*} state machine and every add/restart/finish/delete/cancel
 * operation. This is the whole of what {@link RunnableManager} used to be
 * minus the two things only an Android {@code Service} can do — go
 * foreground and post notifications — which it reaches through
 * {@link Host}.
 *
 * <p><b>Why it is a separate class.</b> The queue logic was fused to
 * {@code Service} for years, which meant (1) nothing about it could be
 * exercised without an Android process, and (2) the host mechanism —
 * today a {@code dataSync} foreground service, which Android 15 caps at
 * 6 h/day (see {@code RunnableManager.onTimeout}) — could not be changed
 * without rewriting the queue. With the engine extracted, moving to a
 * user-initiated data transfer job on API 34+ (the uncapped mechanism
 * Google added for exactly this) is a new {@link Host} implementation, not
 * a rewrite. The service is now a thin adapter: intents in, notifications
 * out.
 *
 * <p><b>Threading contract.</b> Every mutator runs on ONE thread, the
 * engine's own {@link HandlerThread} ({@code "RunnableManagerArguments"}):
 * callers hand it work through {@link #dispatch(Intent)} /
 * {@link #handleState(DownloadTask, int)}, which post messages, and the
 * {@link Host} callbacks are delivered on that same thread (so
 * {@code startForeground} is called from it, exactly as before the
 * extraction). Two documented exceptions run on the CALLER's
 * thread: {@link #cancelAll()} (service {@code onDestroy}) and
 * {@link #sealTasksAsSystemStopped()} (the FGS timeout), both of which must
 * complete BEFORE the service finishes tearing down and so cannot wait on
 * the handler; each step in them is a field write or a non-blocking flag
 * flip, and the task lists are copy-on-write so their walks are snapshots
 * (see the field doc). {@link #mQueuedFileTasks} is the one structure shared
 * with the download threads ({@code DownloadTask.onFilePathResolved}) and
 * keeps its own monitor.
 */
public class DownloadEngine {

	private static final String TAG = DownloadEngine.class.getName();

	/**
	 * Download half of the filename-provenance trace. Shares the tag with
	 * {@code GeckoInspectTask}'s capture-side logs so the whole chain reads as
	 * one stream: {@code adb logcat -s FileNameTrace:*}.
	 */
	private static final String NAME_TAG = "FileNameTrace";

	public static final int MSG_ERROR = -1;
	public static final int MSG_STARTED = 1;
	public static final int MSG_FINISH = 2;
	public static final int MSG_CANCEL = 3;
	public static final int MSG_STOP = 4;
	public static final int MSG_START_DOWNLOAD = 5;
	public static final int MSG_RESTART_DOWNLOAD = 6;
	public static final int MSG_FINISH_DOWNLOAD = 7;
	public static final int MSG_DELETE_DOWNLOAD = 8;

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

	/**
	 * What the engine needs from whatever hosts it. All three are invoked on
	 * the engine's handler thread.
	 */
	public interface Host {

		/**
		 * Downloads are running; the host must be (or stay) foreground, with
		 * a notification whose tap target follows the split — vault-only
		 * work opens the vault, anything else opens Downloads.
		 */
		void onForegroundNeeded(int safeCount, int regularCount);

		/** A task reached a terminal state. Only FINISHED ones notify. */
		void onDownloadFinished(DownloadTask task);

		/** No active and no queued tasks remain: the host may stop. */
		void onIdle();
	}

	private final Context mContext;
	private final Host mHost;
	private final DownloadDataRepository mDownloadRepository;
	private final TaskRepository mTaskRepository;
	private final OkHttpClient mOkHttpClient;
	private final GeckoRuntimeHelper mGeckoRuntimeHelper;

	/**
	 * Copy-on-write, not ArrayList: the engine thread owns every mutation, but
	 * {@link #cancelAll()} and {@link #sealTasksAsSystemStopped()} iterate
	 * these from the CALLER's thread (see the class doc), and a download
	 * thread unwinding at that instant makes the engine thread
	 * {@code removeIf} the very list being walked — a
	 * ConcurrentModificationException on the {@code onTimeout → stopSelf →
	 * onDestroy} path, caught by {@code scripts/engine-harness} (1 run in 4).
	 * COW iterators are snapshots, and the lists hold a handful of tasks
	 * mutated once per lifecycle event, so the copy is free.
	 */
	private final List<DownloadTask> mActiveTasks = new CopyOnWriteArrayList<>();

	private final List<DownloadTask> mQueueTasks = new CopyOnWriteArrayList<>();

	private final BlockingQueue<Runnable> mDownloadWorkQueue = new LinkedBlockingQueue<>();

	public final Set<String> mQueuedFileTasks = new LinkedHashSet<>();

	private final Handler mHandler;

	// A managed pool of background decoder threads
	private final ThreadPoolExecutor mDownloadThreadPool = new ThreadPoolExecutor(
			NUMBER_OF_CORES, NUMBER_OF_CORES, KEEP_ALIVE_TIME,
			KEEP_ALIVE_TIME_UNIT, mDownloadWorkQueue);

	/**
	 * @param context the APPLICATION context. Tasks read storage paths, the
	 *                default preferences and the gallery publisher through
	 *                it; none of those need an Activity or a Service.
	 */
	public DownloadEngine(@NonNull Context context,
	                      @NonNull Host host,
	                      @NonNull DownloadDataRepository downloadRepository,
	                      @NonNull TaskRepository taskRepository,
	                      @NonNull OkHttpClient okHttpClient,
	                      @NonNull GeckoRuntimeHelper geckoRuntimeHelper) {
		mContext = context.getApplicationContext();
		mHost = host;
		mDownloadRepository = downloadRepository;
		mTaskRepository = taskRepository;
		mOkHttpClient = okHttpClient;
		mGeckoRuntimeHelper = geckoRuntimeHelper;
		// Start up the thread running the engine. A separate thread because
		// the hosting service runs on the process's main thread, which we
		// don't want to block; background priority so CPU-intensive work
		// doesn't disrupt the UI.
		HandlerThread thread = new HandlerThread("RunnableManagerArguments",
				Process.THREAD_PRIORITY_BACKGROUND);
		thread.start();
		// Get the HandlerThread's Looper and use it for our Handler
		Looper looper = thread.getLooper();
		mHandler = new EngineHandler(looper);
	}

	/** The application context tasks and strategies run against. */
	@NonNull
	public Context getContext() {
		return mContext;
	}

	private final class EngineHandler extends Handler {
		public EngineHandler(Looper looper) {
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
					mHost.onIdle();
				}
			}else if(action == MSG_STARTED) {
				DownloadTask downloadTask = (DownloadTask) msg.obj;
				if (downloadTask.getRunnable() == null) {
					// Recycled before its thread got going: a queued task the
					// user deleted/finished at the instant the pool dequeued its
					// runnable (so pool.remove() missed). The runnable sees its
					// stop flag and returns at once (DownloadRunnable.run);
					// re-listing the dead task here would leave a phantom entry
					// that never recycles and blocks onIdle forever.
					Log.d(TAG, "MSG_STARTED for a recycled task, ignored: " + downloadTask.getFileId());
					return;
				}
				addTaskToActive(downloadTask);
				notifyForegroundNeeded();
			}else if(action ==  MSG_ERROR || action == MSG_FINISH || action == MSG_CANCEL){
				DownloadTask downloadTask = (DownloadTask) msg.obj;
				mHost.onDownloadFinished(downloadTask);
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

	/**
	 * Routes a service intent onto the engine thread. Unknown actions post a
	 * message with no matching branch and are ignored there — the same
	 * behaviour the service's {@code onStartCommand} always had.
	 */
	public void dispatch(@NonNull Intent intent, int startId) {
		Message msg = mHandler.obtainMessage();
		msg.arg1 = startId;
		msg.obj = intent;

		Log.d(TAG, "Action: "  + intent.getAction());
		String mAction = intent.getAction();
		if (mAction == null) {
			return;
		}
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

		mHandler.sendMessage(msg);
	}

	public List<DownloadTask> getTasks(){
		List<DownloadTask> tasks = new ArrayList<>();
		tasks.addAll(mActiveTasks);
		tasks.addAll(mQueueTasks);
		return tasks;
	}

	/** Recomputes the vault/regular split and hands it to the host. */
	private void notifyForegroundNeeded() {
		int safeCount = 0;
		int regularCount = 0;
		for (DownloadTask t : mActiveTasks) {
			if (t.isFileSafe()) safeCount++; else regularCount++;
		}
		for (DownloadTask t : mQueueTasks) {
			if (t.isFileSafe()) safeCount++; else regularCount++;
		}
		mHost.onForegroundNeeded(safeCount, regularCount);
	}

	public void handleState(final DownloadTask task, int state) {
		Log.d(TAG, "Engine handleState: " + state + " IN");
		Message msg = mHandler.obtainMessage();
		msg.arg2 = state;
		msg.obj = task;
		mHandler.sendMessage(msg);
		Log.d(TAG, "Engine handleState: " + state + " OUT");
	}

	/**
	 * Seals active and queued tasks as ERROR/{@link MessageHelper#SYSTEM_TIMEOUT}
	 * and unwinds their workers. Mirrors the active branch of
	 * {@link #finishOneDownload(DownloadEntity)} — stop the runnable, drop it
	 * from the pool, interrupt the thread — except that the status written is
	 * an error the user can retry, not a completion.
	 *
	 * <p>Called by the host from the FGS-timeout callback, on the CALLER's
	 * thread, and it must run to completion BEFORE the host calls
	 * {@code stopSelf()}: the service's destroy path runs {@link #cancelAll()},
	 * which seals what is left as FINISHED — for a partial file the dishonest,
	 * unrecoverable outcome. ({@code cancelAll} skips tasks that are already
	 * sealed, so the two compose in that order.)
	 */
	public void sealTasksAsSystemStopped() {
		List<DownloadTask> tasks = getTasks();
		DebugLog.d(TAG, "sealTasksAsSystemStopped count=" + tasks.size());
		for (DownloadTask task : tasks) {
			// Never re-seal (the cancelAll rule): a task the user already
			// Finished (sealed FINISHED, its thread still muxing) or that
			// already errored keeps that deliberate status; the timeout only
			// needs its thread stopped. Found by the stress harness — the
			// unconditional seal stamped ERROR/SYSTEM_TIMEOUT over a
			// user-finished row.
			if (!task.isSealed()) {
				task.sealWithError(MessageHelper.SYSTEM_TIMEOUT);
				task.updateRepository();
			}
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
		}
	}

	private String getFilePathForUrl(String mUrl, String mimeType, String mFileName) {
		// Every step is logged under NAME_TAG: this method is where a good
		// request name can still turn into a bad file name, and which of the
		// four transforms did it is not inferable from the final path alone.
		// Continues the chain GeckoInspectTask starts at capture time.
		DebugLog.d(NAME_TAG, "getFilePathForUrl: in=" + DebugLog.preview(mFileName)
				+ " mime=" + mimeType);
		if(TextUtils.isEmpty(mFileName)){
			mFileName = WebUtils.getFileNameFromURL(mUrl);
		}
		String fileName = TextUtils.isEmpty(mFileName) ? WebUtils.getFileNameFromURL(mUrl) : mFileName;
		DebugLog.d(NAME_TAG, "  fromUrlFallback=" + DebugLog.preview(fileName));
		fileName = FileUriHelper.decodeName(fileName);
		DebugLog.d(NAME_TAG, "  decodeName=" + DebugLog.preview(fileName));
		fileName = FileUriHelper.sanitizeFileName(fileName);
		DebugLog.d(NAME_TAG, "  sanitizeFileName=" + DebugLog.preview(fileName));
		fileName = FileUriHelper.checkFileExtension(fileName, mimeType);
		DebugLog.d(NAME_TAG, "  checkFileExtension=" + DebugLog.preview(fileName));
		File file  = new File(StoragePaths.getDownloadPath(mContext), fileName);
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

		// 2. Cancel active/queued tasks using the maps. A LIVE task (in either
		//    list) is deleted by the engine itself when it recycles — after its
		//    thread has unwound — and its path is released only once that
		//    deletion has landed on the disk executor (see recycleTask). Only
		//    entities NOT in the lists (finished/errored rows, the common
		//    delete) go to the batch below. This is what makes deletion
		//    single-owner: it used to run the batch (row + file, by PATH) at
		//    once AND a second delete from the task's own unwind, with the
		//    path released at intent time — so a new download with the same
		//    name could claim the path in between and lose its file to the
		//    late cleanup (DownloadEngineStressTest: "FINISHED row without a
		//    file"), and a delete landing after the thread had already unwound
		//    left the path reserved forever (the JVM stress harness).
		ArrayList<DownloadEntity> orphans = new ArrayList<>();
		for (DownloadEntity entity : downloadEntities) {
			if (cancelDownloadTask(entity, activeById, queuedById)) {
				orphans.add(entity);
			}
		}

		// 3. Batch-delete the orphans from the repository; fire TaskEvent on
		//    completion. needGrant = entities whose foreign restored FILE
		//    couldn't be deleted without a SAF write grant (rows kept).
		//    Prefer the grant prompt over the "deleted" toast when any are
		//    pending — the SingleLiveEvent would coalesce two posts to the
		//    last one anyway, and the prompt is the actionable signal.
		mDownloadRepository.deleteDownloads(orphans, needGrant -> {
			synchronized (mQueuedFileTasks) {
				for (DownloadEntity orphan : orphans) {
					mQueuedFileTasks.remove(orphan.getFilePath());
				}
			}
			if (!needGrant.isEmpty()) {
				mTaskRepository.sendEvent(new TaskEvent.NeedsDeleteGrant(needGrant));
			} else if (count > 0) {
				mTaskRepository.sendEvent(new TaskEvent.Deleted(count));
			}
		});

		// 4. Stop service if empty
		Message msg = mHandler.obtainMessage();
		msg.arg2 = MSG_STOP;
		mHandler.sendMessage(msg);
	}

	/**
	 * Cancels an active or queued task for the given entity using pre-built
	 * lookup maps. A live task's row and file are deleted by
	 * {@link #recycleTask} once its thread has unwound (its path stays
	 * reserved until then — see addDeleteTaskToExecutor for the race this
	 * closes); nothing is deleted here.
	 *
	 * @return true if the entity is NOT a live task (an orphan) and the caller
	 *         must delete it through the batch.
	 */
	private boolean cancelDownloadTask(DownloadEntity entity,
									   Map<Integer, DownloadTask> activeById,
									   Map<Integer, DownloadTask> queuedById) {

		if (entity == null) {
			Log.w(TAG, "cancelDownloadTask NULL");
			return false;
		}

		int id = entity.getId();
		int status = entity.getFileStatus();

		Log.d(TAG, "cancelDownloadTask id: " + id + " status: " + status);

		// Check active tasks via map
		DownloadTask activeTask = activeById.get(id);
		if (activeTask != null) {
			Log.d(TAG, "cancelDownloadTask stopping active: " + activeTask.getName());
			activeTask.sealWithStatus(Download.ERROR);
			boolean neverRan = false;
			DownloadRunnable runnable = activeTask.getRunnable();
			if (runnable != null) {
				runnable.delete();
				neverRan = mDownloadThreadPool.remove(runnable);
			}
			Thread downloadThread = activeTask.getCurrentThread();
			if (downloadThread != null) {
				downloadThread.interrupt();
			}
			if (neverRan) {
				// "Active" only by the submit-time estimate: the pool had not
				// picked the runnable up yet, and remove() just guaranteed it
				// never will — so no thread will ever unwind and send
				// MSG_FINISH for it. Recycle it here (the queued branch below
				// always did) or it stays in mActiveTasks forever: MSG_STOP
				// never sees empty lists, the service never stops, the
				// foreground notification never clears — and on Android 15 the
				// dataSync timeout kills the process 6 h later. Found by
				// scripts/engine-harness stress (rows stuck PROGRESS with a
				// task left in the list after every thread had unwound).
				handleState(activeTask, MSG_FINISH);
			}
			return false;
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
			return false;
		}

		// Orphan — not in any task list: a finished/errored row, the batch's job
		Log.d(TAG, "cancelDownloadTask orphan id: " + id);
		return true;
	}

	private void resumeDownloadTaskToExecutor(DownloadEntity downloadEntity){

		if(downloadEntity == null) {
			Log.w(TAG, "resumeDownloadTaskToExecutor NULL");
			return;
		}

		String filePath = downloadEntity.getFilePath();
		String mUrl = downloadEntity.getFileUrl();
		// The row keeps ITS OWN id through a resume (task.resume parses the
		// entity), so the duplicate guard must test that id. It used to test a
		// freshly generated one, which can never be in the lists — so a
		// restart tapped while the errored task was still listed (sealed, its
		// thread not yet unwound, MSG_ERROR not yet recycled) ran the same id
		// twice: two tasks on one row, and the first recycle's removeIf then
		// dropped both entries while the second thread kept running unlisted.
		// Found by scripts/engine-harness stress ("task listed twice").
		int id = downloadEntity.getId();

		Log.d(TAG, "resumeDownloadTaskToExecutor mUrl: " + mUrl + " filePath: " + filePath);

		if (checkTaskExists(id)) {
			Log.w(TAG, "resumeDownloadTaskToExecutor Task Already Exists: " + id);
			return;
		}

		// A FRESH task per download — never a pooled one (see recycleTask).
		DownloadTask task = new DownloadTask(DownloadEngine.this, mDownloadRepository, mOkHttpClient, mGeckoRuntimeHelper.getPoTokenGenerator());

		Log.d(TAG, "resumeDownloadTaskToExecutor id: " + id);

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
		String requestName = request.getName();
		String mFileName = UrlParser.decodeUrl(requestName);
		DebugLog.d(NAME_TAG, "request: name=" + DebugLog.preview(requestName)
				+ " decodeUrl=" + DebugLog.preview(mFileName)
				+ " forced=" + request.isFileNameForced());
		String filePath = getFilePathForUrl(mUrl, mimeType, mFileName);

		Log.d(TAG, "addDownloadRequestToExecutor url: " + mUrl + " filePath: " + filePath);

		// A FRESH task per download — never a pooled one (see recycleTask).
		DownloadTask task = new DownloadTask(DownloadEngine.this, mDownloadRepository, mOkHttpClient, mGeckoRuntimeHelper.getPoTokenGenerator());

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

		publishTaskCounts();

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
		publishTaskCounts();
	}


	private void finishDownloadToExecutor(Intent intent) {

		Log.d(TAG, "Executor finishDownloadToExecutor IN");

		ArrayList<DownloadEntity> entities = new ArrayList<>();

		if (intent.hasExtra(Keys.ITEM_ID)) {
			DownloadEntity entity = intent.getParcelableExtra(Keys.ITEM_ID);
			if (entity != null) {
				entities.add(entity);
			}
		} else if (intent.hasExtra(Keys.ITEM_LIST_ID)) {
			ArrayList<DownloadEntity> downloadEntities = intent.getParcelableArrayListExtra(Keys.ITEM_LIST_ID);
			if (downloadEntities != null) {
				entities.addAll(downloadEntities);
			}
		}

		if (entities.isEmpty()) {
			Log.w(TAG, "finishDownloadToExecutor: no entity");
			return;
		}

		// Finish EVERY entity in the intent — the list form is a multi-select,
		// and the old code took only get(0), silently leaving the rest
		// downloading.
		for (DownloadEntity entity : entities) {
			if (entity != null) {
				finishOneDownload(entity);
			}
		}

		Log.d(TAG, "Executor finishDownloadToExecutor OUT");
	}


	/**
	 * User Finish on a download that never wrote a byte (queued, or "active"
	 * only by the submit-time estimate). There is nothing to keep, so it does
	 * NOT become a FINISHED row: that row would point at a file that does not
	 * exist, which the missing-file sweep later flips to ERROR/FILE_NOT_FOUND
	 * anyway (on-device: DownloadEngineStressTest's "FINISHED row without a
	 * file"). Say so now — the row stays retryable (restart resumes from 0).
	 * The UI offers Finish on PROGRESS rows only, so this is the estimate
	 * window and a resumed row that has not started yet, not a common path.
	 */
	private void finishNeverRan(DownloadTask task) {
		task.sealWithError(MessageHelper.FILE_NOT_FOUND);
		task.updateRepository();
		DownloadRunnable runnable = task.getRunnable();
		if (runnable != null) {
			runnable.stop();
			mDownloadThreadPool.remove(runnable);
		}
		recycleTask(task);
	}

	private void finishOneDownload(DownloadEntity entity) {

		int id = entity.getId();
		boolean matched = false;

		// Active tasks
		DownloadTask neverRanActive = null;
		for (DownloadTask task : mActiveTasks) {
			if (id == task.getFileId()) {
				Log.d(TAG, "finishDownload stopping active: " + id);
				matched = true;
				if (task.isSealed()) {
					// Already terminal (errored, timed out, deleted, or finished
					// once already) and its runnable already stopped: a Finish
					// must not stamp FINISHED over that status.
					Log.d(TAG, "finishDownload: already sealed, ignored: " + id);
					break;
				}
				task.sealWithStatus(Download.FINISHED);
				// Don't updateRepository() — the download thread is still running
				// (muxing for SABR/FFmpeg). onRunComplete will do the final DB write
				// after mux completes with correct size and thumbnail.
				// If the app crashes mid-mux, the DB still shows PROGRESS —
				// the user can delete or retry on reopen.
				boolean neverRan = false;
				DownloadRunnable runnable = task.getRunnable();
				if (runnable != null) {
					runnable.stop();
					neverRan = mDownloadThreadPool.remove(runnable);
				}
				Thread downloadThread = task.getCurrentThread();
				if (downloadThread != null) {
					downloadThread.interrupt();
				}
				synchronized (mQueuedFileTasks) {
					mQueuedFileTasks.remove(task.getFilePath());
				}
				if (neverRan) {
					// Same phantom as cancelDownloadTask's active branch: the
					// pool never picked this one up and now never will, so the
					// "onRunComplete will do the final write" above is a write
					// that never comes. Handle it exactly like the queued case.
					neverRanActive = task;
				}
				break;
			}
		}
		if (neverRanActive != null) {
			finishNeverRan(neverRanActive);
		}

		// Queued tasks — not running, safe to recycle immediately. Find first,
		// act after: recycleTask mutates mQueueTasks, so acting inside the
		// iteration only worked because of the immediate break.
		DownloadTask queuedTask = null;
		for (DownloadTask task : mQueueTasks) {
			if (id == task.getFileId()) {
				queuedTask = task;
				break;
			}
		}
		if (queuedTask != null) {
			Log.d(TAG, "finishDownload stopping queued: " + id);
			matched = true;
			if (!queuedTask.isSealed()) {
				finishNeverRan(queuedTask);
			}
		}

		// Orphan — no matching task, update entity directly. Gated on the
		// match flag, not isTaskInLists(): a just-recycled queued task is
		// already out of the lists, and re-adding the intent's parcel copy
		// here would overwrite the task entity's fresher fields.
		if (!matched) {
			entity.setFileStatus(Download.FINISHED);
			mDownloadRepository.add(entity);
			synchronized (mQueuedFileTasks) {
				mQueuedFileTasks.remove(entity.getFilePath());
			}
		}
	}


	/**
	 * Host teardown ({@code Service.onDestroy}). Runs on the CALLER's thread
	 * — see the class doc's threading contract for why.
	 */
	public void cancelAll() {

		Log.d(TAG, "Executor cancelAll IN");

		for (DownloadTask task : mActiveTasks) {
			Log.d(TAG, "cancelAll active: " + task.getFileId());
			// Never re-seal: a task already sealed carries a terminal status
			// somebody decided deliberately — the system-timeout path seals
			// ERROR so the partial file stays retryable, and blindly stamping
			// FINISHED over it here (onDestroy runs right after stopSelf)
			// would strand a half file as a completed download.
			if (!task.isSealed()) {
				task.sealWithStatus(Download.FINISHED);
			}
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
			if (!task.isSealed()) {
				task.sealWithStatus(Download.FINISHED);
			}
			DownloadRunnable runnable = task.getRunnable();
			if (runnable != null) {
				runnable.stop();
				mDownloadThreadPool.remove(runnable);
			}
			synchronized (mQueuedFileTasks) {
				mQueuedFileTasks.remove(task.getFilePath());
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

		// A DELETED task is deleted HERE — row and file, once, by the engine,
		// after its thread has unwound (recycle is reached through MSG_FINISH,
		// or directly for one that never ran) — and its path is released only
		// when that deletion has landed on the disk executor, so a successor
		// with the same name can neither claim the path early nor lose its
		// file to this cleanup. Everything else releases its path right away.
		DownloadRunnable recycledRunnable = runnableTask.getRunnable();
		boolean deleted = recycledRunnable != null && recycledRunnable.isDeleted();
		final String path = runnableTask.getFilePath();
		if (deleted) {
			runnableTask.deleteRepository(() -> {
				synchronized (mQueuedFileTasks) {
					mQueuedFileTasks.remove(path);
				}
			});
		} else {
			synchronized (mQueuedFileTasks) {
				mQueuedFileTasks.remove(path);
			}
		}

		// recycle() seals the task and drops its runnable; the object is then
		// simply dropped. It used to be pooled and REUSED for the next download
		// (initialize() on the same object), which is a race by construction:
		// a runnable of the OLD download can still be unwinding — or, for a
		// queued one the pool had just dequeued when delete/finish called
		// pool.remove(), only just STARTING — and every callback it makes
		// (onStarted → MSG_STARTED, onRunComplete → MSG_FINISH) lands on a
		// task object that now belongs to a DIFFERENT download: the new row
		// got listed twice, or recycled before it ran. Found by
		// scripts/engine-harness stress. A task is an entity plus a few
		// atomics; allocating one per download costs nothing.
		runnableTask.recycle();

		Log.d(TAG, "recycleTask count: " + (mActiveTasks.size() + mQueueTasks.size()));

		publishTaskCounts();

		// Stop service if empty
		Message message = mHandler.obtainMessage();
		message.arg2 = MSG_STOP;
		mHandler.sendMessage(message);
	}

	/**
	 * Walk active+queued task lists and bucket-count by vault status,
	 * then push the split to {@link TaskRepository#updateCount(int, int)}.
	 * Separates the bottom-bar badge for regular vs incognito browsing
	 * so an incognito-tab download doesn't surface in the regular
	 * BrowserFragment / HomeFragment chrome (privacy: don't advertise
	 * private downloads in the public UI).
	 */
	private void publishTaskCounts() {
		int safe = 0;
		int regular = 0;
		for (DownloadTask t : mActiveTasks) {
			if (t.isFileSafe()) safe++; else regular++;
		}
		for (DownloadTask t : mQueueTasks) {
			if (t.isFileSafe()) safe++; else regular++;
		}
		mTaskRepository.updateCount(regular, safe);

		// Re-route the foreground notification's click PendingIntent
		// to whichever Activity actually has content. Without this, the
		// "regular finishes, only vault remains" case would still open
		// the empty DownloadsActivity because the PendingIntent set
		// when the regular task started is stale. The host rebuilds via
		// startForeground(same id), which just replaces the contentIntent —
		// no flicker, no duplicate notification.
		if (safe > 0 || regular > 0) {
			mHost.onForegroundNeeded(safe, regular);
		}
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

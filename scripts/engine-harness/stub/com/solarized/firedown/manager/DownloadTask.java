package com.solarized.firedown.manager;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.geckoview.PoTokenGenerator;
import com.solarized.firedown.utils.MessageHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

/**
 * Collaborator stub for the REAL DownloadTask. The real class drags in every
 * strategy (SABR, FFmpeg, Deezer...) and Room, so it cannot compile on a plain
 * JVM; this mirrors only the CONTRACT the engine relies on, copied from the
 * real class where the semantics matter:
 * <ul>
 *   <li>the {@code sealed} latch — once sealed, no strategy callback may move
 *       the status (onError/onFinished return early), and
 *       {@code sealWithStatus}/{@code sealWithError}/{@code recycle} set it;</li>
 *   <li>{@code terminalMessageSent} — MSG_ERROR from onError and MSG_FINISH
 *       from onRunComplete never both reach the engine for one run;</li>
 *   <li>the lifecycle messages: MSG_STARTED from the download thread's first
 *       breath, MSG_FINISH from its finally block.</li>
 * </ul>
 * Everything else (filename resolution, strategies, metadata refresh) is out
 * of scope — the queue is under test, not the transfer.
 */
public class DownloadTask {
    private static final AtomicInteger IDS = new AtomicInteger(5000);
    public static final List<DownloadTask> ALL = Collections.synchronizedList(new ArrayList<>());

    private final DownloadEngine engine;
    private final DownloadDataRepository repository;
    private final DownloadEntity entity = new DownloadEntity();
    private final AtomicBoolean sealed = new AtomicBoolean(false);
    private final AtomicBoolean terminalMessageSent = new AtomicBoolean(false);
    private DownloadRunnable runnable;
    private volatile Thread currentThread;
    /** How many times this task object was (re)used — the engine recycles tasks. */
    public int initializations;

    public DownloadTask(DownloadEngine engine, DownloadDataRepository repository,
                        OkHttpClient okHttpClient, PoTokenGenerator poTokenGenerator) {
        this.engine = engine;
        this.repository = repository;
        ALL.add(this);
    }

    public static int generateId() { return IDS.incrementAndGet(); }

    public void initialize(int id, DownloadRequest request, String filePath) {
        synchronized (engine) {
            initializations++;
            sealed.set(false);
            terminalMessageSent.set(false);
            entity.setId(id);
            entity.setFileUrl(request.getUrl());
            entity.setFileName(filePath.substring(filePath.lastIndexOf('/') + 1));
            entity.setFilePath(filePath);
            entity.setFileMimeType(request.getMimeType() != null ? request.getMimeType() : "");
            entity.setFileProgress(0);
            entity.setFileErrorType(0);
            entity.setFileStatus(Download.PROGRESS);
            entity.setFileSafe(request.isSaveToVault());
            runnable = new DownloadRunnable(this);
            repository.add(entity);
        }
    }

    public void resume(DownloadEntity existing) {
        initializations++;
        sealed.set(false);
        terminalMessageSent.set(false);
        entity.parseDownload(existing);
        runnable = new DownloadRunnable(this);
        repository.add(entity);
    }

    // ---- what the download thread reports (DownloadCallback in the real class)

    void onStarted() {
        currentThread = Thread.currentThread();
        engine.handleState(this, DownloadEngine.MSG_STARTED);
    }

    void onFinished() {
        if (sealed.get()) return;
        entity.setFileStatus(Download.FINISHED);
        entity.setFileProgress(100);
    }

    /** A strategy failure, as the real onError: seals, writes, sends MSG_ERROR once. */
    public void onError(int errorType) {
        if (sealed.get()) return;
        sealed.set(true);
        terminalMessageSent.set(true);
        entity.setFileStatus(Download.ERROR);
        entity.setFileErrorType(errorType);
        repository.add(entity);
        engine.handleState(this, DownloadEngine.MSG_ERROR);
    }

    void onRunComplete() {
        if (runnable != null && runnable.isDeleted()) {
            repository.deleteDownload(entity);
        } else {
            repository.add(entity);
        }
        if (!terminalMessageSent.getAndSet(true)) {
            engine.handleState(this, DownloadEngine.MSG_FINISH);
        }
    }

    // ---- task lifecycle, called by the engine

    public DownloadRunnable getRunnable() { return runnable; }
    public int getFileId() { return entity.getId(); }
    public String getName() { return entity.getFileName(); }
    public String getFilePath() { return entity.getFilePath(); }
    public int getFileStatus() { return entity.getFileStatus(); }
    public int getFileErrorType() { return entity.getFileErrorType(); }
    public boolean isFileSafe() { return entity.isFileSafe(); }
    public void setFileStatus(int status) { entity.setFileStatus(status); }
    public void sealWithStatus(int status) { sealed.set(true); entity.setFileStatus(status); }
    public void sealWithError(int errorType) {
        sealed.set(true);
        entity.setFileStatus(Download.ERROR);
        entity.setFileErrorType(errorType);
    }
    public boolean isSealed() { return sealed.get(); }
    public Thread getCurrentThread() { return currentThread; }
    public void deleteRepository() { repository.deleteDownload(entity); }
    public void updateRepository() { repository.add(entity); }
    public void recycle() {
        sealed.set(true);
        terminalMessageSent.set(true);
        if (runnable != null) runnable.stop();
        runnable = null;
        currentThread = null;
    }
    /** Harness-only: the entity as the task sees it (not a repository snapshot). */
    public DownloadEntity entity() { return entity; }
}

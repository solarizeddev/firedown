package com.solarized.firedown.harness;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;

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
import com.solarized.firedown.manager.DownloadEngine;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.manager.DownloadRunnable;
import com.solarized.firedown.manager.DownloadTask;
import com.solarized.firedown.utils.MessageHelper;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * Drives the REAL DownloadEngine through its intent surface and observes it
 * through the Host callbacks, the recording repositories and the scripted
 * download runnables. See run.sh for the case list.
 */
public class EngineHarness {
    static int pass = 0, fail = 0;
    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("PASS  " + n + (d.isEmpty() ? "" : "  [" + d + "]")); }
        else    { fail++; System.out.println("FAIL  " + n + "  [" + d + "]"); }
    }

    /** Pool size the engine derives — the queue tests are written against it. */
    static final int POOL = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    static final long WAIT_MS = 4000;

    /** Recording host: every callback becomes one line, in delivery order. */
    static final class Host implements DownloadEngine.Host {
        final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        final List<String> all = new ArrayList<>();
        volatile Thread callbackThread;
        private void emit(String s) { callbackThread = Thread.currentThread(); all.add(s); events.add(s); }
        @Override public void onForegroundNeeded(int safe, int regular) { emit("fg:" + safe + "/" + regular); }
        @Override public void onDownloadFinished(DownloadTask task) { emit("finished:" + task.getFileId() + ":" + task.getFileStatus()); }
        @Override public void onIdle() { emit("idle"); }

        /** Next event whose text starts with {@code prefix}, discarding others; null on timeout. */
        String await(String prefix) throws InterruptedException {
            long deadline = System.currentTimeMillis() + WAIT_MS;
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return null;
                String e = events.poll(left, TimeUnit.MILLISECONDS);
                if (e == null) return null;
                if (e.startsWith(prefix)) return e;
            }
        }
        /** Drain for a quiet period; returns what arrived. */
        List<String> settle() throws InterruptedException {
            List<String> got = new ArrayList<>();
            String e;
            while ((e = events.poll(300, TimeUnit.MILLISECONDS)) != null) got.add(e);
            return got;
        }
        int count(String prefix) { int c = 0; for (String s : all) if (s.startsWith(prefix)) c++; return c; }
    }

    static Host host;
    static DownloadDataRepository repo;
    static TaskRepository taskRepo;
    static DownloadEngine engine;
    static Thread engineThread;

    static void freshEngine() {
        host = new Host();
        repo = new DownloadDataRepository();
        taskRepo = new TaskRepository();
        if (engine != null) {
            engine.shutdown(); // the service's onDestroy contract, per instance
        }
        engine = new DownloadEngine(new Context(), host, repo, taskRepo, new OkHttpClient(), new GeckoRuntimeHelper());
    }

    static Intent start(String url, String name, boolean vault) {
        Intent i = new Intent(IntentActions.DOWNLOAD_START);
        i.putExtra(Keys.DOWNLOAD_REQUEST, new DownloadRequest(url, name, "video/mp4", vault));
        return i;
    }
    static Intent legacyStart(String url, String name) {
        Intent i = new Intent(IntentActions.DOWNLOAD_START);
        i.putExtra(Keys.ITEM_ID, new BrowserDownloadEntity(url, name, "video/mp4"));
        return i;
    }
    static Intent withEntity(String action, DownloadEntity e) {
        Intent i = new Intent(action);
        i.putExtra(Keys.ITEM_ID, e);
        return i;
    }
    static Intent withEntities(String action, List<DownloadEntity> es) {
        Intent i = new Intent(action);
        i.putExtra(Keys.ITEM_LIST_ID, new ArrayList<>(es));
        return i;
    }

    /** Dispatches a start and waits for the download thread's MSG_STARTED to land. */
    static DownloadTask startAndWait(String url, String name, boolean vault) throws InterruptedException {
        int before = DownloadTask.ALL.size();
        int runnablesBefore = DownloadRunnable.ALL.size();
        engine.dispatch(start(url, name, vault), 1);
        // The task object may be a recycled one, so find it by the runnable it created.
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (DownloadRunnable.ALL.size() == runnablesBefore && System.currentTimeMillis() < deadline) Thread.sleep(5);
        DownloadRunnable r = DownloadRunnable.ALL.get(DownloadRunnable.ALL.size() - 1);
        deadline = System.currentTimeMillis() + WAIT_MS;
        while (!r.started && System.currentTimeMillis() < deadline) Thread.sleep(5);
        // and the engine to have processed MSG_STARTED (it emits fg: on it)
        host.await("fg:");
        return taskOf(r);
    }
    static DownloadTask taskOf(DownloadRunnable r) {
        synchronized (DownloadTask.ALL) {
            for (DownloadTask t : DownloadTask.ALL) if (t.getRunnable() == r) return t;
        }
        return null;
    }
    static DownloadRunnable lastRunnable() { return DownloadRunnable.ALL.get(DownloadRunnable.ALL.size() - 1); }
    static boolean inLists(int id) { for (DownloadTask t : engine.getTasks()) if (t.getFileId() == id) return true; return false; }
    static boolean waitUntil(java.util.function.BooleanSupplier c) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (!c.getAsBoolean()) { if (System.currentTimeMillis() > deadline) return false; Thread.sleep(5); }
        return true;
    }

    public static void main(String[] a) {
        // A harness exception must be a reported failure, never a hang: the
        // engine's pool threads are non-daemon, so a main() that dies with a
        // blocked download thread still alive would keep the JVM up forever.
        try {
            run();
        } catch (Throwable t) {
            System.out.println("ABORT  " + t);
            t.printStackTrace(System.out);
            fail++;
        }
        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        File dir = Files.createTempDirectory("engine-harness").toFile();
        StoragePaths.downloadDir = dir.getAbsolutePath();
        System.out.println("pool size " + POOL + " (cores/2)");

        // ── 1. one download: start → active → foreground; natural finish → idle ─
        freshEngine();
        DownloadTask t1 = startAndWait("https://cdn.example/a/clip.mp4", "clip.mp4", false);
        check("1a start lands as an active PROGRESS row",
                t1 != null && repo.latest(t1.getFileId()) != null
                        && repo.latest(t1.getFileId()).getFileStatus() == Download.PROGRESS && inLists(t1.getFileId()),
                "id=" + (t1 == null ? "?" : t1.getFileId()));
        check("1b host asked to go foreground with the regular split",
                host.count("fg:0/1") >= 1, "events=" + host.all);
        check("1c host callbacks arrive on the engine thread, never the caller's",
                host.callbackThread != null && host.callbackThread != Thread.currentThread()
                        && "RunnableManagerArguments".equals(host.callbackThread.getName()),
                String.valueOf(host.callbackThread));
        check("1d TaskRepository badge count published (regular=1, safe=0)",
                taskRepo.lastCount() != null && taskRepo.lastCount().regular == 1 && taskRepo.lastCount().safe == 0, "");
        check("1e path registered in the queued-path set",
                engine.mQueuedFileTasks.contains(t1.getFilePath()), t1.getFilePath());
        DownloadRunnable r1 = t1.getRunnable();
        r1.finishNaturally();
        String fin = host.await("finished:");
        check("1f natural finish → onDownloadFinished with FINISHED",
                fin != null && fin.equals("finished:" + t1.getFileId() + ":" + Download.FINISHED), String.valueOf(fin));
        String idle = host.await("idle");
        check("1g … then onIdle once the lists are empty",
                idle != null && engine.getTasks().isEmpty(), "tasks=" + engine.getTasks().size());
        check("1h repository history reads PROGRESS … FINISHED and the path is released",
                repo.statuses(t1.getFileId()).get(0) == Download.PROGRESS
                        && repo.latest(t1.getFileId()).getFileStatus() == Download.FINISHED
                        && !engine.mQueuedFileTasks.contains(t1.getFilePath()),
                repo.statuses(t1.getFileId()).toString());

        // ── 2. the pool bound: POOL active, the next one QUEUED, promoted on a finish ─
        freshEngine();
        List<DownloadTask> active = new ArrayList<>();
        for (int i = 0; i < POOL; i++) active.add(startAndWait("https://cdn.example/q/" + i + ".mp4", "q" + i + ".mp4", false));
        int runnablesBefore = DownloadRunnable.ALL.size();
        engine.dispatch(start("https://cdn.example/q/extra.mp4", "extra.mp4", false), 1);
        waitUntil(() -> DownloadRunnable.ALL.size() > runnablesBefore);
        DownloadRunnable extraR = lastRunnable();
        DownloadTask extra = taskOf(extraR);
        boolean queued = waitUntil(() -> extra != null && repo.latest(extra.getFileId()) != null
                && repo.latest(extra.getFileId()).getFileStatus() == Download.QUEUED);
        check("2a the (POOL+1)th download is written QUEUED, not PROGRESS",
                queued && !extraR.started, "status=" + (extra == null ? "?" : repo.latest(extra.getFileId())));
        host.settle();
        check("2b badge count = POOL+1 while it waits",
                taskRepo.lastCount().regular == POOL + 1, "regular=" + taskRepo.lastCount().regular);
        active.get(0).getRunnable().finishNaturally();
        boolean promoted = waitUntil(() -> extraR.started
                && repo.latest(extra.getFileId()).getFileStatus() == Download.PROGRESS);
        check("2c a finish frees a slot: the queued task starts and is re-written PROGRESS",
                promoted, repo.statuses(extra.getFileId()).toString());
        check("2d QUEUED → PROGRESS is the recorded transition",
                repo.statuses(extra.getFileId()).contains(Download.QUEUED)
                        && repo.latest(extra.getFileId()).getFileStatus() == Download.PROGRESS, "");
        for (int i = 1; i < POOL; i++) active.get(i).getRunnable().finishNaturally();
        extraR.finishNaturally();
        check("2e everything finishes → idle", host.await("idle") != null && engine.getTasks().isEmpty(), "");

        // ── 3. user Finish on an active download keeps the partial file as FINISHED ─
        freshEngine();
        DownloadTask t3 = startAndWait("https://cdn.example/f/clip.mp4", "fclip.mp4", false);
        engine.dispatch(withEntity(IntentActions.DOWNLOAD_FINISH, new DownloadEntity(t3.entity())), 1);
        DownloadRunnable r3 = t3.getRunnable();
        boolean stopped3 = waitUntil(() -> r3.stopped);
        check("3a Finish stops the runnable (stop, not delete)", stopped3 && !r3.deleted, "");
        String fin3 = host.await("finished:");
        check("3b … the thread unwinds and reports FINISHED",
                fin3 != null && fin3.endsWith(":" + Download.FINISHED)
                        && repo.latest(t3.getFileId()).getFileStatus() == Download.FINISHED, String.valueOf(fin3));
        check("3c … and the engine goes idle", host.await("idle") != null, "");

        // ── 4. delete an ACTIVE download: cancel + batch-delete + Deleted event ─
        freshEngine();
        DownloadTask t4 = startAndWait("https://cdn.example/d/clip.mp4", "dclip.mp4", false);
        DownloadRunnable r4 = t4.getRunnable();
        List<DownloadEntity> del = new ArrayList<>();
        del.add(new DownloadEntity(t4.entity()));
        engine.dispatch(withEntities(IntentActions.DOWNLOAD_DELETE, del), 1);
        boolean deleted4 = waitUntil(() -> r4.deleted && (r4.interrupted || r4.completed));
        check("4a delete marks the runnable deleted and interrupts its thread", deleted4, "");
        check("4b a LIVE task is not batch-deleted: the batch gets only orphans (empty here) and the engine deletes the row at recycle",
                waitUntil(() -> repo.batchDeletes.size() == 1 && repo.batchDeletes.get(0).isEmpty()
                        && repo.isDeleted(t4.getFileId())), "batches=" + repo.batchDeletes.size());
        check("4c TaskEvent.Deleted(1) published",
                waitUntil(() -> !taskRepo.events.isEmpty() && taskRepo.events.get(0) instanceof TaskEvent.Deleted
                        && ((TaskEvent.Deleted) taskRepo.events.get(0)).getCount() == 1), "events=" + taskRepo.events.size());
        check("4d path released", waitUntil(() -> !engine.mQueuedFileTasks.contains(t4.getFilePath())), "");
        check("4e the thread's unwind deletes the row (not re-adds it) and the engine goes idle",
                host.await("idle") != null && !repo.deleted.isEmpty() && repo.deleted.get(0).getId() == t4.getFileId()
                        && engine.getTasks().isEmpty(), "");

        // ── 5. delete a QUEUED download: never starts, recycled immediately ─
        freshEngine();
        List<DownloadTask> act5 = new ArrayList<>();
        for (int i = 0; i < POOL; i++) act5.add(startAndWait("https://cdn.example/dq/" + i + ".mp4", "dq" + i + ".mp4", false));
        int rb5 = DownloadRunnable.ALL.size();
        engine.dispatch(start("https://cdn.example/dq/extra.mp4", "dqextra.mp4", false), 1);
        waitUntil(() -> DownloadRunnable.ALL.size() > rb5);
        DownloadRunnable qr = lastRunnable();
        DownloadTask qt = taskOf(qr);
        waitUntil(() -> repo.latest(qt.getFileId()) != null && repo.latest(qt.getFileId()).getFileStatus() == Download.QUEUED);
        List<DownloadEntity> del5 = new ArrayList<>();
        del5.add(new DownloadEntity(qt.entity()));
        int qid = qt.getFileId();
        engine.dispatch(withEntities(IntentActions.DOWNLOAD_DELETE, del5), 1);
        check("5a a queued task is dropped from the lists without ever running",
                waitUntil(() -> !inLists(qid)) && !qr.started, "started=" + qr.started);
        for (DownloadTask t : act5) t.getRunnable().finishNaturally();
        check("5b the actives finish → idle; the deleted one never ran", host.await("idle") != null && !qr.started, "");

        // ── 6. restart an ERROR row resumes it ─
        freshEngine();
        DownloadEntity errored = new DownloadEntity();
        errored.setId(777);
        errored.setFileUrl("https://cdn.example/r/clip.mp4");
        errored.setFileName("rclip.mp4");
        errored.setFilePath(dir.getAbsolutePath() + "/rclip.mp4");
        errored.setFileStatus(Download.ERROR);
        errored.setFileErrorType(MessageHelper.SYSTEM_TIMEOUT);
        int rb6 = DownloadRunnable.ALL.size();
        engine.dispatch(withEntity(IntentActions.DOWNLOAD_RESTART, errored), 1);
        waitUntil(() -> DownloadRunnable.ALL.size() > rb6);
        DownloadRunnable r6 = lastRunnable();
        check("6a restart re-runs the SAME row id as PROGRESS",
                waitUntil(() -> r6.started) && host.await("fg:") != null
                        && repo.latest(777) != null && repo.latest(777).getFileStatus() == Download.PROGRESS, String.valueOf(repo.latest(777)));
        r6.finishNaturally();
        check("6b … and it can finish normally", host.await("idle") != null
                && repo.latest(777).getFileStatus() == Download.FINISHED, "");

        // ── 7. a strategy error: MSG_ERROR once, no duplicate MSG_FINISH ─
        freshEngine();
        DownloadTask t7 = startAndWait("https://cdn.example/e/clip.mp4", "eclip.mp4", false);
        DownloadRunnable r7 = t7.getRunnable();
        t7.onError(MessageHelper.IOEXCEPTION);
        String fin7 = host.await("finished:");
        check("7a onError → onDownloadFinished with ERROR",
                fin7 != null && fin7.equals("finished:" + t7.getFileId() + ":" + Download.ERROR), String.valueOf(fin7));
        r7.finishNaturally();  // the thread now unwinds, as after a strategy throw
        host.await("idle");
        List<String> extraEv = host.settle();
        check("7b the unwind does NOT report a second terminal (terminalMessageSent)",
                host.count("finished:") == 1 && extraEv.isEmpty(), "finished=" + host.count("finished:"));
        check("7c the row stays ERROR/IOEXCEPTION — the sealed status survives the unwind's write",
                repo.latest(t7.getFileId()).getFileStatus() == Download.ERROR
                        && repo.latest(t7.getFileId()).getFileErrorType() == MessageHelper.IOEXCEPTION, "");

        // ── 8. the FGS-timeout seal: ERROR + SYSTEM_TIMEOUT, and cancelAll must not undo it ─
        freshEngine();
        List<DownloadTask> act8 = new ArrayList<>();
        for (int i = 0; i < POOL; i++) act8.add(startAndWait("https://cdn.example/t/" + i + ".mp4", "t" + i + ".mp4", false));
        int rb8 = DownloadRunnable.ALL.size();
        engine.dispatch(start("https://cdn.example/t/extra.mp4", "textra.mp4", false), 1);
        waitUntil(() -> DownloadRunnable.ALL.size() > rb8);
        DownloadRunnable qr8 = lastRunnable();
        DownloadTask qt8 = taskOf(qr8);
        waitUntil(() -> repo.latest(qt8.getFileId()) != null && repo.latest(qt8.getFileId()).getFileStatus() == Download.QUEUED);
        host.settle();
        // Capture the runnables now: once a sealed thread unwinds, the engine
        // recycles its task and getRunnable() goes null (as in the real class).
        List<DownloadRunnable> act8R = new ArrayList<>();
        for (DownloadTask t : act8) act8R.add(t.getRunnable());
        // The service calls this from the platform's onTimeout — the MAIN thread, not the engine's.
        engine.sealTasksAsSystemStopped();
        boolean allSealed = true;
        StringBuilder why = new StringBuilder();
        List<DownloadTask> all8 = new ArrayList<>(act8);
        all8.add(qt8);
        for (DownloadTask t : all8) {
            DownloadEntity e = repo.latest(t.getFileId());
            boolean ok = t.isSealed() && e.getFileStatus() == Download.ERROR && e.getFileErrorType() == MessageHelper.SYSTEM_TIMEOUT;
            if (!ok) { allSealed = false; why.append(e).append(' '); }
        }
        check("8a every active AND queued task is written ERROR/SYSTEM_TIMEOUT synchronously",
                allSealed, why.toString());
        boolean stoppedAll = true;
        for (DownloadRunnable r : act8R) stoppedAll &= r.stopped;
        check("8b active runnables are stopped (retryable stop, not delete)",
                stoppedAll && !act8R.get(0).deleted, "");
        check("8c the queued runnable never starts (pulled from the pool)", !qr8.started, "");
        check("8d the path set is emptied", engine.mQueuedFileTasks.isEmpty(), engine.mQueuedFileTasks.toString());
        // Now the service's onDestroy runs cancelAll — which used to stamp FINISHED over everything.
        engine.cancelAll();
        boolean stillError = true;
        for (DownloadTask t : all8) stillError &= t.getFileStatus() == Download.ERROR;
        check("8e cancelAll after the seal leaves the ERROR seal alone (no FINISHED partial files)",
                stillError, "");
        boolean unwound = true;
        for (DownloadRunnable r : act8R) unwound &= r.awaitDone(WAIT_MS);
        check("8f the active threads unwind …", unwound, "");
        boolean finalRows = true;
        for (DownloadTask t : act8) finalRows &= repo.latest(t.getFileId()).getFileStatus() == Download.ERROR
                && repo.latest(t.getFileId()).getFileErrorType() == MessageHelper.SYSTEM_TIMEOUT;
        check("8g … and their final repository write is still ERROR/SYSTEM_TIMEOUT", finalRows, "");

        // ── 9. cancelAll on UNSEALED tasks: the destroy-path behaviour, pinned as-is ─
        freshEngine();
        DownloadTask t9 = startAndWait("https://cdn.example/c/clip.mp4", "cclip.mp4", false);
        DownloadRunnable r9 = t9.getRunnable();
        engine.cancelAll();
        check("9a plain cancelAll seals an unsealed active task FINISHED (pre-existing destroy semantics)",
                t9.isSealed() && t9.getFileStatus() == Download.FINISHED && r9.stopped, "");
        r9.awaitDone(WAIT_MS);

        // ── 10. vault split drives the notification target ─
        freshEngine();
        DownloadTask tv = startAndWait("https://cdn.example/v/clip.mp4", "vclip.mp4", true);
        check("10a a vault-only queue reports safe=1/regular=0", host.count("fg:1/0") >= 1, "events=" + host.all);
        DownloadTask tr = startAndWait("https://cdn.example/v/clip2.mp4", "vclip2.mp4", false);
        check("10b mixed reports safe=1/regular=1", host.count("fg:1/1") >= 1 || waitUntil(() -> host.count("fg:1/1") >= 1), "events=" + host.all);
        tv.getRunnable().finishNaturally();
        tr.getRunnable().finishNaturally();
        host.await("idle");

        // ── 11. filename collision: same name twice → distinct paths ─
        freshEngine();
        DownloadTask c1 = startAndWait("https://cdn.example/x/same.mp4", "same.mp4", false);
        DownloadTask c2 = startAndWait("https://cdn.example/y/same.mp4", "same.mp4", false);
        check("11a a second download with the same name gets a different path",
                !c1.getFilePath().equals(c2.getFilePath()) && c2.getFilePath().contains("same-1"),
                c1.getFilePath() + " vs " + c2.getFilePath());
        repo.byPath.put(dir.getAbsolutePath() + "/owned.mp4", new DownloadEntity());
        DownloadTask c3 = startAndWait("https://cdn.example/z/owned.mp4", "owned.mp4", false);
        check("11b a path an existing DB row owns (an errored download) is skipped too",
                c3.getFilePath().endsWith("owned-1.mp4"), c3.getFilePath());
        c1.getRunnable().finishNaturally(); c2.getRunnable().finishNaturally(); c3.getRunnable().finishNaturally();
        host.await("idle");

        // ── 12. legacy intent shape + unknown action ─
        freshEngine();
        engine.dispatch(new Intent("com.solarized.firedown.BOGUS"), 1);
        int rb12 = DownloadRunnable.ALL.size();
        engine.dispatch(legacyStart("https://cdn.example/l/clip.mp4", "lclip.mp4"), 1);
        waitUntil(() -> DownloadRunnable.ALL.size() > rb12);
        DownloadRunnable r12 = lastRunnable();
        check("12a an unknown action is ignored and the legacy BrowserDownloadEntity shape still starts",
                waitUntil(() -> r12.started) && host.await("fg:") != null, "");
        r12.finishNaturally();
        host.await("idle");

        // ── 13. filePathInTasks refuses the main thread ─
        final boolean[] threw = {false};
        Thread main = new Thread(() -> {
            Looper.bindMainToCurrentThread();
            try { engine.filePathInTasks("/nope"); } catch (IllegalStateException e) { threw[0] = true; }
        });
        main.start(); main.join();
        check("13a filePathInTasks throws on the main thread (it does DB IO)", threw[0], "");

        // ── 14. leak sweep ─
        int startedNotDone = 0;
        for (DownloadRunnable r : DownloadRunnable.ALL) if (r.started && !r.completed) startedNotDone++;
        check("14a every download thread that started has unwound", startedNotDone == 0, "stuck=" + startedNotDone);
        check("14b no handler exception was swallowed on the engine thread",
                Looper.UNCAUGHT.isEmpty(), Looper.UNCAUGHT.toString());
        // The service's onDestroy runs cancelAll then shutdown; every engine
        // this suite built (freshEngine per section) must end its thread, or
        // each start/idle cycle of the download service parks one more
        // HandlerThread for the life of the process.
        engine.cancelAll();
        engine.shutdown();
        check("14c shutdown ends the engine thread", awaitNoEngineThreads(WAIT_MS),
                "alive=" + liveEngineThreads());
    }

    static int liveEngineThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("RunnableManagerArguments".equals(t.getName()) && t.isAlive()) n++;
        }
        return n;
    }

    static boolean awaitNoEngineThreads(long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (liveEngineThreads() == 0) return true;
            Thread.sleep(20);
        }
        return liveEngineThreads() == 0;
    }
}

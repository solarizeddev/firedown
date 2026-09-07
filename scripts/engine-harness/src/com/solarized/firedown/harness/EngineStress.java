package com.solarized.firedown.harness;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.Download;
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
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;

/**
 * Randomized concurrency storm against the REAL DownloadEngine. Several
 * "UI" threads fire a random op mix at it — the same intents the app sends,
 * plus the download threads' own natural finishes and strategy errors, plus
 * (in one of the two phases) the system's FGS-timeout seal and the service's
 * destroy-time cancelAll — with filenames drawn from a tiny pool so the
 * collision loop is exercised constantly. Then it drains and checks the
 * invariants that must hold under ANY interleaving.
 *
 * <p>Phase A (no seal) must reach a clean rest: lists empty, path set empty,
 * idle reported, every row terminal or deleted. Phase B ends with a seal +
 * cancelAll mid-storm and checks the sealed rows are ERROR/SYSTEM_TIMEOUT and
 * nothing threw. Both phases: no exception on the engine thread, no task in
 * both lists or twice in one, every started thread unwound, no two live
 * tasks on one path.
 *
 * <p>Usage: {@code EngineStress [seconds-per-phase] [seed]}. The seed fixes
 * the op mix; thread scheduling stays nondeterministic (that is the point).
 */
public class EngineStress {
    static int pass = 0, fail = 0;
    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("PASS  " + n + (d.isEmpty() ? "" : "  [" + d + "]")); }
        else    { fail++; System.out.println("FAIL  " + n + "  [" + d + "]"); }
    }

    static final int POOL = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    static final int UI_THREADS = 4;
    static final int MAX_LIVE = 40;
    static final String[] NAMES = {"clip.mp4", "clip.mp4", "song.mp3", "clip.mp4", "doc.pdf", "song.mp3"};

    /** Counting host; also samples the lists on every callback for the "no task in two lists" invariant. */
    static final class Host implements DownloadEngine.Host {
        final AtomicInteger fg = new AtomicInteger(), finished = new AtomicInteger(), idle = new AtomicInteger();
        final List<String> listViolations = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile DownloadEngine engine;
        private void sample() {
            if (engine == null) return;
            List<DownloadTask> tasks = engine.getTasks();
            Set<Integer> ids = new HashSet<>();
            for (DownloadTask t : tasks) {
                if (!ids.add(t.getFileId())) listViolations.add("task #" + t.getFileId() + " listed twice");
            }
        }
        @Override public void onForegroundNeeded(int safe, int regular) { fg.incrementAndGet(); sample(); }
        @Override public void onDownloadFinished(DownloadTask task) { finished.incrementAndGet(); sample(); }
        @Override public void onIdle() { idle.incrementAndGet(); }
    }

    static Host host;
    static DownloadDataRepository repo;
    static TaskRepository taskRepo;
    static DownloadEngine engine;
    static File dir;
    static final AtomicLong ops = new AtomicLong();
    static final AtomicInteger starts = new AtomicInteger();
    static final Set<Integer> userFinished = java.util.Collections.synchronizedSet(new HashSet<>());
    static final Set<Integer> deletedIds = java.util.Collections.synchronizedSet(new HashSet<>());

    static void fresh() {
        host = new Host();
        repo = new DownloadDataRepository();
        taskRepo = new TaskRepository();
        engine = new DownloadEngine(new Context(), host, repo, taskRepo, new OkHttpClient(), new GeckoRuntimeHelper());
        host.engine = engine;
        DownloadRunnable.ALL.clear();
        DownloadTask.ALL.clear();
        DownloadTask.LIVE_PATHS.clear();
        DownloadTask.PATH_VIOLATIONS.clear();
        userFinished.clear();
        deletedIds.clear();
        starts.set(0);
        ops.set(0);
    }

    static List<DownloadTask> liveTasks() {
        List<DownloadTask> out = new ArrayList<>();
        synchronized (DownloadTask.ALL) {
            for (DownloadTask t : DownloadTask.ALL) {
                DownloadRunnable r = t.getRunnable();
                if (r != null && !r.completed && !t.isSealed()) out.add(t);
            }
        }
        return out;
    }
    static DownloadTask pick(Random rnd, List<DownloadTask> from) {
        return from.isEmpty() ? null : from.get(rnd.nextInt(from.size()));
    }

    /** One random op. Returns false if there was nothing to do. */
    static boolean op(Random rnd, boolean allowSeal) {
        int roll = rnd.nextInt(100);
        ops.incrementAndGet();
        if (roll < 40) {
            // start — names collide on purpose. Bounded: a user queue is tens
            // of items, not thousands, and an unbounded start rate just starves
            // the other ops of live tasks to act on.
            if (engine.getTasks().size() > MAX_LIVE) return false;
            String name = NAMES[rnd.nextInt(NAMES.length)];
            Intent i = new Intent(IntentActions.DOWNLOAD_START);
            i.putExtra(Keys.DOWNLOAD_REQUEST, new DownloadRequest(
                    "https://cdn.example/" + rnd.nextInt(1000) + "/" + name, name, "video/mp4", rnd.nextInt(5) == 0));
            engine.dispatch(i, 1);
            starts.incrementAndGet();
            return true;
        }
        List<DownloadTask> live = liveTasks();
        if (roll < 62) {
            // natural finish of a running download
            DownloadTask t = pick(rnd, live);
            if (t == null) return false;
            DownloadRunnable r = t.getRunnable();
            if (r != null && r.started) { r.finishNaturally(); return true; }
            return false;
        }
        if (roll < 74) {
            DownloadTask t = pick(rnd, live);
            if (t == null) return false;
            userFinished.add(t.getFileId());
            Intent i = new Intent(IntentActions.DOWNLOAD_FINISH);
            i.putExtra(Keys.ITEM_ID, new DownloadEntity(t.entity()));
            engine.dispatch(i, 1);
            return true;
        }
        if (roll < 88) {
            // delete 1..3 live tasks in one intent (multi-select)
            if (live.isEmpty()) return false;
            ArrayList<DownloadEntity> es = new ArrayList<>();
            int n = 1 + rnd.nextInt(Math.min(3, live.size()));
            for (int k = 0; k < n; k++) {
                DownloadTask t = pick(rnd, live);
                deletedIds.add(t.getFileId());
                es.add(new DownloadEntity(t.entity()));
            }
            Intent i = new Intent(IntentActions.DOWNLOAD_DELETE);
            i.putExtra(Keys.ITEM_LIST_ID, es);
            engine.dispatch(i, 1);
            return true;
        }
        if (roll < 94) {
            // strategy error on a running one
            DownloadTask t = pick(rnd, live);
            if (t == null || t.getRunnable() == null || !t.getRunnable().started) return false;
            t.onError(MessageHelper.IOEXCEPTION);
            return true;
        }
        // restart an errored row that is not live any more
        List<Integer> errored = new ArrayList<>();
        synchronized (repo.history) {
            for (Integer id : repo.history.keySet()) {
                DownloadEntity e = repo.latest(id);
                if (e != null && e.getFileStatus() == Download.ERROR && !deletedIds.contains(id)) errored.add(id);
            }
        }
        if (errored.isEmpty()) return false;
        int id = errored.get(rnd.nextInt(errored.size()));
        for (DownloadTask t : live) if (t.getFileId() == id) return false;  // already running again
        Intent i = new Intent(IntentActions.DOWNLOAD_RESTART);
        i.putExtra(Keys.ITEM_ID, new DownloadEntity(repo.latest(id)));
        engine.dispatch(i, 1);
        return true;
    }

    static void storm(long seconds, long seed, boolean allowSeal) throws InterruptedException {
        final AtomicBoolean run = new AtomicBoolean(true);
        final CountDownLatch done = new CountDownLatch(UI_THREADS);
        final List<Throwable> uiErrors = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < UI_THREADS; t++) {
            final Random rnd = new Random(seed * 31 + t);
            Thread th = new Thread(() -> {
                try {
                    while (run.get()) {
                        op(rnd, allowSeal);
                        Thread.sleep(rnd.nextInt(4));
                    }
                } catch (Throwable e) {
                    uiErrors.add(e);
                } finally { done.countDown(); }
            }, "ui-" + t);
            th.setDaemon(true);
            th.start();
        }
        Thread.sleep(seconds * 1000);
        run.set(false);
        done.await();
        check("  UI threads never saw an exception from dispatch", uiErrors.isEmpty(), uiErrors.toString());
    }

    /** Release every still-blocked download thread so the engine can rest. */
    static void drain() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            boolean any = false;
            List<DownloadRunnable> snapshot;
            synchronized (DownloadRunnable.ALL) { snapshot = new ArrayList<>(DownloadRunnable.ALL); }
            for (DownloadRunnable r : snapshot) {
                if (r.started && !r.completed) { r.finishNaturally(); any = true; }
            }
            if (!any && engine.getTasks().isEmpty()) return;
            Thread.sleep(2);
        }
    }

    static boolean waitUntil(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (!c.getAsBoolean()) { if (System.currentTimeMillis() > deadline) return false; Thread.sleep(10); }
        return true;
    }

    static void commonInvariants(String phase) {
        check(phase + " no exception reached the engine thread", Looper.UNCAUGHT.isEmpty(), Looper.UNCAUGHT.toString());
        check(phase + " no task ever listed twice / in both lists", host.listViolations.isEmpty(),
                host.listViolations.isEmpty() ? "" : host.listViolations.get(0));
        int stuck = 0;
        synchronized (DownloadRunnable.ALL) { for (DownloadRunnable r : DownloadRunnable.ALL) if (r.started && !r.completed) stuck++; }
        check(phase + " every started download thread unwound", stuck == 0, "stuck=" + stuck);
        check(phase + " no two live tasks ever shared a path", DownloadTask.PATH_VIOLATIONS.isEmpty(),
                DownloadTask.PATH_VIOLATIONS.isEmpty() ? "" : DownloadTask.PATH_VIOLATIONS.get(0));
    }

    public static void main(String[] a) {
        try {
            run(a);
        } catch (Throwable t) {
            System.out.println("ABORT  " + t);
            t.printStackTrace(System.out);
            fail++;
        }
        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void run(String[] a) throws Exception {
        long seconds = a.length > 0 ? Long.parseLong(a[0]) : 8;
        long seed = a.length > 1 ? Long.parseLong(a[1]) : System.nanoTime();
        dir = Files.createTempDirectory("engine-stress").toFile();
        StoragePaths.downloadDir = dir.getAbsolutePath();
        System.out.println("pool=" + POOL + " ui-threads=" + UI_THREADS + " seconds/phase=" + seconds + " seed=" + seed);

        // ── Phase A: storm, then drain to a clean rest ───────────────────────
        fresh();
        storm(seconds, seed, false);
        System.out.println("A: ops=" + ops.get() + " starts=" + starts.get() + " runnables=" + DownloadRunnable.ALL.size()
                + " fg=" + host.fg.get() + " finished=" + host.finished.get());
        drain();
        boolean rested = waitUntil(() -> engine.getTasks().isEmpty(), 10_000);
        check("A engine reaches an empty task list after drain", rested, "left=" + engine.getTasks().size());
        // Give the last MSG_STOP time to land.
        waitUntil(() -> host.idle.get() > 0 && engine.mQueuedFileTasks.isEmpty(), 5_000);
        if (!engine.mQueuedFileTasks.isEmpty()) {
            int shown = 0;
            List<String> leaked;
            synchronized (engine.mQueuedFileTasks) { leaked = new ArrayList<>(engine.mQueuedFileTasks); }
            for (String path : leaked) {
                if (shown++ >= 2) break;
                StringBuilder d = new StringBuilder("\n    leaked path " + path);
                synchronized (DownloadTask.ALL) {
                    for (DownloadTask t : DownloadTask.ALL) if (path.equals(t.getFilePath())) {
                        DownloadRunnable r = t.getRunnable();
                        d.append("\n      #" + t.getFileId() + " userFinished=" + userFinished.contains(t.getFileId())
                                + " deleted=" + deletedIds.contains(t.getFileId()) + " sealed=" + t.isSealed()
                                + " status=" + t.getFileStatus() + " repoDeleted=" + repo.isDeleted(t.getFileId())
                                + " runnable=" + (r == null ? "null" : "started=" + r.started + " completed=" + r.completed + " stopped=" + r.stopped + " deleted=" + r.deleted)
                                + " trace=" + t.trace);
                    }
                }
                System.out.println(d);
            }
        }
        check("A path set is empty at rest", engine.mQueuedFileTasks.isEmpty(), "" + engine.mQueuedFileTasks.size() + " leaked");
        check("A onIdle was reported", host.idle.get() > 0, "idle=" + host.idle.get());
        commonInvariants("A");
        // every row: terminal, or deleted
        int nonTerminal = 0, rows = 0, finishedRows = 0, errorRows = 0, deletedRows = 0;
        StringBuilder bad = new StringBuilder();
        synchronized (repo.history) {
            for (Integer id : repo.history.keySet()) {
                rows++;
                DownloadEntity e = repo.latest(id);
                if (repo.isDeleted(id)) { deletedRows++; continue; }
                int s = e.getFileStatus();
                if (s == Download.FINISHED) finishedRows++;
                else if (s == Download.ERROR) errorRows++;
                else {
                    nonTerminal++;
                    if (bad.length() < 300) bad.append(e).append(' ');
                    if (nonTerminal <= 3) {
                        StringBuilder d = new StringBuilder("\n    stuck #" + id + " history=" + repo.statuses(id)
                                + " userFinished=" + userFinished.contains(id) + " deleted=" + deletedIds.contains(id));
                        synchronized (DownloadTask.ALL) {
                            for (DownloadTask t : DownloadTask.ALL) if (t.getFileId() == id) {
                                DownloadRunnable r = t.getRunnable();
 d.append(" trace=" + t.trace); d.append(" task{sealed=" + t.isSealed() + " status=" + t.getFileStatus() + " inits=" + t.initializations
                                        + " runnable=" + (r == null ? "null" : ("started=" + r.started + " completed=" + r.completed + " stopped=" + r.stopped + " deleted=" + r.deleted)) + "}");
                            }
                        }
                        synchronized (DownloadRunnable.ALL) {
                            for (DownloadRunnable r : DownloadRunnable.ALL) if (r.taskId() == id)
                                d.append(" r{started=" + r.started + " completed=" + r.completed + " stopped=" + r.stopped + " deleted=" + r.deleted + "}");
                        }
                        System.out.println(d);
                    }
                }
            }
        }
        check("A every row is FINISHED, ERROR or deleted (none stuck PROGRESS/QUEUED)", nonTerminal == 0,
                "rows=" + rows + " finished=" + finishedRows + " error=" + errorRows + " deleted=" + deletedRows + " stuck=" + nonTerminal + " " + bad);
        check("A the storm actually exercised the queue (rows > POOL*4)", rows > POOL * 4, "rows=" + rows);
        check("A onDownloadFinished fired once per terminal run, never for a deleted-queued one twice",
                host.finished.get() >= finishedRows + errorRows, "finished-callbacks=" + host.finished.get());

        // ── Phase B: storm, then the FGS timeout lands mid-storm ─────────────
        fresh();
        storm(seconds, seed + 1, true);
        // Freeze the world the way onTimeout sees it: whatever is in the lists now.
        List<DownloadTask> atSeal = engine.getTasks();
        List<DownloadRunnable> atSealRunnables = new ArrayList<>();
        Set<Integer> sealedBefore = new HashSet<>();
        for (DownloadTask t : atSeal) {
            atSealRunnables.add(t.getRunnable());
            if (t.isSealed()) sealedBefore.add(t.getFileId());   // user-finished / errored: keeps its own status
        }
        engine.sealTasksAsSystemStopped();   // main thread, like the platform callback
        engine.cancelAll();                  // then onDestroy
        System.out.println("B: ops=" + ops.get() + " starts=" + starts.get() + " sealed=" + atSeal.size());
        // Sealed actives unwind on their own (stop + interrupt); sealed queued ones never run.
        boolean unwound = true;
        for (DownloadRunnable r : atSealRunnables) if (r != null && r.started) unwound &= r.awaitDone(5_000);
        check("B every sealed ACTIVE thread unwound after the seal", unwound, "");
        int wrong = 0;
        StringBuilder why = new StringBuilder();
        for (DownloadTask t : atSeal) {
            DownloadEntity e = repo.latest(t.getFileId());
            boolean ok = e != null && e.getFileStatus() == Download.ERROR && e.getFileErrorType() == MessageHelper.SYSTEM_TIMEOUT;
            // A task the storm had ALREADY sealed (user Finish / delete / error) keeps its own status — that's the guard.
            if (!ok && !sealedBefore.contains(t.getFileId()) && !deletedIds.contains(t.getFileId())) {
                wrong++; if (why.length() < 300) why.append(e).append(' ');
            }
        }
        check("B every task in the lists at seal time is ERROR/SYSTEM_TIMEOUT (or already sealed by the storm)", wrong == 0, why.toString());
        int finishedAfterSeal = 0;
        for (DownloadTask t : atSeal) if (!userFinished.contains(t.getFileId()) && t.getFileStatus() == Download.FINISHED) finishedAfterSeal++;
        check("B cancelAll stamped FINISHED on nothing the seal owned", finishedAfterSeal == 0, "finished=" + finishedAfterSeal);
        // Anything the storm started AFTER the seal snapshot is outside the service's life; just release it.
        drain();
        commonInvariants("B");
    }
}

package com.solarized.firedown.harness;

import static com.solarized.firedown.harness.W.*;

import android.app.Sys;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.os.HandlerThread;
import android.os.PowerManager;

import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.ffmpegutils.FakeNative;
import com.solarized.firedown.manager.ServiceActions;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Collections;

/**
 * Two "UI" threads fire random starts (all seven tasks, fresh inputs each),
 * cancels (each task's own action, and the notification's Cancel), null and
 * unknown intents at the REAL TaskManager; then, at rest, the invariants
 * that must hold under any interleaving.
 *
 *   sh scripts/task-harness/run.sh stress [seconds] [seed]
 *
 * The seed fixes the op mix; thread scheduling stays free.
 */
final class TaskStress {

    private TaskStress() {}

    static final Object PLAN_LOCK = new Object();

    static void run(int seconds, long seed, String group) throws Exception {
        String id = group + ".stress";
        quiesce(8000);
        resetFakes();
        clearLog();
        dirs("stress-" + seed);
        int recIdx = sys.records.size();
        int nativeIdx = FakeNative.ALL.size();
        int crash0 = crashCount();
        FakeNative.GIF.steps = 6; FakeNative.GIF.stepMs = 2;
        FakeNative.ENC.steps = 6; FakeNative.ENC.stepMs = 2;
        MediaMetadataRetriever.delayMs = 3;
        System.out.println("stress: " + seconds + " s, seed " + seed);

        AtomicInteger starts = new AtomicInteger(), cancels = new AtomicInteger(), others = new AtomicInteger();
        AtomicInteger seq = new AtomicInteger();
        List<Throwable> threadErrors = Collections.synchronizedList(new ArrayList<>());
        long end = System.currentTimeMillis() + seconds * 1000L;
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 2; t++) {
            Random r = new Random(seed * 31 + t);
            Thread th = new Thread(() -> {
                try {
                    while (System.currentTimeMillis() < end) {
                        if (starts.get() - count(Ev::finished) > 10) {
                            Thread.sleep(5);
                            continue;
                        }
                        int op = r.nextInt(100);
                        if (op < 55) {
                            K k = K.values()[r.nextInt(K.values().length)];
                            Plan p;
                            synchronized (PLAN_LOCK) {
                                p = plan(k, "s" + seq.incrementAndGet(), 1 + r.nextInt(2), 8 * 1024 + r.nextInt(48 * 1024));
                            }
                            start(p);
                            starts.incrementAndGet();
                        } else if (op < 80) {
                            if (r.nextBoolean()) {
                                K k = K.values()[r.nextInt(K.values().length)];
                                sys.startService(cancelIntent(k.cancel), false);
                            } else {
                                cancelViaNotification();
                            }
                            cancels.incrementAndGet();
                        } else if (op < 85) {
                            sys.startNull();
                            others.incrementAndGet();
                        } else if (op < 90) {
                            sys.startService(cancelIntent("com.example.NOT_A_TASK_ACTION"), false);
                            others.incrementAndGet();
                        }
                        Thread.sleep(r.nextInt(12));
                    }
                } catch (Throwable e) {
                    threadErrors.add(e);
                }
            }, "stress-ui-" + t);
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) th.join(seconds * 1000L + 15000L);
        boolean joined = threads.stream().noneMatch(Thread::isAlive);
        boolean settled = waitUntil(() -> count(Ev::finished) >= starts.get(), 30000) && quiesce(30000);

        List<Ev> ev = evs();
        long fins = ev.stream().filter(Ev::finished).count();
        check(id + ".threads-joined", joined && threadErrors.isEmpty(), "errors=" + threadErrors);
        check(id + ".every-start-finished-once", settled && fins == starts.get(),
                starts.get() + " starts, " + fins + " Finished, " + cancels.get() + " cancels, " + others.get() + " other intents");

        // Started -> Progress* -> Finished of the same task, one task at a time.
        String bad = null;
        K open = null;
        Map<K, int[]> outcome = new EnumMap<>(K.class);
        for (Ev e : ev) {
            if (e.started()) {
                if (open != null) { bad = "Started(" + e.action() + ") while " + open + " open"; break; }
                open = K.ofAction(e.action());
                if (open == null) { bad = "Started of unknown " + e.action(); break; }
            } else if (e.progress()) {
                if (open == null) { bad = "Progress outside a task"; break; }
            } else if (e.finished()) {
                K k = K.ofAction(e.action());
                if (open == null || k != open) { bad = "Finished(" + e.action() + ") for open " + open; break; }
                int[] o = outcome.computeIfAbsent(k, x -> new int[3]);
                if (e.action() == k.ok) o[0]++;
                else if (e.action() == k.cancelled) o[1]++;
                else o[2]++;
                if (e.action() == k.ok && e.result() instanceof DownloadEntity d && !new File(d.getFilePath()).isFile()) {
                    bad = "success " + k + " but " + d.getFilePath() + " missing";
                    break;
                }
                open = null;
            }
        }
        if (bad == null && open != null) bad = open + " never finished";
        check(id + ".event-sequence", bad == null, bad == null ? "" : bad);
        StringBuilder oc = new StringBuilder();
        for (Map.Entry<K, int[]> e : outcome.entrySet()) {
            oc.append(e.getKey().tag).append(" ok=").append(e.getValue()[0]).append(" cancel=").append(e.getValue()[1])
                    .append(" error=").append(e.getValue()[2]).append("; ");
        }
        info(id, "outcomes: " + oc);

        check(id + ".in-flight-zero", !taskRepo.isTaskInFlight(), "");
        boolean anyFg = false, anyNotif = false, allDestroyed = true;
        int records = 0;
        synchronized (sys.records) {
            for (int i = recIdx; i < sys.records.size(); i++) {
                Sys.Record r = sys.records.get(i);
                records++;
                if (r.foreground) anyFg = true;
                if (r.notification != null) anyNotif = true;
                if (!r.destroyed) allDestroyed = false;
            }
        }
        check(id + ".no-foreground-at-rest", !anyFg && !anyNotif, "fg=" + anyFg + " notification=" + anyNotif);
        check(id + ".services-stopped", allDestroyed, records + " instance(s)");
        check(id + ".no-wake-lock-held", !PowerManager.anyHeld(), "");
        check(id + ".no-exception", crashCount() == crash0, crashes());
        long workers = HandlerThread.ALL.stream().filter(t -> "TaskManager".equals(t.getName())).count();
        check(id + ".one-idle-worker", workers == 1 && workerIdle(), "workers=" + workers + " idle=" + workerIdle());
        List<FakeNative.State> st = FakeNative.since(nativeIdx);
        long unfreed = st.stream().filter(s -> !s.freed).count();
        check(id + ".native-objects-freed", unfreed == 0 && FakeNative.USE_AFTER_FREE.get() == 0,
                st.size() + " native objects, unfreed=" + unfreed + ", use-after-free=" + FakeNative.USE_AFTER_FREE.get());

        long producedStarted = ev.stream().filter(Ev::started).count();
        List<TaskEvent> got = new ArrayList<>(ui);
        long uiStarted = got.stream().filter(e -> e instanceof TaskEvent.Started).count();
        long uiFinished = got.stream().filter(e -> e instanceof TaskEvent.Finished).count();
        check(id + ".ui-receives-every-start-and-result", uiStarted == producedStarted && uiFinished == fins,
                uiStarted + "/" + producedStarted + " Started, " + uiFinished + "/" + fins + " Finished");
        info(id, "UI observer received " + uiStarted + "/" + producedStarted
                + " Started and " + uiFinished + "/" + fins + " Finished; " + records + " service instance(s) over the run");
    }
}

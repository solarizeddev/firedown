package com.solarized.firedown.geckoview;

import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;


public class PriorityTaskThreadPoolExecutor {

    private static final String TAG = PriorityTaskThreadPoolExecutor.class.getSimpleName();

    private final static int NUMBER_OF_CORES = Runtime.getRuntime().availableProcessors();

    /**
     * Pool size: half the device cores, but at least one — {@code NUMBER_OF_CORES
     * / 2} is 0 on a single-core device and {@link Executors#newFixedThreadPool}
     * throws on 0. All of these threads are usable: {@link #executeWaitingTask}
     * submits while any slot is free (no thread is reserved/idle).
     */
    private final static int NETWORK_CORE_POOL_SIZE = Math.max(1, NUMBER_OF_CORES / 2);

    public static final int PRIORITY_HIGH = 1;

    public static final int PRIORITY_NORMAL = 10;

    public static final int PRIORITY_LOW = 100;

    /**
     * Demotion floor for a task whose tab isn't the foreground one. Strictly
     * worse (larger) than every base priority — {@link #PRIORITY_HIGH},
     * {@link #PRIORITY_NORMAL}, {@link #PRIORITY_LOW} — so any foreground task
     * outranks any background one.
     *
     * <p>Why a level <em>below</em> LOW is needed: generic (non-media) captures
     * carry a base of {@code PRIORITY_LOW}. If background tasks were merely
     * floored at {@code PRIORITY_LOW} too, a tab you just switched into whose own
     * captures are also generic would <em>tie</em> with the previous tab's
     * backlog — switch into a tab behind 200 queued LOW items and your new
     * captures still wait behind them. Demoting the backlog to a priority no
     * foreground task can hold guarantees the current tab's work runs first
     * regardless of its base.</p>
     */
    public static final int PRIORITY_BACKGROUND = 1000;

    private static final int PRIORITY_CAPACITY = 100;

    private final PriorityBlockingQueue<Task> awaitingTasks;

    /**
     * Tasks currently executing on the pool (added at submit, removed in the
     * run's {@code finally}). Unlike the queued {@link #awaitingTasks}, a running
     * task can't be dropped — but it CAN be interrupted: {@link #cancelTab} calls
     * {@link GeckoInspectTask#cancel()} on each running task of a closed tab so a
     * wedged native probe unwinds at once. Concurrent set: added under {@code
     * this} (in {@link #executeWaitingTask}), removed off-monitor in the finally.
     */
    private final Set<Task> runningTasks = ConcurrentHashMap.newKeySet();

    private final ExecutorService executor;

    private final int corePoolSize;

    private final AtomicInteger poolSize;

    /**
     * Count of submitted-but-not-finished inspect tasks (queued + running),
     * across all tabs. Incremented when a task is offered, decremented in the
     * run's {@code finally} so aborts/failures count too. Exposed as LiveData so
     * the capture UI can show a "scanning…" indicator while work is pending
     * (the gap where a video can take seconds to appear after the sheet opens).
     */
    private final AtomicInteger inFlight = new AtomicInteger(0);

    private final MutableLiveData<Integer> inFlightLive = new MutableLiveData<>(0);

    /**
     * Foreground tab. A queued task keeps its base priority while its tab is
     * current and is demoted to {@link #PRIORITY_BACKGROUND} otherwise, so
     * switching tabs re-prioritizes the backlog. Volatile: read in {@link
     * #effectivePriority}, mutated only under {@code this}. {@code -1} = unknown,
     * treat every task as foreground (no wrongful demotion before the first tab
     * is known).
     */
    private volatile int currentTabId = -1;


    /**
     * Creates the inspect-task pool. Sizes itself to {@link
     * #NETWORK_CORE_POOL_SIZE} threads (half the device cores, floored at 1) and
     * uses all of them — {@link #executeWaitingTask} submits while any slot is
     * free. Takes no arguments; there is no reserved/idle thread.
     */
    public PriorityTaskThreadPoolExecutor() {
        this.awaitingTasks = new PriorityBlockingQueue<>(PRIORITY_CAPACITY, new PriorityFutureComparator());
        this.executor = Executors.newFixedThreadPool(NETWORK_CORE_POOL_SIZE);
        this.corePoolSize = NETWORK_CORE_POOL_SIZE;
        this.poolSize = new AtomicInteger(0);
    }

    /**
     * @param basePriority the urlType-derived priority (its value when the tab
     *                     is in the foreground); the executor demotes it to
     *                     {@link #PRIORITY_BACKGROUND} (below every base
     *                     priority) while {@code tabId} isn't the current tab.
     */
    public void execute(GeckoInspectTask task, int basePriority, int tabId) {
        Log.d(TAG, "execute: " + awaitingTasks.size());
        Task t = new Task(task, basePriority, tabId);
        t.priority = effectivePriority(t);
        inFlightLive.postValue(inFlight.incrementAndGet());
        awaitingTasks.offer(t);
        executeWaitingTask();
    }


    public boolean isTerminated() {
        return executor.isTerminated();
    }

    /** In-flight inspect tasks (queued + running) across all tabs. */
    public LiveData<Integer> getInFlight() {
        return inFlightLive;
    }

    /**
     * Handle a closed tab. Two parts:
     * <ul>
     *   <li><b>Queued</b> (not-yet-started) tasks are <em>dropped</em> so the
     *       closed tab's backlog doesn't occupy the pool — each is decremented
     *       from the in-flight count, since its run-finally will never fire.</li>
     *   <li><b>Running</b> tasks can't be dropped, so they are <em>interrupted</em>
     *       via {@link GeckoInspectTask#cancel()}: a probe wedged in a native
     *       HLS/DASH reload loop (e.g. every segment 403s on a dead live stream)
     *       unwinds at once instead of pinning a pool thread until the hls.c
     *       consecutive-failure bail trips. Their in-flight count is NOT touched
     *       here — they still complete and their run-finally decrements it.</li>
     * </ul>
     *
     * <p>Deliberately does <b>not</b> touch {@link #currentTabId}: closing the
     * foreground tab leaves it pointing at the now-dead tab only until the
     * browser activates the next tab, which always fires onActivated →
     * {@link #setCurrentTab} (the sole exception is closing the <i>last</i> tab,
     * after which no captures flow until a new tab opens and activates). Resetting
     * it to {@code -1} here would be worse — that treats every task as foreground
     * and would surge a backgrounded tab's backlog back to base priority.</p>
     */
    public synchronized void cancelTab(int tabId) {
        int removed = 0;
        for (Task t : awaitingTasks.toArray(new Task[0])) {
            if (t != null && t.tabId == tabId && awaitingTasks.remove(t)) {
                removed++;
            }
        }
        if (removed > 0) {
            Log.d(TAG, "cancelTab " + tabId + " dropped " + removed + " queued task(s)");
            int n = inFlight.addAndGet(-removed);
            inFlightLive.postValue(Math.max(0, n));
        }

        // Interrupt any RUNNING tasks for this tab (queued ones are gone above).
        // cancel() is safe/idempotent and only acts if a probe is actually in
        // flight; the task still finishes and its run-finally clears the count.
        int interrupted = 0;
        for (Task t : runningTasks) {
            if (t != null && t.tabId == tabId) {
                t.task.cancel();
                interrupted++;
            }
        }
        if (interrupted > 0) {
            Log.d(TAG, "cancelTab " + tabId + " interrupted " + interrupted + " running task(s)");
        }
    }

    /**
     * Set the foreground tab and re-prioritize the pending queue: tasks for the
     * new current tab regain their base priority, all others drop to
     * {@link #PRIORITY_BACKGROUND} — a level below every base priority, so even
     * the current tab's LOW-base (generic) captures outrank a heavy background
     * tab's backlog instead of tying with it.
     *
     * <p>{@code synchronized} on the same monitor as {@link #cancelTab} and
     * {@link #executeWaitingTask}, which is what makes the "switch then close
     * (or close mid-switch)" race safe: the drain/recompute/re-offer here and a
     * tab-close's remove can't interleave — they run one fully then the other,
     * in either order, with a consistent result and correct in-flight count.
     * Holding the monitor across the drain also stops {@code executeWaitingTask}
     * from polling the transiently-empty queue. ({@code execute}'s offer isn't
     * on the monitor, but the queue is thread-safe and a task offered during the
     * window simply coexists with the re-offered ones.)</p>
     */
    public synchronized void setCurrentTab(int tabId) {
        if (tabId == currentTabId) {
            return;
        }
        currentTabId = tabId;
        if (awaitingTasks.isEmpty()) {
            return;
        }
        ArrayList<Task> pending = new ArrayList<>(awaitingTasks.size());
        awaitingTasks.drainTo(pending);
        for (Task t : pending) {
            t.priority = effectivePriority(t);
        }
        awaitingTasks.addAll(pending);
        executeWaitingTask();
    }

    /** Base priority while the task's tab is current (or the tab is still
     *  unknown); demoted to {@link #PRIORITY_BACKGROUND} for any other tab so a
     *  foreground task always outranks a background one — even when both share
     *  the same {@link #PRIORITY_LOW} base. */
    private int effectivePriority(Task t) {
        int ct = currentTabId;
        return (ct == -1 || t.tabId == ct) ? t.basePriority : PRIORITY_BACKGROUND;
    }

    private synchronized void executeWaitingTask() {
        if (executor.isShutdown()) {
            return;
        }

        // Drain into every free slot, not just one. Bounded: each submit
        // increments poolSize, so the loop runs at most (corePoolSize - busy)
        // times before the gate closes, or until the queue empties. (Submitting
        // one-per-call was already correct — every offer and every completion
        // triggers a call — but draining here removes any reliance on that
        // balance and fills all slots in a single pass after a setCurrentTab
        // re-prioritize. The gate is "> 0", never "> 1": reserving a slot left a
        // thread permanently idle, and on a 2-core device (corePoolSize 1)
        // stalled the pool entirely.)
        while (corePoolSize - poolSize.get() > 0) {
            final Task nextTask = awaitingTasks.poll();
            if (nextTask == null) {
                break;
            }
            poolSize.incrementAndGet();
            runningTasks.add(nextTask);

            boolean submitted = false;
            try {
                executor.submit(() -> {
                    try {
                        nextTask.task.run();
                    } finally {
                        Log.w(TAG, "taskHandler Finish");
                        releaseSlot(nextTask);
                        executeWaitingTask();
                    }
                });
                submitted = true;
            } finally {
                // If submit() threw (e.g. RejectedExecutionException — the
                // executor was shut down between the isShutdown() check above
                // and here), the lambda's finally will never run, so its
                // bookkeeping would leak: poolSize/runningTasks/inFlight would
                // never be undone and the freed slot would be lost, eventually
                // stalling the pool. Roll the just-added task back here and stop
                // draining (the executor is going away).
                if (!submitted) {
                    releaseSlot(nextTask);
                }
            }
            if (!submitted) {
                break;
            }
        }
    }

    /**
     * Release a pool slot: drop the task from the running set and decrement both
     * counters (clamping the published value to >= 0). Called from a worker's
     * run-finally on normal completion, and from {@link #executeWaitingTask} to
     * roll back a task whose submit was rejected — the same three counters in
     * both cases, so they can't drift. Each task hits this at most once: it was
     * counted into {@code inFlight} at {@link #execute}, and is either dropped by
     * {@link #cancelTab} while still queued (never submitted, so never here) or
     * released here exactly once. The clamp is belt-and-braces — poll() (run)
     * and remove() (cancel) share the monitor, so a task is never both cancelled
     * and run — so a stray negative can't get stuck on the "scanning" UI.
     */
    private void releaseSlot(Task task) {
        runningTasks.remove(task);
        poolSize.decrementAndGet();
        int n = inFlight.decrementAndGet();
        inFlightLive.postValue(Math.max(0, n));
    }

    private static class Task {
        final GeckoInspectTask task;
        final int basePriority;   // urlType-derived priority (its foreground value)
        final int tabId;
        int priority;             // effective priority used for queue ordering;
                                  // recomputed on a tab switch (mutated only off-queue)

        public Task(GeckoInspectTask task, int basePriority, int tabId) {
            this.task = task;
            this.basePriority = basePriority;
            this.tabId = tabId;
            this.priority = basePriority;
        }

        public int getPriority(){
            return priority;
        }

    }


    private static class PriorityFutureComparator implements Comparator<Task> {
        public int compare(Task o1, Task o2) {
            if (o1 == null && o2 == null)
                return 0;
            else if (o1 == null)
                return -1;
            else if (o2 == null)
                return 1;
            else {
                int p1 = o1.getPriority();
                int p2 = o2.getPriority();

                return Integer.compare(p1, p2);
            }
        }
    }


}

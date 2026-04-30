package com.solarized.firedown.geckoview;

import android.util.Log;

import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;


public class PriorityTaskThreadPoolExecutor {

    private static final String TAG = PriorityTaskThreadPoolExecutor.class.getSimpleName();

    private final static int NUMBER_OF_CORES = Runtime.getRuntime().availableProcessors();

    private final static int NETWORK_CORE_POOL_SIZE = NUMBER_OF_CORES / 2;

    public static final int PRIORITY_HIGH = 1;

    public static final int PRIORITY_NORMAL = 10;

    public static final int PRIORITY_LOW = 100;

    private static final int PRIORITY_CAPACITY = 100;

    private final PriorityBlockingQueue<Task> awaitingTasks;

    private final ExecutorService executor;

    private final int corePoolSize;

    private final AtomicInteger poolSize;


    /**
     * Creates a new {@code TimeoutTaskThreadPoolExecutor} with the
     * given core pool size.
     * The pool should be greater or equals than 2 because one thread is reserved
     * to schedule cancellation task.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     */
    public PriorityTaskThreadPoolExecutor() {
        this.awaitingTasks = new PriorityBlockingQueue<>(PRIORITY_CAPACITY, new PriorityFutureComparator());
        this.executor = Executors.newFixedThreadPool(NUMBER_OF_CORES/2);
        this.corePoolSize = NETWORK_CORE_POOL_SIZE;
        this.poolSize = new AtomicInteger(0);
    }

    public void execute(GeckoInspectTask task, int priority, int tabId) {
        Log.d(TAG, "execute: " + awaitingTasks.size());
        awaitingTasks.offer(new Task(task, priority, tabId));
        executeWaitingTask();
    }


    public boolean isTerminated() {
        return executor.isTerminated();
    }

    private synchronized void executeWaitingTask() {
        if (executor.isShutdown()) {
            return;
        }

        int poolAvailable = corePoolSize-poolSize.get();
        Log.d(TAG, "executeWaitingTask: " + poolAvailable);
        if (poolAvailable > 1) {
            final Task nextTask = awaitingTasks.poll();
            if (nextTask != null) {
                poolSize.incrementAndGet();
                executor.submit(() -> {
                    try {
                        nextTask.task.run();
                    } finally {
                        Log.w(TAG, "taskHandler Finish");
                        poolSize.decrementAndGet();
                        executeWaitingTask();
                    }
                });
            }
        }
    }

    private static class Task {
        GeckoInspectTask task;
        int priority;
        int tabId;

        public Task(GeckoInspectTask task, int priority, int tabId) {
            this.task = task;
            this.priority = priority;
            this.tabId = tabId;
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

package com.solarized.firedown.ffmpegutils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The JNI side of FFmpegGifMaker and FFmpegEncoder. The Java classes are the
 * REAL ones, copied from app/src with each {@code private native} method
 * rewritten to call the method of the same name here (tools/Prepare.java),
 * so their locking (interrupt vs free under mNativeLock, the freed flag) is
 * what runs. This side mirrors app/src/main/cpp/ffmpegutils:
 * <ul>
 * <li>init allocates the struct and stores it in the object's field
 *     ({@link #FIELD}); dealloc sets interrupt, takes mutex_operation (so it
 *     waits for a running start), clears the field, frees;</li>
 * <li>start runs under mutex_operation, in steps, calling the Java progress
 *     callback (as the native loop does, on the starting thread) and
 *     returning a negative code at the first step that sees interrupt;</li>
 * <li>gifmaker.c: an interrupt set BEFORE start is kept and start returns
 *     -ERROR_INTERRUPTED at once (the flag is never cleared);</li>
 * <li>encoder.c: an interrupt set before start returns -ERROR_COULD_NOT_READ;
 *     otherwise start clears the flag once, then runs;</li>
 * <li>interrupt only writes the flag (no lock), reading the struct pointer
 *     first: a dealloc between the read and the write is a use-after-free,
 *     counted in {@link #USE_AFTER_FREE};</li>
 * <li>stop sets interrupt, then takes mutex_operation and releases the
 *     pipeline; with the field already cleared it does nothing.</li>
 * </ul>
 */
public final class FakeNative {

    public static final int ERROR_INTERRUPTED = -20;
    public static final int ERROR_COULD_NOT_READ = -11;
    public static final int ERROR_OPEN_INPUT = -5;
    public static final int ERROR_PATHS = -3;
    public static final int ERROR_ALLOCATE = -2;
    public static final int ERROR_STEP = -30;

    public static final class Config {
        public volatile int steps = 8;
        public volatile long stepMs = 5;
        public volatile int initResult = 0;
        /** Return ERROR_STEP at this step (a codec error, not an interrupt). */
        public volatile int failAtStep = -1;
        /** Runs inside the Java constructor (initX), before the task can
         *  store the object in its field. */
        public volatile Runnable onInit;
        public void reset() { steps = 8; stepMs = 5; initResult = 0; failAtStep = -1; onInit = null; }
    }

    public static final Config GIF = new Config();
    public static final Config ENC = new Config();

    public static final class State {
        public final String kind;
        final ReentrantLock op = new ReentrantLock();
        public volatile boolean interrupt, freed;
        public volatile int startCalls, stopCalls, interruptCalls, deallocCalls;
        public volatile String in, out, startThread;
        public volatile long startMs, endMs;
        public volatile int fps, width, video, audio;
        public volatile int stepsDone;
        public volatile boolean completed, interruptedBeforeStart;
        public volatile int result = Integer.MIN_VALUE;
        public volatile long startedAtNs, returnedAtNs;
        State(String kind) { this.kind = kind; }
        @Override public String toString() {
            return kind + "{start=" + startCalls + ", steps=" + stepsDone + ", completed=" + completed + ", result=" + result
                    + ", interrupt=" + interrupt + ", beforeStart=" + interruptedBeforeStart + ", freed=" + freed + "}";
        }
    }

    /** The Java object's native pointer field. */
    public static final Map<Object, State> FIELD = Collections.synchronizedMap(new IdentityHashMap<>());
    public static final List<State> ALL = Collections.synchronizedList(new ArrayList<>());
    public static final AtomicInteger USE_AFTER_FREE = new AtomicInteger();

    private FakeNative() {}

    public static List<State> since(int index) {
        synchronized (ALL) { return new ArrayList<>(ALL.subList(Math.min(index, ALL.size()), ALL.size())); }
    }

    /* ── shared ───────────────────────────────────────────────────────── */

    private static int init(Object self, String kind, Config c) {
        Runnable hook = c.onInit;
        if (hook != null) hook.run();
        if (c.initResult != 0) return c.initResult;
        State s = new State(kind);
        FIELD.put(self, s);
        ALL.add(s);
        return 0;
    }

    private static void interrupt(Object self) {
        State s = FIELD.get(self);
        if (s == null) return;
        Thread.yield();
        if (s.freed) USE_AFTER_FREE.incrementAndGet();
        s.interruptCalls++;
        s.interrupt = true;
    }

    private static void stop(Object self) {
        State s = FIELD.get(self);
        if (s == null) return;
        s.stopCalls++;
        s.interrupt = true;
        s.op.lock();
        s.op.unlock();
    }

    private static void dealloc(Object self) {
        State s = FIELD.get(self);
        if (s == null) return;
        s.deallocCalls++;
        s.interrupt = true;
        s.op.lock();
        try {
            FIELD.remove(self);
            Thread.yield();
            s.freed = true;
        } finally {
            s.op.unlock();
        }
    }

    private static void callback(Object self, String method, long a, long b) {
        try {
            Method m = self.getClass().getDeclaredMethod(method, long.class, long.class);
            m.setAccessible(true);
            m.invoke(self, a, b);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof RuntimeException r) throw r;
            if (t instanceof Error er) throw er;
            throw new RuntimeException(t);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("callback " + method + " missing on " + self.getClass(), e);
        }
    }

    private static boolean sleepStep(State s, long ms) {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (System.nanoTime() < end) {
            if (s.interrupt) return false;
            try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return !s.interrupt;
    }

    /** The native read/encode loop; returns 0 or the negative code. */
    private static int runLoop(Object self, State s, Config c, String progressCallback, int interruptCode, String out, byte[] header) {
        if (!new File(s.in).isFile()) return ERROR_OPEN_INPUT;
        if (s.interrupt) return interruptCode;
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(header);
            int steps = c.steps;
            for (int i = 1; i <= steps; i++) {
                if (s.interrupt || !sleepStep(s, c.stepMs)) {
                    fos.write(';');
                    return interruptCode;
                }
                if (i == c.failAtStep) return ERROR_STEP;
                fos.write(new byte[256]);
                s.stepsDone = i;
                callback(self, progressCallback, i * 1000L, steps * 1000L);
            }
            fos.write(';');
        } catch (IOException e) {
            return ERROR_PATHS;
        }
        s.completed = true;
        return 0;
    }

    /* ── gifmaker.c ───────────────────────────────────────────────────── */

    public static int initGifMaker(Object self) { return init(self, "gif", GIF); }

    public static int startGifMaker(Object self, String inputPath, String outputPath, long startMs, long endMs, int fps, int width) {
        State s = FIELD.get(self);
        if (s == null) return ERROR_ALLOCATE;
        if (inputPath == null || outputPath == null) return ERROR_PATHS;
        s.op.lock();
        try {
            s.startCalls++;
            s.startThread = Thread.currentThread().getName();
            s.startedAtNs = System.nanoTime();
            s.in = inputPath; s.out = outputPath; s.startMs = startMs; s.endMs = endMs; s.fps = fps; s.width = width;
            if (s.interrupt) {
                s.interruptedBeforeStart = true;
                s.result = ERROR_INTERRUPTED;
                return s.result;
            }
            s.result = runLoop(self, s, GIF, "gifMakerProgress", ERROR_INTERRUPTED, outputPath, "GIF89a".getBytes());
            return s.result;
        } finally {
            s.returnedAtNs = System.nanoTime();
            s.op.unlock();
        }
    }

    public static void stopGifMaker(Object self) { stop(self); }

    public static void interruptGifMaker(Object self) { interrupt(self); }

    public static void deallocGifMaker(Object self) { dealloc(self); }

    /* ── encoder.c ────────────────────────────────────────────────────── */

    public static int initEncoder(Object self) { return init(self, "encoder", ENC); }

    public static int startEncoder(Object self, Map<String, String> dictionary, Map<String, String> metadata,
                                   String fileName, String downloadUrl, int video, int audio) {
        State s = FIELD.get(self);
        if (s == null) return ERROR_ALLOCATE;
        if (fileName == null || downloadUrl == null) return ERROR_PATHS;
        s.op.lock();
        try {
            s.startCalls++;
            s.startThread = Thread.currentThread().getName();
            s.startedAtNs = System.nanoTime();
            s.in = fileName; s.out = downloadUrl; s.video = video; s.audio = audio;
            if (s.interrupt) {
                s.interruptedBeforeStart = true;
                s.result = ERROR_COULD_NOT_READ;
                return s.result;
            }
            s.interrupt = false;
            s.result = runLoop(self, s, ENC, "encoderProgress", ERROR_COULD_NOT_READ, downloadUrl, new byte[]{(byte) 0xFF, (byte) 0xF1});
            return s.result;
        } finally {
            s.returnedAtNs = System.nanoTime();
            s.op.unlock();
        }
    }

    public static void stopEncoder(Object self) {
        State s = FIELD.get(self);
        if (s == null) return;
        stop(self);
    }

    public static void interruptEncoder(Object self) { interrupt(self); }

    public static void deallocEncoder(Object self) { dealloc(self); }
}

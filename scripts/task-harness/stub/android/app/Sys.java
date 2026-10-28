package android.app;

import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.HandlerThread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.io.StringWriter;
import java.io.PrintWriter;

/**
 * The system side of a started service, as ActivityManager runs it, for ONE
 * service component (TaskManager):
 * <ul>
 * <li>startService assigns the startId at once, on the caller's thread (as
 *     system_server does), and delivers onCreate/onStartCommand later, in
 *     order, on the app's main thread; the service gets a parcelled COPY of
 *     the intent;</li>
 * <li>stopSelfResult(id) stops only when id is the newest startId handed
 *     out (delivered or not); stopSelf() stops unconditionally; a stopping
 *     record is gone at once (the next startService creates a new
 *     instance) and its onDestroy runs later on main, after its foreground
 *     notification was removed;</li>
 * <li>startForeground on a dead or stopping service is ignored; one with a
 *     type outside the manifest's throws IllegalArgumentException; on
 *     Android 12+ one from the background, or of a type whose daily quota is
 *     spent, throws ForegroundServiceStartNotAllowedException, unless the
 *     service is already foreground (an update);</li>
 * <li>startService from the background is refused unless the app has a
 *     foreground service or the start comes from a notification
 *     (PendingIntent: temporary allowlist).</li>
 * </ul>
 * An exception escaping a lifecycle callback is recorded in {@link #crashes}
 * (it would end the process on Android).
 */
public final class Sys {

    private static volatile Sys current;

    public static Sys get() {
        Sys s = current;
        if (s == null) throw new IllegalStateException("no system model installed");
        return s;
    }

    public static Sys install(Supplier<? extends Service> factory, int manifestFgsTypes) {
        Sys s = new Sys(factory, manifestFgsTypes);
        current = s;
        return s;
    }

    public static final class Record {
        public final int serial;
        public volatile Service instance;
        public int lastStartId;
        public volatile boolean stopping, destroyed;
        public volatile String stopReason;
        public volatile boolean foreground;
        public volatile Notification notification;
        public volatile int fgType;
        public volatile boolean everForeground;
        public final List<Notification> notifications = Collections.synchronizedList(new ArrayList<>());
        public final List<String> fgThreads = Collections.synchronizedList(new ArrayList<>());
        public final List<Integer> fgTypes = Collections.synchronizedList(new ArrayList<>());
        public volatile int startForegroundCalls, refusedForegroundCalls, stopForegroundCalls, deadServiceCalls;
        public final List<Integer> delivered = Collections.synchronizedList(new ArrayList<>());
        public final List<String> stopSelfCalls = Collections.synchronizedList(new ArrayList<>());
        public final CountDownLatch created = new CountDownLatch(1);
        public final CountDownLatch destroyedLatch = new CountDownLatch(1);
        Record(int serial) { this.serial = serial; }
        @Override public String toString() {
            return "S" + serial + "{lastStartId=" + lastStartId + ", fg=" + foreground + ", stopping=" + stopping
                    + ", destroyed=" + destroyed + ", reason=" + stopReason + "}";
        }
    }

    public final HandlerThread mainThread = new HandlerThread("main");
    public final Handler main;
    private final Supplier<? extends Service> factory;
    public final int manifestFgsTypes;
    private final Object lock = new Object();
    private Record live;
    private int serial;
    public final List<Record> records = Collections.synchronizedList(new ArrayList<>());
    public final List<String> crashes = Collections.synchronizedList(new ArrayList<>());
    public final List<String> log = Collections.synchronizedList(new ArrayList<>());
    public volatile boolean appInBackground;
    /** >0: refuse that many more startForeground calls; <0: refuse all. */
    public volatile int refuseForeground;
    /** Foreground types whose daily budget ran out (after onTimeout). */
    public final Set<Integer> exhaustedFgsTypes = Collections.synchronizedSet(new HashSet<>());

    private Sys(Supplier<? extends Service> factory, int manifestFgsTypes) {
        this.factory = factory;
        this.manifestFgsTypes = manifestFgsTypes;
        mainThread.start();
        main = new Handler(mainThread.getLooper());
        Looper.setMainLooper(mainThread.getLooper());
    }

    private void note(String s) { log.add(s); }

    private void crash(String where, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        crashes.add(where + ": " + sw);
    }

    /* ── starts ───────────────────────────────────────────────────────── */

    /** Context.startService: returns the startId it was given. */
    public int startService(Intent intent, boolean fromNotification) {
        Intent copy = intent != null ? intent.parcelCopy() : null;
        synchronized (lock) {
            if (appInBackground && !fromNotification && (live == null || !live.foreground)) {
                note("startService refused (background): " + intent);
                throw new IllegalStateException("Not allowed to start service " + intent + ": app is in background");
            }
            Record r = live;
            boolean create = r == null;
            if (create) {
                r = new Record(++serial);
                live = r;
                records.add(r);
            }
            int id = ++r.lastStartId;
            Record rr = r;
            if (create) main.post(() -> create(rr));
            main.post(() -> deliverStart(rr, copy, id));
            note("startService " + intent + " -> S" + r.serial + "#" + id);
            return id;
        }
    }

    /** A START_STICKY-style redelivery with a null intent. */
    public int startNull() {
        synchronized (lock) {
            Record r = live;
            boolean create = r == null;
            if (create) {
                r = new Record(++serial);
                live = r;
                records.add(r);
            }
            int id = ++r.lastStartId;
            Record rr = r;
            if (create) main.post(() -> create(rr));
            main.post(() -> deliverStart(rr, null, id));
            return id;
        }
    }

    /** The user tapping a notification action / content. */
    public int send(PendingIntent pi) {
        if (!"service".equals(pi.kind)) {
            note("activity launched: " + pi.intent);
            return -1;
        }
        return startService(pi.intent, true);
    }

    /** Not something Android does on its own for a live record: a second
     *  instance beside the first, for the static-runnable check. */
    public Record forceNewInstance() {
        synchronized (lock) {
            Record r = new Record(++serial);
            live = r;
            records.add(r);
            main.post(() -> create(r));
            return r;
        }
    }

    private void create(Record r) {
        try {
            Service s = factory.get();
            s.record = r;
            r.instance = s;
            s.onCreate();
        } catch (Throwable t) {
            crash("onCreate S" + r.serial, t);
        } finally {
            r.created.countDown();
        }
    }

    private void deliverStart(Record r, Intent intent, int id) {
        if (r.destroyed || r.instance == null) {
            note("start #" + id + " for dead S" + r.serial + " dropped");
            return;
        }
        r.delivered.add(id);
        try {
            r.instance.onStartCommand(intent, 0, id);
        } catch (Throwable t) {
            crash("onStartCommand S" + r.serial + "#" + id, t);
        }
    }

    /* ── stops ────────────────────────────────────────────────────────── */

    boolean stopSelfResult(Service s, int startId) {
        synchronized (lock) {
            Record r = s.record;
            boolean ok;
            if (r == null || r.stopping) {
                ok = false;
            } else if (startId >= 0 && startId != r.lastStartId) {
                ok = false;
            } else {
                ok = true;
                beginStop(r, startId < 0 ? "stopSelf()" : "stopSelfResult(" + startId + ")");
            }
            if (r != null) r.stopSelfCalls.add(startId + (ok ? ":stopped" : ":kept") + "@" + Thread.currentThread().getName());
            note("stopSelfResult(" + startId + ") on " + r + " -> " + ok);
            return ok;
        }
    }

    /** The idle-uid / background-service limit stop Android applies to a
     *  started (not foreground) service about a minute after the app left
     *  the screen. */
    public void systemStop(Record r) {
        synchronized (lock) {
            if (!r.stopping) beginStop(r, "system");
        }
    }

    private void beginStop(Record r, String reason) {
        r.stopping = true;
        r.stopReason = reason;
        if (live == r) live = null;
        main.post(() -> destroy(r));
    }

    private void destroy(Record r) {
        synchronized (lock) {
            if (r.foreground) {
                r.foreground = false;
                r.notification = null;
                note("S" + r.serial + " destroyed while foreground: notification removed by the system");
            }
        }
        try {
            if (r.instance != null) r.instance.onDestroy();
        } catch (Throwable t) {
            crash("onDestroy S" + r.serial, t);
        } finally {
            r.destroyed = true;
            r.destroyedLatch.countDown();
        }
    }

    /* ── foreground ───────────────────────────────────────────────────── */

    void startForeground(Service s, int id, Notification n, int type) {
        synchronized (lock) {
            Record r = s.record;
            if (r == null) throw new IllegalStateException("service not attached");
            r.startForegroundCalls++;
            r.fgThreads.add(Thread.currentThread().getName());
            if (r.stopping || r.destroyed) {
                r.deadServiceCalls++;
                note("startForeground on dead S" + r.serial + " ignored");
                return;
            }
            int effective = type < 0 ? manifestFgsTypes : type;
            if (Build.VERSION.SDK_INT >= 29 && (effective & ~manifestFgsTypes) != 0) {
                throw new IllegalArgumentException("foregroundServiceType 0x" + Integer.toHexString(effective)
                        + " is not a subset of foregroundServiceType attribute 0x" + Integer.toHexString(manifestFgsTypes));
            }
            if (refuseForeground != 0) {
                if (refuseForeground > 0) refuseForeground--;
                r.refusedForegroundCalls++;
                throw new ForegroundServiceStartNotAllowedException("startForeground refused (harness)");
            }
            /* Android 15's spent daily budget only bites while the app is in
             * the background (bringing it forward resets it), so the
             * background rule covers it. */
            if (Build.VERSION.SDK_INT >= 31 && !r.foreground && appInBackground) {
                r.refusedForegroundCalls++;
                throw new ForegroundServiceStartNotAllowedException("startForeground from the background");
            }
            r.foreground = true;
            r.everForeground = true;
            r.notification = n;
            r.fgType = effective;
            r.fgTypes.add(effective);
            r.notifications.add(n);
        }
    }

    void stopForeground(Service s, int flags) {
        synchronized (lock) {
            Record r = s.record;
            if (r == null) return;
            r.stopForegroundCalls++;
            if (r.destroyed || r.stopping) {
                r.deadServiceCalls++;
                return;
            }
            r.foreground = false;
            if ((flags & Service.STOP_FOREGROUND_REMOVE) != 0) r.notification = null;
        }
    }

    /** Android 14 (onTimeout(int)) / 15 (onTimeout(int,int)): the daily
     *  budget of a time-limited foreground type ran out. Returns once the
     *  callback ran on main. */
    public boolean timeout(Record r, int fgsType, long waitMs) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            try {
                exhaustedFgsTypes.add(fgsType);
                Service s = r.instance;
                int startId;
                synchronized (lock) { startId = r.lastStartId; }
                if (Build.VERSION.SDK_INT >= 35) {
                    s.onTimeout(startId, fgsType);
                } else {
                    s.onTimeout(startId);
                }
            } catch (Throwable t) {
                crash("onTimeout S" + r.serial, t);
            } finally {
                done.countDown();
            }
        });
        return done.await(waitMs, TimeUnit.MILLISECONDS);
    }

    /* ── main thread ──────────────────────────────────────────────────── */

    public Record live() {
        synchronized (lock) { return live; }
    }

    /** Everything posted to main so far has run. */
    public boolean awaitMainIdle(long ms) throws InterruptedException {
        if (Thread.currentThread() == mainThread) throw new IllegalStateException("awaitMainIdle on main");
        CountDownLatch l = new CountDownLatch(1);
        main.post(l::countDown);
        return l.await(ms, TimeUnit.MILLISECONDS);
    }

    /** Blocks main until the returned latch is released (or 10 s pass). */
    public CountDownLatch holdMain() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = new CountDownLatch(1);
        main.post(() -> {
            held.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        });
        held.await(5, TimeUnit.SECONDS);
        return release;
    }
}

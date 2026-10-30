package com.solarized.firedown.harness;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Sys;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;

import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.data.repository.TaskRepository;
import com.solarized.firedown.ffmpegutils.FFmpegThumbnailer;
import com.solarized.firedown.ffmpegutils.FakeNative;
import com.solarized.firedown.manager.ServiceActions;
import com.solarized.firedown.manager.tasks.HarnessAccess;
import com.solarized.firedown.manager.tasks.TaskManager;
import com.solarized.firedown.utils.GalleryPublisher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.StringWriter;
import java.io.PrintWriter;
import android.os.Build;
import java.util.Arrays;

/** The harness's world: the system model, the real repositories, the event
 *  probes, fixtures and waits. Shared by the suite and the stress. */
final class W {

    private W() {}

    /* ── reporting ─────────────────────────────────────────────────────── */

    static int pass, fail;

    static boolean check(String id, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("PASS  " + id + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        } else {
            fail++;
            System.out.println("FAIL  " + id + "  [" + detail + "]");
        }
        return ok;
    }

    static void info(String id, String msg) {
        System.out.println("INFO  " + id + "  " + msg);
    }

    /* ── world ─────────────────────────────────────────────────────────── */

    record Ev(TaskEvent e, String thread, long t, boolean wake, boolean inFlight, boolean fg) {
        boolean started() { return e instanceof TaskEvent.Started; }
        boolean progress() { return e instanceof TaskEvent.Progress; }
        boolean finished() { return e instanceof TaskEvent.Finished; }
        ServiceActions action() {
            if (e instanceof TaskEvent.Started s) return s.getAction();
            if (e instanceof TaskEvent.Finished f) return f.getAction();
            return null;
        }
        Object result() { return e instanceof TaskEvent.Finished f ? f.getResult() : null; }
        @Override public String toString() {
            if (started()) return "Started(" + action() + ")";
            if (progress()) return "Progress(" + ((TaskEvent.Progress) e).getPercent() + ")";
            if (finished()) return "Finished(" + action() + "," + resultText(result()) + ")";
            return e.getClass().getSimpleName();
        }
    }

    static String resultText(Object r) {
        if (r instanceof DownloadEntity d) return "entity:" + new File(d.getFilePath()).getName();
        return String.valueOf(r);
    }

    static Sys sys;
    static TaskRepository taskRepo;
    static DownloadDataRepository repo;
    static final List<Ev> events = Collections.synchronizedList(new ArrayList<>());
    static final List<TaskEvent> ui = Collections.synchronizedList(new ArrayList<>());
    /** What a screen sees on main, in order: "ev:<event>" from the events,
     *  "run:<action>:<percent>" / "run:null" from the running task. */
    static final List<String> timeline = Collections.synchronizedList(new ArrayList<>());
    /** One test hook at a time: runs on the posting (worker) thread, before
     *  the event is stored in the LiveData. */
    static volatile Consumer<TaskEvent> onEvent;
    static final Context ctx = new Context();
    static File root, dl, safe;
    static String CANCEL_CURRENT;
    static final int MANIFEST_TYPES = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING;
    static final Random rnd = new Random(7);

    static void boot() throws IOException {
        root = Files.createTempDirectory("task-harness").toFile();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(root)));
        repo = new DownloadDataRepository();
        sys = Sys.install(() -> HarnessAccess.newTaskManager(repo, taskRepo), MANIFEST_TYPES);
        LiveData.mainPoster = r -> sys.main.post(r);
        // After Sys: the repository takes the main looper when it is built.
        // Every event the tasks produce is recorded as it is sent, on the
        // sending thread, before the real repository hands it to main.
        taskRepo = new TaskRepository() {
            @Override
            public void sendEvent(TaskEvent e) {
                events.add(new Ev(e, Thread.currentThread().getName(), System.nanoTime(),
                        PowerManager.anyHeld(), isTaskInFlight(), anyForeground()));
                Consumer<TaskEvent> h = onEvent;
                if (h != null) h.accept(e);
                super.sendEvent(e);
            }
        };
        LifecycleOwner owner = new LifecycleOwner() {};
        taskRepo.getObservableEvent().observe(owner, e -> {
            ui.add(e);
            timeline.add("ev:" + e.getClass().getSimpleName());
        });
        taskRepo.getRunningTask().observe(owner, t -> timeline.add(t == null ? "run:null"
                : "run:" + t.getAction() + ":" + t.getPercent()));
        CANCEL_CURRENT = HarnessAccess.cancelCurrentAction();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            Looper.UNCAUGHT.add(t.getName() + ": " + sw);
        });
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    static boolean anyForeground() {
        synchronized (sys.records) { for (Sys.Record r : sys.records) if (r.foreground) return true; }
        return false;
    }

    /** Fresh download + safe folders for one case. */
    static void dirs(String name) {
        File base = new File(root, name.replaceAll("[^A-Za-z0-9.-]", "_"));
        dl = new File(base, "Download/Firedown");
        safe = new File(base, "files/safe");
        dl.mkdirs();
        safe.mkdirs();
        StoragePaths.downloadDir = dl.getAbsolutePath();
        StoragePaths.safeDir = safe.getAbsolutePath();
    }

    static void resetFakes() {
        FakeNative.GIF.reset();
        FakeNative.ENC.reset();
        MediaMetadataRetriever.delayMs = 5;
        MediaMetadataRetriever.returnNull = false;
        MediaMetadataRetriever.onGetFrame = null;
        FFmpegThumbnailer.returnBitmap = true;
        Bitmap.compressFails = false;
        PowerManager.onAcquire = null;
        onEvent = null;
        sys.refuseForeground = 0;
        sys.appInBackground = false;
        Build.VERSION.SDK_INT = 35;
    }

    static void clearLog() {
        events.clear();
        ui.clear();
        repo.clear();
        GalleryPublisher.PUBLISHED.clear();
        GalleryPublisher.FORGOTTEN.clear();
    }

    /* ── the seven tasks ───────────────────────────────────────────────── */

    enum K {
        GIF("gif", IntentActions.DOWNLOAD_START_MAKE_GIF, IntentActions.DOWNLOAD_CANCEL_MAKE_GIF,
                ServiceActions.MAKE_GIF, ServiceActions.CANCEL_MAKE_GIF, ServiceActions.ERROR_MAKE_GIF,
                "task_notification_gif", "ic_gif_box_24", true, true),
        AUDIO("audio", IntentActions.DOWNLOAD_START_AUDIO_ENCODE, IntentActions.DOWNLOAD_CANCEL_AUDIO_ENCODE,
                ServiceActions.AUDIO_ENCODE, ServiceActions.CANCEL_AUDIO_ENCODE, ServiceActions.ERROR_AUDIO_ENCODE,
                "task_notification_audio", "ic_headphones_24", true, true),
        FRAME("frame", IntentActions.DOWNLOAD_START_SAVE_FRAME, IntentActions.DOWNLOAD_CANCEL_SAVE_FRAME,
                ServiceActions.SAVE_FRAME, ServiceActions.CANCEL_SAVE_FRAME, ServiceActions.ERROR_SAVE_FRAME,
                "task_notification_frame", "ic_photo_camera_24", true, false),
        COMPRESS("compress", IntentActions.DOWNLOAD_START_COMPRESS, IntentActions.DOWNLOAD_CANCEL_COMPRESS,
                ServiceActions.COMPRESS, ServiceActions.CANCEL_COMPRESS, ServiceActions.ERROR_COMPRESS,
                "task_notification_compress", "ic_baseline_archive_24", false, true),
        EXTRACT("extract", IntentActions.DOWNLOAD_START_EXTRACT, IntentActions.DOWNLOAD_CANCEL_EXTRACT,
                ServiceActions.EXTRACT, ServiceActions.CANCEL_EXTRACT, ServiceActions.ERROR_EXTRACT,
                "task_notification_extract", "ic_baseline_unarchive_24", false, true),
        LOCK("lock", IntentActions.START_ENCRYPTION, IntentActions.CANCEL_ENCRYPTION,
                ServiceActions.ENCRYPTION, null, null, "task_notification_lock", "ic_lock_24", false, true),
        UNLOCK("unlock", IntentActions.START_DECRYPTION, IntentActions.CANCEL_DECRYPTION,
                ServiceActions.DECRYPTION, null, null, "task_notification_unlock", "ic_lock_open_right_24", false, true);

        final String tag, start, cancel, title, icon;
        final ServiceActions ok, cancelled, error;
        final boolean media, progress;

        K(String tag, String start, String cancel, ServiceActions ok, ServiceActions cancelled, ServiceActions error,
          String title, String icon, boolean media, boolean progress) {
            this.tag = tag; this.start = start; this.cancel = cancel; this.ok = ok; this.cancelled = cancelled;
            this.error = error; this.title = title; this.icon = icon; this.media = media; this.progress = progress;
        }

        boolean family(ServiceActions a) { return a != null && (a == ok || a == cancelled || a == error); }

        /** Vault tasks have no cancel result: a cancel ends as their
         *  success action with the number of files done. */
        boolean vault() { return this == LOCK || this == UNLOCK; }

        int fgType() {
            return media && Build.VERSION.SDK_INT >= 35
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        }

        static K ofAction(ServiceActions a) {
            for (K k : values()) if (k.family(a)) return k;
            return null;
        }
    }

    /** One start: its intent, inputs and the outputs a success leaves. */
    static final class Plan {
        final K k;
        final String tag;
        final Intent intent;
        final List<File> inputs = new ArrayList<>();
        final List<byte[]> inputBytes = new ArrayList<>();
        final List<DownloadEntity> entities = new ArrayList<>();
        final List<File> outputs = new ArrayList<>();
        long gifStart = 1000, gifEnd = 3000, framePosMs = 1500;
        int gifFps = 10, gifWidth = 240;
        int startId;
        Plan(K k, String tag) { this.k = k; this.tag = tag; this.intent = new Intent(ctx, TaskManager.class).setAction(k.start); }
    }

    static byte[] bytes(int n) {
        byte[] b = new byte[n];
        rnd.nextBytes(b);
        return b;
    }

    static File file(File dir, String name, byte[] content) throws IOException {
        File f = new File(dir, name);
        Files.write(f.toPath(), content);
        return f;
    }

    static int ids = 100;

    static DownloadEntity entity(File f, String mime, boolean safeFlag) {
        DownloadEntity e = new DownloadEntity();
        e.setId(++ids);
        e.setFileName(f.getName());
        e.setFilePath(f.getAbsolutePath());
        e.setFileMimeType(mime);
        e.setFileSize(f.length());
        e.setFileStatus(Download.FINISHED);
        e.setFileOriginUrl("https://example.com/watch/" + ids);
        e.setFileSafe(safeFlag);
        return e;
    }

    /** n = number of files for the multi-file tasks (compress, lock, unlock). */
    static Plan plan(K k, String tag, int n, int size) throws IOException {
        Plan p = new Plan(k, tag);
        switch (k) {
            case GIF, AUDIO, FRAME -> {
                byte[] b = bytes(size);
                File f = file(dl, tag + ".mp4", b);
                p.inputs.add(f); p.inputBytes.add(b);
                p.entities.add(entity(f, "video/mp4", false));
                String out = switch (k) {
                    case GIF -> tag + ".gif";
                    case AUDIO -> tag + ".aac";
                    default -> tag + "_frame.jpg";
                };
                p.outputs.add(new File(dl, out));
            }
            case COMPRESS -> {
                for (int i = 1; i <= n; i++) {
                    byte[] b = bytes(size);
                    File f = file(dl, tag + "-" + i + ".mp4", b);
                    p.inputs.add(f); p.inputBytes.add(b);
                    p.entities.add(entity(f, "video/mp4", false));
                }
                p.outputs.add(new File(dl, tag + "-1.zip"));
            }
            case EXTRACT -> {
                File zip = new File(dl, tag + ".zip");
                try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
                    for (int i = 1; i <= Math.max(1, n); i++) {
                        byte[] b = bytes(size);
                        z.putNextEntry(new ZipEntry("inner/" + tag + "-entry" + i + ".bin"));
                        z.write(b);
                        z.closeEntry();
                        p.inputBytes.add(b);
                        p.outputs.add(new File(dl, tag + "-entry" + i + ".bin"));
                    }
                }
                p.inputs.add(zip);
                p.entities.add(entity(zip, "application/zip", false));
            }
            case LOCK, UNLOCK -> {
                File from = k == K.LOCK ? dl : safe;
                File to = k == K.LOCK ? safe : dl;
                for (int i = 1; i <= n; i++) {
                    byte[] b = bytes(size);
                    File f = file(from, tag + "-" + i + ".mp4", b);
                    p.inputs.add(f); p.inputBytes.add(b);
                    p.entities.add(entity(f, "video/mp4", k == K.UNLOCK));
                    p.outputs.add(new File(to, f.getName()));
                }
            }
        }
        p.intent.putParcelableArrayListExtra(Keys.ITEM_LIST_ID, new ArrayList<>(p.entities));
        if (k == K.GIF) {
            p.intent.putExtra(Keys.GIF_START_MS, p.gifStart);
            p.intent.putExtra(Keys.GIF_END_MS, p.gifEnd);
            p.intent.putExtra(Keys.GIF_FPS, p.gifFps);
            p.intent.putExtra(Keys.GIF_WIDTH, p.gifWidth);
        }
        if (k == K.FRAME) p.intent.putExtra(Keys.FRAME_POSITION_MS, p.framePosMs);
        return p;
    }

    static Plan gifPlan(String tag, long startMs, long endMs, int fps, int width) throws IOException {
        Plan p = plan(K.GIF, tag, 1, 32 * 1024);
        p.gifStart = startMs; p.gifEnd = endMs; p.gifFps = fps; p.gifWidth = width;
        p.intent.putExtra(Keys.GIF_START_MS, startMs);
        p.intent.putExtra(Keys.GIF_END_MS, endMs);
        p.intent.putExtra(Keys.GIF_FPS, fps);
        p.intent.putExtra(Keys.GIF_WIDTH, width);
        return p;
    }

    static int start(Plan p) {
        p.startId = sys.startService(p.intent, false);
        return p.startId;
    }

    static Intent cancelIntent(String action) {
        return new Intent(ctx, TaskManager.class).setAction(action);
    }

    /** The notification's Cancel, tapped. Falls back to the same intent when
     *  no notification is up (foreground refused). Returns how it was sent. */
    static String cancelViaNotification() {
        Sys.Record r = sys.live();
        Notification n = null;
        if (r != null) n = r.notification;
        if (n == null) {
            synchronized (sys.records) {
                for (Sys.Record x : sys.records) if (x.notification != null) n = x.notification;
            }
        }
        if (n != null) {
            for (Notification.Action a : n.actions) {
                PendingIntent pi = a.intent;
                if (pi != null && "service".equals(pi.kind) && pi.intent != null && CANCEL_CURRENT.equals(pi.intent.getAction())) {
                    sys.send(pi);
                    return "notification";
                }
            }
        }
        sys.startService(cancelIntent(CANCEL_CURRENT), true);
        return "intent(no notification)";
    }

    /** From a hook on the worker: the cancel lands on main, and the hook
     *  returns only once main has handled it. */
    /** How the last cancelAndWait was sent. */
    static volatile String lastCancelRoute = "";

    static long cancelAndWait(String action, boolean viaNotification) {
        if (viaNotification) lastCancelRoute = cancelViaNotification();
        else {
            sys.startService(cancelIntent(action), false);
            lastCancelRoute = "intent " + action.substring(action.lastIndexOf('.') + 1);
        }
        try {
            if (!sys.awaitMainIdle(3000)) info("hook", "main did not run the cancel within 3 s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return System.nanoTime();
    }

    /** A hook that fires once. */
    static Consumer<TaskEvent> once(Predicate<TaskEvent> when, Runnable action) {
        AtomicBoolean fired = new AtomicBoolean();
        return e -> {
            if (when.test(e) && fired.compareAndSet(false, true)) action.run();
        };
    }

    /* ── waits ─────────────────────────────────────────────────────────── */

    static boolean waitUntil(BooleanSupplier c, long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) return false;
            try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return true;
    }

    static List<Ev> evs() { synchronized (events) { return new ArrayList<>(events); } }

    static long count(Predicate<Ev> p) { return evs().stream().filter(p).count(); }

    static boolean awaitFinished(int n, long ms) { return waitUntil(() -> count(Ev::finished) >= n, ms); }

    static Looper workerLooper() {
        synchronized (HandlerThread.ALL) {
            for (HandlerThread t : HandlerThread.ALL) if ("TaskManager".equals(t.getName())) return t.getLooper();
        }
        return null;
    }

    static boolean workerIdle() {
        Looper l = workerLooper();
        return l == null || l.isIdle();
    }

    /** Worker idle, main idle, and every stopping record destroyed. */
    static boolean quiesce(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (workerIdle() && sys.awaitMainIdle(1000) && workerIdle()) {
                    boolean settled = true;
                    synchronized (sys.records) {
                        for (Sys.Record r : sys.records) if (r.stopping && !r.destroyed) settled = false;
                    }
                    if (settled && sys.awaitMainIdle(1000) && workerIdle()) return true;
                }
                Thread.sleep(3);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    static Sys.Record recordAt(int index) {
        synchronized (sys.records) { return index < sys.records.size() ? sys.records.get(index) : null; }
    }

    /* ── probes ────────────────────────────────────────────────────────── */

    static String crashes() {
        List<String> all = new ArrayList<>();
        synchronized (Looper.UNCAUGHT) { all.addAll(Looper.UNCAUGHT); }
        synchronized (sys.crashes) { all.addAll(sys.crashes); }
        if (all.isEmpty()) return "";
        String first = all.get(0);
        int nl = first.indexOf('\n', first.indexOf('\n') + 1);
        return all.size() + " exception(s), first: " + (nl > 0 ? first.substring(0, nl).replace('\n', ' ') : first);
    }

    static int crashCount() { return Looper.UNCAUGHT.size() + sys.crashes.size(); }

    /** Open file descriptors of this process under {@code dir}. */
    static List<String> fdsUnder(File dir) {
        List<String> out = new ArrayList<>();
        File[] fds = new File("/proc/self/fd").listFiles();
        if (fds == null) return out;
        String prefix = dir.getAbsolutePath();
        for (File fd : fds) {
            try {
                Path target = Files.readSymbolicLink(fd.toPath());
                if (target.toString().startsWith(prefix)) out.add(target.toString().substring(prefix.length()));
            } catch (IOException ignored) { }
        }
        return out;
    }

    static int res(String type, String name) {
        try {
            return Class.forName("com.solarized.firedown.R$" + type).getField(name).getInt(null);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    static String seq(List<Ev> list) {
        StringBuilder sb = new StringBuilder();
        int progress = 0;
        for (Ev e : list) {
            if (e.progress()) { progress++; continue; }
            if (progress > 0) { sb.append("Progress x").append(progress).append(' '); progress = 0; }
            sb.append(e).append(' ');
        }
        if (progress > 0) sb.append("Progress x").append(progress);
        return sb.toString().trim();
    }

    static String files(File dir) {
        String[] n = dir.list();
        if (n == null) return "[]";
        Arrays.sort(n);
        return Arrays.toString(n);
    }

    static void quietLogs() { Log.echo = false; }
}

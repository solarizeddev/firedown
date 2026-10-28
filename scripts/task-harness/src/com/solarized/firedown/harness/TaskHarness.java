package com.solarized.firedown.harness;

import static com.solarized.firedown.harness.W.*;

import android.app.Notification;
import android.app.Sys;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;

import com.solarized.firedown.App;
import com.solarized.firedown.Keys;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.ffmpegutils.FakeNative;
import com.solarized.firedown.manager.ServiceActions;
import com.solarized.firedown.manager.tasks.TaskManager;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.utils.GalleryPublisher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.LinkedHashSet;
import java.util.Enumeration;
import com.solarized.firedown.ffmpegutils.FFmpegThumbnailer;
import java.util.concurrent.TimeUnit;

/**
 * The REAL TaskManager, TaskRunnable and the seven tasks, driven through
 * intents delivered by a model of ActivityManager (android.app.Sys) and
 * observed through the real TaskRepository, the recording repositories, the
 * foreground notification and the fake JNI side. One group per JVM (run.sh).
 */
public class TaskHarness {

    static final long WAIT = 8000;

    /** Per-case bookkeeping: everything created from here on is this case's. */
    static final class Case {
        final String id;
        final int recIdx, nativeIdx, crash0, mmrIdx, bitmapIdx;
        Case(String id) {
            quiesce(WAIT);
            this.id = id;
            dirs(id);
            resetFakes();
            clearLog();
            recIdx = sys.records.size();
            nativeIdx = FakeNative.ALL.size();
            crash0 = crashCount();
            mmrIdx = MediaMetadataRetriever.REQUESTS_US.size();
            bitmapIdx = Bitmap.ALL.size();
        }
        Sys.Record rec() { return recordAt(recIdx); }
        Sys.Record rec(int n) { return recordAt(recIdx + n); }
        int records() { return sys.records.size() - recIdx; }
        List<FakeNative.State> natives() { return FakeNative.since(nativeIdx); }
        String newCrashes() {
            List<String> all = new ArrayList<>();
            synchronized (Looper.UNCAUGHT) { all.addAll(Looper.UNCAUGHT); }
            synchronized (sys.crashes) { all.addAll(sys.crashes); }
            if (all.size() <= crash0) return "";
            String first = all.get(crash0);
            String[] lines = first.split("\n");
            return (all.size() - crash0) + " exception(s): " + lines[0] + (lines.length > 1 ? " " + lines[1].trim() : "")
                    + (lines.length > 2 ? " " + lines[2].trim() : "");
        }
        boolean clean() { return crashCount() == crash0; }

        /** The state every case must end in. */
        void endState() { endState(true); }

        /** {@code checkExceptions} false: the case asserted them itself. */
        void endState(boolean checkExceptions) {
            boolean settled = quiesce(WAIT);
            Sys.Record r = rec();
            check(id + ".in-flight-cleared", !taskRepo.isTaskInFlight(), "isTaskInFlight=" + taskRepo.isTaskInFlight());
            check(id + ".foreground-left", r != null && !anyForeground() && r.notification == null,
                    String.valueOf(r));
            check(id + ".wake-lock-released", !PowerManager.anyHeld(), "held=" + PowerManager.anyHeld());
            boolean allStopped = true;
            for (int i = 0; i < records(); i++) if (!rec(i).destroyed) allStopped = false;
            check(id + ".service-stopped", settled && allStopped, records() + " instance(s): " + recordsText());
            if (checkExceptions) check(id + ".no-exception", clean(), newCrashes());
        }
        String recordsText() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < records(); i++) sb.append(rec(i)).append(' ');
            return sb.toString().trim();
        }
    }

    static Ev first(Class<? extends TaskEvent> type) {
        for (Ev e : evs()) if (type.isInstance(e.e())) return e;
        return null;
    }

    static List<Ev> all(Class<? extends TaskEvent> type) {
        List<Ev> out = new ArrayList<>();
        for (Ev e : evs()) if (type.isInstance(e.e())) out.add(e);
        return out;
    }

    static String notificationProblems(Sys.Record r, K k) {
        List<String> p = new ArrayList<>();
        if (r == null || r.notifications.isEmpty()) return "never went foreground";
        Notification n = r.notifications.get(0);
        if (!k.title.equals(String.valueOf(n.title))) p.add("title=" + n.title + " want " + k.title);
        if (n.smallIcon != res("drawable", k.icon)) p.add("icon=0x" + Integer.toHexString(n.smallIcon) + " want " + k.icon);
        if (!App.DOWNLOADS_NOTIFICATION_ID.equals(n.channelId)) p.add("channel=" + n.channelId);
        if (!n.ongoing) p.add("not ongoing");
        boolean cancel = false;
        for (Notification.Action a : n.actions) {
            if (a.intent != null && "service".equals(a.intent.kind) && a.intent.intent != null
                    && CANCEL_CURRENT.equals(a.intent.intent.getAction())
                    && a.intent.intent.getComponentClass() == TaskManager.class) cancel = true;
        }
        if (!cancel) p.add("no Cancel action starting TaskManager with " + CANCEL_CURRENT);
        if (n.contentIntent == null || !"activity".equals(n.contentIntent.kind)
                || n.contentIntent.intent.getComponentClass() != DownloadsActivity.class) p.add("tap does not open DownloadsActivity");
        for (int t : new ArrayList<>(r.fgTypes)) if (t != k.fgType()) p.add("fgType=0x" + Integer.toHexString(t) + " want 0x" + Integer.toHexString(k.fgType()));
        return String.join("; ", new LinkedHashSetList(p));
    }

    /** Dedup for problem lists. */
    static final class LinkedHashSetList extends ArrayList<String> {
        LinkedHashSetList(List<String> in) { super(new LinkedHashSet<>(in)); }
    }

    static boolean sameBytes(File f, byte[] want) {
        try { return f.isFile() && Arrays.equals(Files.readAllBytes(f.toPath()), want); } catch (IOException e) { return false; }
    }

    /** "" when the success outputs are right. */
    static String successProblems(Case c, Plan p, Ev fin) {
        List<String> pr = new ArrayList<>();
        Object res = fin.result();
        switch (p.k) {
            case GIF, AUDIO, FRAME -> {
                if (!(res instanceof DownloadEntity e)) { pr.add("result " + res + " is not an entity"); break; }
                File out = p.outputs.get(0);
                if (!out.getAbsolutePath().equals(e.getFilePath())) pr.add("path " + e.getFilePath() + " want " + out);
                if (!out.isFile() || out.length() == 0) pr.add("output missing or empty");
                if (e.getFileSize() != out.length()) pr.add("size " + e.getFileSize() + " vs file " + out.length());
                if (e.getFileStatus() != Download.FINISHED) pr.add("status " + e.getFileStatus());
                String mime = p.k == K.GIF ? "image/gif" : p.k == K.AUDIO ? "audio/aac" : "image/jpeg";
                if (!mime.equals(e.getFileMimeType())) pr.add("mime " + e.getFileMimeType());
                if (repo.added.size() != 1 || !out.getAbsolutePath().equals(repo.added.get(0).getFilePath())) pr.add("repo rows " + repo.added);
                if (!GalleryPublisher.PUBLISHED.contains(out.getAbsolutePath())) pr.add("not published to the gallery");
                if (p.k == K.FRAME) {
                    if (!"1280x720".equals(e.getFileResolution())) pr.add("resolution " + e.getFileResolution());
                    if (!p.entities.get(0).getOriginUrl().equals(e.getOriginUrl())) pr.add("origin not carried");
                    List<Long> req = MediaMetadataRetriever.REQUESTS_US.subList(c.mmrIdx, MediaMetadataRetriever.REQUESTS_US.size());
                    if (req.isEmpty() || req.get(req.size() - 1) != p.framePosMs * 1000L) pr.add("grabbed at " + req + " want " + p.framePosMs * 1000L);
                    for (Bitmap b : Bitmap.ALL.subList(c.bitmapIdx, Bitmap.ALL.size())) if (!b.isRecycled()) pr.add("bitmap not recycled");
                } else {
                    List<FakeNative.State> st = c.natives();
                    if (st.size() != 1) { pr.add(st.size() + " native objects"); break; }
                    FakeNative.State s = st.get(0);
                    if (!p.inputs.get(0).getAbsolutePath().equals(s.in)) pr.add("native input " + s.in);
                    if (!out.getAbsolutePath().equals(s.out)) pr.add("native output " + s.out);
                    if (!s.completed) pr.add("native did not complete: " + s);
                    if (!s.freed) pr.add("native object not freed");
                    if (p.k == K.GIF) {
                        if (s.startMs != p.gifStart || s.endMs != p.gifEnd || s.fps != p.gifFps || s.width != p.gifWidth)
                            pr.add("gif args " + s.startMs + "/" + s.endMs + "/" + s.fps + "/" + s.width);
                        if (!"240x135".equals(e.getFileResolution())) pr.add("resolution " + e.getFileResolution());
                    } else if (s.video != -1 || s.audio != -1) {
                        pr.add("streams " + s.video + "/" + s.audio);
                    }
                }
            }
            case COMPRESS -> {
                if (!(res instanceof DownloadEntity e)) { pr.add("result " + res + " is not an entity"); break; }
                File out = p.outputs.get(0);
                if (!out.getAbsolutePath().equals(e.getFilePath())) pr.add("path " + e.getFilePath() + " want " + out);
                if (!"application/zip".equals(e.getFileMimeType())) pr.add("mime " + e.getFileMimeType());
                if (e.getFileStatus() != Download.FINISHED) pr.add("status " + e.getFileStatus());
                try (ZipFile z = new ZipFile(out)) {
                    List<String> names = new ArrayList<>();
                    for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                        ZipEntry ze = en.nextElement();
                        names.add(ze.getName());
                        int i = names.size() - 1;
                        if (i < p.inputBytes.size() && !Arrays.equals(z.getInputStream(ze).readAllBytes(), p.inputBytes.get(i))) pr.add("entry " + ze.getName() + " differs");
                    }
                    List<String> want = new ArrayList<>();
                    for (File f : p.inputs) want.add(f.getName());
                    if (!names.equals(want)) pr.add("entries " + names + " want " + want);
                } catch (IOException ex) {
                    pr.add("zip unreadable: " + ex);
                }
                if (repo.added.size() != 1) pr.add("repo rows " + repo.added.size());
                for (File f : p.inputs) if (!f.isFile()) pr.add("source " + f.getName() + " removed");
            }
            case EXTRACT -> {
                if (!Integer.valueOf(p.outputs.size()).equals(res)) pr.add("result " + res + " want " + p.outputs.size());
                for (int i = 0; i < p.outputs.size(); i++) if (!sameBytes(p.outputs.get(i), p.inputBytes.get(i))) pr.add(p.outputs.get(i).getName() + " missing/differs");
                if (repo.added.size() != p.outputs.size()) pr.add("repo rows " + repo.added.size());
                if (GalleryPublisher.PUBLISHED.size() != p.outputs.size()) pr.add("published " + GalleryPublisher.PUBLISHED.size());
                if (!p.inputs.get(0).isFile()) pr.add("archive removed");
            }
            case LOCK, UNLOCK -> {
                if (!Integer.valueOf(p.outputs.size()).equals(res)) pr.add("result " + res + " want " + p.outputs.size());
                for (int i = 0; i < p.outputs.size(); i++) {
                    if (!sameBytes(p.outputs.get(i), p.inputBytes.get(i))) pr.add(p.outputs.get(i) + " missing/differs");
                    if (p.inputs.get(i).exists()) pr.add("source " + p.inputs.get(i).getName() + " still there");
                }
                if (repo.added.size() != p.outputs.size()) pr.add("repo rows " + repo.added.size());
                for (DownloadEntity e : repo.added) {
                    if (e.isFileSafe() != (p.k == K.LOCK)) pr.add("row safe=" + e.isFileSafe());
                    if (!e.getFilePath().startsWith((p.k == K.LOCK ? safe : dl).getAbsolutePath())) pr.add("row path " + e.getFilePath());
                }
                if (p.k == K.LOCK) {
                    for (File f : p.inputs) if (!GalleryPublisher.FORGOTTEN.contains(f.getAbsolutePath())) pr.add("gallery not told to forget " + f.getName());
                } else if (GalleryPublisher.PUBLISHED.size() != p.outputs.size()) {
                    pr.add("published " + GalleryPublisher.PUBLISHED.size());
                }
            }
        }
        return String.join("; ", pr);
    }

    /* ── 1. happy path ─────────────────────────────────────────────────── */

    static void group1() throws Exception {
        for (K k : K.values()) happy(k, 35);
        happy(K.GIF, 34);
    }

    static void happy(K k, int sdk) throws Exception {
        Case c = new Case("1." + k.tag + (sdk != 35 ? ".sdk" + sdk : ""));
        Build.VERSION.SDK_INT = sdk;
        Plan p = plan(k, "h", 2, 48 * 1024);
        start(p);
        boolean done = awaitFinished(1, WAIT);
        quiesce(WAIT);
        List<Ev> ev = evs();
        Ev st = first(TaskEvent.Started.class), fin = first(TaskEvent.Finished.class);
        long fins = count(Ev::finished), progress = count(Ev::progress);
        String id = c.id;
        check(id + ".events", done && st != null && fin != null && ev.indexOf(st) < ev.indexOf(fin) && fins == 1
                        && st.action() == k.ok && fin.action() == k.ok && (!k.progress || progress >= 1) && (k.progress || progress == 0),
                seq(ev));
        if (fin != null) check(id + ".result", successProblems(c, p, fin).isEmpty(), successProblems(c, p, fin) + " result=" + resultText(fin.result()));
        check(id + ".in-flight-during", st != null && st.inFlight() && fin != null && fin.inFlight(), "at Started=" + (st != null && st.inFlight()));
        check(id + ".wake-lock-during", st != null && st.wake() && fin != null && fin.wake(), "held at Started=" + (st != null && st.wake()));
        PowerManager.WakeLock wl = PowerManager.ALL.isEmpty() ? null : PowerManager.ALL.get(PowerManager.ALL.size() - 1);
        check(id + ".wake-lock-shape", wl != null && wl.flags == PowerManager.PARTIAL_WAKE_LOCK && wl.lastTimeoutMs > 0
                        && wl.lastTimeoutMs <= 6L * 3600_000L && wl.threads.stream().allMatch("TaskManager"::equals),
                wl == null ? "none" : "timeout=" + wl.lastTimeoutMs + " threads=" + new HashSet<>(wl.threads));
        Sys.Record r = c.rec();
        String np = notificationProblems(r, k);
        check(id + ".foreground", r != null && r.everForeground && st != null && st.fg() && np.isEmpty(), np);
        if (k.progress) {
            boolean determinate = false;
            if (r != null) synchronized (r.notifications) { for (Notification n : r.notifications) if (n.hasProgress && !n.progressIndeterminate) determinate = true; }
            check(id + ".notification-progress", determinate, r == null ? "" : r.notifications.toString());
        }
        check(id + ".stopped-by-stopSelfResult", r != null && r.destroyed && ("stopSelfResult(" + p.startId + ")").equals(r.stopReason),
                String.valueOf(r));
        c.endState();
    }

    /* ── 2. cancel mid-work ────────────────────────────────────────────── */

    static void group2() throws Exception {
        for (K k : K.values()) {
            cancelMidWork(k, false);
            cancelMidWork(k, true);
        }
        for (K k : new K[]{K.GIF, K.AUDIO, K.FRAME, K.EXTRACT}) errorResult(k);
    }

    /** A failure that is not a cancel: the native step fails, the frame
     *  cannot be decoded at all, the archive is corrupt. */
    static void errorResult(K k) throws Exception {
        Case c = new Case("2e." + k.tag);
        Plan p = plan(k, "e", 1, 32 * 1024);
        switch (k) {
            case GIF -> FakeNative.GIF.failAtStep = 3;
            case AUDIO -> FakeNative.ENC.failAtStep = 3;
            case FRAME -> {
                MediaMetadataRetriever.returnNull = true;
                FFmpegThumbnailer.returnBitmap = false;
            }
            default -> Files.write(p.inputs.get(0).toPath(), "not a zip archive".getBytes());
        }
        start(p);
        awaitFinished(1, WAIT);
        quiesce(WAIT);
        Ev fin = first(TaskEvent.Finished.class);
        check(c.id + ".error-result", fin != null && fin.action() == k.error, seq(evs()));
        List<String> left = new ArrayList<>();
        for (File o : p.outputs) if (o.exists()) left.add(o.getName());
        check(c.id + ".partial-output-deleted", left.isEmpty() && repo.added.isEmpty(), "left " + left + " rows " + repo.added.size());
        c.endState();
    }

    static void longWork() {
        FakeNative.GIF.steps = 200; FakeNative.GIF.stepMs = 10;
        FakeNative.ENC.steps = 200; FakeNative.ENC.stepMs = 10;
    }

    static void cancelMidWork(K k, boolean viaNotification) throws Exception {
        Case c = new Case("2." + k.tag + (viaNotification ? ".notification-cancel" : ".own-cancel"));
        longWork();
        MediaMetadataRetriever.delayMs = 300;
        Plan p = plan(k, "c", 2, 96 * 1024);
        AtomicLong cancelAt = new AtomicLong();
        AtomicReference<List<String>> fdsAtCancel = new AtomicReference<>(List.of());
        Runnable cancel = () -> {
            cancelAt.set(cancelAndWait(k.cancel, viaNotification));
        };
        if (k == K.FRAME) {
            MediaMetadataRetriever.onGetFrame = () -> { MediaMetadataRetriever.onGetFrame = null; cancel.run(); };
        } else {
            onEvent = once(e -> e instanceof TaskEvent.Progress, cancel);
        }
        start(p);
        boolean done = awaitFinished(1, WAIT);
        quiesce(WAIT);
        Ev fin = first(TaskEvent.Finished.class);
        long ms = fin != null && cancelAt.get() > 0 ? (fin.t() - cancelAt.get()) / 1_000_000L : -1;
        String id = c.id;
        if (k.vault()) {
            check(id + ".result-action", fin != null && fin.action() == k.ok, fin + " (vault tasks have no cancel result)");
            check(id + ".reports-no-file-done", fin != null && Integer.valueOf(0).equals(fin.result()),
                    "Finished count=" + (fin == null ? "-" : fin.result()) + ", files moved: "
                            + p.outputs.stream().filter(File::exists).count());
        } else {
            check(id + ".result", done && fin != null && fin.action() == k.cancelled, fin + " via " + lastCancelRoute);
        }
        check(id + ".prompt", fin != null && cancelAt.get() > 0 && ms >= 0 && ms < 1500, ms + " ms from cancel to Finished");
        if (viaNotification) check(id + ".sent-by-the-notification-action", "notification".equals(lastCancelRoute), lastCancelRoute);
        List<String> left = new ArrayList<>();
        for (File o : p.outputs) if (o.exists()) left.add(o.getName());
        check(id + ".partial-output-deleted", left.isEmpty(), "left: " + left + " dl=" + files(dl) + " safe=" + files(safe));
        boolean sources = true;
        for (File in : p.inputs) if (!in.isFile()) sources = false;
        check(id + ".sources-intact", sources, "dl=" + files(dl) + " safe=" + files(safe));
        check(id + ".no-row-written", repo.added.isEmpty(), repo.added.toString());
        if (k == K.GIF || k == K.AUDIO) {
            List<FakeNative.State> st = c.natives();
            boolean ok = st.size() == 1 && st.get(0).interrupt && !st.get(0).completed && st.get(0).freed;
            check(id + ".native-interrupted-and-freed", ok, st.toString());
        }
        if (k == K.FRAME) info(id, "the frame grab cannot be interrupted: the cancel waited out the grab (" + ms + " ms with a 300 ms grab)");
        List<String> fds = fdsUnder(new File(dl.getParentFile().getParentFile(), ""));
        check(id + ".no-open-file-descriptors", fds.isEmpty(), "open: " + fds);
        c.endState();
    }

    /* ── 3. cancel in the windows before the work starts ────────────────── */

    static void group3() throws Exception {
        for (K k : K.values()) cancelBeforeRun(k);
        cancelAtStarted(K.GIF);
        cancelAtStarted(K.AUDIO);
        cancelInNativeConstructor(K.GIF);
        cancelInNativeConstructor(K.AUDIO);
        for (K k : K.values()) emptyList(k);
    }

    /** A start with no entities: the task returns at once and run() still
     *  calls stop() (AudioEncodeTask.stop used to NPE on the null encoder). */
    static void emptyList(K k) throws Exception {
        Case c = new Case("3d." + k.tag + ".empty-list");
        Intent i = new Intent(ctx, TaskManager.class).setAction(k.start);
        i.putParcelableArrayListExtra(Keys.ITEM_LIST_ID, new ArrayList<DownloadEntity>());
        sys.startService(i, false);
        quiesce(WAIT);
        check(c.id + ".no-exception", c.clean(), c.newCrashes());
        if (!evs().isEmpty()) info(c.id, "events: " + seq(evs()));
        c.endState(false);
    }

    static boolean endedCancelled(K k, Ev fin) {
        if (fin == null) return false;
        if (k.vault()) return fin.action() == k.ok && Integer.valueOf(0).equals(fin.result());
        return fin.action() == k.cancelled;
    }

    /** "" when nothing of the task's work was done. */
    static String workDone(Case c, Plan p) {
        List<String> w = new ArrayList<>();
        for (FakeNative.State s : c.natives()) if (s.completed || s.stepsDone > 0) w.add("native ran " + s.stepsDone + " step(s), completed=" + s.completed);
        for (File o : p.outputs) if (o.exists()) w.add(o.getName() + " written");
        for (File in : p.inputs) if (!in.exists()) w.add(in.getName() + " moved");
        if (!repo.added.isEmpty()) w.add(repo.added.size() + " row(s)");
        return String.join("; ", w);
    }

    /** The window between "this is the current task" and run(): the cancel
     *  lands while runJob takes the wake lock. */
    static void cancelBeforeRun(K k) throws Exception {
        Case c = new Case("3a." + k.tag);
        longWork();
        Plan p = plan(k, "w", 1, 64 * 1024);
        PowerManager.onAcquire = wl -> {
            PowerManager.onAcquire = null;
            cancelAndWait(k.cancel, false);
        };
        start(p);
        awaitFinished(1, WAIT);
        quiesce(WAIT);
        Ev fin = first(TaskEvent.Finished.class);
        check(c.id + ".ends-cancelled", endedCancelled(k, fin), seq(evs()));
        String w = workDone(c, p);
        check(c.id + ".not-run-to-completion", w.isEmpty(), w);
        if (k == K.FRAME) {
            int grabs = MediaMetadataRetriever.REQUESTS_US.size() - c.mmrIdx;
            if (grabs > 0) info(c.id, "the frame was still decoded (" + grabs + " grab) after the cancel: SaveFrameTask checks isStopped only after grabFrame");
        }
        c.endState();
    }

    /** Before the native object exists: AudioEncodeTask.stop() used to NPE here. */
    static void cancelAtStarted(K k) throws Exception {
        Case c = new Case("3b." + k.tag);
        longWork();
        Plan p = plan(k, "s", 1, 64 * 1024);
        onEvent = once(e -> e instanceof TaskEvent.Started, () -> cancelAndWait(k.cancel, false));
        start(p);
        awaitFinished(1, WAIT);
        quiesce(WAIT);
        Ev fin = first(TaskEvent.Finished.class);
        check(c.id + ".no-exception-on-stop", c.clean(), c.newCrashes());
        check(c.id + ".ends-cancelled", endedCancelled(k, fin), seq(evs()));
        check(c.id + ".no-native-work", c.natives().isEmpty() || workDone(c, p).isEmpty(), c.natives().toString());
        c.endState(false);
    }

    /** The cancel lands while the task builds its native object: the task's
     *  isStopped() check already passed and the field is still null, so
     *  stop() has nothing to interrupt. */
    static void cancelInNativeConstructor(K k) throws Exception {
        Case c = new Case("3c." + k.tag);
        FakeNative.Config cfg = k == K.GIF ? FakeNative.GIF : FakeNative.ENC;
        cfg.steps = 100; cfg.stepMs = 10;
        Plan p = plan(k, "n", 1, 64 * 1024);
        AtomicLong cancelAt = new AtomicLong();
        cfg.onInit = () -> {
            cfg.onInit = null;
            cancelAt.set(cancelAndWait(k.cancel, false));
        };
        start(p);
        awaitFinished(1, WAIT);
        quiesce(WAIT);
        Ev fin = first(TaskEvent.Finished.class);
        long ms = fin != null && cancelAt.get() > 0 ? (fin.t() - cancelAt.get()) / 1_000_000L : -1;
        List<FakeNative.State> st = c.natives();
        check(c.id + ".ends-cancelled", endedCancelled(k, fin), seq(evs()));
        check(c.id + ".output-deleted", !p.outputs.get(0).exists(), files(dl));
        check(c.id + ".native-work-skipped", st.stream().noneMatch(s -> s.completed),
                st + " (the whole " + cfg.steps * cfg.stepMs + " ms encode ran after the cancel)");
        check(c.id + ".prompt", ms >= 0 && ms < 300, ms + " ms from cancel to Finished");
        c.endState();
    }

    /* ── 4. input snapshots ────────────────────────────────────────────── */

    static void group4() throws Exception {
        snapshotGif();
        snapshotFrame();
        snapshotCompress();
        cancelStopsCurrentNotQueued();
    }

    static void snapshotGif() throws Exception {
        Case c = new Case("4a.gif");
        Plan a = gifPlan("snapA", 1000, 3000, 10, 240);
        Plan b = gifPlan("snapB", 500, 900, 15, 480);
        onEvent = once(e -> e instanceof TaskEvent.Started, () -> {
            start(b);
            try { sys.awaitMainIdle(3000); } catch (InterruptedException ignored) { }
        });
        start(a);
        awaitFinished(2, WAIT);
        quiesce(WAIT);
        List<FakeNative.State> st = c.natives();
        FakeNative.State sa = st.size() > 0 ? st.get(0) : null, sb = st.size() > 1 ? st.get(1) : null;
        check(c.id + ".A-own-args", sa != null && a.inputs.get(0).getAbsolutePath().equals(sa.in)
                        && sa.startMs == 1000 && sa.endMs == 3000 && sa.fps == 10 && sa.width == 240,
                sa == null ? "no native call" : "A ran " + new File(sa.in).getName() + " " + sa.startMs + "-" + sa.endMs + "ms fps=" + sa.fps + " w=" + sa.width);
        check(c.id + ".B-own-args", sb != null && b.inputs.get(0).getAbsolutePath().equals(sb.in)
                        && sb.startMs == 500 && sb.endMs == 900 && sb.fps == 15 && sb.width == 480,
                sb == null ? "no native call" : "B ran " + new File(sb.in).getName() + " " + sb.startMs + "-" + sb.endMs + "ms fps=" + sb.fps + " w=" + sb.width);
        List<Ev> fins = all(TaskEvent.Finished.class);
        check(c.id + ".each-result-its-own", fins.size() == 2
                        && fins.get(0).result() instanceof DownloadEntity ea && ea.getFilePath().equals(a.outputs.get(0).getAbsolutePath())
                        && fins.get(1).result() instanceof DownloadEntity eb && eb.getFilePath().equals(b.outputs.get(0).getAbsolutePath())
                        && a.outputs.get(0).isFile() && b.outputs.get(0).isFile(),
                seq(evs()));
        c.endState();
    }

    static void snapshotFrame() throws Exception {
        Case c = new Case("4b.frame");
        Plan a = plan(K.FRAME, "fA", 1, 32 * 1024);
        Plan b = plan(K.FRAME, "fB", 1, 32 * 1024);
        b.framePosMs = 4200;
        b.intent.putExtra(Keys.FRAME_POSITION_MS, 4200L);
        onEvent = once(e -> e instanceof TaskEvent.Started, () -> {
            start(b);
            try { sys.awaitMainIdle(3000); } catch (InterruptedException ignored) { }
        });
        start(a);
        awaitFinished(2, WAIT);
        quiesce(WAIT);
        List<Long> req = new ArrayList<>(MediaMetadataRetriever.REQUESTS_US.subList(c.mmrIdx, MediaMetadataRetriever.REQUESTS_US.size()));
        check(c.id + ".each-grab-at-its-own-position", req.equals(List.of(1_500_000L, 4_200_000L)), "grabs at " + req + " us");
        check(c.id + ".each-output-its-own", a.outputs.get(0).isFile() && b.outputs.get(0).isFile(), files(dl));
        c.endState();
    }

    static void snapshotCompress() throws Exception {
        Case c = new Case("4c.compress");
        Plan a = plan(K.COMPRESS, "zA", 2, 32 * 1024);
        Plan b = plan(K.COMPRESS, "zB", 1, 32 * 1024);
        onEvent = once(e -> e instanceof TaskEvent.Started, () -> {
            start(b);
            try { sys.awaitMainIdle(3000); } catch (InterruptedException ignored) { }
        });
        start(a);
        awaitFinished(2, WAIT);
        quiesce(WAIT);
        List<Ev> fins = all(TaskEvent.Finished.class);
        String pa = fins.size() > 0 ? successProblems(c, a, fins.get(0)) : "no Finished";
        check(c.id + ".A-zips-its-own-files", fins.size() == 2 && fins.get(0).action() == ServiceActions.COMPRESS
                && (pa.isEmpty() || pa.equals("repo rows 2")), pa);
        c.endState();
    }

    static void cancelStopsCurrentNotQueued() throws Exception {
        Case c = new Case("4d.gif");
        FakeNative.GIF.steps = 100; FakeNative.GIF.stepMs = 10;
        Plan a = gifPlan("qA", 1000, 3000, 10, 240);
        Plan b = gifPlan("qB", 200, 800, 8, 160);
        onEvent = once(e -> e instanceof TaskEvent.Progress, () -> cancelAndWait(K.GIF.cancel, false));
        start(a);
        start(b);
        awaitFinished(2, WAIT);
        quiesce(WAIT);
        List<Ev> fins = all(TaskEvent.Finished.class);
        check(c.id + ".cancel-stops-the-running-task", fins.size() >= 1 && fins.get(0).action() == ServiceActions.CANCEL_MAKE_GIF, seq(evs()));
        boolean bOk = fins.size() == 2 && fins.get(1).action() == ServiceActions.MAKE_GIF
                && fins.get(1).result() instanceof DownloadEntity e && e.getFilePath().equals(b.outputs.get(0).getAbsolutePath());
        List<FakeNative.State> st = c.natives();
        FakeNative.State sb = st.size() > 1 ? st.get(1) : null;
        check(c.id + ".queued-task-runs-with-its-own-inputs", bOk && sb != null && sb.startMs == 200 && sb.endMs == 800 && sb.fps == 8 && sb.width == 160,
                seq(evs()) + " B native=" + sb);
        c.endState();
    }

    /* ── 5. the orphan scenarios ───────────────────────────────────────── */

    static void group5() throws Exception {
        systemDestroysRunningService();
        secondInstanceCancels(false);
        secondInstanceCancels(true);
        newInstanceWhileOldTaskUnwinds();
    }

    /** S1 (a plain started service: its startForeground was refused) is
     *  destroyed by the system while its frame grab, which cannot be
     *  interrupted, is still running. The user taps a new task: Android
     *  creates S2. S2's onStartCommand sees the old runnable still current
     *  and skips startForeground ("its notification stays its own"), but that
     *  notification died with S1. S2 asks again only from the worker, after
     *  the grab ends; by then the user has left the app. */
    static void newInstanceWhileOldTaskUnwinds() throws Exception {
        Case c = new Case("5d.frame-then-gif");
        sys.refuseForeground = -1;
        MediaMetadataRetriever.delayMs = 600;
        Plan a = plan(K.FRAME, "uA", 1, 32 * 1024);
        Plan b = plan(K.GIF, "uB", 1, 32 * 1024);
        MediaMetadataRetriever.onGetFrame = () -> {
            MediaMetadataRetriever.onGetFrame = null;
            sys.systemStop(c.rec());
            try { sys.awaitMainIdle(3000); } catch (InterruptedException ignored) { }
            sys.refuseForeground = 0;       // the app is on screen: S2 may go foreground
            start(b);
            try { sys.awaitMainIdle(3000); } catch (InterruptedException ignored) { }
            sys.appInBackground = true;     // the user leaves while the old grab finishes
        };
        start(a);
        awaitFinished(2, WAIT);
        quiesce(WAIT);
        Sys.Record s2 = c.rec(1);
        List<Ev> fins = all(TaskEvent.Finished.class);
        check(c.id + ".new-task-completes", fins.size() == 2 && fins.get(1).action() == ServiceActions.MAKE_GIF, seq(evs()));
        check(c.id + ".new-instance-goes-foreground-while-the-app-is-on-screen", s2 != null && s2.everForeground,
                s2 == null ? "no second instance" : "S2 startForeground calls from " + s2.fgThreads + ", refused " + s2.refusedForegroundCalls + "x"
                        + (s2.everForeground ? "" : ": it never asked while the app was on screen, and ran as a plain background service (Android stops those about a minute after the app leaves)"));
        c.endState();
        sys.appInBackground = false;
    }

    static void systemDestroysRunningService() throws Exception {
        Case c = new Case("5a.gif");
        longWork();
        sys.refuseForeground = -1; // a plain started service: what Android stops in the background
        Plan a = plan(K.GIF, "oA", 1, 32 * 1024);
        Plan b = plan(K.COMPRESS, "oB", 1, 32 * 1024);
        AtomicLong destroyedAt = new AtomicLong();
        onEvent = once(e -> e instanceof TaskEvent.Progress, () -> {
            sys.systemStop(c.rec());
            try { sys.awaitMainIdle(3000); } catch (InterruptedException ignored) { }
            destroyedAt.set(System.nanoTime());
        });
        start(a);
        start(b);
        awaitFinished(1, WAIT);
        quiesce(WAIT);
        Ev fin = first(TaskEvent.Finished.class);
        long ms = fin != null && destroyedAt.get() > 0 ? (fin.t() - destroyedAt.get()) / 1_000_000L : -1;
        check(c.id + ".task-ends-with-the-service", fin != null && fin.action() == ServiceActions.CANCEL_MAKE_GIF && ms >= 0 && ms < 1500,
                fin + " " + ms + " ms after onDestroy");
        check(c.id + ".output-deleted", !a.outputs.get(0).exists(), files(dl));
        long started = count(Ev::started);
        info(c.id, "the start queued behind it on the destroyed instance (" + K.COMPRESS.tag + "): "
                + (started == 1 ? "skipped, with no Started or Finished event and no notification (the user's request vanishes silently)" : "ran: " + seq(evs())));
        check(c.id + ".queued-start-not-run-on-dead-instance", started == 1 && !b.outputs.get(0).exists(), seq(evs()));
        Sys.Record r = c.rec();
        info(c.id, "after onDestroy the worker still called stopSelfResult on the dead instance: " + (r == null ? "-" : r.stopSelfCalls)
                + "; startForeground on it ignored " + (r == null ? 0 : r.deadServiceCalls) + "x");
        c.endState();
        // The next start gets a new instance and the same single worker.
        Case c2 = new Case("5a.next-start");
        Plan d = plan(K.GIF, "oD", 1, 32 * 1024);
        start(d);
        awaitFinished(1, WAIT);
        quiesce(WAIT);
        Ev f2 = first(TaskEvent.Finished.class);
        long workers = HandlerThread.ALL.stream().filter(t -> "TaskManager".equals(t.getName())).count();
        check(c2.id + ".runs-on-a-new-instance", f2 != null && f2.action() == ServiceActions.MAKE_GIF && c2.records() == 1 && workers == 1,
                f2 + " instances=" + c2.records() + " workers=" + workers);
        c2.endState();
    }

    static void secondInstanceCancels(boolean viaNotification) throws Exception {
        Case c = new Case("5" + (viaNotification ? "c" : "b") + ".gif");
        longWork();
        Plan a = plan(K.GIF, "iA", 1, 32 * 1024);
        AtomicLong cancelAt = new AtomicLong();
        AtomicReference<String> how = new AtomicReference<>("");
        onEvent = once(e -> e instanceof TaskEvent.Progress, () -> {
            Sys.Record s2 = sys.forceNewInstance();
            try { s2.created.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            if (viaNotification) how.set(cancelViaNotification());
            else sys.startService(cancelIntent(K.GIF.cancel), false);
            try { sys.awaitMainIdle(3000); } catch (InterruptedException ignored) { }
            cancelAt.set(System.nanoTime());
        });
        start(a);
        awaitFinished(1, WAIT);
        quiesce(WAIT);
        Ev fin = first(TaskEvent.Finished.class);
        long ms = fin != null && cancelAt.get() > 0 ? (fin.t() - cancelAt.get()) / 1_000_000L : -1;
        Sys.Record s1 = c.rec(0), s2 = c.rec(1);
        check(c.id + ".second-instance-cancel-stops-task",
                fin != null && fin.action() == ServiceActions.CANCEL_MAKE_GIF && ms >= 0 && ms < 1500,
                fin + " " + ms + " ms after the cancel reached S2" + (viaNotification ? " via " + how.get() : ""));
        check(c.id + ".second-instance-stops-itself", s2 != null && s2.destroyed && s2.stopReason != null && s2.stopReason.startsWith("stopSelfResult"),
                String.valueOf(s2));
        check(c.id + ".first-instance-stops-after-the-task", s1 != null && s1.destroyed && s1.stopReason != null && s1.stopReason.startsWith("stopSelfResult"),
                String.valueOf(s1));
        c.endState();
    }

    /* ── 6. the daily foreground-service timeout ───────────────────────── */

    static void group6() throws Exception {
        timeout(35);
        timeout(34);
        timeoutWithQueued();
    }

    static void timeout(int sdk) throws Exception {
        Case c = new Case("6a.sdk" + sdk);
        Build.VERSION.SDK_INT = sdk;
        longWork();
        Plan a = plan(K.GIF, "tA", 1, 32 * 1024);
        AtomicLong at = new AtomicLong();
        AtomicReference<String> after = new AtomicReference<>("");
        onEvent = once(e -> e instanceof TaskEvent.Progress, () -> {
            try {
                boolean ran = sys.timeout(c.rec(), K.GIF.fgType(), 3000);
                Sys.Record r = c.rec();
                after.set("ran=" + ran + " fg=" + r.foreground + " stopping=" + r.stopping);
            } catch (InterruptedException ignored) { }
            at.set(System.nanoTime());
        });
        start(a);
        awaitFinished(1, WAIT);
        quiesce(WAIT);
        Ev fin = first(TaskEvent.Finished.class);
        long ms = fin != null && at.get() > 0 ? (fin.t() - at.get()) / 1_000_000L : -1;
        check(c.id + ".onTimeout-leaves-foreground-at-once", after.get().contains("fg=false"), after.get());
        check(c.id + ".onTimeout-stops-service", after.get().contains("stopping=true") && c.rec().destroyed, after.get() + " " + c.rec());
        check(c.id + ".task-stopped", fin != null && fin.action() == ServiceActions.CANCEL_MAKE_GIF && ms >= 0 && ms < 1500, fin + " " + ms + " ms");
        check(c.id + ".output-deleted", !a.outputs.get(0).exists(), files(dl));
        c.endState();
        Case c2 = new Case("6b.sdk" + sdk + ".after-timeout");
        Build.VERSION.SDK_INT = sdk;
        Plan d = plan(K.GIF, "tD", 1, 32 * 1024);
        start(d);
        awaitFinished(1, WAIT);
        quiesce(WAIT);
        Ev f2 = first(TaskEvent.Finished.class);
        check(c2.id + ".next-task-runs", f2 != null && f2.action() == ServiceActions.MAKE_GIF, String.valueOf(f2));
        c2.endState();
    }

    static void timeoutWithQueued() throws Exception {
        Case c = new Case("6c.queued");
        longWork();
        Plan a = plan(K.GIF, "qA", 1, 32 * 1024);
        Plan b = plan(K.LOCK, "qB", 1, 32 * 1024);
        onEvent = once(e -> e instanceof TaskEvent.Progress, () -> {
            try { sys.timeout(c.rec(), K.GIF.fgType(), 3000); } catch (InterruptedException ignored) { }
        });
        start(a);
        start(b);
        awaitFinished(1, WAIT);
        quiesce(WAIT);
        long started = count(Ev::started);
        info(c.id, "a lock queued behind a task stopped by onTimeout: " + (started == 1
                ? "dropped (the instance is destroyed), no Started/Finished event; the file stays unlocked and the user is not told"
                : "ran: " + seq(evs())));
        check(c.id + ".queued-files-untouched", b.inputs.get(0).isFile() || b.outputs.get(0).isFile(), files(dl) + files(safe));
        c.endState();
    }

    /* ── 7. startForeground refused ────────────────────────────────────── */

    static void group7() throws Exception {
        refused(-1, "7a.refused-always");
        refused(1, "7b.refused-at-start");
        backgroundMidTask();
        backgroundWithQueued();
    }

    static void refused(int refuse, String name) throws Exception {
        Case c = new Case(name);
        sys.refuseForeground = refuse;
        Plan p = plan(K.GIF, "r", 1, 32 * 1024);
        start(p);
        awaitFinished(1, WAIT);
        quiesce(WAIT);
        Ev fin = first(TaskEvent.Finished.class);
        check(c.id + ".task-completes", fin != null && fin.action() == ServiceActions.MAKE_GIF && p.outputs.get(0).isFile(), seq(evs()));
        Sys.Record r = c.rec();
        if (refuse < 0) {
            check(c.id + ".never-foreground", r != null && !r.everForeground && r.refusedForegroundCalls >= 1,
                    r == null ? "-" : "refused " + r.refusedForegroundCalls + "x");
        } else {
            boolean fromWorker = r != null && r.fgThreads.contains("TaskManager") && r.everForeground;
            check(c.id + ".foreground-from-the-worker", fromWorker, r == null ? "-" : "threads " + r.fgThreads + " refused=" + r.refusedForegroundCalls);
        }
        c.endState();
    }

    static void backgroundMidTask() throws Exception {
        Case c = new Case("7c.background-mid-task");
        FakeNative.GIF.steps = 40; FakeNative.GIF.stepMs = 10;
        Plan p = plan(K.GIF, "bg", 1, 32 * 1024);
        onEvent = once(e -> e instanceof TaskEvent.Progress, () -> sys.appInBackground = true);
        start(p);
        awaitFinished(1, WAIT);
        quiesce(WAIT);
        Sys.Record r = c.rec();
        Ev fin = first(TaskEvent.Finished.class);
        check(c.id + ".task-completes", fin != null && fin.action() == ServiceActions.MAKE_GIF, seq(evs()));
        check(c.id + ".no-refused-update", r != null && r.refusedForegroundCalls == 0, r == null ? "-" : "refused=" + r.refusedForegroundCalls);
        c.endState();
        sys.appInBackground = false;
    }

    static void backgroundWithQueued() throws Exception {
        Case c = new Case("7d.background-queued");
        FakeNative.GIF.steps = 40; FakeNative.GIF.stepMs = 10;
        Plan a = plan(K.GIF, "bA", 1, 32 * 1024);
        Plan b = plan(K.COMPRESS, "bB", 1, 32 * 1024);
        onEvent = once(e -> e instanceof TaskEvent.Progress, () -> sys.appInBackground = true);
        start(a);
        start(b);
        awaitFinished(2, WAIT);
        quiesce(WAIT);
        Sys.Record r = c.rec();
        List<Ev> fins = all(TaskEvent.Finished.class);
        Ev bStart = null;
        for (Ev e : evs()) if (e.started() && e.action() == ServiceActions.COMPRESS) bStart = e;
        check(c.id + ".queued-task-runs-foreground-from-background",
                fins.size() == 2 && fins.get(1).action() == ServiceActions.COMPRESS && bStart != null && bStart.fg()
                        && r != null && r.refusedForegroundCalls == 0,
                seq(evs()) + " refused=" + (r == null ? "-" : r.refusedForegroundCalls));
        c.endState();
        sys.appInBackground = false;
    }

    /* ── 8. service lifetime ───────────────────────────────────────────── */

    static void group8() throws Exception {
        startWhileFinishing();
        idleIntent("8b.cancel-idle", cancelIntent(CANCEL_CURRENT));
        idleIntent("8c.own-cancel-idle", cancelIntent(K.COMPRESS.cancel));
        idleIntent("8d.null-intent", null);
        idleIntent("8e.unknown-action", cancelIntent("com.example.NOT_A_TASK_ACTION"));
        otherIntentDuringTask();
        twoStarts();
        crossCancel();
    }

    static void crossCancel() throws Exception {
        Case c = new Case("8h.cross-cancel");
        FakeNative.GIF.steps = 50; FakeNative.GIF.stepMs = 10;
        Plan a = plan(K.GIF, "x", 1, 32 * 1024);
        onEvent = once(e -> e instanceof TaskEvent.Progress, () -> cancelAndWait(K.COMPRESS.cancel, false));
        start(a);
        awaitFinished(1, WAIT);
        quiesce(WAIT);
        Ev fin = first(TaskEvent.Finished.class);
        // A task's own Cancel stops only its kind of task; the notification's
        // CANCEL_CURRENT is the one that stops whatever runs (group 2).
        check(c.id + ".other-kind-cancel-leaves-the-task-running",
                fin != null && fin.action() == ServiceActions.MAKE_GIF, seq(evs()));
        c.endState();
    }

    static void startWhileFinishing() throws Exception {
        Case c = new Case("8a.start-while-finishing");
        Plan a = plan(K.GIF, "fa", 1, 32 * 1024);
        Plan b = plan(K.GIF, "fb", 1, 32 * 1024);
        AtomicReference<CountDownLatch> hold = new AtomicReference<>();
        onEvent = once(e -> e instanceof TaskEvent.Finished, () -> {
            try { hold.set(sys.holdMain()); } catch (InterruptedException ignored) { }
            start(b); // startId handed out now; onStartCommand waits behind the held main thread
        });
        start(a);
        Sys.Record r = null;
        boolean kept = waitUntil(() -> c.rec() != null && !c.rec().stopSelfCalls.isEmpty(), WAIT);
        r = c.rec();
        String calls = r == null ? "-" : r.stopSelfCalls.toString();
        boolean stoppingBeforeB = r != null && r.stopping;
        if (hold.get() != null) hold.get().countDown();
        awaitFinished(2, WAIT);
        quiesce(WAIT);
        check(c.id + ".older-startId-does-not-stop", kept && !stoppingBeforeB && calls.contains(a.startId + ":kept"), calls);
        List<Ev> fins = all(TaskEvent.Finished.class);
        check(c.id + ".queued-start-runs-on-the-same-instance", fins.size() == 2 && fins.get(1).action() == ServiceActions.MAKE_GIF && c.records() == 1,
                seq(evs()) + " instances=" + c.records());
        check(c.id + ".stops-on-the-newest-startId", r != null && r.destroyed && ("stopSelfResult(" + b.startId + ")").equals(r.stopReason), String.valueOf(r));
        c.endState();
    }

    static void idleIntent(String name, Intent intent) throws Exception {
        Case c = new Case(name);
        if (intent == null) sys.startNull();
        else sys.startService(intent, false);
        quiesce(WAIT);
        Sys.Record r = c.rec();
        check(c.id + ".stops-the-service", r != null && r.destroyed && !r.everForeground && evs().isEmpty(),
                String.valueOf(r) + " events=" + seq(evs()));
        c.endState();
    }

    static void otherIntentDuringTask() throws Exception {
        Case c = new Case("8f.other-intent-during-task");
        FakeNative.GIF.steps = 30; FakeNative.GIF.stepMs = 10;
        Plan a = plan(K.GIF, "oi", 1, 32 * 1024);
        AtomicReference<String> state = new AtomicReference<>("");
        onEvent = once(e -> e instanceof TaskEvent.Progress, () -> {
            sys.startService(cancelIntent("com.example.NOT_A_TASK_ACTION"), false);
            sys.startNull();
            try { sys.awaitMainIdle(3000); } catch (InterruptedException ignored) { }
            Sys.Record r = c.rec();
            state.set("stopping=" + r.stopping + " fg=" + r.foreground);
        });
        start(a);
        awaitFinished(1, WAIT);
        quiesce(WAIT);
        Ev fin = first(TaskEvent.Finished.class);
        check(c.id + ".service-kept-while-task-runs", state.get().equals("stopping=false fg=true"), state.get());
        check(c.id + ".task-completes", fin != null && fin.action() == ServiceActions.MAKE_GIF, seq(evs()));
        c.endState();
    }

    static void twoStarts() throws Exception {
        Case c = new Case("8g.two-starts");
        Plan a = plan(K.COMPRESS, "ta", 1, 32 * 1024);
        Plan b = plan(K.EXTRACT, "tb", 1, 32 * 1024);
        start(a);
        start(b);
        awaitFinished(2, WAIT);
        quiesce(WAIT);
        Sys.Record r = c.rec();
        List<Ev> fins = all(TaskEvent.Finished.class);
        long stops = r == null ? 0 : r.stopSelfCalls.stream().filter(s -> s.contains(":stopped")).count();
        check(c.id + ".both-run-one-stop", fins.size() == 2 && fins.get(0).action() == ServiceActions.COMPRESS
                        && fins.get(1).action() == ServiceActions.EXTRACT && c.records() == 1 && stops == 1
                        && ("stopSelfResult(" + b.startId + ")").equals(r.stopReason),
                seq(evs()) + " stopSelf=" + (r == null ? "-" : r.stopSelfCalls));
        c.endState();
    }

    /* ── 11. what the UI receives, and hostile inputs ──────────────────── */

    static void group11() throws Exception {
        startedReachesUi();
        finishedReachesUi();
        nullEntity();
        lockMissingSource();
        readErrorMidCopy(K.LOCK);
        readErrorMidCopy(K.UNLOCK);
    }

    /** A read that fails after the open (a bad sector, a card pulled): the
     *  source is a symlink to /proc/self/mem, which opens and then answers
     *  EIO. The task has already created its output file. */
    static void readErrorMidCopy(K k) throws Exception {
        Case c = new Case("11" + (k == K.LOCK ? "e" : "f") + "." + k.tag + "-read-error");
        File from = k == K.LOCK ? dl : safe;
        File to = k == K.LOCK ? safe : dl;
        File link = new File(from, "io-1.mp4");
        Files.createSymbolicLink(link.toPath(), new File("/proc/self/mem").toPath());
        DownloadEntity e = entity(link, "video/mp4", k == K.UNLOCK);
        e.setFileSize(64 * 1024);
        Intent i = new Intent(ctx, TaskManager.class).setAction(k.start);
        ArrayList<DownloadEntity> list = new ArrayList<>();
        list.add(e);
        i.putParcelableArrayListExtra(Keys.ITEM_LIST_ID, list);
        sys.startService(i, false);
        awaitFinished(1, WAIT);
        quiesce(WAIT);
        File partial = new File(to, "io-1.mp4");
        Ev fin = first(TaskEvent.Finished.class);
        String where = partial.getParentFile().getName() + "/" + partial.getName();
        check(c.id + ".no-partial-file-left", !partial.exists(),
                "after " + fin + ": " + (!partial.exists() ? "no " + where
                        : where + " left behind, " + partial.length() + " bytes, no row for it"
                        + (k == K.UNLOCK ? " (in the PUBLIC download folder)" : " (in the app-private safe folder)")));
        c.endState();
    }

    /** The main thread busy for a moment (a frame, a list bind) right when a
     *  task starts. */
    static void startedReachesUi() throws Exception {
        Case c = new Case("11a.lock");
        Plan p = plan(K.LOCK, "u", 1, 256 * 1024);
        onEvent = once(e -> e instanceof TaskEvent.Started, () -> sys.main.post(() -> {
            try { Thread.sleep(30); } catch (InterruptedException ignored) { }
        }));
        start(p);
        awaitFinished(1, WAIT);
        quiesce(WAIT);
        boolean uiStarted = new ArrayList<>(ui).stream().anyMatch(e -> e instanceof TaskEvent.Started);
        check(c.id + ".ui-receives-Started", uiStarted, "produced: " + seq(evs()) + " | UI observer got: " + uiText());
        c.endState();
    }

    static void finishedReachesUi() throws Exception {
        Case c = new Case("11b.compress-then-gif");
        Plan a = plan(K.COMPRESS, "ua", 1, 32 * 1024);
        Plan b = plan(K.GIF, "ub", 1, 32 * 1024);
        onEvent = once(e -> e instanceof TaskEvent.Finished, () -> sys.main.post(() -> {
            try { Thread.sleep(30); } catch (InterruptedException ignored) { }
        }));
        start(a);
        start(b);
        awaitFinished(2, WAIT);
        quiesce(WAIT);
        boolean got = new ArrayList<>(ui).stream().anyMatch(e -> e instanceof TaskEvent.Finished f && f.getAction() == ServiceActions.COMPRESS);
        check(c.id + ".ui-receives-first-Finished", got, "produced: " + seq(evs()) + " | UI observer got: " + uiText());
        c.endState();
    }

    static String uiText() {
        List<String> s = new ArrayList<>();
        for (TaskEvent e : new ArrayList<>(ui)) s.add(new Ev(e, "", 0, false, false, false).toString());
        return String.join(" ", s);
    }

    /** What BaseDownloadFragment's vault bar Cancel sends (START_DECRYPTION
     *  with a list holding one null entity). */
    static void nullEntity() throws Exception {
        Case c = new Case("11c.unlock-null-entity");
        Intent i = new Intent(ctx, TaskManager.class).setAction(K.UNLOCK.start);
        ArrayList<DownloadEntity> list = new ArrayList<>();
        list.add(null);
        i.putParcelableArrayListExtra(Keys.ITEM_LIST_ID, list);
        sys.startService(i, false);
        quiesce(WAIT);
        check(c.id + ".no-exception-on-the-worker", c.clean(), c.newCrashes() + " (on Android: an uncaught exception on the TaskManager HandlerThread ends the process)");
        c.endState(false);
    }

    static void lockMissingSource() throws Exception {
        Case c = new Case("11d.lock-missing-source");
        Plan p = plan(K.LOCK, "m", 2, 32 * 1024);
        Files.delete(p.inputs.get(0).toPath());
        start(p);
        awaitFinished(1, WAIT);
        quiesce(WAIT);
        Ev fin = first(TaskEvent.Finished.class);
        long locked = p.outputs.stream().filter(File::isFile).count();
        check(c.id + ".count-matches-files-locked", fin != null && Integer.valueOf((int) locked).equals(fin.result()),
                "Finished(" + (fin == null ? "-" : fin.action() + "," + fin.result()) + "), " + locked + " file(s) locked");
        info(c.id, "second file " + (p.inputs.get(1).isFile() ? "never attempted (the first IOException ends the batch)" : "locked"));
        c.endState();
    }

    /* ── main ──────────────────────────────────────────────────────────── */

    static Set<Thread> baseline;

    public static void main(String[] args) {
        String group = args.length > 0 ? args[0] : "1";
        try {
            baseline = new HashSet<>(Thread.getAllStackTraces().keySet());
            boot();
            switch (group) {
                case "1" -> group1();
                case "2" -> group2();
                case "3" -> group3();
                case "4" -> group4();
                case "5" -> group5();
                case "6" -> group6();
                case "7" -> group7();
                case "8" -> group8();
                case "9" -> TaskStress.run(args.length > 1 ? Integer.parseInt(args[1]) : 3, args.length > 2 ? Long.parseLong(args[2]) : 1L, "9");
                case "11" -> group11();
                default -> throw new IllegalArgumentException("unknown group " + group);
            }
            threadSweep(group);
        } catch (Throwable t) {
            System.out.println("FAIL  " + group + ".abort  [" + t + "]");
            t.printStackTrace(System.out);
            fail++;
        }
        System.out.println("RESULT group " + group + ": " + pass + " passed, " + fail + " failed");
        System.out.flush();
        System.exit(fail == 0 ? 0 : 1);
    }

    /** Every thread the run started is accounted for: the system's main
     *  looper and ONE TaskManager worker (never quit, by design), both idle. */
    static void threadSweep(String group) {
        quiesce(WAIT);
        List<String> unexpected = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (!t.isAlive() || baseline.contains(t) || t == Thread.currentThread()) continue;
            if (t instanceof HandlerThread && ("main".equals(t.getName()) || "TaskManager".equals(t.getName()))) continue;
            ThreadGroup g = t.getThreadGroup();
            if (g != null && ("system".equals(g.getName()) || "InnocuousThreadGroup".equals(g.getName()))) continue;
            unexpected.add(t.getName() + "(" + t.getState() + ")");
        }
        long workers = HandlerThread.ALL.stream().filter(t -> "TaskManager".equals(t.getName())).count();
        check(group + ".threads-accounted", unexpected.isEmpty() && workers <= 1 && workerIdle(),
                "workers=" + workers + " idle=" + workerIdle() + " unexpected=" + unexpected);
    }
}

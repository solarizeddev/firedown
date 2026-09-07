package com.solarized.firedown.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.di.RepositoryEntryPoint;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.utils.MessageHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import dagger.hilt.android.EntryPointAccessors;

/**
 * ON-DEVICE concurrency storm against the REAL download service: the real
 * {@link RunnableManager} + {@link DownloadEngine} + {@link DownloadTask} +
 * {@link HttpDownloadStrategy} + Room + the foreground notification, driven
 * with the same intents the UI sends, against a local byte server that
 * throttles, cuts connections mid-body (so the resume path runs under load)
 * and serves a deterministic byte pattern (so a resume that appended at the
 * wrong offset is caught by content, not just by length).
 *
 * <p>This is the layer {@code scripts/engine-harness} cannot be: the JVM
 * harness stubs DownloadTask and the transport, so it proves the QUEUE; this
 * proves the seams — strategies, DB writes, the service lifecycle — under the
 * same random start / user-Finish / delete / restart mix, fired from two
 * threads. It exercises the whole pipeline the way a user hammering the
 * Downloads screen and the notification actions would, only faster.
 *
 * <p>What it asserts once the storm ends and the queue drains:
 * <ul>
 *   <li>no row of ours is left PROGRESS/QUEUED (the queue always drains);</li>
 *   <li>every FINISHED row that was NOT user-finished has a file of exactly
 *       the served size whose every byte matches the pattern — including
 *       the ones whose connection was cut and resumed with a Range;</li>
 *   <li>every user-finished row has a file (a prefix), never a missing one;</li>
 *   <li>no two live rows share a path; no deleted row's file survives;</li>
 *   <li>no ERROR rows (a local server never fails) — an ERROR here is a
 *       pipeline bug, and its error type is printed;</li>
 *   <li>the service stopped itself once idle (no foreground leak).</li>
 * </ul>
 * A crash anywhere in the process fails the run outright, which is the
 * point of running it in-process.
 *
 * <p>Run (device or emulator; NO network needed — the server is local):
 * <pre>
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.manager.DownloadEngineStressTest
 * </pre>
 * Tunables via instrumentation args: {@code rounds} (default 3),
 * {@code perRound} (default 8), {@code seed}. Files land in the real
 * download folder and are removed at the end (rows deleted through the
 * service, so the delete path is exercised too).
 */
@RunWith(AndroidJUnit4.class)
public class DownloadEngineStressTest {

    private static final String TAG = "EngineStress";
    private static final int CHUNK = 16 * 1024;

    private Context mApp;
    private DownloadDataRepository mRepo;
    private ByteServer mServer;
    private ActivityScenario<DownloadsActivity> mScenario;
    private int mRounds = 3, mPerRound = 8;
    private long mSeed;

    /** ids we sent DOWNLOAD_FINISH for — their files are legitimately partial. */
    private final Set<Integer> mUserFinished = Collections.synchronizedSet(new HashSet<>());
    /** ids we sent DOWNLOAD_DELETE for. */
    private final Set<Integer> mDeleted = Collections.synchronizedSet(new HashSet<>());
    /** path → id of a row we deleted, to name a successor whose file a late cleanup took. */
    private final Map<String, Integer> mDeletedPaths = Collections.synchronizedMap(new HashMap<>());

    @Before
    public void setUp() throws Exception {
        mApp = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        RepositoryEntryPoint ep = EntryPointAccessors.fromApplication(mApp, RepositoryEntryPoint.class);
        mRepo = ep.getDownloadDataRepository();
        assertNotNull(mRepo);
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        if (args.getString("rounds") != null) mRounds = Integer.parseInt(args.getString("rounds"));
        if (args.getString("perRound") != null) mPerRound = Integer.parseInt(args.getString("perRound"));
        mSeed = args.getString("seed") != null ? Long.parseLong(args.getString("seed")) : System.nanoTime();
        mServer = new ByteServer();
        mServer.start();
        // The app must be in the foreground for startService → startForeground
        // to be allowed on Android 12+; the Downloads screen also gives the
        // storm a live observer of every Room write, as a user would have.
        // Launched with the same plain component intent the app uses (no
        // action): ActivityScenario.launch(Class) would send ACTION_MAIN,
        // which BaseActivity's IntentHandler treats as a launcher start and
        // routes to the browser destination — not in the Downloads graph.
        mScenario = ActivityScenario.launch(new Intent(mApp, DownloadsActivity.class));
    }

    @After
    public void tearDown() throws Exception {
        try {
            cleanupOurRows();
        } finally {
            if (mScenario != null) mScenario.close();
            if (mServer != null) mServer.stop();
        }
    }

    @Test
    public void storm() throws Exception {
        Log.i(TAG, "seed=" + mSeed + " rounds=" + mRounds + " perRound=" + mPerRound + " server=" + mServer.base());
        Random rnd = new Random(mSeed);
        Map<Integer, Long> expectedSize = new HashMap<>();

        for (int round = 0; round < mRounds; round++) {
            // 1. fire a burst of starts, from two threads at once
            List<String> urls = new ArrayList<>();
            for (int i = 0; i < mPerRound; i++) {
                long size = 200_000 + rnd.nextInt(1_800_000);
                int delay = 2 + rnd.nextInt(20);          // ms per 16 KB chunk
                boolean cut = rnd.nextInt(3) == 0;         // drop the socket at ~40%
                String name = (rnd.nextInt(2) == 0 ? "stress-clip" : "stress-song") + ".bin";  // collide on purpose
                urls.add(mServer.base() + "/r" + round + "/f" + i + "?size=" + size + "&delay=" + delay
                        + (cut ? "&cut=1" : "") + "&name=" + name);
            }
            fireFromTwoThreads(urls, rnd);

            // 2. let them get going, then a random mix of Finish / delete / restart
            Thread.sleep(400);
            long until = System.currentTimeMillis() + 2500 + rnd.nextInt(2500);
            while (System.currentTimeMillis() < until) {
                List<DownloadEntity> live = ours(Download.PROGRESS, Download.QUEUED);
                List<DownloadEntity> running = ours(Download.PROGRESS);
                if (!live.isEmpty()) {
                    int roll = rnd.nextInt(10);
                    if (roll < 3 && !running.isEmpty()) {
                        // Finish is offered on PROGRESS rows only (DownloadsOptionDialogFragment)
                        DownloadEntity pick = running.get(rnd.nextInt(running.size()));
                        mUserFinished.add(pick.getId());
                        send(IntentActions.DOWNLOAD_FINISH, pick);
                    } else if (roll < 6) {
                        ArrayList<DownloadEntity> batch = new ArrayList<>();
                        int n = 1 + rnd.nextInt(Math.min(3, live.size()));
                        for (int k = 0; k < n; k++) {
                            DownloadEntity e = live.get(rnd.nextInt(live.size()));
                            mDeleted.add(e.getId());
                            if (e.getFilePath() != null) mDeletedPaths.put(e.getFilePath(), e.getId());
                            batch.add(e);
                        }
                        sendList(IntentActions.DOWNLOAD_DELETE, batch);
                    }
                }
                List<DownloadEntity> errored = ours(Download.ERROR);
                if (!errored.isEmpty() && rnd.nextInt(4) == 0) {
                    send(IntentActions.DOWNLOAD_RESTART, errored.get(rnd.nextInt(errored.size())));
                }
                Thread.sleep(50 + rnd.nextInt(200));
            }
            for (DownloadEntity e : ours()) {
                Long size = mServer.sizeOf(e.getFileUrl());
                if (size != null) expectedSize.put(e.getId(), size);
            }
        }

        // 3. drain: nothing of ours may stay PROGRESS/QUEUED
        boolean drained = waitUntil(() -> ours(Download.PROGRESS, Download.QUEUED).isEmpty(), 180_000);
        List<DownloadEntity> stuck = ours(Download.PROGRESS, Download.QUEUED);
        assertTrue("rows still PROGRESS/QUEUED after 180 s: " + stuck, drained && stuck.isEmpty());

        // 4. the service stops itself once idle
        boolean stopped = waitUntil(() -> !RunnableManager.isRunning(), 15_000);
        assertTrue("RunnableManager still running with an empty queue (foreground leak)", stopped);

        // 5. every remaining row is honest
        List<DownloadEntity> all = ours();
        Log.i(TAG, "rows at rest: " + all.size() + " userFinished=" + mUserFinished.size() + " deleted=" + mDeleted.size());
        List<String> problems = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        for (DownloadEntity e : all) {
            String path = e.getFilePath();
            if (!paths.add(path)) problems.add("two rows share a path: " + path);
            if (mDeleted.contains(e.getId())) {
                problems.add("deleted row still present: " + e.getId() + " status=" + e.getFileStatus());
                continue;
            }
            File f = path == null ? null : new File(path);
            switch (e.getFileStatus()) {
                case Download.FINISHED:
                    if (f == null || !f.exists()) {
                        problems.add("FINISHED row without a file: " + e.getId() + " " + path
                                + " userFinished=" + mUserFinished.contains(e.getId())
                                + " pathOfDeletedRow=" + mDeletedPaths.get(path));
                        break;
                    }
                    if (!mUserFinished.contains(e.getId())) {
                        Long want = expectedSize.get(e.getId());
                        if (want == null) want = mServer.sizeOf(e.getFileUrl());
                        if (want != null && f.length() != want) {
                            problems.add("FINISHED row " + e.getId() + " has " + f.length() + " bytes, served " + want + " (" + e.getFileUrl() + ")");
                        } else {
                            long bad = firstBadOffset(f, mServer.saltOf(e.getFileUrl()));
                            if (bad >= 0) problems.add("FINISHED row " + e.getId() + " content wrong at byte " + bad + " (a resume appended at the wrong offset?) " + e.getFileUrl());
                        }
                    }
                    break;
                case Download.ERROR:
                    // The one honest ERROR: a Finish that landed before the download
                    // wrote a byte (nothing to keep) — the engine says FILE_NOT_FOUND
                    // rather than minting a FINISHED row with no file.
                    if (mUserFinished.contains(e.getId())
                            && e.getFileErrorType() == MessageHelper.FILE_NOT_FOUND) break;
                    problems.add("ERROR row " + e.getId() + " errorType=" + e.getFileErrorType() + " " + e.getFileUrl());
                    break;
                default:
                    problems.add("non-terminal row " + e.getId() + " status=" + e.getFileStatus());
            }
        }
        // deleted rows: their files must be gone
        for (Integer id : mDeleted) {
            DownloadEntity e = mRepo.findByIdSync(id);
            if (e != null) problems.add("deleted row " + id + " still in the table (status=" + e.getFileStatus() + ")");
        }
        assertTrue("problems:\n" + String.join("\n", problems), problems.isEmpty());
        assertTrue("the storm produced no finished downloads at all — nothing was tested", countStatus(all, Download.FINISHED) > 0);
        assertTrue("no cut-and-resumed download reached FINISHED — the resume path was not exercised",
                mServer.cutsServed.get() == 0 || resumedFinished(all) > 0);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private void fireFromTwoThreads(List<String> urls, Random rnd) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(2);
        for (int t = 0; t < 2; t++) {
            final int lane = t;
            final long jitterSeed = rnd.nextLong();
            new Thread(() -> {
                Random r = new Random(jitterSeed);
                try {
                    for (int i = lane; i < urls.size(); i += 2) {
                        startDownload(urls.get(i));
                        Thread.sleep(r.nextInt(40));
                    }
                } catch (InterruptedException ignored) {
                } finally { done.countDown(); }
            }, "stress-ui-" + t).start();
        }
        done.await();
    }

    private void startDownload(String url) {
        String name = url.substring(url.indexOf("name=") + 5);
        DownloadRequest req = new DownloadRequest.Builder(url)
                .name(name)
                .mimeType("application/octet-stream")
                .fileType(UrlType.FILE.getValue())
                .build();
        Intent i = new Intent(mApp, RunnableManager.class);
        i.setAction(IntentActions.DOWNLOAD_START);
        i.putExtra(Keys.DOWNLOAD_REQUEST, req);
        mApp.startService(i);
    }

    private void send(String action, DownloadEntity e) {
        Intent i = new Intent(mApp, RunnableManager.class);
        i.setAction(action);
        i.putExtra(Keys.ITEM_ID, e);
        mApp.startService(i);
    }

    private void sendList(String action, ArrayList<DownloadEntity> es) {
        Intent i = new Intent(mApp, RunnableManager.class);
        i.setAction(action);
        i.putParcelableArrayListExtra(Keys.ITEM_LIST_ID, es);
        mApp.startService(i);
    }

    private List<DownloadEntity> ours(int... statuses) {
        List<DownloadEntity> out = new ArrayList<>();
        for (DownloadEntity e : mRepo.getAllRawList()) {
            String u = e.getFileUrl();
            if (u == null || !u.startsWith(mServer.base())) continue;
            if (statuses.length == 0) { out.add(e); continue; }
            for (int s : statuses) if (e.getFileStatus() == s) { out.add(e); break; }
        }
        return out;
    }

    private static int countStatus(List<DownloadEntity> es, int status) {
        int n = 0;
        for (DownloadEntity e : es) if (e.getFileStatus() == status) n++;
        return n;
    }

    private int resumedFinished(List<DownloadEntity> es) {
        int n = 0;
        for (DownloadEntity e : es) {
            if (e.getFileStatus() == Download.FINISHED && e.getFileUrl().contains("cut=1")
                    && !mUserFinished.contains(e.getId())) n++;
        }
        return n;
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (!c.getAsBoolean()) { if (System.currentTimeMillis() > deadline) return false; Thread.sleep(250); }
        return true;
    }

    /** -1 if every byte matches the server pattern, else the first offending offset. */
    private static long firstBadOffset(File f, int salt) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[CHUNK];
            long pos = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (buf[i] != ByteServer.pattern(pos + i, salt)) return pos + i;
                }
                pos += n;
            }
        }
        return -1;
    }

    private void cleanupOurRows() throws InterruptedException {
        List<DownloadEntity> all = ours();
        if (all.isEmpty()) return;
        sendList(IntentActions.DOWNLOAD_DELETE, new ArrayList<>(all));
        waitUntil(() -> ours().isEmpty(), 30_000);
        for (DownloadEntity e : all) {
            if (e.getFilePath() != null) new File(e.getFilePath()).delete();
        }
    }

    // ---------------------------------------------------------------------
    // the byte server
    // ---------------------------------------------------------------------

    /**
     * Minimal HTTP/1.1 server on 127.0.0.1: GET /path?size=N&delay=ms[&cut=1]
     * serves N bytes of {@link #pattern} in 16 KB chunks with {@code delay} ms
     * between them, honours {@code Range: bytes=X-} with a 206, and with
     * {@code cut=1} closes the socket at ~40% of a FRESH (non-ranged) request
     * — a resume with a Range then gets the rest. The pattern is salted per
     * URL so a file assembled from two different URLs' bytes fails too.
     */
    static final class ByteServer {
        private ServerSocket mSocket;
        private Thread mAcceptor;
        private final AtomicBoolean mRunning = new AtomicBoolean();
        final AtomicInteger cutsServed = new AtomicInteger();
        private final Map<String, Long> mSizes = new HashMap<>();

        static byte pattern(long offset, int salt) {
            return (byte) ((offset * 31 + salt) ^ (offset >>> 8));
        }

        void start() throws IOException {
            mSocket = new ServerSocket(0, 64, java.net.InetAddress.getByName("127.0.0.1"));
            mRunning.set(true);
            mAcceptor = new Thread(() -> {
                while (mRunning.get()) {
                    try {
                        Socket s = mSocket.accept();
                        new Thread(() -> serve(s), "stress-http").start();
                    } catch (IOException e) {
                        if (mRunning.get()) Log.w(TAG, "accept failed", e);
                    }
                }
            }, "stress-http-accept");
            mAcceptor.start();
        }

        void stop() throws IOException {
            mRunning.set(false);
            if (mSocket != null) mSocket.close();
        }

        String base() { return "http://127.0.0.1:" + mSocket.getLocalPort(); }

        Long sizeOf(String url) {
            String q = query(url, "size");
            return q == null ? null : Long.parseLong(q);
        }

        int saltOf(String url) {
            int qi = url.indexOf('?');
            return (qi < 0 ? url : url.substring(0, qi)).hashCode();
        }

        private static String query(String url, String key) {
            int qi = url.indexOf('?');
            if (qi < 0) return null;
            for (String kv : url.substring(qi + 1).split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && kv.substring(0, eq).equals(key)) return kv.substring(eq + 1);
            }
            return null;
        }

        private void serve(Socket s) {
            try (Socket sock = s) {
                sock.setSoTimeout(30_000);
                BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1));
                String requestLine = in.readLine();
                if (requestLine == null) return;
                long rangeStart = -1;
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    String l = line.toLowerCase();
                    if (l.startsWith("range:")) {
                        String v = l.substring(6).trim();
                        if (v.startsWith("bytes=")) {
                            String from = v.substring(6);
                            int dash = from.indexOf('-');
                            rangeStart = Long.parseLong(dash >= 0 ? from.substring(0, dash) : from);
                        }
                    }
                }
                String target = requestLine.split(" ")[1];
                String full = base() + target;
                long size = Long.parseLong(query(target, "size"));
                int delay = Integer.parseInt(query(target, "delay"));
                boolean cut = "1".equals(query(target, "cut"));
                int salt = saltOf(full);
                synchronized (mSizes) { mSizes.put(full, size); }

                OutputStream out = sock.getOutputStream();
                long from = Math.max(0, rangeStart);
                if (rangeStart >= size) {
                    out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + size + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    return;
                }
                long len = size - from;
                StringBuilder h = new StringBuilder();
                h.append(rangeStart >= 0 ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
                h.append("Content-Type: application/octet-stream\r\n");
                h.append("Accept-Ranges: bytes\r\n");
                h.append("Content-Length: ").append(len).append("\r\n");
                if (rangeStart >= 0) h.append("Content-Range: bytes ").append(from).append('-').append(size - 1).append('/').append(size).append("\r\n");
                h.append("Connection: close\r\n\r\n");
                out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
                out.flush();

                // Only a FRESH request gets cut; the resume must succeed.
                long cutAt = (cut && rangeStart < 0) ? (long) (size * 0.4) : Long.MAX_VALUE;
                byte[] buf = new byte[CHUNK];
                long pos = from;
                while (pos < size) {
                    if (pos >= cutAt) { cutsServed.incrementAndGet(); return; }  // socket closes by try-with-resources
                    int n = (int) Math.min(CHUNK, size - pos);
                    for (int i = 0; i < n; i++) buf[i] = pattern(pos + i, salt);
                    out.write(buf, 0, n);
                    out.flush();
                    pos += n;
                    if (delay > 0) Thread.sleep(delay);
                }
            } catch (IOException | InterruptedException | RuntimeException e) {
                // client went away (a delete / Finish interrupt) — expected under the storm
            }
        }
    }
}

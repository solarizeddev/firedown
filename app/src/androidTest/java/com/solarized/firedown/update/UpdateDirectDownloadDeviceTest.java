package com.solarized.firedown.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.UpdateDirectDownloadWorker;
import com.solarized.firedown.UpdateDownloader;
import com.solarized.firedown.UpdateInstallProgress;
import com.solarized.firedown.UpdateInstalledApk;
import com.solarized.firedown.UpdateInstaller;
import com.solarized.firedown.UpdatePlan;
import com.solarized.firedown.utils.NotificationID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The update download when the Download Manager can't take it, on a phone,
 * end to end: UpdateDownloader.start with no Download Manager (the state a
 * user who disabled the Download Manager app is in), the REAL
 * UpdateDirectDownloadWorker run by the app's own WorkManager with the
 * app's Hilt-injected OkHttp client, a connection dropped mid-file and its
 * continuation 30 s later resuming with Range, the finalize worker's
 * SHA-256 + signature checks, and the ready record. Then the installer's
 * belt: a ready APK whose bytes no longer match the digest it was verified
 * against is refused before Android sees it, and dropped.
 *
 * The "update" is the installed APK itself, served from a loopback server:
 * it carries the app's signature, which is what the finalize worker checks.
 * Nothing is installed.
 *
 * The app's update state (its preferences and any update APK on disk) is
 * set aside first and put back after.
 *
 * <p>Run from Android Studio (right-click → Debug, debug variant) or:
 * <pre>
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.update.UpdateDirectDownloadDeviceTest
 * </pre>
 * Needs a network that is not roaming (the worker's constraint; nothing goes
 * past the loopback), about twice the APK's size free, and about a minute.
 */
@RunWith(AndroidJUnit4.class)
public class UpdateDirectDownloadDeviceTest {

    private static final String KEY_PREFIX = "com.solarized.firedown.UPDATE_";
    /** Where the first answer is cut: the continuation asks for the rest. */
    private static final long DROP_AT = 20L * 1024L * 1024L;
    private static final long WAIT_MS = 180_000L;

    private Context mApp;
    private SharedPreferences mPrefs;
    private final Map<String, Object> mSaved = new HashMap<>();
    private File mApk;
    private File mApkBackup;
    private File mServed;
    private MiniServer mServer;
    private long mDownloadId = -1;

    @Before
    public void setUp() throws Exception {
        mApp = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mApp);
        SharedPreferences.Editor clear = mPrefs.edit();
        for (Map.Entry<String, ?> e : mPrefs.getAll().entrySet()) {
            if (e.getKey().startsWith(KEY_PREFIX)) {
                mSaved.put(e.getKey(), e.getValue());
                clear.remove(e.getKey());
            }
        }
        assertTrue(clear.commit());
        mApk = Preferences.getUpdateApkFile(mApp);
        mApkBackup = new File(mApk.getParentFile(), mApk.getName() + ".device-test-backup");
        if (mApk.exists()) {
            Files.move(mApk.toPath(), mApkBackup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        mServed = UpdateInstalledApk.file(mApp);
        assertNotNull("installed as split APKs, or base.apk unreadable", mServed);
        mServer = new MiniServer(mServed);
    }

    @After
    public void tearDown() throws Exception {
        if (mServer != null) {
            mServer.close();
        }
        if (mDownloadId != -1) {
            UpdateDirectDownloadWorker.cancel(mApp, mDownloadId);
        }
        NotificationManager nm = mApp.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(NotificationID.APK_UPDATE_PROMPT_INSTALL);
            nm.cancel(NotificationID.APK_UPDATE_FAILED_INSTALL);
        }
        UpdateDownloader.clearPending(mApp);
        UpdateDownloader.clearReady(mApp);
        SharedPreferences.Editor e = mPrefs.edit();
        for (String key : mPrefs.getAll().keySet()) {
            if (key.startsWith(KEY_PREFIX)) {
                e.remove(key);
            }
        }
        for (Map.Entry<String, Object> s : mSaved.entrySet()) {
            put(e, s.getKey(), s.getValue());
        }
        e.commit();
        if (mApkBackup != null && mApkBackup.exists()) {
            Files.move(mApkBackup.toPath(), mApk.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    public void withNoDownloadManagerTheUpdateDownloadsDirectlyResumesAndIsVerified() throws Exception {
        assumeTrue("needs a network that is not roaming (the worker's constraint)", nonRoamingNetwork());
        String sha = UpdatePatch.sha256Hex(mServed, null);
        // Above any published release: the app's own update check, which
        // instrumentation starts with the process, then sees this one as
        // ready and only re-posts its prompt, instead of replacing it with
        // a real download of the live release.
        int code = Integer.MAX_VALUE - 1;
        mServer.dropAfter = DROP_AT;

        // A context whose Download Manager is gone, as when the user disabled it.
        Context noDownloadManager = new ContextWrapper(mApp) {
            @Override
            public Object getSystemService(String name) {
                return Context.DOWNLOAD_SERVICE.equals(name) ? null : super.getSystemService(name);
            }
        };
        UpdatePlan plan = UpdatePlan.apkOnly(Collections.singletonList(mServer.url()), sha, 0);
        UpdateDownloader.start(noDownloadManager, plan, sha, "Direct test", code);
        mDownloadId = mPrefs.getLong(KEY_PREFIX + "DOWNLOAD_ID", -1);
        assertTrue("recorded as a direct download: " + mDownloadId, UpdateDownloader.isDirect(mDownloadId));

        long deadline = SystemClock.elapsedRealtime() + WAIT_MS;
        while (!UpdateDownloader.isVerifiedReady(mApp, code) && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(500);
        }
        List<String> ranges = mServer.ranges();
        assertTrue("verified within " + (WAIT_MS / 1000) + " s; requests (Range per request): " + ranges
                        + ", record: " + updateKeys(),
                UpdateDownloader.isVerifiedReady(mApp, code));
        assertEquals("two requests: the dropped one and its continuation: " + ranges, 2, ranges.size());
        assertEquals("the first asks for the whole file", "-", ranges.get(0));
        assertEquals("the continuation asks for the rest", "bytes=" + DROP_AT + "-", ranges.get(1));
        assertEquals("the identity encoding is asked for", "identity", mServer.lastAcceptEncoding);
        assertEquals("the ready APK is the served file", sha, UpdatePatch.sha256Hex(mApk, null));
        assertEquals(sha, UpdateDownloader.readySha256(mApp));
        assertFalse("no part file left", partFilesLeft());

        // The installer's belt: bytes that changed after the check never reach Android.
        assertTrue("the verified APK is in place before the install", mApk.isFile());
        mPrefs.edit().putString(KEY_PREFIX + "READY_SHA",
                "0000000000000000000000000000000000000000000000000000000000000000").commit();
        Thread install = new Thread(() -> UpdateInstaller.install(mApp, "Direct test", false));
        install.start();
        install.join(WAIT_MS);
        assertFalse("the install finished", install.isAlive());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        UpdateInstallProgress.Snapshot state = UpdateInstaller.state().getValue();
        assertNotNull(state);
        assertEquals("a changed file fails the install", UpdateInstallProgress.Phase.FAILED, state.phase);
        assertFalse("and is dropped, so the next check downloads it again",
                UpdateDownloader.isVerifiedReady(mApp, code) || mApk.exists());
    }

    private boolean nonRoamingNetwork() {
        ConnectivityManager cm = mApp.getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
    }

    private boolean partFilesLeft() {
        File[] dirs = {mApp.getExternalFilesDir(null), mApp.getFilesDir()};
        for (File dir : dirs) {
            String[] names = dir == null ? null : dir.list();
            if (names == null) {
                continue;
            }
            for (String n : names) {
                if (n.startsWith(Preferences.UPDATE_APK + ".dl")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String updateKeys() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, ?> e : mPrefs.getAll().entrySet()) {
            if (e.getKey().startsWith(KEY_PREFIX)) {
                b.append(e.getKey().substring(KEY_PREFIX.length())).append('=').append(e.getValue()).append(' ');
            }
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor e, String key, Object value) {
        if (value instanceof Boolean) {
            e.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            e.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            e.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            e.putFloat(key, (Float) value);
        } else if (value instanceof String) {
            e.putString(key, (String) value);
        } else if (value instanceof Set) {
            e.putStringSet(key, (Set<String>) value);
        }
    }

    /**
     * HTTP/1.1 on 127.0.0.1 (the IPv4 literal: the loopback address Android
     * resolves first is ::1), one connection at a time, serving one file with
     * Range. The first answer can be cut after {@link #dropAfter} bytes.
     */
    private static final class MiniServer implements AutoCloseable {
        final File file;
        final ServerSocket socket;
        volatile long dropAfter = -1;
        volatile String lastAcceptEncoding;
        private final List<String> mRanges = Collections.synchronizedList(new ArrayList<>());

        MiniServer(File file) throws IOException {
            this.file = file;
            socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(this::loop, "update-direct-test-http");
            t.setDaemon(true);
            t.start();
        }

        String url() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/update.apk";
        }

        List<String> ranges() {
            synchronized (mRanges) {
                return new ArrayList<>(mRanges);
            }
        }

        private void loop() {
            while (!socket.isClosed()) {
                try (Socket c = socket.accept()) {
                    serve(c);
                } catch (IOException e) {
                    // closed, or the client went away
                }
            }
        }

        private void serve(Socket c) throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.ISO_8859_1));
            if (in.readLine() == null) {
                return;
            }
            String range = null;
            String h;
            while ((h = in.readLine()) != null && !h.isEmpty()) {
                int colon = h.indexOf(':');
                String name = h.substring(0, colon).trim().toLowerCase();
                String value = h.substring(colon + 1).trim();
                if (name.equals("range")) {
                    range = value;
                } else if (name.equals("accept-encoding")) {
                    lastAcceptEncoding = value;
                }
            }
            mRanges.add(range == null ? "-" : range);
            long length = file.length();
            long from = range == null ? 0 : Long.parseLong(range.substring(6, range.indexOf('-')));
            OutputStream out = c.getOutputStream();
            String head = from > 0
                    ? "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes " + from + "-" + (length - 1) + "/" + length + "\r\n"
                    : "HTTP/1.1 200 OK\r\n";
            out.write((head + "Content-Type: application/vnd.android.package-archive\r\nContent-Length: "
                    + (length - from) + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            long drop = dropAfter;
            dropAfter = -1;
            long send = drop >= 0 ? Math.min(drop, length - from) : length - from;
            byte[] buffer = new byte[64 * 1024];
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(from);
                long left = send;
                while (left > 0) {
                    int n = raf.read(buffer, 0, (int) Math.min(buffer.length, left));
                    if (n < 0) {
                        break;
                    }
                    out.write(buffer, 0, n);
                    left -= n;
                }
            }
            out.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}

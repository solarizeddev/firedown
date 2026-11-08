package com.solarized.firedown.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitor;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.di.RepositoryEntryPoint;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.IncognitoStateRepository;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.media.GeckoMediaController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import dagger.hilt.android.EntryPointAccessors;

/**
 * Issue #311 on a device: a web video in page fullscreen goes to a
 * picture-in-picture window when the user leaves the app; a video that is
 * not fullscreen keeps only its audio, as before.
 *
 * <p>The real app: the browser opened on a loopback page by a VIEW intent
 * from the shell (another app), a real tap ({@code input tap}) as the user
 * gesture that lets the page go fullscreen and play, the Home key from the
 * shell, and the activity read in place. Needs no network; plays a
 * near-silent tone. The PiP window is closed through its accessibility
 * dismiss action (what TalkBack's "close" does); a device whose system UI
 * does not offer it skips that case.
 *
 * <pre>
 * ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.phone.BrowserPipDeviceTest
 * </pre>
 *
 * Written for issue #311; not run on a device at write time.
 */
@RunWith(AndroidJUnit4.class)
public class BrowserPipDeviceTest {

    private static final String BROWSER = "com.solarized.firedown.phone.BrowserMainActivity";
    private static final long WAIT_MS = 20_000;
    private static final long SETTLE_MS = 1_500;

    private Instrumentation mInstrumentation;
    private Context mApp;
    private String mPkg;
    private SharedPreferences mPrefs;
    private GeckoStateDataRepository mTabs;
    private IncognitoStateRepository mPrivateTabs;
    private GeckoState mPrivateTab;
    private GeckoMediaController mMedia;
    private Boolean mSavedLock;
    private PageServer mServer;
    private final Recorder mRecorder = new Recorder();

    @Before
    public void setUp() throws IOException {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mApp = mInstrumentation.getTargetContext().getApplicationContext();
        mPkg = mApp.getPackageName();
        assumeTrue("no picture-in-picture on this device",
                mApp.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE));
        RepositoryEntryPoint entryPoint = EntryPointAccessors.fromApplication(mApp, RepositoryEntryPoint.class);
        mPrefs = entryPoint.getSharedPreferences();
        mTabs = entryPoint.getGeckoStateDataRepository();
        mPrivateTabs = entryPoint.getIncognitoStateRepository();
        mMedia = entryPoint.getGeckoMediaController();

        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        KeyguardManager keyguard = mApp.getSystemService(KeyguardManager.class);
        assertTrue("the phone is locked: unlock it before running this test",
                waitUntil(() -> !keyguard.isKeyguardLocked(), 3_000));
        // The user's app lock would put a lock screen over the expand.
        mSavedLock = mPrefs.contains(Preferences.SETTINGS_APP_LOCK)
                ? mPrefs.getBoolean(Preferences.SETTINGS_APP_LOCK, false) : null;
        mPrefs.edit().putBoolean(Preferences.SETTINGS_APP_LOCK, false).commit();

        finishStrayActivities();
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(mRecorder);
        mServer = new PageServer();
        mServer.start();
    }

    @After
    public void tearDown() {
        try {
            if (mPrivateTab != null) {
                GeckoState privateTab = mPrivateTab;
                mPrivateTab = null;
                onMain(() -> {
                    if (mPrivateTabs.getGeckoState(privateTab.getEntityId()) != null) {
                        mPrivateTabs.closeGeckoState(privateTab);
                    }
                    return null;
                });
            }
            GeckoState tab = currentTab();
            if (tab != null && tab.getEntityUri() != null
                    && tab.getEntityUri().startsWith(mServer.url(""))) {
                onMain(() -> {
                    mMedia.pause(tab.getEntityId());
                    mTabs.closeGeckoState(tab);
                    return null;
                });
            }
            for (Activity a : mRecorder.alive()) {
                onMain(() -> {
                    a.finish();
                    return null;
                });
            }
        } finally {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(mRecorder);
            if (mSavedLock == null) {
                mPrefs.edit().remove(Preferences.SETTINGS_APP_LOCK).commit();
            } else {
                mPrefs.edit().putBoolean(Preferences.SETTINGS_APP_LOCK, mSavedLock).commit();
            }
            if (mServer != null) {
                mServer.stop();
            }
        }
    }

    // ── The cases ───────────────────────────────────────────────────────────

    /** The report: fullscreen video, Home → a PiP window, not audio alone. */
    @Test
    public void fullscreenVideoGoesToPipOnHomeAndExpandsBackToFullscreen() {
        BrowserMainActivity browser = openPage("/");
        GeckoState tab = playFullscreen();

        shell("input keyevent KEYCODE_HOME");
        assertTrue("the browser never entered PiP. " + describe(browser, tab),
                waitUntil(() -> onMain(browser::isInPictureInPictureMode), WAIT_MS));
        SystemClock.sleep(SETTLE_MS);
        // Entering PiP changes smallestScreenSize: the activity must not be
        // recreated (its chrome would come back inside the window).
        assertEquals("the browser was recreated on entering PiP. " + describe(browser, tab),
                1, mRecorder.created(BrowserMainActivity.class).size());
        assertTrue("still fullscreen in PiP. " + describe(browser, tab), tab.isFullScreen());
        assertTrue("still playing in PiP. " + describe(browser, tab), mMedia.isPlaying(tab.getEntityId()));
        assertFalse("the toolbar shows in the PiP window", viewShown(browser, R.id.toolbar_layout));

        // Expand: the icon brings the task back, the window leaves PiP.
        shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "
                + mPkg + "/com.solarized.firedown.phone.BrowserActivity");
        assertTrue("the browser never left PiP. " + describe(browser, tab),
                waitUntil(() -> !onMain(browser::isInPictureInPictureMode)
                        && mRecorder.stage(browser) == Stage.RESUMED, WAIT_MS));
        SystemClock.sleep(SETTLE_MS);
        assertSame("the same browser came back", browser, mRecorder.resumed(BrowserMainActivity.class));
        assertTrue("expanded back to the fullscreen video. " + describe(browser, tab), tab.isFullScreen());
        assertTrue("the video kept playing. " + describe(browser, tab), mMedia.isPlaying(tab.getEntityId()));
        assertFalse("the toolbar came back over the fullscreen video", viewShown(browser, R.id.toolbar_layout));
    }

    /** Closing the window ends the video: paused, page out of fullscreen. */
    @Test
    public void closingThePipWindowPausesAndLeavesFullscreen() {
        BrowserMainActivity browser = openPage("/");
        // A private tab open meanwhile: closing the window removes the task
        // and finishes the browser on most devices, which must not end
        // private browsing.
        mPrivateTab = onMain(() -> {
            GeckoStateEntity entity = new GeckoStateEntity(false, mServer.url("/?private=1"));
            entity.setIncognito(true);
            GeckoState state = new GeckoState(entity);
            mPrivateTabs.setGeckoState(state, false);
            return state;
        });
        GeckoState tab = playFullscreen();
        shell("input keyevent KEYCODE_HOME");
        assertTrue("the browser never entered PiP. " + describe(browser, tab),
                waitUntil(() -> onMain(browser::isInPictureInPictureMode), WAIT_MS));
        SystemClock.sleep(SETTLE_MS);

        assumeTrue("this system UI offers no accessibility dismiss on the PiP window",
                dismissPipWindow());
        assertTrue("the video kept playing after the window was closed. " + describe(browser, tab),
                waitUntil(() -> !mMedia.isPlaying(tab.getEntityId()), WAIT_MS));
        assertTrue("the page stayed fullscreen after the window was closed. " + describe(browser, tab),
                waitUntil(() -> !tab.isFullScreen(), WAIT_MS));
        assertFalse("the browser is still in PiP", onMain(browser::isInPictureInPictureMode));
        SystemClock.sleep(SETTLE_MS);
        assertNotNull("closing the PiP window deleted the private tabs. " + describe(browser, tab),
                onMain(() -> mPrivateTabs.getGeckoState(mPrivateTab.getEntityId())));
    }

    /** The reporter's other case: not fullscreen → audio only, as before. */
    @Test
    public void videoNotFullscreenKeepsPlayingWithoutPip() {
        BrowserMainActivity browser = openPage("/?inline=1");
        tap();
        GeckoState tab = currentTab();
        assertNotNull(tab);
        assertTrue("the video never played. " + describe(browser, tab),
                waitUntil(() -> mMedia.isPlaying(tab.getEntityId()), WAIT_MS));
        assertFalse("the page went fullscreen", tab.isFullScreen());

        shell("input keyevent KEYCODE_HOME");
        assertTrue("the browser never stopped. " + describe(browser, tab),
                waitUntil(() -> mRecorder.stage(browser) == Stage.STOPPED, WAIT_MS));
        assertFalse("a page that is not fullscreen went to PiP", onMain(browser::isInPictureInPictureMode));
        assertTrue("the audio stopped in the background", mMedia.isPlaying(tab.getEntityId()));
    }

    /**
     * A page's alert() while it plays in the PiP window: no dialog in the
     * small window, and the page is answered at once (its script goes on).
     */
    @Test
    public void alertInThePipWindowIsAnsweredWithoutADialog() {
        BrowserMainActivity browser = openPage("/?alert=1");
        GeckoState tab = playFullscreen();
        shell("input keyevent KEYCODE_HOME");
        assertTrue("the browser never entered PiP. " + describe(browser, tab),
                waitUntil(() -> onMain(browser::isInPictureInPictureMode), WAIT_MS));
        // The page alerts when its viewport shrinks to the window's size,
        // then retitles itself.
        assertTrue("the page's script never got past alert() in PiP. " + describe(browser, tab),
                waitUntil(() -> "after-alert".equals(tab.getEntityTitle()), WAIT_MS));
        assertTrue("still in PiP", onMain(browser::isInPictureInPictureMode));
        // Prompt dialogs are plain AlertDialogs, marked on their tab.
        assertFalse("a dialog opened in the PiP window", tab.isPromptDisplaying());
        assertEquals("a sheet opened in the PiP window", "", onMain(() -> dialogsIn(
                browser.getSupportFragmentManager())));
    }

    // ── Driving the app ─────────────────────────────────────────────────────

    private BrowserMainActivity openPage(String path) {
        String url = mServer.url(path);
        shell("am start -W -a android.intent.action.VIEW -d " + url + " -n " + mPkg + "/" + BROWSER);
        assertTrue("the browser never resumed. " + mRecorder,
                waitUntil(() -> mRecorder.resumed(BrowserMainActivity.class) != null, WAIT_MS));
        BrowserMainActivity browser = mRecorder.resumed(BrowserMainActivity.class);
        assertTrue("the page never loaded in the current tab: " + describe(browser, currentTab()),
                waitUntil(() -> {
                    GeckoState tab = currentTab();
                    return tab != null && url.equals(tab.getEntityUri()) && !tab.isLoading()
                            && "ready".equals(tab.getEntityTitle());
                }, WAIT_MS));
        SystemClock.sleep(SETTLE_MS);
        return browser;
    }

    /** A real tap: the gesture the page needs for fullscreen and sound. */
    private GeckoState playFullscreen() {
        GeckoState tab = currentTab();
        assertNotNull(tab);
        tap();
        assertTrue("the page never went fullscreen and played: fullscreen=" + tab.isFullScreen()
                        + " playing=" + mMedia.isPlaying(tab.getEntityId()) + " title=" + tab.getEntityTitle(),
                waitUntil(() -> tab.isFullScreen() && mMedia.isPlaying(tab.getEntityId()), WAIT_MS));
        SystemClock.sleep(SETTLE_MS);
        return tab;
    }

    private void tap() {
        DisplayMetrics dm = mApp.getResources().getDisplayMetrics();
        shell("input tap " + dm.widthPixels / 2 + " " + dm.heightPixels / 2);
    }

    /** TalkBack's "close" on the PiP window (SystemUI's replacing connection). */
    private boolean dismissPipWindow() {
        UiAutomation ua = mInstrumentation.getUiAutomation();
        AccessibilityServiceInfo info = ua.getServiceInfo();
        if (info == null) {
            return false;
        }
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        ua.setServiceInfo(info);
        long deadline = SystemClock.uptimeMillis() + 5_000;
        while (SystemClock.uptimeMillis() < deadline) {
            for (AccessibilityWindowInfo window : ua.getWindows()) {
                if (!window.isInPictureInPictureMode()) {
                    continue;
                }
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null && root.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) {
                    return true;
                }
            }
            SystemClock.sleep(250);
        }
        return false;
    }

    /** A browser left by an earlier run would hide the one this test opens. */
    private void finishStrayActivities() {
        onMain(() -> {
            ActivityLifecycleMonitor monitor = ActivityLifecycleMonitorRegistry.getInstance();
            for (Stage stage : Stage.values()) {
                if (stage == Stage.DESTROYED) {
                    continue;
                }
                for (Activity a : monitor.getActivitiesInStage(stage)) {
                    a.finish();
                }
            }
            return null;
        });
        SystemClock.sleep(SETTLE_MS);
    }

    private GeckoState currentTab() {
        return onMain(mTabs::peekCurrentGeckoState);
    }

    private boolean viewShown(Activity activity, int id) {
        return onMain(() -> {
            View v = activity.findViewById(id);
            return v != null && v.isShown();
        });
    }

    private static String dialogsIn(FragmentManager fm) {
        StringBuilder out = new StringBuilder();
        for (Fragment f : fm.getFragments()) {
            if (f instanceof DialogFragment) {
                DialogFragment dialog = (DialogFragment) f;
                if (dialog.getDialog() != null && dialog.getDialog().isShowing()) {
                    out.append(f.getClass().getSimpleName()).append(' ');
                }
            }
            if (f.isAdded()) {
                out.append(dialogsIn(f.getChildFragmentManager()));
            }
        }
        return out.toString().trim();
    }

    private String describe(Activity browser, GeckoState tab) {
        return "browser stage=" + mRecorder.stage(browser)
                + " pip=" + onMain(browser::isInPictureInPictureMode)
                + " tab=" + (tab == null ? "none" : tab.getEntityUri()
                + " fullscreen=" + tab.isFullScreen()
                + " playing=" + mMedia.isPlaying(tab.getEntityId())
                + " title=" + tab.getEntityTitle()) + "; " + mRecorder;
    }

    // ── Threads and the shell ───────────────────────────────────────────────

    private <T> T onMain(Callable<T> call) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        mInstrumentation.runOnMainSync(() -> {
            try {
                result.set(call.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return result.get();
    }

    private static boolean waitUntil(BooleanSupplier condition, long ms) {
        long deadline = SystemClock.uptimeMillis() + ms;
        while (!condition.getAsBoolean()) {
            if (SystemClock.uptimeMillis() > deadline) {
                return false;
            }
            SystemClock.sleep(200);
        }
        return true;
    }

    private static String shell(String command) {
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            byte[] buffer = new byte[4096];
            int n = in.read(buffer);
            while (n != -1) {
                out.write(buffer, 0, n);
                n = in.read(buffer);
            }
        } catch (IOException e) {
            throw new AssertionError("shell: " + command, e);
        }
        return out.toString();
    }

    /** Every activity's last stage, in creation order. */
    private static final class Recorder implements ActivityLifecycleCallback {
        private final List<Activity> mCreated = new ArrayList<>();
        private final Map<Activity, Stage> mStages = new ConcurrentHashMap<>();

        @Override
        public synchronized void onActivityLifecycleChanged(Activity activity, Stage stage) {
            if (stage == Stage.PRE_ON_CREATE) {
                mCreated.add(activity);
            }
            mStages.put(activity, stage);
        }

        Stage stage(Activity activity) {
            return mStages.get(activity);
        }

        synchronized <T extends Activity> List<T> created(Class<T> cls) {
            List<T> out = new ArrayList<>();
            for (Activity a : mCreated) {
                if (a.getClass() == cls) {
                    out.add(cls.cast(a));
                }
            }
            return out;
        }

        <T extends Activity> T resumed(Class<T> cls) {
            for (T a : created(cls)) {
                if (stage(a) == Stage.RESUMED) {
                    return a;
                }
            }
            return null;
        }

        synchronized Collection<Activity> alive() {
            List<Activity> out = new ArrayList<>();
            for (Activity a : mCreated) {
                Stage s = mStages.get(a);
                if (s != Stage.DESTROYED) {
                    out.add(a);
                }
            }
            return out;
        }

        @Override
        public synchronized String toString() {
            StringBuilder out = new StringBuilder("activities:");
            for (Activity a : mCreated) {
                out.append(' ').append(a.getClass().getSimpleName()).append('=').append(mStages.get(a));
            }
            return out.toString();
        }
    }

    // ── The page ────────────────────────────────────────────────────────────

    /** Bound to 127.0.0.1 BY NAME (getLoopbackAddress is ::1 on Android). */
    private static final class PageServer {
        private final ServerSocket mSocket;
        private final byte[] mTone;
        private volatile boolean mRunning = true;

        PageServer() throws IOException {
            mSocket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            mTone = tone(30, 16_000);
        }

        String url(String path) {
            return "http://127.0.0.1:" + mSocket.getLocalPort() + path;
        }

        void start() {
            Thread t = new Thread(() -> {
                while (mRunning) {
                    try {
                        Socket client = mSocket.accept();
                        new Thread(() -> serve(client), "pip-page-client").start();
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "pip-page");
            t.setDaemon(true);
            t.start();
        }

        void stop() {
            mRunning = false;
            try {
                mSocket.close();
            } catch (IOException ignored) {
            }
        }

        private void serve(Socket client) {
            try (Socket c = client) {
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        c.getInputStream(), StandardCharsets.ISO_8859_1));
                String requestLine = in.readLine();
                String line = in.readLine();
                while (line != null && !line.isEmpty()) {
                    line = in.readLine();
                }
                String path = "/";
                if (requestLine != null) {
                    String[] parts = requestLine.split(" ");
                    if (parts.length >= 2) {
                        path = parts[1];
                    }
                }
                OutputStream out = c.getOutputStream();
                if (path.startsWith("/tone.wav")) {
                    respond(out, "audio/wav", mTone);
                } else if (path.equals("/") || path.startsWith("/?")) {
                    respond(out, "text/html; charset=utf-8", page().getBytes(StandardCharsets.UTF_8));
                } else {
                    respond(out, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
            } catch (IOException ignored) {
            }
        }

        private static void respond(OutputStream out, String type, byte[] body) throws IOException {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                    + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
        }

        /**
         * A tap anywhere plays the video, and puts it in fullscreen unless the
         * page was opened with ?inline=1. With ?alert=1 the page alerts once
         * its fullscreen viewport shrinks to under a quarter of its area (the
         * PiP window), then retitles itself: the title says the script got
         * past alert().
         */
        private static String page() {
            return "<!doctype html><html><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width\">"
                    + "<title>loading</title>"
                    + "<style>html,body{margin:0;height:100%;background:#000}"
                    + "video{width:100%;height:100%}</style></head>"
                    + "<body><video id=\"v\" src=\"/tone.wav\" loop playsinline></video>"
                    + "<script>"
                    + "const v = document.getElementById('v');"
                    + "const q = new URLSearchParams(location.search);"
                    + "v.volume = 0.02;"
                    + "document.addEventListener('click', function () {"
                    + "  if (!q.has('inline')) { v.requestFullscreen().catch(function (e) {"
                    + "    document.title = 'fullscreen refused: ' + e; }); }"
                    + "  v.play().catch(function (e) { document.title = 'play refused: ' + e; });"
                    + "});"
                    + "let fullArea = 0, alerted = false;"
                    + "document.addEventListener('fullscreenchange', function () {"
                    + "  if (document.fullscreenElement) { fullArea = innerWidth * innerHeight; }"
                    + "});"
                    + "addEventListener('resize', function () {"
                    + "  if (!q.has('alert') || alerted || !document.fullscreenElement || fullArea === 0) return;"
                    + "  if (innerWidth * innerHeight * 4 < fullArea) {"
                    + "    alerted = true; alert('in pip'); document.title = 'after-alert';"
                    + "  }"
                    + "});"
                    + "document.title = 'ready';"
                    + "</script></body></html>";
        }

        /** 16-bit mono PCM, a 440 Hz sine. */
        private static byte[] tone(int seconds, int rate) {
            int samples = seconds * rate;
            ByteBuffer buf = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
            buf.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples * 2)
                    .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                    .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                    .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2)
                    .putShort((short) 2).putShort((short) 16)
                    .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
            for (int i = 0; i < samples; i++) {
                buf.putShort((short) (Math.sin(2 * Math.PI * 440 * i / rate) * 12000));
            }
            return buf.array();
        }
    }
}

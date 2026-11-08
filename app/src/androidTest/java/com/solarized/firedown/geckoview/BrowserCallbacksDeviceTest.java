package com.solarized.firedown.geckoview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaDrm;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.di.RepositoryEntryPoint;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.IncognitoStateRepository;
import com.solarized.firedown.siteaudit.SiteAuditActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import dagger.hilt.android.EntryPointAccessors;

/**
 * The 1.2.7 browser-callback changes on a device, with no network: a page's
 * pop-up window and the DRM switch, through the app's own session delegates
 * ({@link GeckoComponents#connectSession}) on tabs registered in the real
 * repositories. No browser fragment is involved.
 *
 * <ul>
 *   <li>{@code popupGetsARealWindow}: a tap on a page that calls
 *       {@code window.open} gets a window, the window is listed as the
 *       opener's child tab, and its {@code window.opener.postMessage}
 *       reaches the opener (a login pop-up's shape). Before 1.2.7 the call
 *       returned null and the address loaded in an unrelated tab; the risk
 *       in the new code is GeckoView opening the session twice, which
 *       crashes the app, so the test process surviving is part of the
 *       check.</li>
 *   <li>{@code drm*}: with the switch off a page's request for Widevine is
 *       refused and the tab knows (the shield's dot); with it on the
 *       request is granted with no dialog, in a regular AND a private tab
 *       (issue #310). The "on" cases skip on a device without Widevine.</li>
 * </ul>
 *
 * <p>Run:
 * {@code ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.geckoview.BrowserCallbacksDeviceTest}
 */
@RunWith(AndroidJUnit4.class)
public class BrowserCallbacksDeviceTest {

    private static final long LOAD_MS = 30_000;
    private static final long PREF_MS = 10_000;
    private static final long POLL_MS = 200;
    private static final UUID WIDEVINE = new UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL);

    private static GeckoRuntimeHelper sHelper;
    private static GeckoStateDataRepository sTabs;
    private static IncognitoStateRepository sPrivateTabs;
    private static GeckoComponents sComponents;
    private static SharedPreferences sPrefs;

    private Context mApp;
    private PageServer mServer;
    private ActivityScenario<SiteAuditActivity> mScenario;
    private SiteAuditActivity mActivity;
    private final List<GeckoState> mOpened = new ArrayList<>();
    private GeckoState mPreviousCurrent;
    private boolean mDrmWasEnabled;

    @Before
    public void setUp() throws IOException {
        mApp = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        if (sHelper == null) {
            // Constructing GeckoRuntimeHelper creates the runtime and registers
            // every built-in extension: main thread only.
            onMain(() -> {
                RepositoryEntryPoint entryPoint = EntryPointAccessors.fromApplication(
                        mApp, RepositoryEntryPoint.class);
                sHelper = entryPoint.getGeckoRuntimeHelper();
                sTabs = entryPoint.getGeckoStateDataRepository();
                sPrivateTabs = entryPoint.getIncognitoStateRepository();
                sComponents = entryPoint.getGeckoComponents();
                sPrefs = entryPoint.getSharedPreferences();
            });
        }
        assertNotNull(sHelper);
        mDrmWasEnabled = Preferences.getDRMEnabled(sPrefs);
        mServer = new PageServer();
        mServer.start();
        mScenario = ActivityScenario.launch(SiteAuditActivity.class);
        AtomicReference<SiteAuditActivity> activity = new AtomicReference<>();
        mScenario.onActivity(activity::set);
        mActivity = activity.get();
        assertNotNull(mActivity);
        onMain(() -> mPreviousCurrent = sTabs.getCurrentGeckoState());
    }

    @After
    public void tearDown() {
        onMain(() -> {
            mActivity.getGeckoView().releaseSession();
            for (GeckoState tab : mOpened) {
                GeckoSession session = tab.getGeckoSession();
                if (tab.isIncognito()) {
                    sPrivateTabs.closeGeckoState(tab);
                } else {
                    sTabs.closeGeckoState(tab);
                }
                if (session != null && session.isOpen()) {
                    session.close();
                }
            }
            mOpened.clear();
            if (mPreviousCurrent != null && sTabs.getGeckoState(mPreviousCurrent.getEntityId()) != null) {
                sTabs.setGeckoState(mPreviousCurrent, true);
            }
        });
        setDrm(mDrmWasEnabled);
        if (mScenario != null) {
            mScenario.close();
        }
        if (mServer != null) {
            mServer.stop();
        }
    }

    /* ---------------------------------------------------------------------- */
    /* pop-up windows                                                         */
    /* ---------------------------------------------------------------------- */

    @Test
    public void popupGetsARealWindow() throws Exception {
        String openerUrl = mServer.url("/opener");
        String popupUrl = mServer.url("/popup");
        // The opener is the CURRENT tab, so the app also runs its "put the new
        // window on screen" step (the path that must not open the session a
        // second time).
        GeckoState opener = openTab(openerUrl, false, true);
        awaitTitle(opener, "opener:ready", LOAD_MS);

        tapCentre();
        awaitTitle(opener, "opener:hello", LOAD_MS);

        AtomicReference<GeckoState> popup = new AtomicReference<>();
        onMain(() -> popup.set(sTabs.getGeckoState(popupUrl)));
        assertNotNull("the pop-up is listed as a tab", popup.get());
        mOpened.add(popup.get());
        assertEquals("the pop-up's parent is the opener",
                opener.getEntityId(), popup.get().getGeckoStateEntity().getParentId());
        AtomicReference<Boolean> open = new AtomicReference<>(false);
        AtomicReference<Integer> current = new AtomicReference<>(-1);
        onMain(() -> {
            GeckoSession session = popup.get().getGeckoSession();
            open.set(session != null && session.isOpen());
            GeckoState now = sTabs.getCurrentGeckoState();
            current.set(now == null ? -1 : now.getEntityId());
        });
        assertTrue("the pop-up's session is open", open.get());
        assertEquals("the pop-up from the tab on screen becomes the tab on screen",
                popup.get().getEntityId(), (int) current.get());
    }

    /* ---------------------------------------------------------------------- */
    /* DRM                                                                    */
    /* ---------------------------------------------------------------------- */

    @Test
    public void drmOffIsRefusedAndTheTabKnows() throws Exception {
        setDrm(false);
        GeckoState tab = openTab(mServer.url("/drm"), false, false);
        String title = awaitTitlePrefix(tab, "drm:", LOAD_MS);
        assertTrue("refused with the switch off: " + title, title.startsWith("drm:refused"));
        assertTrue("the tab knows DRM was refused here (shield dot)", tab.isDrmRefusedHere());
    }

    @Test
    public void drmOffIsRefusedInAPrivateTab() throws Exception {
        setDrm(false);
        GeckoState tab = openTab(mServer.url("/drm"), true, false);
        String title = awaitTitlePrefix(tab, "drm:", LOAD_MS);
        assertTrue("refused with the switch off: " + title, title.startsWith("drm:refused"));
    }

    @Test
    public void drmOnIsGrantedWithNoDialog() throws Exception {
        assumeTrue("no Widevine on this device", MediaDrm.isCryptoSchemeSupported(WIDEVINE));
        setDrm(true);
        GeckoState tab = openTab(mServer.url("/drm"), false, false);
        String title = awaitTitlePrefix(tab, "drm:", LOAD_MS);
        assertEquals("granted with the switch on", "drm:granted", title);
        assertTrue("the tab remembers the grant", tab.isDrmGrantedHere());
    }

    @Test
    public void drmOnIsGrantedInAPrivateTab() throws Exception {
        assumeTrue("no Widevine on this device", MediaDrm.isCryptoSchemeSupported(WIDEVINE));
        setDrm(true);
        GeckoState tab = openTab(mServer.url("/drm"), true, false);
        String title = awaitTitlePrefix(tab, "drm:", LOAD_MS);
        assertEquals("granted in a private tab too (issue #310)", "drm:granted", title);
    }

    /* ---------------------------------------------------------------------- */
    /* helpers                                                                */
    /* ---------------------------------------------------------------------- */

    /** The switch exactly as Settings sets it: the preference, then the Gecko prefs. */
    private void setDrm(boolean enabled) {
        sPrefs.edit().putBoolean(Preferences.SETTINGS_ENABLE_DRM, enabled).commit();
        AtomicReference<GeckoResult<Void>> result = new AtomicReference<>();
        onMain(() -> result.set(sHelper.setDRM(!enabled)));
        long deadline = SystemClock.elapsedRealtime() + PREF_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                result.get().poll(POLL_MS);
                return;
            } catch (Throwable notYet) {
                // still pending, or failed: try again until the deadline
            }
        }
    }

    private GeckoState openTab(String url, boolean incognito, boolean current) {
        AtomicReference<GeckoState> made = new AtomicReference<>();
        GeckoRuntime runtime = sHelper.getGeckoRuntime();
        onMain(() -> {
            // The constructor's flag is "home", not private: the mode is set apart.
            GeckoStateEntity entity = new GeckoStateEntity(false, url);
            entity.setIncognito(incognito);
            GeckoState tab = new GeckoState(entity);
            if (incognito) {
                sPrivateTabs.setGeckoState(tab, current);
            } else {
                sTabs.setGeckoState(tab, current);
            }
            GeckoSession session = tab.getOrCreateGeckoSession(sHelper.blocksTrackingContent(incognito));
            sComponents.connectSession(session);
            session.open(runtime);
            mActivity.getGeckoView().setSession(session);
            session.setActive(true);
            runtime.getWebExtensionController().setTabActive(session, true);
            session.loadUri(url);
            made.set(tab);
        });
        mOpened.add(made.get());
        return made.get();
    }

    /** A finger on the middle of the page (the opener's button fills it). */
    private void tapCentre() {
        View view = mActivity.getGeckoView();
        int[] at = new int[2];
        onMain(() -> view.getLocationOnScreen(at));
        float x = at[0] + view.getWidth() / 2f;
        float y = at[1] + view.getHeight() / 2f;
        long down = SystemClock.uptimeMillis();
        InstrumentationRegistry.getInstrumentation().sendPointerSync(
                MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0));
        InstrumentationRegistry.getInstrumentation().sendPointerSync(
                MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0));
    }

    private String titleOf(GeckoState tab) {
        AtomicReference<String> title = new AtomicReference<>();
        onMain(() -> title.set(tab.getGeckoStateEntity().getTitle()));
        return title.get() == null ? "" : title.get();
    }

    private void awaitTitle(GeckoState tab, String title, long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (title.equals(titleOf(tab))) {
                return;
            }
            Thread.sleep(POLL_MS);
        }
        throw new AssertionError("page never titled \"" + title + "\"; last: \"" + titleOf(tab) + "\"");
    }

    private String awaitTitlePrefix(GeckoState tab, String prefix, long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            String title = titleOf(tab);
            if (title.startsWith(prefix)) {
                return title;
            }
            Thread.sleep(POLL_MS);
        }
        throw new AssertionError("page never titled \"" + prefix + "…\"; last: \"" + titleOf(tab) + "\"");
    }

    private static void onMain(Runnable r) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(r);
    }

    /* ---------------------------------------------------------------------- */
    /* the pages                                                              */
    /* ---------------------------------------------------------------------- */

    /** A loopback HTTP server for the three pages. IPv4 by name, see MediaNotificationTest. */
    private static final class PageServer {
        private final ServerSocket mSocket;
        private volatile boolean mRunning = true;

        PageServer() throws IOException {
            mSocket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        }

        String url(String path) {
            return "http://127.0.0.1:" + mSocket.getLocalPort() + path;
        }

        void start() {
            Thread t = new Thread(() -> {
                while (mRunning) {
                    try {
                        Socket client = mSocket.accept();
                        new Thread(() -> serve(client), "page-server-client").start();
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "page-server");
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
                BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.ISO_8859_1));
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
                String body;
                if (path.startsWith("/opener")) {
                    body = OPENER;
                } else if (path.startsWith("/popup")) {
                    body = POPUP;
                } else if (path.startsWith("/drm")) {
                    body = DRM;
                } else {
                    body = "<!doctype html><title>404</title>";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                OutputStream out = c.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                        + bytes.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.write(bytes);
                out.flush();
            } catch (IOException ignored) {
            }
        }

        /** A button over the whole page; a tap opens a window and waits for its message. */
        private static final String OPENER = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width\"><title>opener:loading</title></head>"
                + "<body style=\"margin:0\"><button id=\"b\" style=\"position:fixed;inset:0;width:100%;height:100%\">Open</button>"
                + "<script>"
                + "window.addEventListener('message', function (e) { document.title = 'opener:' + e.data; });"
                + "document.getElementById('b').addEventListener('click', function () {"
                + "  var w = window.open('/popup', '_blank');"
                + "  document.title = w ? 'opener:opened' : 'opener:null';"
                + "});"
                + "document.title = 'opener:ready';"
                + "</script></body></html>";

        /** The pop-up answers its opener, as a login pop-up does. */
        private static final String POPUP = "<!doctype html><html><head><meta charset=\"utf-8\"><title>popup</title></head>"
                + "<body><script>"
                + "if (window.opener) { window.opener.postMessage('hello', '*'); document.title = 'popup:sent'; }"
                + "else { document.title = 'popup:no-opener'; }"
                + "</script></body></html>";

        /** Asks for Widevine the way a streaming site does. */
        private static final String DRM = "<!doctype html><html><head><meta charset=\"utf-8\"><title>asking</title></head>"
                + "<body><script>"
                + "document.title = 'asking';"
                + "navigator.requestMediaKeySystemAccess('com.widevine.alpha', [{"
                + "  initDataTypes: ['cenc'],"
                + "  videoCapabilities: [{ contentType: 'video/mp4; codecs=\"avc1.42E01E\"' }]"
                + "}]).then(function () { document.title = 'drm:granted'; })"
                + ".catch(function (e) { document.title = 'drm:refused ' + e.name; });"
                + "</script></body></html>";
    }
}

package com.solarized.firedown.geckoview.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solarized.firedown.data.di.RepositoryEntryPoint;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.geckoview.GeckoComponents;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.siteaudit.SiteAuditActivity;
import com.solarized.firedown.utils.NotificationID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebRequestError;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import dagger.hilt.android.EntryPointAccessors;

/**
 * Issue #308 on a device, with no network: a local page plays a tone and
 * publishes {@code navigator.mediaSession.metadata} (title, artist, artwork),
 * the way a media site does, and the test asserts the playback notification
 * the user sees carries that title and a picture — and follows the page when
 * it moves on to the next clip, the autoplay-next shape the report described.
 *
 * <p>Everything between the page and the notification is the app's own: the
 * real runtime, the real {@code GeckoComponents} media-session and permission
 * delegates on a tab registered in the real repository, the real controller
 * (which fetches the artwork itself since #308) and the real playback
 * service. No browser fragment is involved, which is exactly the case #308
 * was about: the metadata arrives while no UI is looking.
 *
 * <p>The page is served from a loopback socket; the artwork is a solid red
 * PNG for the first clip and a solid blue one for the second, so the picture
 * on the notification can be told from the favicon/letter-tile fallback by
 * its colour. The tone plays at near-zero volume for ~15 s.
 *
 * <p>Run:
 * {@code ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.geckoview.media.MediaNotificationTest}
 */
@RunWith(AndroidJUnit4.class)
public class MediaNotificationTest {

    private static final String TITLE_ONE = "Clip One";
    private static final String TITLE_TWO = "Clip Two";
    private static final String ARTIST = "Firedown test";
    private static final long FIRST_NOTIFICATION_MS = 45_000;
    private static final long ARTWORK_MS = 20_000;
    private static final long SECOND_CLIP_MS = 25_000;
    private static final long POLL_MS = 250;
    private static final int ART_SIZE = 256;

    private static GeckoRuntimeHelper sHelper;
    private static GeckoStateDataRepository sTabs;
    private static GeckoComponents sComponents;
    private static GeckoMediaController sController;

    private Context mApp;
    private PageServer mServer;
    private ActivityScenario<SiteAuditActivity> mScenario;
    private SiteAuditActivity mActivity;
    private GeckoState mTab;
    private final AtomicReference<String> mPageTitle = new AtomicReference<>();
    private final AtomicReference<String> mLoadError = new AtomicReference<>();

    @Before
    public void setUp() throws IOException {
        mApp = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        if (sHelper == null) {
            // Constructing GeckoRuntimeHelper CREATES the GeckoRuntime and
            // registers every built-in extension — main thread only.
            onMain(() -> {
                RepositoryEntryPoint entryPoint = EntryPointAccessors.fromApplication(
                        mApp, RepositoryEntryPoint.class);
                sHelper = entryPoint.getGeckoRuntimeHelper();
                sTabs = entryPoint.getGeckoStateDataRepository();
                sComponents = entryPoint.getGeckoComponents();
                sController = entryPoint.getGeckoMediaController();
            });
        }
        assertNotNull(sHelper);
        assertNotNull(sTabs);
        assertNotNull(sComponents);
        assertNotNull(sController);
        if (Build.VERSION.SDK_INT >= 33) {
            // A foreground service's notification is posted regardless, but
            // getActiveNotifications is only meaningful with the permission.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(mApp.getPackageName(), "android.permission.POST_NOTIFICATIONS");
        }
        mServer = new PageServer();
        mServer.start();
        // The service is started with startService() from the controller, which
        // Android allows only while the app has a foreground activity.
        mScenario = ActivityScenario.launch(SiteAuditActivity.class);
        AtomicReference<SiteAuditActivity> activity = new AtomicReference<>();
        mScenario.onActivity(activity::set);
        mActivity = activity.get();
        assertNotNull(mActivity);
    }

    @After
    public void tearDown() {
        onMain(() -> {
            if (mTab != null) {
                GeckoSession session = mTab.getGeckoSession();
                // Closing the tab stops its media and the service with it.
                sTabs.closeGeckoState(mTab);
                if (session != null) {
                    if (mActivity != null) {
                        mActivity.getGeckoView().releaseSession();
                    }
                    session.close();
                }
                mTab = null;
            }
        });
        if (mScenario != null) {
            mScenario.close();
        }
        if (mServer != null) {
            mServer.stop();
        }
    }

    @Test
    public void notificationCarriesThePageTitleAndArtwork() throws Exception {
        String url = mServer.url("/");
        GeckoRuntime runtime = sHelper.getGeckoRuntime();
        onMain(() -> {
            mTab = new GeckoState(new GeckoStateEntity(false, url));
            // Registered (so the delegates find the tab) but NOT made the
            // current tab: the user's own current tab is left as it was.
            sTabs.setGeckoState(mTab, false);
            GeckoSession session = mTab.getOrCreateGeckoSession(sHelper.blocksTrackingContent(false));
            session.setMediaSessionDelegate(sComponents.getMediaSessionDelegate());
            session.setPermissionDelegate(sComponents.getPermissionDelegate());
            session.setContentDelegate(new GeckoSession.ContentDelegate() {
                @Override
                public void onTitleChange(GeckoSession s, String title) {
                    mPageTitle.set(title);
                }
            });
            // A failed page load names itself in the assertion instead of
            // showing as the engine's "Problem loading page" title alone.
            session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
                @Override
                public GeckoResult<String> onLoadError(GeckoSession s, String uri, WebRequestError error) {
                    mLoadError.set(uri + " -> code " + error.code + " category " + error.category);
                    return null;
                }
            });
            sHelper.registerSession(session);
            session.open(runtime);
            mActivity.getGeckoView().setSession(session);
            session.setActive(true);
            runtime.getWebExtensionController().setTabActive(session, true);
            session.loadUri(url);
        });
        int sessionId = mTab.getEntityId();

        // 1. The first clip: title from the page, picture from the page's artwork.
        Notification first = awaitNotification(TITLE_ONE, true, FIRST_NOTIFICATION_MS);
        assertEquals(ARTIST, String.valueOf(first.extras.getCharSequence(Notification.EXTRA_TEXT)));
        Bitmap art = awaitArtwork(sessionId, Color.RED, ARTWORK_MS);
        assertTrue("artwork is the page's, not a tile", isSolid(art, Color.RED));

        // 2. The page moves on to its next clip while nothing but the
        //    notification is looking: new title, new picture, old one gone.
        awaitNotification(TITLE_TWO, true, SECOND_CLIP_MS);
        Bitmap art2 = awaitArtwork(sessionId, Color.BLUE, ARTWORK_MS);
        assertTrue("second clip's artwork", isSolid(art2, Color.BLUE));
        GeckoMetaData meta = sController.getGeckoMetaData(sessionId);
        assertNotNull(meta);
        assertEquals(TITLE_TWO, meta.getTitle());
    }

    /* ---------------------------------------------------------------------- */
    /* waits                                                                  */
    /* ---------------------------------------------------------------------- */

    private Notification awaitNotification(String title, boolean withPicture, long timeoutMs)
            throws InterruptedException {
        NotificationManager manager = (NotificationManager) mApp.getSystemService(Context.NOTIFICATION_SERVICE);
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = "none";
        while (System.currentTimeMillis() < deadline) {
            StatusBarNotification[] active = manager.getActiveNotifications();
            for (StatusBarNotification sbn : active) {
                if (sbn.getId() != NotificationID.MEDIA_ID) {
                    continue;
                }
                Notification n = sbn.getNotification();
                CharSequence shown = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                boolean pictured = n.extras.get(Notification.EXTRA_LARGE_ICON) != null;
                last = shown + (pictured ? " (with picture)" : " (no picture)");
                if (title.contentEquals(shown == null ? "" : shown) && (!withPicture || pictured)) {
                    return n;
                }
            }
            Thread.sleep(POLL_MS);
        }
        throw new AssertionError("no media notification titled \"" + title + "\""
                + (withPicture ? " with a picture" : "") + " within " + timeoutMs + " ms; last seen: " + last
                + "; page title: " + mPageTitle.get() + "; load error: " + mLoadError.get());
    }

    private Bitmap awaitArtwork(int sessionId, int colour, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            GeckoMetaData meta = sController.getGeckoMetaData(sessionId);
            Bitmap bitmap = meta == null ? null : meta.getBitmap();
            if (bitmap != null && isSolid(bitmap, colour)) {
                return bitmap;
            }
            Thread.sleep(POLL_MS);
        }
        GeckoMetaData meta = sController.getGeckoMetaData(sessionId);
        throw new AssertionError("controller never held the " + Integer.toHexString(colour)
                + " artwork for session " + sessionId + "; meta=" + (meta == null ? "null"
                : meta.getTitle() + " bitmap=" + meta.getBitmap()) + "; page title: " + mPageTitle.get()
                + "; load error: " + mLoadError.get());
    }

    /** The centre pixel is the colour asked for (the fallback tile is a letter on a tint, never solid). */
    private static boolean isSolid(Bitmap bitmap, int colour) {
        if (bitmap == null || bitmap.getWidth() == 0 || bitmap.getHeight() == 0) {
            return false;
        }
        int px = bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2);
        return Math.abs(Color.red(px) - Color.red(colour)) < 40
                && Math.abs(Color.green(px) - Color.green(colour)) < 40
                && Math.abs(Color.blue(px) - Color.blue(colour)) < 40;
    }

    private static void onMain(Runnable r) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(r);
    }

    /* ---------------------------------------------------------------------- */
    /* the page                                                               */
    /* ---------------------------------------------------------------------- */

    /**
     * A loopback HTTP server: the page, a 20 s PCM tone and two solid PNGs.
     * Every response is a whole body with a length; Range is ignored, which
     * Gecko accepts for a resource this small.
     */
    private static final class PageServer {
        private final ServerSocket mSocket;
        private final byte[] mHtml;
        private final byte[] mTone;
        private final byte[] mArtRed;
        private final byte[] mArtBlue;
        private volatile boolean mRunning = true;

        PageServer() throws IOException {
            // The IPv4 loopback by name: on Android getLoopbackAddress() is the
            // IPv6 ::1, and a server bound there answers nothing at the
            // http://127.0.0.1 URL the page is loaded from (the engine refused
            // the connection and showed its own error page). Bind and connect
            // must be the same family — the same rule the app's loopback
            // server for the share engine follows.
            mSocket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            mHtml = page().getBytes(StandardCharsets.UTF_8);
            mTone = tone(20, 16_000);
            mArtRed = png(Color.RED);
            mArtBlue = png(Color.BLUE);
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
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    // headers are not needed
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
                } else if (path.startsWith("/art.png")) {
                    respond(out, "image/png", mArtRed);
                } else if (path.startsWith("/art2.png")) {
                    respond(out, "image/png", mArtBlue);
                } else if (path.equals("/") || path.startsWith("/?")) {
                    respond(out, "text/html; charset=utf-8", mHtml);
                } else {
                    byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
                    out.write(("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: "
                            + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.write(body);
                }
                out.flush();
            } catch (IOException ignored) {
            }
        }

        private static void respond(OutputStream out, String type, byte[] body) throws IOException {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                    + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
        }

        /**
         * A page that plays its audio and publishes per-clip metadata the way a
         * media site does: the first clip's as soon as playback starts, the
         * second clip's six seconds later, as an autoplaying playlist would.
         */
        private static String page() {
            return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Media notification test</title></head>"
                    + "<body><audio id=\"a\" src=\"/tone.wav\" loop></audio>"
                    + "<script>"
                    + "const a = document.getElementById('a');"
                    + "a.volume = 0.02;"
                    + "function meta(title, art) {"
                    + "  navigator.mediaSession.metadata = new MediaMetadata({ title: title, artist: '" + ARTIST + "',"
                    + "    album: 'Device test', artwork: [{ src: location.origin + art, sizes: '" + ART_SIZE + "x" + ART_SIZE + "', type: 'image/png' }] });"
                    + "}"
                    + "a.play().then(function () {"
                    + "  meta('" + TITLE_ONE + "', '/art.png');"
                    + "  setTimeout(function () { meta('" + TITLE_TWO + "', '/art2.png'); }, 6000);"
                    + "}).catch(function (e) { document.title = 'autoplay refused: ' + e; });"
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

        private static byte[] png(int colour) {
            Bitmap bitmap = Bitmap.createBitmap(ART_SIZE, ART_SIZE, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(colour);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            bitmap.recycle();
            return out.toByteArray();
        }
    }
}

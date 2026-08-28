package com.solarized.firedown.geckoview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solarized.firedown.data.di.RepositoryEntryPoint;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.repository.BrowserDownloadRepository;

import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebExtension;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import dagger.hilt.android.EntryPointAccessors;

/**
 * ON-DEVICE end-to-end capture tests against LIVE sites — the layer no
 * offline suite can be: the REAL GeckoView (real fingerprint, real cookies,
 * real page JS), the real bundled extensions, the real native pipeline
 * (message delegate → GeckoInspectTask → probe → BrowserDownloadRepository).
 * A page is loaded exactly as a user would load it and the test asserts a
 * capture entity actually appears in the Captured-sheet repository.
 *
 * <p>This answers "does capture still work against TODAY'S site" for the
 * sites reachable logged-out with no play tap:
 * Telegram (t.me post), Vimeo (watch page), Apple Podcasts (show page), and
 * the generic catcher's DOM scrape (a Wikimedia Commons video page). A
 * FAILURE here with working network usually means the SITE changed — start
 * CLAUDE.md's "video not captured" debugging order (fresh HAR first).
 *
 * <p>Deliberately NOT here: Instagram/Facebook/Twitter/TikTok/Niconico
 * (login-walled or feed-nondeterministic on a fresh profile — a red test
 * would measure the wall, not the parser) and Dailymotion/Twitch/Kick
 * (capture keys on the player's own fetches, which need a play tap or a live
 * channel — nondeterministic in CI terms). Those stay covered by the offline
 * replays plus manual on-device use.
 *
 * <p>Run (device or emulator attached, WITH NETWORK):
 * <pre>
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.geckoview.CaptureLiveTest
 * </pre>
 *
 * <p>Tests share one booted runtime and run in name order (t1…t5); the
 * runtime is a process singleton so per-test isolation is impossible by
 * construction (the UblockBridgeLiveTest model). Pinned URLs are long-lived
 * public content; if one dies, replace it — a 404'd pin fails with the
 * page-specific message, not a mystery.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class CaptureLiveTest {

    private static final String TAG = "CaptureLiveTest";

    private static final String WEBREQUESTS_URI = "resource://android/assets/webrequests/";
    private static final String WEBREQUESTS_ID = "downloader@solarized.dev";

    // Pinned public content. Long-lived choices; update if one disappears.
    private static final String TELEGRAM_POST = "https://t.me/WatcherGuru/14028";
    private static final String VIMEO_PAGE = "https://vimeo.com/76979871";
    private static final String APPLE_SHOW = "https://podcasts.apple.com/us/podcast/the-daily/id1200361736";
    private static final String COMMONS_VIDEO_PAGE =
            "https://commons.wikimedia.org/wiki/File:Big_Buck_Bunny_4K.webm";

    private static final long BOOT_TIMEOUT_MS = 120_000;
    // Page load + extension parse + (for probed captures) a network probe.
    private static final long CAPTURE_TIMEOUT_MS = 90_000;

    private static GeckoRuntimeHelper sHelper;
    private static BrowserDownloadRepository sCaptures;
    private static GeckoSession sSession;

    @Before
    public void boot() {
        if (sHelper != null) { return; }
        Context app = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        // Constructing GeckoRuntimeHelper CREATES the GeckoRuntime and
        // registers every built-in extension — main thread only.
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            RepositoryEntryPoint entryPoint = EntryPointAccessors.fromApplication(
                    app, RepositoryEntryPoint.class);
            sHelper = entryPoint.getGeckoRuntimeHelper();
            sCaptures = entryPoint.getBrowserDownloadRepository();
        });
        assertNotNull(sHelper);
        assertNotNull(sCaptures);
    }

    @AfterClass
    public static void closeSession() {
        if (sSession == null) { return; }
        // Every session opened must end up closed (the GeckoInspectTask rule).
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            sSession.close();
            sSession = null;
        });
    }

    /* ---------------------------------------------------------------------- */
    /* t1 — the capture extension the runtime runs IS the one in this APK      */
    /* ---------------------------------------------------------------------- */

    /**
     * The {@code ensureBuiltIn} version-cache trap, probed for the CAPTURE
     * extension: after an in-place update a stale registration silently keeps
     * running the OLD parser bundle — every "new parser doesn't fire" report
     * starts here. Compares the registered version against the manifest in
     * this APK's assets (the uBlock t1 pattern).
     */
    @Test
    public void t1_registeredVersionMatchesBundledManifest() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        String bundledVersion;
        try (InputStream in = app.getAssets().open("webrequests/manifest.json")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n = in.read(chunk);
            while (n != -1) {
                buf.write(chunk, 0, n);
                n = in.read(chunk);
            }
            JSONObject manifest = new JSONObject(buf.toString(StandardCharsets.UTF_8.name()));
            bundledVersion = manifest.getString("version");
        }
        assertNotNull(bundledVersion);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<WebExtension> registered = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                sHelper.getGeckoRuntime().getWebExtensionController()
                        .ensureBuiltIn(WEBREQUESTS_URI, WEBREQUESTS_ID)
                        .accept(ext -> {
                            registered.set(ext);
                            latch.countDown();
                        }, throwable -> {
                            error.set(throwable);
                            latch.countDown();
                        }));
        assertTrue("ensureBuiltIn did not resolve within the boot window",
                latch.await(BOOT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertNull("ensureBuiltIn rejected: " + error.get(), error.get());
        WebExtension ext = registered.get();
        assertNotNull("ensureBuiltIn resolved null", ext);
        assertNotNull(ext.metaData);
        assertEquals("registered webrequests version != bundled manifest version — "
                        + "stale ensureBuiltIn registration (the version-cache trap): "
                        + "bump webrequests/manifest.json or clean-install",
                bundledVersion, ext.metaData.version);
        Log.i(TAG, "t1: registered webrequests " + ext.metaData.version);
    }

    /* ---------------------------------------------------------------------- */
    /* t2 — Telegram public post (parser, doc filter, no play needed)          */
    /* ---------------------------------------------------------------------- */

    @Test
    public void t2_telegramPostCapture() throws Exception {
        loadPage(TELEGRAM_POST);
        BrowserDownloadEntity hit = awaitCapture(
                e -> urlContains(e, "cdn-telegram.org") || urlContains(e, "telesco.pe"),
                CAPTURE_TIMEOUT_MS);
        assertNotNull("no Telegram CDN capture from " + TELEGRAM_POST
                + " — the t.me widget markup or the embed sub_frame flow changed "
                + "(or the pinned post was deleted; pick any public post with video)", hit);
        Log.i(TAG, "t2: telegram captured " + hit.getFileUrl()
                + " name=" + hit.getFileName());
    }

    /* ---------------------------------------------------------------------- */
    /* t3 — Vimeo watch page (parser, embedded player config, pre-play)        */
    /* ---------------------------------------------------------------------- */

    @Test
    public void t3_vimeoPageCapture() throws Exception {
        loadPage(VIMEO_PAGE);
        BrowserDownloadEntity hit = awaitCapture(
                e -> originContains(e, "vimeo.com") || urlContains(e, "vimeocdn"),
                CAPTURE_TIMEOUT_MS);
        assertNotNull("no capture from " + VIMEO_PAGE
                + " — the player config shape (request.files.hls) or the "
                + "sub_frame trigger changed (or the pinned video is gone)", hit);
        Log.i(TAG, "t3: vimeo captured " + hit.getFileUrl()
                + " name=" + hit.getFileName());
    }

    /* ---------------------------------------------------------------------- */
    /* t4 — Apple Podcasts show page (parser, amp-api XHR, pre-play)           */
    /* ---------------------------------------------------------------------- */

    @Test
    public void t4_applePodcastsShowCapture() throws Exception {
        loadPage(APPLE_SHOW);
        BrowserDownloadEntity hit = awaitCapture(
                e -> originContains(e, "podcasts.apple.com"),
                CAPTURE_TIMEOUT_MS);
        assertNotNull("no episode capture from " + APPLE_SHOW
                + " — the amp-api show XHR (include=episodes / assetUrl) "
                + "changed, or the lookup fallback broke", hit);
        Log.i(TAG, "t4: apple captured " + hit.getFileUrl()
                + " name=" + hit.getFileName());
    }

    /* ---------------------------------------------------------------------- */
    /* t5 — generic catcher (DOM scrape of a plain <video> page)               */
    /* ---------------------------------------------------------------------- */

    @Test
    public void t5_genericCatcherDomScrape() throws Exception {
        loadPage(COMMONS_VIDEO_PAGE);
        BrowserDownloadEntity hit = awaitCapture(
                e -> urlContains(e, "upload.wikimedia.org"),
                CAPTURE_TIMEOUT_MS);
        assertNotNull("no capture from " + COMMONS_VIDEO_PAGE
                + " — the generic catcher's content-script scrape / HEAD-probe "
                + "path broke (this one is OUR pipeline, not a site change)", hit);
        Log.i(TAG, "t5: generic captured " + hit.getFileUrl()
                + " mime=" + hit.getMimeType());
    }

    /* ---------------------------------------------------------------------- */
    /* plumbing                                                                */
    /* ---------------------------------------------------------------------- */

    private static boolean urlContains(BrowserDownloadEntity e, String needle) {
        String url = e.getFileUrl();
        return url != null && url.contains(needle);
    }

    private static boolean originContains(BrowserDownloadEntity e, String needle) {
        String origin = e.getFileOrigin();
        return origin != null && origin.contains(needle);
    }

    private interface Check { boolean ok(BrowserDownloadEntity entity); }

    /** Waits until the Captured repository holds an entity passing {@code check}. */
    private static BrowserDownloadEntity awaitCapture(Check check, long timeoutMs)
            throws InterruptedException {
        LiveData<List<BrowserDownloadEntity>> live = sCaptures.getData();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<BrowserDownloadEntity> seen = new AtomicReference<>();
        Observer<List<BrowserDownloadEntity>> observer = list -> {
            if (list == null) { return; }
            for (BrowserDownloadEntity entity : list) {
                if (check.ok(entity)) {
                    seen.set(entity);
                    latch.countDown();
                    return;
                }
            }
        };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> live.observeForever(observer));
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> live.removeObserver(observer));
        }
        return seen.get();
    }

    /**
     * One shared visible-tab session (the UblockBridgeLiveTest pattern):
     * delegates registered BEFORE open, marked active AND the active tab, so
     * content scripts run, the tabs API resolves it, and the priority
     * executor treats its captures as foreground.
     */
    private void loadPage(String url) {
        GeckoRuntime runtime = sHelper.getGeckoRuntime();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (sSession == null) {
                GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                        .usePrivateMode(false)
                        .allowJavascript(true)
                        .build();
                GeckoSession s = new GeckoSession(settings);
                sHelper.registerSession(s);
                s.open(runtime);
                s.setActive(true);
                runtime.getWebExtensionController().setTabActive(s, true);
                sSession = s;
            }
            sSession.loadUri(url);
        });
    }
}

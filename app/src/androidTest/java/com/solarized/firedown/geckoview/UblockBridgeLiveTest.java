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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dagger.hilt.android.EntryPointAccessors;

/**
 * ON-DEVICE end-to-end tests of the Firedown↔uBlock native bridge — the slice
 * {@code node scripts/ublock-smoke.mjs} structurally cannot reach: the real
 * GeckoRuntime, the real {@code ensureBuiltIn} registration (the
 * version-cache trap), the real {@code connectNative("ublock")} port
 * transport, and real webRequest blocking against the bundled filter lists.
 *
 * <p>The node smoke owns the JS behavior (toggle semantics, counting buckets,
 * CNAME polarity) and the message-contract cross-check; this suite owns the
 * TRANSPORT: Java API call → port → firedown.js handler → sendNativeMessage →
 * MessageDelegate → GeckoUblockHelper LiveData.
 *
 * <p>Run (device or emulator attached; t5 needs network for its
 * https://example.com/ tab):
 * <pre>
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.geckoview.UblockBridgeLiveTest
 * </pre>
 *
 * <p>Tests share one booted runtime and run in name order (t1…t5) — the
 * runtime is a process singleton, so per-test isolation is impossible by
 * construction; each test leaves the state it found (the cookie toggle ends
 * disabled, the ads toggle ends enabled).
 *
 * <p>First run on a fresh profile compiles the bundled lists (several
 * seconds); the boot-gated waits are sized for that, not for the steady
 * state.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class UblockBridgeLiveTest {

    private static final String TAG = "UblockBridgeLiveTest";

    private static final String UBLOCK_URI = "resource://android/assets/ublock/";
    private static final String UBLOCK_ID = "uBlock0@raymondhill.net";

    // First boot on a fresh profile compiles ~60k rules.
    private static final long BOOT_TIMEOUT_MS = 120_000;
    // A cookie toggle triggers a full recompile.
    private static final long RECOMPILE_TIMEOUT_MS = 90_000;
    private static final long ROUND_TRIP_TIMEOUT_MS = 30_000;

    private static GeckoRuntimeHelper sHelper;
    private static GeckoUblockHelper sUblock;
    private static GeckoSession sSession;   // opened by t2, reused by t4/t5

    @Before
    public void boot() {
        if (sHelper != null) { return; }
        Context app = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        // Constructing GeckoRuntimeHelper CREATES the GeckoRuntime and
        // registers every built-in extension — it must run on the main
        // thread (GeckoRuntime.create asserts it).
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            RepositoryEntryPoint entryPoint = EntryPointAccessors.fromApplication(
                    app, RepositoryEntryPoint.class);
            sHelper = entryPoint.getGeckoRuntimeHelper();
            sUblock = entryPoint.getGeckoUblockHelper();
        });
        assertNotNull(sHelper);
        assertNotNull(sUblock);
    }

    @AfterClass
    public static void closeSession() {
        if (sSession == null) { return; }
        // An open GeckoSession holds a content process; nothing else
        // references this one, so close it here (the GeckoInspectTask rule:
        // every session opened must end up closed).
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            sSession.close();
            sSession = null;
        });
    }

    /* ---------------------------------------------------------------------- */
    /* LiveData helpers                                                        */
    /* ---------------------------------------------------------------------- */

    private interface Check<T> { boolean ok(T value); }

    /** Waits until the LiveData holds a value passing {@code check}. */
    private static <T> T awaitLive(LiveData<T> live, Check<T> check, long timeoutMs)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> seen = new AtomicReference<>();
        Observer<T> observer = value -> {
            if (value != null && check.ok(value)) {
                seen.set(value);
                latch.countDown();
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
     * Counts deliveries on a LiveData. observeForever replays the sticky
     * current value once on registration; callers snapshot the count after a
     * settle beat and then watch for it to grow — a growth strictly after a
     * send proves a fresh push arrived, independent of the value itself.
     */
    private static final class EmissionCounter<T> {
        final AtomicInteger count = new AtomicInteger();
        private final LiveData<T> live;
        private final Observer<T> observer = value -> count.incrementAndGet();

        EmissionCounter(LiveData<T> live) {
            this.live = live;
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> live.observeForever(observer));
        }

        void stop() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> live.removeObserver(observer));
        }
    }

    /* ---------------------------------------------------------------------- */
    /* t1 — registration: the runtime runs the version we ship                 */
    /* ---------------------------------------------------------------------- */

    /**
     * The {@code ensureBuiltIn} registration is version-cache-keyed: after an
     * in-place update, a stale registration keeps serving the OLD bundle with
     * no error anywhere. Re-asking the controller for the extension and
     * comparing against the manifest actually in this APK's assets is the
     * direct probe for that trap.
     */
    @Test
    public void t1_registeredVersionMatchesBundledManifest() throws Exception {
        // The bundled truth.
        Context app = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        String bundledVersion;
        try (InputStream in = app.getAssets().open("ublock/manifest.json")) {
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

        // The runtime's truth. ensureBuiltIn is idempotent — a second call
        // for an already-registered id resolves with the registered
        // extension.
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<WebExtension> registered = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                sHelper.getGeckoRuntime().getWebExtensionController()
                        .ensureBuiltIn(UBLOCK_URI, UBLOCK_ID)
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
        assertEquals("registered uBlock version != bundled manifest version — "
                        + "stale ensureBuiltIn registration (the version-cache trap)",
                bundledVersion, ext.metaData.version);
        Log.i(TAG, "t1: registered uBlock " + ext.metaData.version);
    }

    /* ---------------------------------------------------------------------- */
    /* t2 — the native port round trip                                         */
    /* ---------------------------------------------------------------------- */

    /**
     * Java → port → firedown.js → sendNativeMessage → MessageDelegate →
     * LiveData, end to end. The probe is {@code setCookies(false)}: with the
     * lists already deselected (the default) the JS handler's drain loop
     * no-ops — no recompile, no reload — but its unconditional
     * {@code pushCookieState} still answers with a firewall message on every
     * delivery. {@code sendPortMessage} silently DROPS messages until the
     * background page has connected the port, so the probe loops: the first
     * counted emission after a send proves the transport.
     *
     * <p>The session is opened FIRST: the app never runs a booted runtime
     * with zero GeckoSessions, and in that artificial state the extension
     * background page (and its {@code connectNative("ublock")}) need not be
     * up, so every port send drops and the probe times out with no failure
     * anywhere else — exactly how t2/t3 failed on-device while t4/t5 (which
     * open the session) passed. A live-transport test asserts the transport
     * under the app's real invariant, session included.
     */
    @Test
    public void t2_nativePortRoundTrip() throws Exception {
        openTestSession();
        EmissionCounter<Boolean> firewall =
                new EmissionCounter<>(sUblock.getFirewallActiveLive());
        try {
            Thread.sleep(500);  // let the sticky replay + boot pushes settle
            int baseline = firewall.count.get();
            long deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS;
            boolean delivered = false;
            while (System.currentTimeMillis() < deadline) {
                sHelper.setCookies(false);
                Thread.sleep(1000);
                if (firewall.count.get() > baseline) {
                    delivered = true;
                    break;
                }
            }
            assertTrue("no firewall push arrived — the \"ublock\" native port "
                    + "never completed a Java→JS→Java round trip", delivered);
        } finally {
            firewall.stop();
        }
    }

    /* ---------------------------------------------------------------------- */
    /* t3 — cookie-notice toggle                                               */
    /* ---------------------------------------------------------------------- */

    @Test
    public void t3_cookieNoticeToggle() throws Exception {
        // Enable: the optimistic push flips the LiveData ahead of the
        // multi-second recompile.
        sHelper.setCookies(true);
        Boolean on = awaitLive(sUblock.getCookieNoticesBlockedLive(),
                v -> v, ROUND_TRIP_TIMEOUT_MS);
        assertEquals("cookie-notice enable never reported back", Boolean.TRUE, on);

        // Disable while the enable's recompile may still be in flight — the
        // JS side coalesces to the latest requested state; this must settle
        // to disabled, not wedge.
        sHelper.setCookies(false);
        Boolean off = awaitLive(sUblock.getCookieNoticesBlockedLive(),
                v -> !v, RECOMPILE_TIMEOUT_MS);
        assertEquals("cookie-notice disable never reported back (coalescing wedge?)",
                Boolean.FALSE, off);
    }

    /* ---------------------------------------------------------------------- */
    /* t4 — real blocking, real counting                                       */
    /* ---------------------------------------------------------------------- */

    /**
     * Loads a page whose subresources hit universally-blocked tracker URLs
     * (EasyPrivacy: google-analytics.com/analytics.js,
     * googletagmanager.com/gtm.js) and asserts the cumulative and
     * per-category counters the Home card / TrackersInfoSheet read actually
     * advance. Network-independent: uBlock cancels the requests in
     * webRequest before any socket opens, and the host page is a data: URL.
     */
    @Test
    public void t4_pageLoadCountsBlockedRequests() throws Exception {
        long before = currentCumulative();
        long scriptsBefore = currentScriptsBucket();

        openTestSession();
        // Unquoted attribute values + %20 for the one space: a data: URI must
        // not carry raw spaces/quotes for GeckoView to load it verbatim.
        String page = "data:text/html,<html><body>"
                + "<script%20src=https://www.google-analytics.com/analytics.js></script>"
                + "<script%20src=https://www.googletagmanager.com/gtm.js></script>"
                + "test</body></html>";
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> sSession.loadUri(page));

        Long grown = awaitLive(sUblock.getCumulativeBlockedLive(),
                v -> v > before, RECOMPILE_TIMEOUT_MS);
        assertNotNull("cumulativeBlocked never advanced past " + before
                + " — either the tracker requests were not blocked (lists not "
                + "compiled?) or the counting pushes never crossed the port", grown);
        Log.i(TAG, "t4: cumulativeBlocked " + before + " -> " + grown);

        // The category breakdown rides the same journal; both scripts were
        // itype SCRIPT, so the scripts bucket must grow too.
        Map<GeckoUblockHelper.Category, Long> cats = awaitLive(
                sUblock.getCategoryBlockedLive(),
                m -> bucket(m) > scriptsBefore, ROUND_TRIP_TIMEOUT_MS);
        assertNotNull("categoryBlocked scripts bucket never advanced past "
                + scriptsBefore, cats);
    }

    /* ---------------------------------------------------------------------- */
    /* t5 — per-page enable/disable                                            */
    /* ---------------------------------------------------------------------- */

    /**
     * The per-tab net-filtering switch, on a real origin (the JS toggle
     * whitelists by hostname, which a data: URL doesn't have). Needs
     * network for the example.com load.
     */
    @Test
    public void t5_perPageAdsToggle() throws Exception {
        openTestSession();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> sSession.loadUri("https://example.com/"));
        // Let the load commit so vAPI.tabs.getCurrent() reports the real URL.
        Thread.sleep(5000);

        // Establish the known pre-toggle state first: the load-complete push
        // (vapi-background.js onUpdated) reports activated=true for a fresh
        // origin, so a sticky stale `false` from an earlier test can never
        // satisfy the post-toggle await below spuriously.
        Boolean pre = awaitLive(sUblock.getFirewallActiveLive(),
                v -> v, ROUND_TRIP_TIMEOUT_MS);
        assertEquals("page never reported activated=true after load — "
                + "no tab-complete firewall push (network down, or the test "
                + "session is invisible to the tabs API)", Boolean.TRUE, pre);

        sHelper.setAds(false);
        Boolean off = awaitLive(sUblock.getFirewallActiveLive(),
                v -> !v, ROUND_TRIP_TIMEOUT_MS);
        assertEquals("{ads:false} never reported activated=false for the page "
                + "(per-page whitelist toggle broken, or the test tab was not "
                + "the extension's active tab)", Boolean.FALSE, off);

        sHelper.setAds(true);
        Boolean on = awaitLive(sUblock.getFirewallActiveLive(),
                v -> v, ROUND_TRIP_TIMEOUT_MS);
        assertEquals("{ads:true} never reported activated=true", Boolean.TRUE, on);
    }

    /* ---------------------------------------------------------------------- */
    /* plumbing                                                                */
    /* ---------------------------------------------------------------------- */

    private long currentCumulative() {
        Long v = sUblock.getCumulativeBlockedLive().getValue();
        return v == null ? 0L : v;
    }

    private long currentScriptsBucket() {
        return bucket(sUblock.getCategoryBlockedLive().getValue());
    }

    private static long bucket(Map<GeckoUblockHelper.Category, Long> m) {
        if (m == null) { return 0L; }
        Long v = m.get(GeckoUblockHelper.Category.SCRIPTS);
        return v == null ? 0L : v;
    }

    /**
     * One shared visible-tab session for t2/t4/t5 — created the way the app's
     * own hidden sessions are (PoTokenGenerator pattern): register delegates
     * BEFORE open, mark active, and additionally mark it the ACTIVE TAB so
     * firedown.js' vAPI.tabs.getCurrent() resolves to it.
     */
    private void openTestSession() {
        if (sSession != null) { return; }
        GeckoRuntime runtime = sHelper.getGeckoRuntime();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
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
        });
    }
}

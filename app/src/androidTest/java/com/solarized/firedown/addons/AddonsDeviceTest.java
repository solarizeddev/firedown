package com.solarized.firedown.addons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solarized.firedown.data.di.RepositoryEntryPoint;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.geckoview.GeckoUblockHelper;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebExtension;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dagger.hilt.android.EntryPointAccessors;

/**
 * User add-ons on a device, through the real {@link AddonManager} attached
 * to the real WebExtensionController: what the JVM harness can't reach.
 *
 * <ul>
 *   <li>t1: uBlock Origin is installed from the copy in the app, as a user
 *       add-on allowed in private tabs, and it stands behind the shield; the
 *       built-in extensions never show as user add-ons.</li>
 *   <li>t2–t5: files refused — no signature (by us, before Gecko), a
 *       signature that doesn't verify (by Gecko: this build requires
 *       Mozilla's signature), a forged uBlock Origin, not an add-on. uBlock
 *       is still installed and on afterwards.</li>
 *   <li>t6–t7 (network): an add-on from Mozilla's Android list — the prompt,
 *       declining it, then adding it, its button rows for a tab, off/on, run
 *       in private tabs, remove.</li>
 *   <li>t8: uBlock removed, then the shield sheet's "Turn on": the app's
 *       copy again, with no question; turned off, then on again. The shield
 *       follows.</li>
 *   <li>t9: a page whose ad requests uBlock refuses: the capture extension
 *       counts them, the lifetime figure and the page's count move (no
 *       network needed: the refused requests never leave the phone).</li>
 * </ul>
 *
 * <p>Run (device attached; t6–t7 skip without network):
 * <pre>
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.addons.AddonsDeviceTest
 * </pre>
 * Nothing is left installed: a test add-on still there at the end is
 * removed.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class AddonsDeviceTest {

    private static final String TAG = "AddonsDeviceTest";

    private static final String UBLOCK_ID = "uBlock0@raymondhill.net";
    private static final long BOOT_TIMEOUT_MS = 120_000;
    private static final long FILE_TIMEOUT_MS = 60_000;
    private static final long NETWORK_TIMEOUT_MS = 180_000;
    private static final long CHANGE_TIMEOUT_MS = 30_000;

    private static GeckoRuntimeHelper sHelper;
    private static AddonManager sAddons;
    private static AmoCatalogClient sCatalog;
    private static AmoCatalog.Entry sEntry;
    private static GeckoSession sSession;
    private static GeckoSession sPage;
    private static GeckoUblockHelper sStats;

    @Before
    public void boot() throws Exception {
        if (sHelper != null) {
            return;
        }
        Context app = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            RepositoryEntryPoint entryPoint = EntryPointAccessors.fromApplication(
                    app, RepositoryEntryPoint.class);
            sHelper = entryPoint.getGeckoRuntimeHelper();
            sAddons = entryPoint.getAddonManager();
            sCatalog = entryPoint.getAmoCatalogClient();
            sStats = entryPoint.getGeckoUblockHelper();
        });
        assertNotNull(sHelper);
        assertNotNull(sAddons);
        Boolean loaded = awaitLive(sAddons.getLoaded(), Boolean::booleanValue, BOOT_TIMEOUT_MS);
        assertTrue("the add-on list never loaded", loaded != null && loaded);
    }

    @AfterClass
    public static void cleanUp() throws Exception {
        if (sAddons != null && sEntry != null) {
            String id = sEntry.id;
            onMain(() -> {
                if (sAddons.isInstalled(id)) {
                    sAddons.uninstall(id);
                }
            });
        }
        if (sSession != null) {
            onMain(() -> {
                sSession.close();
                sSession = null;
            });
        }
        if (sPage != null) {
            onMain(() -> {
                sPage.close();
                sPage = null;
            });
        }
    }

    // ── t1 ──────────────────────────────────────────────────────────────

    @Test
    public void t1_ublockIsInstalledFromTheAppsCopy() throws Exception {
        AddonInfo ublock = awaitUblockAddon(a -> a.enabled, BOOT_TIMEOUT_MS);
        assertNotNull("uBlock Origin was not installed from the app's copy", ublock);
        assertTrue("uBlock Origin should run in private tabs", ublock.allowedInPrivateBrowsing);
        assertTrue("uBlock Origin should stand behind the shield", isBlocker(ublock));
        WebExtension inGecko = awaitUblock();
        assertNotNull(inGecko);
        assertFalse("the patched built-in is still registered", inGecko.isBuiltIn);
        List<AddonInfo> shown = valueOf(sAddons.getAddons());
        for (AddonInfo info : shown) {
            assertFalse("built-in shown as a user add-on: " + info.id, AddonPolicy.isBuiltInId(info.id));
        }
        assertEquals(AddonPolicy.SHIELD_PROTECTED, shield(false));
        assertEquals(AddonPolicy.SHIELD_PROTECTED, shield(true));
    }

    // ── t2–t5: files that must not install ─────────────────────────────

    @Test
    public void t2_unsignedFileIsRefusedBeforeGecko() throws Exception {
        File xpi = xpi(manifest("fd-test-unsigned@example.org", "Unsigned test"), false);
        AddonManager.Event event = installFileAndAwaitEvent(xpi);
        assertEquals(AddonManager.Event.UNSIGNED, event.type);
        assertFalse(sAddons.isInstalled("fd-test-unsigned@example.org"));
    }

    @Test
    public void t3_aSignatureThatDoesNotVerifyIsRefusedByGecko() throws Exception {
        // Signature files present, so our pre-check passes and Gecko decides:
        // a broken signature is ERROR_CORRUPT_FILE in XPIInstall.
        File xpi = xpi(manifest("fd-test-badsig@example.org", "Bad signature test"), true);
        AddonManager.Event event = installFileAndAwaitEvent(xpi);
        assertTrue("expected CORRUPT or UNSIGNED, got " + event.type,
                event.type == AddonManager.Event.CORRUPT || event.type == AddonManager.Event.UNSIGNED);
        assertFalse(sAddons.isInstalled("fd-test-badsig@example.org"));
        Integer inFlight = valueOf(sAddons.getInstallsInFlight());
        assertEquals(Integer.valueOf(0), inFlight);
    }

    @Test
    public void t4_aForgedUblockIsRefusedAndUblockStays() throws Exception {
        File xpi = xpi(manifest(UBLOCK_ID, "Fake uBlock"), true);
        AddonManager.Event event = installFileAndAwaitEvent(xpi);
        assertTrue("a forged signature must be refused, got " + event.type,
                event.type == AddonManager.Event.CORRUPT || event.type == AddonManager.Event.UNSIGNED);
        WebExtension ublock = awaitUblock();
        assertNotNull("uBlock is gone", ublock);
        assertTrue("uBlock is off", ublock.metaData.enabled);
        String name = ublock.metaData.name;
        assertFalse("uBlock should still be the official copy", name != null && name.contains("Fake"));
    }

    @Test
    public void t5_aFileThatIsNotAnAddon() throws Exception {
        File dir = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File junk = new File(dir, "fd-test-junk.xpi");
        try (FileOutputStream out = new FileOutputStream(junk)) {
            out.write("this is not a zip file".getBytes(StandardCharsets.UTF_8));
        }
        AddonManager.Event event = installFileAndAwaitEvent(junk);
        assertEquals(AddonManager.Event.NOT_AN_ADDON, event.type);
    }

    // ── t6–t7: Mozilla's list (network) ────────────────────────────────

    @Test
    public void t6_declinedInstallLeavesNothing() throws Exception {
        AmoCatalog.Entry entry = pickCatalogEntry();
        EventCollector events = new EventCollector();
        try {
            onMain(() -> sAddons.installFromCatalog(entry));
            AddonManager.Prompt prompt = awaitPrompt(entry.id);
            assertNull("an add-on from Mozilla's list has no outside source", prompt.source);
            assertEquals(AddonManager.Prompt.INSTALL, prompt.type);
            int seq = prompt.seq;
            onMain(() -> sAddons.dismissPrompt(seq));
            Integer done = awaitLive(sAddons.getInstallsInFlight(), v -> v == 0, NETWORK_TIMEOUT_MS);
            assertEquals(Integer.valueOf(0), done);
            Thread.sleep(1000);
            assertFalse(sAddons.isInstalled(entry.id));
            for (AddonManager.Event event : events.seen) {
                assertFalse("declining is not an error: " + event.type,
                        event.type == AddonManager.Event.FAILED || event.type == AddonManager.Event.NETWORK);
            }
            assertNull("the prompt should be gone", valueOf(sAddons.getPrompt()));
        } finally {
            events.stop();
        }
    }

    @Test
    public void t7_installUseAndRemove() throws Exception {
        AmoCatalog.Entry entry = pickCatalogEntry();
        String id = entry.id;
        EventCollector events = new EventCollector();
        StateTrail trail = new StateTrail(id);
        try {
            onMain(() -> sAddons.installFromCatalog(entry));
            AddonManager.Prompt prompt = awaitPrompt(id);
            int seq = prompt.seq;
            onMain(() -> sAddons.answer(seq, true, false));

            AddonInfo info = awaitAddon(id, a -> a != null);
            assertNotNull("not installed: " + entry.name, info);
            assertTrue(info.enabled);
            assertFalse("private tabs are off unless asked", info.allowedInPrivateBrowsing);
            assertTrue(events.await(AddonManager.Event.INSTALLED, CHANGE_TIMEOUT_MS));
            assertTrue(sAddons.hasAddonsFor(false));

            // A tab sees it on its Add-ons page.
            onMain(() -> {
                sSession = new GeckoSession(new GeckoSessionSettings.Builder().build());
                sSession.open(sHelper.getGeckoRuntime());
                sAddons.registerSession(sSession);
            });
            assertTrue("no row for a tab " + trail, awaitRow(sSession, false, id, true));
            assertTrue("listed for private tabs " + trail, awaitRow(sSession, true, id, false));

            onMain(() -> sAddons.setEnabled(id, false));
            assertNotNull(awaitAddon(id, a -> a != null && !a.enabled));
            assertTrue("still listed when off " + trail, awaitRow(sSession, false, id, false));

            onMain(() -> sAddons.setEnabled(id, true));
            assertNotNull(awaitAddon(id, a -> a != null && a.enabled));
            assertTrue("not listed when on " + trail, awaitRow(sSession, false, id, true));

            // Allowing private tabs reloads the add-on in Gecko: the state
            // must SETTLE on on + allowed, whatever order the notices came in.
            onMain(() -> sAddons.setAllowedInPrivate(id, true));
            assertNotNull(awaitAddon(id, a -> a != null && a.allowedInPrivateBrowsing));
            assertTrue("not listed for private tabs " + trail, awaitRow(sSession, true, id, true));
            Thread.sleep(2000);
            AddonInfo settled = find(valueOf(sAddons.getAddons()), id);
            assertNotNull(settled);
            assertTrue("off after allowing private tabs " + trail, settled.enabled);
            assertTrue("private tabs not kept " + trail, settled.allowedInPrivateBrowsing);

            onMain(() -> sAddons.uninstall(id));
            List<AddonInfo> after = awaitLive(sAddons.getAddons(), list -> find(list, id) == null,
                    CHANGE_TIMEOUT_MS);
            assertNotNull("still installed after remove", after);
            assertTrue(events.await(AddonManager.Event.REMOVED, CHANGE_TIMEOUT_MS));
            boolean stillInGecko = false;
            for (WebExtension extension : listInstalled()) {
                if (id.equals(extension.id)) {
                    stillInGecko = true;
                }
            }
            assertFalse("Gecko still lists it", stillInGecko);
        } finally {
            Log.i(TAG, "t7 states " + trail);
            trail.stop();
            events.stop();
        }
    }

    // ── t8–t9: uBlock Origin behind the shield ─────────────────────────

    @Test
    public void t8_removeThenTurnOnBringsBackTheAppsCopy() throws Exception {
        AddonInfo ublock = awaitUblockAddon(a -> a.enabled, BOOT_TIMEOUT_MS);
        assertNotNull(ublock);
        String id = ublock.id;
        EventCollector events = new EventCollector();
        try {
            onMain(() -> sAddons.uninstall(id));
            assertNotNull("uBlock still installed",
                    awaitLive(sAddons.getAddons(), list -> find(list, id) == null, CHANGE_TIMEOUT_MS));
            assertEquals(AddonPolicy.SHIELD_NO_BLOCKER, shield(false));

            onMain(() -> sAddons.turnOnUblock(false));
            AddonInfo back = awaitUblockAddon(a -> a.enabled, FILE_TIMEOUT_MS);
            assertNotNull("Turn on did not reinstall uBlock", back);
            assertNull("the app's copy asks no question", valueOf(sAddons.getPrompt()));
            assertTrue("reinstalled without private tabs", back.allowedInPrivateBrowsing);
            assertTrue(events.await(AddonManager.Event.INSTALLED, CHANGE_TIMEOUT_MS));
            assertEquals(AddonPolicy.SHIELD_PROTECTED, shield(false));

            onMain(() -> sAddons.setEnabled(id, false));
            assertNotNull(awaitUblockAddon(a -> !a.enabled, CHANGE_TIMEOUT_MS));
            assertEquals(AddonPolicy.SHIELD_NO_BLOCKER, shield(false));
            onMain(() -> sAddons.turnOnUblock(false));
            assertNotNull("Turn on did not turn uBlock back on", awaitUblockAddon(a -> a.enabled, CHANGE_TIMEOUT_MS));
            assertEquals(AddonPolicy.SHIELD_PROTECTED, shield(false));
        } finally {
            events.stop();
            // Whatever happened, leave uBlock installed and on.
            onMain(() -> sAddons.turnOnUblock(false));
            awaitUblockAddon(a -> a.enabled, FILE_TIMEOUT_MS);
        }
    }

    /** Requests the bundled filter lists refuse (no redirect to a neutered copy). */
    private static final String AD_PAGE = "<!doctype html><title>ads</title>"
            + "<script src=\"https://cdn.taboola.com/libtrc/firedown-test/loader.js\"></script>"
            + "<script src=\"https://static.criteo.net/js/ld/publishertag.js\"></script>"
            + "<img src=\"https://ad.doubleclick.net/ddm/trackimp/N1.firedown;ord=1\">"
            + "<img src=\"https://pixel.quantserve.com/pixel/p-firedown.gif\">"
            + "<img src=\"https://www.facebook.com/tr?id=1&ev=PageView\">"
            + "<p>ads</p>";

    @Test
    public void t9_aRefusedRequestIsCounted() throws Exception {
        assertNotNull(awaitUblockAddon(a -> a.enabled, BOOT_TIMEOUT_MS));
        GeckoUblockHelper stats = sStats;
        Long start = valueOf(stats.getCumulativeBlockedLive());
        long before = start == null ? 0L : start;
        try (PageServer server = new PageServer(AD_PAGE)) {
            onMain(() -> {
                if (sPage == null) {
                    GeckoSession s = new GeckoSession(new GeckoSessionSettings.Builder()
                            .usePrivateMode(false).allowJavascript(true).build());
                    sHelper.registerSession(s);
                    s.open(sHelper.getGeckoRuntime());
                    s.setActive(true);
                    sHelper.getGeckoRuntime().getWebExtensionController().setTabActive(s, true);
                    sPage = s;
                }
                sPage.loadUri(server.url());
            });
            // A fresh profile compiles uBlock's lists first: reload until it filters.
            Long after = null;
            long deadline = System.currentTimeMillis() + NETWORK_TIMEOUT_MS;
            while (after == null && System.currentTimeMillis() < deadline) {
                after = awaitLive(stats.getCumulativeBlockedLive(), v -> v > before, 10_000);
                if (after == null) {
                    onMain(() -> sPage.reload());
                }
            }
            assertNotNull("no refused request was counted", after);
            String page = awaitLive(stats.getAdsBlockedLive(), v -> !"0".equals(v), CHANGE_TIMEOUT_MS);
            assertNotNull("the page's count never arrived", page);
            Log.i(TAG, "refused: lifetime +" + (after - before) + ", page " + page);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static int shield(boolean incognito) throws Exception {
        AtomicReference<Integer> state = new AtomicReference<>();
        onMain(() -> state.set(sAddons.shieldState(null, incognito, "https://example.com/")));
        return state.get();
    }

    private static boolean isBlocker(AddonInfo info) throws Exception {
        AtomicReference<Boolean> blocker = new AtomicReference<>();
        onMain(() -> blocker.set(sAddons.isBlocker(info)));
        return Boolean.TRUE.equals(blocker.get());
    }

    /** uBlock Origin in the manager's list, once {@code check} holds for it. */
    private static AddonInfo awaitUblockAddon(Check<AddonInfo> check, long timeoutMs) throws Exception {
        List<AddonInfo> list = awaitLive(sAddons.getAddons(), l -> {
            AddonInfo info = findUblock(l);
            return info != null && check.ok(info);
        }, timeoutMs);
        return findUblock(list);
    }

    private static AddonInfo findUblock(List<AddonInfo> list) {
        if (list == null) {
            return null;
        }
        for (AddonInfo info : list) {
            if (AddonPolicy.isUblockId(info.id)) {
                return info;
            }
        }
        return null;
    }

    /** One page over plain HTTP on the IPv4 loopback (by name: getLoopbackAddress() is ::1 on Android). */
    private static final class PageServer implements AutoCloseable {
        private final ServerSocket mSocket;
        private final byte[] mBody;
        private final Thread mThread;

        PageServer(String html) throws Exception {
            mBody = html.getBytes(StandardCharsets.UTF_8);
            mSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            mThread = new Thread(this::serve, "fd-test-page");
            mThread.setDaemon(true);
            mThread.start();
        }

        String url() {
            return "http://127.0.0.1:" + mSocket.getLocalPort() + "/ads.html";
        }

        private void serve() {
            while (!mSocket.isClosed()) {
                try (Socket client = mSocket.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(
                            client.getInputStream(), StandardCharsets.ISO_8859_1));
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        // the request head, unread
                    }
                    OutputStream out = client.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                            + mBody.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    out.write(mBody);
                    out.flush();
                } catch (Exception e) {
                    if (!mSocket.isClosed()) {
                        Log.w(TAG, "page server", e);
                    }
                }
            }
        }

        @Override
        public void close() throws Exception {
            mSocket.close();
        }
    }

    private interface Check<T> {
        boolean ok(T value);
    }

    private interface MainTask {
        void run() throws Exception;
    }

    private static void onMain(MainTask task) throws Exception {
        AtomicReference<Exception> error = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                task.run();
            } catch (Exception e) {
                error.set(e);
            }
        });
        if (error.get() != null) {
            throw error.get();
        }
    }

    private static <T> T valueOf(LiveData<T> live) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        onMain(() -> value.set(live.getValue()));
        return value.get();
    }

    private static <T> T awaitLive(LiveData<T> live, Check<T> check, long timeoutMs) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> seen = new AtomicReference<>();
        Observer<T> observer = value -> {
            if (value != null && check.ok(value)) {
                seen.set(value);
                latch.countDown();
            }
        };
        onMain(() -> live.observeForever(observer));
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            onMain(() -> live.removeObserver(observer));
        }
        return seen.get();
    }

    private static AddonInfo find(List<AddonInfo> list, String id) {
        if (list == null) {
            return null;
        }
        for (AddonInfo info : list) {
            if (info.id.equals(id)) {
                return info;
            }
        }
        return null;
    }

    private static AddonInfo awaitAddon(String id, Check<AddonInfo> check) throws Exception {
        List<AddonInfo> list = awaitLive(sAddons.getAddons(), l -> check.ok(find(l, id)), CHANGE_TIMEOUT_MS);
        return find(list, id);
    }

    /** Waits (up to the change timeout) for the add-on's row on a tab to be there, or gone. */
    private static boolean awaitRow(GeckoSession session, boolean incognito, String id, boolean present)
            throws Exception {
        long deadline = System.currentTimeMillis() + CHANGE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (contains(actionsFor(session, incognito), id) == present) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    /** Every state the manager published for one add-on, for a failure's message. */
    private static final class StateTrail {
        private final List<String> mStates = new CopyOnWriteArrayList<>();
        private final long mStart = System.currentTimeMillis();
        private final Observer<List<AddonInfo>> mObserver;

        StateTrail(String id) throws Exception {
            mObserver = list -> {
                AddonInfo info = find(list, id);
                String state = info == null ? "absent"
                        : (info.enabled ? "on" : "off") + (info.allowedInPrivateBrowsing ? "+private" : "");
                int last = mStates.size() - 1;
                if (last < 0 || !mStates.get(last).endsWith(" " + state)) {
                    mStates.add((System.currentTimeMillis() - mStart) + "ms " + state);
                }
            };
            onMain(() -> sAddons.getAddons().observeForever(mObserver));
        }

        void stop() throws Exception {
            onMain(() -> sAddons.getAddons().removeObserver(mObserver));
        }

        @Override
        public String toString() {
            return mStates.toString();
        }
    }

    private static List<AddonManager.ActionItem> actionsFor(GeckoSession session, boolean incognito)
            throws Exception {
        AtomicReference<List<AddonManager.ActionItem>> items = new AtomicReference<>();
        onMain(() -> items.set(sAddons.actionsFor(session, incognito)));
        return items.get();
    }

    private static boolean contains(List<AddonManager.ActionItem> items, String id) {
        for (AddonManager.ActionItem item : items) {
            if (item.addon.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static List<WebExtension> listInstalled() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<WebExtension>> list = new AtomicReference<>(Collections.emptyList());
        onMain(() -> sHelper.getGeckoRuntime().getWebExtensionController().list().accept(l -> {
            if (l != null) {
                list.set(l);
            }
            latch.countDown();
        }, e -> latch.countDown()));
        assertTrue("list() never answered", latch.await(CHANGE_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        return list.get();
    }

    /**
     * uBlock as Gecko lists it. The add-on manager installs it from the
     * app's copy after listing what a previous run installed (asynchronous),
     * so the first test can list Gecko's extensions before it is there.
     */
    private static WebExtension awaitUblock() throws Exception {
        long deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            for (WebExtension extension : listInstalled()) {
                if (UBLOCK_ID.equalsIgnoreCase(extension.id)) {
                    return extension;
                }
            }
            Thread.sleep(250);
        }
        return null;
    }

    private static AddonManager.Event installFileAndAwaitEvent(File file) throws Exception {
        EventCollector events = new EventCollector();
        try {
            onMain(() -> sAddons.installFromUri(Uri.fromFile(file)));
            AddonManager.Event event = events.awaitAny(FILE_TIMEOUT_MS);
            assertNotNull("no answer for " + file.getName(), event);
            Log.i(TAG, file.getName() + " -> event " + event.type);
            return event;
        } finally {
            events.stop();
        }
    }

    private static AddonManager.Prompt awaitPrompt(String id) throws Exception {
        AddonManager.Prompt prompt = awaitLive(sAddons.getPrompt(),
                p -> id.equals(p.addon.id), NETWORK_TIMEOUT_MS);
        assertNotNull("no install prompt for " + id, prompt);
        return prompt;
    }

    /** Mozilla's Android list: the add-on asking for the fewest permissions. */
    private static AmoCatalog.Entry pickCatalogEntry() throws Exception {
        if (sEntry != null) {
            return sEntry;
        }
        onMain(() -> sCatalog.load(false));
        AmoCatalogClient.State state = awaitLive(sCatalog.getState(),
                s -> !s.loading && (s.failed || !s.entries.isEmpty()), NETWORK_TIMEOUT_MS);
        assumeTrue("Mozilla's list unreachable (no network?)",
                state != null && !state.failed && !state.entries.isEmpty());
        AmoCatalog.Entry best = null;
        for (AmoCatalog.Entry entry : state.entries) {
            if (AddonPolicy.isBuiltInId(entry.id) || sAddons.isInstalled(entry.id)) {
                continue;
            }
            if (best == null || entry.permissions.size() < best.permissions.size()) {
                best = entry;
            }
        }
        assumeTrue("nothing in the list to try", best != null);
        Log.i(TAG, "catalog test add-on: " + best.id + " " + best.version);
        sEntry = best;
        return best;
    }

    /** Collects every add-on event set after it was created (not the one already there). */
    private static final class EventCollector {
        final List<AddonManager.Event> seen = new CopyOnWriteArrayList<>();
        private AddonManager.Event mInitial;
        private boolean mSkippedInitial;
        private final Observer<AddonManager.Event> mObserver = event -> {
            if (!mSkippedInitial) {
                mSkippedInitial = true;
                if (event == mInitial) {
                    return;
                }
            }
            if (event != null) {
                seen.add(event);
            }
        };

        EventCollector() throws Exception {
            onMain(() -> {
                mInitial = sAddons.getEvents().getValue();
                sAddons.getEvents().observeForever(mObserver);
            });
        }

        AddonManager.Event awaitAny(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (!seen.isEmpty()) {
                    return seen.get(0);
                }
                Thread.sleep(100);
            }
            return null;
        }

        boolean await(int type, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                for (AddonManager.Event event : new ArrayList<>(seen)) {
                    if (event.type == type) {
                        return true;
                    }
                }
                Thread.sleep(100);
            }
            return false;
        }

        void stop() throws Exception {
            onMain(() -> sAddons.getEvents().removeObserver(mObserver));
        }
    }

    private static String manifest(String id, String name) {
        return "{\"manifest_version\":2,\"name\":\"" + name + "\",\"version\":\"1.0\","
                + "\"browser_specific_settings\":{\"gecko\":{\"id\":\"" + id + "\"},\"gecko_android\":{}},"
                + "\"background\":{\"scripts\":[\"bg.js\"]}}";
    }

    /** {@code signed}: add the signature files, with bytes that won't verify. */
    private static File xpi(String manifest, boolean signed) throws Exception {
        File dir = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File file = File.createTempFile("fd-test-", ".xpi", dir);
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            put(out, "manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
            put(out, "bg.js", "void 0;".getBytes(StandardCharsets.UTF_8));
            if (signed) {
                put(out, "META-INF/manifest.mf", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
                put(out, "META-INF/mozilla.sf", "Signature-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
                put(out, "META-INF/mozilla.rsa", new byte[]{0x30, 0x03, 0x02, 0x01, 0x00});
            }
        }
        return file;
    }

    private static void put(ZipOutputStream out, String name, byte[] bytes) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}

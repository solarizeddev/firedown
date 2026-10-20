package com.solarized.firedown.siteaudit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.data.capture.CaptureSections;
import com.solarized.firedown.data.di.RepositoryEntryPoint;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.repository.BrowserDownloadRepository;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.ui.adapters.BrowserOptionAdapter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dagger.hilt.android.EntryPointAccessors;

/**
 * The DEVICE AUDIT: the real app browses a list of sites on the phone and
 * records, for each, what the Captured sheet would show and why. Nothing is
 * simulated — the real GeckoView with the phone's own fingerprint, network
 * and cookies, the real bundled extensions (catcher, parsers, bridge,
 * uBlock, youtube@), the real native pipeline down to
 * {@link BrowserDownloadRepository}. What a Chromium replay could never get
 * past (bot walls, a Gecko-only code path, uBlock's real blocking) is simply
 * what happens here.
 *
 * <p>Per site, in {@link SiteAuditActivity} (a real view, so pages lay out
 * and paint at phone size): load, agree to the consent wall as a user would,
 * scroll the page through, wait for captures to settle, then write to
 * {@code <external files>/site-audit/<out>/<NN-site>/}:
 * <ul>
 *   <li>{@code entities.json} — the tab's captures as the repository lists
 *       them, each with the title the sheet SHOWS ({@code tileTitle}, the
 *       real rule: "Video on <host>" for a filename);</li>
 *   <li>{@code emits.json} — every capture message the extensions sent
 *       ({@link GeckoRuntimeHelper.CaptureTap}), including the later copies
 *       the dedup dropped with their names; credentials redacted;</li>
 *   <li>{@code census.json} — every frame's media elements, embeds,
 *       placeholders and page names (content.js of the debug-only
 *       {@code siteaudit} extension);</li>
 *   <li>{@code around.json} — the markup around each captured clip: the
 *       elements holding its URL, poster or file name, their card and
 *       data-* config, so a wrong title can be traced to the right one;</li>
 *   <li>{@code requests.json} — the tab's request log (and the extensions'
 *       own fetches) with statuses, errors and redirects;</li>
 *   <li>{@code screen.jpg} — the page as the phone painted it.</li>
 * </ul>
 * The laptop judges it ({@code scripts/device-audit/judge.mjs}); the phone
 * only collects. Run with {@code scripts/device-audit.sh}, which builds,
 * installs, starts this and pulls the results.
 *
 * <p>Arguments ({@code -e name value}): {@code sites} (URLs, comma or space
 * separated; default the bundled {@code site-audit/sites.txt}), {@code only}
 * (substring filter), {@code limit}, {@code wait} (ms after consent and after
 * the scroll, default 10000), {@code out} (run folder name).
 *
 * <p>Debug builds only: the extension lives in {@code src/debug/assets}, and
 * the test installs it at the start and uninstalls it at the end.
 */
@RunWith(AndroidJUnit4.class)
public class SiteAuditTest {

    private static final String TAG = "SiteAudit";

    private static final String EXT_URI = "resource://android/assets/siteaudit/";
    private static final String EXT_ID = "siteaudit@solarized.dev";
    private static final String NATIVE_APP = "siteaudit";

    private static final long BOOT_TIMEOUT_MS = 120_000;
    private static final long PORT_TIMEOUT_MS = 60_000;
    private static final long LOAD_TIMEOUT_MS = 45_000;
    private static final long SETTLE_QUIET_MS = 6_000;
    private static final long SETTLE_MAX_MS = 45_000;
    private static final long DEFAULT_WAIT_MS = 10_000;
    private static final int TAB_LOOKUP_ATTEMPTS = 3;
    private static final int MAX_NEEDLE_CLIPS = 16;
    // File names every player uses: they find every clip and none.
    private static final Set<String> GENERIC_STEMS = new HashSet<>(Arrays.asList(
            "playlist", "manifest", "download", "thumbnail", "original", "preview", "segment",
            "default", "frame-0001", "poster-image", "videoplayback"));

    private static final Set<String> SECRET_KEYS = new HashSet<>(Arrays.asList("cookie", "authorization",
            "potoken", "visitordata", "masterkey", "filekey", "proxy-authorization"));

    private final Instrumentation mInstrumentation = InstrumentationRegistry.getInstrumentation();
    private GeckoRuntimeHelper mHelper;
    private BrowserDownloadRepository mCaptures;
    private final AuditPort mPort = new AuditPort();
    private WebExtension mExtension;

    /** Every capture message, stamped, for the site being audited. */
    private final List<JSONObject> mEmits = new ArrayList<>();
    private volatile long mSiteStart;

    @Test
    public void audit() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        List<String> sites = sitesFrom(args);
        long waitMs = longArg(args, "wait", DEFAULT_WAIT_MS);
        String stamp = args.getString("out");
        if (TextUtils.isEmpty(stamp)) {
            stamp = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date());
        }
        Context app = mInstrumentation.getTargetContext().getApplicationContext();
        File outDir = new File(app.getExternalFilesDir(null), "site-audit/" + stamp);
        assertTrue("cannot create " + outDir, outDir.isDirectory() || outDir.mkdirs());
        assertTrue("no sites to audit", !sites.isEmpty());

        boot(app);
        installExtension();
        assertTrue("the siteaudit extension never connected its native port",
                mPort.awaitConnected(PORT_TIMEOUT_MS));

        JSONObject run = new JSONObject();
        run.put("started", System.currentTimeMillis());
        run.put("device", Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");
        run.put("app", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        run.put("waitMs", waitMs);
        run.put("sites", new JSONArray(sites));
        write(new File(outDir, "run.json"), run.toString(2));

        GeckoRuntimeHelper.setCaptureTap(message -> {
            JSONObject copy;
            try {
                copy = new JSONObject(message.toString());
                copy.put("_t", SystemClock.elapsedRealtime() - mSiteStart);
            } catch (JSONException e) {
                return;
            }
            synchronized (mEmits) {
                mEmits.add(copy);
            }
        });

        JSONArray summary = new JSONArray();
        try (ActivityScenario<SiteAuditActivity> scenario = ActivityScenario.launch(SiteAuditActivity.class)) {
            AtomicReference<SiteAuditActivity> activity = new AtomicReference<>();
            scenario.onActivity(activity::set);
            assertNotNull(activity.get());
            for (int i = 0; i < sites.size(); i++) {
                String url = sites.get(i);
                String label = String.format(Locale.US, "%02d-%s", i + 1, slug(url));
                File siteDir = new File(outDir, label);
                if (!siteDir.isDirectory() && !siteDir.mkdirs()) {
                    Log.w(TAG, "cannot create " + siteDir);
                    continue;
                }
                String progress = "[" + (i + 1) + "/" + sites.size() + "] " + url;
                Log.i(TAG, progress);
                JSONObject line;
                try {
                    line = auditSite(activity.get(), url, siteDir, waitMs, progress);
                } catch (Throwable t) {
                    Log.e(TAG, "site failed: " + url, t);
                    line = new JSONObject();
                    line.put("url", url);
                    line.put("error", String.valueOf(t));
                    write(new File(siteDir, "site.json"), line.toString(2));
                }
                line.put("dir", label);
                summary.put(line);
                write(new File(outDir, "summary.json"), summary.toString(2));
                Log.i(TAG, "[" + (i + 1) + "/" + sites.size() + "] done " + label + ": "
                        + line.optInt("captures") + " captures, " + line.optInt("emits") + " emits, "
                        + line.optInt("frames") + " frames, " + line.optLong("ms") / 1000 + " s"
                        + (line.has("error") ? " ERROR " + line.optString("error") : ""));
            }
        } finally {
            GeckoRuntimeHelper.setCaptureTap(null);
            uninstallExtension();
            run.put("ended", System.currentTimeMillis());
            write(new File(outDir, "run.json"), run.toString(2));
            Log.i(TAG, "finished: " + outDir.getAbsolutePath());
        }
    }

    /* ---------------------------------------------------------------------- */
    /* one site                                                               */
    /* ---------------------------------------------------------------------- */

    private JSONObject auditSite(SiteAuditActivity activity, String url, File dir, long waitMs,
                                 String progress) throws Exception {
        long t0 = SystemClock.elapsedRealtime();
        mSiteStart = t0;
        synchronized (mEmits) {
            mEmits.clear();
        }
        JSONObject site = new JSONObject();
        site.put("url", url);
        JSONArray steps = new JSONArray();
        site.put("steps", steps);

        PageWatch watch = new PageWatch();
        AtomicReference<GeckoSession> sessionRef = new AtomicReference<>();
        GeckoRuntime runtime = mHelper.getGeckoRuntime();
        mInstrumentation.runOnMainSync(() -> {
            activity.setStatus(progress + "\nloading");
            GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                    .usePrivateMode(false)
                    .suspendMediaWhenInactive(false)
                    .useTrackingProtection(mHelper.blocksTrackingContent(false))
                    .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                    .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                    .allowJavascript(true)
                    .build();
            GeckoSession session = new GeckoSession(settings);
            session.setProgressDelegate(watch);
            session.setNavigationDelegate(watch);
            session.setContentDelegate(watch);
            session.setPermissionDelegate(watch);
            mHelper.registerSession(session);
            session.open(runtime);
            activity.getGeckoView().setSession(session);
            session.setActive(true);
            runtime.getWebExtensionController().setTabActive(session, true);
            session.loadUri(url);
            sessionRef.set(session);
        });
        GeckoSession session = sessionRef.get();
        int tabId = -1;
        try {
            boolean loaded = watch.stopped.await(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            step(steps, t0, "load", loaded ? (watch.success ? "ok" : "failed") : "timeout");
            site.put("loadOk", loaded && watch.success);

            tabId = resolveTab(watch.location);
            site.put("tabId", tabId);
            if (tabId < 0) {
                throw new IllegalStateException("no tab for " + watch.location);
            }

            // Consent: every frame, up to three rounds while something was
            // clicked (a second layer, or a wall in a frame that came late).
            JSONArray clicks = new JSONArray();
            for (int round = 0; round < 3; round++) {
                JSONArray results = runOp(tabId, "consent", new JSONObject(), false, 10_000);
                boolean clicked = false;
                for (int i = 0; results != null && i < results.length(); i++) {
                    JSONObject r = results.optJSONObject(i);
                    String hit = r == null ? "" : r.optString("result", "");
                    if (!TextUtils.isEmpty(hit) && !"null".equals(hit)) {
                        clicked = true;
                        JSONObject click = new JSONObject();
                        click.put("frame", r.optString("url"));
                        click.put("button", hit);
                        clicks.put(click);
                    }
                }
                if (!clicked) {
                    break;
                }
                SystemClock.sleep(2_500);
            }
            site.put("consent", clicks);
            step(steps, t0, "consent", clicks.length() + " click(s)");
            status(activity, progress + "\nwaiting");
            SystemClock.sleep(waitMs / 2);

            status(activity, progress + "\nscrolling");
            JSONArray scroll = runOp(tabId, "scroll", new JSONObject(), true, 45_000);
            site.put("scroll", scroll);
            step(steps, t0, "scroll", "done");
            status(activity, progress + "\nwaiting for captures");
            SystemClock.sleep(waitMs);

            settle(tabId, t0, steps);

            status(activity, progress + "\nreading the page");
            JSONArray census = runOp(tabId, "census", new JSONObject(), false, 15_000);
            JSONArray frames = callArray("frames", tabFields(tabId), 10_000);
            JSONObject censusOut = new JSONObject();
            censusOut.put("frames", frames == null ? new JSONArray() : frames);
            censusOut.put("census", census == null ? new JSONArray() : census);
            write(new File(dir, "census.json"), censusOut.toString(2));

            List<BrowserDownloadEntity> entities = entitiesFor(tabId);
            JSONArray entityJson = new JSONArray();
            for (BrowserDownloadEntity e : entities) {
                entityJson.put(describe(activity, e));
            }
            write(new File(dir, "entities.json"), entityJson.toString(2));

            JSONArray emits = emitsFor(tabId);
            write(new File(dir, "emits.json"), emits.toString(2));

            JSONObject aroundArgs = new JSONObject();
            aroundArgs.put("needles", needles(entities));
            aroundArgs.put("media", true);
            JSONArray around = runOp(tabId, "around", aroundArgs, false, 20_000);
            write(new File(dir, "around.json"), around == null ? "[]" : around.toString(2));

            Object requests = mPort.call(command("requests", tabFields(tabId)), 15_000);
            write(new File(dir, "requests.json"), requests == null ? "{}" : requests.toString());

            screenshot(activity.getGeckoView(), new File(dir, "screen.jpg"));

            site.put("location", watch.location);
            site.put("title", watch.title);
            site.put("captures", entities.size());
            site.put("emits", emits.length());
            site.put("frames", frames == null ? 0 : frames.length());
        } finally {
            site.put("ms", SystemClock.elapsedRealtime() - t0);
            write(new File(dir, "site.json"), site.toString(2));
            mInstrumentation.runOnMainSync(() -> {
                activity.getGeckoView().releaseSession();
                if (session != null) {
                    session.close();
                }
            });
            if (tabId >= 0) {
                try {
                    mPort.call(command("forget", tabFields(tabId)), 5_000);
                } catch (Exception e) {
                    Log.w(TAG, "forget failed", e);
                }
            }
            // Let the extensions see the tab close (the repository trims it).
            SystemClock.sleep(1_500);
        }
        return site;
    }

    /** Waits until the tab's captures and capture messages stop changing. */
    private void settle(int tabId, long t0, JSONArray steps) throws JSONException {
        long start = SystemClock.elapsedRealtime();
        long quietSince = start;
        int lastEntities = -1;
        int lastEmits = -1;
        while (SystemClock.elapsedRealtime() - start < SETTLE_MAX_MS) {
            int entities = entitiesFor(tabId).size();
            int emits = emitsFor(tabId).length();
            if (entities != lastEntities || emits != lastEmits) {
                lastEntities = entities;
                lastEmits = emits;
                quietSince = SystemClock.elapsedRealtime();
            } else if (SystemClock.elapsedRealtime() - quietSince >= SETTLE_QUIET_MS) {
                break;
            }
            SystemClock.sleep(1_000);
        }
        step(steps, t0, "settle", lastEntities + " capture(s), " + lastEmits + " emit(s)");
    }

    /** The audited tab's id. The background page answers on an event loop
     *  the page can keep busy: repubblica.it left one {@code tabs} call
     *  unanswered for 10 s (device audit 2026-10-20) and the whole site was
     *  lost, so the lookup is tried a few times before the site is given up. */
    private int resolveTab(@Nullable String location) throws Exception {
        IllegalStateException last = null;
        for (int attempt = 0; attempt < TAB_LOOKUP_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                SystemClock.sleep(2_000);
            }
            try {
                int id = tabIn(mPort.call(command("tabs", new JSONObject()), 10_000), location);
                if (id >= 0) {
                    return id;
                }
                last = null;
            } catch (IllegalStateException e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        return -1;
    }

    /** The tab showing {@code location}, else the active one, else the newest. */
    private static int tabIn(@Nullable Object reply, @Nullable String location) {
        if (!(reply instanceof JSONArray tabs)) {
            return -1;
        }
        int active = -1;
        int newest = -1;
        for (int i = 0; i < tabs.length(); i++) {
            JSONObject tab = tabs.optJSONObject(i);
            if (tab == null) {
                continue;
            }
            int id = tab.optInt("id", -1);
            if (location != null && location.equals(tab.optString("url"))) {
                return id;
            }
            if (tab.optBoolean("active")) {
                active = Math.max(active, id);
            }
            newest = Math.max(newest, id);
        }
        return active >= 0 ? active : newest;
    }

    /* ---------------------------------------------------------------------- */
    /* what is recorded                                                       */
    /* ---------------------------------------------------------------------- */

    private List<BrowserDownloadEntity> entitiesFor(int tabId) {
        AtomicReference<List<BrowserDownloadEntity>> out = new AtomicReference<>(new ArrayList<>());
        mInstrumentation.runOnMainSync(() -> {
            List<BrowserDownloadEntity> all = mCaptures.getData().getValue();
            List<BrowserDownloadEntity> mine = new ArrayList<>();
            if (all != null) {
                for (BrowserDownloadEntity e : all) {
                    if (e.getTabId() == tabId) {
                        mine.add(e);
                    }
                }
            }
            out.set(mine);
        });
        return out.get();
    }

    /** The capture messages of this tab (and those that named no tab). */
    private JSONArray emitsFor(int tabId) {
        JSONArray out = new JSONArray();
        synchronized (mEmits) {
            for (JSONObject emit : mEmits) {
                int emitTab = emit.optInt("tabId", 0);
                if (emitTab == tabId || emitTab <= 0) {
                    out.put(redact(emit));
                }
            }
        }
        return out;
    }

    private static JSONObject describe(SiteAuditActivity context, BrowserDownloadEntity e) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("uid", e.getUid());
        o.put("shown", BrowserOptionAdapter.tileTitle(context, e));
        o.put("name", e.getFileName());
        o.put("url", e.getFileUrl());
        o.put("origin", e.getFileOrigin());
        o.put("mime", e.getMimeType());
        o.put("video", CaptureSections.isVideo(e));
        o.put("audio", CaptureSections.isAudioCapture(e));
        o.put("square", CaptureSections.isSquare(e));
        o.put("filenameLike", CaptureSections.isFilenameLike(e.getFileName()));
        o.put("thumbnail", e.getFileThumbnail());
        o.put("description", e.getFileDescription());
        o.put("namedByPage", e.isNamedByPage());
        o.put("durationMs", e.getDurationTime());
        o.put("length", e.getFileLength());
        JSONArray variants = new JSONArray();
        List<FFmpegEntity> streams = e.getStreams();
        if (streams != null) {
            for (FFmpegEntity s : streams) {
                JSONObject v = new JSONObject();
                v.put("url", s.getStreamUrl());
                v.put("audioUrl", s.getStreamAudioUrl());
                v.put("w", s.getWidth());
                v.put("h", s.getHeight());
                v.put("size", s.getByteSize());
                v.put("bitrate", s.getBitrate());
                variants.put(v);
            }
        }
        o.put("variants", variants);
        return o;
    }

    /**
     * What to look for around each clip: its URL without the query, its file
     * name, its poster. An HLS master the page never puts in the DOM is still
     * found by its poster (a {@code poster=} attribute) or its file name in a
     * player's data-* config.
     */
    private static JSONArray needles(List<BrowserDownloadEntity> entities) throws JSONException {
        JSONArray out = new JSONArray();
        // A thumbnail several tiles share is the page's own picture (its
        // logo): looked for, it finds the <head> and every card at once.
        Map<String, Integer> thumbs = new HashMap<>();
        for (BrowserDownloadEntity e : entities) {
            String t = e.getFileThumbnail();
            if (!TextUtils.isEmpty(t)) {
                Integer n = thumbs.get(t);
                thumbs.put(t, n == null ? 1 : n + 1);
            }
        }
        int clips = 0;
        for (BrowserDownloadEntity e : entities) {
            if (CaptureSections.isSquare(e)) {
                continue;
            }
            if (clips >= MAX_NEEDLE_CLIPS) {
                break;
            }
            clips++;
            Set<String> values = new LinkedHashSet<>();
            addUrlNeedles(values, e.getFileUrl());
            String thumb = e.getFileThumbnail();
            Integer shared = TextUtils.isEmpty(thumb) ? null : thumbs.get(thumb);
            if (shared == null || shared < 2) {
                addUrlNeedles(values, thumb);
            }
            List<FFmpegEntity> streams = e.getStreams();
            if (streams != null) {
                for (int i = 0; i < streams.size() && i < 4; i++) {
                    addUrlNeedles(values, streams.get(i).getStreamUrl());
                }
            }
            JSONObject needle = new JSONObject();
            needle.put("key", String.valueOf(e.getUid()));
            needle.put("values", new JSONArray(values));
            out.put(needle);
        }
        return out;
    }

    private static void addUrlNeedles(Set<String> values, @Nullable String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) {
            return;
        }
        String bare = url;
        int cut = bare.indexOf('?');
        if (cut < 0) {
            cut = bare.indexOf('#');
        }
        if (cut >= 0) {
            bare = bare.substring(0, cut);
        }
        values.add(bare);
        int at = bare.indexOf("://");
        if (at >= 0) {
            int path = bare.indexOf('/', at + 3);
            if (path >= 0 && bare.length() - path >= 8) {
                values.add(bare.substring(path));
            }
        }
        int slash = bare.lastIndexOf('/');
        if (slash >= 0) {
            String file = bare.substring(slash + 1);
            int dot = file.lastIndexOf('.');
            String stem = dot > 0 ? file.substring(0, dot) : file;
            if (stem.length() >= 8 && !GENERIC_STEMS.contains(stem.toLowerCase(Locale.ROOT))) {
                values.add(stem);
            }
        }
    }

    /** Credentials out: the emits carry the page's cookies and tokens. */
    private static JSONObject redact(JSONObject in) {
        try {
            return (JSONObject) redactValue(new JSONObject(in.toString()));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static Object redactValue(Object value) throws JSONException {
        if (value instanceof JSONObject o) {
            String headerName = o.optString("name", "").toLowerCase(Locale.ROOT);
            if (SECRET_KEYS.contains(headerName) && o.has("value")) {
                o.put("value", "<redacted>");
            }
            JSONArray names = o.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String key = names.getString(i);
                if (SECRET_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                    o.put(key, "<redacted>");
                } else {
                    o.put(key, redactValue(o.get(key)));
                }
            }
            return o;
        }
        if (value instanceof JSONArray a) {
            for (int i = 0; i < a.length(); i++) {
                a.put(i, redactValue(a.get(i)));
            }
            return a;
        }
        return value;
    }

    private void screenshot(GeckoView view, File file) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bitmap> bitmap = new AtomicReference<>();
        mInstrumentation.runOnMainSync(() -> view.capturePixels().accept(value -> {
            bitmap.set(value);
            latch.countDown();
        }, error -> latch.countDown()));
        try {
            latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        Bitmap shot = bitmap.get();
        if (shot == null) {
            return;
        }
        int width = Math.min(540, shot.getWidth());
        int height = Math.max(1, Math.round(shot.getHeight() * (width / (float) shot.getWidth())));
        Bitmap small = Bitmap.createScaledBitmap(shot, width, height, true);
        try (OutputStream out = new FileOutputStream(file)) {
            small.compress(Bitmap.CompressFormat.JPEG, 70, out);
        } catch (IOException e) {
            Log.w(TAG, "screenshot not written", e);
        }
    }

    /* ---------------------------------------------------------------------- */
    /* the siteaudit extension                                                */
    /* ---------------------------------------------------------------------- */

    private void boot(Context app) {
        mInstrumentation.runOnMainSync(() -> {
            RepositoryEntryPoint entryPoint = EntryPointAccessors.fromApplication(
                    app, RepositoryEntryPoint.class);
            mHelper = entryPoint.getGeckoRuntimeHelper();
            mCaptures = entryPoint.getBrowserDownloadRepository();
        });
        assertNotNull(mHelper);
        assertNotNull(mCaptures);
    }

    /**
     * A fresh install every run: a leftover registration (a test that died
     * before its uninstall) is removed first, so a changed content.js is never
     * shadowed by the cached copy of the same version.
     */
    private void installExtension() throws InterruptedException {
        WebExtensionController controller = mHelper.getGeckoRuntime().getWebExtensionController();
        CountDownLatch cleaned = new CountDownLatch(1);
        mInstrumentation.runOnMainSync(() -> controller.list().accept(list -> {
            GeckoResult<Void> done = GeckoResult.fromValue(null);
            if (list != null) {
                for (WebExtension ext : list) {
                    if (EXT_ID.equals(ext.id)) {
                        done = controller.uninstall(ext);
                    }
                }
            }
            done.accept(v -> cleaned.countDown(), e -> cleaned.countDown());
        }, e -> cleaned.countDown()));
        cleaned.await(BOOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        CountDownLatch installed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        mInstrumentation.runOnMainSync(() -> controller.ensureBuiltIn(EXT_URI, EXT_ID).accept(ext -> {
            mExtension = ext;
            if (ext != null) {
                ext.setMessageDelegate(mPort, NATIVE_APP);
            }
            installed.countDown();
        }, e -> {
            failure.set(e);
            installed.countDown();
        }));
        assertTrue("ensureBuiltIn(siteaudit) did not resolve",
                installed.await(BOOT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertNotNull("ensureBuiltIn(siteaudit) failed: " + failure.get(), mExtension);
    }

    private void uninstallExtension() {
        WebExtension ext = mExtension;
        if (ext == null) {
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        mInstrumentation.runOnMainSync(() -> mHelper.getGeckoRuntime().getWebExtensionController()
                .uninstall(ext).accept(v -> done.countDown(), e -> done.countDown()));
        try {
            done.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        mExtension = null;
    }

    @Nullable
    private JSONArray runOp(int tabId, String op, JSONObject args, boolean topOnly, long timeoutMs) {
        try {
            JSONObject fields = tabFields(tabId);
            fields.put("op", op);
            fields.put("args", args);
            fields.put("top", topOnly);
            Object reply = mPort.call(command("run", fields), timeoutMs);
            return reply instanceof JSONArray array ? array : null;
        } catch (Exception e) {
            Log.w(TAG, op + " failed", e);
            return null;
        }
    }

    @Nullable
    private JSONArray callArray(String cmd, JSONObject fields, long timeoutMs) {
        try {
            Object reply = mPort.call(command(cmd, fields), timeoutMs);
            return reply instanceof JSONArray array ? array : null;
        } catch (Exception e) {
            Log.w(TAG, cmd + " failed", e);
            return null;
        }
    }

    private static JSONObject tabFields(int tabId) throws JSONException {
        JSONObject fields = new JSONObject();
        fields.put("tabId", tabId);
        return fields;
    }

    private static JSONObject command(String cmd, JSONObject fields) throws JSONException {
        fields.put("cmd", cmd);
        return fields;
    }

    /**
     * The "siteaudit" native port: the extension's background page connects,
     * the test sends {@code {id, cmd, …}} and waits for {@code {id, ok, reply}}.
     */
    private final class AuditPort implements WebExtension.MessageDelegate, WebExtension.PortDelegate {
        private volatile WebExtension.Port mActive;
        private final CountDownLatch mConnected = new CountDownLatch(1);
        private final AtomicInteger mNextId = new AtomicInteger(1);
        private final Map<Integer, Waiter> mWaiting = new ConcurrentHashMap<>();

        boolean awaitConnected(long timeoutMs) throws InterruptedException {
            return mConnected.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        @Nullable
        @Override
        public GeckoResult<Object> onMessage(@NonNull String nativeApp, @NonNull Object message,
                                             @NonNull WebExtension.MessageSender sender) {
            return null;
        }

        @Override
        public void onConnect(@NonNull WebExtension.Port port) {
            port.setDelegate(this);
            mActive = port;
            mConnected.countDown();
        }

        @Override
        public void onPortMessage(@NonNull Object message, @NonNull WebExtension.Port port) {
            if (!(message instanceof JSONObject json)) {
                return;
            }
            Waiter waiter = mWaiting.remove(json.optInt("id", -1));
            if (waiter != null) {
                waiter.reply = json;
                waiter.latch.countDown();
            }
        }

        @Override
        public void onDisconnect(@NonNull WebExtension.Port port) {
            if (mActive == port) {
                mActive = null;
            }
            for (Waiter waiter : mWaiting.values()) {
                waiter.latch.countDown();
            }
            mWaiting.clear();
        }

        /** Sends a command and returns its reply; throws on an error or a timeout. */
        @Nullable
        Object call(JSONObject cmd, long timeoutMs) throws Exception {
            WebExtension.Port port = mActive;
            if (port == null) {
                throw new IllegalStateException("siteaudit port not connected");
            }
            int id = mNextId.getAndIncrement();
            cmd.put("id", id);
            Waiter waiter = new Waiter();
            mWaiting.put(id, waiter);
            // Callable from any thread (it posts to Gecko); it throws for a
            // port Gecko considers dead, which must not reach the main thread.
            try {
                port.postMessage(cmd);
            } catch (RuntimeException e) {
                mWaiting.remove(id);
                if (mActive == port) {
                    mActive = null;
                }
                throw new IllegalStateException(cmd.optString("cmd") + ": port dead", e);
            }
            boolean answered = waiter.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            mWaiting.remove(id);
            JSONObject reply = waiter.reply;
            if (!answered || reply == null) {
                throw new IllegalStateException(cmd.optString("cmd") + ": no answer in " + timeoutMs + " ms");
            }
            if (!reply.optBoolean("ok")) {
                throw new IllegalStateException(cmd.optString("cmd") + ": " + reply.optString("error"));
            }
            return reply.opt("reply");
        }
    }

    private static final class Waiter {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile JSONObject reply;
    }

    /** The audited session's delegates: load progress, location, title, and
     *  the app's autoplay rule (allowed; everything else denied, no prompts). */
    private static final class PageWatch implements GeckoSession.ProgressDelegate,
            GeckoSession.NavigationDelegate, GeckoSession.ContentDelegate, GeckoSession.PermissionDelegate {
        final CountDownLatch stopped = new CountDownLatch(1);
        volatile boolean started;
        volatile boolean success;
        volatile String location;
        volatile String title;

        @Override
        public void onPageStart(@NonNull GeckoSession session, @NonNull String url) {
            if (!url.startsWith("about:")) {
                started = true;
            }
        }

        @Override
        public void onPageStop(@NonNull GeckoSession session, boolean ok) {
            if (started) {
                success = ok;
                stopped.countDown();
            }
        }

        @Override
        public void onLocationChange(@NonNull GeckoSession session, @Nullable String url,
                                     @NonNull List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                     @NonNull Boolean hasUserGesture) {
            if (url != null && !url.startsWith("about:")) {
                location = url;
            }
        }

        @Override
        public void onTitleChange(@NonNull GeckoSession session, @Nullable String newTitle) {
            title = newTitle;
        }

        @Override
        public GeckoResult<Integer> onContentPermissionRequest(@NonNull GeckoSession session,
                                                               @NonNull ContentPermission perm) {
            if (perm.permission == PERMISSION_AUTOPLAY_AUDIBLE
                    || perm.permission == PERMISSION_AUTOPLAY_INAUDIBLE) {
                return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
            }
            return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
        }
    }

    /* ---------------------------------------------------------------------- */
    /* plumbing                                                               */
    /* ---------------------------------------------------------------------- */

    private void status(SiteAuditActivity activity, String text) {
        mInstrumentation.runOnMainSync(() -> activity.setStatus(text));
    }

    private static void step(JSONArray steps, long t0, String what, String result) throws JSONException {
        JSONObject step = new JSONObject();
        step.put("ms", SystemClock.elapsedRealtime() - t0);
        step.put("step", what);
        step.put("result", result);
        steps.put(step);
        Log.i(TAG, "  " + what + ": " + result);
    }

    private List<String> sitesFrom(Bundle args) throws IOException {
        List<String> raw = new ArrayList<>();
        String given = args.getString("sites");
        if (!TextUtils.isEmpty(given)) {
            for (String token : given.split("[,\\s]+")) {
                if (!token.isEmpty()) {
                    raw.add(token);
                }
            }
        } else {
            Context testContext = mInstrumentation.getContext();
            try (InputStream in = testContext.getAssets().open("site-audit/sites.txt");
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                while (line != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        raw.add(trimmed.split("\\s+")[0]);
                    }
                    line = reader.readLine();
                }
            }
        }
        String only = args.getString("only");
        int limit = (int) longArg(args, "limit", Integer.MAX_VALUE);
        List<String> out = new ArrayList<>();
        for (String site : raw) {
            String url = site.contains("://") ? site : "https://" + site;
            if (!TextUtils.isEmpty(only) && !url.contains(only)) {
                continue;
            }
            if (out.size() >= limit) {
                break;
            }
            out.add(url);
        }
        return out;
    }

    private static long longArg(Bundle args, String name, long fallback) {
        String value = args.getString(name);
        if (TextUtils.isEmpty(value)) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String slug(String url) {
        String host = url;
        String path = "";
        try {
            URI uri = new URI(url);
            host = uri.getHost() == null ? url : uri.getHost();
            path = uri.getPath() == null ? "" : uri.getPath();
        } catch (Exception e) {
            // keep the raw string
        }
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        String out = (host + path).replaceAll("[^A-Za-z0-9.]+", "_").replaceAll("_+$", "");
        return out.length() > 60 ? out.substring(0, 60) : out;
    }

    private static void write(File file, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}

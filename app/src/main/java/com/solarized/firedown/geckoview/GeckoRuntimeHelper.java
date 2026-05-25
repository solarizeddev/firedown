package com.solarized.firedown.geckoview;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.UiThread;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.GeckoInspectEntity;
import com.solarized.firedown.data.repository.BrowserDownloadRepository;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.IncognitoStateRepository;
import com.solarized.firedown.data.repository.IconsRepository;
import com.solarized.firedown.data.repository.WasmAllowlistRepository;
import com.solarized.firedown.manager.UrlParser;
import com.solarized.firedown.manager.UrlType;
import com.solarized.firedown.utils.JsonHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.ExperimentalGeckoViewApi;
import org.mozilla.geckoview.GeckoPreferenceController;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.WebExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Singleton
public class GeckoRuntimeHelper {
    private static final String TAG = GeckoRuntimeHelper.class.getName();
    public static final int DEFAULT_TAB_ID = 10001;
    //Map extensions
    private final Map<String, WebExtension> mLoadedExtensions = new HashMap<>();
    private final GeckoRuntime sGeckoRuntime;
    private final IconsRepository mIconsRepository;
    private final BrowserDownloadRepository mBrowserDownloadRepository;
    private final GeckoStateDataRepository mGeckoStateDataRepository;
    private final IncognitoStateRepository mIncognitoStateRepository;
    private final WasmAllowlistRepository mWasmAllowlistRepository;
    private final GeckoUblockHelper mGeckoUblockHelper;
    private final Executor mMainExecutor;
    public final BrowserSessionActionDelegate mBrowserSessionActionDelegate;
    private final MessageDelegate mMessageDelegate;
    private final PoTokenGenerator mPoTokenGenerator;
    private final SharedPreferences mSharedPreferences;
    private final PriorityTaskThreadPoolExecutor mPriorityExecutor;
    private final Executor mNetworkExecutor;
    private final OkHttpClient mOkHttpClient;
    private final Map<String, WebExtension.Port> mPorts = new HashMap<>();
    private int mTabId = DEFAULT_TAB_ID;

    @Inject
    public GeckoRuntimeHelper(
            @ApplicationContext Context context,
            SharedPreferences sharedPreferences,
            IconsRepository iconsRepository,
            BrowserDownloadRepository browserDownloadRepository,
            GeckoStateDataRepository geckoStateDataRepository,
            IncognitoStateRepository incognitoStateRepository,
            WasmAllowlistRepository wasmAllowlistRepository,
            GeckoUblockHelper geckoUblockHelper,
            PriorityTaskThreadPoolExecutor priorityExecutor,
            OkHttpClient okHttpClient,
            @Qualifiers.MainThread Executor mainExecutor,
            @Qualifiers.Network Executor networkExecutor
    ) {
        this.mIconsRepository = iconsRepository;
        this.mBrowserDownloadRepository = browserDownloadRepository;
        this.mGeckoStateDataRepository = geckoStateDataRepository;
        this.mIncognitoStateRepository = incognitoStateRepository;
        this.mWasmAllowlistRepository = wasmAllowlistRepository;
        this.mGeckoUblockHelper = geckoUblockHelper;
        this.mPriorityExecutor = priorityExecutor;
        this.mMainExecutor = mainExecutor;
        this.mNetworkExecutor = networkExecutor;
        this.mOkHttpClient = okHttpClient;
        this.mSharedPreferences = sharedPreferences;

        final GeckoRuntimeSettings.Builder runtimeSettingsBuilder = new GeckoRuntimeSettings.Builder();

        if (BuildConfig.DEBUG) {
            runtimeSettingsBuilder.arguments(new String[]{"-purgecaches"});
        }

        runtimeSettingsBuilder
                // Gecko native crashes need libcrashhelper.so to produce
                // a Breakpad minidump — without it the intent reaches us
                // with no diagnostic data, so there's nothing actionable
                // to report. Tab-level death is already handled by
                // ContentDelegate.onKill (reload the killed tab). Java
                // crashes in the main process are still caught by
                // CrashHandler installed in App.onCreate.
                .crashHandler(null)
                .remoteDebuggingEnabled(BuildConfig.DEBUG)
                .consoleOutput(BuildConfig.DEBUG)
                .debugLogging(BuildConfig.DEBUG)
                .contentBlocking(new ContentBlocking.Settings.Builder()
                        .allowListConvenienceTrackingProtection(true)
                        .allowListBaselineTrackingProtection(true)
                        .antiTracking(Preferences.getAntiTrackingCategories(sharedPreferences))
                        .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                        .cookiePurging(true)
                        .enhancedTrackingProtectionCategory(Preferences.getEnhancedTrackingProtectionCategories(sharedPreferences))
                        .enhancedTrackingProtectionLevel(Preferences.getEnhancedTrackingProtectionLevel(sharedPreferences))
                        .strictSocialTrackingProtection(true)
                        .cookieBehavior(Preferences.getCookieBehavior(sharedPreferences))
                        .queryParameterStrippingEnabled(Preferences.getQueryParameterStrippingEnabled(sharedPreferences))
                        .queryParameterStrippingPrivateBrowsingEnabled(Preferences.getQueryParameterStrippingEnabled(sharedPreferences))
                        .queryParameterStrippingStripList(Preferences.getQueryParameterStripList(sharedPreferences))
                        .build())
                .fontSizeFactor(1.0f)
                .fissionEnabled(true)
                .setLnaBlocking(true)
                .setLnaEnabled(true)
                .javaScriptEnabled(Preferences.getJavascriptEnabled(sharedPreferences))
                .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
                .aboutConfigEnabled(true);

        sGeckoRuntime = GeckoRuntime.create(context, runtimeSettingsBuilder.build());

        sGeckoRuntime.getSettings().setBaselineFingerprintingProtection(true);
        sGeckoRuntime.getSettings().setFingerprintingProtection(true);
        sGeckoRuntime.getSettings().setFingerprintingProtectionPrivateBrowsing(true);

        mMessageDelegate = new MessageDelegate();

        // PoTokenGenerator owns its own GeckoSession (created outside
        // TabDelegate to bypass the WebExtension Tabs API surface that has
        // been the source of repeated mid-mint tab deaths). It needs to
        // attach our loaded WebExtensions to the session it creates so the
        // youtube content script gets injected on robots.txt — pass
        // registerSession as a method reference so PoTokenGenerator doesn't
        // have to know about mLoadedExtensions.
        mPoTokenGenerator = new PoTokenGenerator(sGeckoRuntime, this::registerSession);

        mBrowserSessionActionDelegate = new BrowserSessionActionDelegate();

        setupWebExtensions();

        // User-facing privacy toggles — defaults pick the privacy-preferring
        // value but every one of them can be flipped from settings. Disk
        // cache and Safe Browsing follow the "Disable X" convention used
        // by WebGL (switch ON = privacy-preferring action), so invert when
        // calling the underlying setter which still takes the runtime's
        // "is this feature enabled" sense.

        setWebRTC(sharedPreferences.getBoolean(Preferences.SETTINGS_ENABLE_WEBRTC,
                Preferences. DEFAULT_ENABLE_WEBRTC));
        setWebAssembly(sharedPreferences.getBoolean(Preferences.SETTINGS_ENABLE_WEBASSEMBLY,
                Preferences.DEFAULT_ENABLE_WEBASSEMBLY));
        setJITCompiler(sharedPreferences.getBoolean(Preferences.SETTINGS_ENABLE_JIT,
                Preferences.DEFAULT_ENABLE_JIT));
        setWebGL(sharedPreferences.getBoolean(Preferences.SETTINGS_DISABLE_WEBGL,
                Preferences.DEFAULT_DISABLE_WEBGL));
        setGeo(sharedPreferences.getBoolean(Preferences.SETTINGS_BLOCK_LOCATION,
                Preferences.DEFAULT_BLOCK_LOCATION));
        setResistFingerPrinting(sharedPreferences.getBoolean(Preferences.SETTINGS_ENABLE_RESIST_FINGERPRINTING,
                Preferences.DEFAULT_RESIST_FINGERPRINTING));
        boolean drmEnabledPref = Preferences.getDRMEnabled(sharedPreferences);
        Log.d(TAG, "init: SETTINGS_ENABLE_DRM resolved to " + drmEnabledPref
                + " → setDRM(disable=" + (!drmEnabledPref) + ")");
        setDRM(!drmEnabledPref);
        setHttpsOnly(sharedPreferences.getBoolean(
                Preferences.SETTINGS_HTTPS_ONLY, Preferences.DEFAULT_HTTPS_ONLY));
        setDiskCacheEnabled(!sharedPreferences.getBoolean(
                Preferences.SETTINGS_DISABLE_DISK_CACHE, Preferences.DEFAULT_DISABLE_DISK_CACHE));
        setSafeBrowsing(!sharedPreferences.getBoolean(
                Preferences.SETTINGS_DISABLE_SAFE_BROWSING, Preferences.DEFAULT_DISABLE_SAFE_BROWSING));
        // These have no UI toggle — the privacy gain is high enough and the
        // breakage low enough that it's not a meaningful choice to expose.
        applyHardeningPrefs();
    }

    private void setupWebExtensions() {
        // We use the MainExecutor for all delegate registrations to prevent threading crashes
        registerBuiltIn("resource://android/assets/parser/", "parser@solarized.dev", "parser");
        registerBuiltIn("resource://android/assets/youtube/", "youtube@solarized.dev", "youtube");
        registerBuiltIn("resource://android/assets/webrequests/", "downloader@solarized.dev", "browser");
        registerBuiltIn("resource://android/assets/ublock/", "uBlock0@raymondhill.net", "ublock");
        registerBuiltIn("resource://android/assets/icons/", "icons@mozac.org", "icons");
    }

    private void registerBuiltIn(String uri, String id, String delegateId) {
        sGeckoRuntime.getWebExtensionController()
                .ensureBuiltIn(uri, id)
                .accept(webExtension -> mMainExecutor.execute(() -> {
                    // Store it for future sessions
                    mLoadedExtensions.put(delegateId, webExtension);

                    // Set global delegate
                    if (webExtension != null && delegateId != null) {
                        webExtension.setMessageDelegate(mMessageDelegate, delegateId);
                        // The youtube extension's content.js opens a second
                        // native port for PoTokenGenerator. setMessageDelegate
                        // is keyed by nativeAppName — the call above only
                        // registers for the extension's main name ("youtube"),
                        // so connectNative('youtube_potoken') from content.js
                        // would never reach Java's MessageDelegate.onConnect.
                        // Register the same delegate for the potoken port too;
                        // the router in onConnect dispatches by port.name.
                        if ("youtube".equals(delegateId)) {
                            webExtension.setMessageDelegate(
                                    mMessageDelegate, PoTokenGenerator.PORT_NAME);
                        }
                    }

                    // Set TabDelegate so extensions can use browser.tabs.create
                    // (needed by YouTube extension for hidden robots.txt BotGuard session)
                    if (webExtension != null) {
                        webExtension.setTabDelegate(mTabDelegate);
                    }

                    // If you have a current active session, attach it now
                    GeckoState geckoState = mGeckoStateDataRepository.getCurrentGeckoState();
                    if (geckoState != null && geckoState.getGeckoSession() != null) {
                        registerSession(geckoState.getGeckoSession());
                    }
                }), e -> Log.e(TAG, "Error", e));
    }

    /**
     * TabDelegate handles browser.tabs.create calls from WebExtensions.
     * Creates a new GeckoSession for the requested tab. Used by the YouTube
     * extension to create a hidden youtube.com/robots.txt session for BotGuard
     * PO token generation (no CSP on robots.txt allows eval injection).
     */

    private final WebExtension.TabDelegate mTabDelegate = new WebExtension.TabDelegate() {
        @Override
        public GeckoResult<GeckoSession> onNewTab(@NonNull WebExtension source,
                                                  @NonNull WebExtension.CreateTabDetails createDetails) {
            Log.d(TAG, "onNewTab from " + source.id + ": " + createDetails.url);

            GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                    .usePrivateMode(false)
                    .suspendMediaWhenInactive(true)
                    .allowJavascript(true)
                    .build();

            // GeckoView requires an UNOPENED session — it opens it internally
            GeckoSession session = new GeckoSession(settings);
            registerSession(session);

            return GeckoResult.fromValue(session);
        }
    };


    @UiThread
    public void registerSession(GeckoSession geckoSession) {
        mMainExecutor.execute(() -> {
            for (Map.Entry<String, WebExtension> entry : mLoadedExtensions.entrySet()) {
                geckoSession.getWebExtensionController().setMessageDelegate(entry.getValue(), mMessageDelegate, entry.getKey());
                geckoSession.getWebExtensionController().setActionDelegate(entry.getValue(), mBrowserSessionActionDelegate);
                // Mirror the global setMessageDelegate hookup for the
                // PoTokenGenerator port name so connectNative('youtube_potoken')
                // from content.js reaches Java on per-session controllers too.
                if ("youtube".equals(entry.getKey())) {
                    geckoSession.getWebExtensionController().setMessageDelegate(
                            entry.getValue(), mMessageDelegate, PoTokenGenerator.PORT_NAME);
                }
            }
        });
    }

    public final class BrowserSessionActionDelegate implements WebExtension.ActionDelegate {
        @Override
        public void onBrowserAction(@NonNull WebExtension extension, @Nullable GeckoSession session, @NonNull WebExtension.Action action) {
            String count = TextUtils.isEmpty(action.badgeText) ? "0" : action.badgeText;
            boolean isIncognito = session != null
                    && mIncognitoStateRepository.getGeckoState(session) != null;
            if(BuildConfig.DEBUG) {
                Log.d(TAG, "onBrowserAction: " + count + " is_incognito: " + isIncognito);
            }
            mGeckoUblockHelper.onAdsCount(count, isIncognito);
        }
    }


    private final class MessageDelegate implements WebExtension.MessageDelegate {

        @Nullable
        @Override
        public GeckoResult<Object> onMessage(@NonNull String nativeApp, @NonNull Object message, @NonNull WebExtension.MessageSender sender) {
            if (!(message instanceof JSONObject jsonObject))
                return null;
            Log.d(TAG, "onMessage: " + jsonObject);
            try {
                switch (nativeApp) {
                    case "browser" -> handleBrowserMessage(jsonObject, sender.session);
                    case "icons" -> handleIconsMessage(jsonObject);
                    case "ublock" -> handleUblockMessage(jsonObject, sender.session);
                    case "youtube", "parser" -> handleExtractionMessage(jsonObject);
                }
            } catch (JSONException e) {
                Log.e(TAG, "JSON Error", e);
            }
            return null;
        }


        private void handleBrowserMessage(JSONObject json, GeckoSession senderSession) throws JSONException {
            Log.d(TAG, "handleBrowserMessage: " + json);
            String listener = json.getString("listener");
            switch (listener) {
                case "onActivated", "onUpdated" -> {
                    mTabId = json.getInt("id");
                    mGeckoStateDataRepository.setCurrentTabId(mTabId);
                    mBrowserDownloadRepository.postComplete();
                }
                case "onRemoved" -> {
                    int removedTabId = json.getInt("id");
                    mBrowserDownloadRepository.trimTabs(removedTabId);
                }
                case "onHeadersReceived", "onResponseStarted", "contentScript" -> {
                    handleExtractionMessage(json);
                }
                case "wasmUnavailable" -> {
                    // The content-script bridge spotted a WASM error on the
                    // page. Route to the active repo (incognito vs regular)
                    // so the BrowserFragment for that tab type observes it
                    // and shows the "Enable for {host}?" snackbar.
                    //
                    // Prefer senderSession lookup over the JS-sent tabId —
                    // content scripts going through sendNativeMessage don't
                    // populate sender.tab, so the JS payload's tabId may
                    // be -1. The GeckoSession the message arrived on is
                    // always authoritative.
                    String url = json.optString("url", null);
                    String detail = json.optString("detail", "");
                    Log.d(TAG, "wasmUnavailable received: url=" + url
                            + " session=" + senderSession + " detail=" + detail);
                    if (TextUtils.isEmpty(url)) break;
                    boolean isIncognito = senderSession != null
                            && mIncognitoStateRepository.getGeckoState(senderSession) != null;
                    if (isIncognito) {
                        mIncognitoStateRepository.getWasmAllowlistRepository().postNeedsWasm(url);
                    } else {
                        mWasmAllowlistRepository.postNeedsWasm(url);
                    }
                }
            }
        }

        private void handleIconsMessage(JSONObject json) {
            Log.d(TAG, "handleIconMessage: " + json);
            try {
                String url = json.optString("url");
                JSONArray icons = json.optJSONArray("icons");

                if (icons == null)
                    return;

                JSONObject largestIcon = null;
                int maxPixels = -1;

                for (int i = 0; i < icons.length(); i++) {
                    JSONObject icon = icons.getJSONObject(i);
                    String type = icon.optString("type", "");

                    // 1. Check if type contains "icon"
                    if (type.contains("icon")) {
                        JSONArray sizes = icon.getJSONArray("sizes");
                        int currentPixels = 0;

                        if (sizes.length() > 0) {
                            // Parse "96x96" -> 96 * 96
                            String[] parts = sizes.getString(0).split("x");
                            if (parts.length == 2) {
                                currentPixels = Integer.parseInt(parts[0]) * Integer.parseInt(parts[1]);
                            }
                        } else {
                            // If sizes is empty (like your .ico example), assign a base value
                            currentPixels = 16 * 16;
                        }

                        // 2. Track the largest
                        if (currentPixels > maxPixels) {
                            maxPixels = currentPixels;
                            largestIcon = icon;
                        }
                    }
                }

                if (largestIcon != null) {
                    String bestHref = largestIcon.getString("href");
                    setIcon(url, bestHref, maxPixels);
                }
            } catch (JSONException e) {
                Log.w(TAG, "handleIconsMessage", e);
            }

        }

        private void setIcon(String originUrl, String icon, int resolution) {
            Log.d(TAG, "setIcon: " + icon + " url: " + originUrl + " resolution: " + resolution);
            if (TextUtils.isEmpty(icon) || TextUtils.isEmpty(originUrl))
                return;

            // Update in-memory state on whichever repo owns the tab
            // (the one without a match is a no-op)
            boolean isIncognito = mIncognitoStateRepository.updateIcon(icon, originUrl);

            if (!isIncognito) {
                // Only persist icons for regular tabs
                mIconsRepository.updateIcon(originUrl, icon, resolution);
                mGeckoStateDataRepository.updateIcon(icon, originUrl);
            }
        }

        private void handleUblockMessage(JSONObject json, GeckoSession session) throws JSONException {
            Log.d(TAG, "handleUblockMessage: " + json);
            // uBlock sends a 'counter' update
            if (json.has("count")) {
                String count = json.optString("count", "0");
                boolean isIncognito = session != null
                        && mIncognitoStateRepository.getGeckoState(session) != null;
                mGeckoUblockHelper.onAdsCount(count, isIncognito);
            }

            // Cumulative blocked-request count from µb.requestStats —
            // drives the Home 'trackers blocked' card.
            if (json.has("cumulativeBlocked")) {
                mGeckoUblockHelper.onCumulativeBlocked(json.optLong("cumulativeBlocked", 0));
            }

            // Per-category blocked breakdown — drives the bucketed rows
            // under the hero number in TrackersInfoSheet. firedown.js
            // bucketises by fctxt.itype (uBlock can't honestly bucket
            // by source list; see the comment in firedown.js).
            if (json.has("categoryBlocked")) {
                JSONObject buckets = json.optJSONObject("categoryBlocked");
                if (buckets != null) {
                    mGeckoUblockHelper.onCategoryBlocked(
                            buckets.optLong("scripts", 0),
                            buckets.optLong("pixels", 0),
                            buckets.optLong("frames", 0),
                            buckets.optLong("other", 0));
                }
            }

            // Top-N blocked third-party hostnames, sorted descending by
            // block count. firedown.js sends this on the same push
            // triggers as the cumulative total; the host list excludes
            // incognito tabs via firedown.js's incognitoTabIds gate.
            if (json.has("topTrackers")) {
                JSONArray list = json.optJSONArray("topTrackers");
                if (list != null) {
                    mGeckoUblockHelper.onTopTrackers(list);
                }
            }

            // uBlock sends a firewall state change
            if (json.has("firewall")) {
                JSONObject firewall = json.optJSONObject("firewall");
                if (firewall != null) {
                    mGeckoUblockHelper.onFirewallChanged(
                            firewall.optBoolean("activated"),
                            // Wired in firedown.js' updateState() → firewall.cookies.
                            // Defaults to false if the extension hasn't reported yet,
                            // matching the "disabled by default" install behaviour.
                            firewall.optBoolean("cookies", false)
                    );
                }
            }
        }

        /**
         * Logic for categorizing task urgency
         */
        private int getPriority(UrlType type) {
            if (type.usesFFmpeg()) {
                return PriorityTaskThreadPoolExecutor.PRIORITY_HIGH;
            } else if (type == UrlType.SVG || type == UrlType.IMAGE) {
                return PriorityTaskThreadPoolExecutor.PRIORITY_NORMAL;
            } else {
                return PriorityTaskThreadPoolExecutor.PRIORITY_LOW;
            }
        }

        private void handleExtractionMessage(JSONObject json) {

            // SABR download test — intercept before normal parsing
//            if (json.has("sabr") && "variants".equals(json.optString("type"))) {
//                final JSONObject sabrJson = json; // capture for lambda
//                mNetworkExecutor.execute(() -> {
//                    File sabrDir = new File(App.getAppContext().getFilesDir(), "sabr_test");
//                    SabrTester.testFromNativeMessage(sabrJson, sabrDir, NetworkModule.globalClient);
//                });
//            }

            Log.d(TAG, "handleExtractionMessage: " + json);

            // 1. Parse the JSON to our Entity
            GeckoInspectEntity entity = JsonHelper.parse(json);

            if (entity != null) {
                String url = entity.getUrl();
                String geckoType = entity.getGeckoType();

                // 2. Determine the URL Type based on the extension that sent it
                UrlType urlType = UrlParser.getUrlGeckoType(url, geckoType);

                // 3. Determine priority (High for current tab, Low for background)
                int currentTabId = getTabId();
                int priority = (entity.getTabId() == currentTabId)
                        ? getPriority(urlType)
                        : PriorityTaskThreadPoolExecutor.PRIORITY_LOW;

                // 4. Create the Task, passing the Hilt-injected repository
                GeckoInspectTask task = new GeckoInspectTask(
                        mBrowserDownloadRepository, // Passed from outer class injection
                        urlType,
                        entity
                );

                // 5. Submit to the priority executor
                mPriorityExecutor.execute(task, priority, entity.getTabId());

                Log.d(TAG, "handleExtractionMessage execute: " + json);
            }
        }

        @Override
        public void onConnect(@NonNull WebExtension.Port port) {
            String name = port.name; // This is the native app ID, e.g. "browser", "ublock"

            // The youtube-potoken port is the native bridge the content script
            // opens on robots.txt when running inside the PoTokenGenerator
            // session. Hand it off — PoTokenGenerator owns its own delegate
            // for that port and we DON'T want to put it in mPorts (which is
            // for the general-purpose extension <-> native channels).
            if (PoTokenGenerator.PORT_NAME.equals(name)) {
                mPoTokenGenerator.onPortConnected(port);
                return;
            }

            mPorts.put(name, port);
            port.setDelegate(new PortDelegate());
            // When the ublock port connects (once per extension lifecycle), push
            // the user's persisted toggle states so the extension's in-memory
            // state matches what the UI believes. uBO itself persists
            // selectedFilterLists in browser.storage.local, so this is mostly
            // defensive — but it also handles first-run after upgrade where
            // the new KEY_BLOCK_COOKIE_NOTICES key didn't exist yet.
            if ("ublock".equals(name)) {
                boolean blockCookies = mSharedPreferences.getBoolean(
                        Preferences.SETTINGS_BLOCK_COOKIE_NOTICES, Preferences.DEFAULT_BLOCK_COOKIE_NOTICES);
                if (blockCookies) {
                    // Only push if enabled; the uBO default is already "off",
                    // so pushing false on first run is redundant (and would
                    // trigger an unnecessary loadFilterLists recompile).
                    setCookies(true);
                }
            }
        }
    }

    private final class PortDelegate implements WebExtension.PortDelegate {
        @Override
        public void onPortMessage(@NonNull Object message, @NonNull WebExtension.Port port) {
            try {
                JSONObject json = (JSONObject) message;
                String type = json.optString("type", "");

                if (type.equals("fetch")) {


                    String method = json.optString("method", "GET");
                    String body = json.optString("body", null);
                    String requestId = json.getString("requestId");
                    String url = json.getString("url");
                    JSONArray headers = json.optJSONArray("headers");

                    Log.d(TAG, "onFetch: " + url);

                    mNetworkExecutor.execute(() -> {
                        try {

                            Request.Builder reqBuilder = new Request.Builder().url(url);

                            if ("POST".equals(method)) {
                                reqBuilder.post(RequestBody.create(body, MediaType.parse("application/json")));
                            }

                            if (headers != null) {
                                for (int i = 0; i < headers.length(); i++) {
                                    JSONObject h = headers.getJSONObject(i);
                                    reqBuilder.addHeader(h.getString("name"), h.getString("value"));
                                }
                            }

                            try (Response response = mOkHttpClient.newCall(reqBuilder.build()).execute()) {
                                String html = response.body().string();

                                JSONObject result = new JSONObject();
                                result.put("type", "fetchResult");
                                result.put("requestId", requestId);
                                result.put("html", html);
                                result.put("status", response.code());

                                // Return Set-Cookie headers so JS can capture session cookies
                                List<String> setCookies = response.headers("Set-Cookie");
                                if (!setCookies.isEmpty()) {
                                    JSONArray cookieArr = new JSONArray();
                                    for (String sc : setCookies) {
                                        cookieArr.put(sc);
                                    }
                                    result.put("setCookies", cookieArr);
                                }

                                port.postMessage(result);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "fetchResult", e);
                            try {
                                JSONObject error = new JSONObject();
                                error.put("type", "fetchResult");
                                error.put("requestId", requestId);
                                error.put("error", e.getMessage());
                                port.postMessage(error);
                            } catch (JSONException je) {
                                Log.e(TAG, "fetchResult error", je);
                            }
                        }
                    });
                } else if (type.equals("cookiesResult")) {
                    int sessionId = json.getInt("id");
                    String cookieHeader = json.getString("cookieHeader");
                    GeckoState geckoState = mGeckoStateDataRepository.getGeckoState(sessionId);
                    if (geckoState == null) {
                        geckoState = mIncognitoStateRepository.getGeckoState(sessionId);
                    }
                    if (geckoState != null) {
                        geckoState.setCookieHeader(cookieHeader);
                    }
                } else {
                    if (json.has("listener") && "onActivated".equals(json.optString("listener"))) {
                        mTabId = json.getInt("id");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Port Error", e);
            }
        }

        @Override
        public void onDisconnect(@NonNull WebExtension.Port port) {
            mPorts.values().remove(port);
        }
    }


    /**
     * Clears persisted permission state for a single origin. Safe to call
     * with a null or malformed URL — does nothing in that case.
     */
    public void clearPermissionsForOrigin(@Nullable String url) {
        if (url == null) return;
        String host = Uri.parse(url).getHost();
        if (host == null) return;
        getGeckoRuntime()
                .getStorageController()
                .clearDataFromHost(host, StorageController.ClearFlags.PERMISSIONS);
    }

    public void setAds(boolean enable) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("ads", enable);
            sendPortMessage("ublock", msg);
        } catch (JSONException e) {
            Log.e(TAG, "setAds error", e);
        }
    }

    public void setJavascript(boolean enable) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("javascript", enable);
            sendPortMessage("ublock", msg);
        } catch (JSONException e) {
            Log.e(TAG, "setJavascript error", e);
        }
    }

    public void setMedia(boolean enable) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("media", enable);
            sendPortMessage("ublock", msg);
        } catch (JSONException e) {
            Log.e(TAG, "setMedia error", e);
        }
    }

    public void setFonts(boolean enable) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("fonts", enable);
            sendPortMessage("ublock", msg);
        } catch (JSONException e) {
            Log.e(TAG, "setFonts error", e);
        }
    }


    /**
     * Toggles cookie-notice blocking. Unlike the per-hostname switches above
     * (javascript/media/fonts) and unlike the per-tab ads switch, this is a
     * GLOBAL filter-list selection — enabling adds fanboy-cookiemonster to
     * µb.selectedFilterLists, persists it via saveSelectedFilterLists, and
     * recompiles via loadFilterLists. The extension reloads the active tab
     * so the new cosmetic rules take effect immediately.
     */
    public void setCookies(boolean enable) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("cookies", enable);
            sendPortMessage("ublock", msg);
        } catch (JSONException e) {
            Log.e(TAG, "setCookies error", e);
        }
    }

    public void setCookieContext(String targetUrl, int id) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "getCookiesForUrl");
            msg.put("url", targetUrl);
            msg.put("id", id);
            sendPortMessage("browser", msg);
        } catch (JSONException e) {
            Log.e(TAG, "setCookieContext error", e);
        }
    }

    private void sendPortMessage(String portName, JSONObject message) {
        WebExtension.Port port = mPorts.get(portName);
        if (port != null) {
            port.postMessage(message);
        }
    }

    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    public void setWebRTC(boolean enable) {
        GeckoResult<Void> geckoResult = GeckoPreferenceController
                .setGeckoPref("media.peerconnection.enabled", enable, GeckoPreferenceController.PREF_BRANCH_USER);

        geckoResult.accept(unused -> {
            Log.d(TAG, "setWebRTC: " + unused);
        });
    }

    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    public void setJITCompiler(boolean enable) {
        List<GeckoPreferenceController.SetGeckoPreference<?>> preferenceList = new ArrayList<>();

        preferenceList.add(GeckoPreferenceController.SetGeckoPreference
                .setBoolPref("javascript.options.baselinejit", enable, GeckoPreferenceController.PREF_BRANCH_USER));

        preferenceList.add(GeckoPreferenceController.SetGeckoPreference
                .setBoolPref("javascript.options.ion", enable, GeckoPreferenceController.PREF_BRANCH_USER));

        preferenceList.add(GeckoPreferenceController.SetGeckoPreference
                .setBoolPref("javascript.options.wasm_baselinejit", enable, GeckoPreferenceController.PREF_BRANCH_USER));


        GeckoResult<Map<String, Boolean>> geckoResult = GeckoPreferenceController.setGeckoPrefs(preferenceList);

        geckoResult.accept(map -> {
            if (map == null)
                return;
            for (Map.Entry<String, Boolean> entry : map.entrySet()) {
                Log.d(TAG, "setJITCompile: " + entry.getKey() + "/" + entry.getValue());
            }
        });
    }

    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    public void setResistFingerPrinting(boolean enable) {

        GeckoResult<Void> geckoResult = GeckoPreferenceController
                .setGeckoPref("privacy.resistFingerprinting", enable, GeckoPreferenceController.PREF_BRANCH_USER);

        geckoResult.accept(unused -> {
            Log.d(TAG, "setResistFingerPrinting: " + unused + " enable: " + enable);
        });
    }

    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    public void setWebGL(boolean disable) {

        GeckoResult<Void> geckoResult = GeckoPreferenceController
                .setGeckoPref("webgl.disabled", disable, GeckoPreferenceController.PREF_BRANCH_USER);

        geckoResult.accept(unused -> {
            Log.d(TAG, "setWebGL: " + unused);
        });
    }

    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    public void setWebAssembly(boolean enable) {

        GeckoResult<Void> geckoResult = GeckoPreferenceController
                .setGeckoPref("javascript.options.wasm", enable, GeckoPreferenceController.PREF_BRANCH_USER);

        geckoResult.accept(unused -> {
            Log.d(TAG, "setWebAssembly: " + unused + " enable: " + enable);
        });
    }

    /**
     * Returns the user's saved WebAssembly preference (the "off when
     * no allowlisted site is active" baseline). The NavigationDelegate
     * uses this to know what to revert to after leaving an allowlisted
     * host.
     */
    public boolean getUserWebAssemblyPreference() {
        return mSharedPreferences.getBoolean(
                Preferences.SETTINGS_ENABLE_WEBASSEMBLY,
                Preferences.DEFAULT_ENABLE_WEBASSEMBLY);
    }

    /**
     * Resolves the WASM pref for a tab navigating to {@code url}:
     * pref ON if the host is in the regular or incognito allowlist,
     * otherwise the user's baseline. Called from NavigationDelegate
     * on every onLocationChange so the pref tracks the active tab.
     *
     * <p>Short-circuits non-http(s) URLs (about:, moz-extension:, data:,
     * file:) — WebUtils.getDomainName logs a MalformedURLException for
     * those, and the allowlist is meaningful only for web origins anyway.</p>
     */
    public boolean shouldEnableWasmFor(String url) {
        if (TextUtils.isEmpty(url)) return getUserWebAssemblyPreference();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return getUserWebAssemblyPreference();
        }
        if (mWasmAllowlistRepository.contains(url)) return true;
        if (mIncognitoStateRepository.getWasmAllowlistRepository().contains(url)) return true;
        return getUserWebAssemblyPreference();
    }

    /**
     * Flips {@code javascript.options.wasm} only when the desired state
     * differs from the current runtime state, then reloads
     * {@code session} once the pref has been applied. Used by the
     * "Enable for {host}?" snackbar — the page that just failed needs
     * a fresh load to actually pick up the new pref.
     */
    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    public void enableWasmAndReload(GeckoSession session) {
        GeckoPreferenceController
                .setGeckoPref("javascript.options.wasm", true, GeckoPreferenceController.PREF_BRANCH_USER)
                .accept(unused -> mMainExecutor.execute(() -> {
                    if (session != null) session.reload();
                }));
    }

    public WasmAllowlistRepository getWasmAllowlistRepository() {
        return mWasmAllowlistRepository;
    }


    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    public void setGeo(boolean block) {

        // const uint32_t ALLOW_ACTION = 1;
        // const uint32_t DENY_ACTION = 2;
        // const uint32_t PROMPT_ACTION = 3;

        int value = block ? 2 : 3;

        GeckoResult<Void> geckoResult = GeckoPreferenceController
                .setGeckoPref("permissions.default.geo", value, GeckoPreferenceController.PREF_BRANCH_USER);

        geckoResult.accept(unused -> {
            Log.d(TAG, "setGeo: " + unused);
        });
    }


    /**
     * Toggle DRM (Encrypted Media Extensions).
     *
     * IronFox / Mull / Tor Browser for Android stance: when the user
     * has DRM off, kill the entire EME + GMP machinery. The EME API
     * surface itself ({@code requestMediaKeySystemAccess}, supported-
     * key-system enumeration, codec / robustness probing) is a stable
     * fingerprint vector; even with no CDM behind it, "EME exists but
     * rejects everything" is a distinctive signal. Flipping
     * {@code media.eme.enabled = false} removes the API entirely so
     * the page can't probe at all.
     *
     * Trade-off: a small number of DASH/HLS players call
     * {@code requestMediaKeySystemAccess} synchronously at load and
     * abort if it throws, even when the rendition they would have
     * selected is unencrypted. Modern players (Shaka, dash.js,
     * hls.js, the Max bespoke player) catch and continue; some long-
     * tail players don't. We accept that breakage in exchange for
     * the privacy posture.
     *
     *   media.eme.enabled                = !disable
     *   media.gmp-widevinecdm.enabled    = !disable
     *   media.gmp-widevinecdm.visible    = !disable
     *   media.gmp-manager.updateEnabled  = !disable
     *   media.gmp-provider.enabled       = !disable
     *   browser.eme.ui.enabled           = !disable
     *
     * Net effect with the toggle OFF:
     *   - {@code navigator.requestMediaKeySystemAccess} is undefined
     *     / throws — page can't fingerprint EME capability.
     *   - GMP install / consent path is disabled at every layer.
     *   - No DRM playback (clear-only).
     *
     * Net effect with the toggle ON: every pref above flips back to
     * true, the EME API is callable, and the full GMP install /
     * consent flow runs. (Stock GeckoView for Android still doesn't
     * fetch the Widevine binary — that needs a build with MediaDrm-
     * backed EME compiled in — but the prefs are correct.)
     *
     * The defence-in-depth pieces still apply: the GMP-level disables
     * stop Gecko-internal pathways, and
     * PermissionDelegate.onContentPermissionRequest auto-denies
     * PERMISSION_MEDIA_KEY_SYSTEM_ACCESS when the toggle is off
     * (covers any pre-loaded page whose EME API binding survives a
     * runtime toggle flip).
     */
    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    public void setDRM(boolean disable) {

        boolean enable = !disable;

        Log.d(TAG, "setDRM: enter disable=" + disable
                + " → media.eme.enabled=" + enable
                + " widevinecdm.enabled=" + enable
                + " widevinecdm.visible=" + enable
                + " gmp-manager.updateEnabled=" + enable
                + " gmp-provider.enabled=" + enable
                + " browser.eme.ui.enabled=" + enable);

        List<GeckoPreferenceController.SetGeckoPreference<?>> preferenceList = new ArrayList<>();

        // Master EME switch — IronFox-style hard disable. Removes
        // requestMediaKeySystemAccess from the page entirely, which
        // closes the capability-probing fingerprint surface.
        preferenceList.add(GeckoPreferenceController.SetGeckoPreference
                .setBoolPref("media.eme.enabled", enable, GeckoPreferenceController.PREF_BRANCH_USER));

        preferenceList.add(GeckoPreferenceController.SetGeckoPreference
                .setBoolPref("media.gmp-widevinecdm.enabled", enable, GeckoPreferenceController.PREF_BRANCH_USER));

        // Hide the Widevine GMP from JavaScript discovery when off.
        preferenceList.add(GeckoPreferenceController.SetGeckoPreference
                .setBoolPref("media.gmp-widevinecdm.visible", enable, GeckoPreferenceController.PREF_BRANCH_USER));

        // Stop the GMP manager from polling Mozilla's plugin update server.
        preferenceList.add(GeckoPreferenceController.SetGeckoPreference
                .setBoolPref("media.gmp-manager.updateEnabled", enable, GeckoPreferenceController.PREF_BRANCH_USER));

        // Disable plugin discovery itself.
        preferenceList.add(GeckoPreferenceController.SetGeckoPreference
                .setBoolPref("media.gmp-provider.enabled", enable, GeckoPreferenceController.PREF_BRANCH_USER));

        // Suppress the Firefox-style "This site uses Widevine, install?" UI.
        preferenceList.add(GeckoPreferenceController.SetGeckoPreference
                .setBoolPref("browser.eme.ui.enabled", enable, GeckoPreferenceController.PREF_BRANCH_USER));

        GeckoResult<Map<String, Boolean>> geckoResult = GeckoPreferenceController.setGeckoPrefs(preferenceList);

        // accept(success, error) — the success-only overload swallows any
        // exception from setGeckoPrefs (runtime not ready, IPC error,
        // unknown pref name) and we'd never know DRM toggling failed.
        //
        // The Boolean in the result map is "did Gecko accept this pref
        // change". A pref returning false means the runtime refused our
        // value (e.g. locked pref, unknown name, branch mismatch) and the
        // pref retains whatever it was before — that's exactly the
        // scenario we want surfaced when video stops working.
        geckoResult.accept(map -> {
            if (map == null) {
                Log.w(TAG, "setDRM: setGeckoPrefs returned null map (runtime not ready?)");
                return;
            }
            if (map.isEmpty()) {
                Log.w(TAG, "setDRM: setGeckoPrefs returned an empty map");
                return;
            }
            for (Map.Entry<String, Boolean> entry : map.entrySet()) {
                Log.d(TAG, "setDRM: applied " + entry.getKey() + " accepted=" + entry.getValue());
            }
        }, throwable -> Log.w(TAG, "setDRM failed", throwable));
    }


    /**
     * Apply privacy-hardening Gecko prefs that don't need a UI toggle.
     * Called once from {@link #applySharedPreferences} so they're set before
     * any GeckoSession runs. Three groups:
     *
     * <ul>
     *   <li><b>A — telemetry / speculative network:</b> disables Mozilla
     *       connectivity & captive-portal probes, DNS / link / predictor
     *       prefetch, sendBeacon analytics, AMO web API; trims cross-site
     *       Referer to origin only.</li>
     *   <li><b>B — Local Network Access:</b> blocks public sites from
     *       probing the user's home router / NAS / IoT via JS.</li>
     *   <li><b>C — fingerprinting belt-and-braces:</b> disables battery /
     *       gamepad / VR / sensor / SpeechSynthesis APIs that
     *       privacy.resistFingerprinting already neutralises, so the
     *       protection survives RFP being toggled off.</li>
     * </ul>
     *
     * Mapped from IronFox's {@code templates/gecko/ironfox.cfg}; values
     * picked for "high privacy gain, near-zero site breakage". User-facing
     * toggles for HTTPS-only, disk cache, and Safe Browsing live separately
     * because each has user-visible consequences.
     */
    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    private void applyHardeningPrefs() {
        List<GeckoPreferenceController.SetGeckoPreference<?>> prefs = new ArrayList<>();

        // ── Cluster A: telemetry / speculative network ─────────────────────
        // Mozilla background probes — silent fetches the user never asked for
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "network.connectivity-service.enabled", false, GeckoPreferenceController.PREF_BRANCH_USER));
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "network.captive-portal-service.enabled", false, GeckoPreferenceController.PREF_BRANCH_USER));
        // DNS / link / predictor prefetch — speculative connections leak
        // visited sites to the resolver before the user actually clicks
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "network.dns.disablePrefetch", true, GeckoPreferenceController.PREF_BRANCH_USER));
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "network.predictor.enabled", false, GeckoPreferenceController.PREF_BRANCH_USER));
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "network.prefetch-next", false, GeckoPreferenceController.PREF_BRANCH_USER));
        // sendBeacon — fire-and-forget analytics on page leave
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "beacon.enabled", false, GeckoPreferenceController.PREF_BRANCH_USER));
        // navigator.mozAddonManager — sites probing for installed extensions
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "extensions.webapi.enabled", false, GeckoPreferenceController.PREF_BRANCH_USER));
        // Referer trimming — cross-site only when base-domains match,
        // path/query stripped on every Referer (= origin only)
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setIntPref(
                "network.http.referer.XOriginPolicy", 2, GeckoPreferenceController.PREF_BRANCH_USER));
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setIntPref(
                "network.http.referer.trimmingPolicy", 2, GeckoPreferenceController.PREF_BRANCH_USER));

        // ── Cluster B: Local Network Access blocking ───────────────────────
        // Stops public-internet sites from probing 192.168.x.x / 10.x / etc.
        // through the browser. Recent Firefox feature; IronFox enables all.
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "network.lna.enabled", true, GeckoPreferenceController.PREF_BRANCH_USER));
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "network.lna.blocking", true, GeckoPreferenceController.PREF_BRANCH_USER));
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "network.lna.block_trackers", true, GeckoPreferenceController.PREF_BRANCH_USER));

        // ── Cluster C: fingerprinting belt-and-braces ──────────────────────
        // RFP already neutralises most of these, but they're hard-disables
        // here so the protection persists if RFP is toggled off.
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "dom.battery.enabled", false, GeckoPreferenceController.PREF_BRANCH_USER));
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "dom.gamepad.enabled", false, GeckoPreferenceController.PREF_BRANCH_USER));
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "dom.vr.enabled", false, GeckoPreferenceController.PREF_BRANCH_USER));
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "device.sensors.enabled", false, GeckoPreferenceController.PREF_BRANCH_USER));
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "media.webspeech.synth.enabled", false, GeckoPreferenceController.PREF_BRANCH_USER));

        GeckoResult<Map<String, Boolean>> result = GeckoPreferenceController.setGeckoPrefs(prefs);
        result.accept(
                map -> Log.d(TAG, "applyHardeningPrefs: applied " + (map != null ? map.size() : 0) + " prefs"),
                throwable -> Log.w(TAG, "applyHardeningPrefs failed", throwable));
    }


    /**
     * HTTPS-only mode — refuse plaintext HTTP loads, show a warning page
     * with a per-site override option. Setting both regular and PBM
     * (incognito) variants so the choice applies in both browsing modes.
     */
    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    public void setHttpsOnly(boolean enable) {
        List<GeckoPreferenceController.SetGeckoPreference<?>> prefs = new ArrayList<>();
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "dom.security.https_only_mode", enable, GeckoPreferenceController.PREF_BRANCH_USER));
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "dom.security.https_only_mode_pbm", enable, GeckoPreferenceController.PREF_BRANCH_USER));
        GeckoResult<Map<String, Boolean>> result = GeckoPreferenceController.setGeckoPrefs(prefs);
        result.accept(
                map -> Log.d(TAG, "setHttpsOnly: " + enable),
                throwable -> Log.w(TAG, "setHttpsOnly failed", throwable));
    }


    /**
     * Disk-cache toggle. When disabled, Gecko keeps only an in-memory
     * cache — eliminates cross-site cache fingerprinting and leftover
     * tracking traces, at a noticeable repeat-visit perf cost.
     */
    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    public void setDiskCacheEnabled(boolean enable) {
        GeckoResult<Void> result = GeckoPreferenceController.setGeckoPref(
                "browser.cache.disk.enable", enable, GeckoPreferenceController.PREF_BRANCH_USER);
        result.accept(
                unused -> Log.d(TAG, "setDiskCacheEnabled: " + enable),
                throwable -> Log.w(TAG, "setDiskCacheEnabled failed", throwable));
    }


    /**
     * Safe Browsing — Google's URL-blocklist for malware/phishing. The
     * privacy/security tradeoff is that URL hash prefixes are sent to
     * Google for matching. LibreWolf disables, IronFox keeps on; expose
     * the choice rather than picking one for the user.
     */
    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    public void setSafeBrowsing(boolean enable) {
        List<GeckoPreferenceController.SetGeckoPreference<?>> prefs = new ArrayList<>();
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "browser.safebrowsing.malware.enabled", enable, GeckoPreferenceController.PREF_BRANCH_USER));
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "browser.safebrowsing.phishing.enabled", enable, GeckoPreferenceController.PREF_BRANCH_USER));
        GeckoResult<Map<String, Boolean>> result = GeckoPreferenceController.setGeckoPrefs(prefs);
        result.accept(
                map -> Log.d(TAG, "setSafeBrowsing: " + enable),
                throwable -> Log.w(TAG, "setSafeBrowsing failed", throwable));
    }


    public GeckoRuntime getGeckoRuntime() {
        return sGeckoRuntime;
    }

    /** Singleton native PO-token minter. Callers should invoke
     *  {@code getPoTokenGenerator().generate(videoId, visitorData)} on a
     *  background thread (the call blocks until token or timeout). */
    public PoTokenGenerator getPoTokenGenerator() {
        return mPoTokenGenerator;
    }

    public int getTabId() {
        return mTabId;
    }
}
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
    private final GeckoUblockHelper mGeckoUblockHelper;
    private final Executor mMainExecutor;
    public final BrowserSessionActionDelegate mBrowserSessionActionDelegate;
    private final MessageDelegate mMessageDelegate;
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
                .crashHandler(null)              // no custom handler
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

        mBrowserSessionActionDelegate = new BrowserSessionActionDelegate();

        setupWebExtensions();

        setWebRTC(sharedPreferences.getBoolean(Preferences.SETTINGS_ENABLE_WEBRTC, false));
        setJITCompiler(sharedPreferences.getBoolean(Preferences.SETTINGS_ENABLE_JIT, false));
        setWebGL(sharedPreferences.getBoolean(Preferences.SETTINGS_DISABLE_WEBGL, false));
        setGeo(sharedPreferences.getBoolean(Preferences.SETTINGS_BLOCK_LOCATION, false));
        setResistFingerPrinting(sharedPreferences.getBoolean(Preferences.SETTINGS_ENABLE_RESIST_FINGERPRINTING, false));
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
                    case "browser" -> handleBrowserMessage(jsonObject);
                    case "icons" -> handleIconsMessage(jsonObject);
                    case "ublock" -> handleUblockMessage(jsonObject, sender.session);
                    case "youtube", "parser" -> handleExtractionMessage(jsonObject);
                }
            } catch (JSONException e) {
                Log.e(TAG, "JSON Error", e);
            }
            return null;
        }


        private void handleBrowserMessage(JSONObject json) throws JSONException {
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

            // uBlock sends a firewall state change
            if (json.has("firewall")) {
                JSONObject firewall = json.optJSONObject("firewall");
                if (firewall != null) {
                    mGeckoUblockHelper.onFirewallChanged(
                            firewall.optBoolean("activated"),
                            firewall.optBoolean("noJavascript"),
                            firewall.optBoolean("noMedia"),
                            firewall.optBoolean("noFonts"),
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


    public GeckoRuntime getGeckoRuntime() {
        return sGeckoRuntime;
    }

    public int getTabId() {
        return mTabId;
    }
}
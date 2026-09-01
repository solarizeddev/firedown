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
import com.solarized.firedown.nostr.NostrSignerBridge;
import com.solarized.firedown.p2pshare.P2pShareController;
import com.solarized.firedown.utils.DebugLog;
import com.solarized.firedown.utils.JsonHelper;
import com.solarized.firedown.utils.UrlStringUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.ExperimentalGeckoViewApi;
import org.mozilla.geckoview.GeckoPreferenceController;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
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
import okhttp3.ResponseBody;

@Singleton
public class GeckoRuntimeHelper {
    private static final String TAG = GeckoRuntimeHelper.class.getName();

    /**
     * Ceiling on a body the extension "fetch" bridge will buffer and post
     * back over the native port. Clears YouTube's biggest legitimate payload
     * (base.js) several times over; see the bounded read in onPortMessage.
     */
    private static final long MAX_BRIDGE_BODY_BYTES = 16L * 1024 * 1024;
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
    private final NostrSignerBridge mNostrSignerBridge;
    private final P2pShareController mP2pShareController;
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
            NostrSignerBridge nostrSignerBridge,
            P2pShareController p2pShareController,
            @Qualifiers.MainThread Executor mainExecutor,
            @Qualifiers.Network Executor networkExecutor
    ) {
        this.mNostrSignerBridge = nostrSignerBridge;
        this.mP2pShareController = p2pShareController;
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
                        // Persist ETP block events to Gecko's on-device content-blocking
                        // database (pref browser.contentblocking.database.enabled, default
                        // false). Off, the DB stays empty and the query APIs
                        // (ContentBlockingController.sumAllTrackingDbEvents /
                        // getTrackingDbEventsByDateRange, unlocked by the 153 bump) return
                        // nothing — so this gate is the foundation for an honest
                        // "trackers blocked this week/month/all-time" view sourced from a
                        // persisted, cross-session store, rather than uBlock's in-memory
                        // µb.requestStats count. Enable it NOW (not when the UI lands) so the
                        // all-time total reflects real history instead of starting at zero.
                        // Stays on-device only (no telemetry) and is wiped by "Delete
                        // browsing data" (DeleteBrowsingDialogFragment → clearTrackingDb).
                        .contentBlockingDatabase(true)
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

        // Enable the Android FIDO2/Credential-Manager WebAuthn backend so passkey
        // logins surface the OS prompt (the ActivityDelegate is wired in
        // BaseActivity but Gecko only invokes it when this backend is enabled).
        applyWebAuthnPrefs();

        mMessageDelegate = new MessageDelegate();

        // PoTokenGenerator owns its own GeckoSession (created outside
        // TabDelegate to bypass the WebExtension Tabs API surface that has
        // been the source of repeated mid-mint tab deaths). It needs to
        // attach our loaded WebExtensions to the session it creates so the
        // youtube content script gets injected on robots.txt — pass
        // registerSession as a method reference so PoTokenGenerator doesn't
        // have to know about mLoadedExtensions.
        mPoTokenGenerator = new PoTokenGenerator(sGeckoRuntime, this::registerSession);

        // P2pShareController owns a hidden GeckoSession hosting the page-world
        // WebRTC engine (createOffer hangs in an extension background page).
        // It can't take the runtime via @Inject (GeckoRuntimeHelper depends on
        // the controller, so the reverse is a Hilt cycle) — hand it over here,
        // same registerSession registrar the PoTokenGenerator gets.
        mP2pShareController.attachRuntime(sGeckoRuntime, this::registerSession);

        mBrowserSessionActionDelegate = new BrowserSessionActionDelegate();

        setupWebExtensions();

        // User-facing privacy toggles — defaults pick the privacy-preferring
        // value but every one of them can be flipped from settings. Disk
        // cache and Safe Browsing follow the "Disable X" convention used
        // by WebGL (switch ON = privacy-preferring action), so invert when
        // calling the underlying setter which still takes the runtime's
        // "is this feature enabled" sense.

        // WebRTC is ALWAYS on (the toggle was removed — stock-Firefox posture;
        // mDNS obfuscation covers the local-IP leak, see Preferences note).
        setWebRTC(true);
        setWebAssembly(!sharedPreferences.getBoolean(Preferences.SETTINGS_DISABLE_WASM,
                Preferences.DEFAULT_DISABLE_WASM));
        setJITCompiler(!sharedPreferences.getBoolean(Preferences.SETTINGS_DISABLE_JIT,
                Preferences.DEFAULT_DISABLE_JIT));
        setWebGL(sharedPreferences.getBoolean(Preferences.SETTINGS_DISABLE_WEBGL,
                Preferences.DEFAULT_DISABLE_WEBGL));
        setGeo(sharedPreferences.getBoolean(Preferences.SETTINGS_BLOCK_LOCATION,
                Preferences.DEFAULT_BLOCK_LOCATION));
        setResistFingerPrinting(sharedPreferences.getBoolean(Preferences.SETTINGS_ENABLE_RESIST_FINGERPRINTING,
                Preferences.DEFAULT_RESIST_FINGERPRINTING));
        setTimezoneSpoofing(sharedPreferences.getBoolean(Preferences.SETTINGS_SPOOF_TIMEZONE,
                Preferences.DEFAULT_SPOOF_TIMEZONE));
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
        // DoH is a per-process runtime setting (TRR mode defaults to OFF on
        // every GeckoRuntime.create) — apply it at boot, or an enabled DoH
        // toggle would only take effect after the user re-opens the DoH
        // settings screen (the only other place that sets it).
        applyDoh(sharedPreferences);
        // These have no UI toggle — the privacy gain is high enough and the
        // breakage low enough that it's not a meaningful choice to expose.
        applyHardeningPrefs();
        applySelectionVisibilityPref();
    }

    /**
     * Make web-page text selection visible even when Gecko paints it in the
     * "disabled" (unfocused-document) state — the GREY #AAAAAA wash.
     *
     * <p>Why this exists (the full chain, traced through Gecko 152 source and
     * confirmed on-device with the {@code ui.textSelectDisabledBackground}
     * red-probe): a selection is painted with the accent color (ColorID::
     * Highlight = colorAccent @ ~30% alpha) ONLY while its document's frame
     * selection is SELECTION_ON, which requires the content document to have
     * DOM focus ({@code nsFrameSelection::WillFocusDocument}); a blurred or
     * never-focused document paints TextSelectDisabledBackground instead
     * (nsTextPaintStyle's default branch). On Android, window activation —
     * the precondition for that document focus — is owned by
     * {@code widget/android/nsWindow}: {@code Show(true)} unconditionally
     * BringToFront()s a newly shown Gecko window (deactivating the visible
     * tab's window and blurring its document), and {@code Destroy()} removes
     * a window from the top-level list WITHOUT re-activating the next one.
     * So any hidden GeckoSession this app opens and closes (PoToken minting,
     * the P2P share engine) leaves the visible tab's window "list-top but
     * not focus-manager-active" — a WEDGED state no app-side API can exit:
     * UserActivity() no-ops (already list-top), GeckoView.requestFocus()
     * no-ops (already view-focused), and setFocused(true) → browser.focus()
     * dies in nsFocusManager::SetFocusInner ({@code sendFocusEvent} requires
     * {@code isElementInActiveWindow}). Stock Fenix never trips this because
     * it doesn't churn hidden windows mid-session.
     *
     * <p>The ROOT fix belongs in the firedown-geckoview fork
     * (nsWindow::Destroy must re-raise the next visible top-level window,
     * mirroring the Show(false) path). Until that ships, this pref paints
     * the disabled state in the same brand wash as the active state —
     * #f0716c at 0.35 alpha, deliberately a hair off the active 78/255 so
     * nsTextPaintStyle's EnsureDifferentColors doesn't nudge it — which is
     * the correct look for a phone browser anyway: single-window UX has no
     * "unfocused pane" concept worth a distinct grey.
     */
    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    private void applySelectionVisibilityPref() {
        GeckoResult<Void> geckoResult = GeckoPreferenceController.setGeckoPref(
                "ui.textSelectDisabledBackground", "rgba(240, 113, 108, 0.35)",
                GeckoPreferenceController.PREF_BRANCH_USER);
        geckoResult.accept(
                unused -> {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "applySelectionVisibilityPref: set");
                    }
                },
                throwable -> Log.w(TAG, "applySelectionVisibilityPref failed", throwable));
    }

    private void setupWebExtensions() {
        // The former parser@ extension was merged into downloader@ and its assets
        // removed. GeckoView PERSISTS a built-in's registration across an in-place
        // app update, so simply dropping its registerBuiltIn() call below is not
        // enough — on the next boot Gecko still tries to start parser@ and fails
        // with NS_ERROR_FILE_NOT_FOUND (its manifest no longer ships). Explicitly
        // uninstall the orphan so the state is clean. No-op on a fresh install (the
        // id isn't present) and after the first successful cleanup.
        uninstallOrphanedExtension("parser@solarized.dev");

        // We use the MainExecutor for all delegate registrations to prevent threading crashes
        // The former parser@ extension has been merged into webrequests@
        // (downloader@solarized.dev): the per-site parsers + page-state bridge now
        // run in that one extension's background/content scripts. Its captures
        // still arrive over the "parser" nativeApp name, so the downloader@
        // delegate is registered under BOTH "browser" and "parser" below.
        registerBuiltIn("resource://android/assets/youtube/", "youtube@solarized.dev", "youtube");
        registerBuiltIn("resource://android/assets/webrequests/", "downloader@solarized.dev", "browser");
        registerBuiltIn("resource://android/assets/ublock/", "uBlock0@raymondhill.net", "ublock");
        registerBuiltIn("resource://android/assets/icons/", "icons@mozac.org", "icons");
        // window.nostr (NIP-07) provider — its content script sends signing
        // requests over the "nostr" nativeApp name, routed in onMessage to
        // NostrSignerBridge. The generic global + per-session delegate hookup
        // in registerBuiltIn/registerSession covers this name (no special
        // multi-name repeat needed, unlike youtube/parser).
        registerBuiltIn("resource://android/assets/nostr/", "nostr@solarized.dev", "nostr");
        // P2P share engine — a bridge content script (content.js) that binds
        // to the hidden engine GeckoSession P2pShareController opens on the
        // loopback /engine page (the page-world WebRTC engine needs a real
        // docShell; createOffer hangs in an extension background page). The
        // content script opens the native port ("p2pshare") which onConnect
        // hands to P2pShareController (the PoTokenGenerator ownership pattern);
        // file bytes ride a loopback HTTP bridge, never the messaging layer.
        registerBuiltIn("resource://android/assets/p2pshare/", "p2pshare@solarized.dev", "p2pshare");
    }

    /**
     * Remove a built-in WebExtension whose assets no longer ship (it was merged
     * into another extension). Needed because GeckoView keeps a built-in's
     * registration in the profile across an in-place update; without this, the
     * orphaned registration fails to boot every launch (missing manifest).
     * Enumerates the installed extensions and uninstalls the matching id.
     */
    private void uninstallOrphanedExtension(String id) {
        sGeckoRuntime.getWebExtensionController().list().accept(extensions -> {
            if (extensions == null) {
                return;
            }
            for (WebExtension extension : extensions) {
                if (id.equals(extension.id)) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Uninstalling orphaned extension: " + id);
                    }
                    sGeckoRuntime.getWebExtensionController().uninstall(extension);
                }
            }
        }, e -> Log.e(TAG, "Orphan-extension cleanup list() failed", e));
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
                        // The merged downloader@ extension also emits parser
                        // captures over the "parser" nativeApp name (former
                        // parser@ extension). Bind the same delegate for it so
                        // sendNativeMessage("parser", …) reaches onMessage's
                        // "parser" case. setMessageDelegate is keyed by name, so
                        // this is additive to the "browser" registration above.
                        if ("browser".equals(delegateId)) {
                            webExtension.setMessageDelegate(mMessageDelegate, "parser");
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
     *
     * <p>We refuse them. Returning a session here would hand GeckoView a
     * GeckoSession that is never tracked in the Java tab repo (findGeckoState
     * returns null for it) nor closed — a leaked, invisible content process.
     * Firedown drives everything through native Java messaging and exposes no
     * extension UI (uBlock's dashboard/logger/etc.), so nothing legitimately
     * needs this. The former use case (a hidden youtube.com/robots.txt tab for
     * BotGuard PO tokens) is obsolete: {@link PoTokenGenerator} now creates and
     * fully lifecycle-manages that session directly, without the Tabs API.
     *
     * <p>Returning {@code null} aborts the tab creation on the GeckoView side.
     * Kept (rather than not setting a delegate) so any future attempt is
     * logged instead of silently leaking.
     */
    private final WebExtension.TabDelegate mTabDelegate = new WebExtension.TabDelegate() {
        @Override
        public GeckoResult<GeckoSession> onNewTab(@NonNull WebExtension source,
                                                  @NonNull WebExtension.CreateTabDetails createDetails) {
            Log.w(TAG, "onNewTab refused from " + source.id + ": " + createDetails.url
                    + " — extension tab creation is disabled (would leak an untracked session)");
            return GeckoResult.fromValue(null);
        }
    };

    /**
     * Session-level tab delegate — handles {@code browser.tabs.update(tabId, {url})}.
     *
     * <p>uBlock's strict-block path (assets/ublock/js/traffic.js
     * {@code onBeforeRootFrameRequest}) cancels the blocked navigation and calls
     * {@code vAPI.tabs.replace} → {@code browser.tabs.update} to swap in its
     * {@code moz-extension://<uuid>/document-blocked.html} interstitial. GeckoView
     * routes that to {@link WebExtension.SessionTabDelegate#onUpdateTab} on the
     * blocked tab's OWN session. With no delegate the update is dropped and the tab
     * is left blank (the original request was already cancelled) — this is exactly
     * the "$document block shows about:blank" bug. Loading the URL here is the
     * app-renders-the-page equivalent of {@link GeckoError}'s error pages, but it
     * keeps the extension origin so the interstitial's Proceed / "don't warn again"
     * buttons can still message the uBlock background.
     *
     * <p>Unlike {@link #mTabDelegate}'s {@code onNewTab} (refused — it would leak an
     * untracked session), {@code onUpdateTab} navigates the EXISTING, Firedown-tracked
     * {@code session} argument, so it is safe to honor. Scoped to {@code moz-extension://}
     * targets so a loaded extension can only redirect a tab to one of its OWN packaged
     * pages (the interstitial), never hijack navigation to an arbitrary web URL.
     */
    private final WebExtension.SessionTabDelegate mSessionTabDelegate =
            new WebExtension.SessionTabDelegate() {
        @Override
        public GeckoResult<AllowOrDeny> onUpdateTab(@NonNull WebExtension source,
                                                    @NonNull GeckoSession session,
                                                    @NonNull WebExtension.UpdateTabDetails details) {
            String url = details.url;
            if (url != null && UrlStringUtils.isMozExtensionLike(url)) {
                session.loadUri(url);
                return GeckoResult.allow();
            }
            return GeckoResult.deny();
        }
    };


    @UiThread
    public void registerSession(GeckoSession geckoSession) {
        mMainExecutor.execute(() -> {
            for (Map.Entry<String, WebExtension> entry : mLoadedExtensions.entrySet()) {
                geckoSession.getWebExtensionController().setMessageDelegate(entry.getValue(), mMessageDelegate, entry.getKey());
                geckoSession.getWebExtensionController().setActionDelegate(entry.getValue(), mBrowserSessionActionDelegate);
                // Honor browser.tabs.update({url}) so uBlock's $document strict-block
                // can swap in its moz-extension document-blocked.html interstitial
                // (see mSessionTabDelegate). Without this the tab is left blank.
                geckoSession.getWebExtensionController().setTabDelegate(entry.getValue(), mSessionTabDelegate);
                // Mirror the global setMessageDelegate hookup for the
                // PoTokenGenerator port name so connectNative('youtube_potoken')
                // from content.js reaches Java on per-session controllers too.
                if ("youtube".equals(entry.getKey())) {
                    geckoSession.getWebExtensionController().setMessageDelegate(
                            entry.getValue(), mMessageDelegate, PoTokenGenerator.PORT_NAME);
                }
                // Per-session twin of the global "parser" delegate hookup so the
                // merged downloader@ extension's parser captures reach Java on
                // per-session controllers too (see setupWebExtensions).
                if ("browser".equals(entry.getKey())) {
                    geckoSession.getWebExtensionController().setMessageDelegate(
                            entry.getValue(), mMessageDelegate, "parser");
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
            // Control-channel: extensions query BuildConfig.DEBUG at
            // startup to gate their log() helpers. Per-extension JS
            // log() is otherwise hard-wired to a compile-time constant
            // that has to be flipped manually; routing through here
            // means release builds drop their console output without
            // a manual edit, and the per-call argument evaluation
            // (template literals, JSON.stringify) is short-circuited
            // — that's the dominant cost, not the JSAPI hop.
            if ("get-debug-flag".equals(jsonObject.optString("kind", null))) {
                // Return a plain Boolean — Gecko's EventDispatcher
                // bundle layer rejects JSONObject return values from
                // MessageDelegate.onMessage with "Invalid event data
                // for callback". Primitives serialize cleanly.
                return GeckoResult.fromValue(BuildConfig.DEBUG);
            }
            // window.nostr (NIP-07) signing requests. Returns a PENDING result
            // completed later with an envelope string once the Amber round-trip
            // (via NostrSignerActivity) finishes — the JS side turns the
            // envelope into a resolve/reject on the page's Promise.
            if ("nostr".equals(nativeApp)) {
                return mNostrSignerBridge.handle(
                        jsonObject, sender != null ? sender.url : null);
            }
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
                    // Re-prioritize the pending inspect queue for the new
                    // foreground tab (no-op if unchanged).
                    mPriorityExecutor.setCurrentTab(mTabId);
                    mBrowserDownloadRepository.postComplete();
                }
                case "onRemoved" -> {
                    int removedTabId = json.getInt("id");
                    mBrowserDownloadRepository.trimTabs(removedTabId);
                    // Drop the closed tab's queued inspect tasks so its backlog
                    // doesn't saturate the pool and starve the next tab.
                    mPriorityExecutor.cancelTab(removedTabId);
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

                // Pick the single best declared icon by a sortable score
                // (iconScore): a STANDARD favicon (rel=icon / shortcut icon)
                // always outranks an apple-touch-icon, and within a tier the
                // larger pixel area wins. Non-icon entries (og:image /
                // twitter:image — share banners, not favicons) score
                // Long.MIN_VALUE and are never selected.
                JSONObject bestIcon = null;
                long bestScore = Long.MIN_VALUE;
                for (int i = 0; i < icons.length(); i++) {
                    JSONObject icon = icons.getJSONObject(i);
                    long score = iconScore(icon);
                    if (score > bestScore) {
                        bestScore = score;
                        bestIcon = icon;
                    }
                }

                if (bestIcon != null) {
                    setIcon(url, bestIcon.getString("href"), iconPixels(bestIcon));
                    return;
                }

                // No <link> icon declared at all (a Next.js SPA like redbull.tv
                // ships none). Fall back to the browser convention:
                // <origin>/favicon.ico, resolution 0 so IconsRepository
                // HEAD-probes and estimates the real size.
                String fallback = defaultFaviconFor(url);
                if (fallback != null) {
                    setIcon(url, fallback, 0);
                }
            } catch (JSONException e) {
                Log.w(TAG, "handleIconsMessage", e);
            }

        }

        /**
         * A sortable rank for a declared icon, tier-major then size: a STANDARD
         * favicon (rel=icon / shortcut icon) always outranks an apple-touch-icon,
         * and within a tier the larger pixel area wins. Standard is preferred
         * because the apple-touch-icon is often a content-hashed, hotlink-gated
         * build asset (e.g. x.com's icon-ios.<hash>.png) that 403s a standalone
         * fetch, whereas the standard favicon (e.g. twitter.3.ico) is the stable,
         * always-hotlinkable one. A non-icon entry (og:image / twitter:image)
         * returns Long.MIN_VALUE so it is never selected.
         */
        private long iconScore(JSONObject icon) {
            String type = icon.optString("type", "");
            if (!type.contains("icon")) {
                return Long.MIN_VALUE;
            }
            long tier = type.contains("apple") ? 0L : 1L;
            // Tier weight exceeds any plausible pixel area, so tier dominates.
            return tier * (1L << 32) + iconPixels(icon);
        }

        /**
         * Pixel area from an icon's first declared size ("96x96" -> 9216), or a
         * base 16x16 when no usable size is present (a bare .ico, sizes="any",
         * or a malformed value). Hardened so a non-numeric size can't throw.
         */
        private int iconPixels(JSONObject icon) {
            JSONArray sizes = icon.optJSONArray("sizes");
            if (sizes == null || sizes.length() == 0) {
                return 16 * 16;
            }
            String[] parts = sizes.optString(0, "").split("x");
            if (parts.length != 2) {
                return 16 * 16;
            }
            try {
                int w = Integer.parseInt(parts[0].trim());
                int h = Integer.parseInt(parts[1].trim());
                return w * h;
            } catch (NumberFormatException e) {
                return 16 * 16;
            }
        }

        /**
         * The conventional <origin>/favicon.ico for a page URL, or null when the
         * URL has no http(s) origin (about:, data:, …). Browsers probe this path
         * whenever a page declares no icon link; we mirror that so SPAs that ship
         * no <link rel=icon> still get a favicon.
         */
        private String defaultFaviconFor(String pageUrl) {
            if (TextUtils.isEmpty(pageUrl)) {
                return null;
            }
            Uri uri = Uri.parse(pageUrl);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            String authority = uri.getAuthority();
            if (TextUtils.isEmpty(authority)) {
                return null;
            }
            return scheme + "://" + authority + "/favicon.ico";
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

            // Per-page blocked-host tally for the currently active tab.
            // Pushed in response to a {requestPageBlocks:true, tabId:N}
            // from Java (see requestPageBlocks below). The JS side
            // echoes back isIncognito (looked up against
            // incognitoTabIds for the resolved tabId) — we trust that
            // flag rather than sender.session because messages from
            // the extension's background script don't carry a
            // per-tab session, so session.isIncognito would always
            // resolve to false and the incognito SecuritySheet's
            // detail list would land on the regular stream.
            if (json.has("pageBlocks")) {
                JSONObject payload = json.optJSONObject("pageBlocks");
                if (payload != null) {
                    // The JS now pushes pageBlocks proactively on every block
                    // burst (piggybacked on updateToolbarIcon), which fires for
                    // whichever tab's badge changed — not necessarily the one
                    // the user is looking at. Only apply pushes for the active
                    // tab so the SecuritySheet detail list can't be overwritten
                    // by a background tab's blocks. tabId<=0 means the JS used
                    // its getCurrent() fallback (explicit requestPageBlocks),
                    // which is already active-tab-scoped, so accept those.
                    int payloadTabId = payload.optInt("tabId", 0);
                    if (payloadTabId <= 0 || payloadTabId == mTabId) {
                        JSONArray items = payload.optJSONArray("items");
                        boolean isIncognito = payload.optBoolean("isIncognito", false);
                        mGeckoUblockHelper.onPageBlocks(items, isIncognito);
                    }
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
            if (type.usesFFmpeg() || type == UrlType.SABR || type == UrlType.HLS_MASTER
                    || type == UrlType.MEGA) {
                // MEGA, like HLS_MASTER, does a network enumeration at capture
                // (the cs `f` tree listing), so it earns the high lane.
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

                // Stamp the navigation-visit id of the owning tab at capture
                // time. This is the unified anchor for the session-aware
                // Captured view — it groups by which page visit a capture
                // belongs to, independent of how the extension spelled the
                // origin URL (m./www., feed vs deep-link).
                entity.setVisitId(mGeckoStateDataRepository.visitIdForTab(entity.getTabId()));
                // VisitTrace: the stamp side of the Captured pin — pairs with
                // GeckoState.updateVisit's id-move lines and the sheet's
                // anchor dump (BrowserDownloadViewModel.filter).
                DebugLog.d("VisitTrace", "stamp tab=" + entity.getTabId()
                        + " visit=" + entity.getVisitId()
                        + " name=" + DebugLog.preview(entity.getName()));

                // 2. Determine the URL Type based on the extension that sent it
                UrlType urlType = UrlParser.getUrlGeckoType(url, geckoType);

                // 3. Create the Task, passing the Hilt-injected repository
                GeckoInspectTask task = new GeckoInspectTask(
                        mBrowserDownloadRepository, // Passed from outer class injection
                        urlType,
                        entity
                );

                // 4. Submit with the urlType-derived base priority. The executor
                // applies the foreground/background adjustment (current tab keeps
                // the base priority, others drop to LOW) and re-applies it to the
                // whole pending queue whenever the foreground tab changes
                // (setCurrentTab below).
                mPriorityExecutor.execute(task, getPriority(urlType), entity.getTabId());

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

            // The p2pshare engine port is owned by P2pShareController (same
            // handoff rationale as the potoken port above): it has its own
            // delegate and must not land in mPorts.
            if (P2pShareController.PORT_NAME.equals(name)) {
                mP2pShareController.onPortConnected(port);
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
                                /* Bounded read. body().string() is unbounded, and
                                 * this bridge fetches whatever url the extension
                                 * asks for — the whole body then lands on the Java
                                 * heap TWICE over (the String, then its copy inside
                                 * the JSONObject we post across the port), so an
                                 * oversized response costs several times its own
                                 * size. The cap clears YouTube's largest legitimate
                                 * payload (base.js, a few MB) with room to spare.
                                 *
                                 * An over-cap body reports empty html with the real
                                 * status rather than a truncated document: the JS
                                 * consumer regex-scrapes this, and half a document
                                 * would silently yield wrong matches instead of an
                                 * honest miss. nativeFetch resolves with the whole
                                 * message, so the extra `error` field is visible to
                                 * anyone debugging and ignored by everyone else. */
                                ResponseBody peeked = response.peekBody(MAX_BRIDGE_BODY_BYTES + 1);
                                boolean tooLarge = peeked.contentLength() > MAX_BRIDGE_BODY_BYTES;
                                String html = tooLarge ? "" : peeked.string();
                                if (tooLarge) {
                                    Log.w(TAG, "onFetch: body over " + MAX_BRIDGE_BODY_BYTES
                                            + " bytes — returning empty html");
                                }

                                JSONObject result = new JSONObject();
                                result.put("type", "fetchResult");
                                result.put("requestId", requestId);
                                result.put("html", html);
                                result.put("status", response.code());
                                if (tooLarge) {
                                    result.put("error", "body-too-large");
                                }

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

    /**
     * Asks firedown.js to push the per-host blocked tally for the
     * currently active tab (the tabId Java has been tracking via
     * browser.tabs.onActivated/onUpdated). Routes through the same
     * long-lived ublock port the setAds toggle uses. Caller observes
     * {@code GeckoUblockHelper.getPageBlocksLive()} (or the incognito
     * variant) for the response; the JS side replies with
     * {@code pageBlocks:{tabId, isIncognito, items:[...]}} which
     * handleUblockMessage above forwards to onPageBlocks.
     *
     * <p>The explicit tabId in the request bypasses vAPI.tabs.getCurrent()
     * in firedown.js, which queries {active:true, currentWindow:true}
     * and doesn't reliably resolve to the active incognito tab in
     * GeckoView — without this, the incognito SecuritySheet's "Ads
     * blocked" drill-down sat empty even when the badge counter said
     * a dozen things had been blocked on the page.
     */
    public void requestPageBlocks() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("requestPageBlocks", true);
            msg.put("tabId", mTabId);
            sendPortMessage("ublock", msg);
        } catch (JSONException e) {
            Log.e(TAG, "requestPageBlocks error", e);
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

    /**
     * Trigger a "Save snapshot" of the foreground page (the browser-popup
     * action). The serializer lives in the downloader@ extension's snapshot.js
     * content script — only it can read the live, post-JS page DOM — so we just
     * poke the existing "browser" native port and let requests.js relay the
     * capture request to the active tab's content script. The snapshot is then
     * delivered back through GeckoView's normal download funnel
     * (onExternalResponse → the browser download pipeline), so nothing else is
     * needed on the Java side.
     *
     * <p>{@code mTabId} is the extension's currently-active tab id (tracked from
     * the port's onActivated events); the JS side falls back to an active-tab
     * query if it's unknown.
     */
    public void captureSnapshot() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "capture-snapshot");
            msg.put("tabId", mTabId);
            sendPortMessage("browser", msg);
        } catch (JSONException e) {
            Log.e(TAG, "captureSnapshot error", e);
        }
    }

    private void sendPortMessage(String portName, JSONObject message) {
        WebExtension.Port port = mPorts.get(portName);
        if (port != null) {
            port.postMessage(message);
        }
    }

    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    public GeckoResult<Void> setWebRTC(boolean enable) {
        // Returns the GeckoResult so callers that need the pref APPLIED before
        // acting (the P2P share, which then opens its fresh engine page to pick
        // up the enabled pref) can chain on it — a fire-and-forget write races
        // the page load and the new global still sees the old value.
        return GeckoPreferenceController
                .setGeckoPref("media.peerconnection.enabled", enable, GeckoPreferenceController.PREF_BRANCH_USER);
    }

    /**
     * Session-scoped for the P2P share, like {@link #setWebRTC}: Firefox
     * obfuscates host ICE candidates as mDNS {@code <uuid>.local} hostnames
     * (a browsing-privacy feature — pages can't read the LAN IP). Resolving a
     * peer's .local candidate needs multicast DNS, which on Android generally
     * fails (receiving multicast requires a MulticastLock the app never
     * takes), so with obfuscation on a same-LAN pair finds NO working host
     * pair and ICE dies with "add a TURN server". The share flow flips this
     * off while the share screen is open (the LAN IP travels only inside the
     * QR/code the user physically hands to the peer) and restores it in
     * onDestroyView — browsing privacy is unchanged outside a share.
     */
    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    public GeckoResult<Void> setWebRtcIceObfuscation(boolean obfuscate) {
        return GeckoPreferenceController
                .setGeckoPref("media.peerconnection.ice.obfuscate_host_addresses", obfuscate,
                        GeckoPreferenceController.PREF_BRANCH_USER);
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

    /**
     * Toggle UTC timezone spoofing. FPP is already enabled (see the constructor),
     * so JSDateTimeUTC is a stock fingerprinting-protection target — we just flip
     * it on/off via the GLOBAL privacy.fingerprintingProtection.overrides pref:
     * ON => "+JSDateTimeUTC" (Date/Intl report UTC, hiding the local-timezone
     * fingerprint), OFF => "" (no global override). This is intentionally the
     * global `overrides` pref, distinct from the per-site `granularOverrides`
     * pref (which nothing else currently sets), so the two never collide. No
     * browser restart: like Resist Fingerprinting it
     * takes effect on the next page load. Default OFF — UTC clocks confuse
     * calendar/scheduling sites, so it's a user opt-in (see {@link
     * com.solarized.firedown.Preferences#SETTINGS_SPOOF_TIMEZONE}).
     */
    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    public void setTimezoneSpoofing(boolean enable) {
        final String overrides = enable ? "+JSDateTimeUTC" : "";
        GeckoResult<Void> geckoResult = GeckoPreferenceController.setGeckoPref(
                "privacy.fingerprintingProtection.overrides", overrides,
                GeckoPreferenceController.PREF_BRANCH_USER);
        geckoResult.accept(
                unused -> Log.d(TAG, "setTimezoneSpoofing: " + enable),
                throwable -> Log.w(TAG, "setTimezoneSpoofing failed", throwable));
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
     * Returns the user's WebAssembly baseline — the state the
     * NavigationDelegate reverts to when the active host isn't in the
     * allowlist. WASM is ON unless the user turned on "Disable WebAssembly",
     * in which case the per-site allowlist re-enables it as exceptions.
     */
    public boolean getUserWebAssemblyPreference() {
        return !mSharedPreferences.getBoolean(
                Preferences.SETTINGS_DISABLE_WASM,
                Preferences.DEFAULT_DISABLE_WASM);
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
        // Referer trimming — send the Referer cross-site (like stock Firefox's
        // strict-origin-when-cross-origin), but path/query stripped on every
        // Referer (= origin only) so no full URL leaks cross-site.
        //
        // XOriginPolicy was 2 ("cross-site Referer only when base-domains
        // match"), which stripped the Referer entirely on any request to a
        // different base domain. That broke sites whose media CDN lives on a
        // separate base domain behind Referer-based hotlink protection: pixiv
        // serves images from i.pximg.net (base pximg.net) while the page is
        // www.pixiv.net (base pixiv.net), so every image 403'd with no Referer.
        // 0 keeps the origin-only Referer (trimmingPolicy 2) that pixiv — and
        // any such CDN — requires, matching what desktop Firefox sends.
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setIntPref(
                "network.http.referer.XOriginPolicy", 0, GeckoPreferenceController.PREF_BRANCH_USER));
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
        // here so the protection persists if RFP is toggled off. Kept ONLY for
        // APIs with near-zero real-site value on a mobile media browser
        // (deprecated / niche): Battery, Gamepad, WebVR.
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "dom.battery.enabled", false, GeckoPreferenceController.PREF_BRANCH_USER));
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "dom.gamepad.enabled", false, GeckoPreferenceController.PREF_BRANCH_USER));
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "dom.vr.enabled", false, GeckoPreferenceController.PREF_BRANCH_USER));
        // device.sensors (DeviceOrientation/Motion) and media.webspeech.synth
        // (Text-to-Speech) are deliberately kept ENABLED: hard-disabling them
        // removed real user-visible functionality on a mobile browser —
        // 360°/panorama/tilt/AR content, and read-aloud / "listen to this
        // article" / language-learning TTS (an accessibility regression) — for
        // only a marginal fingerprint gain that FPP/RFP already cover when
        // active. Set true explicitly (not merely omitted) so they stay on
        // regardless of the IronFox-base build default.
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "device.sensors.enabled", true, GeckoPreferenceController.PREF_BRANCH_USER));
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "media.webspeech.synth.enabled", true, GeckoPreferenceController.PREF_BRANCH_USER));

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
     * Apply the DNS-over-HTTPS setting to GeckoView's Trusted Recursive
     * Resolver. Mirrors {@code DohFragment.applyDohToGecko} — kept in lockstep
     * via the shared {@link Preferences#getDohEnabled}/{@link
     * Preferences#getDohUri} helpers — so DoH is in effect from boot, not only
     * after the DoH settings screen is visited. {@code TRR_MODE_FIRST} tries
     * DoH and falls back to the system resolver, matching {@code DohDns}.
     */
    public void applyDoh(SharedPreferences sharedPreferences) {
        boolean enabled = Preferences.getDohEnabled(sharedPreferences);
        GeckoRuntimeSettings settings = sGeckoRuntime.getSettings();
        settings.setTrustedRecursiveResolverMode(enabled
                ? GeckoRuntimeSettings.TRR_MODE_FIRST
                : GeckoRuntimeSettings.TRR_MODE_OFF);
        if (enabled) {
            String uri = Preferences.getDohUri(sharedPreferences);
            if (uri != null && !uri.isEmpty()) {
                settings.setTrustedRecursiveResolverUri(uri);
            }
        }
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

    /**
     * Enable the WebAuthn / passkey backend. GeckoView routes
     * {@code navigator.credentials.create/get} to Android's FIDO2 / Credential
     * Manager and surfaces the OS passkey UI through the runtime
     * {@code ActivityDelegate} (wired in {@code BaseActivity}). But the Android
     * FIDO2 backend is gated by an embedder pref that does NOT default on in a
     * bare GeckoView embedding (Fenix sets it). Without it Gecko never invokes
     * the delegate, so the password-manager / passkey sheet never appears — which
     * is why passkey login silently does nothing here while it works in other
     * browsers. Enable the master switch + the Android FIDO2 backend so the
     * delegate fires.
     */
    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    private void applyWebAuthnPrefs() {
        List<GeckoPreferenceController.SetGeckoPreference<?>> prefs = new ArrayList<>();
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "security.webauth.webauthn", true, GeckoPreferenceController.PREF_BRANCH_USER));
        prefs.add(GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                "security.webauth.webauthn_enable_android_fido2", true, GeckoPreferenceController.PREF_BRANCH_USER));
        GeckoPreferenceController.setGeckoPrefs(prefs).accept(
                map -> Log.d(TAG, "applyWebAuthnPrefs: set"),
                throwable -> Log.w(TAG, "applyWebAuthnPrefs failed", throwable));
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
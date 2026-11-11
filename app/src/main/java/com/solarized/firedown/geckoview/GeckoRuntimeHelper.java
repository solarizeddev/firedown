package com.solarized.firedown.geckoview;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.addons.AddonManager;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.GeckoInspectEntity;
import com.solarized.firedown.data.repository.BrowserDownloadRepository;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.IncognitoStateRepository;
import com.solarized.firedown.data.repository.IconsRepository;
import com.solarized.firedown.manager.UrlParser;
import com.solarized.firedown.manager.UrlType;
import com.solarized.firedown.p2pshare.P2pShareController;
import com.solarized.firedown.utils.DebugLog;
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
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

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
     * Debug builds only: the device audit (SiteAuditTest) sees every capture
     * message as it arrives, before the repository's dedup drops a later copy
     * of a URL with its name. Called on the thread the message arrived on;
     * an implementation copies what it needs and returns.
     */
    public interface CaptureTap {
        void onCapture(@NonNull JSONObject message);
    }

    private static volatile CaptureTap sCaptureTap;

    public static void setCaptureTap(@Nullable CaptureTap tap) {
        if (BuildConfig.DEBUG) {
            sCaptureTap = tap;
        }
    }

    /**
     * Ceiling on a body the extension "fetch" bridge will buffer and post
     * back over the native port. Clears YouTube's biggest legitimate payload
     * (base.js) several times over; see the bounded read in onPortMessage.
     */
    private static final long MAX_BRIDGE_BODY_BYTES = 16L * 1024 * 1024;

    /**
     * Gecko's network log modules for {@link BuildConfig#DIAGNOSTIC}: HTTP,
     * the socket thread, DNS, the document channel, the IO service's online
     * state and Android's network-change feed. rotate:64 keeps each process's
     * log to 64 MB in four files.
     */
    private static final String GECKO_NET_LOG_MODULES =
            "timestamp,rotate:64,nsHttp:4,nsSocketTransport:4,nsHostResolver:4,"
                    + "DocumentChannel:4,nsIOService:4,nsAndroidNetworkLinkService:4";
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
    private final MessageDelegate mMessageDelegate;
    private final PoTokenGenerator mPoTokenGenerator;
    private final SharedPreferences mSharedPreferences;
    private final PriorityTaskThreadPoolExecutor mPriorityExecutor;
    private final Executor mNetworkExecutor;
    private final OkHttpClient mOkHttpClient;
    private final P2pShareController mP2pShareController;
    private final AddonManager mAddonManager;
    private final Map<String, WebExtension.Port> mPorts = new HashMap<>();
    private int mTabId = DEFAULT_TAB_ID;

    /** Wait before the automatic restart of the extension process. */
    private static final long EXTENSIONS_RESTART_DELAY_MS = 10_000L;
    /** Gecko giving up again within this of an automatic restart asks the user. */
    private static final long EXTENSIONS_AUTO_RESTART_GAP_MS = 10 * 60_000L;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<Boolean> mExtensionsStopped = new MutableLiveData<>(false);
    /** elapsedRealtime of the last automatic restart, 0 for none. */
    private long mLastExtensionsAutoRestart;

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
            P2pShareController p2pShareController,
            AddonManager addonManager,
            @Qualifiers.MainThread Executor mainExecutor,
            @Qualifiers.Network Executor networkExecutor
    ) {
        this.mP2pShareController = p2pShareController;
        this.mAddonManager = addonManager;
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

        if (BuildConfig.DIAGNOSTIC) {
            Bundle netLogExtras = geckoNetLogExtras(context);
            if (netLogExtras != null) {
                runtimeSettingsBuilder.extras(netLogExtras);
            }
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
                        // persisted, cross-session store. Enable it NOW (not when the UI lands) so the
                        // all-time total reflects real history instead of starting at zero.
                        // Stays on-device only (no telemetry) and is wiped by "Delete
                        // browsing data" (DeleteBrowsingDialogFragment → clearTrackingDb).
                        .contentBlockingDatabase(true)
                        // Firefox turns the social-tracker blocker on in Strict
                        // only. It is a separate Gecko feature from the per-tab
                        // tracker blocking (blocksTrackingContent): it ignores
                        // the tab's useTrackingProtection and blocks every
                        // third-party load on the Facebook/LinkedIn/Twitter
                        // lists while this pref is on (embedded posts
                        // included).
                        .strictSocialTrackingProtection(
                                Preferences.getEnhancedTrackingProtectionLevel(sharedPreferences)
                                        == ContentBlocking.EtpLevel.STRICT)
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
                // Web content's prefers-color-scheme follows the APP theme, not
                // only the OS (see Preferences.getPreferredColorScheme).
                .preferredColorScheme(Preferences.getPreferredColorScheme(sharedPreferences))
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


        setupWebExtensions();
        watchExtensionProcess();
        // Add-ons the user installs (Settings → Add-ons): their own prompt,
        // add-on manager, action and tab delegates. Built-in extensions above
        // stay ours; the manager never lists or touches them.
        mAddonManager.attach(sGeckoRuntime, this::blocksTrackingContent);

        // User-facing privacy toggles — defaults pick the privacy-preferring
        // value but every one of them can be flipped from settings. Disk
        // cache and Safe Browsing follow the "Disable X" convention used
        // by WebGL (switch ON = privacy-preferring action), so invert when
        // calling the underlying setter which still takes the runtime's
        // "is this feature enabled" sense.

        // WebRTC is ALWAYS on (the toggle was removed — stock-Firefox posture;
        // mDNS obfuscation covers the local-IP leak, see Preferences note).
        setWebRTC(true);
        // A share switches obfuscation off for its ICE-gathering seconds and
        // back on after (P2pShareController.setObfuscation). The pref is a
        // saved user pref, so a process death inside that window would leave
        // browsing without it; no share runs at boot, so put it back.
        restoreWebRtcObfuscation();
        setWebAssembly(!sharedPreferences.getBoolean(Preferences.SETTINGS_DISABLE_WASM,
                Preferences.DEFAULT_DISABLE_WASM));
        setJITCompiler(!sharedPreferences.getBoolean(Preferences.SETTINGS_DISABLE_JIT,
                Preferences.DEFAULT_DISABLE_JIT));
        setWebGL(sharedPreferences.getBoolean(Preferences.SETTINGS_DISABLE_WEBGL,
                Preferences.DEFAULT_DISABLE_WEBGL));
        setLocationPrompts(sharedPreferences.getBoolean(Preferences.SETTINGS_LOCATION_ASK,
                Preferences.DEFAULT_LOCATION_ASK));
        setResistFingerPrinting(sharedPreferences.getBoolean(Preferences.SETTINGS_ENABLE_RESIST_FINGERPRINTING,
                Preferences.DEFAULT_RESIST_FINGERPRINTING));
        setTimezoneSpoofing(sharedPreferences.getBoolean(Preferences.SETTINGS_SPOOF_TIMEZONE,
                Preferences.DEFAULT_SPOOF_TIMEZONE));
        boolean drmEnabledPref = Preferences.getDRMEnabled(sharedPreferences);
        Log.d(TAG, "init: SETTINGS_ENABLE_DRM resolved to " + drmEnabledPref
                + " → setDRM(disable=" + (!drmEnabledPref) + ")");
        setDRM(!drmEnabledPref);
        setTranslationsEnabled(sharedPreferences.getBoolean(
                Preferences.SETTINGS_TRANSLATIONS_ENABLED, Preferences.DEFAULT_TRANSLATIONS_ENABLED));
        setTranslationsOffer(sharedPreferences.getBoolean(
                Preferences.SETTINGS_TRANSLATIONS_OFFER, Preferences.DEFAULT_TRANSLATIONS_OFFER));
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

    /**
     * Turns on Gecko's network log for a diagnostic build only
     * ({@code -Pfiredown.diagnostic=true}, false in every published build:
     * the log holds every URL). GeckoView makes each "envN" extra an
     * environment variable before Gecko starts, the same path its YAML debug
     * config feeds, without the SnakeYAML reader that R8 breaks in release
     * builds (it crashed Firedown on start). The log goes to a file in the
     * app's external files dir, which adb can pull; %PID gives every start
     * its own file, because with rotate Gecko deletes the file it is about to
     * write, and a stall is usually followed by a force-stop. Returns null
     * when that dir is unavailable.
     */
    @Nullable
    private static Bundle geckoNetLogExtras(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            return null;
        }
        File dir = new File(base, "geckolog");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        Bundle extras = new Bundle();
        extras.putString("env0", "MOZ_LOG=" + GECKO_NET_LOG_MODULES);
        extras.putString("env1", "MOZ_LOG_FILE=" + new File(dir, "net-%PID").getPath());
        return extras;
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
        // The NIP-07 window.nostr signer bridge (assets/nostr/, the Amber
        // round-trip) was REMOVED with the Nostr Wallet Connect integration
        // (maintainer call). Same trap as parser@: GeckoView persists the
        // built-in registration across in-place updates, so the dropped
        // registerBuiltIn alone leaves it failing NS_ERROR_FILE_NOT_FOUND
        // every boot until explicitly uninstalled.
        uninstallOrphanedExtension("nostr@solarized.dev");

        // We use the MainExecutor for all delegate registrations to prevent threading crashes
        // The former parser@ extension has been merged into webrequests@
        // (downloader@solarized.dev): the per-site parsers + page-state bridge now
        // run in that one extension's background/content scripts. Its captures
        // still arrive over the "parser" nativeApp name, so the downloader@
        // delegate is registered under BOTH "browser" and "parser" below.
        registerBuiltIn("resource://android/assets/youtube/", "youtube@solarized.dev", "youtube");
        registerBuiltIn("resource://android/assets/webrequests/", "downloader@solarized.dev", "browser");
        registerBuiltIn("resource://android/assets/icons/", "icons@mozac.org", "icons");
        // Cookie banners: DuckDuckGo's autoconsent answers consent pop-ups
        // with reject. Its switch arrives over the "consent" port (onConnect).
        registerBuiltIn("resource://android/assets/consent/", "consent@solarized.dev", "consent");
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
     * Every capture and the ad blocker run in ONE extension process, and
     * Gecko stops restarting it after it crashes several times in a short
     * window. Nothing turned it back on, so capture and ad blocking stopped
     * silently for the rest of the session. The first time it is restarted
     * after a pause; if Gecko gives up again within
     * {@link #EXTENSIONS_AUTO_RESTART_GAP_MS} of that, the browser says so
     * and offers a restart ({@link #getExtensionsStopped}), so a process
     * that crashes on every start can't loop.
     */
    private void watchExtensionProcess() {
        sGeckoRuntime.getWebExtensionController().setExtensionProcessDelegate(
                new WebExtensionController.ExtensionProcessDelegate() {
                    @Override
                    public void onDisabledProcessSpawning() {
                        onExtensionProcessDisabled();
                    }
                });
    }

    @UiThread
    private void onExtensionProcessDisabled() {
        long now = SystemClock.elapsedRealtime();
        boolean restartedRecently = mLastExtensionsAutoRestart != 0
                && now - mLastExtensionsAutoRestart < EXTENSIONS_AUTO_RESTART_GAP_MS;
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "extension process spawning disabled, restartedRecently=" + restartedRecently);
        }
        if (restartedRecently) {
            mExtensionsStopped.setValue(true);
            return;
        }
        mLastExtensionsAutoRestart = now;
        mMainHandler.postDelayed(this::restartExtensions, EXTENSIONS_RESTART_DELAY_MS);
    }

    /** Let Gecko start the extension process again (the snackbar's Restart). */
    @UiThread
    public void restartExtensions() {
        mExtensionsStopped.setValue(false);
        sGeckoRuntime.getWebExtensionController().enableExtensionProcessSpawning();
    }

    /** True while the extension process is off and waiting for the user. */
    public LiveData<Boolean> getExtensionsStopped() {
        return mExtensionsStopped;
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
     * The built-ins drive everything through native Java messaging and have
     * no UI, so nothing legitimately needs this (user add-ons get
     * AddonManager's tab delegate, which makes Firedown tabs). The former use case (a hidden youtube.com/robots.txt tab for
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

    @UiThread
    public void registerSession(GeckoSession geckoSession) {
        mMainExecutor.execute(() -> {
            for (Map.Entry<String, WebExtension> entry : mLoadedExtensions.entrySet()) {
                geckoSession.getWebExtensionController().setMessageDelegate(entry.getValue(), mMessageDelegate, entry.getKey());
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
            mAddonManager.registerSession(geckoSession);
        });
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
            if ("youtube".equals(nativeApp)
                    && "player-potoken".equals(jsonObject.optString("kind", null))) {
                mPoTokenGenerator.adoptPlayerToken(jsonObject.optString("videoId", ""),
                        jsonObject.optString("token", ""));
                return null;
            }
            Log.d(TAG, "onMessage: " + jsonObject);
            try {
                switch (nativeApp) {
                    case "browser" -> handleBrowserMessage(jsonObject, sender.session);
                    // Only the TOP document names a tab's icon. icons.js runs in
                    // every frame, and a frame without an icon link falls back
                    // to <origin>/favicon.ico: an ad iframe's message used to
                    // cost a HEAD to the ad origin per page load, and would now
                    // fetch and store its icon too.
                    case "icons" -> {
                        if (sender.isTopLevel()) {
                            handleIconsMessage(jsonObject, sender.session);
                        }
                    }
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
                case "blockedStats" -> handleBlockedStats(json);
            }
        }

        /**
         * Requests a blocker refused (webrequests/js/blocked-stats.js):
         * lifetime deltas, and the tally of the page on screen. A page
         * tally for a tab that is no longer the one on screen is dropped.
         */
        private void handleBlockedStats(JSONObject json) {
            JSONObject totals = json.optJSONObject("totals");
            if (totals != null) {
                mGeckoUblockHelper.onBlockedTotals(totals);
            }
            JSONObject page = json.optJSONObject("page");
            if (page != null && page.optInt("tabId", -1) == mTabId) {
                mGeckoUblockHelper.onPageBlocks(page.optLong("count", 0),
                        page.optJSONArray("items"), page.optBoolean("incognito", true));
            }
        }

        private void handleIconsMessage(JSONObject json, @Nullable GeckoSession session) {
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
                    setIcon(url, bestIcon.getString("href"), session);
                    return;
                }

                // No <link> icon declared at all (some SPAs ship none). Fall
                // back to the browser convention:
                // <origin>/favicon.ico.
                String fallback = defaultFaviconFor(url);
                if (fallback != null) {
                    setIcon(url, fallback, session);
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

        private void setIcon(String originUrl, String icon, @Nullable GeckoSession session) {
            Log.d(TAG, "setIcon: " + icon + " url: " + originUrl);
            if (TextUtils.isEmpty(icon) || TextUtils.isEmpty(originUrl))
                return;

            // Update in-memory state on whichever repo owns the tab
            // (the one without a match is a no-op)
            boolean isIncognito = mIncognitoStateRepository.updateIcon(icon, originUrl);

            if (!isIncognito) {
                // Only persist icons for regular tabs. The favicon STORE (icon
                // bytes on disk) is gated on the SENDING SESSION being a regular
                // tab, not on the url match above: a private tab whose SPA url
                // moved matches no tab by url, and its icon file would be an
                // on-disk record of a private visit. The hidden PoToken / P2P
                // sessions are no tab at all and store nothing either.
                boolean regularTab = session != null
                        && mGeckoStateDataRepository.getGeckoState(session) != null;
                mIconsRepository.updateIcon(originUrl, icon, regularTab);
                mGeckoStateDataRepository.updateIcon(icon, originUrl);
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

            CaptureTap tap = sCaptureTap;
            if (BuildConfig.DEBUG && tap != null) {
                tap.onCapture(json);
            }

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
                // Pre-commit stamp: a capture that arrives while a document
                // load is still in flight (document-filter parsers emit
                // before the commit) carries that load's sequence so the
                // commit can re-stamp it with the id it should have had —
                // see GeckoState.mLoadSeq.
                entity.setPendingLoadSeq(mGeckoStateDataRepository.pendingLoadSeqForTab(entity.getTabId()));
                // VisitTrace: the stamp side of the Captured pin — pairs with
                // GeckoState.updateVisit's id-move lines and the sheet's
                // anchor dump (BrowserDownloadViewModel.filter).
                DebugLog.d("VisitTrace", "stamp tab=" + entity.getTabId()
                        + " visit=" + entity.getVisitId()
                        + " pendingLoad=" + entity.getPendingLoadSeq()
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
            String name = port.name; // the native app ID, e.g. "browser"

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
            if ("consent".equals(name)) {
                setRejectCookieBanners(mSharedPreferences.getBoolean(
                        Preferences.SETTINGS_REJECT_COOKIE_BANNERS, Preferences.DEFAULT_REJECT_COOKIE_BANNERS));
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

    /**
     * The answers stored for the page at {@code url} that the shield sheet's
     * "Site permissions" list shows ({@link SitePermissions}), delivered on
     * the calling thread's looper. An error, or a URL Gecko cannot read,
     * gives an empty list: the row stays hidden. Main thread only.
     */
    public void loadSitePermissions(@NonNull String url, boolean privateMode,
            @NonNull Consumer<SitePermissions<ContentPermission>> done) {
        GeckoResult<List<ContentPermission>> result;
        try {
            result = getGeckoRuntime().getStorageController().getPermissions(url, privateMode);
        } catch (RuntimeException e) {
            done.accept(SitePermissions.of(null));
            return;
        }
        result.accept(perms -> {
            List<SitePermissions.Stored<ContentPermission>> stored = new ArrayList<>();
            if (perms != null) {
                for (ContentPermission perm : perms) {
                    stored.add(new SitePermissions.Stored<>(perm.permission, perm.value,
                            perm.thirdPartyOrigin, perm));
                }
            }
            done.accept(SitePermissions.of(stored));
        }, error -> {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "loadSitePermissions", error);
            }
            done.accept(SitePermissions.of(null));
        });
    }

    /**
     * Forgets these stored answers for the page at {@code url} (the site
     * asks again next time), then runs {@code done} on the calling looper.
     *
     * <p>{@code setPermission(VALUE_PROMPT)} forgets location, persistent
     * storage and the cookie access a user allowed ({@code
     * 3rdPartyFrameStorage^}). It cannot forget cookie access Gecko granted
     * by itself ({@code 3rdPartyStorage^}, its login-popup heuristics):
     * GeckoView reports that as storage access too, and writes the frame
     * key. So when cookie access survives the reset, the site's permissions
     * are cleared ({@code clearDataFromHost}) and the answers the user did
     * not reset are written back. Regular tabs only: in a private tab
     * clearing the host would also clear the regular tab's answers.
     */
    public void resetSitePermissions(@NonNull String url, boolean privateMode,
            @NonNull List<ContentPermission> perms, @NonNull Runnable done) {
        StorageController storage = getGeckoRuntime().getStorageController();
        List<String> cookieHosts = new ArrayList<>();
        for (ContentPermission perm : perms) {
            storage.setPermission(perm, ContentPermission.VALUE_PROMPT);
            if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS) {
                cookieHosts.add(SitePermissions.hostOf(perm.thirdPartyOrigin));
            }
        }
        String pageHost = Uri.parse(url).getHost();
        if (cookieHosts.isEmpty() || privateMode || pageHost == null) {
            done.run();
            return;
        }
        loadSitePermissions(url, false, after -> {
            boolean survived = false;
            List<ContentPermission> keep = new ArrayList<>();
            for (SitePermissions.Row<ContentPermission> row : after.rows) {
                if (row.type == SitePermissions.TYPE_STORAGE_ACCESS && cookieHosts.contains(row.thirdPartyHost)) {
                    survived = true;
                } else {
                    keep.addAll(row.handles);
                }
            }
            if (!survived) {
                done.run();
                return;
            }
            storage.clearDataFromHost(pageHost, StorageController.ClearFlags.PERMISSIONS).accept(unused -> {
                for (ContentPermission perm : keep) {
                    storage.setPermission(perm, perm.value);
                }
                done.run();
            }, error -> {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "resetSitePermissions", error);
                }
                done.run();
            });
        });
    }

    /**
     * Settings → Privacy → "Reject cookie banners", to the consent
     * extension: frames that load from now on are answered (or not).
     */
    public void setRejectCookieBanners(boolean enabled) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "config");
            msg.put("enabled", enabled);
            sendPortMessage("consent", msg);
        } catch (JSONException e) {
            Log.e(TAG, "setRejectCookieBanners error", e);
        }
    }

    public void setCookieContext(String targetUrl, int id, boolean incognito) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "getCookiesForUrl");
            msg.put("url", targetUrl);
            msg.put("id", id);
            // The jar to read: cookies.js maps this to firefox-private /
            // firefox-default. Without it the extension read the DEFAULT jar,
            // so a long-press download from a private tab carried the regular
            // session's cookies.
            msg.put("incognito", incognito);
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
    private void restoreWebRtcObfuscation() {
        GeckoPreferenceController.setGeckoPref("media.peerconnection.ice.obfuscate_host_addresses",
                true, GeckoPreferenceController.PREF_BRANCH_USER);
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
     * Re-derives the web content color scheme from the theme pref and
     * pushes it to the live runtime. Called by {@code ThemeFragment} after
     * every theme pick (a sub-screen applies its own prefs — the settings
     * listener is unregistered while it is foreground) and on configuration
     * changes; open sessions pick the change up without a reload, so a
     * page that honours {@code prefers-color-scheme} flips in place.
     */
    public void applyPreferredColorScheme() {
        if (sGeckoRuntime == null) return;
        int scheme = Preferences.getPreferredColorScheme(mSharedPreferences);
        sGeckoRuntime.getSettings().setPreferredColorScheme(scheme);
        Log.d(TAG, "applyPreferredColorScheme: " + scheme);
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

    /**
     * Master switch for Gecko's built-in translator
     * ({@code browser.translations.enable}). Off, Gecko runs no per-page
     * language detection and never contacts Remote Settings for models;
     * the popup row and the settings screen then report translation as
     * unavailable. Read lazily by Gecko's TranslationsParent, so a runtime
     * flip applies from the next page load — no restart.
     */
    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    public void setTranslationsEnabled(boolean enable) {

        GeckoResult<Void> geckoResult = GeckoPreferenceController
                .setGeckoPref("browser.translations.enable", enable, GeckoPreferenceController.PREF_BRANCH_USER);

        geckoResult.accept(unused -> {
            Log.d(TAG, "setTranslationsEnabled: " + enable);
        });
    }

    /**
     * Whether Gecko fires {@code onOfferTranslate} (→ the offer snackbar)
     * for a page in a language the user doesn't read. GeckoView's own
     * runtime setting (pref {@code browser.translations.automaticallyPopup}).
     */
    public void setTranslationsOffer(boolean enable) {
        sGeckoRuntime.getSettings().setTranslationsOfferPopup(enable);
        Log.d(TAG, "setTranslationsOffer: " + enable);
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
     * Settings → Privacy → "Let sites ask for your location"
     * ({@link Preferences#SETTINGS_LOCATION_ASK}, off by default).
     *
     * <p>Off: Gecko's default for location is DENY, so a site with no
     * answer of its own is refused without asking anyone, and every stored
     * location answer is forgotten. That last part is what makes off mean
     * off: a site's stored ALLOW outranks the default, and Gecko would hand
     * it the location without asking the app again. On: the default is
     * PROMPT, so a site asks and {@code GeckoComponents} shows the dialog.
     *
     * <p>Written to the user branch at every boot: a saved user value
     * outlives the build that wrote it (the removed "Block location
     * requests" switch left PROMPT there).
     */
    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    public void setLocationPrompts(boolean ask) {
        // Gecko's permission actions: 1 allow, 2 deny, 3 prompt.
        GeckoPreferenceController.setGeckoPref("permissions.default.geo", ask ? 3 : 2,
                GeckoPreferenceController.PREF_BRANCH_USER);
        // The forget runs once per switch-off (and once on the first boot
        // of a build with the switch), not at every boot: getAllPermissions
        // serializes every stored principal.
        boolean forgotten = mSharedPreferences.getBoolean(Preferences.LOCATION_ANSWERS_FORGOTTEN, false);
        if (ask) {
            if (forgotten) {
                mSharedPreferences.edit().putBoolean(Preferences.LOCATION_ANSWERS_FORGOTTEN, false).apply();
            }
        } else if (!forgotten) {
            forgetLocationAnswers();
            mSharedPreferences.edit().putBoolean(Preferences.LOCATION_ANSWERS_FORGOTTEN, true).apply();
        }
    }

    /** Every site's stored location answer, forgotten (set back to PROMPT). */
    private void forgetLocationAnswers() {
        StorageController storage = sGeckoRuntime.getStorageController();
        storage.getAllPermissions().accept(perms -> {
            if (perms == null) {
                return;
            }
            for (GeckoSession.PermissionDelegate.ContentPermission perm : perms) {
                if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION
                        && perm.value != GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT) {
                    storage.setPermission(perm,
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT);
                }
            }
        }, error -> {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "forgetLocationAnswers", error);
            }
        });
    }


    /**
     * Toggle DRM (Encrypted Media Extensions), the IronFox / Mull / Tor
     * stance: with the switch off the whole EME + GMP machinery is off.
     *
     * <p>{@code media.eme.enabled = false} does NOT remove the API:
     * {@code navigator.requestMediaKeySystemAccess} always exists, and Gecko
     * refuses every Widevine request with the same "EME has been preffed off"
     * before checking which configurations the device supports. That uniform
     * refusal is the privacy point: a page learns "DRM is off" and nothing
     * about the phone's DRM. ClearKey is not proprietary and still works.
     *
     * <p>The refusal happens before GeckoView asks the app, so stock
     * GeckoView never tells the app a page wanted DRM. firedown-geckoview
     * patch 0010 adds a notice for it (an app-approval request whose answer
     * is ignored); {@code GeckoComponents.PermissionDelegate} turns it into
     * the "This video needs DRM" sheet.
     *
     *   media.eme.enabled                = !disable
     *   media.gmp-widevinecdm.enabled    = !disable
     *   media.gmp-widevinecdm.visible    = !disable
     *   media.gmp-manager.updateEnabled  = !disable
     *   media.gmp-provider.enabled       = !disable
     *   browser.eme.ui.enabled           = !disable
     *
     * <p>With the switch on, Android plays Widevine through MediaDrm
     * ({@code media.mediadrm-widevinecdm.visible}, on in GeckoView); the GMP
     * prefs only stop Gecko-internal plugin pathways. Each page's request is
     * then granted by the app with no dialog: the switch is the consent.
     *
     * @return completes once Gecko has the new values (also on failure), so a
     *         caller can reload a page that should see them.
     */
    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    public GeckoResult<Void> setDRM(boolean disable) {

        boolean enable = !disable;

        Log.d(TAG, "setDRM: enter disable=" + disable
                + " → media.eme.enabled=" + enable
                + " widevinecdm.enabled=" + enable
                + " widevinecdm.visible=" + enable
                + " gmp-manager.updateEnabled=" + enable
                + " gmp-provider.enabled=" + enable
                + " browser.eme.ui.enabled=" + enable);

        List<GeckoPreferenceController.SetGeckoPreference<?>> preferenceList = new ArrayList<>();

        // Master EME switch. Off, Gecko refuses every request before probing
        // the device, which closes the capability-probing fingerprint
        // surface (the API itself stays on the page).
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
        return geckoResult.accept(map -> {
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
        // separate base domain behind Referer-based hotlink protection: every
        // image 403'd with no Referer. 0 keeps the origin-only Referer
        // (trimmingPolicy 2) such a CDN requires, matching desktop Firefox.
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
     * Whether a tab blocks tracker LOADS ({@code useTrackingProtection}),
     * Firefox's rule: private tabs always, regular tabs only in Strict. In
     * Standard, Gecko still marks trackers and blocks their cross-site
     * cookies but lets them load; the ad blocker add-on blocks the ads and
     * analytics.
     * Every tab used to block, which made Standard Firefox's Strict: the
     * social-tracker list covers twitter.com, so embedded tweets (and other
     * social embeds) never rendered, with no per-site way to allow them
     * (an add-on's site switch doesn't touch Gecko's lists). Read from the runtime,
     * which is what Gecko enforces.
     */
    public boolean blocksTrackingContent(boolean incognito) {
        if (incognito) {
            return true;
        }
        if (sGeckoRuntime == null) {
            return false;
        }
        return sGeckoRuntime.getSettings().getContentBlocking().getEnhancedTrackingProtectionLevel()
                == ContentBlocking.EtpLevel.STRICT;
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
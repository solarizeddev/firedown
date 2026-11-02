package com.solarized.firedown.addons;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.IncognitoStateRepository;
import com.solarized.firedown.geckoview.GeckoComponents;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.phone.BrowserMainActivity;
import com.solarized.firedown.phone.SettingsActivity;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;
import org.mozilla.geckoview.WebResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * Add-ons the user installs: Firefox extensions from the official Android
 * list ({@link AmoCatalog}) or a signed .xpi file. Built-in extensions (the
 * capture extension, uBlock, YouTube…) are never listed or touched here;
 * {@code GeckoRuntimeHelper} owns them.
 *
 * <p>Modelled on Firefox for Android (android-components' GeckoEngine,
 * WebExtensionSupport and AddonManager): the runtime's prompt and add-on
 * manager delegates, an action and a tab delegate on every add-on, and per
 * tab the action and tab delegates GeckoView keys by session. Every method
 * runs on the main thread, as GeckoView's are.
 *
 * <p>An add-on's button never goes on the toolbar: its browser or page
 * action is listed in the ⋮ sheet's Add-ons page ({@link #actionsFor}), and
 * its popup opens in a sheet ({@link #getPopup}).
 */
@Singleton
public class AddonManager {

    private static final String TAG = "AddonManager";

    /** Biggest add-on file accepted from a link or a file; the largest real ones are tens of MB. */
    static final long MAX_XPI_BYTES = 100L * 1024 * 1024;
    private static final String XPI_DIR = "addons-xpi";
    private static final String PREF_LAST_UPDATE_CHECK = "com.solarized.firedown.addons.update.last";
    private static final String PREF_UPDATE_REVIEW = "com.solarized.firedown.addons.update.review";
    private static final long UPDATE_INTERVAL_MS = TimeUnit.DAYS.toMillis(1);
    /** While the app runs, whether a check is due is looked at this often (uptime: a sleeping phone is not woken). */
    private static final long UPDATE_POLL_MS = TimeUnit.HOURS.toMillis(1);
    /** Let the browser finish starting before the daily update check. */
    private static final long UPDATE_DELAY_MS = TimeUnit.MINUTES.toMillis(1);
    /** A site whose add-on the user declined is not asked about again for this long. */
    private static final long DECLINED_HOST_QUIET_MS = TimeUnit.MINUTES.toMillis(5);
    /** More questions than this waiting at once: the rest are answered "no". */
    private static final int MAX_QUEUED_PROMPTS = 4;
    /** The official uBlock Origin, signed by Mozilla, shipped in the APK (updated from addons.mozilla.org). */
    static final String BUNDLED_UBLOCK_ASSET = "addons/ublock-origin.xpi";
    /**
     * The user removed uBlock Origin: Firedown does not put it back by
     * itself. In {@code backup_local} (excluded from Auto Backup): add-ons
     * live in the Gecko profile, which is not backed up, so on a restored
     * phone the blocker the user chose instead is not there either.
     */
    private static final String PREF_UBLOCK_REMOVED = "com.solarized.firedown.addons.ublock.removed";
    private static final String LOCAL_PREFS = "backup_local";
    /** Add-ons the user put behind the shield, or took out of it, against what their permissions say. */
    private static final String PREF_SHIELD_SHOW = "com.solarized.firedown.addons.shield.show";
    private static final String PREF_SHIELD_HIDE = "com.solarized.firedown.addons.shield.hide";
    /** Quiet time after the last change before Gecko's list is read back as the truth. */
    private static final long REFRESH_DELAY_MS = 300;

    /** What GeckoRuntimeHelper lends: it owns the runtime and can't be injected here (cycle). */
    public interface Host {
        boolean blocksTrackingContent(boolean incognito);
    }

    /** A permission question waiting for the user. */
    public static final class Prompt {
        public static final int INSTALL = 1;
        public static final int UPDATE = 2;
        public static final int OPTIONAL = 3;
        static final String TECHNICAL_DATA = "technicalAndInteraction";

        public final int seq;
        public final int type;
        @NonNull public final AddonInfo addon;
        @NonNull public final List<String> permissions;
        @NonNull public final List<String> origins;
        @NonNull public final List<String> dataCollection;
        /** The host or "a file" for an add-on from outside the official list; null from the list. */
        @Nullable public final String source;
        /** The file's manifest doesn't declare Firefox for Android. */
        public final boolean notMadeForAndroid;
        /** The add-on may share technical and interaction data if the user opts in. */
        public final boolean offersTechnicalData;
        @Nullable final String pageHost;
        @Nullable final GeckoResult<WebExtension.PermissionPromptResponse> installResult;
        @Nullable final GeckoResult<AllowOrDeny> allowResult;

        Prompt(int seq, int type, @NonNull AddonInfo addon, @NonNull List<String> permissions,
               @NonNull List<String> origins, @NonNull List<String> dataCollection,
               @Nullable String source, boolean notMadeForAndroid, @Nullable String pageHost,
               @Nullable GeckoResult<WebExtension.PermissionPromptResponse> installResult,
               @Nullable GeckoResult<AllowOrDeny> allowResult) {
            this.seq = seq;
            this.type = type;
            this.addon = addon;
            this.permissions = permissions;
            this.origins = origins;
            this.dataCollection = dataCollection;
            this.source = source;
            this.notMadeForAndroid = notMadeForAndroid;
            this.offersTechnicalData = type == INSTALL
                    && addon.optionalDataCollection.contains(TECHNICAL_DATA);
            this.pageHost = pageHost;
            this.installResult = installResult;
            this.allowResult = allowResult;
        }
    }

    /** Something the user should hear about once (a snackbar or a dialog). */
    public static final class Event {
        public static final int INSTALLED = 1;
        public static final int FAILED = 2;
        public static final int BUILT_IN = 3;
        public static final int NOT_AN_ADDON = 4;
        public static final int UNSIGNED = 5;
        public static final int TOO_LARGE = 6;
        public static final int NETWORK = 7;
        public static final int BLOCKED = 8;
        public static final int INCOMPATIBLE = 9;
        public static final int UPDATED = 10;
        public static final int NO_UPDATE = 11;
        public static final int REMOVED = 12;
        /** Downloaded or picked, but damaged: a broken signature or a wrong hash. */
        public static final int CORRUPT = 13;

        public final int type;
        @Nullable public final String name;
        private boolean mTaken;

        Event(int type, @Nullable String name) {
            this.type = type;
            this.name = name;
        }

        /** True the first time only, so a rotation or a second screen doesn't repeat it. */
        public boolean take() {
            if (mTaken) {
                return false;
            }
            mTaken = true;
            return true;
        }
    }

    /** An add-on's popup, open in a sheet. */
    public static final class Popup {
        /** A popup the browser could not show this soon after it was asked for is dropped. */
        private static final long SHOW_WINDOW_MS = 3000;

        @NonNull public final String addonId;
        @NonNull public final String name;
        @NonNull public final GeckoSession session;
        private final long mCreatedAt = SystemClock.elapsedRealtime();

        Popup(@NonNull String addonId, @NonNull String name, @NonNull GeckoSession session) {
            this.addonId = addonId;
            this.name = name;
            this.session = session;
        }

        /**
         * False once the moment has passed: a popup an add-on opened while
         * the browser was not on screen must not appear when the user comes
         * back later.
         */
        public boolean isFresh() {
            return SystemClock.elapsedRealtime() - mCreatedAt <= SHOW_WINDOW_MS;
        }
    }

    /** One row of the Add-ons page: an add-on and its button for this tab. */
    public static final class ActionItem {
        @NonNull public final AddonInfo addon;
        /** The browser action, else the page action; null when the add-on has neither. */
        @Nullable public final WebExtension.Action action;

        ActionItem(@NonNull AddonInfo addon, @Nullable WebExtension.Action action) {
            this.addon = addon;
            this.action = action;
        }
    }

    private static final class InstallContext {
        @Nullable final String source;
        final boolean notMadeForAndroid;
        /** The page's host, for an .xpi linked from a page; null otherwise. */
        @Nullable final String pageHost;
        /** The copy of uBlock Origin in the app: no question, private tabs allowed. */
        final boolean bundled;

        InstallContext(@Nullable String source, boolean notMadeForAndroid) {
            this(source, notMadeForAndroid, null, false);
        }

        InstallContext(@Nullable String source, boolean notMadeForAndroid, @Nullable String pageHost) {
            this(source, notMadeForAndroid, pageHost, false);
        }

        InstallContext(@Nullable String source, boolean notMadeForAndroid, @Nullable String pageHost,
                       boolean bundled) {
            this.source = source;
            this.notMadeForAndroid = notMadeForAndroid;
            this.pageHost = pageHost;
            this.bundled = bundled;
        }
    }

    /** A row behind the shield: a blocker and what it does in one tab. */
    public static final class BlockerItem {
        /** It runs here and filters the page. */
        public static final int ON = 0;
        /** It runs here and the user turned it off for this site. */
        public static final int OFF_FOR_SITE = 1;
        /** A private tab, and the add-on is not allowed in private tabs. */
        public static final int NOT_IN_PRIVATE = 2;

        @NonNull public final AddonInfo addon;
        @Nullable public final WebExtension.Action action;
        public final int state;

        BlockerItem(@NonNull AddonInfo addon, @Nullable WebExtension.Action action, int state) {
            this.addon = addon;
            this.action = action;
            this.state = state;
        }

        public boolean isUblock() {
            return AddonPolicy.isUblockId(addon.id);
        }
    }

    /** The browser and page action an add-on defined, globally and per tab. */
    private static final class ActionState {
        @Nullable WebExtension.Action browserDefault;
        @Nullable WebExtension.Action pageDefault;
        final Map<GeckoSession, WebExtension.Action> browserOverrides = new WeakHashMap<>();
        final Map<GeckoSession, WebExtension.Action> pageOverrides = new WeakHashMap<>();
    }

    private final Context mContext;
    private final SharedPreferences mPrefs;
    /** Install-local facts (see {@link #PREF_UBLOCK_REMOVED}). */
    private final SharedPreferences mLocalPrefs;
    private final GeckoStateDataRepository mRegularRepository;
    private final IncognitoStateRepository mIncognitoRepository;
    private final Provider<GeckoComponents> mComponents;
    private final Executor mHeavyExecutor;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    @Nullable private GeckoRuntime mRuntime;
    @Nullable private Host mHost;

    private final Map<String, WebExtension> mInstalled = new LinkedHashMap<>();
    private final Map<String, ActionState> mActions = new HashMap<>();
    private final Set<GeckoSession> mSessions = Collections.newSetFromMap(new WeakHashMap<>());
    /** Sessions showing an add-on's own pages (its popup, its options page) → the add-on's id. */
    private final Map<GeckoSession, String> mPageSessions = new WeakHashMap<>();
    private final ArrayDeque<Prompt> mPromptQueue = new ArrayDeque<>();
    private final Map<String, InstallContext> mInstallContexts = new HashMap<>();
    @Nullable private InstallContext mLastFileContext;
    private final Set<String> mInteractiveUpdates = new HashSet<>();
    private final Set<String> mRefusedBuiltIn = new HashSet<>();
    /** Installs that say nothing when they land (the first run's uBlock Origin). */
    private final Set<String> mQuietInstalls = new HashSet<>();
    private int mPendingInstalls;
    /** An .xpi from a page is being staged or is waiting for its answer (one at a time). */
    private boolean mPageInstallInFlight;
    /** Host → when the user declined an add-on from it; its links are ignored for a while. */
    private final Map<String, Long> mDeclinedHosts = new HashMap<>();
    private boolean mUpdateTicking;
    /** An update review asked for (the notification's tap) before the runtime was there. */
    @Nullable private String mPendingReview;
    private int mPromptSeq;
    private boolean mBrowsingPrivate;
    private boolean mLoaded;

    private final MutableLiveData<List<AddonInfo>> mAddons = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<Boolean> mLoadedLiveData = new MutableLiveData<>(false);
    private final MutableLiveData<Integer> mActionsVersion = new MutableLiveData<>(0);
    private final MutableLiveData<Prompt> mPrompt = new MutableLiveData<>(null);
    private final MutableLiveData<Event> mEvent = new MutableLiveData<>(null);
    private final MutableLiveData<Popup> mPopup = new MutableLiveData<>(null);
    private final MutableLiveData<Integer> mInstallsInFlight = new MutableLiveData<>(0);
    private final MutableLiveData<Set<String>> mInstalling = new MutableLiveData<>(Collections.emptySet());

    @Inject
    public AddonManager(@ApplicationContext Context context,
                        SharedPreferences sharedPreferences,
                        GeckoStateDataRepository regularRepository,
                        IncognitoStateRepository incognitoRepository,
                        Provider<GeckoComponents> components,
                        @Qualifiers.HeavyIO Executor heavyExecutor) {
        mContext = context;
        mPrefs = sharedPreferences;
        mLocalPrefs = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
        mRegularRepository = regularRepository;
        mIncognitoRepository = incognitoRepository;
        mComponents = components;
        mHeavyExecutor = heavyExecutor;
    }

    // ── Wiring ──────────────────────────────────────────────────────────

    /**
     * Called once by GeckoRuntimeHelper after the built-in extensions are
     * registered: installs the runtime-wide delegates and adopts the add-ons
     * a previous run installed.
     */
    public void attach(@NonNull GeckoRuntime runtime, @NonNull Host host) {
        mRuntime = runtime;
        mHost = host;
        runOnMain(() -> {
            WebExtensionController controller = runtime.getWebExtensionController();
            controller.setPromptDelegate(mPromptDelegate);
            controller.setAddonManagerDelegate(mAddonManagerDelegate);
            controller.list().accept(list -> {
                WebExtension legacyUblock = null;
                if (list != null) {
                    for (WebExtension extension : list) {
                        if (extension.isBuiltIn && AddonPolicy.isUblockId(extension.id)) {
                            // The patched copy Firedown used to register as a
                            // built-in; its files no longer ship.
                            legacyUblock = extension;
                        } else if (isUserAddon(extension)) {
                            adopt(extension);
                        }
                    }
                }
                mLoaded = true;
                mLoadedLiveData.setValue(true);
                publish();
                scheduleUpdateCheck();
                ensureUblock(controller, legacyUblock);
                if (mPendingReview != null) {
                    String id = mPendingReview;
                    mPendingReview = null;
                    reviewUpdate(id);
                }
            }, e -> {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "list() failed", e);
                }
                mLoaded = true;
                mLoadedLiveData.setValue(true);
            });
        });
    }

    /**
     * Every tab session passes through here (GeckoComponents.connectSession
     * → GeckoRuntimeHelper.registerSession): each add-on gets its per-tab
     * action and tab delegates on it, now and when installed later.
     */
    @UiThread
    public void registerSession(@NonNull GeckoSession session) {
        mSessions.add(session);
        for (WebExtension extension : mInstalled.values()) {
            registerOnSession(extension, session);
        }
    }

    /** The browser says which mode is on screen: new tabs and popups follow it. */
    @UiThread
    public void setBrowsingPrivate(boolean incognito) {
        mBrowsingPrivate = incognito;
    }

    private static boolean isUserAddon(@Nullable WebExtension extension) {
        return extension != null && !extension.isBuiltIn && !AddonPolicy.isBuiltInId(extension.id);
    }

    private void adopt(@NonNull WebExtension extension) {
        mInstalled.put(extension.id, extension);
        extension.setActionDelegate(mActionDelegate);
        extension.setTabDelegate(mTabDelegate);
        for (GeckoSession session : new ArrayList<>(mSessions)) {
            registerOnSession(extension, session);
        }
    }

    private void registerOnSession(@NonNull WebExtension extension, @NonNull GeckoSession session) {
        WebExtension.SessionController controller = session.getWebExtensionController();
        controller.setActionDelegate(extension, mActionDelegate);
        controller.setTabDelegate(extension, mSessionTabDelegate);
    }

    private void publish() {
        List<AddonInfo> list = new ArrayList<>();
        for (WebExtension extension : mInstalled.values()) {
            list.add(AddonInfo.from(extension));
        }
        Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
        mAddons.setValue(Collections.unmodifiableList(list));
        mActionsVersion.setValue(nextVersion());
    }

    private int nextVersion() {
        Integer v = mActionsVersion.getValue();
        return v == null ? 1 : v + 1;
    }

    // ── What the screens read ───────────────────────────────────────────

    @NonNull
    public LiveData<List<AddonInfo>> getAddons() {
        return mAddons;
    }

    /** In {@link #getInstalling()}: a file the user picked (Install from file). */
    public static final String INSTALLING_FILE = "file:";

    /**
     * What is being installed now, for the screen's spinners: the ids of
     * add-ons from Mozilla's list, and {@link #INSTALLING_FILE} for a picked
     * file. Set on the tap, cleared when Gecko is done (installed, declined
     * or failed).
     */
    @NonNull
    public LiveData<Set<String>> getInstalling() {
        return mInstalling;
    }

    private void markInstalling(@NonNull String key, boolean on) {
        Set<String> current = mInstalling.getValue();
        Set<String> next = new HashSet<>(current == null ? Collections.emptySet() : current);
        boolean changed = on ? next.add(key) : next.remove(key);
        if (changed) {
            mInstalling.setValue(Collections.unmodifiableSet(next));
        }
    }

    /** False until the add-ons a previous run installed have been listed. */
    @NonNull
    public LiveData<Boolean> getLoaded() {
        return mLoadedLiveData;
    }

    /** Bumped whenever an add-on's button changes (badge, title, enabled). */
    @NonNull
    public LiveData<Integer> getActionsVersion() {
        return mActionsVersion;
    }

    @NonNull
    public LiveData<Prompt> getPrompt() {
        return mPrompt;
    }

    @NonNull
    public LiveData<Event> getEvents() {
        return mEvent;
    }

    @NonNull
    public LiveData<Popup> getPopup() {
        return mPopup;
    }

    /** Installs started here and not finished (a progress row while it downloads). */
    @NonNull
    public LiveData<Integer> getInstallsInFlight() {
        return mInstallsInFlight;
    }

    @Nullable
    public AddonInfo find(@Nullable String id) {
        if (id == null) {
            return null;
        }
        WebExtension extension = mInstalled.get(id);
        return extension == null ? null : AddonInfo.from(extension);
    }

    public boolean isInstalled(@Nullable String id) {
        return id != null && mInstalled.containsKey(id);
    }

    /** Whether the browser's ⋮ sheet shows the Add-ons row for a tab in this mode. */
    public boolean hasAddonsFor(boolean incognito) {
        for (WebExtension extension : mInstalled.values()) {
            WebExtension.MetaData m = extension.metaData;
            if (m.enabled && (!incognito || m.allowedInPrivateBrowsing)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The Add-ons page for a tab: every enabled add-on usable in its mode,
     * with its button as this tab sees it (the tab's override over the
     * add-on's default, Firefox's {@code withDefault}).
     */
    @NonNull
    public List<ActionItem> actionsFor(@Nullable GeckoSession session, boolean incognito) {
        List<ActionItem> items = new ArrayList<>();
        List<AddonInfo> addons = mAddons.getValue();
        if (addons == null) {
            return items;
        }
        for (AddonInfo info : addons) {
            if (!info.enabled) {
                continue;
            }
            if (incognito && !info.allowedInPrivateBrowsing) {
                continue;
            }
            items.add(new ActionItem(info, actionFor(info.id, session)));
        }
        return items;
    }

    @Nullable
    private static WebExtension.Action merged(@Nullable WebExtension.Action fallback,
                                              @Nullable WebExtension.Action override) {
        if (override == null) {
            return fallback;
        }
        if (fallback == null) {
            return override;
        }
        return override.withDefault(fallback);
    }

    public boolean isUpdateWaitingForReview(@NonNull String id) {
        return mPrefs.getStringSet(PREF_UPDATE_REVIEW, Collections.emptySet()).contains(id);
    }

    // ── uBlock Origin ───────────────────────────────────────────────────

    /**
     * Firedown installs the official uBlock Origin, the copy in the APK, on
     * the first run (no question asked: it is the browser's ad blocker, as
     * the patched built-in was), and replaces the patched built-in of older
     * versions with it. It is an ordinary add-on from then on: updated from
     * addons.mozilla.org, turned off or removed by the user, and never put
     * back by Firedown once removed (the shield sheet's "Turn on" and
     * Settings → Add-ons do that on the user's tap).
     */
    private void ensureUblock(@NonNull WebExtensionController controller, @Nullable WebExtension legacy) {
        if (legacy != null) {
            // Same id: the built-in goes first. Its site exceptions are not
            // carried over (the user sets them again in uBlock's popup).
            controller.uninstall(legacy).accept(unused -> installBundledUblock(false), e -> {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "legacy uBlock uninstall failed", e);
                }
                installBundledUblock(false);
            });
            return;
        }
        if (findUblock() == null && !mLocalPrefs.getBoolean(PREF_UBLOCK_REMOVED, false)) {
            installBundledUblock(false);
        }
    }

    @Nullable
    private WebExtension findUblock() {
        for (WebExtension extension : mInstalled.values()) {
            if (AddonPolicy.isUblockId(extension.id)) {
                return extension;
            }
        }
        return null;
    }

    @Nullable
    public AddonInfo findUblockInfo() {
        WebExtension extension = findUblock();
        return extension == null ? null : AddonInfo.from(extension);
    }

    /**
     * Installs the copy of uBlock Origin in the app: no download, no
     * question, allowed in private tabs. {@code announce}: the user asked
     * (the "Added" message and failures are shown); the first run's install
     * is quiet.
     */
    @UiThread
    public void installBundledUblock(boolean announce) {
        if (mRuntime == null || findUblock() != null || isInstalling(AddonPolicy.UBLOCK_ID)) {
            return;
        }
        markInstalling(AddonPolicy.UBLOCK_ID, true);
        mHeavyExecutor.execute(() -> {
            File staged = null;
            try {
                staged = copyBundledUblock();
            } catch (IOException e) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "bundled uBlock copy failed", e);
                }
            }
            File file = staged;
            post(() -> {
                if (file == null) {
                    markInstalling(AddonPolicy.UBLOCK_ID, false);
                    if (announce) {
                        emit(Event.FAILED, null);
                    }
                    return;
                }
                mInstallContexts.put(AddonPolicy.UBLOCK_ID, new InstallContext(null, false, null, true));
                if (announce) {
                    mQuietInstalls.remove(AddonPolicy.UBLOCK_ID);
                } else {
                    mQuietInstalls.add(AddonPolicy.UBLOCK_ID);
                }
                install(Uri.fromFile(file).toString(), WebExtensionController.INSTALLATION_METHOD_FROM_FILE,
                        file, AddonPolicy.UBLOCK_ID);
            });
        });
    }

    /** Heavy executor: the asset to a file Gecko can install from. */
    @NonNull
    private File copyBundledUblock() throws IOException {
        File dir = new File(mContext.getCacheDir(), XPI_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("no cache dir");
        }
        File file = new File(dir, UUID.randomUUID() + ".xpi");
        boolean complete = false;
        try (InputStream in = mContext.getAssets().open(BUNDLED_UBLOCK_ASSET);
             FileOutputStream out = new FileOutputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            while (true) {
                int read = in.read(buf);
                if (read < 0) {
                    break;
                }
                out.write(buf, 0, read);
            }
            complete = true;
        } finally {
            if (!complete) {
                file.delete();
            }
        }
        return file;
    }

    /**
     * "Turn on" behind the shield: uBlock Origin running in this tab's mode
     * again — reinstalled from the app's copy, turned back on, and allowed
     * in private tabs for a private tab, whichever it needs.
     */
    @UiThread
    public void turnOnUblock(boolean incognito) {
        WebExtension extension = findUblock();
        if (extension == null) {
            installBundledUblock(true);
            return;
        }
        String id = extension.id;
        boolean needsPrivate = incognito && !extension.metaData.allowedInPrivateBrowsing;
        if (!extension.metaData.enabled) {
            GeckoRuntime runtime = mRuntime;
            if (runtime == null) {
                return;
            }
            runtime.getWebExtensionController().enable(extension, WebExtensionController.EnableSource.USER)
                    .accept(enabled -> {
                        onChanged(enabled);
                        // One change at a time: allowing private tabs reloads the add-on.
                        if (needsPrivate) {
                            setAllowedInPrivate(id, true);
                        }
                    }, e -> {
                        if (BuildConfig.DEBUG) {
                            Log.w(TAG, "uBlock enable failed", e);
                        }
                        publish();
                        emit(Event.FAILED, AddonInfo.from(extension).name);
                    });
        } else if (needsPrivate) {
            setAllowedInPrivate(id, true);
        }
    }

    // ── Blockers behind the shield ──────────────────────────────────────

    /**
     * The add-ons that block requests and run as this tab sees them: every
     * enabled blocker, uBlock Origin first. In a private tab a blocker not
     * allowed there is listed as {@link BlockerItem#NOT_IN_PRIVATE}.
     * {@code pageUrl}: uBlock reads "off" for pages it does not filter
     * (about:, the home), which is not a site the user turned it off for.
     */
    @NonNull
    public List<BlockerItem> blockersFor(@Nullable GeckoSession session, boolean incognito,
                                         @Nullable String pageUrl) {
        List<BlockerItem> ublock = new ArrayList<>(1);
        List<BlockerItem> others = new ArrayList<>();
        List<AddonInfo> addons = mAddons.getValue();
        if (addons == null) {
            return others;
        }
        boolean webPage = pageUrl != null && (pageUrl.startsWith("http://") || pageUrl.startsWith("https://"));
        for (AddonInfo info : addons) {
            if (!info.enabled || !isBlocker(info)) {
                continue;
            }
            WebExtension.Action action = actionFor(info.id, session);
            int state;
            if (incognito && !info.allowedInPrivateBrowsing) {
                state = BlockerItem.NOT_IN_PRIVATE;
            } else if (webPage && AddonPolicy.saysOffForSite(info.id, action == null ? null : action.title)) {
                state = BlockerItem.OFF_FOR_SITE;
            } else {
                state = BlockerItem.ON;
            }
            BlockerItem item = new BlockerItem(info, action, state);
            if (AddonPolicy.isUblockId(info.id)) {
                ublock.add(item);
            } else {
                others.add(item);
            }
        }
        ublock.addAll(others);
        return ublock;
    }

    /** The shield for a tab: one of {@link AddonPolicy}'s SHIELD_ states. */
    public int shieldState(@Nullable GeckoSession session, boolean incognito, @Nullable String pageUrl) {
        List<Boolean> running = new ArrayList<>();
        for (BlockerItem item : blockersFor(session, incognito, pageUrl)) {
            if (item.state != BlockerItem.NOT_IN_PRIVATE) {
                running.add(item.state == BlockerItem.OFF_FOR_SITE);
            }
        }
        return AddonPolicy.shieldState(running);
    }

    /** Whether the add-on gets a row behind the shield. */
    public boolean isBlocker(@NonNull AddonInfo info) {
        List<String> permissions = new ArrayList<>(info.requiredPermissions);
        permissions.addAll(info.grantedOptionalPermissions);
        List<String> origins = new ArrayList<>(info.requiredOrigins);
        origins.addAll(info.grantedOptionalOrigins);
        return AddonPolicy.isBlocker(info.id, hasButton(info.id),
                AddonPolicy.blocksRequests(permissions, origins), shieldOverride(info.id));
    }

    /** Whether the add-on page's "Show behind the shield" switch applies (uBlock is always there). */
    public boolean canChooseShield(@NonNull AddonInfo info) {
        return !AddonPolicy.isUblockId(info.id) && hasButton(info.id);
    }

    /** The add-on page's "Show behind the shield": stored only where it differs from what the permissions say. */
    @UiThread
    public void setShowBehindShield(@NonNull String id, boolean show) {
        AddonInfo info = find(id);
        if (info == null) {
            return;
        }
        List<String> permissions = new ArrayList<>(info.requiredPermissions);
        permissions.addAll(info.grantedOptionalPermissions);
        List<String> origins = new ArrayList<>(info.requiredOrigins);
        origins.addAll(info.grantedOptionalOrigins);
        boolean detected = AddonPolicy.blocksRequests(permissions, origins);
        Set<String> showIds = new HashSet<>(mPrefs.getStringSet(PREF_SHIELD_SHOW, Collections.emptySet()));
        Set<String> hideIds = new HashSet<>(mPrefs.getStringSet(PREF_SHIELD_HIDE, Collections.emptySet()));
        showIds.remove(id);
        hideIds.remove(id);
        if (show != detected) {
            (show ? showIds : hideIds).add(id);
        }
        mPrefs.edit().putStringSet(PREF_SHIELD_SHOW, showIds).putStringSet(PREF_SHIELD_HIDE, hideIds).apply();
        mActionsVersion.setValue(nextVersion());
    }

    @Nullable
    private Boolean shieldOverride(@NonNull String id) {
        if (mPrefs.getStringSet(PREF_SHIELD_SHOW, Collections.emptySet()).contains(id)) {
            return Boolean.TRUE;
        }
        if (mPrefs.getStringSet(PREF_SHIELD_HIDE, Collections.emptySet()).contains(id)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private boolean hasButton(@NonNull String id) {
        ActionState state = mActions.get(id);
        return state != null && (state.browserDefault != null || state.pageDefault != null);
    }

    /** The add-on's button as this tab sees it (its browser action, else its page action). */
    @Nullable
    private WebExtension.Action actionFor(@NonNull String id, @Nullable GeckoSession session) {
        ActionState state = mActions.get(id);
        if (state == null) {
            return null;
        }
        WebExtension.Action action = merged(state.browserDefault,
                session == null ? null : state.browserOverrides.get(session));
        if (action == null) {
            action = merged(state.pageDefault, session == null ? null : state.pageOverrides.get(session));
        }
        return action;
    }

    // ── Installing ──────────────────────────────────────────────────────

    /** From the official list. */
    @UiThread
    public void installFromCatalog(@NonNull AmoCatalog.Entry entry) {
        if (AddonPolicy.isBuiltInId(entry.id)) {
            emit(Event.BUILT_IN, entry.name);
            return;
        }
        if (isInstalling(entry.id)) {
            return;
        }
        mInstallContexts.put(entry.id, new InstallContext(null, false));
        markInstalling(entry.id, true);
        install(entry.downloadUrl, WebExtensionController.INSTALLATION_METHOD_MANAGER, null, entry.id);
    }

    private boolean isInstalling(@NonNull String key) {
        Set<String> current = mInstalling.getValue();
        return current != null && current.contains(key);
    }

    /** A file the user picked (Settings → Add-ons → Install from file). */
    @UiThread
    public void installFromUri(@NonNull Uri uri) {
        if (isInstalling(INSTALLING_FILE)) {
            return;
        }
        markInstalling(INSTALLING_FILE, true);
        mHeavyExecutor.execute(() -> {
            boolean handedOver = false;
            try (InputStream in = mContext.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    post(() -> emit(Event.NOT_AN_ADDON, null));
                } else {
                    handedOver = stageAndInstall(in, null);
                }
            } catch (IOException | SecurityException e) {
                post(() -> emit(Event.NOT_AN_ADDON, null));
            } finally {
                if (!handedOver) {
                    post(() -> markInstalling(INSTALLING_FILE, false));
                }
            }
        });
    }

    /**
     * A page linked to an .xpi (GeckoComponents.onExternalResponse): the
     * response is read here instead of being saved to Downloads.
     */
    @UiThread
    public void installFromResponse(@NonNull WebResponse response, @Nullable String host) {
        String pageHost = TextUtils.isEmpty(host) ? null : host;
        if (!mayInstallFromPage(pageHost)) {
            // A page navigating to .xpi links over and over: one question at
            // a time, and a declined site is quiet for a while.
            closeQuietly(response.body);
            return;
        }
        mPageInstallInFlight = true;
        mHeavyExecutor.execute(() -> {
            boolean handedOver = false;
            InputStream body = response.body;
            try {
                if (body == null) {
                    post(() -> emit(Event.NOT_AN_ADDON, null));
                } else {
                    try (InputStream in = body) {
                        handedOver = stageAndInstall(in, pageHost);
                    }
                }
            } catch (IOException e) {
                post(() -> emit(Event.NETWORK, null));
            } finally {
                if (!handedOver) {
                    post(() -> mPageInstallInFlight = false);
                }
            }
        });
    }

    private boolean mayInstallFromPage(@Nullable String host) {
        if (mPageInstallInFlight) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        mDeclinedHosts.values().removeIf(at -> now - at > DECLINED_HOST_QUIET_MS);
        return host == null || !mDeclinedHosts.containsKey(host);
    }

    private static void closeQuietly(@Nullable InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "close failed", e);
            }
        }
    }

    /** A manifest name still in its __MSG_x__ form says nothing: no name rather than that. */
    @Nullable
    private static String shownName(@Nullable String name) {
        return name == null || name.startsWith("__MSG_") ? null : name;
    }

    /**
     * Heavy executor: copy, read the manifest, refuse early, then hand the
     * file to Gecko. True once the file was handed over (a page's install
     * is then finished by Gecko's answer, not here).
     */
    private boolean stageAndInstall(@NonNull InputStream in, @Nullable String host) throws IOException {
        File dir = new File(mContext.getCacheDir(), XPI_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("no cache dir");
        }
        File file = new File(dir, UUID.randomUUID() + ".xpi");
        boolean complete = false;
        try (FileOutputStream out = new FileOutputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            while (true) {
                int read = in.read(buf);
                if (read < 0) {
                    break;
                }
                total += read;
                if (total > MAX_XPI_BYTES) {
                    post(() -> emit(Event.TOO_LARGE, null));
                    return false;
                }
                out.write(buf, 0, read);
            }
            complete = true;
        } finally {
            if (!complete) {
                file.delete();
            }
        }
        XpiManifest.Info info;
        try {
            info = XpiManifest.read(file);
        } catch (XpiManifest.NotAnAddonException e) {
            file.delete();
            post(() -> emit(Event.NOT_AN_ADDON, null));
            return false;
        }
        String name = shownName(info.name);
        if (AddonPolicy.isBuiltInId(info.id)) {
            file.delete();
            post(() -> emit(Event.BUILT_IN, name));
            return false;
        }
        if (!info.signed) {
            file.delete();
            post(() -> emit(Event.UNSIGNED, name));
            return false;
        }
        InstallContext context = new InstallContext(
                host == null ? mContext.getString(R.string.addons_source_file) : host,
                !info.declaresAndroid, host);
        String declaredId = info.id;
        boolean fromPage = host != null;
        post(() -> {
            if (declaredId != null) {
                mInstallContexts.put(declaredId, context);
            }
            mLastFileContext = context;
            install(Uri.fromFile(file).toString(), WebExtensionController.INSTALLATION_METHOD_FROM_FILE,
                    file, declaredId, fromPage);
        });
        return true;
    }

    private void install(@NonNull String url, @Nullable String method, @Nullable File staged,
                         @Nullable String contextId) {
        install(url, method, staged, contextId, false);
    }

    /**
     * Main thread. {@code staged}, when set, is deleted once Gecko is done
     * with it; {@code contextId}'s install context is dropped if Gecko fails
     * before asking; {@code fromPage} ends the page's one install in flight.
     */
    private void install(@NonNull String url, @Nullable String method, @Nullable File staged,
                         @Nullable String contextId, boolean fromPage) {
        GeckoRuntime runtime = mRuntime;
        if (runtime == null) {
            if (staged != null) {
                staged.delete();
            }
            if (fromPage) {
                mPageInstallInFlight = false;
            }
            if (contextId != null) {
                markInstalling(contextId, false);
            }
            if (staged != null && !fromPage) {
                markInstalling(INSTALLING_FILE, false);
            }
            emit(Event.FAILED, null);
            return;
        }
        mPendingInstalls++;
        mInstallsInFlight.setValue(mPendingInstalls);
        boolean quiet = contextId != null && mQuietInstalls.contains(contextId);
        runtime.getWebExtensionController().install(url, method).accept(extension -> {
            finishInstall(staged, contextId, fromPage);
        }, e -> {
            finishInstall(staged, contextId, fromPage);
            // A quiet install's marker is consumed by onInstalled, which
            // may land before or after this result; a failure never gets one.
            if (contextId != null) {
                mQuietInstalls.remove(contextId);
            }
            if (quiet) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "quiet install failed: " + contextId, e);
                }
            } else {
                report(e);
            }
        });
    }

    private void finishInstall(@Nullable File staged, @Nullable String contextId, boolean fromPage) {
        mPendingInstalls = Math.max(0, mPendingInstalls - 1);
        mInstallsInFlight.setValue(mPendingInstalls);
        if (mPendingInstalls == 0) {
            mLastFileContext = null;
        }
        if (contextId != null) {
            mInstallContexts.remove(contextId);
        }
        if (fromPage) {
            mPageInstallInFlight = false;
        }
        if (contextId != null) {
            markInstalling(contextId, false);
        }
        if (staged != null) {
            if (!fromPage) {
                // A picked file (a page's .xpi has no spinner on screen).
                markInstalling(INSTALLING_FILE, false);
            }
            File file = staged;
            mHeavyExecutor.execute(file::delete);
        }
    }

    private void report(@Nullable Throwable error) {
        if (!(error instanceof WebExtension.InstallException)) {
            emit(Event.FAILED, null);
            return;
        }
        WebExtension.InstallException e = (WebExtension.InstallException) error;
        String name = e.extensionName;
        if (e.extensionId != null && mRefusedBuiltIn.remove(e.extensionId)) {
            // Already said "built in" at the prompt.
            return;
        }
        switch (e.code) {
            case WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED:
                return;
            case WebExtension.InstallException.ErrorCodes.ERROR_SIGNEDSTATE_REQUIRED:
                emit(Event.UNSIGNED, name);
                return;
            case WebExtension.InstallException.ErrorCodes.ERROR_NETWORK_FAILURE:
                emit(Event.NETWORK, name);
                return;
            case WebExtension.InstallException.ErrorCodes.ERROR_CORRUPT_FILE:
            case WebExtension.InstallException.ErrorCodes.ERROR_INCORRECT_HASH:
                // A signature that doesn't verify lands here too (XPIInstall).
                emit(Event.CORRUPT, name);
                return;
            case WebExtension.InstallException.ErrorCodes.ERROR_UNSUPPORTED_ADDON_TYPE:
            case WebExtension.InstallException.ErrorCodes.ERROR_UNEXPECTED_ADDON_TYPE:
                emit(Event.NOT_AN_ADDON, name);
                return;
            case WebExtension.InstallException.ErrorCodes.ERROR_BLOCKLISTED:
            case WebExtension.InstallException.ErrorCodes.ERROR_SOFT_BLOCKED:
                emit(Event.BLOCKED, name);
                return;
            case WebExtension.InstallException.ErrorCodes.ERROR_INCOMPATIBLE:
                emit(Event.INCOMPATIBLE, name);
                return;
            default:
                emit(Event.FAILED, name);
        }
    }

    private void emit(int type, @Nullable String name) {
        mEvent.setValue(new Event(type, name));
    }

    @NonNull
    private InstallContext contextFor(@NonNull String id) {
        InstallContext context = mInstallContexts.remove(id);
        if (context != null) {
            return context;
        }
        if (mLastFileContext != null) {
            return mLastFileContext;
        }
        return new InstallContext(null, false);
    }

    // ── Managing ────────────────────────────────────────────────────────

    @UiThread
    public void setEnabled(@NonNull String id, boolean enabled) {
        WebExtension extension = mInstalled.get(id);
        GeckoRuntime runtime = mRuntime;
        if (extension == null || runtime == null) {
            return;
        }
        WebExtensionController controller = runtime.getWebExtensionController();
        GeckoResult<WebExtension> result = enabled
                ? controller.enable(extension, WebExtensionController.EnableSource.USER)
                : controller.disable(extension, WebExtensionController.EnableSource.USER);
        result.accept(this::onChanged, e -> {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "enable/disable failed", e);
            }
            publish();
        });
    }

    @UiThread
    public void setAllowedInPrivate(@NonNull String id, boolean allowed) {
        WebExtension extension = mInstalled.get(id);
        GeckoRuntime runtime = mRuntime;
        if (extension == null || runtime == null) {
            return;
        }
        runtime.getWebExtensionController().setAllowedInPrivateBrowsing(extension, allowed)
                .accept(this::onChanged, e -> {
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "private browsing change failed", e);
                    }
                    publish();
                });
    }

    /**
     * Grants or takes back one optional permission, site or data kind (the
     * add-on's page in Settings; Firefox's permission switches). Exactly
     * one of the three is set.
     */
    @UiThread
    public void setOptionalPermission(@NonNull String id, @Nullable String permission,
                                      @Nullable String origin, @Nullable String data, boolean granted) {
        GeckoRuntime runtime = mRuntime;
        if (runtime == null || !mInstalled.containsKey(id)) {
            return;
        }
        String[] permissions = permission == null ? new String[0] : new String[]{permission};
        String[] origins = origin == null ? new String[0] : new String[]{origin};
        String[] dataKinds = data == null ? new String[0] : new String[]{data};
        WebExtensionController controller = runtime.getWebExtensionController();
        GeckoResult<WebExtension> result = granted
                ? controller.addOptionalPermissions(id, permissions, origins, dataKinds)
                : controller.removeOptionalPermissions(id, permissions, origins, dataKinds);
        result.accept(this::onChanged, e -> {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "optional permission change failed", e);
            }
            publish();
        });
    }

    @UiThread
    public void uninstall(@NonNull String id) {
        WebExtension extension = mInstalled.get(id);
        GeckoRuntime runtime = mRuntime;
        if (extension == null || runtime == null) {
            return;
        }
        String name = AddonInfo.from(extension).name;
        runtime.getWebExtensionController().uninstall(extension).accept(unused -> {
            if (AddonPolicy.isUblockId(id)) {
                mLocalPrefs.edit().putBoolean(PREF_UBLOCK_REMOVED, true).apply();
            }
            forget(id);
            emit(Event.REMOVED, name);
        }, e -> {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "uninstall failed", e);
            }
            emit(Event.FAILED, name);
        });
    }

    private void onChanged(@Nullable WebExtension extension) {
        if (isUserAddon(extension)) {
            adopt(extension);
        }
        publish();
        scheduleRefresh();
    }

    private final Runnable mRefresh = this::refreshFromGecko;

    /**
     * Gecko's change notices can arrive out of order: allowing private
     * tabs RELOADS the add-on, and the "disabled" notice of that reload
     * (or a snapshot taken under the old permission) can land after the
     * new state, leaving the add-on shown off or not allowed while it runs
     * with the permission. So once the notices stop, the installed list is
     * read back from Gecko and replaces what they said.
     */
    private void scheduleRefresh() {
        mMain.removeCallbacks(mRefresh);
        mMain.postDelayed(mRefresh, REFRESH_DELAY_MS);
    }

    private void refreshFromGecko() {
        GeckoRuntime runtime = mRuntime;
        if (runtime == null) {
            return;
        }
        runtime.getWebExtensionController().list().accept(list -> {
            if (list == null) {
                return;
            }
            boolean changed = false;
            for (WebExtension extension : list) {
                // Only add-ons already known: an install or a removal in
                // progress is the delegates' to report.
                if (isUserAddon(extension) && mInstalled.containsKey(extension.id)) {
                    mInstalled.put(extension.id, extension);
                    changed = true;
                }
            }
            if (changed) {
                publish();
            }
        }, e -> {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "refresh list() failed", e);
            }
        });
    }

    private void forget(@NonNull String id) {
        mInstalled.remove(id);
        mActions.remove(id);
        clearReview(id);
        Popup popup = mPopup.getValue();
        if (popup != null && popup.addonId.equals(id)) {
            closePopup();
        }
        publish();
    }

    // ── Updates ─────────────────────────────────────────────────────────

    /**
     * Asks Gecko to update add-ons. {@code interactive} is the user's tap:
     * an update that asks for new permissions is then put to the user; in
     * the daily background check it is refused and the add-on is marked
     * "update needs review" until the user looks.
     */
    @UiThread
    public void checkForUpdates(boolean interactive, @Nullable String onlyId) {
        GeckoRuntime runtime = mRuntime;
        if (runtime == null) {
            return;
        }
        mPrefs.edit().putLong(PREF_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply();
        for (WebExtension extension : new ArrayList<>(mInstalled.values())) {
            if (onlyId != null && !onlyId.equals(extension.id)) {
                continue;
            }
            String id = extension.id;
            String name = AddonInfo.from(extension).name;
            if (interactive) {
                mInteractiveUpdates.add(id);
            }
            runtime.getWebExtensionController().update(extension).accept(updated -> {
                mInteractiveUpdates.remove(id);
                if (updated != null) {
                    clearReview(id);
                    onChanged(updated);
                    if (interactive) {
                        emit(Event.UPDATED, name);
                    }
                } else if (interactive) {
                    emit(Event.NO_UPDATE, name);
                }
            }, e -> {
                mInteractiveUpdates.remove(id);
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "update failed: " + id, e);
                }
                if (interactive) {
                    emit(Event.FAILED, name);
                }
            });
        }
    }

    /**
     * The daily check, for as long as the app runs: a minute after start,
     * then looked at every hour, run once a day has passed since the last
     * check (any check, the user's too). The hourly look is on the handler's
     * uptime clock, so a sleeping phone is never woken for it; the next look
     * after it wakes catches up. Firedown runs no background job for this.
     */
    private void scheduleUpdateCheck() {
        if (mInstalled.isEmpty() || mUpdateTicking) {
            return;
        }
        mUpdateTicking = true;
        mMain.postDelayed(mUpdateTick, UPDATE_DELAY_MS);
    }

    private final Runnable mUpdateTick = this::onUpdateTick;

    private void onUpdateTick() {
        if (mInstalled.isEmpty()) {
            mUpdateTicking = false;
            return;
        }
        long last = mPrefs.getLong(PREF_LAST_UPDATE_CHECK, 0L);
        long now = System.currentTimeMillis();
        // A last check in the future is a clock that moved back: check now.
        if (now - last >= UPDATE_INTERVAL_MS || now < last) {
            checkForUpdates(false, null);
        }
        mMain.postDelayed(mUpdateTick, UPDATE_POLL_MS);
    }

    /**
     * The review notification's tap: the update asked for again, as the
     * user's own check, so its permission question is put to them.
     */
    @UiThread
    public void reviewUpdate(@NonNull String id) {
        if (mRuntime == null || !mLoaded) {
            mPendingReview = id;
            return;
        }
        checkForUpdates(true, id);
    }

    private void markReview(@NonNull String id) {
        Set<String> ids = new HashSet<>(mPrefs.getStringSet(PREF_UPDATE_REVIEW, Collections.emptySet()));
        if (ids.add(id)) {
            mPrefs.edit().putStringSet(PREF_UPDATE_REVIEW, ids).apply();
            publish();
            // Once per add-on and update: the daily check that finds it again
            // finds it already marked and says nothing.
            AddonUpdateNotification.show(mContext, reviewNames(ids), ids.size() == 1 ? id : null);
        }
    }

    private void clearReview(@NonNull String id) {
        Set<String> ids = new HashSet<>(mPrefs.getStringSet(PREF_UPDATE_REVIEW, Collections.emptySet()));
        if (ids.remove(id)) {
            mPrefs.edit().putStringSet(PREF_UPDATE_REVIEW, ids).apply();
            if (ids.isEmpty()) {
                AddonUpdateNotification.cancel(mContext);
            }
        }
    }

    /** The names of the add-ons whose update waits for the user, for the notification. */
    @NonNull
    private List<String> reviewNames(@NonNull Set<String> ids) {
        List<String> names = new ArrayList<>();
        for (String id : ids) {
            WebExtension extension = mInstalled.get(id);
            names.add(extension == null ? id : AddonInfo.from(extension).name);
        }
        Collections.sort(names, String::compareToIgnoreCase);
        return names;
    }

    // ── Prompts ─────────────────────────────────────────────────────────

    private void enqueue(@NonNull Prompt prompt) {
        if (mPrompt.getValue() == null) {
            mPrompt.setValue(prompt);
        } else if (mPromptQueue.size() < MAX_QUEUED_PROMPTS) {
            mPromptQueue.add(prompt);
        } else {
            // Nobody answers a pile of questions: Gecko gets its "no" now.
            complete(prompt, false, false, false);
        }
    }

    private static void complete(@NonNull Prompt prompt, boolean allow, boolean allowPrivate,
                                 boolean allowTechnicalData) {
        if (prompt.installResult != null) {
            prompt.installResult.complete(new WebExtension.PermissionPromptResponse(
                    allow, allow && allowPrivate, allow && allowTechnicalData));
        }
        if (prompt.allowResult != null) {
            prompt.allowResult.complete(allow ? AllowOrDeny.ALLOW : AllowOrDeny.DENY);
        }
    }

    /**
     * The user answered prompt {@code seq}. A prompt the screen lost (the
     * activity finished before an answer) is answered "no" by
     * {@link #dismissPrompt}, never left hanging: Gecko waits on it.
     */
    @UiThread
    public void answer(int seq, boolean allow, boolean allowPrivate) {
        answer(seq, allow, allowPrivate, false);
    }

    /** {@code allowTechnicalData}: the install prompt's opt-in to share technical and interaction data. */
    @UiThread
    public void answer(int seq, boolean allow, boolean allowPrivate, boolean allowTechnicalData) {
        Prompt current = mPrompt.getValue();
        if (current == null || current.seq != seq) {
            return;
        }
        complete(current, allow, allowPrivate, allowTechnicalData && current.offersTechnicalData);
        if (allow && current.type == Prompt.UPDATE) {
            clearReview(current.addon.id);
        }
        if (current.pageHost != null) {
            mPageInstallInFlight = false;
            if (!allow) {
                mDeclinedHosts.put(current.pageHost, SystemClock.elapsedRealtime());
            }
        }
        mPrompt.setValue(mPromptQueue.poll());
    }

    @UiThread
    public void dismissPrompt(int seq) {
        answer(seq, false, false);
    }

    @NonNull
    private static List<String> list(@Nullable String[] values) {
        if (values == null || values.length == 0) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(values)));
    }

    private final WebExtensionController.PromptDelegate mPromptDelegate =
            new WebExtensionController.PromptDelegate() {
        @Nullable
        @Override
        public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                @NonNull WebExtension extension, @NonNull String[] permissions,
                @NonNull String[] origins, @NonNull String[] dataCollectionPermissions) {
            GeckoResult<WebExtension.PermissionPromptResponse> result = new GeckoResult<>();
            AddonInfo info = AddonInfo.from(extension);
            if (AddonPolicy.isBuiltInId(extension.id)) {
                mRefusedBuiltIn.add(extension.id);
                emit(Event.BUILT_IN, info.name);
                result.complete(new WebExtension.PermissionPromptResponse(false, false, false));
                return result;
            }
            InstallContext context = contextFor(extension.id);
            if (context.bundled && AddonPolicy.isUblockId(extension.id)) {
                // The copy in the app: Firedown's own ad blocker, in private tabs too.
                result.complete(new WebExtension.PermissionPromptResponse(true, true, false));
                return result;
            }
            enqueue(new Prompt(++mPromptSeq, Prompt.INSTALL, info, list(permissions), list(origins),
                    list(dataCollectionPermissions), context.source, context.notMadeForAndroid,
                    context.pageHost, result, null));
            return result;
        }

        @Nullable
        @Override
        public GeckoResult<AllowOrDeny> onUpdatePrompt(
                @NonNull WebExtension extension, @NonNull String[] newPermissions,
                @NonNull String[] newOrigins, @NonNull String[] newDataCollectionPermissions) {
            GeckoResult<AllowOrDeny> result = new GeckoResult<>();
            if (!mInteractiveUpdates.contains(extension.id)) {
                markReview(extension.id);
                result.complete(AllowOrDeny.DENY);
                return result;
            }
            enqueue(new Prompt(++mPromptSeq, Prompt.UPDATE, AddonInfo.from(extension),
                    list(newPermissions), list(newOrigins), list(newDataCollectionPermissions),
                    null, false, null, null, result));
            return result;
        }

        @Nullable
        @Override
        public GeckoResult<AllowOrDeny> onOptionalPrompt(
                @NonNull WebExtension extension, @NonNull String[] permissions,
                @NonNull String[] origins, @NonNull String[] dataCollectionPermissions) {
            GeckoResult<AllowOrDeny> result = new GeckoResult<>();
            if (!isUserAddon(extension)) {
                result.complete(AllowOrDeny.DENY);
                return result;
            }
            enqueue(new Prompt(++mPromptSeq, Prompt.OPTIONAL, AddonInfo.from(extension),
                    list(permissions), list(origins), list(dataCollectionPermissions),
                    null, false, null, null, result));
            return result;
        }
    };

    private final WebExtensionController.AddonManagerDelegate mAddonManagerDelegate =
            new WebExtensionController.AddonManagerDelegate() {
        @Override
        public void onInstalled(@NonNull WebExtension extension) {
            if (!isUserAddon(extension)) {
                return;
            }
            boolean isNew = !mInstalled.containsKey(extension.id);
            adopt(extension);
            publish();
            if (AddonPolicy.isUblockId(extension.id)) {
                mLocalPrefs.edit().remove(PREF_UBLOCK_REMOVED).apply();
            }
            boolean quiet = mQuietInstalls.remove(extension.id);
            if (isNew) {
                if (!quiet) {
                    emit(Event.INSTALLED, AddonInfo.from(extension).name);
                }
                scheduleUpdateCheck();
            }
        }

        @Override
        public void onUninstalled(@NonNull WebExtension extension) {
            if (mInstalled.containsKey(extension.id)) {
                forget(extension.id);
            }
        }

        @Override
        public void onEnabled(@NonNull WebExtension extension) {
            if (isUserAddon(extension)) {
                onChanged(extension);
            }
        }

        @Override
        public void onDisabled(@NonNull WebExtension extension) {
            if (isUserAddon(extension)) {
                onChanged(extension);
            }
        }

        @Override
        public void onReady(@NonNull WebExtension extension) {
            if (isUserAddon(extension) && mInstalled.containsKey(extension.id)) {
                onChanged(extension);
            }
        }

        @Override
        public void onOptionalPermissionsChanged(@NonNull WebExtension extension) {
            if (isUserAddon(extension) && mInstalled.containsKey(extension.id)) {
                onChanged(extension);
            }
        }

        @Override
        public void onInstallationFailed(@Nullable WebExtension extension,
                                         @NonNull WebExtension.InstallException exception) {
            // Our own installs report from their result; this catches the
            // ones a page started (Gecko's own web install).
            if (mPendingInstalls == 0) {
                report(exception);
            }
        }
    };

    // ── Buttons (browser and page actions) and popups ──────────────────

    /** One delegate for the add-on's defaults (session null) and every tab's overrides. */
    private final WebExtension.ActionDelegate mActionDelegate = new WebExtension.ActionDelegate() {
        @Override
        public void onBrowserAction(@NonNull WebExtension extension, @Nullable GeckoSession session,
                                    @NonNull WebExtension.Action action) {
            ActionState state = actionState(extension.id);
            if (session == null) {
                state.browserDefault = action;
            } else {
                state.browserOverrides.put(session, action);
            }
            mActionsVersion.setValue(nextVersion());
        }

        @Override
        public void onPageAction(@NonNull WebExtension extension, @Nullable GeckoSession session,
                                 @NonNull WebExtension.Action action) {
            ActionState state = actionState(extension.id);
            if (session == null) {
                state.pageDefault = action;
            } else {
                state.pageOverrides.put(session, action);
            }
            mActionsVersion.setValue(nextVersion());
        }

        @Nullable
        @Override
        public GeckoResult<GeckoSession> onTogglePopup(@NonNull WebExtension extension,
                                                       @NonNull WebExtension.Action action) {
            Popup open = mPopup.getValue();
            if (open != null && open.addonId.equals(extension.id)) {
                closePopup();
                return null;
            }
            return openPopup(extension);
        }

        @Nullable
        @Override
        public GeckoResult<GeckoSession> onOpenPopup(@NonNull WebExtension extension,
                                                     @NonNull WebExtension.Action action) {
            return openPopup(extension);
        }
    };

    @NonNull
    private ActionState actionState(@NonNull String id) {
        ActionState state = mActions.get(id);
        if (state == null) {
            state = new ActionState();
            mActions.put(id, state);
        }
        return state;
    }

    @Nullable
    private GeckoResult<GeckoSession> openPopup(@NonNull WebExtension extension) {
        GeckoRuntime runtime = mRuntime;
        if (runtime == null || !isUserAddon(extension)) {
            return null;
        }
        closePopup();
        GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                .usePrivateMode(mBrowsingPrivate)
                .build();
        GeckoSession session = new GeckoSession(settings);
        session.setContentDelegate(mPopupContentDelegate);
        session.setNavigationDelegate(mPopupNavigationDelegate);
        session.open(runtime);
        mPageSessions.put(session, extension.id);
        Popup popup = new Popup(extension.id, AddonInfo.from(extension).name, session);
        mPopup.setValue(popup);
        return GeckoResult.fromValue(session);
    }

    /** The sheet closed, or the popup closed itself: the session goes with it. */
    @UiThread
    public void closePopup() {
        Popup popup = mPopup.getValue();
        if (popup == null) {
            return;
        }
        mPopup.setValue(null);
        popup.session.close();
    }

    @UiThread
    public void closePopup(@NonNull Popup popup) {
        if (mPopup.getValue() == popup) {
            closePopup();
        }
    }

    private final GeckoSession.ContentDelegate mPopupContentDelegate = new GeckoSession.ContentDelegate() {
        @Override
        public void onCloseRequest(@NonNull GeckoSession session) {
            closePopupOf(session);
        }
    };

    /**
     * A popup's (or an options page's) links to web pages open in a tab;
     * the session itself only shows the add-on's own pages. A popup that
     * sent the user to a tab closes.
     */
    private final GeckoSession.NavigationDelegate mPopupNavigationDelegate = new GeckoSession.NavigationDelegate() {
        @Nullable
        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(@NonNull GeckoSession session,
                                                      @NonNull LoadRequest request) {
            String uri = request.uri;
            String lower = uri.toLowerCase(Locale.ROOT);
            if (lower.startsWith("moz-extension://") || lower.startsWith("about:blank")
                    || lower.startsWith("data:") || lower.startsWith("blob:")) {
                return GeckoResult.allow();
            }
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                openWebTab(uri);
                closePopupOf(session);
            }
            return GeckoResult.deny();
        }

        @Nullable
        @Override
        public GeckoResult<GeckoSession> onNewSession(@NonNull GeckoSession session, @NonNull String uri) {
            String lower = uri.toLowerCase(Locale.ROOT);
            // A web link, or a link to one of the add-on's own pages opened
            // in a new window: on Android Ghostery's "Enable Ghostery" is a
            // target=_blank link to its onboarding page (its openHref), and
            // the add-on does nothing until that page is done.
            if (lower.startsWith("http://") || lower.startsWith("https://") || isOwnPage(session, uri)) {
                openWebTab(uri);
                closePopupOf(session);
            }
            return null;
        }
    };

    /** {@code uri} is a page of the add-on that {@code session} shows. */
    private boolean isOwnPage(@NonNull GeckoSession session, @NonNull String uri) {
        String id = mPageSessions.get(session);
        WebExtension extension = id == null ? null : mInstalled.get(id);
        if (extension == null) {
            return false;
        }
        String base = extension.metaData.baseUrl;
        return uri.toLowerCase(Locale.ROOT).startsWith("moz-extension://")
                && AddonPolicy.mayLoadInTab(uri, base);
    }

    private void closePopupOf(@NonNull GeckoSession session) {
        Popup popup = mPopup.getValue();
        if (popup != null && popup.session == session) {
            closePopup();
        }
    }

    // ── Tabs (tabs.create / update / remove, runtime.openOptionsPage) ──

    private final WebExtension.TabDelegate mTabDelegate = new WebExtension.TabDelegate() {
        @Nullable
        @Override
        public GeckoResult<GeckoSession> onNewTab(@NonNull WebExtension extension,
                                                  @NonNull WebExtension.CreateTabDetails details) {
            return newTab(extension, details);
        }

        @Override
        public void onOpenOptionsPage(@NonNull WebExtension extension) {
            openOptionsPage(extension.id);
        }
    };

    private final WebExtension.SessionTabDelegate mSessionTabDelegate = new WebExtension.SessionTabDelegate() {
        @NonNull
        @Override
        public GeckoResult<AllowOrDeny> onUpdateTab(@NonNull WebExtension extension,
                                                    @NonNull GeckoSession session,
                                                    @NonNull WebExtension.UpdateTabDetails details) {
            // Only a Firedown tab: the hidden sessions (YouTube's token page,
            // the share engine) and add-on pages are not the add-on's to move.
            GeckoState state = findState(session);
            if (state == null) {
                return GeckoResult.deny();
            }
            if (details.url != null
                    && !AddonPolicy.mayLoadInTab(details.url, extension.metaData.baseUrl)) {
                return GeckoResult.deny();
            }
            // No loadUri here: GeckoView loads the url itself once this
            // answers ALLOW (a second load would navigate twice).
            if (Boolean.TRUE.equals(details.active) && !isCurrent(state)) {
                showTab(state);
            }
            return GeckoResult.allow();
        }

        @NonNull
        @Override
        public GeckoResult<AllowOrDeny> onCloseTab(@Nullable WebExtension extension,
                                                   @NonNull GeckoSession session) {
            if (extension == null || findState(session) == null) {
                return GeckoResult.deny();
            }
            // The same path as a page's window.close(): the tab leaves the
            // list, its session closes, the browser moves to another tab.
            // Posted: GeckoView answers the add-on through this session's
            // window, so it must still be open when the ALLOW goes out.
            mMain.post(() -> {
                if (findState(session) != null) {
                    mComponents.get().getContentDelegate().onCloseRequest(session);
                }
            });
            return GeckoResult.allow();
        }
    };

    private boolean isCurrent(@NonNull GeckoState state) {
        return state.isIncognito()
                ? mIncognitoRepository.isCurrentGeckoState(state)
                : mRegularRepository.isCurrentGeckoState(state);
    }

    @Nullable
    private GeckoState findState(@NonNull GeckoSession session) {
        GeckoState state = mRegularRepository.getGeckoState(session);
        if (state == null) {
            state = mIncognitoRepository.getGeckoState(session);
        }
        return state;
    }

    /**
     * {@code tabs.create}: GeckoView needs an UNOPENED session it then opens
     * and loads itself. The tab is made a Firedown tab (connected, listed)
     * so it is tracked like any other, never a hidden session nobody closes.
     *
     * <p>Order matters: GeckoView opens the session in a callback it queues
     * on the main looper when the result completes, and the browser's tab
     * switch opens any session it finds unopened. So the result completes in
     * a posted task and the tab is shown in the task after GeckoView's.
     */
    @Nullable
    private GeckoResult<GeckoSession> newTab(@NonNull WebExtension extension,
                                             @NonNull WebExtension.CreateTabDetails details) {
        Host host = mHost;
        if (host == null || !isUserAddon(extension)) {
            return null;
        }
        boolean incognito = mBrowsingPrivate;
        if (incognito && !extension.metaData.allowedInPrivateBrowsing) {
            return null;
        }
        String url = TextUtils.isEmpty(details.url) ? "about:blank" : details.url;
        GeckoStateEntity entity = new GeckoStateEntity(false);
        entity.setUri(url);
        entity.setCreationDate(System.currentTimeMillis());
        entity.setIncognito(incognito);
        GeckoState state = new GeckoState(entity);
        GeckoSession session = state.getOrCreateGeckoSession(host.blocksTrackingContent(incognito));
        mComponents.get().connectSession(session);
        boolean active = details.active == null || details.active;
        GeckoResult<GeckoSession> result = new GeckoResult<>();
        mMain.post(() -> {
            result.complete(session);
            mMain.post(() -> showNewTab(state, session, url, active));
        });
        return result;
    }

    private void showNewTab(@NonNull GeckoState state, @NonNull GeckoSession session,
                            @NonNull String url, boolean active) {
        if (!session.isOpen()) {
            // GeckoView did not take the session (the extension went away
            // meanwhile); nothing to show and nothing to close.
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "tabs.create: session not opened, dropping " + url);
            }
            return;
        }
        if (state.isIncognito()) {
            mIncognitoRepository.setGeckoState(state, active);
        } else {
            mRegularRepository.setGeckoState(state, active);
        }
        if (active) {
            showTab(state);
        }
    }

    /**
     * Puts a tab on screen from wherever the user is (the browser, its home,
     * the tab grid, Settings): the browser activity is brought to the front
     * with the tab, the path a tab picked from the grid takes. An add-on
     * acting while the app is in the background adds the tab and makes it
     * current; Android does not let the app come forward by itself.
     */
    private void showTab(@NonNull GeckoState state) {
        Intent intent = new Intent(mContext, BrowserMainActivity.class);
        intent.setAction(IntentActions.OPEN_SESSION);
        intent.putExtra(Keys.ITEM_ID, state.getEntityId());
        intent.putExtra(Keys.ITEM_IS_HOME, false);
        intent.putExtra(Keys.IS_INCOGNITO, state.isIncognito());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivity(intent);
        } catch (RuntimeException e) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "showTab failed", e);
            }
        }
    }

    /** A link from a popup or options page (a web page, or the add-on's own): a new tab in the mode on screen. */
    private void openWebTab(@NonNull String url) {
        GeckoStateEntity entity = new GeckoStateEntity(false);
        entity.setUri(url);
        entity.setCreationDate(System.currentTimeMillis());
        entity.setIncognito(mBrowsingPrivate);
        GeckoState state = new GeckoState(entity);
        if (mBrowsingPrivate) {
            mIncognitoRepository.setGeckoState(state, true);
        } else {
            mRegularRepository.setGeckoState(state, true);
        }
        showTab(state);
    }

    /** {@code runtime.openOptionsPage()}: the add-on's settings, in Settings → Add-ons. */
    @UiThread
    public void openOptionsPage(@NonNull String id) {
        AddonInfo info = find(id);
        if (info == null || info.optionsPageUrl == null) {
            return;
        }
        Intent intent = new Intent(mContext, SettingsActivity.class);
        intent.putExtra(SettingsActivity.EXTRA_OPEN_ADDON_OPTIONS, id);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }

    /** The add-on's options page in a session of its own (Settings → Add-ons → the add-on → Settings). */
    @Nullable
    @UiThread
    public GeckoSession openOptionsSession(@NonNull String id) {
        GeckoRuntime runtime = mRuntime;
        AddonInfo info = find(id);
        if (runtime == null || info == null || info.optionsPageUrl == null) {
            return null;
        }
        GeckoSession session = new GeckoSession(new GeckoSessionSettings.Builder().build());
        session.setNavigationDelegate(mPopupNavigationDelegate);
        session.open(runtime);
        mPageSessions.put(session, id);
        registerSession(session);
        session.loadUri(info.optionsPageUrl);
        return session;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void post(@NonNull Runnable r) {
        mMain.post(r);
    }

    private void runOnMain(@NonNull Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mMain.post(r);
        }
    }
}

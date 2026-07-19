package com.solarized.firedown.data.repository;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.CertificateInfoEntity;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.TrackingCategory;
import com.solarized.firedown.geckoview.media.GeckoMediaController;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.mozilla.geckoview.GeckoSession;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import javax.inject.Inject;

import dagger.hilt.android.qualifiers.ApplicationContext;


public class GeckoStateDataRepository {

    private static final String TAG = GeckoStateDataRepository.class.getSimpleName();
    public static final String FILE = "com_solarized_firedown_sessions.json";

    // ── Boot-read hardening (issue #292: OutOfMemoryError on startup) ─────────
    // The sessions file is read on every cold start. It could grow without
    // bound: an inline data:-URL preview or favicon (an unbounded base64 blob)
    // was persisted verbatim per tab, and the number of tabs is uncapped, so
    // the file reached tens of MB. The old load slurped the WHOLE file into one
    // String and then built a full JSONArray from it — ~2–3× the file size
    // resident at once — and then kept the parsed preview/icon blobs resident in
    // mGeckoStates for the whole app lifetime. On a 128 MB heap that either
    // threw OutOfMemoryError during init or left the heap so full that any later
    // allocation — including a tiny main-thread one, as in issue #292's reported
    // stack — became the OOM victim. Because the file was re-read (and never
    // shrunk) on every launch, the crash repeated: a boot loop.
    //
    // The read is now STREAMED (android.util.JsonReader — one field resident at
    // a time, no whole-file String, no full JSONArray tree), the inline blob
    // fields are dropped (see sanitizeInlineField / the PREVIEW skip below), and
    // a size + OutOfMemoryError backstop moves a pathological file aside so the
    // app still boots. The write side (GeckoStateObserver) STREAMS a versioned
    // v2 document (JsonWriter, atomic tmp→fsync→rename with failed-write
    // cleanup), externalizes any data: favicon to a sidecar file (TabIconStore)
    // and writes NO preview at all, so the file can't re-grow. This mirrors
    // Firefox for Android (android-components SessionStorage /
    // BrowserStateWriter+Reader): streamed JsonWriter/JsonReader over an atomic
    // file, and NO image data inlined in the session file at all — Fenix keeps
    // favicons (BrowserIcons) and tab thumbnails (ThumbnailStorage) in separate
    // disk caches keyed by url/tab-id, which is why its session file is bounded
    // text and ours wasn't. Like Fenix, the v2 shape is read STRICTLY
    // (readDocumentStrict — the writer owns the shape); the lenient readers
    // below exist only for the legacy bare-array files older builds wrote,
    // which migrate to v2 on the next persist.
    //
    // MAX_SESSION_FILE_BYTES is NOT a memory bound anymore — the streamed read's
    // peak is one field regardless of file size (verified: a 122 MB blob file
    // streams fine on a 128 MB heap). It only bounds worst-case boot PARSE TIME
    // for a truly pathological file, so it is deliberately far above anything a
    // sanitized writer produces: a legacy blob-laden file under it still gets
    // every tab recovered by the streamed read instead of being thrown away.
    private static final long MAX_SESSION_FILE_BYTES = 256L * 1024 * 1024;
    /** v2 session document shape (Fenix parity — a versioned top-level object,
     *  like android-components' {@code Keys.VERSION_KEY}): {@code
     *  {"version":2,"tabs":[…]}}. The bare top-level ARRAY older builds wrote
     *  is the legacy shape, detected by the first token and read leniently as
     *  a one-time migration — the next persist rewrites v2. Bump the version
     *  (writer + strict reader together) for any non-backward-readable change. */
    public static final int SESSION_FILE_VERSION = 2;
    /** Newest version this build can READ — the forward-compat shim (readers
     *  ship ahead of writers, Chromium's format-rollout practice): v3 (the
     *  per-tab-session-state-files branch) externalizes each tab's session
     *  state to a file and writes a {@code session_ref} path instead of the
     *  inline string. This build resolves the ref INLINE at read time
     *  ({@link #readSessionStateFile}) so a downgrade from a v3 build loses
     *  no tabs; the next persist rewrites plain v2, and the v3 build's
     *  grace prune cleans the then-unreferenced state files if it returns.
     *  Without this shim a v3 file is moved aside → one-time tab reset. */
    public static final int MAX_READABLE_SESSION_FILE_VERSION = 3;
    /** Sanity cap when resolving a v3 {@code session_ref} file — real session
     *  states are KBs (Gecko caps per-tab history ~50 entries). */
    private static final int MAX_SESSION_STATE_FILE_BYTES = 16 * 1024 * 1024;
    public static final String KEY_VERSION = "version";
    public static final String KEY_TABS = "tabs";
    /** Above this length an icon/preview string is an inline blob we neither
     *  persist nor restore (it re-derives when the tab loads). Generous enough
     *  to keep real favicon/preview URLs. */
    public static final int MAX_INLINE_FIELD_CHARS = 256 * 1024;
    /** data: icons above this are dropped; small ones are kept — some sites ship
     *  their favicon only as a small data: URI, and dropping those left restored
     *  tabs iconless until reload. Bounded: even 1000 tabs × 8 KB is ~8 MB of
     *  file, nowhere near the blob previews that caused the OOM. */
    public static final int MAX_INLINE_DATA_URI_CHARS = 8 * 1024;
    /** How long a moved-aside {@code .corrupt} file is kept for post-mortem
     *  before {@link #pruneStaleCorruptFiles} deletes it on a later boot. */
    private static final long CORRUPT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000;
    private final Context mContext;
    private final List<GeckoState> mGeckoStates;
    private final MutableLiveData<Boolean> mInitialized;
    private final MutableLiveData<List<GeckoStateEntity>> mGeckoStatesLiveData;
    private final MutableLiveData<Integer> mCountLiveData;
    private final MutableLiveData<CertificateInfoEntity> mCertLiveData;
    private final MutableLiveData<Map<TrackingCategory, Integer>> mBlockedTrackerLiveData;
    private final Executor mDiskExecutor;
    private final GeckoMediaController mGeckoMediaController;
    private final TabStateArchivedRepository mArchivedRepository;

    // volatile: written under synchronized(mGeckoStates) — including on the
    // disk-executor thread in initializeGeckoStates — but read without the
    // lock in peekCurrentGeckoState/isCurrentGeckoState. volatile gives those
    // lock-free reads a happens-before edge so the main thread can't see a
    // stale id (e.g. the init value 0) after init set it on another thread.
    private volatile int mCurrentId = GeckoState.NULL_SESSION_ID;

    @Inject
    public GeckoStateDataRepository(
            @ApplicationContext Context context,
            @Qualifiers.DiskIO Executor diskExecutor,
            TabStateArchivedRepository archivedRepository,
            GeckoMediaController geckoMediaController) {
        this.mContext = context;
        this.mDiskExecutor = diskExecutor;
        this.mArchivedRepository = archivedRepository;
        this.mInitialized = new MutableLiveData<>(false);
        this.mBlockedTrackerLiveData = new MutableLiveData<>(Collections.emptyMap());
        this.mGeckoStates = Collections.synchronizedList(new ArrayList<>());
        this.mGeckoStatesLiveData = new MutableLiveData<>();
        this.mCountLiveData = new MutableLiveData<>();
        this.mCertLiveData = new MutableLiveData<>();
        this.mGeckoMediaController = geckoMediaController;
    }

    public LiveData<Boolean> isInitializedLiveData() {
        return mInitialized;
    }


    public void updateIcon(String icon, String originUrl) {
        synchronized (mGeckoStates) {
            for (GeckoState state : mGeckoStates) {
                if (StringUtils.compare(state.getEntityUri(), originUrl, false) == 0) {
                    state.setEntityIcon(icon);
                }
            }
        }
        notifyTabs();
    }


    public boolean isEmpty(){
        synchronized (mGeckoStates) {
            return mGeckoStates.isEmpty();
        }
    }

    public void notifyTabs() {
        List<GeckoStateEntity> geckoStateEntityList;
        List<GeckoState> snapshot;

        // 1. Create a point-in-time snapshot to avoid ConcurrentModificationException
        synchronized (mGeckoStates) {
            snapshot = new ArrayList<>(mGeckoStates);
        }

        // 2. Process the snapshot outside of the synchronized block to keep the lock duration short
        geckoStateEntityList = snapshot.stream()
                .map(geckoState -> {
                    GeckoStateEntity copy = new GeckoStateEntity(geckoState.getGeckoStateEntity());
                    copy.setCachedThumb(geckoState.getCachedThumb()); // carry the in-memory bitmap
                    return copy;
                })
                .collect(Collectors.toList());

        // Use setValue() if on main thread (synchronous — value is available immediately)
        // Use postValue() if on background thread (disk executor callbacks)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mGeckoStatesLiveData.setValue(geckoStateEntityList);
            mCountLiveData.setValue(geckoStateEntityList.size());
        } else {
            mGeckoStatesLiveData.postValue(geckoStateEntityList);
            mCountLiveData.postValue(geckoStateEntityList.size());
        }
    }


    public MutableLiveData<CertificateInfoEntity> getCertMutableLiveData(){
        return mCertLiveData;
    }

    public void notifyCert(CertificateInfoEntity value){
        mCertLiveData.postValue(value);
    }

    public LiveData<Map<TrackingCategory, Integer>> getBlockedTrackerLiveData(){
        return mBlockedTrackerLiveData;
    }

    public void postBlockedTrackerCounts(Map<TrackingCategory, Integer> counts){
        // setValue when called from main so the security sheet's
        // observer, which is registered immediately after the refresh
        // call in onCreateView, sees this value as its initial emission
        // — postValue would land one frame later via the Looper, and
        // the observer would receive whichever stale tab's snapshot
        // was last there before this one.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mBlockedTrackerLiveData.setValue(counts);
        } else {
            mBlockedTrackerLiveData.postValue(counts);
        }
    }

    public void setCurrentTabId(int tabId){
        GeckoState geckoState = peekCurrentGeckoState();
        if(geckoState != null){
            geckoState.setTabId(tabId);
        }
    }

    /**
     * Navigation-visit id of the tab that owns a capture, used to stamp
     * captures so the session-aware Captured view can group by page visit.
     * Prefers the active GeckoState (the one receiving onLocationChange) when
     * it owns {@code tabId}; otherwise the first non-home state with that tabId
     * that has actually navigated; otherwise the current tab's id, then 0.
     * tabId is unique per tab, but several GeckoState objects can share one
     * (duplicate/stale states for the same tab from navigation/restore churn),
     * so a naive first-match scan can return a stale state.
     */
    public int visitIdForTab(int tabId) {
        // The active tab is the common case and the one whose visit id we can
        // trust: it's the GeckoState that actually receives onLocationChange.
        // Prefer it directly — several GeckoState objects can carry the same
        // tabId (stale duplicates for one tab), so a plain "first state whose
        // tabId matches" scan can return a stale state (visit id 0) instead of
        // the page you're looking at.
        GeckoState current = peekCurrentGeckoState();
        if (current != null && !current.isHome() && current.getTabId() == tabId) {
            return current.getVisitId();
        }
        synchronized (mGeckoStates) {
            for (GeckoState state : mGeckoStates) {
                if (!state.isHome() && state.getTabId() == tabId && state.getVisitId() > 0) {
                    return state.getVisitId();
                }
            }
        }
        // No state owns this tabId — do NOT borrow the active tab's id, or a
        // background-tab capture would be stamped as "current page" and wrongly
        // float. 0 means "no anchor" → the list falls back to recency.
        return 0;
    }

    public GeckoState getGeckoState(int sessionId) {
        synchronized (mGeckoStates) {
            for (GeckoState state : mGeckoStates) {
                if (state.getEntityId() == sessionId) {
                    return state;
                }
            }
        }
        return null;
    }

    public GeckoState getGeckoState(String uri) {
        synchronized (mGeckoStates) {
            for (GeckoState state : mGeckoStates)
                if (state.getEntityUri().equals(uri)) {
                    return state;
                }
        }
        return null;
    }

    public GeckoState getGeckoState(GeckoSession geckoSession) {
        synchronized (mGeckoStates) {
            for (GeckoState state : mGeckoStates)
                if (state.getGeckoSession() == geckoSession) {
                    return state;
                }
        }
        return null;
    }

    public boolean isCurrentGeckoState(GeckoState geckoState) {
        return geckoState != null && geckoState.getEntityId() == mCurrentId;
    }

    /**
     * Returns the currently active GeckoState, or {@code null} if the tab
     * list is empty or the active ID doesn't match any tab.
     *
     * <p><b>Does NOT create a new tab.</b>  Use this in read-only / guard
     * contexts where accidentally mutating the tab list would be wrong —
     * for example {@code ensureSessionConnected()}, toolbar UI updates,
     * or any GeckoComponents delegate callback that just needs to look up
     * state.</p>
     *
     * @return the active GeckoState or {@code null}
     * @see #getCurrentGeckoState()
     */
    @Nullable
    public GeckoState peekCurrentGeckoState() {
        GeckoState state = getGeckoState(mCurrentId);
        Log.d(TAG, "peekCurrentGeckoState: mCurrentId=" + mCurrentId
                + " found=" + (state != null)
                + (state != null ? " isHome=" + state.isHome() + " uri=" + state.getEntityUri() : ""));
        return state;
    }

    /**
     * Returns the currently active GeckoState, creating a new home tab if
     * no active tab exists.
     */
    public GeckoState getCurrentGeckoState() {
        GeckoState geckoState = getGeckoState(mCurrentId);
        if (geckoState == null) {
            geckoState = new GeckoState(new GeckoStateEntity(true));
            Log.d(TAG, "getCurrentGeckoState: mCurrentId=" + mCurrentId
                    + " not found → auto-created home tab id=" + geckoState.getEntityId());
            setGeckoState(geckoState, true);
        } else {
            Log.d(TAG, "getCurrentGeckoState: mCurrentId=" + mCurrentId
                    + " found id=" + geckoState.getEntityId()
                    + " isHome=" + geckoState.isHome()
                    + " uri=" + geckoState.getEntityUri());
        }
        return geckoState;
    }


    /**
     * Guarantees the list holds at least the current (home) tab. Closing the
     * last tab leaves the list empty (count 0); when the user then lands on
     * Home, Home is itself a tab, so materialise a home {@link GeckoState} - the
     * count becomes a truthful 1 and the Tabs screen shows it. Gated on
     * initialization so it can't race {@link #initializeGeckoStates} (which
     * already guarantees >=1 at startup), and a no-op when ANY tab already
     * exists, so it never duplicates and never fights the close-undo window
     * (that lives on the Tabs screen, before Home is shown).
     */
    public void ensureHomeTabIfEmpty() {
        if (!Boolean.TRUE.equals(mInitialized.getValue())) {
            return;
        }
        synchronized (mGeckoStates) {
            if (!mGeckoStates.isEmpty()) {
                return;
            }
        }
        setGeckoState(new GeckoState(new GeckoStateEntity(true)), true);
    }


    public void setGeckoState(GeckoState geckoState, boolean active) {
        Log.d(TAG, "setGeckoState: id=" + geckoState.getEntityId()
                + " uri=" + geckoState.getEntityUri()
                + " isHome=" + geckoState.isHome()
                + " active=" + active
                + " prevCurrentId=" + mCurrentId);
        boolean changed = false;
        synchronized (mGeckoStates) {
            if (!mGeckoStates.contains(geckoState)) {
                // Honour a pending restore index from a prior
                // closeGeckoState (undo-on-close path). If the index is
                // out of range — list shrank in the meantime — clamp to
                // the end instead of throwing.
                int restoreIndex = geckoState.consumePendingRestoreIndex();
                if (restoreIndex >= 0 && restoreIndex <= mGeckoStates.size()) {
                    Log.d(TAG, "setGeckoState: restoring tab at index " + restoreIndex
                            + " (size was " + mGeckoStates.size() + ")");
                    mGeckoStates.add(restoreIndex, geckoState);
                } else {
                    Log.d(TAG, "setGeckoState: adding NEW tab to list (size was " + mGeckoStates.size() + ")");
                    mGeckoStates.add(geckoState);
                }
                changed = true;
            }
            if (active && geckoState.getEntityId() != mCurrentId) {
                // Skip the iterate-and-deactivate sweep when the state is
                // already the current one. Every tab switch through
                // TabsFragment fires setGeckoState(_, true) twice (once
                // from BaseTabsFragment.activateGeckoState, once from
                // BrowserFragment.setActiveSession via the OPEN_SESSION
                // observer); the second call previously did a redundant
                // clearCachedThumb pass on every non-active tab plus a
                // notifyTabs emission for no state change.
                mCurrentId = geckoState.getEntityId();
                Log.d(TAG, "setGeckoState: mCurrentId → " + mCurrentId);
                long now = System.currentTimeMillis();
                for (GeckoState state : mGeckoStates) {
                    boolean isActive = state.getEntityId() == mCurrentId;
                    state.setActive(isActive);
                    if (isActive) {
                        // Stamp "most recently used" so the Continue-browsing
                        // card and auto-archive can rank by real usage rather
                        // than creation order.
                        state.getGeckoStateEntity().setLastAccess(now);
                    } else {
                        state.clearCachedThumb();
                    }
                }
                changed = true;
            } else if (active) {
                // Same-tab reactivate: caller is asserting "this tab is
                // current and active", so make sure the GeckoSession side
                // matches even when we skip the iterate sweep. Without
                // this, an out-of-band setActive(false) (URL-bar focus,
                // surface reattach, …) on the current tab would never be
                // undone and the GeckoView would render a blank surface.
                // setActive is idempotent so this is safe when the
                // session is already active.
                geckoState.setActive(true);
            }
        }
        if (changed) notifyTabs();
    }

    public void deleteAll() {
        //Stop all media before clearing tabs
        mGeckoMediaController.clearMedia();
        // Snapshot the list under the lock and clear it, then close the
        // sessions outside the synchronized block. closeGeckoSession() must
        // run on the main thread; if we're already there, do it inline,
        // otherwise post — the previous implementation silently dropped
        // sessions on the floor when called off main, which would leak
        // them since clearing mGeckoStates was the last reference.
        // Mirrors IncognitoStateRepository.deleteAll().
        List<GeckoState> toClose;
        synchronized (mGeckoStates) {
            toClose = new ArrayList<>(mGeckoStates);
            for (GeckoState state : mGeckoStates) {
                state.clearCachedThumb();
            }
            mGeckoStates.clear();
        }
        mCurrentId = GeckoState.NULL_SESSION_ID;

        // dismissActivePrompt invokes AlertDialog.dismiss(), which must
        // run on the main thread — co-locate it with the closeGeckoSession
        // dispatch so both share the same main-thread guarantee.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            for (GeckoState state : toClose) {
                state.dismissActivePrompt();
                state.closeGeckoSession();
            }
        } else {
            new Handler(Looper.getMainLooper()).post(() -> {
                for (GeckoState state : toClose) {
                    state.dismissActivePrompt();
                    state.closeGeckoSession();
                }
            });
        }

        mGeckoStatesLiveData.postValue(null);
        mCountLiveData.postValue(0);
    }

    public void closeGeckoState(GeckoState geckoState) {
        //Notify the Media Controller to prevent orphaned Notification
        mGeckoMediaController.onTabClosed(geckoState.getEntityId());
        geckoState.clearCachedThumb();
        // Dismiss any prompt dialog still open for this tab. Tab-switch
        // already routes through setActive(false) which fires the same
        // hook; here on the close path we use dismissActivePrompt
        // because closeGeckoState reads isActive() below to decide
        // parent-tab promotion, and setActive(false) would flip it.
        geckoState.dismissActivePrompt();
        synchronized (mGeckoStates) {
            int currentPosition = mGeckoStates.indexOf(geckoState);
            if (currentPosition == -1) return;

            // Stash the index for a potential undo-on-close. The
            // TabsFragment snackbar calls setGeckoState(_, true) to
            // reinsert this state if the user taps "Undo"; without
            // this hint the reinsert appended to the end of the list
            // and the tab silently moved to last position. Consumed
            // once by setGeckoState on re-add.
            geckoState.setPendingRestoreIndex(currentPosition);

            mGeckoStates.remove(geckoState);

            if (!mGeckoStates.isEmpty()) {
                if (geckoState.isActive()) {
                    int parentId = geckoState.getEntityParentId();
                    if (parentId != GeckoState.NULL_SESSION_ID && getGeckoState(parentId) != null) {
                        mCurrentId = parentId;
                    } else {
                        currentPosition = Math.max(0, currentPosition - 1);
                        mCurrentId = mGeckoStates.get(currentPosition).getEntityId();
                    }
                }
                for (GeckoState state : mGeckoStates) {
                    state.setActive(state.getEntityId() == mCurrentId);
                }
            } else {
                mCurrentId = GeckoState.NULL_SESSION_ID;
            }
        }
        notifyTabs();

        // Use injected AppExecutors and Repository
        mDiskExecutor.execute(() -> {
            // User-closed tabs are NOT archived — closing was an explicit
            // user decision, so we respect it and drop the entity. Only
            // auto-closed tabs (archiveInactiveTabsLocked, fired by the
            // inactivity-threshold job) feed the archive; that's
            // 'recover something the browser took away on your behalf'
            // rather than 'undo your own action'. Incognito has never
            // archived either, so this matches that behaviour for
            // regular tabs.
            File thumbsDir = new File(StoragePaths.getThumbsPath(mContext));
            File[] existing = thumbsDir.listFiles(f ->
                    f.getName().startsWith(geckoState.getEntityId() + "_") && f.getName().endsWith(".png")
            );
            if (existing != null) {
                for (File f : existing) {
                    try { FileUtils.delete(f); } catch (IOException e) {
                        Log.w(TAG, "Error deleting thumb", e);
                    }
                }
            }
        });
    }

    public void updateThumb(GeckoState geckoState, Bitmap bitmap) {
        if (bitmap == null || geckoState == null) return;
        Bitmap scaled = GeckoState.scaleThumbnail(bitmap);
        // Cache in memory immediately — UI can use this right away
        geckoState.setCachedThumb(scaled);
        notifyTabs(); // triggers the adapter to rebind with the cached bitmap
        // Write to disk in background for persistence
        mDiskExecutor.execute(() -> {
            File thumbsDir = new File(StoragePaths.getThumbsPath(mContext));
            File[] existing = thumbsDir.listFiles(f ->
                    f.getName().startsWith(geckoState.getEntityId() + "_") && f.getName().endsWith(".png")
            );
            if (existing != null) {
                for (File old : existing) {
                    try { FileUtils.delete(old); } catch (IOException e) { /* ignore */ }
                }
            }
            long timestamp = System.currentTimeMillis();
            File file = new File(StoragePaths.getThumbsPath(mContext), geckoState.getEntityId() + "_" + timestamp + ".png");
            try {
                FileUtils.createParentDirectories(file);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                geckoState.setEntityThumb(file.getAbsolutePath());
                geckoState.clearCachedThumb();

            } catch (IOException e) {
                Log.e(TAG, "updateThumb error", e);
                geckoState.clearCachedThumb(); // always release, success or failure
            }
        });
    }


    /**
     * Drops a persisted icon/preview value that is an oversized inline blob —
     * the exact payload that once inflated the sessions file to the point of an
     * OOM boot loop. A SMALL data: URI is kept: some sites ship their favicon
     * only as one, and dropping it left the restored tab iconless until reload;
     * the cap ({@link #MAX_INLINE_DATA_URI_CHARS}) keeps the total bounded.
     * Kept public so the write-side guard ({@code GeckoStateObserver.toJson})
     * shares one rule.
     */
    public static String sanitizeInlineField(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() > MAX_INLINE_FIELD_CHARS) {
            return "";
        }
        if (s.startsWith("data:") && s.length() > MAX_INLINE_DATA_URI_CHARS) {
            return "";
        }
        return s;
    }

    /**
     * Loads the persisted tabs, streaming the file so the PARSE overhead is one
     * field at a time — never a whole-file String or a full parsed tree —
     * regardless of how large the file has grown. (What is RETAINED is the
     * entity payload itself, dominated by the per-tab session states the app
     * must hold to restore tabs — same as Fenix's RecoverableTabs; the blob
     * fields that used to dwarf it are skipped/externalized.) Prefers the live
     * file, falls back to the {@code .tmp} written by the atomic-write path,
     * and returns an empty list if neither can be read. A present-but-
     * unreadable or oversized file is moved aside (not silently deleted) by
     * {@link #tryReadEntities}, so it can never re-crash the next launch.
     */
    private List<GeckoStateEntity> loadEntities() {
        File dir = mContext.getFilesDir();
        File localFile = new File(dir, FILE);
        File tempFile = new File(dir, FILE + ".tmp");

        pruneStaleCorruptFiles(dir);

        List<GeckoStateEntity> fromLocal = tryReadEntities(localFile);
        if (fromLocal != null) {
            return fromLocal;
        }

        List<GeckoStateEntity> fromTemp = tryReadEntities(tempFile);
        if (fromTemp != null) {
            // Temp is valid — promote it so the next write cycle starts clean.
            if (!tempFile.renameTo(localFile)) {
                Log.w(TAG, "Could not promote temp session file");
            }
            return fromTemp;
        }

        return new ArrayList<>();
    }

    /**
     * Streams one session file into entities, or returns {@code null} if the
     * file is absent, oversized, or fails to parse (including
     * {@link OutOfMemoryError}). An unreadable/oversized file is moved aside via
     * {@link #moveAside} rather than parsed — never OOM-crashing the boot, and
     * never re-read on the next launch.
     */
    @Nullable
    private List<GeckoStateEntity> tryReadEntities(File file) {
        if (!file.exists()) {
            return null;
        }
        long len = file.length();
        if (len > MAX_SESSION_FILE_BYTES) {
            Log.e(TAG, "Session file oversized (" + len + " bytes) — moving aside to boot");
            moveAside(file);
            return null;
        }
        try (InputStream in = new FileInputStream(file);
             JsonReader reader = new JsonReader(
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))) {
            // Shape dispatch (Fenix version model): the v2 writer produces a
            // versioned OBJECT and is read STRICTLY — the writer fully controls
            // that shape, so any deviation is corruption and moves the file
            // aside. A bare ARRAY is the legacy shape from older builds, read
            // through the LENIENT per-field readers exactly once — the next
            // persist rewrites it as v2, after which strictness applies.
            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                List<GeckoStateEntity> entities = new ArrayList<>();
                reader.beginArray();
                while (reader.hasNext()) {
                    entities.add(readEntity(reader));
                }
                reader.endArray();
                return entities;
            }
            return readDocumentStrict(reader);
        } catch (OutOfMemoryError | IOException | RuntimeException e) {
            // RuntimeException covers JsonReader's IllegalStateException /
            // NumberFormatException on a malformed file; OutOfMemoryError covers
            // a single pathological field. Either way the file is unusable —
            // move it aside so this launch (and the next) can proceed.
            Log.e(TAG, "Session file unreadable — moving aside", e);
            moveAside(file);
            return null;
        }
    }

    /**
     * Renames a bad session file to a fixed {@code .corrupt} sibling (kept for
     * post-mortem, overwritten each time so it can't accumulate); falls back to
     * deleting it if the rename fails. Never throws. A kept {@code .corrupt}
     * can be as large as the pathological file it came from, so it is not kept
     * forever — {@link #pruneStaleCorruptFiles} deletes it on a later boot once
     * it is older than {@link #CORRUPT_RETENTION_MS}.
     */
    private void moveAside(File file) {
        try {
            File aside = new File(file.getParentFile(), file.getName() + ".corrupt");
            FileUtils.deleteQuietly(aside);
            if (!file.renameTo(aside)) {
                FileUtils.deleteQuietly(file);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "moveAside failed", e);
        }
    }

    /**
     * Deletes moved-aside {@code .corrupt} session files once they are older
     * than {@link #CORRUPT_RETENTION_MS} — long enough for a post-mortem, short
     * enough that a one-time 100 MB corpse doesn't occupy app storage forever.
     * A fresh {@code .corrupt} (this boot's, or a recent one) is left alone.
     */
    private void pruneStaleCorruptFiles(File dir) {
        long cutoff = System.currentTimeMillis() - CORRUPT_RETENTION_MS;
        String[] candidates = {FILE + ".corrupt", FILE + ".tmp.corrupt"};
        for (String name : candidates) {
            File corrupt = new File(dir, name);
            if (corrupt.exists() && corrupt.lastModified() < cutoff) {
                FileUtils.deleteQuietly(corrupt);
            }
        }
    }

    private GeckoStateEntity readEntity(JsonReader reader) throws IOException {
        long date = System.currentTimeMillis();
        long update = 0L;
        boolean hasUpdate = false;
        String icon = "";
        int iconResolution = 0;
        String thumb = "";
        String title = "";
        // Same default as the old parseEntity: a random id for a row missing one.
        int id = UUID.randomUUID().hashCode();
        String session = "";
        int parentId = 0;
        String uri = "";
        boolean backward = false;
        boolean forward = false;
        boolean desktop = false;
        boolean fullScreen = false;
        boolean home = false;
        boolean active = false;
        boolean trackingProtection = true;

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case GeckoStateEntity.KEYS.DATE:
                    date = nextLongSafe(reader, date);
                    break;
                case GeckoStateEntity.KEYS.UPDATE:
                    update = nextLongSafe(reader, 0L);
                    hasUpdate = true;
                    break;
                case GeckoStateEntity.KEYS.ICON:
                    icon = sanitizeInlineField(nextStringSafe(reader));
                    break;
                case GeckoStateEntity.KEYS.ICON_RESOLUTION:
                    iconResolution = nextIntSafe(reader, 0);
                    break;
                case GeckoStateEntity.KEYS.THUMB:
                    thumb = nextStringSafe(reader);
                    break;
                case GeckoStateEntity.KEYS.PREVIEW:
                    // Never restored: the tab UI shows THUMB + ICON, not PREVIEW
                    // (an og:image, re-emitted live on load), and it is the field
                    // most prone to being a huge data: blob. Skip without keeping.
                    reader.skipValue();
                    break;
                case GeckoStateEntity.KEYS.TITLE:
                    title = nextStringSafe(reader);
                    break;
                case GeckoStateEntity.KEYS.ID:
                    id = nextIntSafe(reader, id);
                    break;
                case GeckoStateEntity.KEYS.SESSION:
                    session = nextStringSafe(reader);
                    break;
                case GeckoStateEntity.KEYS.PARENT_ID:
                    parentId = nextIntSafe(reader, 0);
                    break;
                case GeckoStateEntity.KEYS.URI:
                    uri = nextStringSafe(reader);
                    break;
                case GeckoStateEntity.KEYS.BACKWARD:
                    backward = nextBooleanSafe(reader, false);
                    break;
                case GeckoStateEntity.KEYS.FORWARD:
                    forward = nextBooleanSafe(reader, false);
                    break;
                case GeckoStateEntity.KEYS.DESKTOP:
                    desktop = nextBooleanSafe(reader, false);
                    break;
                case GeckoStateEntity.KEYS.FULLSCREEN:
                    fullScreen = nextBooleanSafe(reader, false);
                    break;
                case GeckoStateEntity.KEYS.HOME:
                    home = nextBooleanSafe(reader, false);
                    break;
                case GeckoStateEntity.KEYS.ACTIVE:
                    active = nextBooleanSafe(reader, false);
                    break;
                case GeckoStateEntity.KEYS.TRACKING_PROTECTION:
                    trackingProtection = nextBooleanSafe(reader, true);
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();

        GeckoStateEntity entity = new GeckoStateEntity(false);
        entity.setCreationDate(date);
        // Tabs persisted before lastAccess existed have no UPDATE key —
        // fall back to creation date so they still order sensibly.
        entity.setLastAccess(hasUpdate ? update : date);
        entity.setIcon(icon);
        entity.setIconResolution(iconResolution);
        entity.setThumb(thumb);
        entity.setTitle(title);
        entity.setId(id);
        entity.setSessionState(session);
        entity.setParentId(parentId);
        entity.setUri(uri);
        entity.setCanGoBackward(backward);
        entity.setCanGoForward(forward);
        entity.setDesktop(desktop);
        entity.setFullScreen(fullScreen);
        entity.setHome(home);
        entity.setActive(active);
        entity.setUseTrackingProtection(trackingProtection);
        return entity;
    }

    /**
     * Strict read of the v2 versioned document ({@code {"version":2,"tabs":[…]}}).
     * Fenix parity ({@code BrowserStateReader}): the WRITER fully controls this
     * shape ({@code GeckoStateObserver.writeEntity} is the contract), so the
     * reader demands exact types and THROWS on an unknown key or a version it
     * doesn't understand — the caller's catch moves the file aside. Leniency
     * here would only mask writer bugs; the lenient readers exist solely for
     * the legacy bare-array files older builds wrote. Consequence to know:
     * a future version bump followed by an APK downgrade reads as unknown →
     * move-aside → one-time tab reset (Fenix accepts the same).
     */
    private List<GeckoStateEntity> readDocumentStrict(JsonReader reader) throws IOException {
        List<GeckoStateEntity> entities = new ArrayList<>();
        int version = -1;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case KEY_VERSION:
                    version = reader.nextInt();
                    if (version < SESSION_FILE_VERSION
                            || version > MAX_READABLE_SESSION_FILE_VERSION) {
                        throw new IOException("Unsupported session file version: " + version);
                    }
                    break;
                case KEY_TABS:
                    reader.beginArray();
                    while (reader.hasNext()) {
                        entities.add(readEntityStrict(reader));
                    }
                    reader.endArray();
                    break;
                default:
                    throw new IllegalArgumentException("Unknown session document key: " + name);
            }
        }
        reader.endObject();
        if (version < SESSION_FILE_VERSION || version > MAX_READABLE_SESSION_FILE_VERSION) {
            throw new IOException("Session file missing version");
        }
        return entities;
    }

    /**
     * One tab from a v2 document — exact-type reads, unknown key throws (see
     * {@link #readDocumentStrict}). The writer emits {@code ""} for absent
     * strings, so no null handling is needed; {@code sanitizeInlineField} on
     * ICON is defense-in-depth only (the writer already externalized any
     * {@code data:} icon to a {@code TabIconStore} path).
     */
    private GeckoStateEntity readEntityStrict(JsonReader reader) throws IOException {
        GeckoStateEntity entity = new GeckoStateEntity(false);
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case GeckoStateEntity.KEYS.DATE:
                    entity.setCreationDate(reader.nextLong());
                    break;
                case GeckoStateEntity.KEYS.UPDATE:
                    entity.setLastAccess(reader.nextLong());
                    break;
                case GeckoStateEntity.KEYS.ICON:
                    entity.setIcon(sanitizeInlineField(reader.nextString()));
                    break;
                case GeckoStateEntity.KEYS.ICON_RESOLUTION:
                    entity.setIconResolution(reader.nextInt());
                    break;
                case GeckoStateEntity.KEYS.THUMB:
                    entity.setThumb(reader.nextString());
                    break;
                case GeckoStateEntity.KEYS.SESSION:
                    entity.setSessionState(reader.nextString());
                    break;
                case GeckoStateEntity.KEYS.SESSION_REF:
                    // v3 forward-compat (see MAX_READABLE_SESSION_FILE_VERSION):
                    // resolve the externalized state file INLINE so a downgrade
                    // from the per-tab-files branch keeps every tab. A missing/
                    // unreadable file degrades to "" — that one tab restores by
                    // URL (per-file containment, Chromium's restoreTabState
                    // contract), never the whole list.
                    entity.setSessionState(readSessionStateFile(reader.nextString()));
                    break;
                case GeckoStateEntity.KEYS.URI:
                    entity.setUri(reader.nextString());
                    break;
                case GeckoStateEntity.KEYS.ID:
                    entity.setId(reader.nextInt());
                    break;
                case GeckoStateEntity.KEYS.PARENT_ID:
                    entity.setParentId(reader.nextInt());
                    break;
                case GeckoStateEntity.KEYS.TITLE:
                    entity.setTitle(reader.nextString());
                    break;
                case GeckoStateEntity.KEYS.BACKWARD:
                    entity.setCanGoBackward(reader.nextBoolean());
                    break;
                case GeckoStateEntity.KEYS.FORWARD:
                    entity.setCanGoForward(reader.nextBoolean());
                    break;
                case GeckoStateEntity.KEYS.FULLSCREEN:
                    entity.setFullScreen(reader.nextBoolean());
                    break;
                case GeckoStateEntity.KEYS.DESKTOP:
                    entity.setDesktop(reader.nextBoolean());
                    break;
                case GeckoStateEntity.KEYS.ACTIVE:
                    entity.setActive(reader.nextBoolean());
                    break;
                case GeckoStateEntity.KEYS.TRACKING_PROTECTION:
                    entity.setUseTrackingProtection(reader.nextBoolean());
                    break;
                case GeckoStateEntity.KEYS.HOME:
                    entity.setHome(reader.nextBoolean());
                    break;
                default:
                    throw new IllegalArgumentException("Unknown session key: " + name);
            }
        }
        reader.endObject();
        return entity;
    }

    /**
     * Resolves a v3 {@code session_ref} (absolute path of an externalized
     * per-tab session-state file) to its content — the forward-compat half of
     * {@link #MAX_READABLE_SESSION_FILE_VERSION}. Total: {@code ""} on an
     * empty ref, a missing/oversized file, or any read failure.
     */
    private static String readSessionStateFile(String ref) {
        if (ref == null || ref.isEmpty()) {
            return "";
        }
        try {
            File file = new File(ref);
            long length = file.length();
            if (length <= 0 || length > MAX_SESSION_STATE_FILE_BYTES) {
                return "";
            }
            byte[] bytes = new byte[(int) length];
            try (InputStream in = new FileInputStream(file)) {
                int off = 0;
                while (off < bytes.length) {
                    int read = in.read(bytes, off, bytes.length - off);
                    if (read < 0) {
                        return "";
                    }
                    off += read;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    // LEGACY-ARRAY PATH ONLY — the v2 versioned document is read strictly
    // (readDocumentStrict above, Fenix parity); these lenient readers serve the
    // bare-array files older builds wrote, until the next persist migrates them.
    // Lenient primitive reads mirroring the old opt* semantics: a value stored
    // as the "wrong" JSON type still parses where it sensibly can, and ANY
    // token these helpers can't convert — null, an object/array, a fractional
    // or out-of-range number, a garbage numeric string — is consumed and falls
    // back to the default. Totality matters: a per-field throw would propagate
    // to tryReadEntities' catch-all and move the WHOLE file aside (all tabs
    // lost) over one odd field, which is exactly the wrong trade. Note
    // android.util.JsonReader's nextLong/nextInt/nextDouble accept BOTH NUMBER
    // and STRING tokens (parsing the string), and on a parse failure they throw
    // WITHOUT consuming the token — hence the skipValue() in each catch.
    private static String nextStringSafe(JsonReader reader) throws IOException {
        JsonToken token = reader.peek();
        if (token == JsonToken.STRING || token == JsonToken.NUMBER) {
            return reader.nextString();
        }
        if (token == JsonToken.BOOLEAN) {
            return String.valueOf(reader.nextBoolean());
        }
        reader.skipValue();
        return "";
    }

    private static long nextLongSafe(JsonReader reader, long def) throws IOException {
        JsonToken token = reader.peek();
        if (token != JsonToken.NUMBER && token != JsonToken.STRING) {
            reader.skipValue();
            return def;
        }
        try {
            return reader.nextLong();
        } catch (NumberFormatException e) {
            reader.skipValue();
            return def;
        }
    }

    private static int nextIntSafe(JsonReader reader, int def) throws IOException {
        JsonToken token = reader.peek();
        if (token != JsonToken.NUMBER && token != JsonToken.STRING) {
            reader.skipValue();
            return def;
        }
        try {
            return reader.nextInt();
        } catch (NumberFormatException e) {
            reader.skipValue();
            return def;
        }
    }

    private static boolean nextBooleanSafe(JsonReader reader, boolean def) throws IOException {
        JsonToken token = reader.peek();
        if (token == JsonToken.BOOLEAN) {
            return reader.nextBoolean();
        }
        if (token == JsonToken.STRING) {
            return Boolean.parseBoolean(reader.nextString());
        }
        if (token == JsonToken.NUMBER) {
            try {
                return reader.nextDouble() != 0.0;
            } catch (NumberFormatException e) {
                reader.skipValue();
                return def;
            }
        }
        reader.skipValue();
        return def;
    }

    public LiveData<List<GeckoStateEntity>> getTabsLiveData() {
        return mGeckoStatesLiveData;
    }

    public LiveData<Integer> getTabsLiveCount() {
        return mCountLiveData;
    }

    public void initializeGeckoStates(long autoArchiveThresholdMillis) {
        try {
            List<GeckoStateEntity> entities = loadEntities();
            HashSet<Integer> mIds = new HashSet<>();

            synchronized (mGeckoStates) {
                mGeckoStates.clear();
                for (GeckoStateEntity entity : entities) {
                    GeckoState geckoState = new GeckoState(entity);

                    if (!mIds.contains(geckoState.getEntityId()) && !geckoState.isHome()) {
                        mGeckoStates.add(geckoState);
                        mIds.add(geckoState.getEntityId());
                        // Only entities we actually keep can claim mCurrentId.
                        // The previous unconditional check let a (dropped)
                        // home entity's stale isActive flag set mCurrentId
                        // to an id that wasn't in mGeckoStates, leaving
                        // peekCurrentGeckoState returning null and the user
                        // bounced to home instead of their last non-home tab.
                        if (geckoState.isActive()) {
                            mCurrentId = geckoState.getEntityId();
                        }
                    }
                }

                if (mGeckoStates.isEmpty()) {
                    GeckoState newGeckoState = new GeckoState(new GeckoStateEntity(true));
                    newGeckoState.setActive(true);
                    mGeckoStates.add(newGeckoState);
                    mCurrentId = newGeckoState.getEntityId();
                } else {
                    mGeckoStates.sort(Comparator.comparingLong(GeckoState::getCreationDate));
                }

                // ── Auto-archive right after loading ────────────────────
                // We're already inside synchronized(mGeckoStates) and on
                // the disk executor, so this is safe and sequential.
                if (autoArchiveThresholdMillis > 0) {
                    archiveInactiveTabsLocked(autoArchiveThresholdMillis);
                }
            }
        } catch (OutOfMemoryError | RuntimeException e) {
            // loadEntities() handles its own IO/parse/OOM failures (moving a bad
            // file aside and returning what it could). This is a final safety net
            // so a fault anywhere in init still leaves the app initialized rather
            // than looping — the finally block below always marks it ready.
            // OutOfMemoryError is included deliberately: an uncaught Error on
            // this executor thread reaches Android's default uncaught handler
            // and kills the process, and an OOM while RETAINING a huge (legit)
            // session set would then re-crash every boot — the boot loop this
            // whole path exists to prevent. Catching it boots with whatever
            // subset was built; the next persist rewrites the file from it.
            Log.e(TAG, "initializeGeckoState error", e);
        } finally {
            mInitialized.postValue(true);
            notifyTabs();
        }
    }


    /**
     * Public entry point — acquires the lock itself.
     * Use from ViewModel / TabsFragment / WorkManager.
     */
    public int archiveInactiveTabs(long maxInactiveMillis) {
        synchronized (mGeckoStates) {
            return archiveInactiveTabsLocked(maxInactiveMillis);
        }
    }

    /**
     * Internal — caller MUST already hold synchronized(mGeckoStates).
     * Called from initializeGeckoStates() and archiveInactiveTabs().
     */
    private int archiveInactiveTabsLocked(long maxInactiveMillis) {
        long cutoff = System.currentTimeMillis() - maxInactiveMillis;
        List<GeckoState> toArchive = new ArrayList<>();

        for (GeckoState state : mGeckoStates) {
            if (state.isActive() || state.isHome()) continue;
            if (state.getGeckoStateEntity().isIncognito()) continue; // never archive incognito
            // Inactivity is measured from last use, not creation — a tab
            // the user keeps returning to shouldn't be archived just
            // because it's old.
            if (state.getGeckoStateEntity().getLastAccess() < cutoff) {
                toArchive.add(state);
            }
        }

        mGeckoStates.removeAll(toArchive);

        // Close the archived tabs' GeckoSessions. Dropping the GeckoState
        // from the list is not enough — the underlying GeckoSession stays
        // open() and registered with the runtime, leaking a content process
        // per auto-archived tab (which, accumulated over a long session,
        // is exactly the kind of pressure that triggers content-process
        // kills and dead tabs). The serialized session state lives in the
        // entity handed to mArchivedRepository below, so the tab still
        // restores fully on reopen via getOrCreateGeckoSession →
        // restoreState. closeGeckoSession() must run on the main thread
        // (GeckoSession contract); this method runs on the disk executor
        // during init and on a background thread for the periodic job, so
        // post when off-main — mirror deleteAll().
        if (!toArchive.isEmpty()) {
            final List<GeckoState> toCloseSessions = new ArrayList<>(toArchive);
            if (Looper.myLooper() == Looper.getMainLooper()) {
                for (GeckoState state : toCloseSessions) state.closeGeckoSession();
            } else {
                new Handler(Looper.getMainLooper()).post(() -> {
                    for (GeckoState state : toCloseSessions) state.closeGeckoSession();
                });
            }
        }

        // Persist outside the critical section isn't needed here because
        // we're already on the disk executor thread during initialization
        long nowMs = System.currentTimeMillis();
        for (GeckoState state : toArchive) {
            state.clearCachedThumb();
            // Stamp the actual archive time so the tabs page banner can
            // count "X tabs archived in the last [user's interval]"
            // regardless of when the auto-archive job actually ran.
            mArchivedRepository.addSync(state.getGeckoStateEntity(), nowMs);

            File thumbsDir = new File(StoragePaths.getThumbsPath(mContext));
            File[] existing = thumbsDir.listFiles(f ->
                    f.getName().startsWith(state.getEntityId() + "_")
                            && f.getName().endsWith(".png"));
            if (existing != null) {
                for (File f : existing) {
                    try { FileUtils.delete(f); } catch (IOException e) {
                        Log.w(TAG, "Error deleting thumb for archived tab", e);
                    }
                }
            }
        }

        // Bound the archive on every sweep (not only when we added rows): each
        // archived row holds a full sessionState blob, so an unbounded archive
        // is real storage growth. Age purge + count cap, both synchronous —
        // we're already off-main here (disk executor at init, background thread
        // for the periodic job), same context as the addSync() above.
        int purged = mArchivedRepository.purgeSync(
                Preferences.TABS_ARCHIVE_MAX_COUNT,
                Preferences.TABS_ARCHIVE_MAX_AGE_INTERVAL);
        if (purged > 0) {
            Log.d(TAG, "archiveInactiveTabsLocked: purged " + purged + " stale archived tabs");
        }

        return toArchive.size();
    }

}
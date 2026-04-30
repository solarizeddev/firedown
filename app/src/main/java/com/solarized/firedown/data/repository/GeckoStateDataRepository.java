package com.solarized.firedown.data.repository;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.CertificateInfoEntity;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.media.GeckoMediaController;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoSession;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import javax.inject.Inject;

import dagger.hilt.android.qualifiers.ApplicationContext;


public class GeckoStateDataRepository {

    private static final String TAG = GeckoStateDataRepository.class.getSimpleName();
    public static final String FILE = "com_solarized_firedown_sessions.json";
    private final Context mContext;
    private final List<GeckoState> mGeckoStates;
    private final MutableLiveData<Boolean> mInitialized;
    private final MutableLiveData<List<GeckoStateEntity>> mGeckoStatesLiveData;
    private final MutableLiveData<Integer> mCountLiveData;
    private final MutableLiveData<CertificateInfoEntity> mCertLiveData;
    private final Executor mDiskExecutor;
    private final GeckoMediaController mGeckoMediaController;
    private final TabStateArchivedRepository mArchivedRepository;

    private int mCurrentId = GeckoState.NULL_SESSION_ID;

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

    public void setCurrentTabId(int tabId){
        GeckoState geckoState = peekCurrentGeckoState();
        if(geckoState != null){
            geckoState.setTabId(tabId);
        }
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


    public void setGeckoState(GeckoState geckoState, boolean active) {
        Log.d(TAG, "setGeckoState: id=" + geckoState.getEntityId()
                + " uri=" + geckoState.getEntityUri()
                + " isHome=" + geckoState.isHome()
                + " active=" + active
                + " prevCurrentId=" + mCurrentId);
        synchronized (mGeckoStates) {
            if (!mGeckoStates.contains(geckoState)) {
                Log.d(TAG, "setGeckoState: adding NEW tab to list (size was " + mGeckoStates.size() + ")");
                mGeckoStates.add(geckoState);
            }
            if (active) {
                mCurrentId = geckoState.getEntityId();
                Log.d(TAG, "setGeckoState: mCurrentId → " + mCurrentId);
                for (GeckoState state : mGeckoStates) {
                    boolean isActive = state.getEntityId() == mCurrentId;
                    state.setActive(isActive);
                    if (!isActive) {
                        state.clearCachedThumb();
                    }
                }
            }
        }
        notifyTabs();
    }

    public void deleteAll() {
        //Stop all media before clearing tabs
        mGeckoMediaController.clearMedia();
        synchronized (mGeckoStates) {
            for (GeckoState state : mGeckoStates) {
                state.clearCachedThumb();
                if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
                    state.closeGeckoSession();
                }
            }
            mGeckoStates.clear();
        }
        mGeckoStatesLiveData.postValue(null);
        mCountLiveData.postValue(0);
        mCurrentId = GeckoState.NULL_SESSION_ID;
    }

    public void closeGeckoState(GeckoState geckoState) {
        //Notify the Media Controller to prevent orphaned Notification
        mGeckoMediaController.onTabClosed(geckoState.getEntityId());
        geckoState.clearCachedThumb();
        synchronized (mGeckoStates) {
            int currentPosition = mGeckoStates.indexOf(geckoState);
            if (currentPosition == -1) return;

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
            // Defense in depth: this repo is for regular tabs only, but
            // if an incognito entity ever slips in through a routing
            // bug, do NOT persist it to the archive. Incognito URLs
            // must never touch disk.
            if (!geckoState.getGeckoStateEntity().isIncognito()) {
                mArchivedRepository.addSync(geckoState.getGeckoStateEntity());
            } else {
                Log.w(TAG, "closeGeckoState: refusing to archive incognito entity id="
                        + geckoState.getEntityId());
            }
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


    private GeckoStateEntity parseEntity(JSONObject jsonObject) {
        GeckoStateEntity entity = new GeckoStateEntity(false);
        entity.setCreationDate(jsonObject.optLong(GeckoStateEntity.KEYS.DATE, System.currentTimeMillis()));
        entity.setIcon(jsonObject.optString(GeckoStateEntity.KEYS.ICON, ""));
        entity.setIconResolution(jsonObject.optInt(GeckoStateEntity.KEYS.ICON_RESOLUTION, 0));
        entity.setThumb(jsonObject.optString(GeckoStateEntity.KEYS.THUMB, ""));
        entity.setPreview(jsonObject.optString(GeckoStateEntity.KEYS.PREVIEW, ""));
        entity.setTitle(jsonObject.optString(GeckoStateEntity.KEYS.TITLE, ""));
        entity.setId(jsonObject.optInt(GeckoStateEntity.KEYS.ID, UUID.randomUUID().hashCode()));
        entity.setSessionState(jsonObject.optString(GeckoStateEntity.KEYS.SESSION, ""));
        entity.setParentId(jsonObject.optInt(GeckoStateEntity.KEYS.PARENT_ID, 0));
        entity.setUri(jsonObject.optString(GeckoStateEntity.KEYS.URI, ""));
        entity.setCanGoBackward(jsonObject.optBoolean(GeckoStateEntity.KEYS.BACKWARD, false));
        entity.setCanGoForward(jsonObject.optBoolean(GeckoStateEntity.KEYS.FORWARD, false));
        entity.setDesktop(jsonObject.optBoolean(GeckoStateEntity.KEYS.DESKTOP, false));
        entity.setFullScreen(jsonObject.optBoolean(GeckoStateEntity.KEYS.FULLSCREEN, false));
        entity.setHome(jsonObject.optBoolean(GeckoStateEntity.KEYS.HOME, false));
        entity.setActive(jsonObject.optBoolean(GeckoStateEntity.KEYS.ACTIVE, false));
        entity.setUseTrackingProtection(jsonObject.optBoolean(GeckoStateEntity.KEYS.TRACKING_PROTECTION, true));
        return entity;
    }

    private JSONArray fileToJSON() throws IOException, JSONException {
        File dir = mContext.getFilesDir();
        File localFile = new File(dir, FILE);
        File tempFile = new File(dir, FILE + ".tmp");

        if (localFile.exists()) {
            try {
                return readJsonArray(localFile);
            } catch (JSONException e) {
                Log.e(TAG, "Session file corrupt, trying temp fallback", e);
            }
        }

        if (tempFile.exists()) {
            try {
                JSONArray result = readJsonArray(tempFile);
                // Temp is valid — promote it so next write cycle starts clean
                if (!tempFile.renameTo(localFile)) {
                    Log.w(TAG, "Could not promote temp session file");
                }
                return result;
            } catch (JSONException e) {
                Log.e(TAG, "Temp session file also corrupt, starting fresh", e);
            }
        }

        return new JSONArray();
    }

    private JSONArray readJsonArray(File file) throws IOException, JSONException {
        try (InputStream inputStream = new FileInputStream(file)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            String content = reader.lines().collect(Collectors.joining());
            return new JSONArray(content);
        }
    }

    public LiveData<List<GeckoStateEntity>> getTabsLiveData() {
        return mGeckoStatesLiveData;
    }

    public LiveData<Integer> getTabsLiveCount() {
        return mCountLiveData;
    }

    public void initializeGeckoStates(long autoArchiveThresholdMillis) {
        try {
            JSONArray jsonArray = fileToJSON();
            HashSet<Integer> mIds = new HashSet<>();

            synchronized (mGeckoStates) {
                mGeckoStates.clear();
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject jsonObject = jsonArray.getJSONObject(i);
                    GeckoStateEntity entity = parseEntity(jsonObject);
                    GeckoState geckoState = new GeckoState(entity);

                    if (!mIds.contains(geckoState.getEntityId()) && !geckoState.isHome()) {
                        mGeckoStates.add(geckoState);
                        mIds.add(geckoState.getEntityId());
                    }
                    if (geckoState.isActive()) {
                        mCurrentId = geckoState.getEntityId();
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
        } catch (JSONException | IOException e) {
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
            if (state.getCreationDate() < cutoff) {
                toArchive.add(state);
            }
        }

        mGeckoStates.removeAll(toArchive);

        // Persist outside the critical section isn't needed here because
        // we're already on the disk executor thread during initialization
        for (GeckoState state : toArchive) {
            state.clearCachedThumb();
            mArchivedRepository.addSync(state.getGeckoStateEntity());

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

        return toArchive.size();
    }

}
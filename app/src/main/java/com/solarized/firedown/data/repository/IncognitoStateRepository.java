package com.solarized.firedown.data.repository;

import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.solarized.firedown.data.entity.CertificateInfoEntity;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.TrackingCategory;
import com.solarized.firedown.geckoview.media.GeckoMediaController;

import org.mozilla.geckoview.GeckoSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * In-memory repository for incognito tabs.
 *
 * <p>Mirrors the essential API of {@link GeckoStateDataRepository} but
 * <b>never persists anything to disk</b> — no JSON file, no Room archive,
 * no thumbnails.  All data is lost when the process dies, which is the
 * expected behavior for private browsing.</p>
 *
 * <p>Every {@link GeckoState} created through this repository uses
 * {@code usePrivateMode(true)} on its {@link GeckoSession}.</p>
 */
@Singleton
public class IncognitoStateRepository {

    private static final String TAG = IncognitoStateRepository.class.getSimpleName();

    private final List<GeckoState> mGeckoStates;
    private final MutableLiveData<List<GeckoStateEntity>> mGeckoStatesLiveData;
    private final MutableLiveData<Integer> mCountLiveData;
    private final MutableLiveData<CertificateInfoEntity> mCertLiveData;
    private final MutableLiveData<Map<TrackingCategory, Integer>> mBlockedTrackerLiveData;
    private final GeckoMediaController mGeckoMediaController;
    private final IncognitoTrackingPermissionRepository mTrackingRepository;
    private final IncognitoWasmAllowlistRepository mWasmAllowlistRepository;
    private int mCurrentId = GeckoState.NULL_SESSION_ID;

    @Inject
    public IncognitoStateRepository(GeckoMediaController geckoMediaController) {
        this.mCertLiveData = new MutableLiveData<>();
        this.mGeckoStates = Collections.synchronizedList(new ArrayList<>());
        this.mGeckoStatesLiveData = new MutableLiveData<>(Collections.emptyList());
        this.mCountLiveData = new MutableLiveData<>(0);
        this.mBlockedTrackerLiveData = new MutableLiveData<>(Collections.emptyMap());
        this.mGeckoMediaController = geckoMediaController;
        this.mTrackingRepository = new IncognitoTrackingPermissionRepository();
        this.mWasmAllowlistRepository = new IncognitoWasmAllowlistRepository();
    }

    // ── Tracking ─────────────────────────────────────────────────────

    public IncognitoTrackingPermissionRepository getTrackingRepository() {
        return mTrackingRepository;
    }

    public IncognitoWasmAllowlistRepository getWasmAllowlistRepository() {
        return mWasmAllowlistRepository;
    }

    // ── Query ────────────────────────────────────────────────────────

    public LiveData<List<GeckoStateEntity>> getTabsLiveData() {
        return mGeckoStatesLiveData;
    }

    public LiveData<Integer> getTabsLiveCount() {
        return mCountLiveData;
    }

    @Nullable
    public GeckoState getGeckoState(int sessionId) {
        synchronized (mGeckoStates) {
            for (GeckoState state : mGeckoStates) {
                if (state.getEntityId() == sessionId) return state;
            }
        }
        return null;
    }

    @Nullable
    public GeckoState getGeckoState(GeckoSession geckoSession) {
        synchronized (mGeckoStates) {
            for (GeckoState state : mGeckoStates) {
                if (state.getGeckoSession() == geckoSession) return state;
            }
        }
        return null;
    }

    @Nullable
    public GeckoState peekCurrentGeckoState() {
        return getGeckoState(mCurrentId);
    }

    public GeckoState getCurrentGeckoState() {
        GeckoState state = getGeckoState(mCurrentId);
        if (state == null) {
            GeckoStateEntity entity = new GeckoStateEntity(true);
            entity.setIncognito(true);
            state = new GeckoState(entity);
            setGeckoState(state, true);
        }
        return state;
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

    public boolean isCurrentGeckoState(GeckoState geckoState) {
        return geckoState != null && geckoState.getEntityId() == mCurrentId;
    }

    public boolean isEmpty() {
        synchronized (mGeckoStates) {
            return mGeckoStates.isEmpty();
        }
    }

    /**
     * Updates the icon for any incognito tab matching the given URL.
     *
     * @return true if at least one incognito tab matched (caller should
     *         skip disk persistence), false if no match (not an incognito tab).
     */
    public boolean updateIcon(String icon, String originUrl) {
        boolean found = false;
        synchronized (mGeckoStates) {
            for (GeckoState state : mGeckoStates) {
                if (originUrl != null && originUrl.equalsIgnoreCase(state.getEntityUri())) {
                    state.setEntityIcon(icon);
                    found = true;
                }
            }
        }
        if (found) notifyTabs();
        return found;
    }

    // ── Mutation ─────────────────────────────────────────────────────

    public void setGeckoState(GeckoState geckoState, boolean active) {
        boolean changed = false;
        synchronized (mGeckoStates) {
            if (!mGeckoStates.contains(geckoState)) {
                mGeckoStates.add(geckoState);
                changed = true;
            }
            if (active && geckoState.getEntityId() != mCurrentId) {
                // Skip the iterate-and-set-active sweep when this state is
                // already current. Mirrors the regular GeckoStateDataRepository
                // fix: TabsFragment-style switches fire setGeckoState twice
                // per tap, the second call did a redundant pass.
                mCurrentId = geckoState.getEntityId();
                for (GeckoState state : mGeckoStates) {
                    state.setActive(state.getEntityId() == mCurrentId);
                }
                changed = true;
            } else if (active) {
                // Same-tab reactivate: keep the GeckoSession side in sync
                // even when the iterate sweep is skipped. See sibling fix
                // in GeckoStateDataRepository for the full rationale.
                geckoState.setActive(true);
            }
        }
        if (changed) notifyTabs();
    }

    public void closeGeckoState(GeckoState geckoState) {
        mGeckoMediaController.onTabClosed(geckoState.getEntityId());
        geckoState.clearCachedThumb();
        // Dismiss any prompt dialog still open for this tab (mirrors
        // the regular-repo path; the incognito repo lacks an undo so
        // the session closes immediately downstream).
        geckoState.dismissActivePrompt();
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
        // No archive — incognito tabs are simply discarded
        notifyTabs();
    }

    /**
     * Closes all incognito tabs and destroys their GeckoSessions.
     * Call when the user explicitly closes all incognito tabs or on app exit.
     *
     * <p>Also clears the ephemeral tracking-permission exceptions so no
     * incognito browsing data survives.</p>
     *
     * <p>GeckoSession.close() must be called on the main thread. If called
     * from a background thread, the sessions are posted to main for cleanup.</p>
     */
    public void deleteAll() {
        mGeckoMediaController.clearMedia();
        mTrackingRepository.clear();
        mWasmAllowlistRepository.clear();
        List<GeckoState> toClose;
        synchronized (mGeckoStates) {
            toClose = new ArrayList<>(mGeckoStates);
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
            new android.os.Handler(Looper.getMainLooper()).post(() -> {
                for (GeckoState state : toClose) {
                    state.dismissActivePrompt();
                    state.closeGeckoSession();
                }
            });
        }

        mGeckoStatesLiveData.postValue(Collections.emptyList());
        mCountLiveData.postValue(0);
    }

    // ── Notification ─────────────────────────────────────────────────

    public void notifyTabs() {
        List<GeckoStateEntity> entities;
        synchronized (mGeckoStates) {
            entities = mGeckoStates.stream()
                    .map(state -> {
                        GeckoStateEntity copy = new GeckoStateEntity(state.getGeckoStateEntity());
                        copy.setCachedThumb(state.getCachedThumb());
                        return copy;
                    })
                    .collect(Collectors.toList());
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            mGeckoStatesLiveData.setValue(entities);
            mCountLiveData.setValue(entities.size());
        } else {
            mGeckoStatesLiveData.postValue(entities);
            mCountLiveData.postValue(entities.size());
        }
    }
}
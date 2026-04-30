package com.solarized.firedown.geckoview.media;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.Log;

import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.utils.WebUtils;

import org.mozilla.geckoview.MediaSession;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * Manages active {@link MediaSession} instances and coordinates with
 * {@link GeckoMediaPlaybackService}.
 *
 * <h3>Fixes over previous implementation:</h3>
 * <ul>
 *   <li><b>Tab-close awareness.</b> {@link #onTabClosed(int)} must be called when a tab is
 *       removed. This ensures the service is stopped even when GeckoView never fires
 *       {@code onDeactivated} (crash, force-close, background tab removal).
 *   <li><b>{@link #onMediaPlay} owns promotion to current; {@link #onActivated}
 *       only seeds mCurrentSessionId when nothing is playing.</b>
 *   <li><b>{@link #onMetaData} guards against stealing current from an active session.</b>
 *   <li><b>{@link #clearMedia()} sends MEDIA_STOP to the service.</b>
 *   <li><b>{@link #stopMediaForSession(int)} replaces the old stopMediaId.</b>
 *   <li><b>Fallback current selection on deactivation.</b>
 * </ul>
 */
@Singleton
public class GeckoMediaController {

    private static final String TAG = "GeckoMediaController";

    // GeckoView session callbacks fire on the UI thread in practice, but
    // service / lifecycle callers (ApplicationLifeCycleHandler#onActivityDestroyed,
    // GeckoMediaPlaybackService) can reach this from other threads. Use concurrent
    // collections + volatile scalars so iterations don't throw CME and visibility
    // is guaranteed across threads.
    private final Map<Integer, GeckoMetaData>     mMetaMap    = new ConcurrentHashMap<>();
    private final Map<Integer, GeckoMediaSession>  mSessionMap = new ConcurrentHashMap<>();
    // Tracks sessions that have fired onPlay without a paired onPause/onStop.
    // mCurrentSessionId follows actual playback rather than mere registration,
    // so opening a background tab with autoplay can't hijack the notification
    // away from a tab that is actually playing.
    private final Set<Integer> mPlayingSessionIds = ConcurrentHashMap.newKeySet();

    private final Context mContext;
    private volatile MediaSession.PositionState mPositionState;
    private volatile int mCurrentSessionId;

    private final MutableLiveData<Set<Integer>> mActiveSessionIds = new MutableLiveData<>(Collections.emptySet());

    @Inject
    public GeckoMediaController(@ApplicationContext Context context) {
        this.mContext = context;
    }


    // ── Observable active sessions ───────────────────────────────────────────────────────────────

    /**
     * Observe this from your Fragment/Activity to update the tab media indicator.
     * Lifecycle-aware — no manual cleanup needed in onDestroyView.
     */
    public LiveData<Set<Integer>> getActiveSessionIdsLiveData() {
        return mActiveSessionIds;
    }

    /**
     * Returns a snapshot of session IDs that currently have active media.
     */
    public Set<Integer> getActiveSessionIds() {
        Set<Integer> value = mActiveSessionIds.getValue();
        return value != null ? value : Collections.emptySet();
    }

    private void notifyMediaSessionsChanged() {
        mActiveSessionIds.postValue(Collections.unmodifiableSet(new HashSet<>(mSessionMap.keySet())));
    }


    // ── Lifecycle control ─────────────────────────────────────────────────────────────────────────

    /**
     * Clears all tracked sessions/metadata AND stops the playback service.
     * Previously only cleared maps, leaving the foreground service alive.
     */
    public void clearMedia() {
        Log.d(TAG, "clearMedia — stopping service");
        mMetaMap.clear();
        mSessionMap.clear();
        mPlayingSessionIds.clear();
        mCurrentSessionId = 0;
        mPositionState = null;
        stopService();
        notifyMediaSessionsChanged();
    }

    /**
     * Called when a tab is being closed. Cleans up media state for the given session ID
     * and stops the service if it was the current session.
     *
     * <p>This is the critical missing link: {@code GeckoStateDataRepository.closeGeckoState()}
     * and {@code deleteAll()} must call this so the notification is always cleaned up.
     */
    public void onTabClosed(int sessionId) {
        Log.d(TAG, "onTabClosed: id=" + sessionId + " current=" + mCurrentSessionId);

        boolean wasCurrent = (mCurrentSessionId == sessionId);
        boolean hadSession = mSessionMap.containsKey(sessionId);
        mPlayingSessionIds.remove(sessionId);

        if (wasCurrent) {
            //Don't set mCurrentSessionId = 0 before stop();
            stop();
            // Hand off to whatever else is still playing rather than tearing
            // down the service. Only stop the service when nothing's left.
            int fallback = pickPlayingFallback();
            mCurrentSessionId = fallback;
            mPositionState = null;
            if (fallback == 0) {
                stopService();
            }
        }

        //Don't remove sessionId before stop;
        mMetaMap.remove(sessionId);
        mSessionMap.remove(sessionId);

        if (hadSession) {
            notifyMediaSessionsChanged();
        }
    }

    /**
     * Stops the service for a given session. Unlike the old {@code stopMediaId} which
     * silently returned on ID mismatch, this always cleans up and stops when appropriate.
     */
    public void stopMediaForSession(int sessionId) {
        Log.d(TAG, "stopMediaForSession: id=" + sessionId + " current=" + mCurrentSessionId);

        boolean wasCurrent = (mCurrentSessionId == sessionId);
        boolean hadSession = mSessionMap.containsKey(sessionId);
        mPlayingSessionIds.remove(sessionId);

        mMetaMap.remove(sessionId);
        mSessionMap.remove(sessionId);

        if (wasCurrent) {
            int fallback = pickPlayingFallback();
            mCurrentSessionId = fallback;
            mPositionState = null;
            if (fallback == 0) {
                stopService();
            }
        }

        if (hadSession) {
            notifyMediaSessionsChanged();
        }
    }

    // ── GeckoView MediaSession callbacks ──────────────────────────────────────────────────────────

    public void onPositionChange(MediaSession mediaSession, GeckoState geckoState,
                                 MediaSession.PositionState state) {
        int sessionId = geckoState.getEntityId();
        boolean isNew = !mSessionMap.containsKey(sessionId);
        ensureSessionExists(mediaSession, sessionId);
        if (sessionId == mCurrentSessionId) {
            mPositionState = state;
        }
        if (isNew) {
            notifyMediaSessionsChanged();
        }
    }

    public void onActivated(MediaSession mediaSession, GeckoState geckoState) {

        int sessionId = geckoState.getEntityId();

        Log.d(TAG, "onActivated sessionId: " + sessionId);

        if (!mMetaMap.containsKey(sessionId)) {
            GeckoMetaData meta = new GeckoMetaData();
            meta.setUrl(WebUtils.getDomainName(geckoState.getEntityUri()));
            meta.setTitle(geckoState.getEntityTitle());
            meta.setSessionId(sessionId);
            meta.setIcon(geckoState.getEntityIcon());
            mMetaMap.put(sessionId, meta);
        }

        boolean isNew = !mSessionMap.containsKey(sessionId);
        ensureSessionExists(mediaSession, sessionId);
        // Don't hijack the spotlight from a tab that's actually playing.
        // mCurrentSessionId follows real playback state via onMediaPlay; activation
        // only seeds the field on first registration.
        if (mCurrentSessionId == 0) {
            mCurrentSessionId = sessionId;
        }

        if (isNew) {
            notifyMediaSessionsChanged();
        }
    }

    /**
     * Called when the session transitions to PLAYING. The actively playing
     * session takes ownership of the foreground notification.
     */
    public void onMediaPlay(MediaSession mediaSession, GeckoState geckoState) {
        int sessionId = geckoState.getEntityId();
        boolean isNew = !mSessionMap.containsKey(sessionId);
        ensureSessionExists(mediaSession, sessionId);
        mPlayingSessionIds.add(sessionId);
        mCurrentSessionId = sessionId;
        if (isNew) {
            notifyMediaSessionsChanged();
        }
    }

    /**
     * Called when the session transitions to PAUSED or STOPPED. If the paused
     * session was holding the spotlight, hand off to whatever is still
     * playing; otherwise leave mCurrentSessionId so notification controls
     * still target the user's last-played session for a quick resume.
     */
    public void onMediaPauseOrStop(MediaSession mediaSession, GeckoState geckoState) {
        int sessionId = geckoState.getEntityId();
        boolean isNew = !mSessionMap.containsKey(sessionId);
        ensureSessionExists(mediaSession, sessionId);
        mPlayingSessionIds.remove(sessionId);
        if (mCurrentSessionId == sessionId && !mPlayingSessionIds.isEmpty()) {
            mCurrentSessionId = mPlayingSessionIds.iterator().next();
        }
        if (isNew) {
            notifyMediaSessionsChanged();
        }
    }

    private int pickPlayingFallback() {
        if (mPlayingSessionIds.isEmpty()) return 0;
        return mPlayingSessionIds.iterator().next();
    }

    public void onDeactivated(GeckoState geckoState) {
        int sessionId = geckoState.getEntityId();
        Log.d(TAG, "onDeactivated: id=" + sessionId + " current=" + mCurrentSessionId);

        boolean hadSession = mSessionMap.containsKey(sessionId);

        mPlayingSessionIds.remove(sessionId);
        mMetaMap.remove(sessionId);
        mSessionMap.remove(sessionId);

        if (mCurrentSessionId == sessionId) {
            mCurrentSessionId = findFallbackSessionId();
            if (mCurrentSessionId == 0) {
                stopService();
            }
        }

        if (hadSession) {
            notifyMediaSessionsChanged();
        }
    }

    /**
     * Only promotes mCurrentSessionId if there's no current session or this IS the current.
     * Prevents background tabs from hijacking the active session's notification.
     */
    public void onMetaData(MediaSession.Metadata meta, GeckoState geckoState) {
        int sessionId = geckoState.getEntityId();
        GeckoMetaData data = mMetaMap.get(sessionId);

        if (data == null) {
            mMetaMap.put(sessionId, new GeckoMetaData(meta, sessionId));
        } else {
            data.setAlbum(meta.album);
            data.setArtist(meta.artist);
            data.setTitle(meta.title);
        }

        // Guard: only promote if no current session or same session
        if (mCurrentSessionId == 0 || mCurrentSessionId == sessionId) {
            mCurrentSessionId = sessionId;
        }
    }

    // ── Playback control ──────────────────────────────────────────────────────────────────────────

    public boolean isActive() {
        GeckoMediaSession session = mSessionMap.get(mCurrentSessionId);
        return session != null && session.getMediaSession().isActive();
    }

    public void play()  {
        executeOnCurrent(s -> s.getMediaSession().play());
    }

    public void pause() {
        executeOnCurrent(s -> s.getMediaSession().pause());
    }

    public void stop()  {
        executeOnCurrent(s -> s.getMediaSession().stop());
    }

    // ── Bitmap ────────────────────────────────────────────────────────────────────────────────────

    public void setBitmap(Bitmap bitmap, GeckoState geckoState) {
        int sessionId = geckoState.getEntityId();
        GeckoMetaData data = mMetaMap.get(sessionId);
        if (data != null) {
            data.setBitmap(bitmap);
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────────────────────────

    public GeckoMetaData getGeckoMetaData() { return mMetaMap.get(mCurrentSessionId); }
    public MediaSession.PositionState getPositionState() { return mPositionState; }
    public int getCurrentSessionId() { return mCurrentSessionId; }
    public boolean isCurrentSession(int id) { return mCurrentSessionId == id; }
    public boolean hasSession(int sessionId) { return mSessionMap.containsKey(sessionId); }

    // ── Internals ─────────────────────────────────────────────────────────────────────────────────

    private void ensureSessionExists(MediaSession session, int id) {
        // putIfAbsent avoids the check-then-put race; the caller has already
        // computed isNew via containsKey before calling, so a concurrent winner
        // here just means we drop the loser's GeckoMediaSession instance.
        mSessionMap.putIfAbsent(id, new GeckoMediaSession(session, id));
    }

    private void executeOnCurrent(MediaAction action) {
        GeckoMediaSession session = mSessionMap.get(mCurrentSessionId);
        if (session != null) {
            action.run(session);
        } else {
            Log.w(TAG, "executeOnCurrent: no session for id=" + mCurrentSessionId);
        }
    }

    /**
     * Finds a fallback session from remaining sessions, or 0 if none remain.
     */
    private int findFallbackSessionId() {
        if (mSessionMap.isEmpty()) return 0;
        for (Integer id : mMetaMap.keySet()) {
            if (mSessionMap.containsKey(id)) return id;
        }
        return mSessionMap.keySet().iterator().next();
    }

    /**
     * Sends MEDIA_STOP to the playback service.
     */
    private void stopService() {
        if(GeckoMediaPlaybackService.isRunning()){
            Intent intent = new Intent(mContext, GeckoMediaPlaybackService.class);
            intent.setAction(IntentActions.MEDIA_STOP);
            mContext.startService(intent);
        }
    }

    private interface MediaAction {
        void run(GeckoMediaSession session);
    }
}
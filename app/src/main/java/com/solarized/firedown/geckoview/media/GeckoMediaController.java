package com.solarized.firedown.geckoview.media;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.Log;

import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.utils.WebUtils;

import org.mozilla.geckoview.Image;
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
import android.text.TextUtils;

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

    // Observables for the *single* current session id + its play state.
    // The home media strip observes these to decide what to render (and
    // whether to render at all). Distinct from mActiveSessionIds (the
    // set used by the tabs grid's audio indicator) because the strip
    // cares about exactly one session — the one whose metadata feeds
    // the foreground notification.
    private final MutableLiveData<Integer> mCurrentSessionIdLive = new MutableLiveData<>(0);
    private final MutableLiveData<Boolean> mIsPlayingLive = new MutableLiveData<>(false);

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

    /**
     * Current playing/paused session id. 0 means no media surface is
     * up — observers should hide their card / strip.
     */
    public LiveData<Integer> getCurrentSessionIdLiveData() {
        return mCurrentSessionIdLive;
    }

    /**
     * True iff the current session is actively playing (vs paused /
     * stopped but still 'spotlit' so the notification controls work
     * for a quick resume).
     */
    public LiveData<Boolean> getIsPlayingLiveData() {
        return mIsPlayingLive;
    }

    private void notifyMediaSessionsChanged() {
        mActiveSessionIds.postValue(Collections.unmodifiableSet(new HashSet<>(mSessionMap.keySet())));
    }

    /**
     * Re-publish the single-session observables. Called from every
     * mutation point that touches mCurrentSessionId or
     * mPlayingSessionIds so a Fragment observer sees state changes
     * the moment they happen.
     */
    private void notifyCurrentSessionChanged() {
        int current = mCurrentSessionId;
        mCurrentSessionIdLive.postValue(current);
        mIsPlayingLive.postValue(current != 0 && mPlayingSessionIds.contains(current));
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
        notifyCurrentSessionChanged();
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
        notifyCurrentSessionChanged();
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
        notifyCurrentSessionChanged();
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

        ensureMetaExists(sessionId, geckoState);

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
        notifyCurrentSessionChanged();
    }

    /**
     * Called when the session transitions to PLAYING. The actively playing
     * session takes ownership of the foreground notification.
     */
    public void onMediaPlay(MediaSession mediaSession, GeckoState geckoState) {
        int sessionId = geckoState.getEntityId();
        boolean isNew = !mSessionMap.containsKey(sessionId);
        ensureSessionExists(mediaSession, sessionId);
        ensureMetaExists(sessionId, geckoState);
        mPlayingSessionIds.add(sessionId);
        mCurrentSessionId = sessionId;
        if (isNew) {
            notifyMediaSessionsChanged();
        }
        notifyCurrentSessionChanged();
        // Controller-driven service start: guarantees the notification appears
        // whenever media actually plays, even if this onPlay fired before the
        // tab became current (fresh start / resume / tab switch) — the case the
        // gated BrowserFragment observer misses with no recovery.
        refreshService();
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
        notifyCurrentSessionChanged();
        // Update the notification to paused (or hand off to a still-playing
        // fallback session) without depending on the gated observer.
        refreshService();
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
        notifyCurrentSessionChanged();
    }

    /**
     * The page's metadata for a session: title, artist, album and the
     * artwork, fetched HERE — not in a BrowserFragment observer. That
     * observer runs only while the tab is current and the browser UI is
     * alive, and a MediaSession-API page sends its metadata the moment the
     * clip loads: before the tab became current on a fresh open, and from
     * the background when the next clip autoplays. Every such metadata
     * reached the map without its picture, and the service's fallback had
     * no URL to fetch either (the entry was created from the metadata, with
     * no page facts), so the notification carried a letter tile or the
     * previous clip's picture. Only promotes mCurrentSessionId if there is
     * no current session or this IS the current one, so a background tab
     * cannot hijack the active session's notification.
     */
    public void onMetaData(MediaSession.Metadata meta, GeckoState geckoState) {
        int sessionId = geckoState.getEntityId();
        // The page's facts first (domain, favicon): the service's fallback
        // artwork needs them when the metadata precedes the play.
        ensureMetaExists(sessionId, geckoState);
        GeckoMetaData data = mMetaMap.get(sessionId);
        if (data == null) {
            return;
        }
        boolean changed = !TextUtils.equals(data.getTitle(), meta.title)
                || !TextUtils.equals(data.getArtist(), meta.artist);
        data.setAlbum(meta.album);
        data.setArtist(meta.artist);
        if (!TextUtils.isEmpty(meta.title)) {
            data.setTitle(meta.title);
        }
        if (changed) {
            // Another clip: its predecessor's picture must not stay on it.
            data.setBitmap(null);
            data.setIconBitmap(null);
        }
        fetchArtwork(meta.artwork, data);

        // Guard: only promote if no current session or same session
        if (mCurrentSessionId == 0 || mCurrentSessionId == sessionId) {
            mCurrentSessionId = sessionId;
        }
        notifyCurrentSessionChanged();
        if (changed) {
            // A running notification shows the new title now, not at the
            // next position or play event.
            sendToService(IntentActions.MEDIA_METADATA);
        }
    }

    /**
     * Resolves the page's artwork and pushes a {@code MEDIA_METADATA}
     * refresh to the playback service — from the controller, with the
     * application context, so a fragment detached meanwhile cannot null
     * the Context (shipped once as {@code NullPointerException:
     * Context.getPackageName()} inside a {@code GeckoResult} callback) and
     * a background start is try/caught in {@link #sendToService(String)}.
     * The size is the notification's artwork size; the 48 px once asked for
     * here was a favicon, blurred across the lock screen's artwork slot.
     */
    private void fetchArtwork(Image artwork, GeckoMetaData data) {
        if (artwork == null) {
            return;
        }
        try {
            artwork.getBitmap(GeckoMetaData.ARTWORK_SIZE).then(bitmap -> {
                if (bitmap != null) {
                    data.setBitmap(bitmap);
                    data.setIconBitmap(bitmap);
                    sendToService(IntentActions.MEDIA_METADATA);
                }
                return null;
            }, error -> {
                Log.w(TAG, "fetchArtwork: " + error);
                return null;
            });
        } catch (Image.ImageProcessingException e) {
            Log.w(TAG, "fetchArtwork", e);
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────────────────────────

    public GeckoMetaData getGeckoMetaData() { return mMetaMap.get(mCurrentSessionId); }

    /**
     * Same as {@link #getGeckoMetaData()} but for a caller-supplied
     * session id, so the caller can snapshot the id once and then
     * read metadata for that exact session — protecting against TOCTOU
     * where mCurrentSessionId changes between the two reads (tab
     * close, deactivate, switch).
     */
    public GeckoMetaData getGeckoMetaData(int sessionId) { return mMetaMap.get(sessionId); }
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

    /**
     * Lazy-creates a {@link GeckoMetaData} entry seeded from the
     * GeckoState (URL, title, favicon) when nothing has been mapped
     * for this session yet. Critical for {@link #onMediaPlay} — when
     * onPlay fires before onActivated (or onActivated's earlier entry
     * was wiped by an onDeactivated between page transitions), the
     * playback service reads {@link #getGeckoMetaData()} and would
     * see null, then shut itself down, never showing the
     * notification. Seeding here guarantees a meta entry exists by
     * the time the service's onStartCommand reads from the map.
     */
    private void ensureMetaExists(int sessionId, GeckoState geckoState) {
        if (mMetaMap.containsKey(sessionId)) return;
        GeckoMetaData meta = new GeckoMetaData();
        meta.setUrl(WebUtils.getDomainName(geckoState.getEntityUri()));
        meta.setTitle(geckoState.getEntityTitle());
        meta.setSessionId(sessionId);
        meta.setIcon(geckoState.getEntityIcon());
        mMetaMap.put(sessionId, meta);
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

    /**
     * Sends MEDIA_STOP to the playback service.
     */
    private void stopService() {
        sendToService(IntentActions.MEDIA_STOP);
    }

    /**
     * Start (or update) the playback service with the given action, **directly
     * from the controller** — not via a BrowserFragment observer.
     *
     * <p>Why this exists: the foreground notification is shown by the service in
     * response to a {@code MEDIA_PLAY} intent. That intent used to be sent only
     * by {@code BrowserFragment.onMediaPlay}, which {@code GeckoComponents} fires
     * only when {@code isCurrentGeckoState(geckoState)} is true at the instant
     * {@code onPlay} arrives. So an {@code onPlay} that fired before its tab
     * became the current one — a fresh start / process-restore autoplay, a resume,
     * or a tab switch where the current-id update hadn't propagated yet — left
     * media playing with **no notification and no recovery** ({@code onPosition}
     * bails when the service isn't running, {@code onMetadata} only updates an
     * existing one). The controller always knows the truly-playing session
     * ({@link #mCurrentSessionId} / {@link #mPlayingSessionIds}) and has already
     * seeded the metadata, so it is the reliable place to start the service.
     *
     * <p>{@code MEDIA_PLAY} starts the service; any other action is a no-op unless
     * it's already running — we don't spin a service up just to show a paused
     * notification. Wrapped in try/catch for the Android 8+/12+ background-start
     * restriction (when there's no foreground context we couldn't show a
     * notification anyway).
     */
    private void sendToService(String action) {
        if (!IntentActions.MEDIA_PLAY.equals(action) && !GeckoMediaPlaybackService.isRunning()) {
            return;
        }
        try {
            Intent intent = new Intent(mContext, GeckoMediaPlaybackService.class);
            intent.setAction(action);
            mContext.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "sendToService failed for " + action, e);
        }
    }

    /**
     * Reflect the current playback state onto the service notification: PLAY when
     * the spotlit session is actually playing (starts the service if needed),
     * otherwise PAUSE (updates the notification only if the service is already up).
     */
    private void refreshService() {
        if (mCurrentSessionId != 0 && mPlayingSessionIds.contains(mCurrentSessionId)) {
            sendToService(IntentActions.MEDIA_PLAY);
        } else {
            sendToService(IntentActions.MEDIA_PAUSE);
        }
    }

    private interface MediaAction {
        void run(GeckoMediaSession session);
    }
}
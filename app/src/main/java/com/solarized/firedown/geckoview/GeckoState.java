package com.solarized.firedown.geckoview;

import android.graphics.Bitmap;
import android.net.Uri;
import android.text.TextUtils;
import android.webkit.URLUtil;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.solarized.firedown.data.entity.CertificateInfoEntity;
import com.solarized.firedown.data.entity.ContextElementEntity;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.utils.UrlStringUtils;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebResponse;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class GeckoState {

    private static final String TAG = GeckoState.class.getSimpleName();

    private static final int THUMB_SCALE_DIVISOR = 2;

    public static final int NULL_SESSION_ID = 0;

    private Bitmap mCachedThumb;

    private String mCookieHeader = "";

    private GeckoSession mGeckoSession;

    private GeckoSession.PromptDelegate.AutocompleteRequest<?> mAutoCompleteRequest;

    private GeckoSession.PromptDelegate.FilePrompt mFilePrompt;

    private WebResponse mWebResponse;

    private ContextElementEntity mContextElementEntity;

    /**
     * Wall-clock time of the last NavigationDelegate.onLocationChange
     * for this session. Used by the Play Store redirect blocker to
     * tell apart a "redirector" page (one that fires a Play Store
     * navigation within a few seconds of loading, without user input)
     * from a legitimate page where the user clicked a Play Store
     * link after spending time on it. 0 until the first onLocationChange.
     */
    private long mLastNavigationTime;

    /**
     * Visit id of the page currently shown in this tab. Captures are stamped
     * with this value at capture time, so "the page you're on now" is the set
     * of captures whose visit id equals this. It is NOT a raw onLocationChange
     * counter — see {@link #updateVisit}: it only moves when the page
     * <em>identity</em> ({@link #pageKeyTail}) actually changes, and it is
     * <em>restored</em> (not re-allocated) when you navigate back to a page you
     * already visited in this tab, so that page's earlier captures float again
     * without needing to be re-captured. Starts at 0 (no page / home).
     */
    private int mVisitId;

    /**
     * High-water mark for visit-id allocation in this tab. A genuinely new page
     * gets {@code ++mMaxVisitId}; a revisit reuses the id stored in
     * {@link #mVisitIdByKey}. Never decreases.
     */
    private int mMaxVisitId;

    /**
     * Page identity of the page {@link #mVisitId} currently points at — the
     * value last written to {@link #mCurrentPageKey}. Used to recognise that a
     * stream of onLocationChange callbacks (SPA pushState, tracking-param
     * mutations like YouTube's {@code &pp=…}) are all the same logical page so
     * they don't each allocate a new id. null until the first navigation.
     */
    private String mCurrentPageKey;

    /**
     * Per-tab memo of pageKey → the visit id that page was first given, so
     * navigating back/forward (or re-clicking a link) to a previously seen page
     * re-anchors to its original id instead of stranding its captures under a
     * stale id. Lives for the life of the tab.
     */
    private final Map<String, Integer> mVisitIdByKey = new HashMap<>();

    /**
     * Query parameters that carry no page identity — pure tracking / UI / SPA
     * noise. Dropped from {@link #pageKeyTail} so adding them (e.g. YouTube
     * appending {@code &pp=…} after load) doesn't read as a new page. Matched
     * case-insensitively; {@code utm_*} is handled by prefix.
     */
    private static final Set<String> NOISE_PARAMS = new HashSet<>(Arrays.asList(
            "pp", "feature", "si", "t", "ab_channel", "fbclid", "gclid",
            "ref", "ref_src", "ref_url", "ref_source", "spm", "cmpid",
            "igshid", "gi", "context", "app", "embeds_referring_euri"));

    /**
     * Original index in {@link GeckoStateDataRepository#mGeckoStates}
     * captured at the moment {@code closeGeckoState} removes this state,
     * so an undo-on-close (the snackbar action in {@code TabsFragment})
     * can re-insert the tab at its prior position instead of appending
     * to the end of the list. -1 means "no pending restore"; setting
     * any non-negative value here is a transient hint consumed once by
     * the next {@code setGeckoState} call that re-adds this state.
     */
    private int mPendingRestoreIndex = -1;

    /**
     * Action to invoke once when this state next becomes inactive via
     * {@link #setActive(boolean) setActive(false)}. {@code null} when
     * no action is registered. Used by {@code GeckoPromptManager} to
     * dismiss any open prompt dialog on tab switch.
     */
    @Nullable
    private Runnable mOnDeactivateAction;

    /**
     * URI of a user-committed load ({@code BrowserFragment.openUri} → {@code loadUri})
     * that Gecko has not yet STARTED (no {@code onPageStart} observed since it was
     * issued). While this is set, an {@code onLocationChange} whose url differs is by
     * definition a STALE commit of the <em>previous</em> load racing the user's new
     * navigation — Gecko's docshell cancels the in-flight load the moment the new one
     * reaches {@code InternalLoad}, so once the new load has started (onPageStart) the
     * old one can never commit again. Fenix needs no such flag only because its toolbar
     * never writes optimistically; ours does ({@code applyOpenUriUi}), so the stale
     * commit window (loadUri issued → docshell processes it) must be guarded or the
     * old page's commit overwrites the entity URI + toolbar with the abandoned URL.
     *
     * <p><b>ONE-SHOT by design — over-suppression is worse than a transient repaint.</b>
     * The guard consumes itself on the FIRST location change it sees (match → process
     * normally, the user's load committed same-document with no onPageStart; mismatch
     * → suppress that single stale event), and is also cleared by onPageStart,
     * onPageStop, and an onLoadRequest deny. A persistent flag wedged shut when the
     * committed load produced NO progress events at all (same-document {@code #fragment}
     * navigation, or a typed deeplink denied by onLoadRequest on a quiescent page) and
     * then silently swallowed every later SPA/pushState location change on the tab —
     * frozen toolbar, stale entity URI/history/capture attribution. The worst case of
     * one-shot is one stale repaint that the new load's own commit corrects.
     *
     * <p><b>Scope:</b> armed only by the user-load entry points that write the toolbar
     * optimistically ({@code openUri} / {@code setGeckoViewSession}'s fresh-session
     * load). History navigation ({@code goBack}/{@code goForward}) and {@code reload()}
     * deliberately do NOT arm it — they paint nothing optimistically, so a late commit
     * from a load they cancelled is at worst a transient correct-at-the-time repaint,
     * not a clobbered user intent.
     */
    @Nullable
    private String mPendingUserLoadUri;

    /**
     * Whether this tab currently has a page load in flight, tracked UNGATED from
     * {@code ProgressDelegate.onPageStart}/{@code onPageStop} (and cleared on
     * crash/kill) — i.e. per-tab truth, unlike the foreground-gated START/STOP
     * observer notifications. Read by {@code BrowserFragment.openSession} so a
     * tab switch onto a tab that started loading while backgrounded (its START
     * was gated out and will never re-fire) still pins the bars for the rest of
     * the load.
     */
    private boolean mLoading;

    private final GeckoStateEntity mGeckoStateEntity;

    /**
     * Per-page running counts of trackers blocked by GeckoView's
     * ContentBlocking pipeline, bucketed via {@link TrackingCategory}.
     * Reset on each {@code ProgressDelegate.onPageStart}; consumed by
     * the security bottom sheet to surface what protection actually did.
     */
    private final EnumMap<TrackingCategory, Integer> mBlockedTrackerCounts =
            new EnumMap<>(TrackingCategory.class);

    /**
     * Per-page deduped list of blocked hosts, bucketed by category so the
     * detail sheet can drill into "which domains were blocked". Keyed by
     * lowercase host (a tracker fires N times across the page from the
     * same domain — we count each, but only need one row per host).
     * LinkedHashMap so iteration matches first-seen order, which is what
     * the user remembers ("Facebook fired first when I scrolled to the
     * comments section"). Capped at {@link #MAX_BLOCKED_HOSTS_PER_CATEGORY}
     * per category — tracker-heavy news/sports sites can produce hundreds
     * of unique hosts and we don't want this growing without bound.
     */
    private static final int MAX_BLOCKED_HOSTS_PER_CATEGORY = 200;
    private final EnumMap<TrackingCategory, LinkedHashMap<String, Integer>> mBlockedTrackerHosts =
            new EnumMap<>(TrackingCategory.class);

    public GeckoState(GeckoStateEntity geckoStateEntity){
        mGeckoStateEntity = geckoStateEntity;
    }

    public void closeGeckoSession() {
        if(mGeckoSession != null)
            mGeckoSession.close();
        setCachedThumb(null);
    }

    /**
     * Closes the current GeckoSession AND nulls out the cached reference.
     * The next {@link #getOrCreateGeckoSession()} will construct a brand-new
     * {@link GeckoSession} and re-call {@code restoreState()} (which queues
     * auto-navigation to the last history entry).
     *
     * <p>Use after the underlying content process is gone but the tab
     * itself should come back — onKill (OS reclaim), onCrash (renderer
     * crash). The plain {@link #closeGeckoSession()} only calls
     * {@code close()}; it leaves {@code mGeckoSession} non-null so a
     * subsequent reopen via {@code mGeckoSession.open()} does NOT replay
     * the queued restoreState (that only fires on a fresh construction),
     * which is why kills/crashes used to leave tabs blank on return.</p>
     */
    public void discardGeckoSession() {
        if (mGeckoSession != null) {
            mGeckoSession.close();
            mGeckoSession = null;
        }
        setCachedThumb(null);
    }

    public void setEntityIncognito(boolean value){
        mGeckoStateEntity.setIncognito(value);
    }
    public void goBack(){
        if(mGeckoSession != null) mGeckoSession.goBack();
    }

    public void goForward(){
        if(mGeckoSession != null) mGeckoSession.goForward();
    }

    public void exitFullScreen(){
        if(mGeckoSession != null) mGeckoSession.exitFullScreen();
    }

    public void setEntityExternal(boolean value){
        mGeckoStateEntity.setExternal(value);
    }

    public boolean isExternal(){
        return mGeckoStateEntity.isExternal();
    }

    public void setContextElementEntity(ContextElementEntity mContextElementEntity) {
        this.mContextElementEntity = mContextElementEntity;
    }

    public ContextElementEntity getContextElementEntity() {
        return mContextElementEntity;
    }

    public void reload(){
        // isOpen guard: a killed-but-not-discarded session (ref non-null,
        // !isOpen — e.g. after onKill before discardGeckoSession runs) would
        // otherwise silently swallow reload. Reopen via the normal attach
        // path instead of no-opping on a dead session.
        if(mGeckoSession == null || !mGeckoSession.isOpen()){
            return;
        }
        if(isInitialLoad()){
            mGeckoSession.loadUri(getEntityUri());
        }else{
            mGeckoSession.reload();
        }
    }


    public void stop(){
        // Same isOpen guard as reload(): stop() on a dead session is a no-op
        // in the engine, so don't pretend it did anything.
        if(mGeckoSession == null || !mGeckoSession.isOpen()){
            return;
        }
        mGeckoSession.stop();
    }

    public String getCookieHeader() {
        return mCookieHeader;
    }

    public void setCookieHeader(String value) {
        mCookieHeader = value;
    }

    public void setTabId(int tabId){
        mGeckoStateEntity.setTabId(tabId);
    }

    public int getTabId(){
        return mGeckoStateEntity.getTabId();
    }

    public void setPendingRestoreIndex(int index) {
        mPendingRestoreIndex = index;
    }

    public int consumePendingRestoreIndex() {
        int index = mPendingRestoreIndex;
        mPendingRestoreIndex = -1;
        return index;
    }

    public GeckoSession getGeckoSession(){
        return mGeckoSession;
    }


    public GeckoSession getOrCreateGeckoSession() {
        if (mGeckoSession == null) {
            boolean deskTop = mGeckoStateEntity.isDesktop();
            boolean trackingProtection = mGeckoStateEntity.useTrackingProtection();
            boolean incognito = mGeckoStateEntity.isIncognito();

            GeckoSessionSettings.Builder settingsBuilder = new GeckoSessionSettings.Builder();
            settingsBuilder
                    .usePrivateMode(incognito)                    // ← was hardcoded false
                    .suspendMediaWhenInactive(false)
                    .useTrackingProtection(trackingProtection)
                    .viewportMode(
                            deskTop
                                    ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                                    : GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                    .userAgentMode(
                            deskTop
                                    ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                                    : GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
            mGeckoSession = new GeckoSession(settingsBuilder.build());

            // Don't restore session state for incognito tabs
            if (!incognito) {
                GeckoSession.SessionState sessionState =
                        GeckoSession.SessionState.fromString(mGeckoStateEntity.getSessionState());
                if (sessionState != null) mGeckoSession.restoreState(sessionState);
            }
        }
        return mGeckoSession;
    }

    public void setSearchMode(boolean value){
        mGeckoStateEntity.setSearchMode(value);
    }

    public boolean isSearchMode(){
        return mGeckoStateEntity.isSearchMode();
    }

    public GeckoStateEntity getGeckoStateEntity() {
        return mGeckoStateEntity;
    }


    public boolean isIncognito(){
        return mGeckoStateEntity.isIncognito();
    }
    public void setInitialLoad(boolean value){
        mGeckoStateEntity.setInitialLoad(value);
    }

    public boolean isInitialLoad(){
        return mGeckoStateEntity.isInitialLoad();
    }

    public void setPromptDisplaying(boolean value){
        mGeckoStateEntity.setPromptDisplaying(value);
    }

    public boolean isPromptDisplaying(){
        return mGeckoStateEntity.isPromptDisplaying();
    }

    public void setTrackingProtection(boolean value){
        if(mGeckoSession != null){
            mGeckoSession.getSettings().setUseTrackingProtection(value);
            mGeckoSession.reload();
        }
        mGeckoStateEntity.setUseTrackingProtection(value);
    }

    public void setCertificateState(CertificateInfoEntity certificateInfoEntity){
        mGeckoStateEntity.setCertificateState(certificateInfoEntity);
    }

    public void setFirstContentFulPaint(boolean value){
        mGeckoStateEntity.setFirstContentFulPaint(value);
    }

    public boolean isFirstContentFulPaint(){
        return mGeckoStateEntity.isFirstContentFulPaint();
    }

    public void setHome(boolean home){
        mGeckoStateEntity.setHome(home);
        if(home){
            setActive(true);
            setEntityTitle(null);
            setEntityUri(null);
            setEntityIcon(null);
        }
    }

    public String getPreview(){
        return mGeckoStateEntity.getPreview();
    }

    public void setPreview(String preview){
        mGeckoStateEntity.setPreview(preview);
    }

    public String getEntityTitle() {
        String mTitle = mGeckoStateEntity.getTitle();
        return mTitle == null || mTitle.isEmpty() ? "about:blank" : mTitle;
    }

    public boolean hasPreviousSession() {
        int previousId = mGeckoStateEntity.getParentId();
        return previousId != NULL_SESSION_ID;
    }

    public int getEntityParentId() {
        return mGeckoStateEntity.getParentId();
    }

    public void setActive(boolean active){
        if (!active && mOnDeactivateAction != null) {
            // A prompt dialog is open for this tab. The dialog is a
            // global UI overlay (AlertDialog.show()) — not tied to the
            // GeckoView surface — so without this dismiss it would
            // remain on top after a tab switch, appearing to belong to
            // whatever tab the user switched to. The action stored by
            // GeckoPromptManager invokes dialog.dismiss(), and the
            // dialog's OnDismissListener in PromptViewFactory then
            // routes prompt.dismiss() back to Gecko (so the page
            // resolves rather than hanging on a pending PromptResponse)
            // and clears setPromptDisplaying(false).
            //
            // Clear the field before running the action so a recursive
            // call (e.g. the dismiss listener triggering another
            // setActive(false)) is a no-op rather than re-entering.
            Runnable action = mOnDeactivateAction;
            mOnDeactivateAction = null;
            action.run();
        }
        if(mGeckoSession != null) mGeckoSession.setActive(active);
        mGeckoStateEntity.setActive(active);
    }

    /**
     * Hook invoked once when this state next transitions to inactive
     * via {@link #setActive(boolean) setActive(false)}. Used by
     * {@code GeckoPromptManager} to dismiss any open prompt dialog when
     * the user switches away from the tab that opened it. Setting the
     * action does not retain it across multiple deactivations — it
     * fires once and resets. Pass {@code null} to clear without firing.
     */
    public void setOnDeactivateAction(@Nullable Runnable action) {
        mOnDeactivateAction = action;
    }

    /**
     * Invoke and clear the deactivate action without flipping the
     * {@code isActive} flag. Used by {@code closeGeckoState} on the
     * tab-close path — where the state is being removed entirely and
     * we still need to dismiss any open prompt dialog, but the close
     * logic downstream reads {@link #isActive()} to decide whether to
     * promote a parent tab. Tab-switch paths use {@link #setActive}
     * which both flips the flag and fires the action.
     */
    public void dismissActivePrompt() {
        if (mOnDeactivateAction == null) return;
        Runnable action = mOnDeactivateAction;
        mOnDeactivateAction = null;
        action.run();
    }

    public boolean isActive(){
        return mGeckoStateEntity.isActive();
    }

    public CertificateInfoEntity getCertificateState(){
        return mGeckoStateEntity.getCertificateState();
    }

    public void setEntityTitle(String title) {
        mGeckoStateEntity.setTitle(title);
    }

    public String getEntityUri() {
        return mGeckoStateEntity.getUri();
    }


    public long getCreationDate(){
        return mGeckoStateEntity.getCreationDate();
    }

    // ── Pending user load (see mPendingUserLoadUri) ──────────────────────────

    public void setPendingUserLoadUri(@Nullable String uri) {
        mPendingUserLoadUri = uri;
    }

    @Nullable
    public String getPendingUserLoadUri() {
        return mPendingUserLoadUri;
    }

    public void clearPendingUserLoad() {
        mPendingUserLoadUri = null;
    }

    // ── Per-tab load state (see mLoading) ────────────────────────────────────

    public void setLoading(boolean loading) {
        mLoading = loading;
    }

    public boolean isLoading() {
        return mLoading;
    }


    public void onLocationChange(@NonNull String uri) {
        if(URLUtil.isValidUrl(uri) && !URLUtil.isAboutUrl(uri))
            mGeckoStateEntity.setUri(uri);
        mLastNavigationTime = System.currentTimeMillis();
        updateVisit(uri);
    }

    /**
     * Move {@link #mVisitId} to track the page now loading, but only when the
     * page <em>identity</em> ({@link #pageKeyTail}) changed — not on every
     * onLocationChange. SPA churn (pushState, {@code &pp=…} tracking-param
     * mutations) keeps the same pageKey and is ignored, so one logical page
     * keeps one id no matter how many location callbacks it fires.
     *
     * <p>The pageKey change is the only signal we gate on. We deliberately do
     * NOT also require a user gesture: a gesture filters the churn no better
     * than the pageKey check already does, and it would skip back/forward
     * history navigations (the back button fires onLocationChange with
     * gesture=false), which is exactly the "watch A → watch B → back to A"
     * case we need to re-anchor.
     *
     * <p>Re-anchoring: a page seen before in this tab restores its original id
     * (via {@link #mVisitIdByKey}) instead of allocating a new one — this is
     * what makes back-to-A float A's earlier captures again without
     * re-capturing them.
     */
    private void updateVisit(String uri) {
        String host = normalizedHost(uri);
        if (host == null) return;                       // unparseable / opaque → keep anchor

        String key = host + pageKeyTail(uri);
        if (key.equals(mCurrentPageKey)) return;        // same logical page → churn, ignore

        mCurrentPageKey = key;

        Integer known = mVisitIdByKey.get(key);
        if (known != null) {
            mVisitId = known;                           // revisit → re-anchor to original id
        } else {
            mVisitId = ++mMaxVisitId;                   // genuinely new page
            mVisitIdByKey.put(key, mVisitId);
        }
    }

    /** Lower-cased host with a leading {@code www.} or {@code m.} stripped, so
     *  the mobile and desktop spellings of the same site compare equal. null
     *  for opaque/relative/non-hierarchical URIs. */
    private static String normalizedHost(String url) {
        try {
            String h = Uri.parse(url).getHost();
            if (h == null) return null;
            h = h.toLowerCase(Locale.ROOT);
            if (h.startsWith("www.")) return h.substring(4);
            if (h.startsWith("m.")) return h.substring(2);
            return h;
        } catch (Exception e) {
            return null;
        }
    }

    /** Path + identity-bearing query (noise params dropped, remainder sorted
     *  for stability) — everything after the host that distinguishes one page
     *  from another. The fragment is ignored entirely. */
    private static String pageKeyTail(String url) {
        try {
            Uri u = Uri.parse(url);
            StringBuilder sb = new StringBuilder();
            String path = u.getPath();
            if (path != null) sb.append(path);

            TreeMap<String, String> keep = new TreeMap<>();
            for (String name : u.getQueryParameterNames()) {
                if (name == null || name.isEmpty()) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith("utm_") || NOISE_PARAMS.contains(lower)) continue;
                keep.put(name, u.getQueryParameter(name));
            }
            if (!keep.isEmpty()) {
                char sep = '?';
                for (Map.Entry<String, String> e : keep.entrySet()) {
                    sb.append(sep).append(e.getKey()).append('=').append(e.getValue());
                    sep = '&';
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Current navigation-visit id for this tab. See {@link #mVisitId}. */
    public int getVisitId() {
        return mVisitId;
    }

    /** Wall-clock time of the most recent onLocationChange. See
     *  {@link #mLastNavigationTime} for the use case. */
    public long getLastNavigationTime() {
        return mLastNavigationTime;
    }

    public void setEntityState(GeckoSession.SessionState sessionState){
        mGeckoStateEntity.setSessionState(sessionState.toString());
    }

    public void setEntityState(String sessionState){
        mGeckoStateEntity.setSessionState(sessionState);
    }

    public void setEntityParentId(int id){
        mGeckoStateEntity.setParentId(id);
    }

    public void setEntityFullScreen(boolean fullScreen){
        mGeckoStateEntity.setFullScreen(fullScreen);
    }

    public void setEntityCanGoForward(boolean canGoForward){
        mGeckoStateEntity.setCanGoForward(canGoForward);
    }

    public void setEntityCanGoBackward(boolean canGoBackward){
        mGeckoStateEntity.setCanGoBackward(canGoBackward);
    }

    public void setEntityDesktop(boolean desktop){
        if(mGeckoSession != null){
            mGeckoSession.getSettings().setViewportMode(
                    desktop
                            ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                            : GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
        }
        mGeckoStateEntity.setDesktop(desktop);
    }

    public String getEntityState(){
        return mGeckoStateEntity.getSessionState();
    }

    public GeckoSession.SessionState getState(){
        return GeckoSession.SessionState.fromString(mGeckoStateEntity.getSessionState());
    }

    public void setEntityPreview(String preview){
        mGeckoStateEntity.setPreview(preview);
    }

    public void setEntityUri(String uri) {
        if(!UrlStringUtils.isAboutBlank(uri))
            mGeckoStateEntity.setUri(uri);
    }

    public void setEntityIcon(String icon){
        mGeckoStateEntity.setIcon(icon);
    }

    public String getEntityIcon() {
        return mGeckoStateEntity.getIcon();
    }

    public String getEntityThumb() {
        return mGeckoStateEntity.getThumb();
    }

    public void setEntityThumb(String mThumb) {
        mGeckoStateEntity.setThumb(mThumb);
    }

    public void setEntityId(int id){
        mGeckoStateEntity.setId(id);
    }

    public int getEntityId() {
        return mGeckoStateEntity.getId();
    }

    public boolean isHome(){
        return mGeckoStateEntity.isHome();
    }

    public boolean canGoForward() {
        return mGeckoStateEntity.canGoForward();
    }

    public boolean canGoBackward() {
        return mGeckoStateEntity.canGoBackward();
    }

    public boolean isDesktop() {
        return mGeckoStateEntity.isDesktop();
    }

    public boolean isFullScreen() {
        return mGeckoStateEntity.isFullScreen();
    }

    public int getEntityIconResolution() {
        return mGeckoStateEntity.getIconResolution();
    }

    public void setPendingAutoCompleteRequest(GeckoSession.PromptDelegate.AutocompleteRequest<?> request){
        mAutoCompleteRequest = request;
    }

    public void setPendingFilePrompt(GeckoSession.PromptDelegate.FilePrompt filePrompt){
        mFilePrompt = filePrompt;
    }

    public GeckoSession.PromptDelegate.AutocompleteRequest<?> getAutoCompleteRequest() {
        return mAutoCompleteRequest;
    }

    public GeckoSession.PromptDelegate.FilePrompt getFilePrompt(){
        return mFilePrompt;
    }

    public void setWebResponse(WebResponse mWebResponse) {
        this.mWebResponse = mWebResponse;
    }

    public WebResponse getWebResponse() {
        return mWebResponse;
    }

    /**
     * Increment the bucket matching {@code antiTrackingMask}, returning
     * {@code true} if the count actually changed (the mask resolved to
     * a tracked category). Lets the caller decide whether to bother
     * notifying observers.
     *
     * <p>Main-thread only — the backing {@link EnumMap} is not synchronized.
     * Safe today because GeckoView's
     * {@code ContentBlocking.Delegate.onContentBlocked} (the only caller)
     * is annotated {@code @UiThread} in the runtime, and the security
     * sheet's snapshot reader runs on the main thread too.
     */
    @UiThread
    public boolean incrementBlockedTracker(int antiTrackingMask, @Nullable String uri) {
        TrackingCategory category = TrackingCategory.fromAntiTrackingMask(antiTrackingMask);
        if (category == null) return false;
        Integer current = mBlockedTrackerCounts.get(category);
        mBlockedTrackerCounts.put(category, current == null ? 1 : current + 1);
        recordBlockedHost(category, uri);
        return true;
    }

    /**
     * Cross-site cookie rejections come through a different field on
     * {@code ContentBlocking.BlockEvent} ({@code getCookieBehaviorCategory})
     * and need their own bucket so the visible count matches what users
     * intuit by "cross-site cookies blocked".
     *
     * <p>Main-thread only — see {@link #incrementBlockedTracker(int, String)}.
     */
    @UiThread
    public boolean incrementBlockedCookie(@Nullable String uri) {
        Integer current = mBlockedTrackerCounts.get(TrackingCategory.CROSS_SITE_COOKIES);
        mBlockedTrackerCounts.put(TrackingCategory.CROSS_SITE_COOKIES,
                current == null ? 1 : current + 1);
        recordBlockedHost(TrackingCategory.CROSS_SITE_COOKIES, uri);
        return true;
    }

    /** Main-thread only — see {@link #incrementBlockedTracker(int, String)}. */
    @UiThread
    public void resetBlockedTrackerCounts() {
        mBlockedTrackerCounts.clear();
        mBlockedTrackerHosts.clear();
    }

    /**
     * @return an unmodifiable snapshot suitable for passing to LiveData;
     * keys present in the map have non-zero counts.
     *
     * <p>Main-thread only — see {@link #incrementBlockedTracker(int, String)}.
     */
    @UiThread
    public Map<TrackingCategory, Integer> getBlockedTrackerCountsSnapshot() {
        if (mBlockedTrackerCounts.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new EnumMap<>(mBlockedTrackerCounts));
    }

    /**
     * @return an ordered (host → block-count) map for the given category,
     * preserving first-seen order, never null. Hosts are lowercase,
     * stripped of port. Caller can iterate to render the detail sheet.
     *
     * <p>Main-thread only — see {@link #incrementBlockedTracker(int, String)}.
     */
    @UiThread
    @NonNull
    public Map<String, Integer> getBlockedTrackerHostsSnapshot(@NonNull TrackingCategory category) {
        LinkedHashMap<String, Integer> hosts = mBlockedTrackerHosts.get(category);
        if (hosts == null || hosts.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(hosts));
    }

    /**
     * Extract the host from the BlockEvent's resource URI and bump the
     * per-host count under the resolved category. Skips URIs we can't
     * parse a host from (data:, about:, malformed) — those are rare and
     * not worth showing to the user.
     */
    @UiThread
    private void recordBlockedHost(@NonNull TrackingCategory category, @Nullable String uri) {
        if (TextUtils.isEmpty(uri)) return;
        String host;
        try {
            host = Uri.parse(uri).getHost();
        } catch (Exception e) {
            return;
        }
        if (TextUtils.isEmpty(host)) return;
        host = host.toLowerCase();

        LinkedHashMap<String, Integer> hosts = mBlockedTrackerHosts.get(category);
        if (hosts == null) {
            hosts = new LinkedHashMap<>();
            mBlockedTrackerHosts.put(category, hosts);
        }
        Integer existing = hosts.get(host);
        if (existing != null) {
            hosts.put(host, existing + 1);
            return;
        }
        if (hosts.size() >= MAX_BLOCKED_HOSTS_PER_CATEGORY) {
            // Cap to bound memory on tracker-heavy pages. The count remains
            // accurate (mBlockedTrackerCounts already incremented); we just
            // stop recording new domains past the cap. The cap is per
            // category so a noisy CROSS_SITE_COOKIES doesn't crowd out
            // FINGERPRINTERS in the detail sheet.
            return;
        }
        hosts.put(host, 1);
    }


    public void setCachedThumb(Bitmap bitmap) {
        mCachedThumb = bitmap;
    }

    public Bitmap getCachedThumb() {
        return mCachedThumb;
    }

    public void clearCachedThumb() {
        mCachedThumb = null;
    }

    /**
     * Scales a bitmap down for thumbnail use. Reduces memory by ~94%
     * (1/4 width × 1/4 height = 1/16 pixel count).
     * Returns the scaled bitmap; the caller should recycle the original
     * if it's no longer needed.
     */
    public static Bitmap scaleThumbnail(Bitmap source) {
        if (source == null) return null;
        int targetWidth = Math.max(1, source.getWidth() / THUMB_SCALE_DIVISOR);
        int targetHeight = Math.max(1, source.getHeight() / THUMB_SCALE_DIVISOR);
        Bitmap scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
        if (scaled != source) {
            source.recycle();
        }
        return scaled;
    }
}

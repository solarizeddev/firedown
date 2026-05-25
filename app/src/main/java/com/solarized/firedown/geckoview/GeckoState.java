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

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

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
        if(mGeckoSession == null){
            return;
        }
        if(isInitialLoad()){
            mGeckoSession.loadUri(getEntityUri());
        }else{
            mGeckoSession.reload();
        }
    }


    public void stop(){
        if(mGeckoSession == null){
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
        if(mGeckoSession != null) mGeckoSession.setActive(active);
        mGeckoStateEntity.setActive(active);
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


    public void onLocationChange(@NonNull String uri) {
        if(URLUtil.isValidUrl(uri) && !URLUtil.isAboutUrl(uri))
            mGeckoStateEntity.setUri(uri);
        mLastNavigationTime = System.currentTimeMillis();
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

package com.solarized.firedown.geckoview;

import android.graphics.Bitmap;
import android.webkit.URLUtil;
import androidx.annotation.NonNull;

import com.solarized.firedown.data.entity.CertificateInfoEntity;
import com.solarized.firedown.data.entity.ContextElementEntity;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.utils.UrlStringUtils;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebResponse;

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

    private final GeckoStateEntity mGeckoStateEntity;

    public GeckoState(GeckoStateEntity geckoStateEntity){
        mGeckoStateEntity = geckoStateEntity;
    }

    public void closeGeckoSession() {
        if(mGeckoSession != null)
            mGeckoSession.close();
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

package com.solarized.firedown.data.entity;

import android.graphics.Bitmap;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.solarized.firedown.data.TabState;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.utils.UrlStringUtils;

import java.util.UUID;


public class GeckoStateEntity implements TabState, Parcelable {

    private int mId;

    private int mTabId;

    private CertificateInfoEntity mCertificateState;

    private String mTitle;

    private String mUri;

    private String mThumb;

    private String mIcon;

    private String mPreview;

    private String mSessionState;

    private int mParentId;

    private boolean isActive;

    private boolean isFirstContentFulPaint;

    private long mCreationDate;

    boolean isFullScreen;

    boolean canGoForward;

    boolean canGoBackward;

    boolean isDesktop;

    boolean isInitialLoad;

    boolean enableHome;

    boolean isExternal;

    boolean isPromptDisplaying;

    boolean isSearchMode;

    boolean useTrackingProtection;

    int iconResolution;

    transient Bitmap cachedThumb;

    boolean isIncognito;

    protected GeckoStateEntity(Parcel in) {
        mCertificateState = in.readParcelable(CertificateInfoEntity.class.getClassLoader());
        mTitle = in.readString();
        mUri = in.readString();
        mThumb = in.readString();
        mIcon = in.readString();
        mPreview = in.readString();
        mSessionState = in.readString();
        mParentId = in.readInt();
        mCreationDate = in.readLong();
        mId = in.readInt();
        mTabId = in.readInt();
        isActive = in.readByte() != 0;
        isFirstContentFulPaint = in.readByte() != 0;
        isFullScreen = in.readByte() != 0;
        canGoForward = in.readByte() != 0;
        canGoBackward = in.readByte() != 0;
        isDesktop = in.readByte() != 0;
        isInitialLoad = in.readByte() != 0;
        enableHome = in.readByte() != 0;
        isExternal = in.readByte() != 0;
        isPromptDisplaying = in.readByte() != 0;
        isSearchMode = in.readByte() != 0;
        useTrackingProtection = in.readByte() != 0;
        iconResolution = in.readInt();
        isIncognito = in.readByte() != 0;
    }

    public static final Creator<GeckoStateEntity> CREATOR = new Creator<>() {
        @Override
        public GeckoStateEntity createFromParcel(Parcel in) {
            return new GeckoStateEntity(in);
        }

        @Override
        public GeckoStateEntity[] newArray(int size) {
            return new GeckoStateEntity[size];
        }
    };

    public void setFirstContentFulPaint(boolean mFirstContentFulPaint) {
        this.isFirstContentFulPaint = mFirstContentFulPaint;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public void setCreationDate(long mDate) {
        this.mCreationDate = mDate;
    }

    public void setUseTrackingProtection(boolean useTrackingProtection) {
        this.useTrackingProtection = useTrackingProtection;
    }

    public void setInitialLoad(boolean initialLoad) {
        isInitialLoad = initialLoad;
    }

    public boolean isInitialLoad() {
        return isInitialLoad;
    }

    public void setIcon(String mIcon) {
        this.mIcon = mIcon;
    }

    public void setId(int mId) {
        this.mId = mId;
    }

    public void setPromptDisplaying(boolean promptDisplaying) {
        isPromptDisplaying = promptDisplaying;
    }


    public boolean isIncognito() {
        return isIncognito;
    }

    public void setIncognito(boolean incognito) {
        isIncognito = incognito;
    }

    public boolean isPromptDisplaying() {
        return isPromptDisplaying;
    }

    public void setSessionState(String mSessionState) {
        this.mSessionState = mSessionState;
    }

    public void setThumb(String mThumb) {
        this.mThumb = mThumb;
    }

    public void setTitle(String mTitle) {
        this.mTitle = mTitle;
    }

    public void setUri(String mUri) {
        if(UrlStringUtils.isURLLike(mUri))
            setHome(false);
        this.mUri = mUri;
    }

    public void setTabId(int mTabId) {
        this.mTabId = mTabId;
    }

    public void setCertificateState(CertificateInfoEntity certificateInfoEntity) {
        this.mCertificateState = certificateInfoEntity;
    }


    public void setHome(boolean home) {
        enableHome = home;
    }

    public void setCanGoBackward(boolean backward) {
        canGoBackward = backward;
    }

    public void setCanGoForward(boolean forward) {
        canGoForward = forward;
    }

    public void setFullScreen(boolean fullScreen) {
        isFullScreen = fullScreen;
    }

    public void setDesktop(boolean desktop) {
        isDesktop = desktop;
    }

    public void setPreview(String mPreview) {
        this.mPreview = mPreview;
    }

    public int getParentId() {
        return mParentId;
    }

    public void setParentId(int previousSession) {
        mParentId = previousSession;
    }

    public void setExternal(boolean external) {
        isExternal = external;
    }

    public boolean isExternal() {
        return isExternal;
    }

    public void setSearchMode(boolean searchMode) {
        isSearchMode = searchMode;
    }

    public void setIconResolution(int iconResolution) {
        this.iconResolution = iconResolution;
    }

    public void setCachedThumb(Bitmap bitmap) {
        this.cachedThumb = bitmap;
    }

    public Bitmap getCachedThumb() {
        return cachedThumb;
    }

    public void reset(){
        setHome(true);
        setIcon(null);
        setUri(null);
        setActive(true);
    }

    @Override
    public boolean isSearchMode() {
        return isSearchMode;
    }

    @Override
    public int getId() {
        return mId;
    }

    @Override
    public String getTitle() {
        if(mTitle == null)
            return "";
        return mTitle;
    }

    @Override
    public String getUri() {
        if(mUri == null){
            return "";
        }
        return mUri;
    }

    @Override
    public String getThumb() {
        if(mThumb == null){
            return "";
        }
        return mThumb;
    }

    @Override
    public String getPreview() {
        if(mPreview == null){
            return "";
        }
        return mPreview;
    }

    @Override
    public String getSessionState() {
        if(mSessionState == null)
            return "";
        return mSessionState;
    }

    @Override
    public String getIcon() {
        if(mIcon == null){
            return "";
        }
        return mIcon;
    }

    @Override
    public int getTabId() {
        if(mTabId == 0)
            return GeckoRuntimeHelper.DEFAULT_TAB_ID;
        return mTabId;
    }

    @Override
    public long getCreationDate() {
        return mCreationDate;
    }

    @Override
    public boolean isActive() {
        return isActive;
    }

    @Override
    public boolean isFullScreen() {
        return isFullScreen;
    }

    @Override
    public boolean canGoForward() {
        return canGoForward;
    }

    @Override
    public boolean canGoBackward() {
        return canGoBackward;
    }

    @Override
    public boolean isDesktop() {
        return isDesktop;
    }

    @Override
    public boolean useTrackingProtection() {
        return useTrackingProtection;
    }

    @Override
    public boolean isHome() {
        return enableHome;
    }

    @Override
    public boolean isFirstContentFulPaint() {
        return isFirstContentFulPaint;
    }

    @Override
    public int getIconResolution() {
        return iconResolution;
    }


    @Override
    public CertificateInfoEntity getCertificateState() {
        return mCertificateState;
    }


    public GeckoStateEntity(GeckoStateEntity geckoStateEntity){
        this.mCertificateState = geckoStateEntity.getCertificateState();
        this.mUri = geckoStateEntity.getUri();
        this.mCreationDate = geckoStateEntity.getCreationDate();
        this.mSessionState = geckoStateEntity.getSessionState();
        this.isActive = geckoStateEntity.isActive();
        this.mThumb = geckoStateEntity.getThumb();
        this.mId = geckoStateEntity.getId();
        this.mTabId = geckoStateEntity.getTabId();
        this.mIcon = geckoStateEntity.getIcon();
        this.mTitle = geckoStateEntity.getTitle();
        this.mParentId = geckoStateEntity.getParentId();
        this.mPreview = geckoStateEntity.getPreview();
        this.isFullScreen = geckoStateEntity.isFullScreen();
        this.isDesktop = geckoStateEntity.isDesktop();
        this.canGoForward = geckoStateEntity.canGoForward();
        this.canGoBackward = geckoStateEntity.canGoBackward();
        this.isInitialLoad = geckoStateEntity.isInitialLoad();
        this.enableHome = geckoStateEntity.isHome();
        this.isFirstContentFulPaint = geckoStateEntity.isFirstContentFulPaint();
        this.isExternal = geckoStateEntity.isExternal();
        this.isPromptDisplaying = geckoStateEntity.isPromptDisplaying();
        this.isSearchMode = geckoStateEntity.isSearchMode();
        this.useTrackingProtection = geckoStateEntity.useTrackingProtection();
        this.iconResolution = geckoStateEntity.getIconResolution();
        this.isIncognito = geckoStateEntity.isIncognito();
    }

    public GeckoStateEntity(boolean home){
        mId = UUID.randomUUID().hashCode();
        mCreationDate = System.currentTimeMillis();
        isInitialLoad = true;
        useTrackingProtection = true;
        enableHome = home;
    }

    public GeckoStateEntity(boolean home, String uri){
        mId = UUID.randomUUID().hashCode();
        mCreationDate = System.currentTimeMillis();
        isInitialLoad = true;
        useTrackingProtection = true;
        mUri = uri;
        enableHome = home;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mCertificateState, flags);
        dest.writeString(mTitle);
        dest.writeString(mUri);
        dest.writeString(mThumb);
        dest.writeString(mIcon);
        dest.writeString(mPreview);
        dest.writeString(mSessionState);
        dest.writeInt(mParentId);
        dest.writeLong(mCreationDate);
        dest.writeInt(mId);
        dest.writeInt(mTabId);
        dest.writeByte((byte) (isActive ? 1 : 0));
        dest.writeByte((byte) (isFirstContentFulPaint ? 1 : 0));
        dest.writeByte((byte) (isFullScreen ? 1 : 0));
        dest.writeByte((byte) (canGoForward ? 1 : 0));
        dest.writeByte((byte) (canGoBackward ? 1 : 0));
        dest.writeByte((byte) (isDesktop ? 1 : 0));
        dest.writeByte((byte) (isInitialLoad ? 1 : 0));
        dest.writeByte((byte) (enableHome ? 1 : 0));
        dest.writeByte((byte) (isExternal ? 1 : 0));
        dest.writeByte((byte) (isPromptDisplaying ? 1 : 0));
        dest.writeByte((byte) (isSearchMode ? 1 : 0));
        dest.writeByte((byte) (useTrackingProtection ? 1 : 0));
        dest.writeInt(iconResolution);
        dest.writeByte((byte) (isIncognito ? 1 : 0));
    }

    public static final class KEYS {

        public static final String TITLE = "title";

        public static final String DATE = "date";

        public static final String UPDATE = "update";

        public static final String THUMB = "thumb";

        public static final String SESSION = "session";

        public static final String PREVIEW = "preview";

        public static final String HOME = "home";

        public static final String ICON = "icon";

        public static final String ICON_RESOLUTION = "icon_resolution";

        public static final String TRACKING_PROTECTION = "tracking_protection";

        public static final String URI = "uri";

        public static final String ACTIVE = "active";

        public static final String ID = "id";

        public static final String PARENT_ID = "parent_id";

        public static final String FULLSCREEN = "fullscreen";

        public static final String BACKWARD = "backward";

        public static final String FORWARD = "forward";

        public static final String DESKTOP = "desktop";

    }
}

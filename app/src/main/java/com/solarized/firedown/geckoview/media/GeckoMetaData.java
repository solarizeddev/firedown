package com.solarized.firedown.geckoview.media;

import android.graphics.Bitmap;
import android.text.TextUtils;

import com.solarized.firedown.utils.WebUtils;

import org.apache.commons.io.FilenameUtils;
import org.mozilla.geckoview.MediaSession;

public class GeckoMetaData {

    private static final String TAG = GeckoMetaData.class.getName();

    public static final int ARTWORK_SIZE = 48;

    private int mSessionId;

    private String mTitle;

    private String mArtist;

    private String mAlbum;

    private String mUrl;

    private String mIcon;

    private Bitmap mBitmap;

    private Bitmap mIconBitmap;
    public GeckoMetaData(){

    }

    public GeckoMetaData(MediaSession.Metadata metadata, int sessionId){
        mTitle = metadata.title;
        mArtist = metadata.artist;
        mAlbum = metadata.album;
        mSessionId = sessionId;
    }

    public String getUrl() {
        return mUrl;
    }
    public void setIcon(String mIcon) {
        this.mIcon = mIcon;
    }

    public String getIcon() {
        return mIcon;
    }

    public int getSessionId() {
        return mSessionId;
    }

    public Bitmap getBitmap() {
        return mBitmap;
    }

    public Bitmap getIconBitmap() {
        return mIconBitmap;
    }

    public void setIconBitmap(Bitmap mIconBitmap) {
        this.mIconBitmap = mIconBitmap;
    }

    public String getAlbum() {
        if(TextUtils.isEmpty(mAlbum))
            return WebUtils.getDomainName(mUrl);
        return mAlbum;
    }

    public String getTitle() {
        if(TextUtils.isEmpty(mTitle))
            return FilenameUtils.getName(mUrl);
        return mTitle;
    }

    public String getArtist() {
        if(TextUtils.isEmpty(mArtist))
            return mUrl;
        return mArtist;
    }

    public void setBitmap(Bitmap bitmap) {
        mBitmap = bitmap;
    }

    public void setUrl(String mUrl) {
        this.mUrl = mUrl;
    }

    public void setAlbum(String mAlbum) {
        this.mAlbum = mAlbum;
    }

    public void setSessionId(int mSessionId) {
        this.mSessionId = mSessionId;
    }

    public void setTitle(String mTitle) {
        this.mTitle = mTitle;
    }

    public void setArtist(String mArtist) {
        this.mArtist = mArtist;
    }
}

package com.solarized.firedown;

import android.content.SharedPreferences;
import android.text.TextUtils;

import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.utils.FileUriHelper;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class Sorting {

    public static final int SORT_DATE = 0;
    public static final int SORT_SIZE = 1;
    public static final int SORT_ALPHABET = 2;
    public static final int SORT_DOMAIN = 3;

    public static final String SORT_TYPE_ALL = "all";
    public static final String SORT_TYPE_APK = "apk";
    public static final String SORT_TYPE_VIDEO = "video";
    public static final String SORT_TYPE_AUDIO = "audio";
    public static final String SORT_TYPE_DOCS = "documents";
    public static final String SORT_TYPE_IMAGES = "images";
    public static final String SORT_TYPE_GIF = "gif";
    public static final String SORT_TYPE_ZIP = "zip";
    public static final String SORT_TYPE_SUBTITLE = "subtitle";
    public static final String SORT_TYPE_SVG = "svg";

    private final SharedPreferences mSharedPreferences;

    private String mCurrentSortBrowser = SORT_TYPE_ALL;
    private String mCurrentSortDownloads = SORT_TYPE_ALL;

    @Inject
    public Sorting(
            SharedPreferences sharedPreferences
    ) {
        this.mSharedPreferences = sharedPreferences;
    }

    public int getCurrentSortLocal() {
        return mSharedPreferences.getInt(Preferences.SORT_LOCAL, SORT_DATE);
    }

    public void saveCurrentSortingLocal(int type) {
        mSharedPreferences.edit().putInt(Preferences.SORT_LOCAL, type).apply();
    }

    public void setCurrentSortBrowser(String type) {
        this.mCurrentSortBrowser = type;
    }

    public void setCurrentSortBrowser(int selectedId) {
        mCurrentSortBrowser = getCurrentSortForIds(selectedId);
    }

    public int getCurrentSortBrowserId() {
        if (SORT_TYPE_VIDEO.equals(mCurrentSortBrowser)) return R.id.chip_video;
        if (SORT_TYPE_AUDIO.equals(mCurrentSortBrowser)) return R.id.chip_audio;
        if (SORT_TYPE_GIF.equals(mCurrentSortBrowser)) return R.id.chip_gif;
        if (SORT_TYPE_IMAGES.equals(mCurrentSortBrowser)) return R.id.chip_image;
        if (SORT_TYPE_DOCS.equals(mCurrentSortBrowser)) return R.id.chip_doc;
        if (SORT_TYPE_SVG.equals(mCurrentSortBrowser)) return R.id.chip_svg;
        if (SORT_TYPE_SUBTITLE.equals(mCurrentSortBrowser)) return R.id.chip_subtitle;
        return R.id.chip_all;
    }

    public void setCurrentSortDownloadsForId(int selectedId) {
        mCurrentSortDownloads = getCurrentSortForIds(selectedId);
    }

    public String getCurrentSortForIds(int selectedId) {
        if (selectedId == R.id.chip_video) return SORT_TYPE_VIDEO;
        if (selectedId == R.id.chip_audio) return SORT_TYPE_AUDIO;
        if (selectedId == R.id.chip_image) return SORT_TYPE_IMAGES;
        if (selectedId == R.id.chip_gif) return SORT_TYPE_GIF;
        if (selectedId == R.id.chip_svg) return SORT_TYPE_SVG;
        if (selectedId == R.id.chip_apk) return SORT_TYPE_APK;
        if (selectedId == R.id.chip_doc) return SORT_TYPE_DOCS;
        if (selectedId == R.id.chip_zip) return SORT_TYPE_ZIP;
        if (selectedId == R.id.chip_subtitle) return SORT_TYPE_SUBTITLE;
        return SORT_TYPE_ALL;
    }

    public boolean getPredicateBrowser(BrowserDownloadEntity entity) {
        if (entity == null) return false;
        String mimeType = entity.getMimeType();
        return switch (mCurrentSortBrowser) {
            case SORT_TYPE_VIDEO -> FileUriHelper.isVideo(mimeType);
            case SORT_TYPE_AUDIO -> FileUriHelper.isAudio(mimeType);
            case SORT_TYPE_IMAGES -> FileUriHelper.isImage(mimeType) && !FileUriHelper.isGIF(mimeType) && !FileUriHelper.isSVG(mimeType);
            case SORT_TYPE_GIF -> FileUriHelper.isGIF(mimeType);
            case SORT_TYPE_SVG -> FileUriHelper.isSVG(mimeType);
            case SORT_TYPE_SUBTITLE -> FileUriHelper.isSubtitle(mimeType);
            default -> true;
        };
    }

    public boolean getPredicateDownloads(DownloadEntity downloadEntity) {
        if (downloadEntity == null) return false;
        String mimeType = downloadEntity.getFileMimeType();
        return switch (mCurrentSortDownloads) {
            case SORT_TYPE_DOCS -> FileUriHelper.isDoc(mimeType) && !FileUriHelper.isSVG(mimeType);
            case SORT_TYPE_AUDIO -> FileUriHelper.isAudio(mimeType);
            case SORT_TYPE_VIDEO -> FileUriHelper.isVideo(mimeType);
            case SORT_TYPE_IMAGES -> FileUriHelper.isImage(mimeType) && !FileUriHelper.isSVG(mimeType) && !FileUriHelper.isGIF(mimeType);
            case SORT_TYPE_SVG -> FileUriHelper.isSVG(mimeType);
            case SORT_TYPE_GIF -> FileUriHelper.isGIF(mimeType);
            case SORT_TYPE_ZIP -> FileUriHelper.isCompressed(mimeType);
            case SORT_TYPE_SUBTITLE -> FileUriHelper.isSubtitle(mimeType);
            case SORT_TYPE_APK -> FileUriHelper.isApk(mimeType) || FileUriHelper.isApkM(mimeType);
            default -> true;
        };
    }


    public boolean getPredicateVault(DownloadEntity downloadEntity) {
        return downloadEntity != null;
    }


    public boolean getPredicateDownloads(DownloadEntity entity, int chipId) {
        if (chipId == R.id.chip_all)
            return true;

        if (entity == null)
            return false;

        String mimeType = entity.getFileMimeType();

        if(TextUtils.isEmpty(mimeType))
            return true;

        // Logic moved from Fragment/Singleton state to pure function
        if (chipId == R.id.chip_video)
            return FileUriHelper.isVideo(mimeType);
        else if (chipId == R.id.chip_audio)
            return FileUriHelper.isAudio(mimeType);
        else if (chipId == R.id.chip_image)
            return FileUriHelper.isImage(mimeType);
        else if (chipId == R.id.chip_svg)
            return FileUriHelper.isSVG(mimeType);
        else if (chipId == R.id.chip_gif)
            return FileUriHelper.isGIF(mimeType);
        else if (chipId == R.id.chip_zip)
            return FileUriHelper.isCompressed(mimeType);
        else if (chipId == R.id.chip_doc)
            return FileUriHelper.isDoc(mimeType) && !FileUriHelper.isSVG(mimeType);
        else if (chipId == R.id.chip_subtitle)
            return FileUriHelper.isSubtitle(mimeType);
        else if (chipId == R.id.chip_apk)
            return FileUriHelper.isApk(mimeType);

        return true;
    }
}
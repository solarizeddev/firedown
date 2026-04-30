package com.solarized.firedown.data.entity;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.manager.DownloadRequest;

import java.util.ArrayList;

/**
 * Carries the user's UI action (button tap, selection) from fragments to the host activity/viewmodel.
 * Each action type populates only the fields it needs — the rest stay null.
 */
public class OptionEntity implements Parcelable {

    private String action;
    private int resId;
    private int position;

    // New download path — immutable request built from entity + selected stream
    @Nullable private DownloadRequest downloadRequest;
    @Nullable private ArrayList<DownloadRequest> downloadRequests;

    // Legacy / display — still used for non-download actions (rename, share, open)
    @Nullable private BrowserDownloadEntity browserDownloadEntity;
    @Nullable private ArrayList<BrowserDownloadEntity> browserDownloadEntities;
    @Nullable private DownloadEntity downloadEntity;

    public OptionEntity() {}

    // ========================================================================
    // Getters
    // ========================================================================

    public String getAction()                                       { return action; }
    public int getId()                                              { return resId; }
    public int getPosition()                                        { return position; }
    @Nullable public DownloadRequest getDownloadRequest()           { return downloadRequest; }
    @Nullable public ArrayList<DownloadRequest> getDownloadRequests() { return downloadRequests; }
    @Nullable public BrowserDownloadEntity getBrowserDownloadEntity() { return browserDownloadEntity; }
    @Nullable public ArrayList<BrowserDownloadEntity> getBrowserDownloadEntities() { return browserDownloadEntities; }
    @Nullable public DownloadEntity getDownloadEntity()             { return downloadEntity; }

    // ========================================================================
    // Setters
    // ========================================================================

    public void setAction(String action)                            { this.action = action; }
    public void setId(int resId)                                    { this.resId = resId; }
    public void setPosition(int position)                           { this.position = position; }
    public void setDownloadRequest(DownloadRequest request)         { this.downloadRequest = request; }
    public void setDownloadRequests(ArrayList<DownloadRequest> list) { this.downloadRequests = list; }
    public void setBrowserDownloadEntity(BrowserDownloadEntity e)   { this.browserDownloadEntity = e; }
    public void setBrowserDownloadEntities(ArrayList<BrowserDownloadEntity> list) { this.browserDownloadEntities = list; }
    public void setDownloadEntity(DownloadEntity e)                 { this.downloadEntity = e; }

    // ========================================================================
    // Parcelable
    // ========================================================================

    protected OptionEntity(Parcel in) {
        action = in.readString();
        resId = in.readInt();
        position = in.readInt();
        downloadRequest = in.readParcelable(DownloadRequest.class.getClassLoader());
        downloadRequests = in.createTypedArrayList(DownloadRequest.CREATOR);
        browserDownloadEntity = in.readParcelable(BrowserDownloadEntity.class.getClassLoader());
        browserDownloadEntities = in.createTypedArrayList(BrowserDownloadEntity.CREATOR);
        downloadEntity = in.readParcelable(DownloadEntity.class.getClassLoader());
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(action);
        dest.writeInt(resId);
        dest.writeInt(position);
        dest.writeParcelable(downloadRequest, flags);
        dest.writeTypedList(downloadRequests);
        dest.writeParcelable(browserDownloadEntity, flags);
        dest.writeTypedList(browserDownloadEntities);
        dest.writeParcelable(downloadEntity, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<OptionEntity> CREATOR = new Creator<>() {
        @Override
        public OptionEntity createFromParcel(Parcel in) {
            return new OptionEntity(in);
        }

        @Override
        public OptionEntity[] newArray(int size) {
            return new OptionEntity[size];
        }
    };
}
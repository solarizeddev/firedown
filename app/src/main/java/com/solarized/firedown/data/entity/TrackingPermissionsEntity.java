package com.solarized.firedown.data.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.solarized.firedown.data.TrackingPermission;


@Entity(tableName = "tracking")
public class TrackingPermissionsEntity implements TrackingPermission {

    @PrimaryKey
    public int uid;


    @ColumnInfo(name = "file_origin")
    private String mOrigin;


    @ColumnInfo(name = "file_date")
    private long mDate;


    @ColumnInfo(name = "file_tracking")
    private boolean isTrackingEnabled;

    @Override
    public int getId() {
        return uid;
    }

    @Override
    public String getOrigin() {
        return mOrigin;
    }

    @Override
    public long getDate() {
        return mDate;
    }

    @Override
    public boolean isTrackingEnabled() {
        return isTrackingEnabled;
    }


    public void setDate(long mDate) {
        this.mDate = mDate;
    }

    public void setId(int mId) {
        this.uid = mId;
    }

    public void setOrigin(String mOrigin) {
        this.mOrigin = mOrigin;
    }

    public void setTrackingEnabled(boolean trackingEnabled) {
        isTrackingEnabled = trackingEnabled;
    }


}

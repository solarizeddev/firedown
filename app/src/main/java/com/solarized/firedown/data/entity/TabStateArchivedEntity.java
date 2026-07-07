package com.solarized.firedown.data.entity;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.solarized.firedown.data.TabStateArchived;


@Entity(tableName = "tabstate")
public class TabStateArchivedEntity implements TabStateArchived, Parcelable {

    @PrimaryKey
    public int uid;

    @ColumnInfo(name = "file_title")
    public String mTitle;

    @ColumnInfo(name = "file_uri")
    public String mUri;

    @ColumnInfo(name = "file_icon")
    public String mIcon;

    @ColumnInfo(name = "file_state")
    public String mSessionState;

    @ColumnInfo(name = "file_date")
    public long mCreationDate;

    /**
     * Wall-clock time (ms since epoch) at which this tab was actually moved
     * to the archive. Distinct from {@link #mCreationDate}, which carries
     * the tab's lastUsed timestamp. Drives the "X tabs archived in the
     * last [interval]" banner — counting on this column gives an exact
     * answer regardless of when the auto-archive job actually ran.
     *
     * <p>Defaults to 0 for rows that pre-date this column (v1 → v2
     * migration). The banner query ignores zero values, so legacy rows
     * never show up in the windowed count.</p>
     */
    @ColumnInfo(name = "archived_at", defaultValue = "0")
    public long mArchivedAt;


    protected TabStateArchivedEntity(Parcel in) {
        uid = in.readInt();
        mTitle = in.readString();
        mUri = in.readString();
        mIcon = in.readString();
        mSessionState = in.readString();
        mCreationDate = in.readLong();
        mArchivedAt = in.readLong();
    }

    public static final Creator<TabStateArchivedEntity> CREATOR = new Creator<TabStateArchivedEntity>() {
        @Override
        public TabStateArchivedEntity createFromParcel(Parcel in) {
            return new TabStateArchivedEntity(in);
        }

        @Override
        public TabStateArchivedEntity[] newArray(int size) {
            return new TabStateArchivedEntity[size];
        }
    };


    public void setIcon(String mIcon) {
        this.mIcon = mIcon;
    }

    public void setId(int mId) {
        this.uid = mId;
    }

    public void setSessionState(String sessionState){
        this.mSessionState = sessionState;
    }

    public void setTitle(String mTitle) {
        this.mTitle = mTitle;
    }

    public void setUri(String mUri) {
        this.mUri = mUri;
    }

    public void setCreationDate(long mCreationDate) {
        this.mCreationDate = mCreationDate;
    }

    public void setArchivedAt(long archivedAt) {
        this.mArchivedAt = archivedAt;
    }

    public long getArchivedAt() {
        return mArchivedAt;
    }

    @Override
    public int getId() {
        return uid;
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
    public long getCreationDate() {
        return mCreationDate;
    }


    public TabStateArchivedEntity(TabStateArchivedEntity tabStateEntity){
        this.uid = tabStateEntity.getId();
        this.mTitle = tabStateEntity.getTitle();
        this.mUri = tabStateEntity.getUri();
        this.mIcon = tabStateEntity.getIcon();
        this.mCreationDate = tabStateEntity.getCreationDate();
        this.mSessionState = tabStateEntity.getSessionState();
        this.mArchivedAt = tabStateEntity.getArchivedAt();
    }

    public TabStateArchivedEntity(){

    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // Field order MUST mirror the Parcel constructor exactly (Parcel is
        // positional, not self-describing). This used to write creationDate
        // BEFORE sessionState while the constructor read sessionState first,
        // corrupting every unparceled entity (the delete dialog's selection).
        dest.writeInt(uid);
        dest.writeString(mTitle);
        dest.writeString(mUri);
        dest.writeString(mIcon);
        dest.writeString(mSessionState);
        dest.writeLong(mCreationDate);
        dest.writeLong(mArchivedAt);
    }

}

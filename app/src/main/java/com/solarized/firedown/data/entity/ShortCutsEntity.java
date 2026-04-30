package com.solarized.firedown.data.entity;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.solarized.firedown.data.ShortCut;

import java.util.Objects;

@Entity(tableName = "shortcuts")
public class ShortCutsEntity implements ShortCut, Parcelable {

    @PrimaryKey
    public int uid;

    @ColumnInfo(name = "file_url")
    public String fileUrl;

    @ColumnInfo(name = "file_domain")
    public String fileDomain;

    @ColumnInfo(name = "file_date")
    public long fileDate;

    @ColumnInfo(name = "file_icon")
    public String fileIcon;

    @ColumnInfo(name = "file_title")
    public String fileTitle;

    @ColumnInfo(name = "file_icon_resolution")
    public int fileIconResolution;


    protected ShortCutsEntity(Parcel in) {
        uid = in.readInt();
        fileUrl = in.readString();
        fileDomain = in.readString();
        fileDate = in.readLong();
        fileIcon = in.readString();
        fileTitle = in.readString();
        fileIconResolution = in.readInt();
    }

    public static final Creator<ShortCutsEntity> CREATOR = new Creator<>() {
        @Override
        public ShortCutsEntity createFromParcel(Parcel in) {
            return new ShortCutsEntity(in);
        }

        @Override
        public ShortCutsEntity[] newArray(int size) {
            return new ShortCutsEntity[size];
        }
    };

    @Override
    public int getId() {
        return uid;
    }


    @Override
    public String getUrl() {
        return fileUrl;
    }

    @Override
    public String getDomain() {
        return fileDomain;
    }

    @Override
    public String getIcon() {
        return fileIcon;
    }

    @Override
    public String getTitle() {
        return fileTitle;
    }

    @Override
    public long getDate() {
        return fileDate;
    }

    @Override
    public int getFileIconResolution() {
        return fileIconResolution;
    }

    public void setFileIconResolution(int fileIconResolution) {
        this.fileIconResolution = fileIconResolution;
    }

    public void setId(int id){
        uid = id;
    }

    public void setFileDate(long date){
        fileDate = date;
    }

    public void setFileUrl(String url){
        fileUrl = url;
    }

    public void setFileDomain(String fileDomain) {
        this.fileDomain = fileDomain;
    }

    public void setFileIcon(String fileIcon){
        this.fileIcon = fileIcon;
    }

    public void setFileTitle(String fileTitle) {
        this.fileTitle = fileTitle;
    }


    public ShortCutsEntity(ShortCut shortCut){
        uid = shortCut.getId();
        fileDate = shortCut.getDate();
        fileUrl = shortCut.getUrl();
        fileIcon = shortCut.getIcon();
        fileDomain = shortCut.getDomain();
        fileTitle = shortCut.getTitle();
        fileIconResolution = shortCut.getFileIconResolution();
    }

    public ShortCutsEntity(){

    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(uid);
        dest.writeString(fileUrl);
        dest.writeString(fileDomain);
        dest.writeLong(fileDate);
        dest.writeString(fileIcon);
        dest.writeString(fileTitle);
        dest.writeInt(fileIconResolution);
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ShortCutsEntity that = (ShortCutsEntity) o;
        return uid == that.uid &&
                Objects.equals(fileUrl, that.fileUrl) &&
                Objects.equals(fileTitle, that.fileTitle) &&
                Objects.equals(fileIcon, that.fileIcon) &&
                Objects.equals(fileDomain, that.fileDomain) &&
                fileIconResolution == that.fileIconResolution &&
                fileDate == that.fileDate;
    }
}

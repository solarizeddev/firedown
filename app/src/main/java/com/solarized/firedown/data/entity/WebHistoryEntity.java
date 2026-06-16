package com.solarized.firedown.data.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.solarized.firedown.data.WebHistory;

// Index on file_url: every favicon/title update keys on it
// (updateIconData / updateTitleByUrl, WHERE file_url = ?), so without an index
// each is a full table scan. The uid PK doesn't help those (uid = hash(url)+day,
// not the lookup key). See WebHistoryDatabase.MIGRATION_3_4.
@Entity(tableName = "webhistory", indices = {@Index(value = {"file_url"})})
public class WebHistoryEntity implements WebHistory {

    @PrimaryKey
    public int uid;

    @ColumnInfo(name = "file_title")
    public String fileTitle;

    @ColumnInfo(name = "file_url")
    public String fileUrl;

    @ColumnInfo(name = "file_date")
    public long fileDate;

    @ColumnInfo(name = "file_icon")
    public String fileIcon;

    @ColumnInfo(name = "file_icon_resolution")
    public int fileIconResolution;

    @Override
    public int getId() {
        return uid;
    }

    @Override
    public String getTitle() {
        return fileTitle;
    }

    @Override
    public String getIcon() {
        return fileIcon;
    }

    @Override
    public int getIconResolution() {
        return fileIconResolution;
    }

    @Override
    public String getUrl() {
        return fileUrl;
    }

    @Override
    public long getDate() {
        return fileDate;
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

    public void setFileIcon(String icon){
        fileIcon = icon;
    }

    public void setFileTitle(String title){
        fileTitle = title;
    }

    public void setFileIconResolution(int fileIconResolution) {
        this.fileIconResolution = fileIconResolution;
    }

    public WebHistoryEntity(WebHistory webHistory){
        uid = webHistory.getId();
        fileDate = webHistory.getDate();
        fileUrl = webHistory.getUrl();
        fileTitle = webHistory.getTitle();
        fileIcon = webHistory.getIcon();
        fileIconResolution = webHistory.getIconResolution();
    }

    public WebHistoryEntity(){

    }

}

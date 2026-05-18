package com.solarized.firedown.data.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.solarized.firedown.data.WebBookmark;

@Entity(tableName = "webbookmark")
public class WebBookmarkEntity implements WebBookmark {

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

    @ColumnInfo(name = "file_preview")
    public String filePreview;

    @Override
    public int getId() {
        return uid;
    }

    @Override
    public String getTitle() {
        return fileTitle;
    }

    @Override
    public String getUrl() {
        return fileUrl;
    }

    @Override
    public String getPreview() {
        return filePreview;
    }

    @Override
    public String getIcon() {
        return fileIcon;
    }

    @Override
    public long getDate() {
        return fileDate;
    }

    public void setFileDate(long date){
        fileDate = date;
    }

    public void setFileUrl(String url){
        fileUrl = url;
    }

    public void setFileTitle(String title){
        fileTitle = title;
    }

    public void setFilePreview(String preview){
        filePreview = preview;
    }

    public void setFileIcon(String icon){
        fileIcon = icon;
    }

    public void setId(int id){
        uid = id;
    }

    public WebBookmarkEntity(WebBookmark webBookmark){
        uid = webBookmark.getId();
        fileDate = webBookmark.getDate();
        fileTitle = webBookmark.getTitle();
        fileUrl = webBookmark.getUrl();
        fileIcon = webBookmark.getIcon();
        filePreview = webBookmark.getPreview();
    }

    public WebBookmarkEntity(){

    }
}

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

    /**
     * Pinned bookmarks render at the top of the bookmarks list with a
     * pin badge — replaces the old standalone 'shortcuts' concept.
     * On upgrade we copy every row from the legacy shortcuts-db
     * into this table with {@code isPinned = true}; the standalone
     * shortcuts UI is gone and pinning happens via the bookmark
     * long-press menu.
     */
    @ColumnInfo(name = "is_pinned", defaultValue = "0")
    public boolean isPinned;

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

    @Override
    public boolean isPinned() {
        return isPinned;
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

    public void setPinned(boolean pinned) {
        isPinned = pinned;
    }

    public WebBookmarkEntity(WebBookmark webBookmark){
        uid = webBookmark.getId();
        fileDate = webBookmark.getDate();
        fileTitle = webBookmark.getTitle();
        fileUrl = webBookmark.getUrl();
        fileIcon = webBookmark.getIcon();
        filePreview = webBookmark.getPreview();
        isPinned = webBookmark.isPinned();
    }

    public WebBookmarkEntity(){

    }
}

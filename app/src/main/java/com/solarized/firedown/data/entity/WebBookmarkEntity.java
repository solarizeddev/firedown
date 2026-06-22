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

    // ---- bookmarks sync (webbookmark v3) ----
    // Identity for sync is the URL-hash uid (= bookmarkIdFor(url)), which is
    // deterministic and cross-device-stable, so there is NO separate sync_id.
    // These three carry the per-URL last-writer-wins + tombstone state the merge
    // engine needs (docs/BOOKMARKS_SYNC.md). All default 0 (the annotation
    // defaultValue must match MIGRATION_2_3's column DEFAULT or Room's schema
    // validation fails).

    /** Last-modified epoch millis; bumped on create/edit/delete. */
    @ColumnInfo(name = "updated_at", defaultValue = "0")
    public long updatedAt;

    /** Tombstone flag (0 = live, 1 = deleted). Every list/read query filters
     *  {@code deleted = 0}; a user delete sets this instead of removing the row
     *  so the deletion can propagate, then a GC pass hard-deletes old tombstones. */
    @ColumnInfo(name = "deleted", defaultValue = "0")
    public int deleted;

    /** Epoch millis the tombstone was created (0 when live). */
    @ColumnInfo(name = "deleted_at", defaultValue = "0")
    public long deletedAt;

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

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isDeleted() {
        return deleted != 0;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted ? 1 : 0;
    }

    public long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(long deletedAt) {
        this.deletedAt = deletedAt;
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

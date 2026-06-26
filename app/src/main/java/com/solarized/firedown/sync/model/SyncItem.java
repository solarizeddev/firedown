package com.solarized.firedown.sync.model;

/**
 * One bookmark as it travels in the sync blob and through the merge engine. The
 * merge identity is the normalized {@link #url} (docs/BOOKMARKS_SYNC.md §0 — the
 * spec merges per URL-hash, which the app's {@code uid} already is). A deleted
 * item is a tombstone: {@link #deleted} true with a {@link #deletedAt}.
 */
public final class SyncItem {

    public final String url;       // normalized; the merge key
    public final String title;
    public final long date;        // original bookmark creation (file_date)
    public final String icon;
    public final String preview;
    public final long updatedAt;   // last-modified (live edits)
    public final boolean deleted;
    public final long deletedAt;

    public SyncItem(String url, String title, long date, String icon, String preview,
                    long updatedAt, boolean deleted, long deletedAt) {
        this.url = url;
        this.title = title;
        this.date = date;
        this.icon = icon;
        this.preview = preview;
        this.updatedAt = updatedAt;
        this.deleted = deleted;
        this.deletedAt = deletedAt;
    }

    /** The timestamp the merge compares — the moment of the last action. */
    public long effectiveTs() {
        return deleted ? Math.max(deletedAt, updatedAt) : updatedAt;
    }
}

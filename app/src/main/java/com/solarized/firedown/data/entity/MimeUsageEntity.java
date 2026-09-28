package com.solarized.firedown.data.entity;

import androidx.room.ColumnInfo;

/**
 * One row of {@code DownloadDao.getRegularUsageByMimeLive}: finished,
 * non-vault downloads grouped by mime type. The Storage screen folds these
 * into its per-type bar in Java (FileUriHelper owns the mime → type rules,
 * the same predicates the Downloads filter chips use), so the query stays a
 * handful of rows however large the download table grows.
 */
public class MimeUsageEntity {

    @ColumnInfo(name = "mime")
    public String mime;

    @ColumnInfo(name = "bytes")
    public long bytes;

    @ColumnInfo(name = "files")
    public int files;
}

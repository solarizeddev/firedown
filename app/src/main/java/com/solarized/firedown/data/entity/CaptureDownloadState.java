package com.solarized.firedown.data.entity;

import androidx.room.ColumnInfo;

/**
 * One row of {@code DownloadDao.getCaptureStatesLive}: the few columns the
 * Captured sheet needs to mark a capture as saved, downloading, pending or
 * failed. A projection rather than the whole entity because the query re-runs
 * on every write to the download table, progress ticks included.
 */
public class CaptureDownloadState {

    @ColumnInfo(name = "url")
    public String url;

    @ColumnInfo(name = "status")
    public int status;

    @ColumnInfo(name = "progress")
    public int progress;

    @ColumnInfo(name = "live")
    public boolean live;

    @ColumnInfo(name = "error_type")
    public int errorType;

    /** The picked rendition's frame size ("1280x720"), null when unknown. */
    @ColumnInfo(name = "resolution")
    public String resolution;
}

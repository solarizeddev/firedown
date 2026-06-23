package com.solarized.firedown.data;

public interface Download {


    int HEADER = -2;

    int ERROR = -1;

    int PROGRESS = 0;

    int FINISHED = 1;

    int QUEUED = 2;

    int EMPTY = 3;

    int FINISHED_GRID = 4;

    int QUEUED_GRID = 5;

    int PAUSED_GRID = 6;

    int ERROR_GRID = 7;

    int PROGRESS_GRID = 8;

    // Dense grid view types — the images-only filtered grid (denser span,
    // square bare tiles). Distinct view types rather than an adapter flag
    // read at bind time, because the RecycledViewPool keys holders by view
    // type: reusing the normal grid types across a density toggle would
    // hand back holders inflated from the wrong layout.
    int FINISHED_GRID_DENSE = 9;

    int QUEUED_GRID_DENSE = 10;

    int PAUSED_GRID_DENSE = 11;

    int ERROR_GRID_DENSE = 12;

    int PROGRESS_GRID_DENSE = 13;


    /**
     * Sentinel {@code fileProgress} value (the status stays {@link #PROGRESS})
     * meaning the bytes are fully downloaded and a post-download mux /
     * "finishing" pass is running — SABR's separate FFmpeg mux step. The row
     * renders an indeterminate "Finishing…" state instead of a frozen percent,
     * so the user isn't left staring at a bar stuck near the top while ffmpeg
     * works. 101 is outside the valid 0–100 percent range, so it can never
     * collide with a real progress value.
     */
    int PROCESSING_PROGRESS = 101;


    int getId();
    int getParentId();
    String getFileName();
    String getFileImg();
    String getFileDescription();
    String getFileMimeType();
    String getFilePath();
    String getFileUrl();
    String getFileHeaders();
    String getOriginUrl();
    String getDurationFormatted();
    String getFileLanguage();
    String getFileResolution();
    long getFileDate();
    long getFileSize();
    int getFileProgress();
    int getFileErrorType();
    int getFileType();
    int getFileStatus();
    boolean getFileIsLive();
    boolean isFileEncrypted();
    boolean isFileSafe();
    long getThumbnailDuration();
    long getDuration();
    boolean isFileThumbnailUnavailable();

}

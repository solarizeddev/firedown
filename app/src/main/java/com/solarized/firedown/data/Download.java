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

}

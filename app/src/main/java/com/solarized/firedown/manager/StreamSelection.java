package com.solarized.firedown.manager;

/**
 * Encapsulates stream selection parameters for a download.
 * Groups video/audio stream numbers and the separate audio URL
 * so FFmpegRunnable gets everything it needs in one call.
 */
public class StreamSelection {

    public static final StreamSelection EMPTY = new StreamSelection(-1, -1, null);

    private final int videoNumber;
    private final int audioNumber;
    private final String audioUrl;

    public StreamSelection(int videoNumber, int audioNumber, String audioUrl) {
        this.videoNumber = videoNumber;
        this.audioNumber = audioNumber;
        this.audioUrl = audioUrl;
    }

    public int getVideoNumber() {
        return videoNumber;
    }

    public int getAudioNumber() {
        return audioNumber;
    }

    public String getAudioUrl() {
        return audioUrl;
    }

    public boolean hasAudioUrl() {
        return audioUrl != null && !audioUrl.isEmpty();
    }
}
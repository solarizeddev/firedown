package com.solarized.firedown.geckoview.media;

import org.mozilla.geckoview.MediaSession;

public class GeckoMediaSession {

    private final MediaSession mMediaSession;

    private final int mSessionId;

    public GeckoMediaSession(MediaSession mediaSession, int sessionId){
        mMediaSession = mediaSession;
        mSessionId = sessionId;
    }

    public int getSessionId() {
        return mSessionId;
    }

    public MediaSession getMediaSession() {
        return mMediaSession;
    }
}

package com.solarized.firedown.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaylistLivenessTest {

    private static final String EVENT_LIVE = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXT-X-TARGETDURATION:3\n"
            + "#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:2.002,\nchunk_0.ts?type=event\n#EXTINF:2.002,\nchunk_1.ts?type=event\n";

    @Test
    public void eventPlaylistWithoutEndlistIsLive() {
        assertEquals(PlaylistLiveness.LIVE, PlaylistLiveness.of(EVENT_LIVE));
    }

    @Test
    public void eventPlaylistWithEndlistIsFinished() {
        assertEquals(PlaylistLiveness.FINISHED, PlaylistLiveness.of(EVENT_LIVE + "#EXT-X-ENDLIST\n"));
    }

    @Test
    public void slidingWindowIsLive() {
        String text = "#EXTM3U\r\n#EXT-X-TARGETDURATION:4\r\n#EXT-X-MEDIA-SEQUENCE:620\r\n"
                + "#EXTINF:2.987,\r\nchunk_620_b.aac?type=live\r\n";
        assertEquals(PlaylistLiveness.LIVE, PlaylistLiveness.of(text));
    }

    @Test
    public void vodTypeIsFinishedEvenWithoutEndlist() {
        String text = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:4,\na.ts\n";
        assertEquals(PlaylistLiveness.FINISHED, PlaylistLiveness.of(text));
    }

    @Test
    public void playlistWithNoSegmentsIsUnknown() {
        assertEquals(PlaylistLiveness.UNKNOWN, PlaylistLiveness.of("#EXTM3U\n#EXT-X-VERSION:3\n"));
    }

    @Test
    public void masterIsMasterAndNamesItsFirstRendition() {
        String text = "﻿#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"audio.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=16000000,RESOLUTION=3840x2160\n\n"
                + "2160/dynamic_delta.m3u8?type=live\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2750000,RESOLUTION=1280x720\n720/dynamic_delta.m3u8?type=live\n";
        assertEquals(PlaylistLiveness.MASTER, PlaylistLiveness.of(text));
        assertEquals("2160/dynamic_delta.m3u8?type=live", PlaylistLiveness.firstVariantUri(text));
    }

    @Test
    public void mediaPlaylistHasNoVariant() {
        assertNull(PlaylistLiveness.firstVariantUri(EVENT_LIVE));
    }

    @Test
    public void dashDynamicIsLiveStaticIsFinished() {
        String dynamic = "<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"\n"
                + "  type='dynamic' minimumUpdatePeriod=\"PT2S\">\n<Period/></MPD>";
        String fixed = "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\"><Period/></MPD>";
        assertEquals(PlaylistLiveness.LIVE, PlaylistLiveness.of(dynamic));
        assertEquals(PlaylistLiveness.FINISHED, PlaylistLiveness.of(fixed));
    }

    @Test
    public void otherBytesAreNotAPlaylist() {
        assertFalse(PlaylistLiveness.looksLikePlaylist("\u0000\u0000\u0000\u0018ftypmp42"));
        assertFalse(PlaylistLiveness.looksLikePlaylist("G@\u0011"));
        assertTrue(PlaylistLiveness.looksLikePlaylist("#EXTM3U\n#EXT"));
        assertTrue(PlaylistLiveness.looksLikePlaylist("﻿<?xml version"));
        assertEquals(PlaylistLiveness.UNKNOWN, PlaylistLiveness.of("<html><body>404</body></html>"));
    }
}

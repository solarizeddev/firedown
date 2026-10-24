package com.solarized.firedown.sabr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.ffmpegutils.FFmpegDownloader;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.ffmpegutils.FFmpegListener;
import com.solarized.firedown.ffmpegutils.FFmpegMetaData;
import com.solarized.firedown.ffmpegutils.FFmpegMetaDataReader;
import com.solarized.firedown.ffmpegutils.FFmpegStreamInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The inline fMP4 muxer on a DEVICE, with REAL encoded media: the JVM harness
 * proves the boxes are laid out to the spec, this proves Android's own media
 * stack and the app's bundled FFmpeg read what it writes — the two things no
 * JVM can answer (seeking on the written {@code sidx} in particular).
 *
 * <p>The fixtures under {@code assets/sabr/} are an 8 s H.264 test pattern
 * (320x180, a keyframe every second) and a 440 Hz AAC tone, as single-track
 * fragmented-MP4 segments: an init segment per track plus one fragment per
 * second, each fragment {@code styp}+{@code sidx}+{@code moof}+{@code mdat} —
 * the shape the SABR stream hands {@link Fmp4Muxer}. Each fragment is handed
 * its OWN duration, read from its {@code trun}: the stream's media header
 * names every segment's real length and the muxer sums what it is handed into
 * the patched track durations, so a flat second per fragment put nine seconds
 * on an eight-second audio track (the tone's fragments run 975–1045 ms and
 * the last is a 12 ms sliver). Regenerate with:
 * <pre>
 * ffmpeg -f lavfi -i "testsrc2=size=320x180:rate=30" -f lavfi -i "sine=frequency=440:sample_rate=44100" \
 *   -t 8 -c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p -g 30 -keyint_min 30 -sc_threshold 0 \
 *   -c:a aac -b:a 48k -ac 1 -f dash -seg_duration 1 -use_template 1 -use_timeline 0 \
 *   -init_seg_name 'init-$RepresentationID$.m4s' -media_seg_name 'chunk-$RepresentationID$-$Number%05d$.m4s' out.mpd
 * </pre>
 * then {@code init-0} → {@code video-init}, {@code chunk-0-0000N} → {@code video-0N},
 * and the same for representation 1 as {@code audio-*}.
 *
 * <p>Run:
 * {@code ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.sabr.SabrInlineMuxDeviceTest}
 * The player case plays ~8 s of tone at zero volume.
 */
@RunWith(AndroidJUnit4.class)
public class SabrInlineMuxDeviceTest {

    private static final long SEGMENT_MS = 1000;
    private static final int VIDEO_SEGMENTS = 8;
    private static final int AUDIO_SEGMENTS = 9;
    private static final long FULL_MS = 8000;
    /** Container durations round to the fragment edge; the tone's last fragment is a sliver. */
    private static final long TOLERANCE_MS = 400;
    private static final long PLAYER_STEP_MS = 100;

    private Context mApp;
    private File mDir;

    @Before
    public void setUp() {
        mApp = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        mDir = new File(mApp.getCacheDir(), "sabr-inline-device-test");
        deleteRecursively(mDir);
        assertTrue(mDir.mkdirs());
    }

    @After
    public void tearDown() {
        deleteRecursively(mDir);
    }

    /* ---------------------------------------------------------------------- */
    /* cases                                                                  */
    /* ---------------------------------------------------------------------- */

    /** A whole download: one file, read by the platform, the app's probe and the app's player, and seekable in all three. */
    @Test
    public void fullWriteIsOneSeekableFile() throws Exception {
        File file = mux("full.mp4", VIDEO_SEGMENTS, AUDIO_SEGMENTS, FULL_MS);

        String[] siblings = mDir.list();
        assertNotNull(siblings);
        assertEquals("nothing is written beside the output", 1, siblings.length);

        assertExtractorReads(file, FULL_MS, 5_000);
        assertRetrieverReads(file, FULL_MS, 5_000_000);
        assertNear("app probe (us)", FULL_MS * 1000, probeDurationUs(file), TOLERANCE_MS * 1000);
        assertPlayerPlays(file, FULL_MS, 5_000);
    }

    /** The user-Finish shape: a few fragments then close(); the result is a shorter file that plays, not a broken one. */
    @Test
    public void partialWriteIsPlayable() throws Exception {
        long writtenMs = 3 * SEGMENT_MS;
        File file = mux("partial.mp4", 3, 3, FULL_MS);

        assertExtractorReads(file, writtenMs, 1_500);
        assertRetrieverReads(file, writtenMs, 1_500_000);
        assertNear("app probe (us)", writtenMs * 1000, probeDurationUs(file), TOLERANCE_MS * 1000);
        assertPlayerPlays(file, writtenMs, 1_500);
    }

    /** The probe-failed recovery: the bundled FFmpeg stream-copies the inline file into a classic MP4 beside it, which probes and replaces it. */
    @Test
    public void remuxFallbackRewritesTheInlineFile() throws Exception {
        File file = mux("inline.mp4", VIDEO_SEGMENTS, AUDIO_SEGMENTS, FULL_MS);
        File tmp = StoragePaths.sabrRemuxTempFor(file);
        assertEquals("the remux temp sits beside the file", file.getParentFile(), tmp.getParentFile());

        FFmpegDownloader ffmpeg = new FFmpegDownloader();
        ffmpeg.addListener(new FFmpegListener() {
            @Override public void onProgress(long downloadedLength, long totalLength) { }
            @Override public void onStarted() { }
            @Override public void onFinished() { }
        });
        int rc;
        try {
            rc = ffmpeg.start(new String[]{ file.getAbsolutePath() }, (Map<String, String>) null,
                    null, tmp.getAbsolutePath(), file.length());
        } finally {
            ffmpeg.free();
        }
        assertTrue("ffmpeg stream copy failed rc=" + rc, rc >= 0);
        assertTrue("remux output missing", tmp.exists() && tmp.length() > 0);
        assertNear("remuxed probe (us)", FULL_MS * 1000, probeDurationUs(tmp), TOLERANCE_MS * 1000);
        assertExtractorReads(tmp, FULL_MS, 5_000);

        assertTrue("the copy replaces the original", tmp.renameTo(file));
        assertFalse(tmp.exists());
        assertNear("replaced file (us)", FULL_MS * 1000, probeDurationUs(file), TOLERANCE_MS * 1000);
    }

    /* ---------------------------------------------------------------------- */
    /* the muxer run                                                          */
    /* ---------------------------------------------------------------------- */

    private File mux(String name, int videoSegments, int audioSegments, long declaredMs) throws IOException {
        File out = new File(mDir, name);
        Fmp4Muxer muxer = new Fmp4Muxer(out, declaredMs);
        byte[] videoInit = asset("video-init.m4s");
        byte[] audioInit = asset("audio-init.m4s");
        int videoTimescale = mediaTimescale(videoInit);
        int audioTimescale = mediaTimescale(audioInit);
        muxer.onSegment(false, true, 0, videoInit);
        muxer.onSegment(true, true, 0, audioInit);
        int n = Math.max(videoSegments, audioSegments);
        for (int i = 1; i <= n; i++) {
            if (i <= videoSegments) {
                byte[] seg = asset(String.format("video-%02d.m4s", i));
                muxer.onSegment(false, false, fragmentDurationMs(seg, videoTimescale), seg);
            }
            if (i <= audioSegments) {
                byte[] seg = asset(String.format("audio-%02d.m4s", i));
                muxer.onSegment(true, false, fragmentDurationMs(seg, audioTimescale), seg);
            }
        }
        muxer.close();
        assertTrue("muxer wrote its header", muxer.hasHeader());
        assertFalse("muxer gave up", muxer.isFailed());
        assertTrue(out.exists() && out.length() > 0);
        return out;
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("sabr/" + name)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        }
    }

    /* ---------------------------------------------------------------------- */
    /* fragment durations — what the stream's media header hands the muxer    */
    /* ---------------------------------------------------------------------- */

    /** The track's media timescale, from the init segment's {@code mdhd}. */
    private static int mediaTimescale(byte[] init) {
        int[] mdhd = findBox(init, 0, init.length, "moov", "trak", "mdia", "mdhd");
        assertNotNull("mdhd", mdhd);
        int version = init[mdhd[0]] & 0xff;
        return (int) u32(init, mdhd[0] + (version == 1 ? 20 : 12));
    }

    /** The media time a fragment adds: its {@code trun} sample durations summed, in ms. */
    private static long fragmentDurationMs(byte[] seg, int timescale) {
        int[] moof = findBox(seg, 0, seg.length, "moof");
        assertNotNull("moof", moof);
        long ticks = 0;
        int off = moof[0];
        while (off + 8 <= moof[1]) {
            int size = (int) u32(seg, off);
            assertTrue("box size", size >= 8);
            if ("traf".equals(fourcc(seg, off + 4))) {
                ticks += trafDurationTicks(seg, off + 8, off + size);
            }
            off += size;
        }
        return Math.round(ticks * 1000.0 / timescale);
    }

    private static long trafDurationTicks(byte[] seg, int start, int end) {
        long defaultDuration = -1;
        long ticks = 0;
        int off = start;
        while (off + 8 <= end) {
            int size = (int) u32(seg, off);
            assertTrue("box size", size >= 8);
            String type = fourcc(seg, off + 4);
            int p = off + 8;
            int flags = (int) (u32(seg, p) & 0xffffff);
            p += 4;
            if ("tfhd".equals(type)) {
                p += 4;                                 // track_ID
                if ((flags & 0x01) != 0) p += 8;        // base_data_offset
                if ((flags & 0x02) != 0) p += 4;        // sample_description_index
                if ((flags & 0x08) != 0) defaultDuration = u32(seg, p);
            } else if ("trun".equals(type)) {
                long count = u32(seg, p);
                p += 4;
                if ((flags & 0x01) != 0) p += 4;        // data_offset
                if ((flags & 0x04) != 0) p += 4;        // first_sample_flags
                for (long i = 0; i < count; i++) {
                    long duration = defaultDuration;
                    if ((flags & 0x100) != 0) {
                        duration = u32(seg, p);
                        p += 4;
                    }
                    if ((flags & 0x200) != 0) p += 4;   // sample_size
                    if ((flags & 0x400) != 0) p += 4;   // sample_flags
                    if ((flags & 0x800) != 0) p += 4;   // composition offset
                    assertTrue("a sample duration", duration >= 0);
                    ticks += duration;
                }
            }
            off += size;
        }
        return ticks;
    }

    /** The payload {@code [start, end)} of the first box at {@code path} inside {@code [start, end)}, or null. */
    private static int[] findBox(byte[] b, int start, int end, String... path) {
        int s = start;
        int e = end;
        for (String name : path) {
            int[] found = null;
            int off = s;
            while (off + 8 <= e) {
                long size = u32(b, off);
                assertTrue("no 64-bit boxes in the fixtures", size != 1);
                if (size == 0) size = e - off;
                assertTrue("box size", size >= 8);
                if (name.equals(fourcc(b, off + 4))) {
                    found = new int[]{ off + 8, (int) (off + size) };
                    break;
                }
                off += (int) size;
            }
            if (found == null) return null;
            s = found[0];
            e = found[1];
        }
        return new int[]{ s, e };
    }

    private static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static String fourcc(byte[] b, int off) {
        return new String(b, off, 4, StandardCharsets.ISO_8859_1);
    }

    /* ---------------------------------------------------------------------- */
    /* the readers                                                            */
    /* ---------------------------------------------------------------------- */

    /** The platform extractor: two tracks of the expected length, and a seek on the video track that lands on the keyframe before the target. */
    private void assertExtractorReads(File file, long expectedMs, long seekMs) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            assertEquals("tracks", 2, extractor.getTrackCount());
            int videoTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                assertNotNull(mime);
                assertNear("track " + mime + " duration (us)", expectedMs * 1000,
                        format.getLong(MediaFormat.KEY_DURATION), TOLERANCE_MS * 1000);
                if (mime.startsWith("video/")) {
                    videoTrack = i;
                }
            }
            assertTrue("a video track", videoTrack >= 0);
            extractor.selectTrack(videoTrack);
            extractor.seekTo(seekMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long landed = extractor.getSampleTime();
            assertTrue("seek to " + seekMs + " ms landed at " + landed + " us",
                    landed >= (seekMs - SEGMENT_MS) * 1000 && landed <= seekMs * 1000);
        } finally {
            extractor.release();
        }
    }

    /** MediaMetadataRetriever (what the thumbnail path uses): the duration and a frame at a mid-clip position. */
    private void assertRetrieverReads(File file, long expectedMs, long frameUs) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            assertNotNull("retriever duration", duration);
            assertNear("retriever duration (ms)", expectedMs, Long.parseLong(duration), TOLERANCE_MS);
            Bitmap frame = retriever.getFrameAtTime(frameUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            assertNotNull("frame at " + frameUs + " us", frame);
            assertEquals(320, frame.getWidth());
            assertEquals(180, frame.getHeight());
        } finally {
            retriever.release();
        }
    }

    /** The app's own probe, exactly what SabrStrategy runs before it finalizes: a duration AND a video stream. */
    private long probeDurationUs(File file) throws IOException {
        FFmpegMetaDataReader reader = new FFmpegMetaDataReader();
        try {
            FFmpegMetaData meta = reader.getStreamInfo(file.getAbsolutePath(), null, false);
            assertNotNull("probe metadata", meta);
            ArrayList<FFmpegEntity> streams = reader.getStreams();
            assertNotNull("probe streams", streams);
            boolean video = false;
            for (FFmpegEntity stream : streams) {
                if (stream != null
                        && stream.getCodecType() == FFmpegStreamInfo.CodecType.VIDEO.getValue()) {
                    video = true;
                }
            }
            assertTrue("probe saw a video stream", video);
            return meta.getDuration();
        } finally {
            reader.stop();
            reader.release();
        }
    }

    /**
     * The app's player (media3): the file is SEEKABLE — which the fragmented
     * extractor says only when it found the whole-file {@code sidx} — a seek
     * lands where asked, and the clip plays to its end.
     */
    private void assertPlayerPlays(File file, long expectedMs, long seekMs) throws Exception {
        AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();
        try {
            onMain(() -> {
                ExoPlayer player = new ExoPlayer.Builder(mApp).build();
                player.setVolume(0f);
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
                player.prepare();
                playerRef.set(player);
            });
            ExoPlayer player = playerRef.get();

            awaitPlayerState(player, Player.STATE_READY, 15_000);
            AtomicLong duration = new AtomicLong();
            AtomicReference<Boolean> seekable = new AtomicReference<>(false);
            onMain(() -> {
                duration.set(player.getDuration());
                seekable.set(player.isCurrentMediaItemSeekable());
            });
            assertNear("player duration (ms)", expectedMs, duration.get(), TOLERANCE_MS);
            assertTrue("player reports the file as seekable", seekable.get());

            // A pending seek is REPORTED as the target whatever happens next,
            // so the position is read after a second of playback: a seek that
            // took lands a second past the target, a seek the extractor could
            // not honour restarts the clip and reads near zero.
            onMain(() -> player.seekTo(seekMs));
            awaitPlayerState(player, Player.STATE_READY, 10_000);
            onMain(player::play);
            Thread.sleep(1_200);
            AtomicLong position = new AtomicLong();
            onMain(() -> position.set(player.getCurrentPosition()));
            assertTrue("position a second after seeking to " + seekMs + " ms: " + position.get(),
                    position.get() >= seekMs + 300 && position.get() <= seekMs + 3_500);

            awaitPlayerState(player, Player.STATE_ENDED, expectedMs - seekMs + 20_000);
        } finally {
            ExoPlayer player = playerRef.get();
            if (player != null) {
                onMain(player::release);
            }
        }
    }

    private void awaitPlayerState(ExoPlayer player, int wanted, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        AtomicReference<Integer> state = new AtomicReference<>(Player.STATE_IDLE);
        AtomicReference<String> error = new AtomicReference<>();
        while (System.currentTimeMillis() < deadline) {
            onMain(() -> {
                state.set(player.getPlaybackState());
                error.set(player.getPlayerError() == null ? null : player.getPlayerError().getMessage());
            });
            if (error.get() != null) {
                throw new AssertionError("player error: " + error.get());
            }
            if (state.get() == wanted) {
                return;
            }
            Thread.sleep(PLAYER_STEP_MS);
        }
        throw new AssertionError("player never reached state " + wanted + " (last " + state.get() + ")");
    }

    /* ---------------------------------------------------------------------- */
    /* plumbing                                                               */
    /* ---------------------------------------------------------------------- */

    private static void onMain(Runnable r) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(r);
    }

    private static void assertNear(String what, long expected, long actual, long tolerance) {
        assertTrue(what + ": expected " + expected + " ± " + tolerance + ", got " + actual,
                Math.abs(actual - expected) <= tolerance);
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}

package com.solarized.firedown.sabr;

import android.util.Log;

import com.solarized.firedown.BuildConfig;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming fragmented-MP4 (CMAF) muxer for SABR downloads.
 *
 * <p>SABR delivers YouTube's video-only and audio-only streams as separate
 * fragmented MP4s: each is an init segment ({@code ftyp}+{@code moov}) followed
 * by media fragments ({@code moof}+{@code mdat}). This class interleaves the two
 * into ONE fragmented MP4 <b>as the segments arrive</b>, so the final file is
 * complete the instant the download ends — no second FFmpeg mux pass.
 *
 * <p><b>How:</b> buffer the two init segments; once both are in, build a single
 * {@code moov} carrying both tracks (video=1, audio=2, with a combined
 * {@code mvex}) and write {@code ftyp}+{@code moov} once. Thereafter each media
 * fragment is appended verbatim except its {@code moof}/{@code tfhd}
 * {@code track_ID} is rewritten (video→1, audio→2). The per-fragment
 * {@code moof} carries its own {@code tfdt}/{@code trun}, and each track keeps
 * its own {@code mdhd} timescale + {@code elst}, so A/V sync and AAC priming are
 * preserved — this is a pure container remux, codec bytes are never touched
 * (codec-agnostic: avc1/av01 video + mp4a audio all just ride their copied
 * {@code stsd}).
 *
 * <p><b>Duration.</b> YouTube's init {@code moov} declares the FULL clip length,
 * but we may write only a PREFIX of the fragments (the user finished early), so
 * a verbatim duration would make the player and our probe report the whole
 * length for a partial file. We can't know the written length up front (the
 * {@code moov} is at the file's head), so the header durations are written as
 * placeholders and PATCHED at {@link #close()} with the real downloaded
 * duration — accumulated from the per-segment durations SABR already computes —
 * via {@link RandomAccessFile} seek-back. (mehd, which also carries the total,
 * is dropped: the rebuilt {@code mvex} contains only the two {@code trex}.)
 *
 * <p><b>Safety / fallback:</b> this code is deliberately conservative. Anything
 * it doesn't understand — a non-ISO-BMFF init, encryption boxes, more than one
 * {@code trak} per init, a 64-bit box size, a parse anomaly, or an IO error —
 * flips {@link #isFailed()} and it stops. The caller ({@code SabrStrategy})
 * keeps the raw temp streams and, after the download, <b>probe-validates the
 * muxed output</b>; on failure (or {@link #isFailed()}) it falls back to the
 * proven FFmpeg remux of those temps. So a muxer bug can never ship a broken
 * download — at worst it degrades to the previous behavior.
 *
 * <p>Not thread-safe: only ever called from the single SABR download thread.
 */
public final class Fmp4Muxer implements SabrDownloader.SegmentSink, Closeable {

    private static final String TAG = "Fmp4Muxer";

    private static final int VIDEO_TRACK_ID = 1;
    private static final int AUDIO_TRACK_ID = 2;

    /** Media fragments that arrived before both inits — bounded; in practice the
     *  first SABR response carries both inits ahead of any media, so this stays
     *  tiny. Exceeding the cap is treated as an unexpected ordering → fail. */
    private static final int MAX_PENDING_FRAGMENTS = 16;

    private final File output;
    private RandomAccessFile raf;

    private byte[] videoInit;
    private byte[] audioInit;
    private boolean headerWritten;
    private boolean failed;

    private final List<byte[]> pendingVideo = new ArrayList<>();
    private final List<byte[]> pendingAudio = new ArrayList<>();

    // Accumulated real (downloaded) duration per track, milliseconds.
    private long videoDurationMs;
    private long audioDurationMs;

    // Located at writeHeader: absolute file offsets of the duration fields to
    // patch at close(), their widths (4/8 bytes), and the timescales needed to
    // convert ms → ticks. 0 offset = not located (skipped).
    private long movieTimescale;
    private long videoMediaTimescale;
    private long audioMediaTimescale;
    private long mvhdDurOff;   private int mvhdDurW;
    private long vTkhdDurOff;  private int vTkhdDurW;
    private long aTkhdDurOff;  private int aTkhdDurW;
    private long vMdhdDurOff;  private int vMdhdDurW;
    private long aMdhdDurOff;  private int aMdhdDurW;

    public Fmp4Muxer(File output) {
        this.output = output;
    }

    /** True once a usable interleaved file has been started (both inits parsed
     *  and {@code ftyp}+{@code moov} written). If this is false at the end, the
     *  muxer produced nothing and the caller must use the fallback. */
    public boolean isHeaderWritten() {
        return headerWritten && !failed;
    }

    /** True if the muxer gave up on anything unexpected. The caller falls back. */
    public boolean isFailed() {
        return failed;
    }

    // ---- SegmentSink ----

    @Override
    public void onSegment(boolean isAudio, boolean isInit, long durationMs, byte[] data) {
        if (failed || data == null || data.length == 0) {
            return;
        }
        try {
            if (isInit) {
                onInit(isAudio, data);
            } else {
                if (durationMs > 0) {
                    if (isAudio) audioDurationMs += durationMs;
                    else videoDurationMs += durationMs;
                }
                onMedia(isAudio, data);
            }
        } catch (Throwable t) {
            // ANY problem → give up cleanly; caller falls back to FFmpeg.
            fail("onSegment", t);
        }
    }

    private void onInit(boolean isAudio, byte[] data) throws IOException {
        if (isAudio) {
            if (audioInit == null) audioInit = data;
        } else {
            if (videoInit == null) videoInit = data;
        }
        if (!headerWritten && videoInit != null && audioInit != null) {
            writeHeader();
        }
    }

    private void onMedia(boolean isAudio, byte[] data) throws IOException {
        if (!headerWritten) {
            // Buffer until both inits land (normally only the first response's
            // media, before we've seen both inits).
            List<byte[]> pending = isAudio ? pendingAudio : pendingVideo;
            if (pendingVideo.size() + pendingAudio.size() >= MAX_PENDING_FRAGMENTS) {
                fail("media before init (buffer overflow)", null);
                return;
            }
            pending.add(data);
            return;
        }
        writeFragment(isAudio, data);
    }

    private void writeHeader() throws IOException {
        byte[] ftyp = findTopLevel(videoInit, "ftyp");
        byte[] videoMoov = findTopLevel(videoInit, "moov");
        byte[] audioMoov = findTopLevel(audioInit, "moov");
        if (ftyp == null || videoMoov == null || audioMoov == null) {
            fail("init missing ftyp/moov", null);
            return;
        }

        byte[] mvhd = findChild(videoMoov, "mvhd");
        byte[] videoTrak = findChild(videoMoov, "trak");
        byte[] audioTrak = findChild(audioMoov, "trak");
        if (mvhd == null || videoTrak == null || audioTrak == null) {
            fail("moov missing mvhd/trak", null);
            return;
        }
        // A second trak in either init means a layout we don't model — bail.
        if (hasSecondChild(videoMoov, "trak") || hasSecondChild(audioMoov, "trak")) {
            fail("multi-trak init", null);
            return;
        }
        // Encryption is out of scope (YouTube SABR isn't encrypted, but guard).
        if (containsAny(videoInit, ENCRYPTION_BOXES) || containsAny(audioInit, ENCRYPTION_BOXES)) {
            fail("encrypted init", null);
            return;
        }

        byte[] videoTrex = findTrex(videoMoov);
        byte[] audioTrex = findTrex(audioMoov);
        if (videoTrex == null || audioTrex == null) {
            fail("missing trex", null);
            return;
        }

        // Normalise both tracks to fixed IDs (video=1, audio=2): tkhd in the
        // trak, trex in mvex, and every fragment's tfhd (done in writeFragment).
        setTkhdTrackId(videoTrak, VIDEO_TRACK_ID);
        setTkhdTrackId(audioTrak, AUDIO_TRACK_ID);
        setTrexTrackId(videoTrex, VIDEO_TRACK_ID);
        setTrexTrackId(audioTrex, AUDIO_TRACK_ID);
        setNextTrackId(mvhd, AUDIO_TRACK_ID + 1);

        byte[] mvex = box("mvex", concat(videoTrex, audioTrex));
        byte[] moov = box("moov", concat(mvhd, videoTrak, audioTrak, mvex));

        // The moov is written immediately after the ftyp, so a field at offset
        // `rel` within the moov byte[] sits at file offset ftyp.length + rel.
        // Locate the duration fields (patched at close) before we write.
        locateDurationFields(moov, ftyp.length);

        raf = new RandomAccessFile(output, "rw");
        raf.setLength(0);
        raf.write(ftyp);
        raf.write(moov);
        headerWritten = true;
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Header written (ftyp=" + ftyp.length + " moov=" + moov.length + ")");
        }

        // Flush any media that arrived before both inits, in arrival order per
        // track (video then audio is fine — players read by decode time).
        for (byte[] frag : pendingVideo) writeFragment(false, frag);
        for (byte[] frag : pendingAudio) writeFragment(true, frag);
        pendingVideo.clear();
        pendingAudio.clear();
    }

    /** Append a media fragment, rewriting its moof's tfhd track_ID. Strips the
     *  per-segment {@code styp} and {@code sidx} boxes: {@code styp} is just a
     *  brand marker, and {@code sidx} carries a {@code reference_ID} (a track id)
     *  that our renumbering would invalidate — players don't need either to play
     *  a contiguous fragmented file. */
    private void writeFragment(boolean isAudio, byte[] seg) throws IOException {
        int trackId = isAudio ? AUDIO_TRACK_ID : VIDEO_TRACK_ID;
        int p = 0;
        while (p + 8 <= seg.length) {
            long size = u32(seg, p);
            String type = type(seg, p + 4);
            if (size == 1 || size == 0) {
                // 64-bit / to-EOF sizes: not expected in SABR media segments.
                fail("fragment large/zero box size", null);
                return;
            }
            int boxEnd = p + (int) size;
            if (boxEnd > seg.length || boxEnd <= p) {
                fail("fragment box overrun", null);
                return;
            }
            if ("styp".equals(type) || "sidx".equals(type)) {
                p = boxEnd;
                continue;
            }
            if ("moof".equals(type)) {
                setMoofTrackId(seg, p, boxEnd, trackId);
            }
            raf.write(seg, p, boxEnd - p);
            p = boxEnd;
        }
    }

    @Override
    public void close() {
        if (raf == null) {
            return;
        }
        try {
            if (headerWritten && !failed) {
                patchDurations();
            }
            raf.close();
        } catch (IOException e) {
            fail("close", e);
        }
        raf = null;
    }

    /** Patch the placeholder header durations with the real downloaded length. */
    private void patchDurations() throws IOException {
        long maxMs = Math.max(videoDurationMs, audioDurationMs);
        patch(mvhdDurOff, mvhdDurW, scale(maxMs, movieTimescale));
        patch(vTkhdDurOff, vTkhdDurW, scale(videoDurationMs, movieTimescale));
        patch(aTkhdDurOff, aTkhdDurW, scale(audioDurationMs, movieTimescale));
        patch(vMdhdDurOff, vMdhdDurW, scale(videoDurationMs, videoMediaTimescale));
        patch(aMdhdDurOff, aMdhdDurW, scale(audioDurationMs, audioMediaTimescale));
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Patched durations: video=" + videoDurationMs
                    + "ms audio=" + audioDurationMs + "ms");
        }
    }

    private void patch(long offset, int width, long ticks) throws IOException {
        if (offset <= 0 || ticks < 0) {
            return;
        }
        raf.seek(offset);
        if (width == 8) {
            raf.writeLong(ticks);
        } else {
            raf.writeInt((int) ticks);
        }
    }

    private static long scale(long ms, long timescale) {
        if (ms <= 0 || timescale <= 0) {
            return 0;
        }
        // ms * timescale / 1000, rounded.
        return Math.round((double) ms * (double) timescale / 1000.0);
    }

    private void fail(String why, Throwable t) {
        if (!failed) {
            failed = true;
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "fMP4 mux giving up (" + why + "), will fall back to FFmpeg", t);
            }
        }
    }

    // ====================================================================
    // Duration-field location (offsets relative to the file)
    // ====================================================================

    private void locateDurationFields(byte[] moov, int moovFileStart) {
        int mvhd = childOffsetWithin(moov, 8, moov.length, "mvhd");
        if (mvhd >= 0) {
            int v = moov[mvhd + 8] & 0xFF;
            movieTimescale = u32(moov, mvhd + (v == 1 ? 28 : 20));
            mvhdDurW = (v == 1) ? 8 : 4;
            mvhdDurOff = moovFileStart + mvhd + (v == 1 ? 32 : 24);
        }

        int videoTrak = childOffsetWithin(moov, 8, moov.length, "trak");
        if (videoTrak >= 0) {
            int end = videoTrak + (int) u32(moov, videoTrak);
            locateTrakDurations(moov, moovFileStart, videoTrak, end, true);

            int audioTrak = childOffsetWithin(moov, end, moov.length, "trak");
            if (audioTrak >= 0) {
                int aEnd = audioTrak + (int) u32(moov, audioTrak);
                locateTrakDurations(moov, moovFileStart, audioTrak, aEnd, false);
            }
        }
    }

    private void locateTrakDurations(byte[] moov, int moovFileStart,
                                     int trakStart, int trakEnd, boolean video) {
        int tkhd = childOffsetWithin(moov, trakStart + 8, trakEnd, "tkhd");
        if (tkhd >= 0) {
            int v = moov[tkhd + 8] & 0xFF;
            int w = (v == 1) ? 8 : 4;
            long off = moovFileStart + tkhd + (v == 1 ? 36 : 28);
            if (video) { vTkhdDurOff = off; vTkhdDurW = w; }
            else { aTkhdDurOff = off; aTkhdDurW = w; }
        }
        int mdia = childOffsetWithin(moov, trakStart + 8, trakEnd, "mdia");
        if (mdia >= 0) {
            int mdiaEnd = mdia + (int) u32(moov, mdia);
            int mdhd = childOffsetWithin(moov, mdia + 8, mdiaEnd, "mdhd");
            if (mdhd >= 0) {
                int v = moov[mdhd + 8] & 0xFF;
                int w = (v == 1) ? 8 : 4;
                long ts = u32(moov, mdhd + (v == 1 ? 28 : 20));
                long off = moovFileStart + mdhd + (v == 1 ? 32 : 24);
                if (video) { vMdhdDurOff = off; vMdhdDurW = w; videoMediaTimescale = ts; }
                else { aMdhdDurOff = off; aMdhdDurW = w; audioMediaTimescale = ts; }
            }
        }
    }

    // ====================================================================
    // ISO-BMFF box helpers (32-bit sizes; bail on 64-bit via the callers)
    // ====================================================================

    private static final String[] ENCRYPTION_BOXES = { "encv", "enca", "pssh", "sinf", "senc" };

    /** Returns a COPY of the first top-level box of the given type, or null. */
    private byte[] findTopLevel(byte[] data, String wanted) {
        int p = 0;
        while (p + 8 <= data.length) {
            long size = u32(data, p);
            if (size < 8) return null;
            int end = p + (int) size;
            if (end > data.length || end <= p) return null;
            if (type(data, p + 4).equals(wanted)) {
                return slice(data, p, end);
            }
            p = end;
        }
        return null;
    }

    /** First child box of a container box (skips the 8-byte container header). */
    private byte[] findChild(byte[] container, String wanted) {
        int off = childOffsetWithin(container, 8, container.length, wanted);
        if (off < 0) return null;
        return slice(container, off, off + (int) u32(container, off));
    }

    private boolean hasSecondChild(byte[] container, String wanted) {
        int p = 8;
        int count = 0;
        while (p + 8 <= container.length) {
            long size = u32(container, p);
            if (size < 8) return false;
            int end = p + (int) size;
            if (end > container.length || end <= p) return false;
            if (type(container, p + 4).equals(wanted) && ++count == 2) {
                return true;
            }
            p = end;
        }
        return false;
    }

    /** trex lives under moov→mvex. Returns a copy of the first trex, or null. */
    private byte[] findTrex(byte[] moov) {
        byte[] mvex = findChild(moov, "mvex");
        if (mvex == null) return null;
        return findChild(mvex, "trex");
    }

    private boolean containsAny(byte[] data, String[] types) {
        for (String t : types) {
            if (containsTypeDeep(data, 0, data.length, t, 0)) return true;
        }
        return false;
    }

    private boolean containsTypeDeep(byte[] data, int start, int end, String wanted, int depth) {
        if (depth > 8) return false;
        int p = start;
        while (p + 8 <= end) {
            long size = u32(data, p);
            if (size < 8) return false;
            int boxEnd = p + (int) size;
            if (boxEnd > end || boxEnd <= p) return false;
            String type = type(data, p + 4);
            if (type.equals(wanted)) return true;
            if (isContainer(type) && containsTypeDeep(data, p + 8, boxEnd, wanted, depth + 1)) {
                return true;
            }
            p = boxEnd;
        }
        return false;
    }

    private static boolean isContainer(String type) {
        switch (type) {
            case "moov": case "trak": case "mdia": case "minf": case "stbl":
            case "mvex": case "edts": case "stsd": case "moof": case "traf":
                return true;
            default:
                return false;
        }
    }

    /** Set tkhd.track_ID inside a trak box (in place). */
    private void setTkhdTrackId(byte[] trak, int id) {
        int tkhd = childOffset(trak, "tkhd");
        if (tkhd < 0) { fail("no tkhd", null); return; }
        int payload = tkhd + 8;
        int version = trak[payload] & 0xFF;
        int trackIdOff = (version == 1) ? payload + 4 + 16 : payload + 4 + 8;
        putU32(trak, trackIdOff, id);
    }

    /** Set trex.track_ID (fullbox: version/flags(4) then track_ID(4)). */
    private void setTrexTrackId(byte[] trex, int id) {
        putU32(trex, 8 + 4, id);
    }

    /** Set mvhd.next_track_ID — the final 4 bytes of the box. */
    private void setNextTrackId(byte[] mvhd, int id) {
        putU32(mvhd, mvhd.length - 4, id);
    }

    /** Rewrite the tfhd.track_ID inside a moof (located at [start,end) in seg). */
    private void setMoofTrackId(byte[] seg, int moofStart, int moofEnd, int id) {
        int traf = childOffsetWithin(seg, moofStart + 8, moofEnd, "traf");
        if (traf < 0) { fail("no traf", null); return; }
        int trafSize = (int) u32(seg, traf);
        int tfhd = childOffsetWithin(seg, traf + 8, traf + trafSize, "tfhd");
        if (tfhd < 0) { fail("no tfhd", null); return; }
        putU32(seg, tfhd + 8 + 4, id); // fullbox(4) then track_ID(4)
    }

    /** Offset of the first child box of `type` within a container byte[]. */
    private int childOffset(byte[] container, String wanted) {
        return childOffsetWithin(container, 8, container.length, wanted);
    }

    private int childOffsetWithin(byte[] data, int start, int end, String wanted) {
        int p = start;
        while (p + 8 <= end) {
            long size = u32(data, p);
            if (size < 8) return -1;
            int boxEnd = p + (int) size;
            if (boxEnd > end || boxEnd <= p) return -1;
            if (type(data, p + 4).equals(wanted)) return p;
            p = boxEnd;
        }
        return -1;
    }

    // ---- primitive read/write ----

    private static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static void putU32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    private static String type(byte[] b, int o) {
        return new String(new char[]{
                (char) (b[o] & 0xFF), (char) (b[o + 1] & 0xFF),
                (char) (b[o + 2] & 0xFF), (char) (b[o + 3] & 0xFF)
        });
    }

    private static byte[] slice(byte[] b, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(b, from, out, 0, to - from);
        return out;
    }

    private static byte[] box(String type, byte[] payload) {
        byte[] b = new byte[8 + payload.length];
        putU32(b, 0, b.length);
        b[4] = (byte) type.charAt(0);
        b[5] = (byte) type.charAt(1);
        b[6] = (byte) type.charAt(2);
        b[7] = (byte) type.charAt(3);
        System.arraycopy(payload, 0, b, 8, payload.length);
        return b;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }
}

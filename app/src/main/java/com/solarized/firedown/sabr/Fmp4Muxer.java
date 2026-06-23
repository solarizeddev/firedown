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
 * {@code mvex}), write {@code ftyp}+{@code moov}, then reserve a slot for a
 * {@code sidx}. Thereafter each media fragment is appended verbatim except its
 * {@code moof}/{@code tfhd} {@code track_ID} is rewritten (video→1, audio→2).
 * Pure container remux — codec bytes are never touched (codec-agnostic:
 * avc1/av01 + mp4a all ride their copied {@code stsd}); each track keeps its own
 * {@code mdhd} timescale + {@code elst} + per-fragment {@code tfdt}, so A/V sync
 * and AAC priming are preserved.
 *
 * <p><b>Duration + seeking (patched at {@link #close()}).</b> The {@code moov}
 * is at the file head and we stream, so neither the real length nor a seek index
 * is known up front:
 * <ul>
 *   <li><b>Duration:</b> YouTube's init declares the FULL clip length, wrong for
 *   an early finish. The mvhd/tkhd/mdhd durations are patched with the real
 *   downloaded length (accumulated from SABR's per-segment durations).</li>
 *   <li><b>Seeking:</b> a fragmented MP4 is unseekable in ExoPlayer without a
 *   {@code sidx}. We record each video fragment's file offset + duration as it
 *   streams and write a {@code sidx} (indexed on the video track) into the
 *   reserved slot, so scrubbing and skip work.</li>
 * </ul>
 * Both are done via {@link RandomAccessFile} seek-back.
 *
 * <p><b>Safety / fallback:</b> deliberately conservative — a non-ISO-BMFF init,
 * encryption, &gt;1 trak per init, a 64-bit box size, more video fragments than
 * the reserved index can hold, a parse anomaly, or an IO error flips
 * {@link #isFailed()} and it stops. The caller ({@code SabrStrategy}) keeps the
 * raw temp streams and, after the download, probe-validates the output; on
 * failure it falls back to the proven FFmpeg remux. So a muxer bug degrades to
 * the previous behavior. (Note: the probe can't detect a SEEK-specific bug — a
 * malformed sidx would still play but mis-seek — so this needs on-device check.)
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

    /** Bytes reserved after the moov for the sidx (+ a free-box pad). 128 KB
     *  holds ~10900 video-fragment references (12 bytes each); a longer clip
     *  overflows it and falls back to FFmpeg (which is seekable anyway). */
    private static final int SIDX_RESERVE = 128 * 1024;

    private final File output;
    private RandomAccessFile raf;

    private byte[] videoInit;
    private byte[] audioInit;
    private boolean headerWritten;
    private boolean failed;

    private final List<PendingFrag> pending = new ArrayList<>();

    // Accumulated real (downloaded) duration per track, milliseconds.
    private long videoDurationMs;
    private long audioDurationMs;

    // Per-video-fragment file offsets + durations (ms), for the sidx.
    private final ArrayList<Long> videoFragOffsets = new ArrayList<>();
    private final ArrayList<Long> videoFragDurMs = new ArrayList<>();
    private long firstVideoTfdt = -1; // base-media-decode-time of frag 0 (video timescale)
    private long sidxRegionStart = -1;

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

    private static final class PendingFrag {
        final boolean isAudio;
        final long durationMs;
        final byte[] data;
        PendingFrag(boolean isAudio, long durationMs, byte[] data) {
            this.isAudio = isAudio;
            this.durationMs = durationMs;
            this.data = data;
        }
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
                onMedia(isAudio, durationMs, data);
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

    private void onMedia(boolean isAudio, long durationMs, byte[] data) throws IOException {
        if (!headerWritten) {
            // Buffer until both inits land (normally only the first response's
            // media, before we've seen both inits).
            if (pending.size() >= MAX_PENDING_FRAGMENTS) {
                fail("media before init (buffer overflow)", null);
                return;
            }
            pending.add(new PendingFrag(isAudio, durationMs, data));
            return;
        }
        writeFragment(isAudio, durationMs, data);
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
        if (hasSecondChild(videoMoov, "trak") || hasSecondChild(audioMoov, "trak")) {
            fail("multi-trak init", null);
            return;
        }
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

        setTkhdTrackId(videoTrak, VIDEO_TRACK_ID);
        setTkhdTrackId(audioTrak, AUDIO_TRACK_ID);
        setTrexTrackId(videoTrex, VIDEO_TRACK_ID);
        setTrexTrackId(audioTrex, AUDIO_TRACK_ID);
        setNextTrackId(mvhd, AUDIO_TRACK_ID + 1);

        byte[] mvex = box("mvex", concat(videoTrex, audioTrex));
        byte[] moov = box("moov", concat(mvhd, videoTrak, audioTrak, mvex));

        // A field at offset `rel` within the moov byte[] sits at file offset
        // ftyp.length + rel (the moov is written right after the ftyp).
        locateDurationFields(moov, ftyp.length);

        raf = new RandomAccessFile(output, "rw");
        raf.setLength(0);
        raf.write(ftyp);
        raf.write(moov);
        // Reserve a slot for the sidx (written at close, once all fragment
        // offsets/durations are known) as a placeholder free box.
        sidxRegionStart = raf.getFilePointer();
        writeFreeBox(SIDX_RESERVE);
        headerWritten = true;
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Header written (ftyp=" + ftyp.length + " moov=" + moov.length
                    + " sidxReserve=" + SIDX_RESERVE + ")");
        }

        // Flush any media that arrived before both inits, in arrival order.
        for (PendingFrag p : pending) {
            writeFragment(p.isAudio, p.durationMs, p.data);
        }
        pending.clear();
    }

    /** Append a media fragment, rewriting its moof's tfhd track_ID. Strips the
     *  per-segment {@code styp} and {@code sidx} boxes: {@code styp} is just a
     *  brand marker, and a per-segment {@code sidx} carries a {@code reference_ID}
     *  that our renumbering would invalidate (we write one whole-file sidx). */
    private void writeFragment(boolean isAudio, long durationMs, byte[] seg) throws IOException {
        if (!isAudio) {
            // Record the video moof's file offset (= current position; the
            // skipped styp/sidx aren't written, so the moof lands here) and its
            // duration for the sidx. Capture the first fragment's tfdt as the
            // sidx earliest_presentation_time.
            videoFragOffsets.add(raf.getFilePointer());
            videoFragDurMs.add(durationMs);
            if (firstVideoTfdt < 0) {
                firstVideoTfdt = readTfdt(seg);
            }
        }
        int p = 0;
        while (p + 8 <= seg.length) {
            long size = u32(seg, p);
            String type = type(seg, p + 4);
            if (size == 1 || size == 0) {
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
                setMoofTrackId(seg, p, boxEnd, isAudio ? AUDIO_TRACK_ID : VIDEO_TRACK_ID);
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
                writeSidx();
            }
            if (headerWritten && !failed) {
                patchDurations();
            }
            raf.close();
        } catch (IOException e) {
            fail("close", e);
        }
        raf = null;
    }

    // ---- sidx (seek index) ----

    private void writeSidx() throws IOException {
        int n = videoFragOffsets.size();
        if (n == 0 || videoMediaTimescale <= 0 || sidxRegionStart < 0) {
            fail("sidx: no video fragments / timescale", null);
            return;
        }
        int sidxSize = 32 + n * 12; // fullbox(12) + 16 fixed + refs(n*12)
        if (sidxSize > SIDX_RESERVE - 8) { // leave room for the trailing free box
            fail("sidx too large (clip too long for inline index)", null);
            return;
        }

        long fileEnd = raf.length();
        long first = videoFragOffsets.get(0);
        long firstOffset = first - (sidxRegionStart + sidxSize);
        long ept = firstVideoTfdt > 0 ? firstVideoTfdt : 0;

        byte[] b = new byte[sidxSize];
        putU32(b, 0, sidxSize);
        b[4] = 's'; b[5] = 'i'; b[6] = 'd'; b[7] = 'x';
        // version 0, flags 0 (b[8..11] already zero)
        putU32(b, 12, VIDEO_TRACK_ID);            // reference_ID
        putU32(b, 16, (int) videoMediaTimescale); // timescale
        putU32(b, 20, (int) ept);                 // earliest_presentation_time (v0)
        putU32(b, 24, (int) firstOffset);         // first_offset (v0)
        // b[28..29] reserved = 0
        putU16(b, 30, n);                         // reference_count
        int o = 32;
        for (int i = 0; i < n; i++) {
            long start = videoFragOffsets.get(i);
            long end = (i < n - 1) ? videoFragOffsets.get(i + 1) : fileEnd;
            long refSize = end - start;
            long durTicks = Math.round(videoFragDurMs.get(i) * (double) videoMediaTimescale / 1000.0);
            // reference_type (bit 31) = 0 (media) | referenced_size (31 bits)
            putU32(b, o, (int) (refSize & 0x7FFFFFFFL));
            putU32(b, o + 4, (int) durTicks);
            putU32(b, o + 8, 0x90000000); // starts_with_SAP=1, SAP_type=1
            o += 12;
        }

        raf.seek(sidxRegionStart);
        raf.write(b);
        // Pad the rest of the reserved region with a free box so the first
        // fragment stays exactly where its recorded offset says.
        writeFreeBox(SIDX_RESERVE - sidxSize);
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "sidx written: " + n + " refs, " + sidxSize + " bytes");
        }
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
        return Math.round((double) ms * (double) timescale / 1000.0);
    }

    /** Write a {@code free} box of the given total size at the current position. */
    private void writeFreeBox(int totalSize) throws IOException {
        if (totalSize < 8) {
            return;
        }
        byte[] b = new byte[totalSize];
        putU32(b, 0, totalSize);
        b[4] = 'f'; b[5] = 'r'; b[6] = 'e'; b[7] = 'e';
        raf.write(b);
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

    /** base_media_decode_time of the first traf in a fragment (track timescale). */
    private long readTfdt(byte[] seg) {
        int p = 0;
        while (p + 8 <= seg.length) {
            long size = u32(seg, p);
            if (size < 8) return 0;
            int end = p + (int) size;
            if (end > seg.length || end <= p) return 0;
            if ("moof".equals(type(seg, p + 4))) {
                int traf = childOffsetWithin(seg, p + 8, end, "traf");
                if (traf < 0) return 0;
                int trafEnd = traf + (int) u32(seg, traf);
                int tfdt = childOffsetWithin(seg, traf + 8, trafEnd, "tfdt");
                if (tfdt < 0) return 0;
                int v = seg[tfdt + 8] & 0xFF;
                return (v == 1) ? readU64(seg, tfdt + 12) : u32(seg, tfdt + 12);
            }
            p = end;
        }
        return 0;
    }

    // ====================================================================
    // ISO-BMFF box helpers (32-bit sizes; bail on 64-bit via the callers)
    // ====================================================================

    private static final String[] ENCRYPTION_BOXES = { "encv", "enca", "pssh", "sinf", "senc" };

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

    private void setTkhdTrackId(byte[] trak, int id) {
        int tkhd = childOffset(trak, "tkhd");
        if (tkhd < 0) { fail("no tkhd", null); return; }
        int payload = tkhd + 8;
        int version = trak[payload] & 0xFF;
        int trackIdOff = (version == 1) ? payload + 4 + 16 : payload + 4 + 8;
        putU32(trak, trackIdOff, id);
    }

    private void setTrexTrackId(byte[] trex, int id) {
        putU32(trex, 8 + 4, id);
    }

    private void setNextTrackId(byte[] mvhd, int id) {
        putU32(mvhd, mvhd.length - 4, id);
    }

    private void setMoofTrackId(byte[] seg, int moofStart, int moofEnd, int id) {
        int traf = childOffsetWithin(seg, moofStart + 8, moofEnd, "traf");
        if (traf < 0) { fail("no traf", null); return; }
        int trafSize = (int) u32(seg, traf);
        int tfhd = childOffsetWithin(seg, traf + 8, traf + trafSize, "tfhd");
        if (tfhd < 0) { fail("no tfhd", null); return; }
        putU32(seg, tfhd + 8 + 4, id); // fullbox(4) then track_ID(4)
    }

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

    private static long readU64(byte[] b, int o) {
        return (u32(b, o) << 32) | (u32(b, o + 4) & 0xFFFFFFFFL);
    }

    private static void putU32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    private static void putU16(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 8);
        b[o + 1] = (byte) v;
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

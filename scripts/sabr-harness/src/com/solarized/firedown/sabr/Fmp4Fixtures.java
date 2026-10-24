package com.solarized.firedown.sabr;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the ISO-BMFF pieces a SABR stream is made of — an init segment
 * ({@code ftyp}+{@code moov} with one {@code trak} and an {@code mvex}) and a
 * media fragment ({@code styp}+{@code sidx}+{@code moof}+{@code mdat}) — with
 * every field the muxer reads or rewrites at its real offset, and parses the
 * muxer's output back into boxes so the cases can assert on what was written
 * rather than on log lines. Written from the ISO 14496-12 layouts, not from
 * {@link Fmp4Muxer}'s offsets, so a wrong offset in the muxer and the same
 * wrong offset here cannot cancel out.
 */
public final class Fmp4Fixtures {

    /** A placeholder duration far from any real total, so a patched field is
     *  told apart from one left alone. */
    public static final long PLACEHOLDER_DURATION = 987_654_321L;

    // ── primitives ──────────────────────────────────────────────────────────

    public static byte[] u32(long v) {
        return new byte[]{ (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
    }
    public static byte[] u16(int v) { return new byte[]{ (byte) (v >>> 8), (byte) v }; }
    public static byte[] u64(long v) { return cat(u32(v >>> 32), u32(v & 0xFFFFFFFFL)); }
    public static byte[] str(String s) { return s.getBytes(StandardCharsets.US_ASCII); }
    public static byte[] zeros(int n) { return new byte[n]; }

    public static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    /** [size][type][payload]. */
    public static byte[] box(String type, byte[]... payload) {
        byte[] body = cat(payload);
        return cat(u32(8 + body.length), str(type), body);
    }

    /** A FullBox: [size][type][version][flags:3][payload]. */
    public static byte[] fullBox(String type, int version, int flags, byte[]... payload) {
        byte[] body = cat(payload);
        return cat(u32(12 + body.length), str(type),
                new byte[]{ (byte) version, (byte) (flags >>> 16), (byte) (flags >>> 8), (byte) flags },
                body);
    }

    // ── init segment ────────────────────────────────────────────────────────

    public static byte[] ftyp() {
        return box("ftyp", str("iso5"), u32(0x200), str("iso5"), str("dash"));
    }

    /** mvhd v0: timescale, placeholder duration, next_track_ID = trackId + 1. */
    public static byte[] mvhd(long movieTimescale, int trackId) {
        return fullBox("mvhd", 0, 0,
                u32(0), u32(0),                       // creation, modification
                u32(movieTimescale), u32(PLACEHOLDER_DURATION),
                u32(0x00010000), u16(0x0100), zeros(2), zeros(8),
                identityMatrix(), zeros(24),
                u32(trackId + 1));
    }

    private static byte[] identityMatrix() {
        return cat(u32(0x00010000), u32(0), u32(0), u32(0), u32(0x00010000), u32(0),
                u32(0), u32(0), u32(0x40000000));
    }

    /** tkhd v0 (flags 7): track_ID, placeholder duration. */
    public static byte[] tkhd(int trackId, boolean video) {
        return fullBox("tkhd", 0, 7,
                u32(0), u32(0), u32(trackId), u32(0), u32(PLACEHOLDER_DURATION),
                zeros(8), u16(0), u16(0), u16(video ? 0 : 0x0100), zeros(2),
                identityMatrix(),
                u32(video ? 1280 << 16 : 0), u32(video ? 720 << 16 : 0));
    }

    /** mdhd v0: media timescale, placeholder duration. */
    public static byte[] mdhd(long timescale) {
        return fullBox("mdhd", 0, 0, u32(0), u32(0), u32(timescale),
                u32(PLACEHOLDER_DURATION), u16(0x55C4), u16(0));
    }

    public static byte[] hdlr(boolean video) {
        return fullBox("hdlr", 0, 0, u32(0), str(video ? "vide" : "soun"), zeros(12),
                str(video ? "VideoHandler" : "SoundHandler"), new byte[]{ 0 });
    }

    /** A minimal sample description: one entry of the given codec type. */
    public static byte[] stsd(String codec) {
        byte[] entry = box(codec, zeros(6), u16(1), zeros(codec.equals("mp4a") ? 20 : 70));
        return fullBox("stsd", 0, 0, u32(1), entry);
    }

    public static byte[] stbl(String codec) {
        return box("stbl", stsd(codec),
                fullBox("stts", 0, 0, u32(0)), fullBox("stsc", 0, 0, u32(0)),
                fullBox("stsz", 0, 0, u32(0), u32(0)), fullBox("stco", 0, 0, u32(0)));
    }

    public static byte[] trak(int trackId, boolean video, long mediaTimescale, byte[]... extraInTrak) {
        return box("trak", cat(
                tkhd(trackId, video),
                box("mdia", mdhd(mediaTimescale), hdlr(video),
                        box("minf", video ? fullBox("vmhd", 0, 1, zeros(8)) : fullBox("smhd", 0, 0, zeros(4)),
                                box("dinf", fullBox("dref", 0, 0, u32(1), fullBox("url ", 0, 1))),
                                stbl(video ? "avc1" : "mp4a"))),
                cat(extraInTrak)));
    }

    public static byte[] trex(int trackId) {
        return fullBox("trex", 0, 0, u32(trackId), u32(1), u32(0), u32(0), u32(0));
    }

    /** A complete single-track init segment, as a SABR format delivers it. */
    public static byte[] init(int trackId, boolean video, long movieTimescale, long mediaTimescale) {
        return cat(ftyp(), box("moov", mvhd(movieTimescale, trackId),
                trak(trackId, video, mediaTimescale), box("mvex", trex(trackId))));
    }

    /** An init whose moov carries a second trak. */
    public static byte[] initTwoTraks(int trackId, long ts) {
        return cat(ftyp(), box("moov", mvhd(ts, trackId + 1),
                trak(trackId, true, ts), trak(trackId + 1, false, ts),
                box("mvex", trex(trackId), trex(trackId + 1))));
    }

    /** An init with no mvex/trex (a progressive moov). */
    public static byte[] initNoTrex(int trackId, long ts) {
        return cat(ftyp(), box("moov", mvhd(ts, trackId), trak(trackId, true, ts)));
    }

    /** An init whose moov carries a pssh (common encryption). */
    public static byte[] initEncrypted(int trackId, long ts) {
        return cat(ftyp(), box("moov", mvhd(ts, trackId),
                fullBox("pssh", 0, 0, zeros(16), u32(0)),
                trak(trackId, true, ts), box("mvex", trex(trackId))));
    }

    /** Not ISO-BMFF at all: an EBML (WebM) header followed by junk. */
    public static byte[] webmInit() {
        return cat(new byte[]{ 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3 }, zeros(60));
    }

    // ── media fragment ──────────────────────────────────────────────────────

    /** styp + a per-segment sidx + moof(mfhd, traf(tfhd, tfdt v1, trun)) + mdat. */
    public static byte[] fragment(int trackId, int seq, long tfdt, long durTicks, byte[] payload) {
        return cat(box("styp", str("msdh"), u32(0), str("msdh"), str("msix")),
                fullBox("sidx", 0, 0, u32(trackId), u32(1000), u32(0), u32(0), u16(0), u16(1),
                        u32(100), u32(1000), u32(0x90000000)),
                moofMdat(trackId, seq, tfdt, durTicks, payload));
    }

    public static byte[] moofMdat(int trackId, int seq, long tfdt, long durTicks, byte[] payload) {
        byte[] trun = fullBox("trun", 0, 0x000301, u32(1), u32(0), u32(durTicks), u32(payload.length));
        byte[] traf = box("traf",
                fullBox("tfhd", 0, 0x020000, u32(trackId)),
                fullBox("tfdt", 1, 0, u64(tfdt)),
                trun);
        byte[] moof = box("moof", fullBox("mfhd", 0, 0, u32(seq)), traf);
        // data_offset = moof size + mdat header: patch it so the trun is honest.
        // moof hdr 8 | mfhd 16 | traf hdr 8 | tfhd 16 | tfdt(v1) 20 | trun hdr 12 | sample_count 4
        int dataOffsetField = 8 + 16 + 8 + 16 + 20 + 12 + 4;
        byte[] off = u32(moof.length + 8);
        System.arraycopy(off, 0, moof, dataOffsetField, 4);
        return cat(moof, box("mdat", payload));
    }

    /** A fragment whose first box claims a 64-bit size (largesize). */
    public static byte[] fragmentLargesize(int trackId) {
        return cat(u32(1), str("moof"), u64(40), zeros(24));
    }

    public static byte[] payload(int seed, int bytes) {
        byte[] b = new byte[bytes];
        for (int i = 0; i < bytes; i++) b[i] = (byte) (seed * 31 + i);
        return b;
    }

    // ── parsing the muxer's output ──────────────────────────────────────────

    public static final class Box {
        public final String type;
        public final int off;
        public final int size;
        Box(String type, int off, int size) { this.type = type; this.off = off; this.size = size; }
        public int end() { return off + size; }
        @Override public String toString() { return type + "@" + off + "+" + size; }
    }

    /** Sequential boxes in [start, end). A short or 64-bit size ends the scan. */
    public static List<Box> boxes(byte[] d, int start, int end) {
        List<Box> out = new ArrayList<>();
        int p = start;
        while (p + 8 <= end) {
            long size = readU32(d, p);
            if (size < 8) break;
            int boxEnd = p + (int) size;
            if (boxEnd > end) break;
            out.add(new Box(new String(d, p + 4, 4, StandardCharsets.US_ASCII), p, (int) size));
            p = boxEnd;
        }
        return out;
    }

    public static List<Box> topLevel(byte[] file) { return boxes(file, 0, file.length); }

    /** Children of a plain container box. */
    public static List<Box> children(byte[] d, Box parent) { return boxes(d, parent.off + 8, parent.end()); }

    public static Box first(List<Box> l, String type) {
        for (Box b : l) if (b.type.equals(type)) return b;
        return null;
    }

    public static List<Box> all(List<Box> l, String type) {
        List<Box> out = new ArrayList<>();
        for (Box b : l) if (b.type.equals(type)) out.add(b);
        return out;
    }

    public static String types(List<Box> l) {
        StringBuilder sb = new StringBuilder();
        for (Box b : l) { if (sb.length() > 0) sb.append(' '); sb.append(b.type); }
        return sb.toString();
    }

    public static long readU32(byte[] d, int o) {
        return ((long) (d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16)
                | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }
    public static int readU16(byte[] d, int o) { return ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF); }
    public static long readU64(byte[] d, int o) { return (readU32(d, o) << 32) | readU32(d, o + 4); }

    /** tkhd v0 track_ID of a trak box. */
    public static long tkhdTrackId(byte[] d, Box trak) {
        Box tkhd = first(children(d, trak), "tkhd");
        return readU32(d, tkhd.off + 8 + 4 + 8);
    }
    /** tkhd v0 duration of a trak box (movie timescale). */
    public static long tkhdDuration(byte[] d, Box trak) {
        Box tkhd = first(children(d, trak), "tkhd");
        return readU32(d, tkhd.off + 8 + 4 + 16);
    }
    /** mdhd v0 duration of a trak box (media timescale). */
    public static long mdhdDuration(byte[] d, Box trak) {
        Box mdia = first(children(d, trak), "mdia");
        Box mdhd = first(children(d, mdia), "mdhd");
        return readU32(d, mdhd.off + 8 + 4 + 12);
    }
    public static long mdhdTimescale(byte[] d, Box trak) {
        Box mdia = first(children(d, trak), "mdia");
        Box mdhd = first(children(d, mdia), "mdhd");
        return readU32(d, mdhd.off + 8 + 4 + 8);
    }
    /** mvhd v0 duration / next_track_ID. */
    public static long mvhdDuration(byte[] d, Box moov) {
        Box mvhd = first(children(d, moov), "mvhd");
        return readU32(d, mvhd.off + 8 + 4 + 12);
    }
    public static long mvhdNextTrackId(byte[] d, Box moov) {
        Box mvhd = first(children(d, moov), "mvhd");
        return readU32(d, mvhd.end() - 4);
    }
    /** trex track_ID. */
    public static long trexTrackId(byte[] d, Box trex) { return readU32(d, trex.off + 8 + 4); }
    /** tfhd track_ID of a moof's first traf. */
    public static long moofTrackId(byte[] d, Box moof) {
        Box traf = first(children(d, moof), "traf");
        Box tfhd = first(children(d, traf), "tfhd");
        return readU32(d, tfhd.off + 8 + 4);
    }
    /** The mdat payload bytes. */
    public static byte[] mdatPayload(byte[] d, Box mdat) {
        byte[] out = new byte[mdat.size - 8];
        System.arraycopy(d, mdat.off + 8, out, 0, out.length);
        return out;
    }

    /** The whole-file sidx, decoded. */
    public static final class Sidx {
        public long referenceId, timescale, ept, firstOffset;
        public int count;
        public long[] sizes, durations, flags;
    }

    public static Sidx sidx(byte[] d, Box b) {
        Sidx s = new Sidx();
        int p = b.off + 12; // after fullbox header (v0)
        s.referenceId = readU32(d, p); s.timescale = readU32(d, p + 4);
        s.ept = readU32(d, p + 8); s.firstOffset = readU32(d, p + 12);
        s.count = readU16(d, p + 18);
        s.sizes = new long[s.count]; s.durations = new long[s.count]; s.flags = new long[s.count];
        int o = p + 20;
        for (int i = 0; i < s.count; i++) {
            s.sizes[i] = readU32(d, o) & 0x7FFFFFFFL; s.durations[i] = readU32(d, o + 4);
            s.flags[i] = readU32(d, o + 8); o += 12;
        }
        return s;
    }
}

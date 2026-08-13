package com.solarized.firedown.sabr;

import java.io.ByteArrayOutputStream;

/**
 * Builds UMP responses the way YouTube's SABR endpoint does, so tests can
 * script a server rather than assert against captured bytes.
 *
 * <p>Protobuf bodies are built with the app's own {@link ProtobufWire.Writer},
 * and the field numbers below mirror the real decoders in
 * {@link SabrMessages}. The UMP framing is encoded HERE rather than reused,
 * because {@link UmpReader} only decodes — {@link #varint} is written from
 * the scheme documented on {@code UmpReader.readUmpVarint} and the harness
 * round-trips it through the real reader before trusting it.</p>
 *
 * <p>Known limit, deliberately accepted: bodies are encoded with the same
 * ProtobufWire the downloader decodes with, so a bug in the CODEC would
 * cancel out. That is fine — what is under test here is SabrDownloader's
 * state machine (recovery, bounds, progress), not the wire format.</p>
 */
public class SabrTestServer {

    // ── UMP framing ─────────────────────────────────────────────────────────

    /** Mirror of UmpReader.readUmpVarint: the first byte's high bits pick the width. */
    public static byte[] varint(long v) {
        if (v < 0) throw new IllegalArgumentException("negative: " + v);
        if (v < 128) return new byte[]{ (byte) v };
        if (v < (1L << 14)) return new byte[]{ (byte) (0x80 | (v & 0x3F)), (byte) (v >> 6) };
        if (v < (1L << 21)) {
            long rest = v >> 5;
            return new byte[]{ (byte) (0xC0 | (v & 0x1F)), (byte) (rest & 0xFF), (byte) ((rest >> 8) & 0xFF) };
        }
        if (v < (1L << 28)) {
            long rest = v >> 4;
            return new byte[]{ (byte) (0xE0 | (v & 0x0F)), (byte) (rest & 0xFF),
                    (byte) ((rest >> 8) & 0xFF), (byte) ((rest >> 16) & 0xFF) };
        }
        return new byte[]{ (byte) 0xF0, (byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF),
                (byte) ((v >> 16) & 0xFF), (byte) ((v >> 24) & 0xFF) };
    }

    /** One framed UMP part: [type][size][payload]. */
    public static byte[] part(int type, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(varint(type));
            out.write(varint(payload.length));
            out.write(payload);
        } catch (Exception e) { throw new RuntimeException(e); }
        return out.toByteArray();
    }

    public static byte[] concat(byte[]... blobs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { for (byte[] b : blobs) out.write(b); } catch (Exception e) { throw new RuntimeException(e); }
        return out.toByteArray();
    }

    // ── message bodies (field numbers mirror SabrMessages' decoders) ─────────

    /** FormatInitMetadata: 1 videoId, 2 formatId, 5 mimeType, 9 durationUnits, 10 durationTimescale. */
    public static byte[] formatInit(String videoId, int itag, long lmt, String xtags,
                                    String mime, long durationUnits, long timescale) {
        ProtobufWire.Writer w = new ProtobufWire.Writer();
        w.writeString(1, videoId);
        w.writeBytes(2, new SabrMessages.FormatId(itag, lmt, xtags).encode());
        w.writeString(5, mime);
        w.writeInt64(9, durationUnits);
        w.writeInt64(10, timescale);
        return part(UmpReader.FORMAT_INITIALIZATION_METADATA, w.toByteArray());
    }

    /** MediaHeader: 1 headerId, 2 videoId, 3 itag, 4 lmt, 5 xtags, 8 isInit,
     *  9 sequenceNumber, 11 startMs, 12 durationMs, 13 formatId, 14 contentLength. */
    public static byte[] mediaHeader(int headerId, String videoId, int itag, long lmt, String xtags,
                                     boolean isInit, int seq, long startMs, long durMs, long contentLength) {
        ProtobufWire.Writer w = new ProtobufWire.Writer();
        w.writeInt32(1, headerId);
        w.writeString(2, videoId);
        w.writeInt32(3, itag);
        w.writeInt64(4, lmt);
        if (!xtags.isEmpty()) w.writeString(5, xtags);
        if (isInit) w.writeBool(8, true);
        w.writeInt32(9, seq);
        w.writeInt64(11, startMs);
        w.writeInt64(12, durMs);
        w.writeBytes(13, new SabrMessages.FormatId(itag, lmt, xtags).encode());
        if (contentLength > 0) w.writeInt64(14, contentLength);
        return part(UmpReader.MEDIA_HEADER, w.toByteArray());
    }

    /** MEDIA / MEDIA_END payloads are [headerId byte][data…]. */
    public static byte[] media(int headerId, byte[] payload) {
        byte[] b = new byte[payload.length + 1];
        b[0] = (byte) headerId;
        System.arraycopy(payload, 0, b, 1, payload.length);
        return part(UmpReader.MEDIA, b);
    }

    public static byte[] mediaEnd(int headerId) {
        return part(UmpReader.MEDIA_END, new byte[]{ (byte) headerId });
    }

    /** StreamProtectionStatus: 1 status (3 = attestation required). */
    public static byte[] streamProtection(int status) {
        ProtobufWire.Writer w = new ProtobufWire.Writer();
        w.writeInt32(1, status);
        return part(UmpReader.STREAM_PROTECTION_STATUS, w.toByteArray());
    }

    /** NextRequestPolicy: 3 backoffTimeMs. */
    public static byte[] nextRequestPolicy(int backoffMs) {
        ProtobufWire.Writer w = new ProtobufWire.Writer();
        w.writeInt32(3, backoffMs);
        return part(UmpReader.NEXT_REQUEST_POLICY, w.toByteArray());
    }

    /** SabrRedirect: 1 url. */
    public static byte[] sabrRedirect(String url) {
        ProtobufWire.Writer w = new ProtobufWire.Writer();
        w.writeString(1, url);
        return part(UmpReader.SABR_REDIRECT, w.toByteArray());
    }

    /** A complete segment: header + payload + end. */
    public static byte[] segment(int headerId, String videoId, int itag, long lmt, String xtags,
                                 int seq, long startMs, long durMs, int bytes) {
        byte[] payload = new byte[bytes];
        for (int i = 0; i < bytes; i++) payload[i] = (byte) (seq * 31 + i);
        return concat(
                mediaHeader(headerId, videoId, itag, lmt, xtags, false, seq, startMs, durMs, bytes),
                media(headerId, payload),
                mediaEnd(headerId));
    }
}

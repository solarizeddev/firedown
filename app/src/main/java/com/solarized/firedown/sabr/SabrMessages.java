package com.solarized.firedown.sabr;

import com.solarized.firedown.sabr.ProtobufWire.Writer;
import com.solarized.firedown.sabr.ProtobufWire.Reader;

/**
 * Hand-coded protobuf messages for SABR protocol.
 * Field numbers match googlevideo's proto definitions.
 */
public class SabrMessages {

    // YouTube innertube client name enum values
    public static final int CLIENT_WEB = 1;
    public static final int CLIENT_MWEB = 2;
    public static final int CLIENT_ANDROID = 3;
    public static final int CLIENT_ANDROID_VR = 28;

    // =========================================================================
    // FormatId — identifies a specific format (itag + lastModified + xtags)
    // =========================================================================

    public static class FormatId {
        public int itag;
        public long lastModified;
        public String xtags = "";

        public FormatId() {}
        public FormatId(int itag, long lastModified, String xtags) {
            this.itag = itag;
            this.lastModified = lastModified;
            this.xtags = xtags != null ? xtags : "";
        }

        public byte[] encode() {
            Writer w = new Writer();
            if (itag != 0)         w.writeInt32(1, itag);
            if (lastModified != 0) w.writeInt64(2, lastModified);
            if (!xtags.isEmpty())  w.writeString(3, xtags);
            return w.toByteArray();
        }

        public static FormatId decode(byte[] data, int offset, int length) {
            FormatId f = new FormatId();
            Reader r = new Reader(data, offset, length);
            while (r.hasRemaining()) {
                int tag = r.readTag();
                int fn = r.getFieldNumber(tag);
                int wt = r.getWireType(tag);
                switch (fn) {
                    case 1:
                        if (wt == 0) f.itag = r.readInt32();
                        else r.skipField(wt);
                        break;
                    case 2:
                        if (wt == 0) f.lastModified = r.readInt64();
                        else r.skipField(wt);
                        break;
                    case 3:
                        // YouTube's wire schema for field 3 varies by format:
                        //   wt=2 (string): xtags (e.g. "CggKA2RyYxIBMQ" for DRC variants)
                        //   wt=0 (varint): appears on some video formats (semantics TBD)
                        // Accept either without crashing — xtags only matters when string.
                        if (wt == 2) f.xtags = r.readString();
                        else r.skipField(wt);
                        break;
                    default:
                        r.skipField(wt);
                        break;
                }
            }
            return f;
        }
    }

    // =========================================================================
    // TimeRange
    // =========================================================================

    public static class TimeRange {
        public long startTicks;
        public long durationTicks;
        public int timescale;

        public byte[] encode() {
            Writer w = new Writer();
            if (startTicks != 0)    w.writeInt64(1, startTicks);
            if (durationTicks != 0) w.writeInt64(2, durationTicks);
            if (timescale != 0)     w.writeInt32(3, timescale);
            return w.toByteArray();
        }

        public static TimeRange decode(byte[] data, int offset, int length) {
            TimeRange t = new TimeRange();
            Reader r = new Reader(data, offset, length);
            while (r.hasRemaining()) {
                int tag = r.readTag();
                int fn = r.getFieldNumber(tag);
                int wt = r.getWireType(tag);
                switch (fn) {
                    case 1:
                        if (wt == 0) t.startTicks = r.readInt64();
                        else r.skipField(wt);
                        break;
                    case 2:
                        if (wt == 0) t.durationTicks = r.readInt64();
                        else r.skipField(wt);
                        break;
                    case 3:
                        if (wt == 0) t.timescale = r.readInt32();
                        else r.skipField(wt);
                        break;
                    default:
                        r.skipField(wt);
                        break;
                }
            }
            return t;
        }
    }

    // =========================================================================
    // MediaHeader — header for each media segment in UMP response
    // =========================================================================

    public static class MediaHeader {
        public int headerId;
        public String videoId = "";
        public int itag;
        public long lmt;
        public String xtags = "";
        public boolean isInitSeg;
        public int sequenceNumber;
        public long startMs;
        public long durationMs;
        public FormatId formatId;
        public long contentLength;
        public TimeRange timeRange;

        public static MediaHeader decode(byte[] data, int offset, int length) {
            MediaHeader h = new MediaHeader();
            Reader r = new Reader(data, offset, length);
            while (r.hasRemaining()) {
                int tag = r.readTag();
                int fn = r.getFieldNumber(tag);
                int wt = r.getWireType(tag);
                switch (fn) {
                    case 1:
                        if (wt == 0) h.headerId = r.readInt32();
                        else r.skipField(wt);
                        break;
                    case 2:
                        if (wt == 2) h.videoId = r.readString();
                        else r.skipField(wt);
                        break;
                    case 3:
                        if (wt == 0) h.itag = r.readInt32();
                        else r.skipField(wt);
                        break;
                    case 4:
                        if (wt == 0) h.lmt = r.readInt64();
                        else r.skipField(wt);
                        break;
                    case 5:
                        if (wt == 2) h.xtags = r.readString();
                        else r.skipField(wt);
                        break;
                    case 8:
                        if (wt == 0) h.isInitSeg = r.readBool();
                        else r.skipField(wt);
                        break;
                    case 9:
                        if (wt == 0) h.sequenceNumber = r.readInt32();
                        else r.skipField(wt);
                        break;
                    case 11:
                        if (wt == 0) h.startMs = r.readInt64();
                        else r.skipField(wt);
                        break;
                    case 12:
                        if (wt == 0) h.durationMs = r.readInt64();
                        else r.skipField(wt);
                        break;
                    case 13:
                        if (wt == 2) {
                            Reader sub = r.readSubMessage();
                            h.formatId = FormatId.decode(sub.data(), sub.startOffset(), sub.length());
                        } else r.skipField(wt);
                        break;
                    case 14:
                        if (wt == 0) h.contentLength = r.readInt64();
                        else r.skipField(wt);
                        break;
                    case 15:
                        if (wt == 2) {
                            Reader sub = r.readSubMessage();
                            h.timeRange = TimeRange.decode(sub.data(), sub.startOffset(), sub.length());
                        } else r.skipField(wt);
                        break;
                    default:
                        r.skipField(wt);
                        break;
                }
            }
            return h;
        }

        /** Format key: "itag.lmt.xtags" — matches googlevideo's fromMediaHeader() */
        public String formatKey() {
            return itag + "." + lmt + "." + (xtags != null ? xtags : "");
        }

        public boolean isAudio() {
            // Audio itags: 139-141, 171-172, 249-251, 256-258, 327, 380, 774
            return (itag >= 139 && itag <= 141)
                    || (itag >= 171 && itag <= 172)
                    || (itag >= 249 && itag <= 251)
                    || (itag >= 256 && itag <= 258)
                    || itag == 327 || itag == 380 || itag == 774;
        }
    }

    // =========================================================================
    // FormatInitializationMetadata — sent once per format
    // =========================================================================

    public static class FormatInitMetadata {
        public String videoId = "";
        public FormatId formatId;
        public long endTimeMs;
        public long endSegmentNumber;
        public String mimeType = "";
        public long durationUnits;
        public long durationTimescale;

        public static FormatInitMetadata decode(byte[] data, int offset, int length) {
            FormatInitMetadata m = new FormatInitMetadata();
            Reader r = new Reader(data, offset, length);
            while (r.hasRemaining()) {
                int tag = r.readTag();
                int fn = r.getFieldNumber(tag);
                int wt = r.getWireType(tag);
                switch (fn) {
                    case 1:
                        if (wt == 2) m.videoId = r.readString();
                        else r.skipField(wt);
                        break;
                    case 2:
                        if (wt == 2) {
                            Reader sub = r.readSubMessage();
                            m.formatId = FormatId.decode(sub.data(), sub.startOffset(), sub.length());
                        } else r.skipField(wt);
                        break;
                    case 3:
                        if (wt == 0) m.endTimeMs = r.readInt64();
                        else r.skipField(wt);
                        break;
                    case 4:
                        if (wt == 0) m.endSegmentNumber = r.readInt64();
                        else r.skipField(wt);
                        break;
                    case 5:
                        if (wt == 2) m.mimeType = r.readString();
                        else r.skipField(wt);
                        break;
                    case 9:
                        if (wt == 0) m.durationUnits = r.readInt64();
                        else r.skipField(wt);
                        break;
                    case 10:
                        if (wt == 0) m.durationTimescale = r.readInt64();
                        else r.skipField(wt);
                        break;
                    default:
                        r.skipField(wt);
                        break;
                }
            }
            return m;
        }

        public long durationMs() {
            if (durationTimescale == 0) return 0;
            return (long) ((double) durationUnits / durationTimescale * 1000.0);
        }

        public String formatKey() {
            if (formatId == null) return "";
            return formatId.itag + "." + formatId.lastModified + "." + formatId.xtags;
        }
    }

    // =========================================================================
    // NextRequestPolicy — server tells us when/how to request next
    // =========================================================================

    public static class NextRequestPolicy {
        public int targetAudioReadaheadMs;
        public int targetVideoReadaheadMs;
        public int backoffTimeMs;
        public byte[] playbackCookieRaw;  // raw encoded PlaybackCookie
        public String videoId = "";

        public static NextRequestPolicy decode(byte[] data, int offset, int length) {
            NextRequestPolicy p = new NextRequestPolicy();
            Reader r = new Reader(data, offset, length);
            while (r.hasRemaining()) {
                int tag = r.readTag();
                int fn = r.getFieldNumber(tag);
                int wt = r.getWireType(tag);
                switch (fn) {
                    case 1:
                        if (wt == 0) p.targetAudioReadaheadMs = r.readInt32();
                        else r.skipField(wt);
                        break;
                    case 2:
                        if (wt == 0) p.targetVideoReadaheadMs = r.readInt32();
                        else r.skipField(wt);
                        break;
                    case 4:
                        if (wt == 0) p.backoffTimeMs = r.readInt32();
                        else r.skipField(wt);
                        break;
                    case 7:
                        if (wt == 2) p.playbackCookieRaw = r.readBytes();
                        else r.skipField(wt);
                        break;
                    case 8:
                        if (wt == 2) p.videoId = r.readString();
                        else r.skipField(wt);
                        break;
                    default:
                        r.skipField(wt);
                        break;
                }
            }
            return p;
        }
    }

    // =========================================================================
    // SabrRedirect — server says "go to this URL instead"
    // =========================================================================

    public static class SabrRedirect {
        public String url = "";

        public static SabrRedirect decode(byte[] data, int offset, int length) {
            SabrRedirect s = new SabrRedirect();
            Reader r = new Reader(data, offset, length);
            while (r.hasRemaining()) {
                int tag = r.readTag();
                int fn = r.getFieldNumber(tag);
                int wt = r.getWireType(tag);
                switch (fn) {
                    case 1:
                        if (wt == 2) s.url = r.readString();
                        else r.skipField(wt);
                        break;
                    default:
                        r.skipField(wt);
                        break;
                }
            }
            return s;
        }
    }

    // =========================================================================
    // SabrError
    // =========================================================================

    public static class SabrError {
        public String type = "";
        public int code;

        public static SabrError decode(byte[] data, int offset, int length) {
            SabrError e = new SabrError();
            Reader r = new Reader(data, offset, length);
            while (r.hasRemaining()) {
                int tag = r.readTag();
                int fn = r.getFieldNumber(tag);
                int wt = r.getWireType(tag);
                switch (fn) {
                    case 1:
                        if (wt == 2) e.type = r.readString();
                        else r.skipField(wt);
                        break;
                    case 2:
                        if (wt == 0) e.code = r.readInt32();
                        else r.skipField(wt);
                        break;
                    default:
                        r.skipField(wt);
                        break;
                }
            }
            return e;
        }
    }

    // =========================================================================
    // SabrContextUpdate — context data for ads/tracking
    // =========================================================================

    public static class SabrContextUpdate {
        public int type;
        public int scope;
        public byte[] value;
        public boolean sendByDefault;
        public int writePolicy;

        public static SabrContextUpdate decode(byte[] data, int offset, int length) {
            SabrContextUpdate u = new SabrContextUpdate();
            Reader r = new Reader(data, offset, length);
            while (r.hasRemaining()) {
                int tag = r.readTag();
                int fn = r.getFieldNumber(tag);
                int wt = r.getWireType(tag);
                switch (fn) {
                    case 1:
                        if (wt == 0) u.type = r.readInt32();
                        else r.skipField(wt);
                        break;
                    case 2:
                        if (wt == 0) u.scope = r.readInt32();
                        else r.skipField(wt);
                        break;
                    case 3:
                        if (wt == 2) u.value = r.readBytes();
                        else r.skipField(wt);
                        break;
                    case 4:
                        if (wt == 0) u.sendByDefault = r.readBool();
                        else r.skipField(wt);
                        break;
                    case 5:
                        if (wt == 0) u.writePolicy = r.readInt32();
                        else r.skipField(wt);
                        break;
                    default:
                        r.skipField(wt);
                        break;
                }
            }
            return u;
        }

        public byte[] encode() {
            Writer w = new Writer();
            if (type != 0)  w.writeInt32(1, type);
            if (scope != 0) w.writeInt32(2, scope);
            if (value != null && value.length > 0) w.writeBytes(3, value);
            if (sendByDefault) w.writeBool(4, true);
            if (writePolicy != 0) w.writeInt32(5, writePolicy);
            return w.toByteArray();
        }
    }

    // =========================================================================
    // SabrContextSendingPolicy
    // =========================================================================

    public static class SabrContextSendingPolicy {
        public int[] startPolicy = new int[0];
        public int[] stopPolicy = new int[0];
        public int[] discardPolicy = new int[0];

        public static SabrContextSendingPolicy decode(byte[] data, int offset, int length) {
            SabrContextSendingPolicy p = new SabrContextSendingPolicy();
            // These are packed repeated int32 fields
            Reader r = new Reader(data, offset, length);
            java.util.List<Integer> starts = new java.util.ArrayList<>();
            java.util.List<Integer> stops = new java.util.ArrayList<>();
            java.util.List<Integer> discards = new java.util.ArrayList<>();
            while (r.hasRemaining()) {
                int tag = r.readTag();
                int fn = r.getFieldNumber(tag);
                int wt = r.getWireType(tag);
                if (wt == 2) { // packed
                    Reader sub = r.readSubMessage();
                    java.util.List<Integer> target = fn == 1 ? starts : fn == 2 ? stops : fn == 3 ? discards : null;
                    if (target != null) {
                        while (sub.hasRemaining()) target.add(sub.readInt32());
                    }
                } else if (wt == 0) { // non-packed
                    int val = r.readInt32();
                    if (fn == 1) starts.add(val);
                    else if (fn == 2) stops.add(val);
                    else if (fn == 3) discards.add(val);
                } else {
                    r.skipField(wt);
                }
            }
            p.startPolicy = starts.stream().mapToInt(i -> i).toArray();
            p.stopPolicy = stops.stream().mapToInt(i -> i).toArray();
            p.discardPolicy = discards.stream().mapToInt(i -> i).toArray();
            return p;
        }
    }

    // =========================================================================
    // StreamProtectionStatus
    // =========================================================================

    public static class StreamProtectionStatus {
        public int status;  // 0=ok, 2=pending, 3=attestation required
        public int maxRetries;

        public static StreamProtectionStatus decode(byte[] data, int offset, int length) {
            StreamProtectionStatus s = new StreamProtectionStatus();
            Reader r = new Reader(data, offset, length);
            while (r.hasRemaining()) {
                int tag = r.readTag();
                int fn = r.getFieldNumber(tag);
                int wt = r.getWireType(tag);
                switch (fn) {
                    case 1:
                        if (wt == 0) s.status = r.readInt32();
                        else r.skipField(wt);
                        break;
                    case 2:
                        if (wt == 0) s.maxRetries = r.readInt32();
                        else r.skipField(wt);
                        break;
                    default:
                        r.skipField(wt);
                        break;
                }
            }
            return s;
        }
    }

    // =========================================================================
    // VideoPlaybackAbrRequest — the main request body
    // =========================================================================

    /**
     * Build a VideoPlaybackAbrRequest protobuf.
     *
     * Field numbers reverse-engineered from browser HAR capture of working MWEB SABR request.
     * The MWEB player uses different field semantics than the googlevideo npm package's
     * WEB proto definitions — field numbers are the same but meanings differ.
     *
     * Initial request (requestNumber=0): only f1 (ClientAbrState) + f5 (ustreamerConfig)
     * Subsequent requests: add f2 (selectedFormats), f3 (bufferedRanges), f4 (playerTimeMs),
     *   f19 (streamerContext with playbackCookie, sabrContexts)
     */
    public static byte[] buildAbrRequest(
            long playerTimeMs,
            int stickyResolution,
            String audioTrackId,
            FormatId[] videoFormats,
            FormatId[] audioFormats,
            FormatId[] selectedFormats,
            byte[][] bufferedRanges,
            byte[] ustreamerConfig,
            byte[] poToken,
            byte[] playbackCookie,
            SabrContextUpdate[] sabrContexts,
            int[] unsentContextTypes,
            int clientName,
            String clientVersion
    ) {
        // =================================================================
        // ClientAbrState (request field 1)
        // Field numbers from browser HAR capture of MWEB SABR request:
        //   f13 = monotonic timestamp (ms)
        //   f14 = clientType (2=MWEB)
        //   f16 = stickyResolution
        //   f18 = screenHeight
        //   f19 = viewportHeight
        //   f21 = bufferedMs (0 for initial)
        //   f23 = capabilityBitmask
        //   f28 = playerTimeMs
        //   f29 = bandwidthQuality
        //   f34 = visibility (0=hidden, 1=visible)
        //   f36 = networkMetering
        //   f39 = connectionType
        //   f46 = drcEnabled (bool)
        //   f57 = targetLatencyMs
        //   f58 = 0
        //   f59 = maxResolutionSupported (2160)
        //   f68 = 0
        //   f71 = true
        //   f72 = viewport config message
        //   f79 = capabilities message
        //   f80 = sabrSupported (bool)
        // =================================================================
        boolean isMweb = (clientName == CLIENT_MWEB);
        Writer abrState = new Writer();
        abrState.writeInt64(13, System.nanoTime() / 1000000);  // monotonic timestamp
        abrState.writeInt32(14, isMweb ? 2 : 0);              // clientType
        int screenRes = isMweb ? Math.min(stickyResolution, 1080) : stickyResolution;
        abrState.writeInt32(16, screenRes);
        abrState.writeInt32(18, isMweb ? screenRes : 2966);
        abrState.writeInt32(19, isMweb ? 608 : 1668);
        abrState.writeInt32(21, 0);
        abrState.writeInt32(23, isMweb ? 6049476 : 18613774);
        abrState.writeInt64(28, playerTimeMs);
        abrState.writeInt32(29, isMweb ? 4 : 3);
        abrState.writeInt32(34, 0);
        abrState.writeInt32(36, isMweb ? 9 : 10);
        abrState.writeInt32(39, isMweb ? 16 : 34);
        abrState.writeBool(46, true);                          // drcEnabled
        abrState.writeInt32(57, isMweb ? 173 : 56);
        abrState.writeInt32(58, 0);
        abrState.writeInt32(59, 2160);
        abrState.writeInt32(68, 0);
        abrState.writeBool(71, true);
        // f72: viewport config
        Writer viewport = new Writer();
        viewport.writeInt32(1, isMweb ? 360 : 0);
        viewport.writeInt32(2, 2160);
        viewport.writeInt32(3, 0);
        viewport.writeInt32(4, 0);
        viewport.writeInt32(5, 2160);
        viewport.writeInt32(6, 0);
        abrState.writeMessage(72, viewport);
        // f79: capabilities [{1,0}, {2,0}, {2,1}]
        Writer caps = new Writer();
        Writer c1 = new Writer(); c1.writeInt32(1, 1); c1.writeInt32(2, 0);
        Writer c2 = new Writer(); c2.writeInt32(1, 2); c2.writeInt32(2, 0);
        Writer c3 = new Writer(); c3.writeInt32(1, 2); c3.writeInt32(2, 1);
        caps.writeMessage(1, c1);
        caps.writeMessage(1, c2);
        caps.writeMessage(1, c3);
        abrState.writeBytes(79, caps.toByteArray());
        abrState.writeBool(80, true);
        if (audioTrackId != null && !audioTrackId.isEmpty()) {
            abrState.writeString(69, audioTrackId);
        }

        // =================================================================
        // Build the request
        // Initial request: ONLY f1 + f5 (matches browser HAR capture)
        // Subsequent requests: add f2, f3, f4, f19 as data becomes available
        // =================================================================
        Writer req = new Writer();
        req.writeMessage(1, abrState);

        // f2: selectedFormatIds (only after formats are initialized from server response)
        if (selectedFormats != null) {
            for (FormatId fmt : selectedFormats) {
                req.writeBytes(2, fmt.encode());
            }
        }

        // f3: bufferedRanges (only after we have downloaded segments)
        if (bufferedRanges != null) {
            for (byte[] br : bufferedRanges) {
                req.writeBytes(3, br);
            }
        }

        // f4: playerTimeMs (only for subsequent requests)
        if (playerTimeMs != 0) {
            req.writeInt64(4, playerTimeMs);
        }

        // f5: ustreamerConfig (ALWAYS required)
        if (ustreamerConfig != null) {
            req.writeBytes(5, ustreamerConfig);
        }

        // f16/f17: preferred formats — only for subsequent requests
        if (audioFormats != null) {
            for (FormatId fmt : audioFormats) {
                req.writeBytes(16, fmt.encode());
            }
        }
        if (videoFormats != null) {
            for (FormatId fmt : videoFormats) {
                req.writeBytes(17, fmt.encode());
            }
        }

        // f19: StreamerContext — only for subsequent requests (playbackCookie, sabrContexts)
        // The browser's INITIAL request does NOT include f19 at all.
        // It gets added on subsequent requests when we have playbackCookie from NextRequestPolicy.
        boolean hasStreamerCtxData = (playbackCookie != null && playbackCookie.length > 0)
                || (sabrContexts != null && sabrContexts.length > 0)
                || (unsentContextTypes != null && unsentContextTypes.length > 0)
                || (poToken != null && poToken.length > 0);

        if (hasStreamerCtxData) {
            Writer streamerCtx = new Writer();

            // f19.f1: clientInfo
            Writer clientInfo = new Writer();
            if (isMweb) {
                clientInfo.writeInt32(16, CLIENT_MWEB);
                clientInfo.writeString(17, clientVersion);
                clientInfo.writeString(18, "Android");
                clientInfo.writeString(19, "16");
            } else {
                clientInfo.writeInt32(16, CLIENT_WEB);
                clientInfo.writeString(17, clientVersion);
                clientInfo.writeString(18, "X11");
            }
            streamerCtx.writeMessage(1, clientInfo);

            // f19.f2: PO token
            if (poToken != null && poToken.length > 0) {
                streamerCtx.writeBytes(2, poToken);
            }

            // f19.f3: playbackCookie
            if (playbackCookie != null && playbackCookie.length > 0) {
                streamerCtx.writeBytes(3, playbackCookie);
            }

            // f19.f5: sabrContexts
            if (sabrContexts != null) {
                for (SabrContextUpdate ctx : sabrContexts) {
                    Writer ctxWriter = new Writer();
                    ctxWriter.writeInt32(1, ctx.type);
                    if (ctx.value != null) ctxWriter.writeBytes(2, ctx.value);
                    streamerCtx.writeMessage(5, ctxWriter);
                }
            }

            // f19.f6: unsentSabrContexts
            if (unsentContextTypes != null && unsentContextTypes.length > 0) {
                streamerCtx.writePackedInt32(6, unsentContextTypes);
            }

            req.writeMessage(19, streamerCtx);
        }

        return req.toByteArray();
    }

    // =========================================================================
    // BufferedRange builder
    // =========================================================================

    public static byte[] buildBufferedRange(FormatId formatId, long startTimeMs,
                                            long durationMs, int startSegment, int endSegment,
                                            TimeRange timeRange) {
        Writer w = new Writer();
        if (formatId != null) w.writeBytes(1, formatId.encode());
        if (startTimeMs != 0) w.writeInt64(2, startTimeMs);
        if (durationMs != 0)  w.writeInt64(3, durationMs);
        if (startSegment != 0) w.writeInt32(4, startSegment);
        if (endSegment != 0)   w.writeInt32(5, endSegment);
        if (timeRange != null) w.writeBytes(6, timeRange.encode());
        return w.toByteArray();
    }
}
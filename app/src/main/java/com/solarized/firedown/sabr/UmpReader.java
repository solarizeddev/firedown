package com.solarized.firedown.sabr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Parser for YouTube's UMP (Universal Media Protocol) binary format.
 *
 * UMP frames consist of:
 *   [partType: varint] [partSize: varint] [partData: bytes]
 *
 * The varint encoding uses a custom scheme (not standard protobuf varint):
 *   1 byte:  value < 128       → value = byte
 *   2 bytes: value < 192       → value = (b1 & 0x3F) + 64 * b2
 *   3 bytes: value < 224       → value = (b1 & 0x1F) + 32 * (b2 + 256 * b3)
 *   4 bytes: value < 240       → value = (b1 & 0x0F) + 16 * (b2 + 256 * (b3 + 256 * b4))
 *   5 bytes: otherwise         → value = b2 + 256 * (b3 + 256 * (b4 + 256 * b5))
 */
public class UmpReader {

    /** UMP Part IDs (from ump_part_id.proto) */
    public static final int ONESIE_HEADER = 10;
    public static final int ONESIE_DATA = 11;
    public static final int MEDIA_HEADER = 20;
    public static final int MEDIA = 21;
    public static final int MEDIA_END = 22;
    public static final int LIVE_METADATA = 31;
    public static final int NEXT_REQUEST_POLICY = 35;
    public static final int FORMAT_INITIALIZATION_METADATA = 42;
    public static final int SABR_REDIRECT = 43;
    public static final int SABR_ERROR = 44;
    public static final int RELOAD_PLAYER_RESPONSE = 46;
    public static final int PLAYBACK_START_POLICY = 47;
    public static final int ALLOWED_CACHED_FORMATS = 48;
    public static final int START_BW_SAMPLING_HINT = 49;
    public static final int SELECTABLE_FORMATS = 51;
    public static final int REQUEST_IDENTIFIER = 52;
    // Part 53 — "Server-Driven Request Cancellation" hint.
    // Schema (reverse-engineered from YouTube's player base.js):
    //   message Part53 {
    //     int64 timerDelayMs = 1;      // field 'jM' — delay before callback fires
    //     repeated Item items = 2;     // buffer thresholds by position
    //     int64 secondaryDelayMs = 3;  // field 'zc' — used by a different handler path
    //   }
    //   message Item {
    //     int64 positionThreshold = 1;  // field 'GK' — applies once playhead passes this
    //     int64 bufferTarget = 2;       // field 'Ie' — cancel current req if buffered beyond this
    //     int64 minReadaheadMs = 3;     // field 'minReadaheadMs'
    //   }
    // Gated by policy.enableServerDrivenRequestCancellation; purely a live-playback
    // optimization to cancel in-flight requests early when enough is buffered. Not
    // relevant for whole-video downloaders — we want to keep fetching regardless.
    public static final int SERVER_DRIVEN_CANCELLATION = 53;
    public static final int SABR_CONTEXT_UPDATE = 57;
    public static final int STREAM_PROTECTION_STATUS = 58;
    public static final int SABR_CONTEXT_SENDING_POLICY = 59;
    public static final int SNACKBAR_MESSAGE = 67;

    /** Callback for each parsed UMP part */
    public interface PartHandler {
        /**
         * Called for each complete UMP part.
         * @param partType  The UMP part type ID
         * @param partData  The raw part payload
         * @param offset    Offset into partData
         * @param length    Length of payload
         * @return true to stop reading (e.g. on terminal parts), false to continue
         */
        boolean onPart(int partType, byte[] partData, int offset, int length);
    }

    /**
     * Read all UMP parts from an InputStream (e.g. OkHttp response body).
     * Reads in chunks and handles parts that span multiple chunks.
     */
    public static void readStream(InputStream in, PartHandler handler) throws IOException {
        // Accumulation buffer for partial reads
        ByteArrayOutputStream accumulator = new ByteArrayOutputStream(65536);
        byte[] readBuf = new byte[32768];

        while (true) {
            int bytesRead = in.read(readBuf);
            if (bytesRead == -1) break;

            accumulator.write(readBuf, 0, bytesRead);
            byte[] buffer = accumulator.toByteArray();
            int consumed = processBuffer(buffer, 0, buffer.length, handler);

            if (consumed < 0) break; // handler requested stop

            // Keep unconsumed bytes for next iteration
            if (consumed > 0) {
                accumulator.reset();
                if (consumed < buffer.length) {
                    accumulator.write(buffer, consumed, buffer.length - consumed);
                }
            }
        }
    }

    /**
     * Process buffered data, extracting complete UMP parts.
     * @return Number of bytes consumed, or -1 if handler requested stop
     */
    private static int processBuffer(byte[] buf, int offset, int limit, PartHandler handler) {
        int pos = offset;

        while (pos < limit) {
            int startPos = pos;

            // Read partType varint
            long[] result = readUmpVarint(buf, pos, limit);
            if (result == null) break; // incomplete
            int partType = (int) result[0];
            pos = (int) result[1];

            // Read partSize varint
            result = readUmpVarint(buf, pos, limit);
            if (result == null) { pos = startPos; break; } // incomplete
            int partSize = (int) result[0];
            pos = (int) result[1];

            // Check if full payload is available
            if (pos + partSize > limit) {
                pos = startPos; // rewind — need more data
                break;
            }

            // Deliver part to handler
            boolean stop = handler.onPart(partType, buf, pos, partSize);
            pos += partSize;

            if (stop) return -1;
        }

        return pos - offset;
    }

    /**
     * Read a UMP-style varint from buffer.
     * @return [value, newOffset] or null if not enough data
     */
    private static long[] readUmpVarint(byte[] buf, int offset, int limit) {
        if (offset >= limit) return null;

        int firstByte = buf[offset] & 0xFF;
        int byteLength;

        if (firstByte < 128)      byteLength = 1;
        else if (firstByte < 192) byteLength = 2;
        else if (firstByte < 224) byteLength = 3;
        else if (firstByte < 240) byteLength = 4;
        else                      byteLength = 5;

        if (offset + byteLength > limit) return null;

        long value = switch (byteLength) {
            case 1 -> firstByte;
            case 2 -> (firstByte & 0x3F) + 64 * (buf[offset + 1] & 0xFF);
            case 3 -> (firstByte & 0x1F)
                    + 32 * ((buf[offset + 1] & 0xFF) + 256 * (buf[offset + 2] & 0xFF));
            case 4 -> (firstByte & 0x0F)
                    + 16 * ((buf[offset + 1] & 0xFF)
                    + 256 * ((buf[offset + 2] & 0xFF)
                    + 256 * (buf[offset + 3] & 0xFF)));
            default -> // 5 bytes
                    (buf[offset + 1] & 0xFF)
                            + 256L * ((buf[offset + 2] & 0xFF)
                            + 256L * ((buf[offset + 3] & 0xFF)
                            + 256L * (buf[offset + 4] & 0xFF)));
        };

        return new long[]{ value, offset + byteLength };
    }

    /** Get human-readable name for a UMP part type */
    public static String partTypeName(int type) {
        return switch (type) {
            case MEDIA_HEADER -> "MEDIA_HEADER";
            case MEDIA -> "MEDIA";
            case MEDIA_END -> "MEDIA_END";
            case NEXT_REQUEST_POLICY -> "NEXT_REQUEST_POLICY";
            case FORMAT_INITIALIZATION_METADATA -> "FORMAT_INIT_METADATA";
            case SABR_REDIRECT -> "SABR_REDIRECT";
            case SABR_ERROR -> "SABR_ERROR";
            case RELOAD_PLAYER_RESPONSE -> "RELOAD_PLAYER_RESPONSE";
            case SERVER_DRIVEN_CANCELLATION -> "SERVER_DRIVEN_CANCELLATION";
            case SABR_CONTEXT_UPDATE -> "SABR_CONTEXT_UPDATE";
            case STREAM_PROTECTION_STATUS -> "STREAM_PROTECTION_STATUS";
            case SABR_CONTEXT_SENDING_POLICY -> "SABR_CONTEXT_SENDING_POLICY";
            case SNACKBAR_MESSAGE -> "SNACKBAR_MESSAGE";
            default -> "UNKNOWN(" + type + ")";
        };
    }
}
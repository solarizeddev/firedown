package com.solarized.firedown.sabr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Lightweight protobuf wire format encoder/decoder.
 * No protoc or external dependency needed — just raw wire format.
 */
public class ProtobufWire {

    // =========================================================================
    // WRITER
    // =========================================================================

    public static class Writer {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(256);

        /** Write varint field: tag = (fieldNumber << 3) | 0 */
        public Writer writeVarint(int fieldNumber, long value) {
            writeRawVarint(((long) fieldNumber << 3));          // wire type 0
            writeRawVarint(value);
            return this;
        }

        /** Write int32 field */
        public Writer writeInt32(int fieldNumber, int value) {
            return writeVarint(fieldNumber, value);
        }

        /** Write uint64/int64 field */
        public Writer writeInt64(int fieldNumber, long value) {
            return writeVarint(fieldNumber, value);
        }

        /** Write bool field */
        public Writer writeBool(int fieldNumber, boolean value) {
            return writeVarint(fieldNumber, value ? 1 : 0);
        }

        /** Write float field: tag = (fieldNumber << 3) | 5 */
        public Writer writeFloat(int fieldNumber, float value) {
            writeRawVarint(((long) fieldNumber << 3) | 5);      // wire type 5 (32-bit)
            int bits = Float.floatToIntBits(value);
            buf.write(bits & 0xFF);
            buf.write((bits >> 8) & 0xFF);
            buf.write((bits >> 16) & 0xFF);
            buf.write((bits >> 24) & 0xFF);
            return this;
        }

        /** Write string field: tag = (fieldNumber << 3) | 2 */
        public Writer writeString(int fieldNumber, String value) {
            if (value == null || value.isEmpty()) return this;
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            return writeBytes(fieldNumber, bytes);
        }

        /** Write bytes field: tag = (fieldNumber << 3) | 2 */
        public Writer writeBytes(int fieldNumber, byte[] value) {
            if (value == null || value.length == 0) return this;
            writeRawVarint((fieldNumber << 3) | 2);      // wire type 2
            writeRawVarint(value.length);
            buf.write(value, 0, value.length);
            return this;
        }

        /** Write embedded message field */
        public Writer writeMessage(int fieldNumber, Writer sub) {
            byte[] data = sub.toByteArray();
            return writeBytes(fieldNumber, data);
        }

        /** Write packed repeated int32 field */
        public Writer writePackedInt32(int fieldNumber, int[] values) {
            if (values == null || values.length == 0) return this;
            ByteArrayOutputStream packed = new ByteArrayOutputStream();
            for (int v : values) {
                writeVarintTo(packed, v);
            }
            byte[] data = packed.toByteArray();
            writeRawVarint(((long) fieldNumber << 3) | 2);
            writeRawVarint(data.length);
            buf.write(data, 0, data.length);
            return this;
        }

        public byte[] toByteArray() {
            return buf.toByteArray();
        }

        private void writeRawVarint(long value) {
            writeVarintTo(buf, value);
        }

        private static void writeVarintTo(ByteArrayOutputStream out, long value) {
            // Handle negative values by treating as unsigned
            while (true) {
                if ((value & ~0x7FL) == 0) {
                    out.write((int) value);
                    return;
                }
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
        }
    }

    // =========================================================================
    // READER
    // =========================================================================

    public static class Reader {
        private final byte[] data;
        private int pos;
        private final int limit;
        private final int startOffset;

        public Reader(byte[] data) {
            this(data, 0, data.length);
        }

        public Reader(byte[] data, int offset, int length) {
            this.data = data;
            this.pos = offset;
            this.startOffset = offset;
            this.limit = offset + length;
        }

        public boolean hasRemaining() {
            return pos < limit;
        }

        public int getPos() {
            return pos;
        }

        /** Access to underlying byte array */
        public byte[] data() { return data; }

        /** Start offset of this reader's view */
        public int startOffset() { return startOffset; }

        /** Length of this reader's view */
        public int length() { return limit - startOffset; }

        /** Read tag (fieldNumber << 3 | wireType) */
        public int readTag() {
            if (pos >= limit) return 0;
            return (int) readRawVarint();
        }

        public int getFieldNumber(int tag) {
            return tag >>> 3;
        }

        public int getWireType(int tag) {
            return tag & 0x07;
        }

        public long readVarint() {
            return readRawVarint();
        }

        public int readInt32() {
            return (int) readRawVarint();
        }

        public long readInt64() {
            return readRawVarint();
        }

        public boolean readBool() {
            return readRawVarint() != 0;
        }

        public float readFloat() {
            checkRemaining(4, "readFloat");
            int bits = (data[pos] & 0xFF)
                    | ((data[pos + 1] & 0xFF) << 8)
                    | ((data[pos + 2] & 0xFF) << 16)
                    | ((data[pos + 3] & 0xFF) << 24);
            pos += 4;
            return Float.intBitsToFloat(bits);
        }

        public String readString() {
            int len = readLengthPrefix("readString");
            String s = new String(data, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        public byte[] readBytes() {
            int len = readLengthPrefix("readBytes");
            byte[] result = new byte[len];
            System.arraycopy(data, pos, result, 0, len);
            pos += len;
            return result;
        }

        /** Get a sub-reader for an embedded message */
        public Reader readSubMessage() {
            int len = readLengthPrefix("readSubMessage");
            Reader sub = new Reader(data, pos, len);
            pos += len;
            return sub;
        }

        /** Skip a field based on wire type */
        public void skipField(int wireType) {
            switch (wireType) {
                case 0: readRawVarint(); break;              // varint
                case 1: checkRemaining(8, "skip 64-bit"); pos += 8; break;
                case 2: {                                    // length-delimited
                    int len = readLengthPrefix("skip length-delimited");
                    pos += len;
                    break;
                }
                case 3: // start group (deprecated) — skip until end group
                    while (true) {
                        int innerTag = readTag();
                        if (innerTag == 0) break;
                        int innerWt = getWireType(innerTag);
                        if (innerWt == 4) break; // end group
                        skipField(innerWt);
                    }
                    break;
                case 4: break;                               // end group — nothing to skip
                case 5: checkRemaining(4, "skip 32-bit"); pos += 4; break;
                default:
                    throw new IllegalStateException(
                            "Unknown wire type " + wireType + " at pos=" + pos);
            }
        }

        /** Read a length-delimited prefix and verify it fits within the buffer. */
        private int readLengthPrefix(String op) {
            long lenLong = readRawVarint();
            if (lenLong < 0 || lenLong > Integer.MAX_VALUE) {
                throw new IllegalStateException(
                        op + ": invalid length " + lenLong + " at pos=" + pos);
            }
            int len = (int) lenLong;
            if (pos + len > limit) {
                throw new IllegalStateException(
                        op + ": length " + len + " exceeds remaining "
                                + (limit - pos) + " bytes (pos=" + pos
                                + ", limit=" + limit + ", dataLen=" + data.length + ")");
            }
            return len;
        }

        /** Ensure at least n more bytes are available in this reader's view. */
        private void checkRemaining(int n, String op) {
            if (pos + n > limit) {
                throw new IllegalStateException(
                        op + ": need " + n + " bytes, only "
                                + (limit - pos) + " remaining (pos=" + pos
                                + ", limit=" + limit + ")");
            }
        }

        private long readRawVarint() {
            long result = 0;
            int shift = 0;
            while (pos < limit) {
                byte b = data[pos++];
                result |= (long)(b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
                if (shift >= 64) throw new IllegalStateException("Varint too long");
            }
            return result;
        }
    }
}
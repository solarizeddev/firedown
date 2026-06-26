package com.solarized.firedown.sync.crypto;

/**
 * Crockford base32 (https://www.crockford.com/base32.html) for the bookmarks
 * sync account id. Must round-trip byte-for-byte with the Go server's
 * implementation (the server normalizes to uppercase and strips hyphens before
 * decoding); the shared interop vectors (crockford.json) pin both sides.
 *
 * <p>16 bytes -&gt; 26 chars; decode is case-insensitive and strips hyphens.
 * MSB-first, no padding. Alphabet has no I/L/O/U; on decode I/L map to 1, O to
 * 0, and U is rejected (Crockford reserves it for a checksum we don't use).</p>
 */
public final class Crockford {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int[] DECODE = new int[128];

    static {
        for (int i = 0; i < DECODE.length; i++) {
            DECODE[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length; i++) {
            char c = ALPHABET[i];
            DECODE[c] = i;
            if (c >= 'A' && c <= 'Z') {
                DECODE[Character.toLowerCase(c)] = i;
            }
        }
        // Crockford ambiguity substitutions.
        DECODE['I'] = DECODE['i'] = 1;
        DECODE['L'] = DECODE['l'] = 1;
        DECODE['O'] = DECODE['o'] = 0;
        // 'U'/'u' deliberately left -1 (rejected).
    }

    private Crockford() {}

    /** Encodes bytes as Crockford base32 (no padding, no hyphens). */
    public static String encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(ALPHABET[(buffer >> bits) & 0x1f]);
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer << (5 - bits)) & 0x1f]);
        }
        return out.toString();
    }

    /**
     * Decodes a Crockford base32 string (case-insensitive, hyphens stripped).
     *
     * @throws IllegalArgumentException on an invalid character (including 'U').
     */
    public static byte[] decode(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-') {
                continue;
            }
            int v = (c < 128) ? DECODE[c] : -1;
            if (v < 0) {
                throw new IllegalArgumentException("invalid crockford base32");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }
}

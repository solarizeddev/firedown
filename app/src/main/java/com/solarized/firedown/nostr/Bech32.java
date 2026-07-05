package com.solarized.firedown.nostr;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;

/**
 * Minimal Bech32 decoder — just enough to normalize a NIP-19 {@code npub1…}
 * public key back to the 32-byte hex NIP-07 mandates. Amber may return either
 * form from {@code get_public_key}; the web API contract is lowercase hex.
 *
 * <p>Standard Bech32 (BIP-173), NOT Bech32m — npub uses the original checksum
 * constant. We verify the checksum and reject anything malformed rather than
 * returning a half-decoded key.</p>
 */
final class Bech32 {

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] GENERATORS = {
            0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
    };

    private Bech32() {
    }

    /**
     * Decodes a bech32 string and, if its human-readable part is {@code npub},
     * returns the 64-char lowercase hex of its 32-byte payload. Returns null on
     * any malformed input, checksum failure, wrong HRP, or unexpected length.
     */
    @Nullable
    static String npubToHex(@Nullable String bech) {
        if (bech == null) {
            return null;
        }
        // Bech32 is case-insensitive but must not be mixed case.
        String lower = bech.toLowerCase();
        String upper = bech.toUpperCase();
        if (!bech.equals(lower) && !bech.equals(upper)) {
            return null;
        }
        String s = lower;

        int sep = s.lastIndexOf('1');
        if (sep < 1 || sep + 7 > s.length()) {
            // Need a non-empty HRP and at least a 6-char checksum after it.
            return null;
        }
        String hrp = s.substring(0, sep);
        if (!"npub".equals(hrp)) {
            return null;
        }

        String dataPart = s.substring(sep + 1);
        int[] data = new int[dataPart.length()];
        for (int i = 0; i < dataPart.length(); i++) {
            int v = CHARSET.indexOf(dataPart.charAt(i));
            if (v < 0) {
                return null;
            }
            data[i] = v;
        }

        if (!verifyChecksum(hrp, data)) {
            return null;
        }

        // Strip the 6-symbol checksum, convert 5-bit groups back to bytes.
        int[] payload5 = new int[data.length - 6];
        System.arraycopy(data, 0, payload5, 0, payload5.length);
        byte[] bytes = convertBits(payload5, 5, 8, false);
        if (bytes == null || bytes.length != 32) {
            return null;
        }

        StringBuilder hex = new StringBuilder(64);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static boolean verifyChecksum(String hrp, int[] data) {
        int[] expanded = hrpExpand(hrp);
        int[] values = new int[expanded.length + data.length];
        System.arraycopy(expanded, 0, values, 0, expanded.length);
        System.arraycopy(data, 0, values, expanded.length, data.length);
        return polymod(values) == 1;
    }

    private static int[] hrpExpand(String hrp) {
        int[] out = new int[hrp.length() * 2 + 1];
        for (int i = 0; i < hrp.length(); i++) {
            out[i] = hrp.charAt(i) >> 5;
        }
        out[hrp.length()] = 0;
        for (int i = 0; i < hrp.length(); i++) {
            out[hrp.length() + 1 + i] = hrp.charAt(i) & 31;
        }
        return out;
    }

    private static int polymod(int[] values) {
        int chk = 1;
        for (int value : values) {
            int top = chk >>> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ value;
            for (int i = 0; i < 5; i++) {
                if (((top >>> i) & 1) != 0) {
                    chk ^= GENERATORS[i];
                }
            }
        }
        return chk;
    }

    /**
     * Regroups bits from {@code fromBits}-wide symbols into {@code toBits}-wide
     * symbols. {@code pad=false} rejects leftover non-zero padding (the correct
     * mode for decoding a fixed 32-byte key). Returns null on invalid input.
     */
    @Nullable
    private static byte[] convertBits(int[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        int maxv = (1 << toBits) - 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int value : data) {
            if (value < 0 || (value >>> fromBits) != 0) {
                return null;
            }
            acc = (acc << fromBits) | value;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                out.write((acc >>> bits) & maxv);
            }
        }
        if (pad) {
            if (bits > 0) {
                out.write((acc << (toBits - bits)) & maxv);
            }
        } else if (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0) {
            return null;
        }
        return out.toByteArray();
    }
}

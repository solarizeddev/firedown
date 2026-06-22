package com.solarized.firedown.sync.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Registration hashcash (cloud-sync-spec-api.md §4). The client searches for a
 * nonce such that
 * {@code SHA-256("firedown/register/v1\n" || account_id || challenge || nonce)}
 * has at least {@code powBits} leading zero bits. K=20 (the server default) is a
 * few hundred ms on a phone.
 */
public final class Pow {

    /** Domain-separation prefix; must match the server byte-for-byte (trailing \n). */
    public static final String PREFIX = "firedown/register/v1\n";

    private Pow() {}

    /** Counts most-significant zero bits in {@code h}, big-endian (spec §4.2). */
    public static int leadingZeroBits(byte[] h) {
        int n = 0;
        for (byte value : h) {
            int u = value & 0xff;
            if (u == 0) {
                n += 8;
                continue;
            }
            for (int mask = 0x80; mask != 0; mask >>>= 1) {
                if ((u & mask) != 0) {
                    return n;
                }
                n++;
            }
            return n;
        }
        return n;
    }

    /** H = SHA-256(PREFIX || accountId || challenge || nonce). */
    public static byte[] hash(byte[] accountId, byte[] challenge, byte[] nonce) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(PREFIX.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            md.update(accountId);
            md.update(challenge);
            md.update(nonce);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Whether {@code nonce} solves the challenge to at least {@code powBits} bits. */
    public static boolean verify(byte[] accountId, byte[] challenge, byte[] nonce, int powBits) {
        return leadingZeroBits(hash(accountId, challenge, nonce)) >= powBits;
    }

    /**
     * Solves the PoW by incrementing an 8-byte big-endian counter nonce until the
     * difficulty is met. Deterministic (counter from 0); typically returns in a
     * few hundred ms at K=20. Returns the winning nonce.
     */
    public static byte[] solve(byte[] accountId, byte[] challenge, int powBits) {
        byte[] nonce = new byte[8];
        for (long i = 0; ; i++) {
            putLongBE(nonce, i);
            if (verify(accountId, challenge, nonce, powBits)) {
                return nonce.clone();
            }
        }
    }

    private static void putLongBE(byte[] b, long v) {
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) (v & 0xff);
            v >>>= 8;
        }
    }
}

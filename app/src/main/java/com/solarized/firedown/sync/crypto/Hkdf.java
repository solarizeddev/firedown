package com.solarized.firedown.sync.crypto;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF-SHA256 (RFC 5869) — extract + expand. Used to derive the sync identity
 * keys from the recovery code (full-entropy random, so no Argon2 stretching is
 * needed). Built on {@link Mac} {@code HmacSHA256} (stdlib).
 */
public final class Hkdf {

    private static final String HMAC = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private Hkdf() {}

    /** HKDF-Extract: PRK = HMAC(salt, ikm). A null/empty salt becomes 32 zero bytes. */
    public static byte[] extract(byte[] salt, byte[] ikm) {
        try {
            byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(effectiveSalt, HMAC));
            return mac.doFinal(ikm);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HKDF extract failed", e);
        }
    }

    /** HKDF-Expand: OKM of {@code length} bytes from PRK + info. */
    public static byte[] expand(byte[] prk, byte[] info, int length) {
        if (length < 0 || length > 255 * HASH_LEN) {
            throw new IllegalArgumentException("invalid HKDF length");
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(prk, HMAC));
            byte[] okm = new byte[length];
            byte[] t = new byte[0];
            int pos = 0;
            int counter = 1;
            while (pos < length) {
                mac.reset();
                mac.update(t);
                mac.update(info);
                mac.update((byte) counter);
                t = mac.doFinal();
                int n = Math.min(t.length, length - pos);
                System.arraycopy(t, 0, okm, pos, n);
                pos += n;
                counter++;
            }
            return okm;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HKDF expand failed", e);
        }
    }

    /** Convenience: extract-then-expand with ASCII info. */
    public static byte[] derive(byte[] salt, byte[] ikm, String info, int length) {
        byte[] prk = extract(salt, ikm);
        byte[] out = expand(prk, info.getBytes(java.nio.charset.StandardCharsets.US_ASCII), length);
        Arrays.fill(prk, (byte) 0);
        return out;
    }
}

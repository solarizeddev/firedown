package com.solarized.firedown.manager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Deezer track decryption — the "not-DRM" crux.
 *
 * <p>A Deezer stream is Blowfish-CBC ciphertext, but NOT in the EME/Widevine
 * sense: there is no license server and no per-session key. The 16-byte Blowfish
 * key is DERIVED, offline and deterministically, from the track's public
 * {@code SNG_ID} XOR'd with a hardcoded static secret. Only every THIRD 2048-byte
 * "stripe" is enciphered (the {@code BF_CBC_STRIPE} cipher Deezer's get_url
 * returns); the other two-thirds are plaintext. A full stripe is required — the
 * trailing short stripe is never enciphered.
 *
 * <p>Because the key comes from {@code SNG_ID} alone, a captured stream decrypts
 * with nothing but the id — which is why the capture carries the id (in the
 * synthetic URL) rather than any key material. Pure JDK crypto; no native code.
 *
 * <p><b>Maintenance liability:</b> {@link #SECRET} is the one thing that could
 * break silently. It has been stable for years, but if Deezer ever rotates it,
 * every download decrypts to garbage — that is the first suspect for a Deezer
 * regression, not the transport.
 */
final class DeezerCrypto {

    /** The well-known static Blowfish key-derivation secret. */
    private static final String SECRET = "g4el58wc0zvf9na1";

    /** Fixed CBC IV, reset at the start of every enciphered stripe. */
    private static final byte[] IV = {0, 1, 2, 3, 4, 5, 6, 7};

    /** Stripe size; every 3rd stripe (0-based) is enciphered. */
    static final int STRIPE = 2048;

    private DeezerCrypto() {
    }

    /**
     * Derive the 16-byte Blowfish key from a track's {@code SNG_ID}:
     * {@code key[i] = md5hex[i] ^ md5hex[i+16] ^ SECRET[i]}, where {@code md5hex}
     * is the lowercase hex MD5 of the id (32 chars).
     */
    static byte[] trackKey(String sngId) {
        String md5 = md5Hex(sngId);
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) {
            key[i] = (byte) (md5.charAt(i) ^ md5.charAt(i + 16) ^ SECRET.charAt(i));
        }
        return key;
    }

    /** Blowfish/CBC/NoPadding cipher, re-initialised per stripe by the caller. */
    static Cipher newCipher() throws GeneralSecurityException {
        return Cipher.getInstance("Blowfish/CBC/NoPadding");
    }

    /**
     * Decrypt exactly one full {@link #STRIPE}-byte stripe in place. CBC state is
     * reset to the fixed {@link #IV} for each stripe (Deezer enciphers each stripe
     * independently), so the cipher is re-initialised on every call.
     *
     * @param cipher a {@link #newCipher()} instance (reused across stripes)
     * @param key    the {@link #trackKey(String)} for this track
     * @param buf    buffer holding the stripe at {@code [off, off + STRIPE)}
     */
    static void decryptStripe(Cipher cipher, byte[] key, byte[] buf, int off)
            throws GeneralSecurityException {
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "Blowfish"),
                new IvParameterSpec(IV));
        byte[] plain = cipher.doFinal(buf, off, STRIPE);
        System.arraycopy(plain, 0, buf, off, STRIPE);
    }

    /**
     * Per-stripe progress hook for {@link #decryptStream}. Called after each
     * stripe is written, with the running byte count; return {@code false} to
     * abort (the stream stops at once — nothing further is read or written).
     */
    interface StripeProgress {
        boolean onStripe(long bytesWritten);
    }

    /**
     * Stream Deezer ciphertext from {@code in} to {@code out}, decrypting every
     * third FULL stripe (index 0, 3, 6, …) and passing the other stripes — and
     * the trailing short stripe, which is never enciphered — through verbatim.
     *
     * <p>This is THE decrypt loop; {@link DeezerStrategy} only wraps it with HTTP
     * and progress reporting, so a JDK harness driving this method exercises the
     * real code path (see {@code scripts/deezer-harness}).
     *
     * <p>Reads EXACTLY {@link #STRIPE} bytes per stripe regardless of how the
     * underlying stream chunks its reads (a socket rarely delivers 2048 at once):
     * a stripe boundary that drifted by even one byte would misalign the cipher
     * for the rest of the file.
     *
     * @return total bytes written (equal to bytes read; the cipher is
     *         length-preserving), including on an early abort
     */
    static long decryptStream(InputStream in, OutputStream out, byte[] key, StripeProgress progress)
            throws IOException, GeneralSecurityException {
        Cipher cipher = newCipher();
        byte[] stripe = new byte[STRIPE];
        long written = 0;
        long index = 0;
        int filled = fill(in, stripe);
        while (filled > 0) {
            if (filled == STRIPE && (index % 3) == 0) {
                decryptStripe(cipher, key, stripe, 0);
            }
            out.write(stripe, 0, filled);
            written += filled;
            index++;
            if (progress != null && !progress.onStripe(written)) {
                return written;
            }
            filled = fill(in, stripe);
        }
        return written;
    }

    /** Read until {@code buf} is full or EOF; returns the count (0 at EOF). */
    private static int fill(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        return off;
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.US_ASCII));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}

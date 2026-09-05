package com.solarized.firedown.manager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * JDK-only harness for the REAL {@link DeezerCrypto} (copied from app/src at run
 * time by run.sh; this class shares its package to reach the package-private
 * API). Same package, no Android, no stubs — DeezerCrypto is deliberately
 * Android-free.
 *
 * <p>What it proves:
 * <ul>
 *   <li>the Blowfish key derivation matches known-answer vectors produced by an
 *       INDEPENDENT implementation (node's crypto module — see KATS); a bug that
 *       made DeezerCrypto agree with itself can't pass a vector from a different
 *       codebase;</li>
 *   <li>the stripe cipher round-trips: {@code decrypt(encrypt(x)) == x} for the
 *       enciphered stripes while leaving the plaintext stripes and the trailing
 *       short stripe verbatim;</li>
 *   <li>{@code decryptStream} is byte-exact regardless of how the input stream
 *       chunks its reads — a one-byte-at-a-time stream must yield the identical
 *       file as a whole-buffer one (the stripe-alignment invariant);</li>
 *   <li>the progress hook aborts the stream mid-file when it returns false.</li>
 * </ul>
 */
public final class DeezerHarness {

    // The Deezer stripe IV — the documented constant, hardcoded here so the
    // harness's ENCRYPT side is independent of DeezerCrypto's private copy.
    private static final byte[] IV = {0, 1, 2, 3, 4, 5, 6, 7};

    // Known-answer vectors: SNG_ID -> hex(trackKey), computed by node:
    //   md5=hex(md5(id)); key[i]=md5[i]^md5[i+16]^"g4el58wc0zvf9na1"[i]
    private static final String[][] KATS = {
        {"123456", "6060603b346b716d62712461346f3336"},
        {"3135556", "6c6c666b39662c37652575603c643439"},
        {"1", "3464656e343a7d3a672c236a33696061"},
    };

    private static int failures = 0;

    private static void check(String name, boolean cond, String extra) {
        if (cond) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + (extra == null ? "" : " " + extra));
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    // The harness's own encrypt of one full stripe (mirrors decryptStripe with
    // ENCRYPT_MODE) — the independent side of the round trip.
    private static void encryptStripe(byte[] key, byte[] buf, int off) throws Exception {
        Cipher c = Cipher.getInstance("Blowfish/CBC/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "Blowfish"), new IvParameterSpec(IV));
        byte[] ct = c.doFinal(buf, off, DeezerCrypto.STRIPE);
        System.arraycopy(ct, 0, buf, off, DeezerCrypto.STRIPE);
    }

    /** Build Deezer ciphertext: every 3rd full stripe enciphered, rest verbatim. */
    private static byte[] encodeDeezer(byte[] plain, byte[] key) throws Exception {
        byte[] ct = plain.clone();
        int stripe = DeezerCrypto.STRIPE;
        int index = 0;
        for (int off = 0; off + stripe <= ct.length; off += stripe) {
            if (index % 3 == 0) {
                encryptStripe(key, ct, off);
            }
            index++;
        }
        return ct;
    }

    // An InputStream that hands back at most one byte per read() — the pathology
    // decryptStream's fill() must absorb without drifting the stripe boundary.
    private static InputStream dribble(byte[] data) {
        return new InputStream() {
            int pos = 0;
            @Override public int read() {
                return pos < data.length ? (data[pos++] & 0xFF) : -1;
            }
            @Override public int read(byte[] b, int off, int len) {
                if (pos >= data.length) return -1;
                b[off] = data[pos++];
                return 1;   // never more than one byte, whatever was asked
            }
        };
    }

    public static void main(String[] args) throws Exception {
        // ---- 1. Key derivation vs. independent vectors ----------------------
        for (String[] kat : KATS) {
            String got = hex(DeezerCrypto.trackKey(kat[0]));
            check("keyDerivation SNG_ID=" + kat[0], got.equals(kat[1]), "got " + got + " want " + kat[1]);
        }

        byte[] key = DeezerCrypto.trackKey("123456");

        // ---- 2. Round trip over a multi-stripe body + short tail -----------
        // 7 full stripes (indices 0..6; 0,3,6 enciphered) + a 500-byte tail
        // (never enciphered). Deterministic pseudo-random plaintext.
        int stripe = DeezerCrypto.STRIPE;
        byte[] plain = new byte[stripe * 7 + 500];
        int seed = 12345;
        for (int i = 0; i < plain.length; i++) {
            seed = seed * 1103515245 + 12345;
            plain[i] = (byte) (seed >>> 16);
        }
        byte[] ct = encodeDeezer(plain, key);

        // The enciphered stripes must actually differ from the plaintext (proves
        // the test body isn't accidentally a no-op), while a non-enciphered
        // stripe (index 1) must be identical.
        boolean s0changed = !Arrays.equals(
            Arrays.copyOfRange(ct, 0, stripe), Arrays.copyOfRange(plain, 0, stripe));
        boolean s1same = Arrays.equals(
            Arrays.copyOfRange(ct, stripe, stripe * 2), Arrays.copyOfRange(plain, stripe, stripe * 2));
        check("ciphertext: stripe 0 enciphered (differs from plain)", s0changed, null);
        check("ciphertext: stripe 1 left verbatim (every-third rule)", s1same, null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long[] lastProgress = {0};
        long written = DeezerCrypto.decryptStream(
            new ByteArrayInputStream(ct), out, key, w -> { lastProgress[0] = w; return true; });
        check("decryptStream: round trip byte-exact", Arrays.equals(out.toByteArray(), plain),
            "len " + out.size() + " vs " + plain.length);
        check("decryptStream: returns full byte count", written == plain.length,
            "got " + written);
        check("decryptStream: progress reached total", lastProgress[0] == plain.length,
            "got " + lastProgress[0]);

        // ---- 3. Chunking invariance (one byte per read) --------------------
        ByteArrayOutputStream dribbled = new ByteArrayOutputStream();
        DeezerCrypto.decryptStream(dribble(ct), dribbled, key, w -> true);
        check("decryptStream: 1-byte-per-read yields identical file",
            Arrays.equals(dribbled.toByteArray(), plain), "len " + dribbled.size());

        // ---- 4. Abort mid-stream via the progress hook ---------------------
        ByteArrayOutputStream partial = new ByteArrayOutputStream();
        long stopAt = DeezerCrypto.decryptStream(
            new ByteArrayInputStream(ct), partial, key, w -> false);   // abort after stripe 0
        check("decryptStream: abort stops after first stripe", stopAt == stripe, "got " + stopAt);
        check("decryptStream: abort wrote only what it reported", partial.size() == stripe,
            "got " + partial.size());
        check("decryptStream: aborted stripe still decrypted correctly",
            Arrays.equals(partial.toByteArray(), Arrays.copyOfRange(plain, 0, stripe)), null);

        System.out.println(failures == 0
            ? "\ndeezer-harness: all checks passed"
            : "\ndeezer-harness: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}

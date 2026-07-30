package com.solarized.firedown.nwc;

import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * NIP-04 encryption — the payload layer NIP-47 (Nostr Wallet Connect) rides on.
 * A request/response body is AES-256-CBC under an ECDH secret shared between
 * our connection key and the wallet's, serialized as
 * {@code <base64(ciphertext)>?iv=<base64(iv)>}.
 *
 * <p><b>The shared secret is the RAW x coordinate — not a hash of it.</b> This
 * is the one place NIP-04 departs from what a crypto library will hand you:
 * most ECDH helpers (including BouncyCastle's agreement classes and Go's
 * {@code GenerateSharedSecret}) hash the shared point, and using one here
 * produces a key the wallet can't derive. Every byte is right and nothing
 * decrypts. So the multiply is done by hand and the affine X is used verbatim,
 * exactly as the mint's Go client does (firedown-api {@code internal/mint/
 * payment/nwc/nip04.go}) — the two are deliberately mirror images.
 *
 * <p>NIP-04 is deprecated in favour of NIP-44 for general Nostr messaging, but
 * NIP-47 still specifies it and Alby Hub speaks it, so it is what an
 * interoperable client implements. Its known weakness (no authentication of the
 * ciphertext) is bounded here by the event signature: {@link NostrEvent#verify}
 * rejects anything not signed by the wallet's key before we ever decrypt.
 *
 * <p>Pure Java (no Android imports) so the host crypto harness can exercise it.
 */
public final class Nip04 {

    private Nip04() {
    }

    /**
     * The NIP-04 conversation key for (our secret, their x-only pubkey): the
     * 32-byte affine X of the shared point, used directly as the AES-256 key.
     */
    static byte[] sharedSecret(byte[] ourSecretKey, byte[] theirXOnlyPub) {
        if (ourSecretKey == null || ourSecretKey.length != 32) {
            throw new IllegalArgumentException("nip04: secret key must be 32 bytes");
        }
        if (theirXOnlyPub == null || theirXOnlyPub.length != 32) {
            throw new IllegalArgumentException("nip04: peer pubkey must be 32 bytes");
        }
        ECPoint theirPoint = Bip340.liftX(new BigInteger(1, theirXOnlyPub));
        if (theirPoint == null) {
            throw new IllegalArgumentException("nip04: peer pubkey is not on the curve");
        }
        BigInteger d = new BigInteger(1, ourSecretKey);
        BigInteger n = CustomNamedCurves.getByName("secp256k1").getN();
        if (d.signum() == 0 || d.compareTo(n) >= 0) {
            throw new IllegalArgumentException("nip04: secret key out of range");
        }
        ECPoint shared = theirPoint.multiply(d).normalize();
        if (shared.isInfinity()) {
            throw new IllegalArgumentException("nip04: degenerate shared point");
        }
        return Bip340.be32(shared.getAffineXCoord().toBigInteger());
    }

    /** Encrypts UTF-8 {@code plaintext} into the NIP-04 wire form. */
    public static String encrypt(byte[] ourSecretKey, byte[] theirXOnlyPub, String plaintext) {
        try {
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(sharedSecret(ourSecretKey, theirXOnlyPub), "AES"),
                    new IvParameterSpec(iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder b64 = Base64.getEncoder();
            return b64.encodeToString(ct) + "?iv=" + b64.encodeToString(iv);
        } catch (GeneralSecurityExceptionWrapper e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityExceptionWrapper("nip04: encrypt failed", e);
        }
    }

    /**
     * Decrypts the NIP-04 wire form. Throws rather than returning null on any
     * malformation, so a caller can't accidentally treat garbage as a reply.
     */
    public static String decrypt(byte[] ourSecretKey, byte[] theirXOnlyPub, String payload) {
        if (payload == null) {
            throw new GeneralSecurityExceptionWrapper("nip04: empty payload", null);
        }
        int marker = payload.indexOf("?iv=");
        if (marker < 0) {
            throw new GeneralSecurityExceptionWrapper("nip04: missing ?iv=", null);
        }
        try {
            Base64.Decoder b64 = Base64.getDecoder();
            byte[] ct = b64.decode(payload.substring(0, marker));
            byte[] iv = b64.decode(payload.substring(marker + 4));
            if (iv.length != 16 || ct.length == 0 || ct.length % 16 != 0) {
                throw new GeneralSecurityExceptionWrapper("nip04: bad ciphertext/iv length", null);
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(sharedSecret(ourSecretKey, theirXOnlyPub), "AES"),
                    new IvParameterSpec(iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityExceptionWrapper e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityExceptionWrapper("nip04: decrypt failed", e);
        }
    }

    /**
     * An unchecked wrapper so the NWC call path can report crypto failures the
     * same way it reports protocol failures, without every frame in between
     * declaring a checked exception it can do nothing about.
     */
    public static final class GeneralSecurityExceptionWrapper extends RuntimeException {
        public GeneralSecurityExceptionWrapper(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package com.solarized.firedown.nwc;

import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * BIP-340 Schnorr signatures over secp256k1 — the signature scheme Nostr
 * (NIP-01) uses for event ids, and therefore what NIP-47 / Nostr Wallet Connect
 * needs before it can say anything to a wallet.
 *
 * <p><b>Why hand-rolled.</b> BouncyCastle ships secp256k1 curve arithmetic but
 * no BIP-340 primitive (its {@code rfc8032} package covers Ed25519/Ed448 only),
 * and the alternatives are a full Nostr library or a native secp256k1 build —
 * both far more surface than one signature scheme. This is ~120 lines of
 * spec-literal code over BC's field/point math, deliberately NOT a general
 * crypto utility: it signs and verifies 32-byte messages and computes the
 * x-only public key, and nothing else.
 *
 * <p><b>It is verified against the BIP-340 reference vectors</b>
 * ({@code scripts/nwc-crypto-check.java} runs the published csv on the host).
 * If you change anything here, re-run it — a silently wrong signature does not
 * fail loudly, it just makes every wallet reject every request with no useful
 * diagnostic, which is the same debugging trap the sync canonical has.
 *
 * <p>Everything here is pure Java (no Android imports) so it can run in that
 * host harness.
 */
public final class Bip340 {

    private static final ECDomainParameters CURVE;
    /** Field prime p of secp256k1. */
    private static final BigInteger P;
    /** Group order n. */
    private static final BigInteger N;

    static {
        var params = CustomNamedCurves.getByName("secp256k1");
        CURVE = new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
        P = params.getCurve().getField().getCharacteristic();
        N = params.getN();
    }

    private Bip340() {
    }

    /**
     * The BIP-340 x-only public key for a 32-byte secret key — the "pubkey" a
     * Nostr event carries. Only the x coordinate travels; the y parity is
     * defined to be even, which is what {@link #liftX} reconstructs.
     */
    public static byte[] xOnlyPubKey(byte[] secretKey) {
        BigInteger d = seckeyScalar(secretKey);
        ECPoint point = CURVE.getG().multiply(d).normalize();
        return be32(point.getAffineXCoord().toBigInteger());
    }

    /**
     * Signs a 32-byte message (for Nostr, the event id) with fresh auxiliary
     * randomness, per BIP-340. Returns the 64-byte signature {@code r || s}.
     */
    public static byte[] sign(byte[] secretKey, byte[] message) {
        byte[] aux = new byte[32];
        new SecureRandom().nextBytes(aux);
        return sign(secretKey, message, aux);
    }

    /**
     * Signs with caller-supplied auxiliary randomness. Public only so the host
     * harness can replay the BIP-340 vectors, which pin {@code aux}; production
     * callers want {@link #sign(byte[], byte[])}.
     */
    public static byte[] sign(byte[] secretKey, byte[] message, byte[] aux) {
        if (message.length != 32) {
            throw new IllegalArgumentException("bip340: message must be 32 bytes");
        }
        BigInteger dPrime = seckeyScalar(secretKey);
        ECPoint pointP = CURVE.getG().multiply(dPrime).normalize();
        // The secret is negated when P has odd y, so the effective key always
        // pairs with the even-y point the x-only pubkey denotes.
        BigInteger d = hasEvenY(pointP) ? dPrime : N.subtract(dPrime);
        byte[] pubBytes = be32(pointP.getAffineXCoord().toBigInteger());

        byte[] t = xor(be32(d), taggedHash("BIP0340/aux", aux));
        BigInteger kPrime = new BigInteger(1,
                taggedHash("BIP0340/nonce", concat(t, pubBytes, message))).mod(N);
        if (kPrime.signum() == 0) {
            throw new IllegalStateException("bip340: zero nonce");
        }
        ECPoint pointR = CURVE.getG().multiply(kPrime).normalize();
        BigInteger k = hasEvenY(pointR) ? kPrime : N.subtract(kPrime);
        byte[] rBytes = be32(pointR.getAffineXCoord().toBigInteger());

        BigInteger e = challenge(rBytes, pubBytes, message);
        byte[] sBytes = be32(k.add(e.multiply(d)).mod(N));
        byte[] sig = concat(rBytes, sBytes);
        // Self-check: a signature this code cannot verify must never leave the
        // process. Cheap next to the point multiplies above, and it converts a
        // silent protocol failure into an immediate, local one.
        if (!verify(pubBytes, message, sig)) {
            throw new IllegalStateException("bip340: produced an invalid signature");
        }
        return sig;
    }

    /**
     * Verifies a 64-byte signature against a 32-byte x-only public key. Returns
     * false — never throws — for every malformed input, so a hostile relay
     * response can't do anything but fail the check.
     */
    public static boolean verify(byte[] xOnlyPub, byte[] message, byte[] sig) {
        if (xOnlyPub == null || xOnlyPub.length != 32
                || message == null || message.length != 32
                || sig == null || sig.length != 64) {
            return false;
        }
        try {
            ECPoint pointP = liftX(new BigInteger(1, xOnlyPub));
            if (pointP == null) {
                return false;
            }
            BigInteger r = new BigInteger(1, Arrays.copyOfRange(sig, 0, 32));
            BigInteger s = new BigInteger(1, Arrays.copyOfRange(sig, 32, 64));
            if (r.compareTo(P) >= 0 || s.compareTo(N) >= 0) {
                return false;
            }
            BigInteger e = challenge(Arrays.copyOfRange(sig, 0, 32), xOnlyPub, message);
            // R = s·G - e·P
            ECPoint pointR = CURVE.getG().multiply(s).subtract(pointP.multiply(e)).normalize();
            if (pointR.isInfinity() || !hasEvenY(pointR)) {
                return false;
            }
            return pointR.getAffineXCoord().toBigInteger().equals(r);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Reconstructs the even-y curve point for an x coordinate, or null when x
     * is not on the curve. Package-visible because NIP-04's ECDH needs the same
     * reconstruction to turn a Nostr pubkey back into a point.
     */
    static ECPoint liftX(BigInteger x) {
        if (x.signum() < 0 || x.compareTo(P) >= 0) {
            return null;
        }
        BigInteger c = x.modPow(BigInteger.valueOf(3), P).add(BigInteger.valueOf(7)).mod(P);
        // p ≡ 3 (mod 4), so the square root is c^((p+1)/4).
        BigInteger y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P);
        if (!y.modPow(BigInteger.TWO, P).equals(c)) {
            return null; // x is not on the curve
        }
        if (y.testBit(0)) {
            y = P.subtract(y);
        }
        return CURVE.getCurve().createPoint(x, y).normalize();
    }

    /** The secret scalar, rejecting the out-of-range values BIP-340 forbids. */
    private static BigInteger seckeyScalar(byte[] secretKey) {
        if (secretKey == null || secretKey.length != 32) {
            throw new IllegalArgumentException("bip340: secret key must be 32 bytes");
        }
        BigInteger d = new BigInteger(1, secretKey);
        if (d.signum() == 0 || d.compareTo(N) >= 0) {
            throw new IllegalArgumentException("bip340: secret key out of range");
        }
        return d;
    }

    private static BigInteger challenge(byte[] rBytes, byte[] pubBytes, byte[] message) {
        return new BigInteger(1,
                taggedHash("BIP0340/challenge", concat(rBytes, pubBytes, message))).mod(N);
    }

    private static boolean hasEvenY(ECPoint point) {
        return !point.getAffineYCoord().toBigInteger().testBit(0);
    }

    /** BIP-340 tagged hash: {@code sha256(sha256(tag) || sha256(tag) || msg)}. */
    static byte[] taggedHash(String tag, byte[] message) {
        MessageDigest sha = sha256();
        byte[] tagHash = sha.digest(tag.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        sha.reset();
        sha.update(tagHash);
        sha.update(tagHash);
        sha.update(message);
        return sha.digest();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * A big-endian 32-byte encoding — LEFT-PADDED, and that padding is the
     * point. {@code BigInteger.toByteArray()} emits the minimal form (and a
     * leading sign byte when the top bit is set), so a scalar that happens to
     * be small would otherwise hash as a SHORTER string and produce a valid-
     * looking signature no verifier accepts.
     */
    static byte[] be32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }

    static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    /** Lowercase hex, the encoding every Nostr field uses. */
    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /** Parses lowercase/uppercase hex, or null when the input isn't valid hex. */
    public static byte[] unhex(String s) {
        if (s == null || s.length() % 2 != 0) {
            return null;
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}

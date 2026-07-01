package com.solarized.firedown.sync.crypto;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * RSA full-domain-hash (Chaum) blind signatures — the client half of the mint's
 * credit issuance. This mirrors the Go {@code internal/shared/blind} package
 * byte-for-byte, pinned by the shared interop vectors ({@code blind.json}): a
 * one-byte divergence would make every purchased credit fail to redeem with no
 * useful diagnostic, so keep this in lockstep with the Go side.
 *
 * <p>Pure JDK ({@link BigInteger} + SHA-256) — no Android or BouncyCastle APIs,
 * so it runs in a plain JUnit test.
 *
 * <p>Flow for one credit (the mint only ever sees the BLINDED value, so it can
 * never link the credit it signs to the payment that bought it):
 * <pre>
 *   mint:    publishes a keyset = RSA public key (n, e) per denomination.
 *   client:  picks a random secret x; m = FDH(x); blinds m' = m·r^e mod n
 *            with a random r; sends m'.
 *   mint:    blind-signs s' = m'^d mod n.
 *   client:  unblinds s = s'·r^-1 mod n. The credit is (keysetId, x, s).
 *   storage: verifies s^e == FDH(x) (mod n) with only (n, e).
 * </pre>
 */
public final class BlindSignature {

    /** Fixed public exponent for every keyset (standard F4). */
    public static final int PUBLIC_E = 65537;

    private final BigInteger n;
    private final int e;
    private final BigInteger eBig;

    public BlindSignature(BigInteger modulus, int exponent) {
        this.n = modulus;
        this.e = exponent;
        this.eBig = BigInteger.valueOf(exponent);
    }

    /** Build a keyset from the mint's {@code /v1/mint/keys} fields (n hex, e int). */
    public static BlindSignature fromHex(String modulusHex, int exponent) {
        return new BlindSignature(new BigInteger(modulusHex, 16), exponent);
    }

    public BigInteger modulus() {
        return n;
    }

    public int exponent() {
        return e;
    }

    /**
     * keysetId = the first 8 bytes of SHA-256(big-endian modulus). Matches the
     * mint's KeysetID so the redeemed credit selects the right verification key.
     */
    public byte[] keysetId() {
        byte[] h = sha256(unsignedBytes(n));
        byte[] out = new byte[8];
        System.arraycopy(h, 0, out, 0, 8);
        return out;
    }

    /**
     * The full-domain hash: MGF1-SHA256 expanded one block past the modulus length
     * and reduced mod n (a zero result maps to 1, as on the Go side).
     */
    public BigInteger fdh(byte[] secret) {
        int k = (n.bitLength() + 7) / 8;
        byte[] buf = mgf1(secret, k + 8);
        BigInteger m = new BigInteger(1, buf).mod(n);
        if (m.signum() == 0) {
            return BigInteger.ONE;
        }
        return m;
    }

    /** A blinded message paired with the blinding factor needed to unblind it. */
    public static final class Blinded {
        public final BigInteger blinded;
        public final BigInteger r;

        Blinded(BigInteger blinded, BigInteger r) {
            this.blinded = blinded;
            this.r = r;
        }
    }

    /** Blind the secret with a fresh random blinding factor (the production path). */
    public Blinded blind(byte[] secret, SecureRandom rng) {
        return blindWith(secret, randCoprime(rng));
    }

    /**
     * Blind with a caller-supplied r — deterministic, so the interop test can
     * reproduce the vectors. Package-private on purpose.
     */
    Blinded blindWith(byte[] secret, BigInteger r) {
        BigInteger m = fdh(secret);
        BigInteger re = r.modPow(eBig, n);
        BigInteger blinded = m.multiply(re).mod(n);
        return new Blinded(blinded, r);
    }

    /** Unblind the mint's blind signature: s = blindSig · r^-1 mod n. */
    public BigInteger unblind(BigInteger blindSig, BigInteger r) {
        return blindSig.multiply(r.modInverse(n)).mod(n);
    }

    /** Verify a credit with only the public key: s^e == FDH(secret) (mod n). */
    public boolean verify(byte[] secret, BigInteger sig) {
        if (sig == null || sig.signum() < 0 || sig.compareTo(n) >= 0) {
            return false;
        }
        return sig.modPow(eBig, n).equals(fdh(secret));
    }

    // randCoprime returns a uniform r in [2, n) with gcd(r, n) = 1. For an RSA
    // modulus a random r is coprime with overwhelming probability; the loop is a
    // formality (mirrors the Go side's 64-try bound).
    private BigInteger randCoprime(SecureRandom rng) {
        for (int i = 0; i < 64; i++) {
            BigInteger r = new BigInteger(n.bitLength(), rng);
            if (r.compareTo(BigInteger.TWO) < 0 || r.compareTo(n) >= 0) {
                continue;
            }
            if (r.gcd(n).equals(BigInteger.ONE)) {
                return r;
            }
        }
        throw new IllegalStateException("blind: could not find a coprime blinding factor");
    }

    // mgf1 is the MGF1 mask-generation function over SHA-256 (RFC 8017): the
    // concatenation of SHA-256(seed || counter_be32) for counter = 0,1,2,…,
    // truncated to length. Matches the Go FDH expander exactly.
    private static byte[] mgf1(byte[] seed, int length) {
        byte[] out = new byte[length];
        int filled = 0;
        int counter = 0;
        MessageDigest md = sha256Digest();
        while (filled < length) {
            md.reset();
            md.update(seed);
            md.update(be32(counter));
            byte[] block = md.digest();
            int take = Math.min(block.length, length - filled);
            System.arraycopy(block, 0, out, filled, take);
            filled += take;
            counter++;
        }
        return out;
    }

    private static byte[] be32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    /**
     * The big-endian magnitude WITHOUT {@link BigInteger#toByteArray()}'s sign
     * byte — so it matches Go's {@code n.Bytes()} for the keyset id hash. A
     * 2048-bit modulus has its top bit set, so toByteArray() prepends a 0x00 that
     * must be stripped.
     */
    static byte[] unsignedBytes(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] trimmed = new byte[b.length - 1];
            System.arraycopy(b, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return b;
    }

    private static byte[] sha256(byte[] data) {
        return sha256Digest().digest(data);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}

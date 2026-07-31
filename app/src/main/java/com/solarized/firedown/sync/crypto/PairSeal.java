package com.solarized.firedown.sync.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.util.Base64;

import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.math.ec.ECPoint;

/**
 * The phone half of QR browser pairing: sealing this account's storage keys to
 * an ephemeral public key the browser generated, so the web Cloud Backup client
 * can be unlocked by a scan instead of by typing a 52-character recovery code.
 *
 * <h3>What is sent, and what deliberately is not</h3>
 * Exactly three values — {@code account_id}, {@code auth_seed} and
 * {@code storage_key} — and nothing else. The RECOVERY CODE ITSELF NEVER
 * TRAVELS. That is the point rather than an implementation detail: a paired
 * browser gets the storage account and only that. It cannot derive the bookmark
 * key, and it cannot reconstruct the code, so a compromised browser session
 * cannot be turned into a permanent one. Typing the code into a web page, by
 * contrast, hands over everything derivable from it forever. Pairing is the
 * SAFER path, not merely the more convenient one.
 *
 * <h3>Wire format</h3>
 * <pre>
 *   'FDPR1' | version(1) | phoneEphPub(65, uncompressed) | iv(12) | AES-256-GCM(ct || tag)
 *   plaintext = account_id(16) || auth_seed(32) || storage_key(32)
 *   key       = HKDF-SHA256(ikm = ECDH(phoneEph, browserPub),
 *                           salt = empty,
 *                           info = "firedown/pair/v1" || pairId || browserPub || phonePub)
 * </pre>
 * The info binds the key to THIS pairing and to BOTH public keys, so a blob
 * captured from a real pairing decrypts in no other — it cannot be replayed
 * into a session an attacker started.
 *
 * <p>This is a THIRD implementation of a shared format ({@code backup/fd-pair.js}
 * is the other end). It is pinned by {@code tools/pair-seal.json}, a fixture the
 * web test generates and {@code PairSealTest} reads, so the two clients are
 * checked against the same bytes rather than against each other's prose. A
 * one-byte divergence here fails every pairing with no useful diagnostic.
 *
 * <h3>What makes a pairing safe to approve</h3>
 * Nothing in this class — the security is in the flow that calls it:
 * <ol>
 *   <li>The pairing is resolved ONLY against the app's own hardcoded API base
 *       URL. The scanned QR carries an id and a key, never an endpoint, so a
 *       hostile QR cannot point the phone at a mailbox of its own.</li>
 *   <li>The server-recorded public key is compared against the SCANNED one
 *       before anything is sealed, so a server that swapped the key to read the
 *       blob is caught by the comparison rather than trusted.</li>
 *   <li>The {@link #verificationCode} is compared on both screens. It is a
 *       short authenticated string over the COMPLETED handshake, and it closes
 *       what the two checks above cannot, in BOTH directions.
 *       <p>Phone side: the server cannot tell a real browser from a program
 *       claiming to be one, so an attacker CAN mint a session that truthfully
 *       reports firedown.app and send its QR to a victim. The victim has no
 *       screen showing that session's code, so the comparison cannot succeed.
 *       <p>Browser side: delivery to the mailbox is authenticated by nothing
 *       but knowledge of the pairing id, and the blob is sealed to a key the QR
 *       prints — so the server, or anyone who saw the QR, can seal THEIR
 *       account's keys to the browser and win the race, silently signing the
 *       victim in to the attacker's account. Covering this device's ephemeral
 *       key is what makes those digits differ.</li>
 * </ol>
 */
public final class PairSeal {

    /** Scheme URL the browser encodes, so any scanner offers to open Firedown. */
    public static final String DEEP_LINK = "firedown://pair/";
    /**
     * Payload prefix, versioned so a future format is distinguishable.
     *
     * <p>v3 is the ANNOUNCE handshake: this device publishes its ephemeral
     * public key BEFORE the user approves, so the browser can display the
     * verification code while the approval sheet is still up. A v2 app never
     * announces, so a v3 page paired with one waits for a peer that never
     * arrives — a HANG. The bump makes such an app refuse to parse instead,
     * which is a clean failure. Ship order: API, then this APK, then the page.
     */
    public static final String PREFIX = "FDP3.";
    /**
     * Digits {@link #verificationCode} emits. Exported because the approval
     * sheet groups them for a quicker compare, and that grouping must not be
     * able to drift from the formatter here.
     */
    public static final int CODE_DIGITS = 6;

    private static final byte[] MAGIC = "FDPR1".getBytes(StandardCharsets.US_ASCII);
    private static final byte VERSION = 1;
    private static final int PUB_LEN = 65;   // uncompressed P-256 point
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private static final byte[] INFO = "firedown/pair/v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] VERIFY_INFO =
            "firedown/pair/verify/v2".getBytes(StandardCharsets.US_ASCII);

    private static final ECDomainParameters CURVE;

    static {
        var params = CustomNamedCurves.getByName("secp256r1"); // P-256
        CURVE = new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
    }

    private PairSeal() {
    }

    /** A pairing as read off a QR: the mailbox id and the browser's public key. */
    public static final class Ref {
        public final String pairId;
        /** base64url, exactly as the server recorded it — compared as a string. */
        public final String pubkeyB64;
        public final byte[] pubkey;

        Ref(String pairId, String pubkeyB64, byte[] pubkey) {
            this.pairId = pairId;
            this.pubkeyB64 = pubkeyB64;
            this.pubkey = pubkey;
        }
    }

    /**
     * Parses a scanned payload, in either the deep-link or bare form.
     *
     * <p>Returns null for anything that is not a well-formed pairing payload —
     * including a public key that is not a valid point on P-256. Validating the
     * point here rather than at use is deliberate: an invalid-curve point is a
     * classic way to attack ECDH, and {@code decodePoint} plus the explicit
     * {@code isValid} check is what refuses it.
     */
    public static Ref parse(String scanned) {
        if (scanned == null) {
            return null;
        }
        String s = scanned.trim();
        if (s.startsWith(DEEP_LINK)) {
            s = s.substring(DEEP_LINK.length());
        }
        if (!s.startsWith(PREFIX)) {
            return null;
        }
        s = s.substring(PREFIX.length());

        int dot = s.indexOf('.');
        if (dot <= 0 || dot == s.length() - 1) {
            return null;
        }
        String id = s.substring(0, dot);
        String pubB64 = s.substring(dot + 1);
        // The id is echoed into the HKDF info and put in a URL path, so keep it
        // to the base64url alphabet the server actually mints.
        if (!id.matches("[A-Za-z0-9_-]{16,64}") || !pubB64.matches("[A-Za-z0-9_-]{80,128}")) {
            return null;
        }

        // java.util.Base64 rather than android.util.Base64 so this whole class
        // is Android-free and therefore exercisable by a plain JVM unit test —
        // which matters because `unitTests.returnDefaultValues` would make the
        // Android one return NULL here instead of failing loudly.
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(pubB64);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (raw.length != PUB_LEN || raw[0] != 0x04) {
            return null;
        }
        try {
            ECPoint p = CURVE.getCurve().decodePoint(raw);
            if (!p.isValid()) {
                return null;
            }
        } catch (RuntimeException e) {
            return null; // not a point on the curve
        }
        return new Ref(id, pubB64, raw);
    }

    /**
     * Encodes an ephemeral public key the way the wire carries it: base64url,
     * UNPADDED — the same shape the QR uses for the browser's key and the
     * server's {@code validPairPubkey} accepts.
     */
    public static String encodePublicKey(byte[] pub) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(pub);
    }

    /**
     * The six-digit SAS over a COMPLETED handshake: the pairing id, the
     * browser's public key, and {@code phonePub} — the ephemeral key this
     * device will actually seal with.
     *
     * <p><b>All three inputs are required.</b> Dropping {@code phonePub} makes
     * this a function of the QR alone, which anyone who photographed the QR (or
     * the server, which holds both values) can reproduce — so the digits would
     * confirm only "this is the session I started" and never "these keys came
     * from MY phone". That gap is real in the other direction: delivery to the
     * mailbox is authenticated by nothing but knowledge of the pairing id, and
     * the blob is sealed to a public key the QR prints, so a server or a QR
     * observer can seal THEIR account's keys to the browser and win the race.
     * The browser decrypts them happily. Covering the ephemeral key is what
     * makes a substituted blob produce different digits from the ones this
     * screen is showing.
     *
     * <p>Truncated from SHA-256, so aiming a handshake at a chosen code means
     * grinding ~10^6 of them past a rate limiter for one guess — and the target
     * only ever exists on the other party's own screen.
     */
    public static String verificationCode(String pairId, byte[] browserPub, byte[] phonePub) {
        byte[] buf = concat(VERIFY_INFO, pairId.getBytes(StandardCharsets.UTF_8),
                browserPub, phonePub);
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(buf);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        int n = ((digest[0] & 0xff) << 16 | (digest[1] & 0xff) << 8 | (digest[2] & 0xff))
                % (int) Math.pow(10, CODE_DIGITS);
        return String.format(Locale.US, "%0" + CODE_DIGITS + "d", n);
    }

    /**
     * This device's ephemeral key for one pairing.
     *
     * <p>It exists as its own object because the verification code covers the
     * public half, and the code has to be on screen BEFORE the user approves —
     * so the key must be minted before the seal, and the SAME key must then be
     * used to seal. Generating one inside {@link #seal} would show the user
     * digits for a handshake that never happened.
     */
    public static final class Ephemeral {
        /** Uncompressed P-256 point, 65 bytes. Safe to show and to hash. */
        public final byte[] publicKey;
        private final BigInteger scalar;

        private Ephemeral(BigInteger scalar, byte[] publicKey) {
            this.scalar = scalar;
            this.publicKey = publicKey;
        }
    }

    /**
     * Mints an ephemeral key for one pairing.
     *
     * <p>The scalar is rejection-sampled in [1, n-1] rather than reduced:
     * reducing a random 256-bit value mod n biases the low keys, and while the
     * bias is tiny it costs nothing to avoid. It is held as a {@link BigInteger}
     * and so cannot be wiped — Java's is immutable and copies freely — but it is
     * single-use and dies with the pairing, and the shared secret and derived
     * key, which are the values worth protecting, are wiped in {@link #seal}.
     */
    public static Ephemeral newEphemeral() {
        SecureRandom rnd = new SecureRandom();
        BigInteger n = CURVE.getN();
        BigInteger d;
        do {
            d = new BigInteger(n.bitLength(), rnd);
        } while (d.signum() == 0 || d.compareTo(n) >= 0);
        return new Ephemeral(d, CURVE.getG().multiply(d).normalize().getEncoded(false));
    }

    /**
     * Seals the account's keys for the browser that started this pairing, using
     * the ephemeral key whose verification code the user just confirmed.
     *
     * <p>The caller owns the input arrays and should wipe them; the shared
     * secret and the derived key are wiped here.
     */
    public static byte[] seal(String pairId, byte[] browserPub, Ephemeral eph,
                              byte[] accountId, byte[] authSeed, byte[] storageKey) {
        if (accountId.length != 16 || authSeed.length != 32 || storageKey.length != 32) {
            throw new IllegalArgumentException("unexpected key lengths");
        }

        SecureRandom rnd = new SecureRandom();
        BigInteger d = eph.scalar;
        byte[] phonePub = eph.publicKey;
        ECPoint peer = CURVE.getCurve().decodePoint(browserPub);
        // Multiply by the cofactor-adjusted scalar is unnecessary on P-256
        // (cofactor 1); the point was validated in parse().
        ECPoint shared = peer.multiply(d).normalize();
        byte[] z = be32(shared.getAffineXCoord().toBigInteger());

        byte[] info = concat(INFO, pairId.getBytes(StandardCharsets.UTF_8), browserPub, phonePub);
        byte[] key = Hkdf.expand(Hkdf.extract(new byte[0], z), info, 32);
        Arrays.fill(z, (byte) 0);

        byte[] iv = new byte[IV_LEN];
        rnd.nextBytes(iv);
        byte[] plaintext = concat(accountId, authSeed, storageKey);

        byte[] body;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            body = c.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }

        byte[] out = new byte[MAGIC.length + 1 + PUB_LEN + IV_LEN + body.length];
        int off = 0;
        System.arraycopy(MAGIC, 0, out, off, MAGIC.length);
        off += MAGIC.length;
        out[off++] = VERSION;
        System.arraycopy(phonePub, 0, out, off, PUB_LEN);
        off += PUB_LEN;
        System.arraycopy(iv, 0, out, off, IV_LEN);
        off += IV_LEN;
        System.arraycopy(body, 0, out, off, body.length);
        return out;
    }

    /** Left-pads a coordinate to the curve's 32-byte field width. */
    private static byte[] be32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32); // strip a sign byte
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}

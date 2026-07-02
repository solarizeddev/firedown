package com.solarized.firedown.sync.crypto;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

/**
 * Adversarial half of the blind-signature suite: everything a MALICIOUS/modified
 * client could hand the storage server as a "credit" that was never blind-signed
 * by the mint must fail {@code verify} — the same {@code s^e == FDH(secret)}
 * check the Go {@code blind.Verify} runs on redeem, so a forgery that passes here
 * would be free money server-side. (The server ALSO burns the secret atomically —
 * the double-spend half — covered by the live redeem tests and the Go race tests;
 * this file is the pure verification math.)
 *
 * <p>Uses the same throwaway 2048-bit keyset as {@link BlindSignatureTest} (its
 * private exponent D is embedded ONLY so the test can play the mint).
 */
public class BlindSignatureForgeryTest {

    private static final String N_HEX = "dfc01daf62ec494b89bb400e64012e4df0b52d3a60fa864ee23a82966afd24ab48616835e08ffc0432760c5221587785bb971454c5e6d573367a4c4be9ea5fc6a299ae76e62b112ff87dfc26c86226647b957fafd330ba2b08e025e4649fe7fb45d01841a50130782f708fe97fad8681360fb205478e8480695d8e684ff6294121309dfaecf03f6f46c0b8a06d0309e18044842ab38486c2963bf5df6d19a33c2e0434ffe27aa877447e4a29995de6b3045c50da7aef6ca93722528ab91847954a9fda8842af6f6a3220849c1e04d8501b38f0665097b89c4e417f22fdb59a9caacf3a4d38015bb9edbad7ea54d5f5c1c728a68a65cf15afb3e253c8b1e2c019";
    private static final String D_HEX = "3c80cd262368446b8e2b59a76a885d368b2bdab68a09c46ea942ec13f38b4f3297c86b2f0271bcd27fb8a71d40521543ced58c145e4d4c93b27c008c988c9d686f88820239bc149235ae0f948723ef40c5a047de4a0bc793a27b4613cbd7e7996d27d79f4c98953c328bcc0676557c650d32d24f1629e60f792e68b731441da432fc55b397ce063c07ff6354673fd29a03f0d1f8483a23835bba0ecf043a5471ef16dc80d906460152f6dec1811c252577c3c709be5d0cb20c204659a59918f15b3e1b6654e6a703c35f12a7d1ce68c8aff2353348c5d1850a35c9f7349d7de0796da07fc3436ad12578657caf07032e2837932ada3bd41322e6f1e2988d70af";

    private final BigInteger n = new BigInteger(N_HEX, 16);
    private final BigInteger d = new BigInteger(D_HEX, 16);
    private final BlindSignature bs = new BlindSignature(n, BlindSignature.PUBLIC_E);
    private final SecureRandom rng = new SecureRandom();

    /** Plays the mint: a REAL credit (secret, sig) for this keyset. */
    private BigInteger mintCredit(byte[] secret) {
        BlindSignature.Blinded bl = bs.blind(secret, rng);
        BigInteger blindSig = bl.blinded.modPow(d, n);
        BigInteger sig = bs.unblind(blindSig, bl.r);
        assertTrue("sanity: the honest credit verifies", bs.verify(secret, sig));
        return sig;
    }

    private byte[] randomSecret() {
        byte[] s = new byte[32];
        rng.nextBytes(s);
        return s;
    }

    /** A client that never paid can't fabricate a signature: random values —
     *  forging without the mint's D is the RSA problem itself. */
    @Test
    public void randomSignatureNeverVerifies() {
        byte[] secret = randomSecret();
        for (int i = 0; i < 16; i++) {
            BigInteger fake = new BigInteger(n.bitLength() - 1, rng);
            assertFalse("random sig accepted as a credit", bs.verify(secret, fake));
        }
    }

    /** Secret-swap: a REAL signature (one paid credit) presented with a DIFFERENT
     *  secret — the "redeem one purchase twice under two secrets" attempt. */
    @Test
    public void validSignatureFailsForAnotherSecret() {
        byte[] paid = randomSecret();
        BigInteger sig = mintCredit(paid);
        assertFalse("one paid sig must not cover a second secret",
                bs.verify(randomSecret(), sig));
    }

    /** Tampering: any bit flip in a valid signature must kill it (no malleable
     *  region — FDH covers the whole domain). */
    @Test
    public void bitFlippedSignatureFails() {
        byte[] secret = randomSecret();
        BigInteger sig = mintCredit(secret);
        for (int bit : new int[]{0, 7, 512, n.bitLength() - 2}) {
            assertFalse("bit-flipped sig verified (bit " + bit + ")",
                    bs.verify(secret, sig.flipBit(bit)));
        }
    }

    /** Range games: sig ≥ n, negative, null — a modified client feeding raw
     *  out-of-domain values must be rejected up front, not modPow'd. */
    @Test
    public void outOfRangeSignaturesRejected() {
        byte[] secret = randomSecret();
        BigInteger sig = mintCredit(secret);
        assertFalse("sig + n (same residue!) must be rejected by the range check",
                bs.verify(secret, sig.add(n)));
        assertFalse(bs.verify(secret, sig.negate()));
        assertFalse(bs.verify(secret, n));
        assertFalse(bs.verify(secret, null));
    }

    /** Keyset confusion: a credit minted under keyset A presented against keyset
     *  B (a different denomination's key) — buying the cheap denomination and
     *  redeeming it as the expensive one must fail, and the two keyset ids differ
     *  so the server looks up the right key in the first place. */
    @Test
    public void creditFromOneKeysetFailsUnderAnother() throws Exception {
        byte[] secret = randomSecret();
        BigInteger sig = mintCredit(secret);

        // A second (test-only) keyset standing in for another denomination.
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        RSAPublicKey pub = (RSAPublicKey) kpg.generateKeyPair().getPublic();
        BlindSignature other = new BlindSignature(pub.getModulus(), BlindSignature.PUBLIC_E);

        assertFalse("keyset-A credit verified under keyset B",
                other.verify(secret, sig.mod(pub.getModulus())));
        assertFalse("keyset ids must differ so the server picks the right key",
                Arrays.equals(bs.keysetId(), other.keysetId()));
    }

    /** Wrong blinding factor on resume: unblinding the mint's blind signature with
     *  a DIFFERENT r (a corrupted persisted purchase) yields garbage that fails
     *  verify — the resume path can never accidentally "validate" a mixed-up
     *  record; it just fails redeem and retries issue. */
    @Test
    public void unblindWithWrongRFails() {
        byte[] secret = randomSecret();
        BlindSignature.Blinded bl = bs.blind(secret, rng);
        BigInteger blindSig = bl.blinded.modPow(d, n);

        BigInteger wrongR = bs.blind(randomSecret(), rng).r;
        BigInteger sig = bs.unblind(blindSig, wrongR);
        assertFalse("unblind(wrong r) must not verify", bs.verify(secret, sig));

        // The persisted (blinded, r) pair rebuilt via blindedFrom + the RIGHT r
        // still works — the actual resume path.
        BlindSignature.Blinded resumed = BlindSignature.blindedFrom(bl.blinded, bl.r);
        assertTrue(bs.verify(secret, bs.unblind(blindSig, resumed.r)));
    }
}

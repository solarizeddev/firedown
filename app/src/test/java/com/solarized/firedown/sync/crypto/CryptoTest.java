package com.solarized.firedown.sync.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM unit tests for the sync crypto primitives. The canonical + Crockford cases
 * are pinned against the Go server's interop vectors (firedown-api
 * tests/api-vectors) so any drift between the two implementations fails here —
 * the early warning for the auth-breaking class of bug.
 */
public class CryptoTest {

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16));
            sb.append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }

    // ---- Crockford (matches crockford.json) ----

    @Test
    public void crockfordSpecExampleBytesEncodeTo26Chars() {
        // Spec §3 example BYTES -> the correct 26-char MSB-first encoding (the
        // spec's printed 28-char string is illustrative/wrong).
        byte[] bytes = hex("018d7e4a1c9f0eb547236bfa02d4c891");
        String enc = Crockford.encode(bytes);
        assertEquals("066QWJGWKW7BAHS3DFX05N68J4", enc);
        assertEquals(26, enc.length());
        assertArrayEquals(bytes, Crockford.decode(enc));
    }

    @Test
    public void crockfordCaseInsensitiveAndHyphenTolerant() {
        byte[] raw = hex("000102030405060708090a0b0c0d0e0f");
        String enc = Crockford.encode(raw);
        assertArrayEquals(raw, Crockford.decode(enc.toLowerCase()));
        assertArrayEquals(raw, Crockford.decode(enc.substring(0, 4) + "-" + enc.substring(4)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void crockfordRejectsU() {
        Crockford.decode("UUUU");
    }

    // ---- Canonical (matches canonical.json) ----

    @Test
    public void canonicalMatchesServerVector() {
        byte[] account = hex("000102030405060708090a0b0c0d0e0f");
        byte[] c = Canonical.build(account, "GET", "/v1/sync/bookmarks", "", 1718841600L, null);
        // From firedown-api tests/api-vectors/canonical.json (empty body/query).
        assertEquals(
                "000102030405060708090a0b0c0d0e0f0a4745540a2f76312f73796e632f626f6f6b6d61726b730a0a313731383834313630300ae3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                hex(c));
    }

    @Test
    public void canonicalEmptyBodyUsesEmptySha() {
        byte[] c = Canonical.build(new byte[16], "GET", "/x", "", 0, new byte[0]);
        String h = hex(c);
        assertTrue(h.endsWith("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
    }

    // ---- HKDF (RFC 5869 test case 1) ----

    @Test
    public void hkdfRfc5869Case1() {
        byte[] ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = hex("000102030405060708090a0b0c");
        byte[] info = hex("f0f1f2f3f4f5f6f7f8f9");
        byte[] prk = Hkdf.extract(salt, ikm);
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", hex(prk));
        byte[] okm = Hkdf.expand(prk, info, 42);
        assertEquals(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                hex(okm));
    }

    // ---- PoW ----

    @Test
    public void powSolveAndVerify() {
        byte[] account = hex("018d7e4a1c9f0eb547236bfa02d4c891");
        byte[] challenge = new byte[32];
        int k = 12; // cheap for a test
        byte[] nonce = Pow.solve(account, challenge, k);
        assertTrue(Pow.verify(account, challenge, nonce, k));
    }

    @Test
    public void leadingZeroBits() {
        assertEquals(0, Pow.leadingZeroBits(new byte[]{(byte) 0xff}));
        assertEquals(7, Pow.leadingZeroBits(new byte[]{0x01}));
        assertEquals(16, Pow.leadingZeroBits(new byte[]{0x00, 0x00, (byte) 0x80}));
    }

    // ---- Ed25519 + identity ----

    @Test
    public void ed25519SignVerifyRoundTrip() {
        byte[] seed = new byte[32];
        for (int i = 0; i < 32; i++) {
            seed[i] = (byte) i;
        }
        Ed25519Identity signer = new Ed25519Identity(seed);
        byte[] msg = "firedown".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] sig = signer.sign(msg);
        assertEquals(64, sig.length);
        assertEquals(32, signer.publicKey().length);
        assertTrue(Ed25519Identity.verify(signer.publicKey(), msg, sig));
        byte[] tampered = msg.clone();
        tampered[0] ^= 1;
        assertFalse(Ed25519Identity.verify(signer.publicKey(), tampered, sig));
    }

    @Test
    public void identityIsDeterministicAndShaped() {
        byte[] code = new byte[32];
        for (int i = 0; i < 32; i++) {
            code[i] = (byte) (i * 7);
        }
        SyncIdentity a = SyncIdentity.fromCode(code);
        SyncIdentity b = SyncIdentity.fromCode(code.clone());
        // Deterministic: same code -> same identity on any device.
        assertEquals(a.accountBase32(), b.accountBase32());
        assertArrayEquals(a.authPublicKey(), b.authPublicKey());
        assertArrayEquals(a.fileKey(), b.fileKey());
        assertEquals(16, a.accountId().length);
        assertEquals(26, a.accountBase32().length());
        assertEquals(32, a.authPublicKey().length);
        assertEquals(32, a.fileKey().length);

        // A signature from the identity verifies against its own pubkey.
        byte[] canonical = Canonical.build(a.accountId(), "GET", "/v1/quota", "", 1L, null);
        assertTrue(Ed25519Identity.verify(a.authPublicKey(), canonical, a.sign(canonical)));
    }

    @Test
    public void recoveryCodeRoundTrip() {
        byte[] code = SyncIdentity.generateRecoveryCode();
        assertEquals(32, code.length);
        String enc = SyncIdentity.encodeRecoveryCode(code);
        assertArrayEquals(code, SyncIdentity.decodeRecoveryCode(enc));
        // Grouped display is hyphen-tolerant on decode.
        assertArrayEquals(code, SyncIdentity.decodeRecoveryCode(SyncIdentity.grouped(enc)));
    }

    // ---- Blob round-trip ----

    @Test
    public void blobEncryptDecryptRoundTrip() throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) (i + 1);
        }
        String json = "{\"v\":1,\"bookmarks\":[],\"tombstones\":[]}";
        byte[] blob = BookmarkBlob.encrypt(json, key);
        assertEquals(json, BookmarkBlob.decrypt(blob, key));

        // Wrong key must fail AEAD.
        byte[] wrong = key.clone();
        wrong[0] ^= 0xff;
        try {
            BookmarkBlob.decrypt(blob, wrong);
            throw new AssertionError("decrypt with wrong key should fail");
        } catch (java.security.GeneralSecurityException expected) {
            // ok
        }
        // Ciphertext is not the plaintext.
        assertNotEquals(json, hex(blob));
    }
}

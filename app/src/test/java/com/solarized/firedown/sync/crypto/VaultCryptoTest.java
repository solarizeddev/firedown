package com.solarized.firedown.sync.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * JVM unit tests for the vault chunk framing — specifically the FDVC2
 * position-binding AAD, whose byte layout is a shared wire format with the web
 * client ({@code backup/fd-crypto.js}). Android-free on purpose (like
 * {@link PairSealTest}), so it runs under a plain JVM with just junit on the
 * classpath — do that rather than assuming (the PairSealTest lesson: it had
 * been failing for a whole format bump because nobody ran the suite).
 */
public class VaultCryptoTest {

    /** A server-shaped object id: 16 bytes, 32 lowercase hex chars. */
    private static final String OBJ = "00112233445566778899aabbccddeeff";

    private static byte[] hex(String s) {
        return Hex.decode(s);
    }

    @Test
    public void v2RoundTripAtTheRightPosition() throws Exception {
        byte[] dek = VaultCrypto.generateDek();
        byte[] plain = {1, 2, 3, 4};
        byte[] blob = VaultCrypto.encryptChunk(plain, dek, OBJ, 3);
        // Written format is FDVC2, version byte 2 — self-describing, no
        // manifest field needed.
        assertEquals("FDVC2", new String(blob, 0, 5, StandardCharsets.US_ASCII));
        assertEquals(2, blob[5]);
        // AAD is authenticated, NOT stored: the overhead must stay exactly the
        // 34 bytes the server signs into presigned PUT Content-Lengths
        // (VaultEngine.CHUNK_OVERHEAD). A drift here 403s every upload.
        assertEquals(34, blob.length - plain.length);
        assertArrayEquals(plain, VaultCrypto.decryptChunk(blob, dek, OBJ, 3));
        // Hex-decoded id, so letter case cannot desync the two clients.
        assertArrayEquals(plain, VaultCrypto.decryptChunk(blob, dek, OBJ.toUpperCase(), 3));
    }

    @Test
    public void wrongPositionOrObjectFailsAuthentication() throws Exception {
        // The reorder/splice attack the AAD exists to detect: a malicious server
        // returning the right chunks in the wrong order (restore iterates the
        // SERVER-SUPPLIED url list) used to decrypt cleanly into a scrambled file.
        byte[] dek = VaultCrypto.generateDek();
        byte[] blob = VaultCrypto.encryptChunk(new byte[]{1, 2, 3, 4}, dek, OBJ, 3);
        try {
            VaultCrypto.decryptChunk(blob, dek, OBJ, 4);
            fail("chunk at the wrong index should fail GCM auth");
        } catch (GeneralSecurityException expected) {
            // ok
        }
        try {
            VaultCrypto.decryptChunk(blob, dek, "ff112233445566778899aabbccddeeff", 3);
            fail("chunk from another object should fail GCM auth");
        } catch (GeneralSecurityException expected) {
            // ok
        }
    }

    @Test
    public void legacyV1ChunkStillDecrypts() throws Exception {
        // Every object already in the bucket is FDVC1 (no AAD) and must stay
        // restorable, whatever id/index the restore passes. Built here with the
        // V1 writer's exact layout, since VaultCrypto deliberately no longer
        // has one.
        byte[] dek = VaultCrypto.generateDek();
        byte[] plain = {9, 8, 7};
        byte[] v1 = sealV1(plain, dek);
        assertArrayEquals(plain, VaultCrypto.decryptChunk(v1, dek, OBJ, 3));

        // An unknown version byte is a refusal, never a silent no-AAD fallback.
        byte[] v3 = v1.clone();
        v3[4] = '3';
        v3[5] = 3;
        try {
            VaultCrypto.decryptChunk(v3, dek, OBJ, 3);
            fail("unknown chunk version should be refused");
        } catch (GeneralSecurityException expected) {
            // ok
        }
    }

    @Test
    public void fdvc2InteropVectorDecrypts() throws Exception {
        // Cross-client pin: this exact blob was sealed with node:crypto
        // (OpenSSL — an implementation sharing code with neither client) from
        // fixed inputs, and the SAME hex is asserted by the web client's
        // tools/fd-crypto-selftest.mjs. If either client's AAD layout
        // (hexDecode(objectId) || uint32-BE chunkIndex) drifts by a byte, its
        // copy of this decrypt fails — the tripwire that replaces "a file
        // written by one client is unreadable by the other with no useful
        // diagnostic". Regenerating means re-copying the hex into BOTH tests.
        byte[] dek = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        byte[] blob = hex("464456433202a0a1a2a3a4a5a6a7a8a9aaab80710e4821a475d14213e6a66b0e"
                + "e0bd18d9377bb2c1704cea6b45f210d9b800603ec9bbd5a65b81972fe36e3054");
        assertArrayEquals("firedown vault chunk v2 vector".getBytes(StandardCharsets.UTF_8),
                VaultCrypto.decryptChunk(blob, dek, OBJ, 7));
    }

    /** The pre-FDVC2 chunk writer, byte-for-byte: 'FDVC1' | 1 | iv(12) | ct+tag. */
    private static byte[] sealV1(byte[] plain, byte[] dek) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"),
                new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('F');
        out.write('D');
        out.write('V');
        out.write('C');
        out.write('1');
        out.write(1);
        out.write(iv, 0, iv.length);
        out.write(ct, 0, ct.length);
        return out.toByteArray();
    }
}

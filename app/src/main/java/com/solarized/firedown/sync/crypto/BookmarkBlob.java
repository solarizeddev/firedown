package com.solarized.firedown.sync.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The encrypted bookmark blob: gzip the canonical JSON, then AES-256-GCM with the
 * file_key. Framing mirrors {@link com.solarized.firedown.data.DownloadBackupMirror}
 * (MAGIC | 12-byte IV | ciphertext+tag), with a distinct magic {@code FDSB1} and a
 * schema-version byte so the format can evolve. The server stores these bytes
 * opaquely; the key never leaves the device. (Spec §6.5: gzip BEFORE GCM.)
 *
 * <p>Layout: {@code 'F' 'D' 'S' 'B' '1' | version(1) | iv(12) | ciphertext+tag}.</p>
 */
public final class BookmarkBlob {

    private static final byte[] MAGIC = {'F', 'D', 'S', 'B', '1'};
    private static final byte SCHEMA_VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int HEADER_LEN = MAGIC.length + 1 + IV_BYTES;

    private BookmarkBlob() {}

    /** Encrypts the UTF-8 JSON document to the wire blob. */
    public static byte[] encrypt(String json, byte[] fileKey) throws GeneralSecurityException {
        byte[] gz = gzip(json.getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(fileKey, "AES"),
                new GCMParameterSpec(TAG_BITS, iv));
        byte[] ct = cipher.doFinal(gz);

        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_LEN + ct.length);
        out.write(MAGIC, 0, MAGIC.length);
        out.write(SCHEMA_VERSION);
        out.write(iv, 0, iv.length);
        out.write(ct, 0, ct.length);
        return out.toByteArray();
    }

    /** Decrypts a wire blob back to the UTF-8 JSON document. */
    public static String decrypt(byte[] blob, byte[] fileKey) throws GeneralSecurityException {
        if (blob == null || blob.length < HEADER_LEN) {
            throw new GeneralSecurityException("blob too short");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (blob[i] != MAGIC[i]) {
                throw new GeneralSecurityException("bad blob magic");
            }
        }
        // blob[MAGIC.length] is the schema version (only v1 today).
        GCMParameterSpec spec = new GCMParameterSpec(TAG_BITS, blob, MAGIC.length + 1, IV_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(fileKey, "AES"), spec);
        byte[] gz = cipher.doFinal(blob, HEADER_LEN, blob.length - HEADER_LEN);
        return new String(gunzip(gz), StandardCharsets.UTF_8);
    }

    private static byte[] gzip(byte[] data) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(data);
            }
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("gzip failed", e);
        }
    }

    private static byte[] gunzip(byte[] data) {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 3);
            byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("gunzip failed", e);
        }
    }
}

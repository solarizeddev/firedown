package com.solarized.firedown.sync.crypto;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Client-side encryption for the encrypted-storage vault (storage.firedown.app,
 * cloud-storage-spec.md §5). All of it runs on-device; the server stores opaque
 * ciphertext and never sees a filename, a key, or a plaintext byte.
 *
 * <p>Model:</p>
 * <ul>
 *   <li><b>Per-file DEK</b> — a random AES-256 key per file. File bytes are
 *       chunked and each chunk is AES-256-GCM'd under the DEK with a fresh random
 *       IV, so uploads are resumable and a partial upload never reuses a nonce.
 *       (No gzip — downloaded media is already compressed; gzip would only burn
 *       CPU, unlike the bookmark JSON blob.)</li>
 *   <li><b>Wrapped DEK</b> — the DEK encrypted under the storage master key
 *       ({@link SyncIdentity#storageMasterKey()}) and stored INSIDE the client's
 *       encrypted manifest, never sent to the server in the clear.</li>
 * </ul>
 *
 * <p>Framing mirrors {@link BookmarkBlob}: {@code MAGIC | version(1) | iv(12) |
 * ciphertext+tag}. Distinct magics: {@code FDVC1} for a file chunk, {@code FDVK1}
 * for a wrapped DEK.</p>
 */
public final class VaultCrypto {

    private static final byte[] MAGIC_CHUNK = {'F', 'D', 'V', 'C', '1'};
    private static final byte[] MAGIC_DEK = {'F', 'D', 'V', 'K', '1'};
    private static final byte SCHEMA_VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final int HEADER_LEN = MAGIC_CHUNK.length + 1 + IV_BYTES; // both magics same length

    private VaultCrypto() {}

    /** Generates a fresh random per-file data key (AES-256). */
    public static byte[] generateDek() {
        byte[] dek = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(dek);
        return dek;
    }

    /** Encrypts one file chunk under the DEK. */
    public static byte[] encryptChunk(byte[] plain, byte[] dek) throws GeneralSecurityException {
        return seal(MAGIC_CHUNK, plain, dek);
    }

    /** Decrypts one file chunk under the DEK. */
    public static byte[] decryptChunk(byte[] blob, byte[] dek) throws GeneralSecurityException {
        return open(MAGIC_CHUNK, blob, dek);
    }

    /** Wraps a DEK under the storage master key (the blob stored in the manifest). */
    public static byte[] wrapDek(byte[] dek, byte[] masterKey) throws GeneralSecurityException {
        return seal(MAGIC_DEK, dek, masterKey);
    }

    /** Unwraps a DEK from its manifest blob under the storage master key. */
    public static byte[] unwrapDek(byte[] wrapped, byte[] masterKey) throws GeneralSecurityException {
        return open(MAGIC_DEK, wrapped, masterKey);
    }

    // ---- GCM seal/open with the BookmarkBlob framing ----

    private static byte[] seal(byte[] magic, byte[] plain, byte[] key) throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, iv));
        byte[] ct = cipher.doFinal(plain);

        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_LEN + ct.length);
        out.write(magic, 0, magic.length);
        out.write(SCHEMA_VERSION);
        out.write(iv, 0, iv.length);
        out.write(ct, 0, ct.length);
        return out.toByteArray();
    }

    private static byte[] open(byte[] magic, byte[] blob, byte[] key) throws GeneralSecurityException {
        if (blob == null || blob.length < HEADER_LEN) {
            throw new GeneralSecurityException("vault blob too short");
        }
        for (int i = 0; i < magic.length; i++) {
            if (blob[i] != magic[i]) {
                throw new GeneralSecurityException("bad vault blob magic");
            }
        }
        // blob[magic.length] is the schema version (only v1 today).
        GCMParameterSpec spec = new GCMParameterSpec(TAG_BITS, blob, magic.length + 1, IV_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), spec);
        return cipher.doFinal(blob, HEADER_LEN, blob.length - HEADER_LEN);
    }
}

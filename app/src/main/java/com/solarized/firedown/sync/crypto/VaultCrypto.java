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
 * ciphertext+tag}. Magics: {@code FDVC2} for a file chunk (written), {@code FDVC1}
 * for a legacy chunk (read-only), {@code FDVK1} for a wrapped DEK.</p>
 *
 * <p><b>FDVC2 binds each chunk to ITS OBJECT AND POSITION</b> via GCM's
 * additional authenticated data. FDVC1 chunks authenticated only their bytes,
 * so a malicious server could return the same file's chunks in a different
 * order (restore iterates the SERVER-SUPPLIED url list) and every chunk still
 * decrypted cleanly — a scrambled file that "restored successfully", with only
 * the chunk-count and byte-total checks as backstops (neither can see a
 * reorder). With AAD, a chunk served at the wrong index — or swapped in from
 * another object — fails GCM authentication outright.</p>
 *
 * <p>The AAD is a FIFTH shared wire format (with the canonical, the blob
 * framings, {@code FDPR1}, and the QR payload) and must byte-match the web
 * client's {@code fd-crypto.js}:</p>
 *
 * <pre>AAD = objectId (hex-decoded, 16 bytes) || chunkIndex (uint32, big-endian)</pre>
 *
 * <p>The object id is the server-issued 32-char lowercase hex id, DECODED to
 * its raw 16 bytes (decoding rather than UTF-8-ing the string makes letter
 * case irrelevant); the index is 0-based in upload order. AAD is authenticated
 * but NOT stored, so the ciphertext length — and with it
 * {@code VaultEngine.CHUNK_OVERHEAD} (34) and every declared
 * byte_size/chunk_size the server signs into presigned PUT Content-Lengths —
 * is byte-identical to FDVC1. The version byte at offset 5 makes the format
 * self-describing: {@link #decryptChunk} branches on the byte it reads, so no
 * manifest field records which format an object uses and every FDVC1 object
 * already in the bucket stays restorable. (The reverse is not true: an APK
 * from before FDVC2 rejects a chunk written after it — accepted for this
 * deployment, recorded in the commit that introduced it.)</p>
 */
public final class VaultCrypto {

    private static final byte[] MAGIC_CHUNK_V1 = {'F', 'D', 'V', 'C', '1'};
    private static final byte[] MAGIC_CHUNK_V2 = {'F', 'D', 'V', 'C', '2'};
    private static final byte[] MAGIC_DEK = {'F', 'D', 'V', 'K', '1'};
    private static final byte SCHEMA_VERSION = 1;
    private static final byte CHUNK_VERSION_V1 = 1;
    private static final byte CHUNK_VERSION_V2 = 2;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final int HEADER_LEN = MAGIC_DEK.length + 1 + IV_BYTES; // all magics same length

    private VaultCrypto() {}

    /** Generates a fresh random per-file data key (AES-256). */
    public static byte[] generateDek() {
        byte[] dek = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(dek);
        return dek;
    }

    /** Encrypts one file chunk under the DEK — always FDVC2, bound to
     *  ({@code objectId}, {@code chunkIndex}) as GCM AAD (layout above). */
    public static byte[] encryptChunk(byte[] plain, byte[] dek, String objectId, int chunkIndex)
            throws GeneralSecurityException {
        return seal(MAGIC_CHUNK_V2, CHUNK_VERSION_V2, plain, dek, chunkAad(objectId, chunkIndex));
    }

    /**
     * Decrypts one file chunk under the DEK. Branches on the header's version
     * byte: FDVC2 verifies the chunk really is chunk {@code chunkIndex} of
     * object {@code objectId} (AAD); FDVC1 (objects uploaded before position
     * binding) has no AAD to verify, so legacy objects keep restoring — their
     * protection stays the chunk-count and byte-total checks only.
     */
    public static byte[] decryptChunk(byte[] blob, byte[] dek, String objectId, int chunkIndex)
            throws GeneralSecurityException {
        if (blob == null || blob.length < HEADER_LEN) {
            throw new GeneralSecurityException("vault blob too short");
        }
        byte version = blob[MAGIC_DEK.length];
        if (version == CHUNK_VERSION_V2) {
            return open(MAGIC_CHUNK_V2, blob, dek, chunkAad(objectId, chunkIndex));
        }
        if (version == CHUNK_VERSION_V1) {
            return open(MAGIC_CHUNK_V1, blob, dek, null);
        }
        throw new GeneralSecurityException("unknown vault chunk version " + version);
    }

    /** Wraps a DEK under the storage master key (the blob stored in the manifest). */
    public static byte[] wrapDek(byte[] dek, byte[] masterKey) throws GeneralSecurityException {
        return seal(MAGIC_DEK, SCHEMA_VERSION, dek, masterKey, null);
    }

    /** Unwraps a DEK from its manifest blob under the storage master key. */
    public static byte[] unwrapDek(byte[] wrapped, byte[] masterKey) throws GeneralSecurityException {
        return open(MAGIC_DEK, wrapped, masterKey, null);
    }

    /** The FDVC2 chunk AAD — see the layout comment in the class doc. */
    private static byte[] chunkAad(String objectId, int chunkIndex) {
        byte[] id = Hex.decode(objectId);
        byte[] aad = new byte[id.length + 4];
        System.arraycopy(id, 0, aad, 0, id.length);
        aad[id.length] = (byte) (chunkIndex >>> 24);
        aad[id.length + 1] = (byte) (chunkIndex >>> 16);
        aad[id.length + 2] = (byte) (chunkIndex >>> 8);
        aad[id.length + 3] = (byte) chunkIndex;
        return aad;
    }

    // ---- GCM seal/open with the BookmarkBlob framing ----

    private static byte[] seal(byte[] magic, byte version, byte[] plain, byte[] key, byte[] aad)
            throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, iv));
        if (aad != null) {
            cipher.updateAAD(aad);
        }
        byte[] ct = cipher.doFinal(plain);

        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_LEN + ct.length);
        out.write(magic, 0, magic.length);
        out.write(version);
        out.write(iv, 0, iv.length);
        out.write(ct, 0, ct.length);
        return out.toByteArray();
    }

    private static byte[] open(byte[] magic, byte[] blob, byte[] key, byte[] aad)
            throws GeneralSecurityException {
        if (blob == null || blob.length < HEADER_LEN) {
            throw new GeneralSecurityException("vault blob too short");
        }
        for (int i = 0; i < magic.length; i++) {
            if (blob[i] != magic[i]) {
                throw new GeneralSecurityException("bad vault blob magic");
            }
        }
        // blob[magic.length] is the schema version; chunk callers branch on it
        // BEFORE calling here (the magic's own trailing digit is kept in
        // lockstep with it, so the compare above pins both).
        GCMParameterSpec spec = new GCMParameterSpec(TAG_BITS, blob, magic.length + 1, IV_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), spec);
        if (aad != null) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(blob, HEADER_LEN, blob.length - HEADER_LEN);
    }
}

package com.solarized.firedown.sync.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The sync account identity derived deterministically from the recovery code
 * (docs/BOOKMARKS_SYNC.md §2). The server is zero-knowledge about keys — it only
 * ever sees account_id, the Ed25519 pubkey, signatures, and ciphertext — so the
 * client owns this derivation; it just has to be deterministic (same code -&gt;
 * same identity on every device).
 *
 * <pre>
 *   PRK        = HKDF-Extract(salt="firedown/sync/v1", ikm=recovery_code_bytes)
 *   account_id = HKDF-Expand(PRK, "firedown/sync/account-id/v1",   16)
 *   auth_seed  = HKDF-Expand(PRK, "firedown/sync/auth-ed25519/v1", 32) -> Ed25519 keypair
 *   master_enc = HKDF-Expand(PRK, "firedown/sync/master-enc/v1",   32)
 *   file_key   = HKDF(master_enc, "firedown/bookmarks/v1",         32)  // AES-256-GCM blob key
 * </pre>
 *
 * The recovery code is 256 bits of CSPRNG random, presented to the user as
 * Crockford base32 (case-insensitive, hyphen-tolerant on entry).
 */
public final class SyncIdentity {

    private static final byte[] ROOT_SALT = "firedown/sync/v1".getBytes(StandardCharsets.US_ASCII);
    private static final String INFO_ACCOUNT = "firedown/sync/account-id/v1";
    private static final String INFO_AUTH = "firedown/sync/auth-ed25519/v1";
    private static final String INFO_MASTER = "firedown/sync/master-enc/v1";
    private static final String INFO_FILE = "firedown/bookmarks/v1";

    /** Length of the recovery code in bytes (256-bit). */
    public static final int RECOVERY_CODE_BYTES = 32;

    private final byte[] accountId;   // 16 bytes
    private final String accountBase32;
    private final byte[] masterEnc;   // 32 bytes
    private final byte[] fileKey;     // 32 bytes — AES-256-GCM blob key
    private final Ed25519Identity signer;

    private SyncIdentity(byte[] code) {
        byte[] prk = Hkdf.extract(ROOT_SALT, code);
        this.accountId = Hkdf.expand(prk, INFO_ACCOUNT.getBytes(StandardCharsets.US_ASCII), 16);
        byte[] authSeed = Hkdf.expand(prk, INFO_AUTH.getBytes(StandardCharsets.US_ASCII), 32);
        this.masterEnc = Hkdf.expand(prk, INFO_MASTER.getBytes(StandardCharsets.US_ASCII), 32);
        this.fileKey = Hkdf.derive(masterEnc, new byte[0],
                INFO_FILE, 32); // file_key = HKDF(master_enc, "firedown/bookmarks/v1")
        Arrays.fill(prk, (byte) 0);

        this.signer = new Ed25519Identity(authSeed);
        Arrays.fill(authSeed, (byte) 0);
        this.accountBase32 = Crockford.encode(accountId);
    }

    /** Derives the identity from raw recovery-code bytes (32 bytes). */
    public static SyncIdentity fromCode(byte[] code) {
        if (code == null || code.length != RECOVERY_CODE_BYTES) {
            throw new IllegalArgumentException("recovery code must be " + RECOVERY_CODE_BYTES + " bytes");
        }
        return new SyncIdentity(code);
    }

    /** Derives the identity from a recovery code as entered/stored (base32). */
    public static SyncIdentity fromCodeString(String code) {
        return fromCode(decodeRecoveryCode(code));
    }

    /** Generates a fresh 256-bit recovery code from a CSPRNG. */
    public static byte[] generateRecoveryCode() {
        byte[] code = new byte[RECOVERY_CODE_BYTES];
        new SecureRandom().nextBytes(code);
        return code;
    }

    /** Encodes a recovery code as Crockford base32 (no hyphens — see {@link #grouped}). */
    public static String encodeRecoveryCode(byte[] code) {
        return Crockford.encode(code);
    }

    /** Decodes a recovery code the user typed (case-insensitive, hyphens/spaces tolerated). */
    public static byte[] decodeRecoveryCode(String s) {
        String cleaned = s.replace(" ", "").trim();
        byte[] out = Crockford.decode(cleaned);
        if (out.length != RECOVERY_CODE_BYTES) {
            throw new IllegalArgumentException("recovery code wrong length");
        }
        return out;
    }

    /** Groups a base32 recovery code into hyphenated 4-char blocks for display. */
    public static String grouped(String encoded) {
        StringBuilder sb = new StringBuilder(encoded.length() + encoded.length() / 4);
        for (int i = 0; i < encoded.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            sb.append(encoded.charAt(i));
        }
        return sb.toString();
    }

    public byte[] accountId() {
        return accountId.clone();
    }

    public String accountBase32() {
        return accountBase32;
    }

    public byte[] authPublicKey() {
        return signer.publicKey();
    }

    public byte[] fileKey() {
        return fileKey.clone();
    }

    /** Signs the canonical bytes for an authenticated request. */
    public byte[] sign(byte[] canonical) {
        return signer.sign(canonical);
    }
}

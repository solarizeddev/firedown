package com.solarized.firedown.sync.crypto;

import com.google.crypto.tink.subtle.Ed25519Sign;
import com.google.crypto.tink.subtle.Ed25519Verify;

import java.security.GeneralSecurityException;

/**
 * Ed25519 signing for the sync request canonical, over Google Tink's subtle
 * Ed25519. The platform java.security EdEC APIs need API 33 but minSdk is 26, and
 * the signing key is derived from the recovery-code seed (not an AndroidKeyStore
 * key), so we use a Google-maintained impl that accepts a raw 32-byte seed on
 * every API level. RFC-8032-compliant, so the signatures match the server's
 * ed25519 interop vectors.
 */
public final class Ed25519Signer {

    private final Ed25519Sign signer;
    private final byte[] publicKey;

    /** Builds a signer from a 32-byte Ed25519 seed (the HKDF-derived auth_seed). */
    public Ed25519Signer(byte[] seed) {
        if (seed == null || seed.length != 32) {
            throw new IllegalArgumentException("ed25519 seed must be 32 bytes");
        }
        try {
            Ed25519Sign.KeyPair kp = Ed25519Sign.KeyPair.newKeyPair(seed);
            this.publicKey = kp.getPublicKey().clone();
            this.signer = new Ed25519Sign(kp.getPrivateKey());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ed25519 keypair from seed failed", e);
        }
    }

    /** The 32-byte raw Ed25519 public key (what the server stores as auth_pubkey). */
    public byte[] publicKey() {
        return publicKey.clone();
    }

    /** Signs {@code message}, returning the 64-byte signature. */
    public byte[] sign(byte[] message) {
        try {
            return signer.sign(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ed25519 sign failed", e);
        }
    }

    /** Verifies a 64-byte signature against a 32-byte public key (used in tests). */
    public static boolean verify(byte[] publicKey, byte[] message, byte[] sig) {
        try {
            new Ed25519Verify(publicKey).verify(sig, message);
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}

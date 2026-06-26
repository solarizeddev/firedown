package com.solarized.firedown.sync.crypto;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/**
 * Ed25519 auth identity for the sync request canonical, over Bouncy Castle's
 * RFC-8032 lightweight Ed25519.
 *
 * <p>The signing key is DERIVED from the recovery-code seed (the HKDF-derived
 * {@code auth_seed}, a raw 32-byte value — not an AndroidKeyStore key), so we
 * need to build a keypair from that seed deterministically on minSdk 26. The
 * platform {@code java.security} EdEC APIs need API 33, and Tink's subtle
 * Ed25519 only exposes a RANDOM {@code newKeyPair()} publicly (its seed-based
 * {@code newKeyPair(secretSeed)} is package-private, uncallable from here). BC's
 * lightweight {@code org.bouncycastle.crypto.*} API takes a raw seed on every
 * API level and is RFC-8032-compliant, so the signatures match the server's
 * ed25519 interop vectors. Used directly (no JCE provider registration), so it
 * does not conflict with Android's repackaged bundled BouncyCastle.
 *
 * <p>Named {@code Ed25519Identity} (not {@code Ed25519Signer}) so BC's
 * {@code org.bouncycastle.crypto.signers.Ed25519Signer} can be imported by its
 * simple name instead of fully qualified at each use site.
 */
public final class Ed25519Identity {

    private final Ed25519PrivateKeyParameters privateKey;
    private final byte[] publicKey;

    /** Builds an identity from a 32-byte Ed25519 seed (the HKDF-derived auth_seed). */
    public Ed25519Identity(byte[] seed) {
        if (seed == null || seed.length != 32) {
            throw new IllegalArgumentException("ed25519 seed must be 32 bytes");
        }
        this.privateKey = new Ed25519PrivateKeyParameters(seed, 0);
        this.publicKey = this.privateKey.generatePublicKey().getEncoded();
    }

    /** The 32-byte raw Ed25519 public key (what the server stores as auth_pubkey). */
    public byte[] publicKey() {
        return publicKey.clone();
    }

    /** Signs {@code message}, returning the 64-byte signature. */
    public byte[] sign(byte[] message) {
        Ed25519Signer s = new Ed25519Signer();
        s.init(true, privateKey);
        s.update(message, 0, message.length);
        return s.generateSignature();
    }

    /** Verifies a 64-byte signature against a 32-byte public key (used in tests). */
    public static boolean verify(byte[] publicKey, byte[] message, byte[] sig) {
        Ed25519Signer v = new Ed25519Signer();
        v.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
        v.update(message, 0, message.length);
        return v.verifySignature(sig);
    }
}

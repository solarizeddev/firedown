package com.solarized.firedown.sync.crypto;

import java.security.MessageDigest;

import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

/**
 * Ed25519 signing for the sync request canonical, over the pure-Java eddsa
 * library (the JDK EdEC APIs need API 33 but minSdk is 26). The signing key is
 * derived from the recovery code (a raw 32-byte seed), not an AndroidKeyStore
 * key, which is why a software impl is required. RFC-8032-compliant, so the
 * signatures match the server's ed25519 interop vectors.
 */
public final class Ed25519Signer {

    private static final EdDSANamedCurveSpec CURVE =
            EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);

    private final EdDSAPrivateKey privateKey;
    private final byte[] publicKey;

    /** Builds a signer from a 32-byte Ed25519 seed (the HKDF-derived auth_seed). */
    public Ed25519Signer(byte[] seed) {
        if (seed == null || seed.length != 32) {
            throw new IllegalArgumentException("ed25519 seed must be 32 bytes");
        }
        EdDSAPrivateKeySpec privSpec = new EdDSAPrivateKeySpec(seed, CURVE);
        this.privateKey = new EdDSAPrivateKey(privSpec);
        EdDSAPublicKey pub = new EdDSAPublicKey(new EdDSAPublicKeySpec(privSpec.getA(), CURVE));
        this.publicKey = pub.getAbyte().clone();
    }

    /** The 32-byte raw Ed25519 public key (what the server stores as auth_pubkey). */
    public byte[] publicKey() {
        return publicKey.clone();
    }

    /** Signs {@code message}, returning the 64-byte signature. */
    public byte[] sign(byte[] message) {
        try {
            EdDSAEngine engine = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));
            engine.initSign(privateKey);
            engine.update(message);
            return engine.sign();
        } catch (Exception e) {
            throw new IllegalStateException("ed25519 sign failed", e);
        }
    }

    /** Verifies a 64-byte signature against a 32-byte public key (used in tests). */
    public static boolean verify(byte[] publicKey, byte[] message, byte[] sig) {
        try {
            EdDSAPublicKey pub = new EdDSAPublicKey(new EdDSAPublicKeySpec(publicKey, CURVE));
            EdDSAEngine engine = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));
            engine.initVerify(pub);
            engine.update(message);
            return engine.verify(sig);
        } catch (Exception e) {
            return false;
        }
    }
}

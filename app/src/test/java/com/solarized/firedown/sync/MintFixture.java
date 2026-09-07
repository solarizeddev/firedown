package com.solarized.firedown.sync;

import com.solarized.firedown.sync.crypto.BlindSignature;
import com.solarized.firedown.sync.crypto.Hex;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/** A mint's key material for tests: a real RSA key, its keyset id as the mint
 *  derives it, and the JSON the endpoints would carry it in. */
final class MintFixture {

    static final String QUOTE_ID = "00112233445566778899aabbccddeeff";

    final BigInteger n;
    final RSAPrivateKey priv;
    final BlindSignature keyset;
    final String keysetIdHex;

    MintFixture() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        n = ((RSAPublicKey) kp.getPublic()).getModulus();
        priv = (RSAPrivateKey) kp.getPrivate();
        keyset = new BlindSignature(n, BlindSignature.PUBLIC_E);
        keysetIdHex = Hex.encode(keyset.keysetId());
    }

    /** The real blind signature over a blinded message. */
    BigInteger sign(BigInteger blinded) {
        return blinded.modPow(priv.getPrivateExponent(), n);
    }

    /** /keys carrying THIS key under its own id (honest). */
    String keysJson() {
        return keysJson(keysetIdHex, n);
    }

    /** /keys carrying an arbitrary (id, modulus) pairing — for forgeries. */
    static String keysJson(String idHex, BigInteger modulus) {
        return "{\"keysets\":[{\"id\":\"" + idHex + "\",\"denomination_gb_months\":600,"
                + "\"price_cents\":1800,\"size_gb\":50,\"duration_months\":12,\"n\":\"" + modulus.toString(16)
                + "\",\"e\":65537,\"active\":true}],\"methods\":[\"lightning\",\"onchain\"]}";
    }

    String onchainQuoteJson() {
        return onchainQuoteJson("bitcoin:bc1qx?amount=0.00017", "bc1qx", 17000);
    }

    String onchainQuoteJson(String payRequest, String address, long sats) {
        return "{\"quote_id\":\"" + QUOTE_ID + "\",\"method\":\"onchain\",\"amount_cents\":1800,"
                + "\"denomination_gb_months\":600,\"size_gb\":50,\"duration_months\":12,\"keyset_id\":\""
                + keysetIdHex + "\",\"expires_at\":\"2026-07-02T13:00:00Z\",\"pay_request\":\"" + payRequest
                + "\",\"address\":\"" + address + "\",\"amount_sats\":" + sats + ",\"min_confirmations\":1}";
    }

    String lightningQuoteJson(String invoice, long sats) {
        return "{\"quote_id\":\"" + QUOTE_ID + "\",\"method\":\"lightning\",\"amount_cents\":1800,"
                + "\"denomination_gb_months\":600,\"size_gb\":50,\"duration_months\":12,\"keyset_id\":\""
                + keysetIdHex + "\",\"expires_at\":\"2026-07-02T13:00:00Z\",\"pay_request\":\"" + invoice
                + "\",\"amount_sats\":" + sats + "}";
    }

    String signedJson(BigInteger sig) {
        return "{\"keyset_id\":\"" + keysetIdHex + "\",\"blind_signature\":\"" + sig.toString(16) + "\"}";
    }
}

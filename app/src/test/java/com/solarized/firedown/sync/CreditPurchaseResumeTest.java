package com.solarized.firedown.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.solarized.firedown.sync.crypto.BlindSignature;
import com.solarized.firedown.sync.crypto.Hex;

import org.json.JSONObject;
import org.junit.Test;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;

/**
 * A purchase interrupted mid-wait (process death while an on-chain payment
 * confirms) must resume by re-issuing with the SAME blinded message. The mint
 * mints one credit per quote: a fresh blinding on resume would be a different
 * message, refused with 409 {@code already-issued} once the first was signed —
 * or, worse, a second credit the user never gets. Drives the real
 * {@link CreditPurchase} + {@link PendingPurchase} through a scripted mint
 * holding a real RSA key, so the resumed credit also unblinds and verifies.
 */
public class CreditPurchaseResumeTest {

    private static final String QUOTE_ID = "00112233445566778899aabbccddeeff";

    @Test
    public void restartMidWaitReissuesTheSameBlindedMessageAndTheCreditVerifies() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        java.security.KeyPair kp = kpg.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        RSAPrivateKey priv = (RSAPrivateKey) kp.getPrivate();
        BigInteger n = pub.getModulus();
        BlindSignature keyset = new BlindSignature(n, BlindSignature.PUBLIC_E);
        String keysetIdHex = Hex.encode(keyset.keysetId());
        String keysJson = "{\"keysets\":[{\"id\":\"" + keysetIdHex + "\",\"denomination_gb_months\":600,"
                + "\"price_cents\":1800,\"size_gb\":50,\"duration_months\":12,\"n\":\"" + n.toString(16)
                + "\",\"e\":65537,\"active\":true}],\"methods\":[\"lightning\",\"onchain\"]}";
        String quoteJson = "{\"quote_id\":\"" + QUOTE_ID + "\",\"method\":\"onchain\",\"amount_cents\":1800,"
                + "\"denomination_gb_months\":600,\"size_gb\":50,\"duration_months\":12,\"keyset_id\":\""
                + keysetIdHex + "\",\"expires_at\":\"2026-07-02T13:00:00Z\",\"pay_request\":\"bitcoin:bc1qx?amount=0.00017\","
                + "\"address\":\"bc1qx\",\"amount_sats\":17000,\"min_confirmations\":1}";

        // The mint: keys, one quote, then issue answers not-paid → pending → paid,
        // signing whatever blinded message arrives with the real private key.
        List<String> issuedMessages = new ArrayList<>();
        int[] issueCalls = {0};
        FakeMint mint = new FakeMint(r -> {
            switch (r.path) {
                case "/v1/mint/keys":
                    return new FakeMint.Reply(200, keysJson);
                case "/v1/mint/quote":
                    return new FakeMint.Reply(201, quoteJson);
                case "/v1/mint/issue":
                    try {
                        JSONObject body = new JSONObject(r.body);
                        issuedMessages.add(body.getString("blinded_message"));
                        int call = issueCalls[0]++;
                        if (call == 0) {
                            return new FakeMint.Reply(425, "{\"error\":\"not-paid-yet\"}");
                        }
                        if (call == 1) {
                            return new FakeMint.Reply(425, "{\"error\":\"not-paid-yet\",\"pending\":true,"
                                    + "\"expires_at\":\"2026-07-04T12:00:00Z\"}");
                        }
                        BigInteger blinded = new BigInteger(body.getString("blinded_message"), 16);
                        BigInteger sig = blinded.modPow(priv.getPrivateExponent(), n);
                        return new FakeMint.Reply(200, "{\"keyset_id\":\"" + keysetIdHex
                                + "\",\"blind_signature\":\"" + sig.toString(16) + "\"}");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                default:
                    return new FakeMint.Reply(404, "{\"error\":\"not-found\"}");
            }
        });

        // Run 1: start, persist BEFORE the pay screen would show, poll once (unpaid).
        CreditPurchase first = new CreditPurchase(mint.mint(), null);
        CreditPurchase.Session s1 = first.startByKeyset(keysetIdHex, "onchain");
        PendingPurchase record = CreditPurchase.toPending(s1);
        String persisted = record.toJson(); // what SyncSecrets would hold across the restart
        assertFalse(first.issueAndUnblind(s1));

        // Process death. Run 2: rebuild from the persisted record only.
        PendingPurchase restored = PendingPurchase.fromJson(persisted);
        CreditPurchase second = new CreditPurchase(mint.mint(), null);
        CreditPurchase.Session s2 = second.restore(restored);

        assertFalse("pending 425 → still waiting", second.issueAndUnblind(s2));
        assertTrue(s2.paymentPending());
        assertEquals("2026-07-04T12:00:00Z", s2.pendingExpiresAt());
        assertTrue("confirmed → issued", second.issueAndUnblind(s2));

        assertEquals(3, issuedMessages.size());
        assertEquals("the resumed run re-sent the ORIGINAL blinded message",
                issuedMessages.get(0), issuedMessages.get(1));
        assertEquals(issuedMessages.get(0), issuedMessages.get(2));
        assertEquals(restored.blindedHex, issuedMessages.get(2));

        // And the credit the second run unblinded is a valid signature over the
        // FIRST run's secret — the money the user paid for, not a fresh one.
        assertNotNull(s2.sig());
        assertTrue(keyset.verify(Hex.decode(restored.secretHex), s2.sig()));
        assertEquals(QUOTE_ID, Hex.encode(s2.quote.quoteId));
        assertEquals("bc1qx", s2.quote.address);
    }
}

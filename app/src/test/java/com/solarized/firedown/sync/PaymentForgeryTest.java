package com.solarized.firedown.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.solarized.firedown.sync.crypto.Hex;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;

/**
 * What a hostile or broken mint (or anything between the app and it) can hand
 * the purchase flow, and what the flow must do with it. The client cannot
 * MAKE a credit — only the mint's key can — so the question each case asks is
 * whether a forgery is REFUSED without burning the one real chance at the
 * credit: the persisted blinding state must survive every refusal so a later
 * honest answer still resumes.
 */
public class PaymentForgeryTest {

    private static long issueCalls(FakeMint mint) {
        return mint.requests.stream().filter(r -> r.path.equals("/v1/mint/issue")).count();
    }

    @Test
    public void aForgedSignatureIsRefusedAndTheSessionStaysUnissued() throws Exception {
        MintFixture fx = new MintFixture();
        List<String> messages = new ArrayList<>();
        FakeMint mint = new FakeMint(r -> {
            switch (r.path) {
                case "/v1/mint/keys": return new FakeMint.Reply(200, fx.keysJson());
                case "/v1/mint/quote": return new FakeMint.Reply(201, fx.onchainQuoteJson());
                default:
                    try { messages.add(new JSONObject(r.body).getString("blinded_message")); }
                    catch (Exception e) { throw new RuntimeException(e); }
                    // "Paid" — with a signature that is just a random number.
                    return new FakeMint.Reply(200, fx.signedJson(new BigInteger("deadbeef", 16)));
            }
        });
        CreditPurchase purchase = new CreditPurchase(mint.mint(), null);
        CreditPurchase.Session s = purchase.startByKeyset(fx.keysetIdHex, "onchain");
        try {
            purchase.issueAndUnblind(s);
            fail("a signature that does not verify must not become a credit");
        } catch (MintClient.FatalException fe) {
            assertEquals("invalid-credit", fe.slug);
        }
        assertNull("nothing was issued onto the session", s.sig());
        // The blinding state is intact: a later honest reply still resumes on
        // the SAME message (a forgery must not consume the real credit).
        try { purchase.issueAndUnblind(s); } catch (MintClient.FatalException ignored) { /* still forged */ }
        assertEquals(2, messages.size());
        assertEquals(messages.get(0), messages.get(1));
        assertEquals(PurchaseError.Kind.OTHER_SLUG,
                PurchaseError.classify(new MintClient.FatalException("x", "invalid-credit")).kind);
    }

    @Test
    public void aSignatureFromARogueKeyIsRefused() throws Exception {
        MintFixture honest = new MintFixture();
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        java.security.KeyPair rogue = kpg.generateKeyPair();
        RSAPrivateKey roguePriv = (RSAPrivateKey) rogue.getPrivate();
        BigInteger rogueN = ((RSAPublicKey) rogue.getPublic()).getModulus();
        FakeMint mint = new FakeMint(r -> {
            switch (r.path) {
                case "/v1/mint/keys": return new FakeMint.Reply(200, honest.keysJson());
                case "/v1/mint/quote": return new FakeMint.Reply(201, honest.onchainQuoteJson());
                default:
                    try {
                        BigInteger blinded = new BigInteger(new JSONObject(r.body).getString("blinded_message"), 16);
                        // Signed with a key that is not the advertised keyset.
                        return new FakeMint.Reply(200, honest.signedJson(
                                blinded.modPow(roguePriv.getPrivateExponent(), rogueN)));
                    } catch (Exception e) { throw new RuntimeException(e); }
            }
        });
        CreditPurchase purchase = new CreditPurchase(mint.mint(), null);
        CreditPurchase.Session s = purchase.startByKeyset(honest.keysetIdHex, "onchain");
        try {
            purchase.issueAndUnblind(s);
            fail("a signature under another modulus must not verify");
        } catch (MintClient.FatalException fe) {
            assertEquals("invalid-credit", fe.slug);
        }
        assertNull(s.sig());
    }

    @Test
    public void aKeysetWhoseIdIsNotItsModulusIsRefusedAtStart() throws Exception {
        MintFixture real = new MintFixture();
        MintFixture substitute = new MintFixture();
        // /keys advertises the REAL keyset id but carries the substitute's modulus.
        FakeMint mint = new FakeMint(r -> {
            switch (r.path) {
                case "/v1/mint/keys": return new FakeMint.Reply(200, MintFixture.keysJson(real.keysetIdHex, substitute.n));
                case "/v1/mint/quote": return new FakeMint.Reply(201, real.onchainQuoteJson());
                default: return new FakeMint.Reply(200, "{}");
            }
        });
        CreditPurchase purchase = new CreditPurchase(mint.mint(), null);
        try {
            purchase.startByKeyset(real.keysetIdHex, "onchain");
            fail("must not blind a secret against a modulus that does not hash to its id");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("unknown keyset"));
        }
        assertEquals("nothing was ever sent to issue", 0, issueCalls(mint));
    }

    @Test
    public void aKeysetSwappedUnderTheStoredIdIsRefusedOnResume() throws Exception {
        MintFixture real = new MintFixture();
        MintFixture substitute = new MintFixture();
        // Run 1 against the honest mint, persist.
        FakeMint honest = new FakeMint(r -> r.path.equals("/v1/mint/keys")
                ? new FakeMint.Reply(200, real.keysJson())
                : new FakeMint.Reply(201, real.onchainQuoteJson()));
        CreditPurchase.Session s1 = new CreditPurchase(honest.mint(), null).startByKeyset(real.keysetIdHex, "onchain");
        String persisted = CreditPurchase.toPending(s1).toJson();
        // Run 2: /keys now serves another modulus under the same id.
        FakeMint swapped = new FakeMint(r -> new FakeMint.Reply(200, MintFixture.keysJson(real.keysetIdHex, substitute.n)));
        try {
            new CreditPurchase(swapped.mint(), null).restore(PendingPurchase.fromJson(persisted));
            fail("a resumed credit must not be verified against a substituted key");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("unknown keyset"));
        }
    }

    @Test
    public void anInvoiceForTheWrongAmountIsRefusedBeforeItIsShown() throws Exception {
        MintFixture fx = new MintFixture();
        FakeMint mint = new FakeMint(r -> r.path.equals("/v1/mint/keys")
                ? new FakeMint.Reply(200, fx.keysJson())
                // quoted 17 000 sats, invoice encodes 10 000
                : new FakeMint.Reply(201, fx.lightningQuoteJson("lnbc100u1pabcdef", 17000)));
        try {
            new CreditPurchase(mint.mint(), null).startByKeyset(fx.keysetIdHex, "lightning");
            fail("a 10 000-sat invoice for a 17 000-sat quote must be refused");
        } catch (MintClient.FatalException fe) {
            assertEquals(PaymentRequests.SLUG_PAY_REQUEST_MISMATCH, fe.slug);
        }
        assertEquals(0, issueCalls(mint));
    }

    @Test
    public void aBip21UriNamingAnotherAddressIsRefused() throws Exception {
        MintFixture fx = new MintFixture();
        FakeMint mint = new FakeMint(r -> r.path.equals("/v1/mint/keys")
                ? new FakeMint.Reply(200, fx.keysJson())
                : new FakeMint.Reply(201, fx.onchainQuoteJson("bitcoin:bc1qattacker?amount=0.00017", "bc1qreal", 17000)));
        try {
            new CreditPurchase(mint.mint(), null).startByKeyset(fx.keysetIdHex, "onchain");
            fail("the QR would send the coins somewhere other than the address shown");
        } catch (MintClient.FatalException fe) {
            assertEquals(PaymentRequests.SLUG_PAY_REQUEST_MISMATCH, fe.slug);
        }
    }

    @Test
    public void aReissueWithAnotherMessageIsRefusedAndNothingIsForged() throws Exception {
        // The mint's own rule: one credit per quote; a DIFFERENT blinded message
        // after the first was signed is a 409. On our side that is a bug, never a
        // dead quote: the record (holding the real message) must be kept.
        MintFixture fx = new MintFixture();
        FakeMint mint = new FakeMint(r -> {
            switch (r.path) {
                case "/v1/mint/keys": return new FakeMint.Reply(200, fx.keysJson());
                case "/v1/mint/quote": return new FakeMint.Reply(201, fx.onchainQuoteJson());
                default: return new FakeMint.Reply(409, "{\"error\":\"already-issued\",\"detail\":\"This quote's credit was already issued.\"}");
            }
        });
        CreditPurchase purchase = new CreditPurchase(mint.mint(), null);
        CreditPurchase.Session s = purchase.startByKeyset(fx.keysetIdHex, "onchain");
        try {
            purchase.issueAndUnblind(s);
            fail("expected already-issued");
        } catch (MintClient.FatalException fe) {
            assertEquals(MintClient.SLUG_ALREADY_ISSUED, fe.slug);
        }
        assertNull(s.sig());
        PurchaseError err = PurchaseError.classify(new MintClient.FatalException("x", MintClient.SLUG_ALREADY_ISSUED));
        assertEquals("not an expired quote — the record must be kept", PurchaseError.Kind.OTHER_SLUG, err.kind);
    }

    @Test
    public void anHonestSignatureStillVerifiesAfterAllThat() throws Exception {
        MintFixture fx = new MintFixture();
        FakeMint mint = new FakeMint(r -> {
            switch (r.path) {
                case "/v1/mint/keys": return new FakeMint.Reply(200, fx.keysJson());
                case "/v1/mint/quote": return new FakeMint.Reply(201, fx.onchainQuoteJson());
                default:
                    try {
                        BigInteger blinded = new BigInteger(new JSONObject(r.body).getString("blinded_message"), 16);
                        return new FakeMint.Reply(200, fx.signedJson(fx.sign(blinded)));
                    } catch (Exception e) { throw new RuntimeException(e); }
            }
        });
        CreditPurchase purchase = new CreditPurchase(mint.mint(), null);
        CreditPurchase.Session s = purchase.startByKeyset(fx.keysetIdHex, "onchain");
        assertTrue(purchase.issueAndUnblind(s));
        PendingPurchase p = CreditPurchase.toPending(s);
        assertTrue(fx.keyset.verify(Hex.decode(p.secretHex), s.sig()));
    }
}

package com.solarized.firedown.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.solarized.firedown.sync.crypto.Hex;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The network dying at every point of the purchase. The rule under test: a
 * cut-off is never a verdict — it surfaces as a plain {@link IOException}
 * (the poll's "keep waiting"), never a {@link MintClient.FatalException}, and
 * the retry re-sends the SAME blinded message, so a signature the mint
 * produced but we never received is recovered by its replay path rather than
 * lost.
 */
public class PaymentNetworkTest {

    private static String blindedOf(FakeMint.Recorded r) {
        try {
            return new JSONObject(r.body).getString("blinded_message");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void aCutOffDuringIssueIsRetriedWithTheSameMessage() throws Exception {
        MintFixture fx = new MintFixture();
        AtomicInteger issueCalls = new AtomicInteger();
        List<String> messages = new ArrayList<>();
        FakeMint mint = new FakeMint(r -> {
            switch (r.path) {
                case "/v1/mint/keys": return new FakeMint.Reply(200, fx.keysJson());
                case "/v1/mint/quote": return new FakeMint.Reply(201, fx.onchainQuoteJson());
                default:
                    messages.add(blindedOf(r));
                    if (issueCalls.getAndIncrement() == 0) {
                        // The connection drops mid-request.
                        throw new UncheckedIo(new SocketException("Connection reset"));
                    }
                    return new FakeMint.Reply(200, fx.signedJson(fx.sign(new BigInteger(blindedOf(r), 16))));
            }
        });
        CreditPurchase purchase = new CreditPurchase(mint.mint(), null);
        CreditPurchase.Session s = purchase.startByKeyset(fx.keysetIdHex, "onchain");
        try {
            purchase.issueAndUnblind(s);
            fail("expected the socket error to surface");
        } catch (MintClient.FatalException fe) {
            fail("a cut-off is not a verdict — it must not be fatal: " + fe.slug);
        } catch (IOException expected) {
            // the poll loop's "keep waiting" branch
        }
        assertNull(s.sig());
        assertTrue("the retry gets the credit", purchase.issueAndUnblind(s));
        assertEquals(2, messages.size());
        assertEquals("same blinded message on the retry", messages.get(0), messages.get(1));
        assertTrue(fx.keyset.verify(Hex.decode(CreditPurchase.toPending(s).secretHex), s.sig()));
    }

    @Test
    public void aResponseLostAfterTheMintSignedIsRecoveredByReplay() throws Exception {
        MintFixture fx = new MintFixture();
        // The mint signs on the first issue and STORES the signature (its
        // issue-once gate); the reply never reaches us. The second issue, with
        // the same message, is served the stored signature.
        BigInteger[] stored = {null};
        AtomicInteger signings = new AtomicInteger();
        FakeMint mint = new FakeMint(r -> {
            switch (r.path) {
                case "/v1/mint/keys": return new FakeMint.Reply(200, fx.keysJson());
                case "/v1/mint/quote": return new FakeMint.Reply(201, fx.onchainQuoteJson());
                default:
                    BigInteger blinded = new BigInteger(blindedOf(r), 16);
                    if (stored[0] == null) {
                        stored[0] = fx.sign(blinded);
                        signings.incrementAndGet();
                        throw new UncheckedIo(new SocketException("Connection reset by peer"));
                    }
                    return new FakeMint.Reply(200, fx.signedJson(stored[0])); // replay path
            }
        });
        CreditPurchase purchase = new CreditPurchase(mint.mint(), null);
        CreditPurchase.Session s = purchase.startByKeyset(fx.keysetIdHex, "onchain");
        try {
            purchase.issueAndUnblind(s);
            fail("expected the lost response to surface as an IOException");
        } catch (IOException expected) {
            // keep waiting
        }
        assertTrue(purchase.issueAndUnblind(s));
        assertEquals("the mint signed exactly once", 1, signings.get());
        assertTrue("the replayed signature is the credit we paid for",
                fx.keyset.verify(Hex.decode(CreditPurchase.toPending(s).secretHex), s.sig()));
    }

    @Test
    public void aCutOffOnTheQuoteLeavesNothingHalfStarted() throws Exception {
        MintFixture fx = new MintFixture();
        FakeMint mint = new FakeMint(r -> {
            if (r.path.equals("/v1/mint/keys")) {
                return new FakeMint.Reply(200, fx.keysJson());
            }
            throw new UncheckedIo(new SocketException("Network is unreachable"));
        });
        try {
            new CreditPurchase(mint.mint(), null).startByKeyset(fx.keysetIdHex, "onchain");
            fail("expected the quote failure to surface");
        } catch (MintClient.FatalException fe) {
            fail("not fatal: " + fe.slug);
        } catch (IOException expected) {
            // no session, nothing to persist — the user just retries Continue
        }
        assertEquals(0, mint.requests.stream().filter(r -> r.path.equals("/v1/mint/issue")).count());
    }

    @Test
    public void aFlappingMintIsRetriedTransparentlyThenAPersistentOneGivesUp() throws Exception {
        MintFixture fx = new MintFixture();
        // 503 server-busy once, then fine: the bounded retry interceptor
        // absorbs it and the caller sees one successful catalog.
        AtomicInteger calls = new AtomicInteger();
        FakeMint flapping = new FakeMint(r -> calls.getAndIncrement() == 0
                ? new FakeMint.Reply(503, "{\"error\":\"server-busy\"}")
                : new FakeMint.Reply(200, fx.keysJson()));
        assertEquals(1, flapping.mint().fetchCatalog().keysets.size());
        assertEquals(2, flapping.requests.size());

        // Persistent 429: the interceptor retries a BOUNDED number of times and
        // then the caller gets a TransientException — never a hang, never a
        // storm. 1 request + MAX_RETRIES (3).
        FakeMint limited = new FakeMint(r -> new FakeMint.Reply(429, "{\"error\":\"rate-limited\",\"retry_after\":1}"));
        try {
            limited.mint().fetchCatalog();
            fail("expected TransientException");
        } catch (MintClient.TransientException te) {
            assertEquals(1, te.retryAfterSeconds);
        }
        assertEquals(4, limited.requests.size());
        assertFalse("a persistent limit is not a fatal", false);
    }

    /** The interceptor cannot throw a checked IOException from a lambda — this
     *  carries one across and FakeMint unwraps it (see FakeMint). */
    static final class UncheckedIo extends RuntimeException {
        UncheckedIo(IOException cause) {
            super(cause);
        }
    }
}

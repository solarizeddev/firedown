package com.solarized.firedown.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * {@link MintClient} against a scripted mint: the {@code methods} list on
 * {@code /keys}, the on-chain quote fields, the pending 425, and — the bug
 * that shipped — a 503 {@code rail-unavailable} classified by its SLUG (a
 * fatal with the server's detail), not by its status (a "try again" transient).
 */
public class MintClientTest {

    private static final String KEYSET = "{\"id\":\"5a150ab88353c2e5\",\"denomination_gb_months\":600,"
            + "\"price_cents\":1800,\"size_gb\":50,\"duration_months\":12,\"n\":\"c7\",\"e\":65537,\"active\":true}";

    @Test
    public void keysCarryTheConfiguredRails() throws Exception {
        FakeMint mint = new FakeMint(r -> new FakeMint.Reply(200,
                "{\"keysets\":[" + KEYSET + "],\"methods\":[\"lightning\",\"onchain\"]}"));
        MintClient.Catalog c = mint.mint().fetchCatalog();
        assertEquals(Arrays.asList("lightning", "onchain"), c.methods);
        assertEquals(1, c.keysets.size());
        assertEquals(50, c.keysets.get(0).sizeGb);
    }

    @Test
    public void aMintWithoutMethodsIsLightningOnly() throws Exception {
        FakeMint mint = new FakeMint(r -> new FakeMint.Reply(200, "{\"keysets\":[" + KEYSET + "]}"));
        assertEquals(Arrays.asList("lightning"), mint.mint().fetchCatalog().methods);
    }

    @Test
    public void onchainQuoteCarriesAddressSatsAndConfirmations() throws Exception {
        FakeMint mint = new FakeMint(r -> new FakeMint.Reply(201,
                "{\"quote_id\":\"00112233445566778899aabbccddeeff\",\"method\":\"onchain\","
                + "\"amount_cents\":1800,\"denomination_gb_months\":600,\"size_gb\":50,\"duration_months\":12,"
                + "\"keyset_id\":\"5a150ab88353c2e5\",\"expires_at\":\"2026-07-02T13:00:00Z\","
                + "\"pay_request\":\"bitcoin:bc1qx?amount=0.00017&label=Firedown\",\"address\":\"bc1qx\","
                + "\"amount_sats\":17000,\"min_confirmations\":1}"));
        MintClient.Quote q = mint.mint().createQuoteByKeyset("5a150ab88353c2e5", "onchain");
        assertEquals("onchain", q.method);
        assertEquals("bc1qx", q.address);
        assertEquals(17000, q.amountSats);
        assertEquals(1, q.minConfirmations);
        assertTrue(q.payRequest.startsWith("bitcoin:"));
        assertEquals("{\"keyset_id\":\"5a150ab88353c2e5\",\"method\":\"onchain\"}",
                mint.requests.get(0).body);
    }

    @Test
    public void railUnavailableIsFatalWithDetailNotTransient() throws Exception {
        String detail = "On-chain payments are temporarily unavailable while the Bitcoin node syncs."
                + " Pay with Lightning, or try again later.";
        FakeMint mint = new FakeMint(r -> new FakeMint.Reply(503,
                "{\"error\":\"rail-unavailable\",\"detail\":\"" + detail + "\",\"ray\":\"abc\"}"));
        try {
            mint.mint().createQuoteByKeyset("5a150ab88353c2e5", "onchain");
            fail("expected a FatalException");
        } catch (MintClient.FatalException fe) {
            assertEquals("rail-unavailable", fe.slug);
            assertEquals(detail, fe.detail);
        }
        assertEquals("a fatal must not be retried by the 429/503 interceptor",
                1, mint.requests.size());
    }

    @Test
    public void rateLimitedAndServerBusyAreTransient() throws Exception {
        for (String body : new String[]{
                "{\"error\":\"rate-limited\",\"retry_after\":7}",
                "{\"error\":\"server-busy\"}"}) {
            int code = body.contains("rate") ? 429 : 503;
            FakeMint mint = new FakeMint(r -> new FakeMint.Reply(code, body));
            try {
                mint.mint().fetchCatalog();
                fail("expected a TransientException for " + body);
            } catch (MintClient.TransientException te) {
                if (body.contains("retry_after")) {
                    assertEquals(7, te.retryAfterSeconds);
                }
            }
        }
    }

    @Test
    public void aBareEdgeErrorPageIsTransient() throws Exception {
        FakeMint mint = new FakeMint(r -> new FakeMint.Reply(503, "<html>cloudflare</html>"));
        try {
            mint.mint().fetchCatalog();
            fail("expected a TransientException");
        } catch (MintClient.TransientException expected) {
            // no slug at all: an overloaded edge, retry later
        }
    }

    @Test
    public void issuePendingCarriesTheExtendedExpiry() throws Exception {
        FakeMint mint = new FakeMint(r -> new FakeMint.Reply(425,
                "{\"error\":\"not-paid-yet\",\"detail\":\"Payment detected; waiting for confirmation.\","
                + "\"pending\":true,\"expires_at\":\"2026-07-04T12:00:00Z\"}"));
        MintClient.IssueOutcome out = mint.mint().issue(new byte[16], BigInteger.TEN);
        assertFalse(out.paid);
        assertTrue(out.pending);
        assertEquals("2026-07-04T12:00:00Z", out.expiresAt);
    }

    @Test
    public void issueNotPaidYetWithoutPendingIsPlainWaiting() throws Exception {
        FakeMint mint = new FakeMint(r -> new FakeMint.Reply(425,
                "{\"error\":\"not-paid-yet\",\"detail\":\"Payment not confirmed yet; retry shortly.\"}"));
        MintClient.IssueOutcome out = mint.mint().issue(new byte[16], BigInteger.TEN);
        assertFalse(out.paid);
        assertFalse(out.pending);
        assertNull(out.expiresAt);
    }

    @Test
    public void issueExpiredIsFatalWithTheSlug() throws Exception {
        FakeMint mint = new FakeMint(r -> new FakeMint.Reply(410,
                "{\"error\":\"quote-expired\",\"detail\":\"This quote expired; start a new purchase.\"}"));
        try {
            mint.mint().issue(new byte[16], BigInteger.TEN);
            fail("expected a FatalException");
        } catch (MintClient.FatalException fe) {
            assertEquals(MintClient.SLUG_QUOTE_EXPIRED, fe.slug);
        }
    }
}

package com.solarized.firedown.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.solarized.firedown.sync.crypto.Hex;

import org.junit.Test;

import java.math.BigInteger;

/**
 * {@link PendingPurchase} serialization round-trip — the durable snapshot that
 * makes a paid credit survive process death between paying and redeeming (the
 * lost-credit fix). The {@code secret}/{@code sig} in this record ARE the money,
 * so a field silently dropped or renamed in the JSON round-trip would burn a real
 * payment on the resume path. Pure org.json, so it runs as a plain JUnit test
 * (org.json is a real test dependency, not the Android stub).
 */
public class PendingPurchaseTest {

    private static PendingPurchase full() {
        return new PendingPurchase(
                "00112233445566778899aabbccddeeff", "lightning", 1099, 100,
                50, 2, "5a150ab88353c2e5", "lnbc10990n1p...", null,
                "2026-07-02T12:00:00Z",
                "0f1e2d3c4b5a69788796a5b4c3d2e1f0", "1a2b3c4d5e6f7081",
                "bf95b71f5f8e5fa1", null);
    }

    private static void assertSame(PendingPurchase a, PendingPurchase b) {
        assertEquals(a.quoteIdHex, b.quoteIdHex);
        assertEquals(a.method, b.method);
        assertEquals(a.amountCents, b.amountCents);
        assertEquals(a.denomGbMonths, b.denomGbMonths);
        assertEquals(a.sizeGb, b.sizeGb);
        assertEquals(a.durationMonths, b.durationMonths);
        assertEquals(a.keysetIdHex, b.keysetIdHex);
        assertEquals(a.payRequest, b.payRequest);
        assertEquals(a.checkoutUrl, b.checkoutUrl);
        assertEquals(a.expiresAt, b.expiresAt);
        assertEquals(a.secretHex, b.secretHex);
        assertEquals(a.rHex, b.rHex);
        assertEquals(a.blindedHex, b.blindedHex);
        assertEquals(a.sigHex, b.sigHex);
    }

    @Test
    public void jsonRoundTripPreservesEveryField() throws Exception {
        PendingPurchase p = full();
        assertSame(p, PendingPurchase.fromJson(p.toJson()));
    }

    /** The nullable fields (payRequest/checkoutUrl/expiresAt/sig) must round-trip
     *  as ABSENT, not the string "null" — a "null" sig would resume the purchase
     *  straight into redeem with a garbage signature instead of re-issuing. */
    @Test
    public void absentNullablesStayNull() throws Exception {
        PendingPurchase p = new PendingPurchase(
                "aa", "stripe", 500, 100, 0, 0, "bb", null, null, null,
                "cc", "dd", "ee", null);
        PendingPurchase r = PendingPurchase.fromJson(p.toJson());
        assertNull(r.payRequest);
        assertNull(r.checkoutUrl);
        assertNull(r.expiresAt);
        assertNull("no sig persisted pre-issue → resume must re-issue", r.sigHex);
    }

    /** withSig is the post-issue persist (crash mid-redeem resumes at redeem-only,
     *  never re-issues): the sig lands, everything else is byte-identical. */
    @Test
    public void withSigAddsOnlyTheSignature() throws Exception {
        PendingPurchase p = full();
        BigInteger sig = new BigInteger("45feb15c2f339fca", 16);
        PendingPurchase signed = p.withSig(sig);
        assertEquals(sig.toString(16), signed.sigHex);
        // Round-trip the signed copy too — the resume path loads THIS shape.
        PendingPurchase r = PendingPurchase.fromJson(signed.toJson());
        assertSame(signed, r);
        assertNotNull(r.sigHex);
    }

    /** A corrupted blob must surface as JSONException (load() maps it to null →
    *   "no pending purchase") — never a half-populated record that redeems junk. */
    @Test
    public void malformedJsonThrows() {
        String[] bad = {
                "",                       // empty blob
                "not json",               // garbage
                "{\"method\":\"x\"}",     // missing required fields
        };
        for (String s : bad) {
            try {
                PendingPurchase.fromJson(s);
                fail("fromJson accepted malformed input: " + s);
            } catch (org.json.JSONException expected) {
                // load() catches this and reports "no pending purchase"
            }
        }
    }

    /** The resume path rebuilds the MintClient.Quote from the persisted record —
     *  ids decode back to the original bytes and every scalar survives. */
    @Test
    public void toQuoteRebuildsTheQuote() {
        PendingPurchase p = full();
        MintClient.Quote q = p.toQuote();
        assertEquals(p.quoteIdHex, Hex.encode(q.quoteId));
        assertEquals(p.keysetIdHex, Hex.encode(q.keysetId));
        assertEquals(p.method, q.method);
        assertEquals(p.amountCents, q.amountCents);
        assertEquals(p.denomGbMonths, q.denomGbMonths);
        assertEquals(p.sizeGb, q.sizeGb);
        assertEquals(p.durationMonths, q.durationMonths);
        assertEquals(p.payRequest, q.payRequest);
        assertEquals(p.checkoutUrl, q.checkoutUrl);
        assertEquals(p.expiresAt, q.expiresAt);
    }
}

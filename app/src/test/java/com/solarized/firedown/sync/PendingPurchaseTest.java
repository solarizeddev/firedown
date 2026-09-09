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
                "00112233445566778899aabbccddeeff", "onchain", 1099, 100,
                50, 2, "5a150ab88353c2e5", "bitcoin:bc1qexample?amount=0.00017",
                "bc1qexample", 17000, 1,
                "2026-07-02T12:00:00Z",
                "0f1e2d3c4b5a69788796a5b4c3d2e1f0", "1a2b3c4d5e6f7081",
                "bf95b71f5f8e5fa1", null, false);
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
        assertEquals(a.address, b.address);
        assertEquals(a.amountSats, b.amountSats);
        assertEquals(a.minConfirmations, b.minConfirmations);
        assertEquals(a.expiresAt, b.expiresAt);
        assertEquals(a.secretHex, b.secretHex);
        assertEquals(a.rHex, b.rHex);
        assertEquals(a.blindedHex, b.blindedHex);
        assertEquals(a.sigHex, b.sigHex);
        assertEquals(a.submitted, b.submitted);
        assertEquals(a.detectedNotified, b.detectedNotified);
    }

    /** The once-only "payment detected" marker round-trips, survives every
     *  other with*() copy, and reads false from a legacy record. */
    @Test
    public void withDetectedNotifiedRoundTripsAndSurvivesOtherCopies() throws Exception {
        PendingPurchase p = full();
        assertEquals(false, p.detectedNotified);
        PendingPurchase told = p.withDetectedNotified();
        assertEquals(true, told.detectedNotified);
        assertSame(told, PendingPurchase.fromJson(told.toJson()));
        assertEquals(true, told.withSubmitted().detectedNotified);
        assertEquals(true, told.withExpiresAt("2026-07-04T12:00:00Z").detectedNotified);
        assertEquals(true, told.withSig(new BigInteger("45feb15c2f339fca", 16)).detectedNotified);
        assertEquals(false, PendingPurchase.fromJson(p.toJson()).detectedNotified);
    }

    @Test
    public void jsonRoundTripPreservesEveryField() throws Exception {
        PendingPurchase p = full();
        assertSame(p, PendingPurchase.fromJson(p.toJson()));
    }

    /** The nullable fields (payRequest/address/expiresAt/sig) must round-trip
     *  as ABSENT, not the string "null" — a "null" sig would resume the purchase
     *  straight into redeem with a garbage signature instead of re-issuing. */
    @Test
    public void absentNullablesStayNull() throws Exception {
        PendingPurchase p = new PendingPurchase(
                "aa", "lightning", 500, 100, 0, 0, "bb", null, null, 0, 0, null,
                "cc", "dd", "ee", null, false);
        PendingPurchase r = PendingPurchase.fromJson(p.toJson());
        assertNull(r.payRequest);
        assertNull(r.address);
        assertEquals(0, r.amountSats);
        assertEquals(0, r.minConfirmations);
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

    /** withSubmitted marks money-in-flight (the Checkout success redirect / a
     *  wallet-paid invoice) — the flag that stops the leave-the-wizard cleanup
     *  from dropping a record whose charge may still settle. It must round-trip,
     *  survive withSig, and change nothing else. A LEGACY record (no "submitted"
     *  key) reads back false — old records stay eligible for cleanup. */
    @Test
    public void withSubmittedRoundTripsAndSurvivesWithSig() throws Exception {
        PendingPurchase p = full();
        assertEquals(false, p.submitted);
        PendingPurchase submitted = p.withSubmitted();
        assertEquals(true, submitted.submitted);
        assertEquals(p.sigHex, submitted.sigHex);
        assertSame(submitted, PendingPurchase.fromJson(submitted.toJson()));
        // The poll's post-issue persist must not shed the marker.
        PendingPurchase signed = submitted.withSig(new BigInteger("45feb15c2f339fca", 16));
        assertEquals(true, signed.submitted);
        // Legacy JSON without the key → false.
        assertEquals(false, PendingPurchase.fromJson(p.toJson()).submitted);
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
        assertEquals(p.address, q.address);
        assertEquals(p.amountSats, q.amountSats);
        assertEquals(p.minConfirmations, q.minConfirmations);
        assertEquals(p.expiresAt, q.expiresAt);
    }

    /** The on-chain rail pushes a quote's expiry out once it has seen the
     *  payment; the record must carry the LATER deadline through a restart so
     *  the resumed wait honours it, and nothing else may move. */
    @Test
    public void withExpiresAtMovesOnlyTheDeadline() throws Exception {
        PendingPurchase p = full().withSubmitted();
        PendingPurchase later = p.withExpiresAt("2026-07-04T12:00:00Z");
        assertEquals("2026-07-04T12:00:00Z", later.expiresAt);
        assertEquals(p.quoteIdHex, later.quoteIdHex);
        assertEquals(p.blindedHex, later.blindedHex);
        assertEquals(p.rHex, later.rHex);
        assertEquals(p.secretHex, later.secretHex);
        assertEquals("submitted survives", true, later.submitted);
        PendingPurchase r = PendingPurchase.fromJson(later.toJson());
        assertEquals("2026-07-04T12:00:00Z", r.expiresAt);
        assertEquals(true, r.submitted);
    }
}

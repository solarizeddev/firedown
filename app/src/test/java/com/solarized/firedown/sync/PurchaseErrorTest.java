package com.solarized.firedown.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.IOException;

/**
 * The slug → message mapping the buy screen switches on. Pins the bug that
 * motivated it: {@code rail-unavailable} rides a 503 and used to land in the
 * "too many requests" bucket, so the user read a rate-limit message while the
 * server's own explanation (the node is syncing, pay with Lightning) was never
 * shown. The mapping must key on the SLUG, never the status.
 */
public class PurchaseErrorTest {

    private static final String SYNCING = "On-chain payments are temporarily unavailable"
            + " while the Bitcoin node syncs. Pay with Lightning, or try again later.";

    @Test
    public void railUnavailableShowsTheServersDetailNeverTheRateLimitCopy() {
        PurchaseError e = PurchaseError.classify(
                new MintClient.FatalException("quote: 503 rail-unavailable", "rail-unavailable", SYNCING));
        assertEquals(PurchaseError.Kind.RAIL_UNAVAILABLE, e.kind);
        assertEquals(SYNCING, e.detail);
    }

    @Test
    public void railUnavailableWithoutDetailStillIsNotBusy() {
        PurchaseError e = PurchaseError.classify(
                new MintClient.FatalException("quote: 503 rail-unavailable", "rail-unavailable"));
        assertEquals(PurchaseError.Kind.RAIL_UNAVAILABLE, e.kind);
        assertNull(e.detail);
    }

    @Test
    public void onlyRateLimitedAndServerBusyAreBusy() {
        assertEquals(PurchaseError.Kind.BUSY,
                PurchaseError.classify(new MintClient.TransientException("429 rate-limited", 30)).kind);
        assertEquals(PurchaseError.Kind.BUSY, PurchaseError.fromSlug("rate-limited", null).kind);
        assertEquals(PurchaseError.Kind.BUSY, PurchaseError.fromSlug("server-busy", null).kind);
        assertEquals(PurchaseError.Kind.RAIL_UNAVAILABLE, PurchaseError.fromSlug("rail-unavailable", "x").kind);
    }

    @Test
    public void expiredQuoteIsItsOwnKind() {
        PurchaseError e = PurchaseError.classify(
                new MintClient.FatalException("issue: 410 quote-expired", "quote-expired"));
        assertEquals(PurchaseError.Kind.QUOTE_EXPIRED, e.kind);
    }

    @Test
    public void unknownSlugIsReportedAsItself() {
        PurchaseError e = PurchaseError.classify(
                new MintClient.FatalException("issue: 409 already-issued", "already-issued"));
        assertEquals(PurchaseError.Kind.OTHER_SLUG, e.kind);
        assertEquals("already-issued", e.slug);
    }

    @Test
    public void bareIoExceptionIsNetwork() {
        assertEquals(PurchaseError.Kind.NETWORK, PurchaseError.classify(new IOException("reset")).kind);
    }
}

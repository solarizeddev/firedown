package com.solarized.firedown.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Two settlers can reach the same paid credit at once — the buy wizard's poll
 * and the background {@link CreditSettleWorker} both redeem it, and storage's
 * redeem is idempotent — but the client-side BOOKING is not: the plan merge
 * accumulates, so booking twice double-counts the credit on the hero. This
 * hammers {@link CreditSettlement#commitRedeemed} from many threads and
 * insists on exactly one booking per record.
 */
public class CreditSettlementConcurrencyTest {

    /** An in-memory {@link CreditSettlement.Books} that counts bookings. */
    private static final class MemoryBooks implements CreditSettlement.Books {
        final AtomicReference<PendingPurchase> stored = new AtomicReference<>();
        final AtomicInteger booked = new AtomicInteger();
        final AtomicInteger loads = new AtomicInteger();

        MemoryBooks(PendingPurchase initial) {
            stored.set(initial);
        }

        @Override
        public PendingPurchase load() {
            loads.incrementAndGet();
            // Deliberately slow, so an unlocked implementation WOULD interleave.
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return stored.get();
        }

        @Override
        public void clear() {
            stored.set(null);
        }

        @Override
        public void book(int sizeGb, int durationMonths) {
            booked.incrementAndGet();
        }
    }

    private static PendingPurchase record(String quoteIdHex) {
        return new PendingPurchase(quoteIdHex, "onchain", 1800, 600, 50, 12, "5a150ab88353c2e5",
                "bitcoin:bc1qx?amount=0.00017", "bc1qx", 17000, 1, "2026-07-02T13:00:00Z",
                "0f1e2d3c", "1a2b3c4d", "bf95b71f", "45feb15c", true);
    }

    @Test
    public void manyConcurrentSettlersBookOneCreditExactlyOnce() throws Exception {
        final String quote = "00112233445566778899aabbccddeeff";
        MemoryBooks books = new MemoryBooks(record(quote));
        int threads = 32;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger wins = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    if (CreditSettlement.commitRedeemed(books, quote, 50, 12)) {
                        wins.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "settler-" + i);
            t.start();
            workers.add(t);
        }
        go.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        for (Thread t : workers) {
            t.join();
        }
        assertEquals("exactly one settler books", 1, wins.get());
        assertEquals("the bookkeeping ran once", 1, books.booked.get());
        assertNull("the record is gone", books.stored.get());
        assertEquals("every settler did look", threads, books.loads.get());
    }

    @Test
    public void aRecordForAnotherQuoteIsNeverBooked() {
        MemoryBooks books = new MemoryBooks(record("00112233445566778899aabbccddeeff"));
        assertFalse(CreditSettlement.commitRedeemed(books, "ffffffffffffffffffffffffffffffff", 50, 12));
        assertEquals(0, books.booked.get());
        assertTrue("the stranger's record is left alone", books.stored.get() != null);
    }

    @Test
    public void aLaterPurchaseBooksIndependently() {
        MemoryBooks books = new MemoryBooks(record("aa112233445566778899aabbccddeeff"));
        assertTrue(CreditSettlement.commitRedeemed(books, "aa112233445566778899aabbccddeeff", 50, 12));
        assertFalse("a second commit of the same credit finds nothing", 
                CreditSettlement.commitRedeemed(books, "aa112233445566778899aabbccddeeff", 50, 12));
        books.stored.set(record("bb112233445566778899aabbccddeeff"));
        assertTrue(CreditSettlement.commitRedeemed(books, "bb112233445566778899aabbccddeeff", 20, 3));
        assertEquals(2, books.booked.get());
    }
}

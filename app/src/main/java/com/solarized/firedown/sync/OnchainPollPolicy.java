package com.solarized.firedown.sync;

import androidx.annotation.Nullable;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * The on-chain rail's wait: how often to ask the mint, and until when.
 *
 * <p>Two cadences. Until the mint has SEEN the payment the poll runs every
 * 3 s like Lightning — the user is watching the screen and a broadcast is
 * visible within seconds. Once a pending 425 arrives, nothing changes for
 * minutes (a confirmation is a block), so the poll drops to 30 s; the mint
 * throttles its own rail call per quote anyway, so a faster one buys nothing.
 *
 * <p>One deadline: the LATEST {@code expires_at} the mint has reported. A
 * quote starts with an hour; every pending sighting pushes that out (48 h on
 * the server), and the reply carries the new value. The wait honours the
 * newest one it has seen — never the original hour, which a slow confirmation
 * legitimately outlives. Past the deadline the caller asks the mint once more
 * and lets the server's answer (410, or a further extension) decide; the local
 * clock is a stopping rule for the loop, not the truth about the quote.
 *
 * <p>Pure (no Android, no clock of its own) so the cadence and deadline rules
 * are unit-tested.
 */
public final class OnchainPollPolicy {

    public static final long FAST_DELAY_MS = 3_000L;
    public static final long SLOW_DELAY_MS = 30_000L;

    /**
     * How long past an on-chain quote's (latest known) expiry the client keeps
     * its record and keeps asking the mint. A {@code quote-expired} answer on
     * this rail is NOT proof that nothing was paid: an address the user has
     * seen can be paid from any wallet at any time, the mint polls its node
     * BEFORE the expiry test on every issue call, and its sweep keeps an
     * expired row for 30 days (and forever once the address holds receipts).
     * So a late broadcast still settles — but only if a client is still
     * asking. Dropping the record on the first 410 was the money-loss: the
     * blinding secret went with it. Mirrors the mint's on-chain GC grace.
     */
    public static final long LATE_PAYMENT_WINDOW_MS = 30L * 24 * 60 * 60 * 1000;

    private boolean pendingSeen;
    private long deadlineMs; // epoch ms; 0 = no expiry known

    /** @param initialExpiresAt the quote's expiry as first quoted (RFC3339), or null. */
    public OnchainPollPolicy(@Nullable String initialExpiresAt) {
        this.deadlineMs = parse(initialExpiresAt);
    }

    /** Feeds one issue poll's answer: whether the payment was seen, and the
     *  expiry the reply carried (null when it carried none). The deadline only
     *  ever moves LATER — a stale poll can't shorten a wait an earlier one
     *  extended. */
    public void observe(boolean pending, @Nullable String expiresAt) {
        if (pending) {
            pendingSeen = true;
        }
        long reported = parse(expiresAt);
        if (reported > deadlineMs) {
            deadlineMs = reported;
        }
    }

    /** True once the mint has reported the payment as seen. */
    public boolean pendingSeen() {
        return pendingSeen;
    }

    /** The delay before the next poll. */
    public long nextDelayMs() {
        return pendingSeen ? SLOW_DELAY_MS : FAST_DELAY_MS;
    }

    /** The latest deadline known, as epoch ms (0 when none was ever parsed). */
    public long deadlineMs() {
        return deadlineMs;
    }

    /** True when {@code nowMs} is past the latest deadline. With no parseable
     *  expiry at all the loop never times out on its own — the server's 410
     *  ends it. */
    public boolean pastDeadline(long nowMs) {
        return deadlineMs > 0 && nowMs > deadlineMs;
    }

    /**
     * True once {@code nowMs} is more than {@link #LATE_PAYMENT_WINDOW_MS} past
     * {@code expiresAt} — the point at which an on-chain record whose quote the
     * mint reports expired may finally be dropped. An unparseable expiry keeps
     * the record (the conservative side: a kept record costs a poll, a dropped
     * one can cost the credit).
     */
    public static boolean beyondLateWindow(@Nullable String expiresAt, long nowMs) {
        long exp = parse(expiresAt);
        return exp > 0 && nowMs > exp + LATE_PAYMENT_WINDOW_MS;
    }

    private static long parse(@Nullable String rfc3339) {
        if (rfc3339 == null || rfc3339.isEmpty()) {
            return 0L;
        }
        try {
            return Instant.parse(rfc3339).toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0L;
        }
    }
}

package com.solarized.firedown.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;

/**
 * The on-chain wait's two rules: a pending 425 slows the poll (3 s → 30 s)
 * and EXTENDS the deadline to the latest {@code expires_at} the mint reported —
 * never back to the original hour, and never earlier than a value already seen.
 */
public class OnchainPollPolicyTest {

    private static final String T0 = "2026-07-02T12:00:00Z";
    private static final String T0_PLUS_1H = "2026-07-02T13:00:00Z";
    private static final String T0_PLUS_48H = "2026-07-04T12:00:00Z";

    private static long ms(String rfc3339) {
        return Instant.parse(rfc3339).toEpochMilli();
    }

    @Test
    public void pollsFastUntilThePaymentIsSeenThenSlow() {
        OnchainPollPolicy p = new OnchainPollPolicy(T0_PLUS_1H);
        assertEquals(OnchainPollPolicy.FAST_DELAY_MS, p.nextDelayMs());
        p.observe(false, null);
        assertEquals("a plain not-yet keeps the fast cadence",
                OnchainPollPolicy.FAST_DELAY_MS, p.nextDelayMs());
        p.observe(true, T0_PLUS_48H);
        assertEquals(OnchainPollPolicy.SLOW_DELAY_MS, p.nextDelayMs());
        p.observe(false, null);
        assertEquals("a later throttled reply without the marker must not speed it back up",
                OnchainPollPolicy.SLOW_DELAY_MS, p.nextDelayMs());
    }

    @Test
    public void pendingExtendsTheDeadlineAndItNeverMovesEarlier() {
        OnchainPollPolicy p = new OnchainPollPolicy(T0_PLUS_1H);
        assertEquals(ms(T0_PLUS_1H), p.deadlineMs());
        p.observe(true, T0_PLUS_48H);
        assertEquals(ms(T0_PLUS_48H), p.deadlineMs());
        p.observe(true, T0_PLUS_1H); // a stale/shorter value can't shorten the wait
        assertEquals(ms(T0_PLUS_48H), p.deadlineMs());
        p.observe(false, null);
        assertEquals(ms(T0_PLUS_48H), p.deadlineMs());
    }

    @Test
    public void deadlineIsTheLatestSeenNotTheOriginalHour() {
        OnchainPollPolicy p = new OnchainPollPolicy(T0_PLUS_1H);
        assertFalse(p.pastDeadline(ms(T0)));
        assertTrue("the original hour passed", p.pastDeadline(ms(T0_PLUS_1H) + 1));
        p.observe(true, T0_PLUS_48H);
        assertFalse("...but a sighting pushed it out", p.pastDeadline(ms(T0_PLUS_1H) + 1));
        assertTrue(p.pastDeadline(ms(T0_PLUS_48H) + 1));
    }

    @Test
    public void noParseableExpiryNeverTimesOutLocally() {
        OnchainPollPolicy p = new OnchainPollPolicy(null);
        assertFalse(p.pastDeadline(Long.MAX_VALUE));
        p.observe(true, "not-a-date");
        assertFalse(p.pastDeadline(Long.MAX_VALUE));
        assertTrue(p.pendingSeen());
    }
}

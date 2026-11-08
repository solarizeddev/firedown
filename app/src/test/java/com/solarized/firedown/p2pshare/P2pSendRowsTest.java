package com.solarized.firedown.p2pshare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.solarized.firedown.p2pshare.P2pSendRows.Kind;
import com.solarized.firedown.p2pshare.P2pSendRows.Phase;
import com.solarized.firedown.p2pshare.P2pSendRows.Row;
import com.solarized.firedown.p2pshare.P2pSendRows.Sub;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The Send screen's "Send to" list ({@link P2pSendRows}). The full set of
 * cases, with mutants, is {@code sh scripts/p2p-harness/run.sh}; these are
 * the ones a build runs on every {@code testDebugUnitTest}.
 */
public class P2pSendRowsTest {

    private static final long NOW = 1_000_000_000L;
    private static final long EXPIRES = NOW + 15 * 60_000L;

    @Test
    public void restWithNobodyNearbyHasNoSpinner() {
        List<Row> rows = P2pSendRows.build(Phase.READY, Collections.emptyList(), null,
                false, false, false, EXPIRES, NOW);
        assertEquals(2, rows.size());
        assertEquals(Kind.LINK, rows.get(0).kind);
        assertEquals(Kind.NEARBY, rows.get(1).kind);
        assertEquals(Sub.NEARBY_IDLE, rows.get(1).sub);
        for (Row row : rows) {
            assertFalse(row.toString(), row.spinner);
        }
        assertFalse(P2pSendRows.somethingOut(Phase.READY, null, false));
    }

    @Test
    public void askedPhoneWaitsAndOthersStayLive() {
        List<Row> rows = P2pSendRows.build(Phase.READY, Arrays.asList("A", "B"), "A",
                false, false, true, EXPIRES, NOW);
        List<Row> phones = rows.get(1).children;
        assertEquals(Sub.WAITING_ACCEPT, phones.get(0).sub);
        assertFalse(phones.get(0).tappable);
        assertTrue(phones.get(1).tappable);
        assertTrue(rows.get(0).tappable);
        rows = P2pSendRows.build(Phase.READY, Arrays.asList("A", "B"), "A",
                false, false, false, EXPIRES, NOW);
        assertEquals(Sub.NEARBY_WAITING, rows.get(1).sub);
        assertEquals("A", rows.get(1).name);
    }

    @Test
    public void sharedLinkCountsDown() {
        List<Row> rows = P2pSendRows.build(Phase.READY, Collections.emptyList(), null,
                true, false, false, NOW + 13 * 60_000L + 1_000L, NOW);
        assertEquals(Sub.LINK_SHARED, rows.get(0).sub);
        assertEquals(14, rows.get(0).minutesLeft);
        assertEquals(1, P2pSendRows.minutesLeft(NOW + 10_000L, NOW));
        assertTrue(P2pSendRows.somethingOut(Phase.READY, null, true));
    }

    @Test
    public void foundPhonesAreNamedWhileFolded() {
        List<Row> rows = P2pSendRows.build(Phase.READY, Arrays.asList("A", "B"), null,
                false, false, false, EXPIRES, NOW);
        assertEquals(Sub.NEARBY_FOUND, rows.get(1).sub);
        assertEquals(Arrays.asList("A", "B"), rows.get(1).names);
        assertTrue(rows.get(1).children.isEmpty());
    }

    @Test
    public void connectingCollapsesToThePeer() {
        List<Row> rows = P2pSendRows.build(Phase.CONNECTING, Arrays.asList("A", "B"), "B",
                true, false, true, EXPIRES, NOW);
        assertEquals(1, rows.size());
        assertEquals("B", rows.get(0).name);
        assertTrue(rows.get(0).spinner);
        rows = P2pSendRows.build(Phase.CONNECTING, Arrays.asList("A", "B"), null,
                true, false, true, EXPIRES, NOW);
        assertEquals(Kind.LINK, rows.get(0).kind);
    }

    @Test
    public void noListOutsideReadyAndConnecting() {
        assertTrue(P2pSendRows.build(Phase.NONE, Arrays.asList("A"), "A",
                true, false, true, EXPIRES, NOW).isEmpty());
    }
}

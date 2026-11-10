package com.solarized.firedown.sabr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.solarized.firedown.sabr.StreamTokenBinding.Kind;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link StreamTokenBinding}: which identifier a SABR download's PO token is
 * minted over, and when it switches. The second half plays whole downloads
 * against a model of the server as the device logs show it (a session
 * whose token is over the wrong identifier is served its first responses
 * "pending", then every such token is refused at once; a token over the
 * right identifier draws no status) with the downloader's own bookkeeping:
 * a consecutive count reset by progress and capped at two.
 */
public class StreamTokenBindingTest {

    @Before
    public void startFromVideo() {
        StreamTokenBinding.resetForTest(Kind.VIDEO);
    }

    // ── the decision, one demand at a time ──────────────────────────────

    @Test
    public void startsFromTheLastAcceptedBinding() {
        assertEquals(Kind.VIDEO, new StreamTokenBinding(true).current());
        StreamTokenBinding.resetForTest(Kind.VISITOR);
        assertEquals(Kind.VISITOR, new StreamTokenBinding(true).current());
    }

    @Test
    public void withoutVisitorDataItIsAlwaysTheVideo() {
        StreamTokenBinding.resetForTest(Kind.VISITOR);
        StreamTokenBinding b = new StreamTokenBinding(false);
        assertEquals(Kind.VIDEO, b.current());
        b.onMinted();
        assertEquals(Kind.VIDEO, b.onDemand(false, true, 1));
        assertEquals(Kind.VIDEO, b.onDemand(false, true, 2));
    }

    @Test
    public void aPendingTokenSwitchesAtTheFirstDemand() {
        StreamTokenBinding b = new StreamTokenBinding(true);
        b.onMinted();
        assertEquals(Kind.VISITOR, b.onDemand(false, true, 1));
        assertEquals(Kind.VISITOR, b.current());
    }

    @Test
    public void aDemandOnAnAcceptedTokenKeepsTheBinding() {
        StreamTokenBinding b = new StreamTokenBinding(true);
        b.onMinted();
        assertEquals(Kind.VIDEO, b.onDemand(true, false, 1));
        // Even when the server reports "pending" right before the demand.
        b.onMinted();
        assertEquals(Kind.VIDEO, b.onDemand(true, true, 1));
    }

    @Test
    public void aBindingAcceptedEarlierInTheDownloadStaysProven() {
        StreamTokenBinding b = new StreamTokenBinding(true);
        b.onMinted();
        assertEquals(Kind.VIDEO, b.onDemand(true, false, 1));
        b.onMinted();
        // The fresh token is pending and has moved nothing yet: still the
        // ordinary mid-stream demand, not a wrong binding.
        assertEquals(Kind.VIDEO, b.onDemand(false, true, 1));
    }

    @Test
    public void aSilentRefusalSwitchesOnlyTheSecondTimeInARow() {
        StreamTokenBinding b = new StreamTokenBinding(true);
        b.onMinted();
        assertEquals(Kind.VIDEO, b.onDemand(false, false, 1));
        b.onMinted();
        assertEquals(Kind.VISITOR, b.onDemand(false, false, 2));
    }

    @Test
    public void aProvenBindingRefusedTwiceInARowIsGivenUp() {
        StreamTokenBinding b = new StreamTokenBinding(true);
        b.onMinted();
        assertEquals(Kind.VIDEO, b.onDemand(true, false, 1));
        b.onMinted();
        assertEquals(Kind.VISITOR, b.onDemand(false, false, 2));
    }

    @Test
    public void aFailedFirstMintTriesTheSameBindingFirst() {
        StreamTokenBinding b = new StreamTokenBinding(true);
        // No onMinted: the download started with no token at all.
        assertEquals(Kind.VIDEO, b.onDemand(false, true, 1));
        assertEquals(Kind.VIDEO, b.onDemand(false, true, 2));
        b.onMinted();
        assertEquals(Kind.VISITOR, b.onDemand(false, true, 1));
    }

    @Test
    public void onlyAnAcceptedBindingIsRemembered() {
        StreamTokenBinding b = new StreamTokenBinding(true);
        b.onMinted();
        b.onDemand(false, true, 1);          // now VISITOR, not minted yet
        b.onEnded(false);
        assertEquals(Kind.VIDEO, StreamTokenBinding.lastAccepted());

        b.onMinted();
        b.onEnded(false);                    // minted, still not accepted
        assertEquals(Kind.VIDEO, StreamTokenBinding.lastAccepted());

        b.onEnded(true);
        assertEquals(Kind.VISITOR, StreamTokenBinding.lastAccepted());
    }

    @Test
    public void aProvenBindingIsRememberedEvenWhenTheDownloadFailsLater() {
        StreamTokenBinding.resetForTest(Kind.VISITOR);
        StreamTokenBinding b = new StreamTokenBinding(true);
        b.onMinted();
        b.onDemand(false, true, 1);          // VISITOR pending: now VIDEO
        b.onMinted();
        b.onDemand(true, false, 1);          // VIDEO accepted, then a demand
        b.onEnded(false);                    // the fresh token moved nothing
        assertEquals(Kind.VIDEO, StreamTokenBinding.lastAccepted());
    }

    @Test
    public void anAcceptedFlagWithNoTokenMintedIsNotRemembered() {
        StreamTokenBinding.resetForTest(Kind.VISITOR);
        StreamTokenBinding b = new StreamTokenBinding(false);   // VIDEO, never minted
        b.onEnded(true);
        assertEquals(Kind.VISITOR, StreamTokenBinding.lastAccepted());
    }

    // ── whole downloads against a model of the server ───────────────────

    /** What one simulated download did. */
    private static final class Run {
        boolean finished;
        final List<Kind> mints = new ArrayList<>();
        Kind endedOn;
    }

    private static final int RESPONSES = 40;        // media, in responses
    private static final int GRACE = 3;             // responses served before the first refusal
    private static final int MAX_CONSECUTIVE = 2;   // SabrDownloader.MAX_ATTESTATION_REFRESHES

    /**
     * @param wanted       the identifier the server checks; null = none is accepted
     * @param statusTwo    the server reports "pending" on a token it will refuse
     * @param firstMint    the mint before the download succeeds
     * @param demandsAt    positions where the server demands attestation on a
     *                     token it had accepted (the ordinary mid-stream demand)
     * @param pendingFirst those demands are preceded by a "pending" response
     */
    private static Run play(boolean hasVisitorData, Kind wanted, boolean statusTwo,
                            boolean firstMint, List<Integer> demandsAt, boolean pendingFirst) {
        StreamTokenBinding b = new StreamTokenBinding(hasVisitorData);
        Run run = new Run();
        Kind carried = null;
        if (firstMint) {
            carried = b.current();
            b.onMinted();
            run.mints.add(carried);
        }
        boolean accepted = false;
        boolean pending = false;
        int consecutive = 0;
        int position = 0;
        List<Integer> demands = new ArrayList<>(demandsAt);
        int guard = 0;
        while (position < RESPONSES && guard++ < 1000) {
            boolean right = carried != null && carried == wanted;
            boolean demand;
            boolean progressed = false;
            if (right) {
                if (!demands.isEmpty() && demands.get(0) == position) {
                    demands.remove(0);
                    if (pendingFirst) {
                        pending = true;
                    }
                    demand = true;
                } else {
                    demand = false;
                    progressed = true;
                }
            } else if (position < GRACE) {
                if (statusTwo) {
                    pending = true;
                }
                demand = false;
                progressed = true;
            } else {
                demand = true;
            }
            if (progressed) {
                position++;
                consecutive = 0;
                if (carried != null && !pending) {
                    accepted = true;
                }
            }
            if (!demand) {
                continue;
            }
            if (consecutive >= MAX_CONSECUTIVE) {
                b.onEnded(accepted);
                run.endedOn = b.current();
                return run;
            }
            consecutive++;
            carried = b.onDemand(accepted, pending, consecutive);
            b.onMinted();
            run.mints.add(carried);
            accepted = false;
            pending = false;
        }
        run.finished = position >= RESPONSES;
        b.onEnded(accepted);
        run.endedOn = b.current();
        return run;
    }

    private static List<Integer> none() {
        return new ArrayList<>();
    }

    private static List<Integer> at(int... positions) {
        List<Integer> list = new ArrayList<>();
        for (int p : positions) {
            list.add(p);
        }
        return list;
    }

    @Test
    public void theRightBindingNeverMintsAgain() {
        Run r = play(true, Kind.VIDEO, true, true, none(), false);
        assertTrue(r.finished);
        assertEquals(1, r.mints.size());
        assertEquals(Kind.VIDEO, StreamTokenBinding.lastAccepted());
    }

    @Test
    public void theWrongBindingIsCorrectedWithOneMintInBothDirections() {
        Run r = play(true, Kind.VISITOR, true, true, none(), false);
        assertTrue(r.finished);
        assertEquals(at2(Kind.VIDEO, Kind.VISITOR), r.mints);
        assertEquals(Kind.VISITOR, StreamTokenBinding.lastAccepted());

        // The next download starts on what was learned; a session that
        // wants the video again is corrected the other way.
        Run again = play(true, Kind.VISITOR, true, true, none(), false);
        assertEquals(1, again.mints.size());
        Run back = play(true, Kind.VIDEO, true, true, none(), false);
        assertTrue(back.finished);
        assertEquals(at2(Kind.VISITOR, Kind.VIDEO), back.mints);
        assertEquals(Kind.VIDEO, StreamTokenBinding.lastAccepted());
    }

    @Test
    public void aServerThatNeverSaysPendingIsStillCorrected() {
        Run r = play(true, Kind.VISITOR, false, true, none(), false);
        assertTrue(r.finished);
        // Same binding fresh first, the other one on the second refusal.
        assertEquals(Kind.VISITOR, r.mints.get(r.mints.size() - 1));
        assertTrue(r.mints.size() <= 4);
        assertEquals(Kind.VISITOR, StreamTokenBinding.lastAccepted());
    }

    @Test
    public void ordinaryDemandsNeverLeaveTheRightBinding() {
        for (boolean pendingFirst : new boolean[] {false, true}) {
            StreamTokenBinding.resetForTest(Kind.VIDEO);
            Run r = play(true, Kind.VIDEO, true, true, at(5, 12, 20, 31, 38), pendingFirst);
            assertTrue(r.finished);
            assertEquals(6, r.mints.size());
            for (Kind k : r.mints) {
                assertEquals(Kind.VIDEO, k);
            }
        }
    }

    @Test
    public void ordinaryDemandsAfterACorrectionStayOnTheCorrectedBinding() {
        Run r = play(true, Kind.VISITOR, true, true, at(10, 25), true);
        assertTrue(r.finished);
        assertEquals(Kind.VIDEO, r.mints.get(0));
        for (int i = 1; i < r.mints.size(); i++) {
            assertEquals(Kind.VISITOR, r.mints.get(i));
        }
        assertEquals(4, r.mints.size());
    }

    @Test
    public void aSessionThatAcceptsNothingFailsHavingTriedBoth() {
        StreamTokenBinding.resetForTest(Kind.VISITOR);
        Run r = play(true, null, true, true, none(), false);
        assertFalse(r.finished);
        assertTrue(r.mints.contains(Kind.VIDEO));
        assertTrue(r.mints.contains(Kind.VISITOR));
        assertEquals(Kind.VISITOR, StreamTokenBinding.lastAccepted());
    }

    @Test
    public void withoutVisitorDataAVisitorSessionFailsWithoutEverMintingVisitor() {
        Run r = play(false, Kind.VISITOR, true, true, none(), false);
        assertFalse(r.finished);
        assertFalse(r.mints.contains(Kind.VISITOR));
    }

    @Test
    public void aFailedFirstMintRecoversOnEitherSession() {
        Run same = play(true, Kind.VIDEO, true, false, none(), false);
        assertTrue(same.finished);
        assertEquals(at2(Kind.VIDEO), same.mints);

        StreamTokenBinding.resetForTest(Kind.VIDEO);
        Run other = play(true, Kind.VISITOR, true, false, none(), false);
        assertTrue(other.finished);
        assertEquals(Kind.VISITOR, other.mints.get(other.mints.size() - 1));
    }

    private static List<Kind> at2(Kind... kinds) {
        List<Kind> list = new ArrayList<>();
        for (Kind k : kinds) {
            list.add(k);
        }
        return list;
    }
}

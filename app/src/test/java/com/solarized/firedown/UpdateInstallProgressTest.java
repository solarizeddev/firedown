package com.solarized.firedown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.solarized.firedown.UpdateInstallProgress.Phase;
import com.solarized.firedown.UpdateInstallProgress.Snapshot;

import org.junit.Test;

public class UpdateInstallProgressTest {

    private static final long MB = 1024 * 1024;

    @Test
    public void startsIdleAndNotBusy() {
        Snapshot s = new UpdateInstallProgress().current();
        assertEquals(Phase.IDLE, s.phase);
        assertFalse(s.busy());
        assertEquals(0, s.generation);
    }

    @Test
    public void happyPathReachesIdleThroughAndroidsAnswer() {
        UpdateInstallProgress p = new UpdateInstallProgress();
        Snapshot begun = p.begin("Firedown 1.2.7", false);
        assertNotNull(begun);
        assertEquals(Phase.PREPARING, begun.phase);
        assertEquals(0, begun.percent);
        assertEquals("Firedown 1.2.7", begun.versionName);
        long g = begun.generation;

        Snapshot half = p.copied(g, 100 * MB, 200 * MB);
        assertEquals(50, half.percent);
        Snapshot installing = p.committed(g);
        assertEquals(Phase.INSTALLING, installing.phase);
        assertEquals(-1, installing.percent);
        assertTrue(installing.busy());
        Snapshot idle = p.answered();
        assertEquals(Phase.IDLE, idle.phase);
        assertEquals(g, idle.generation);
        assertFalse(idle.busy());
    }

    @Test
    public void oneInstallAtATime() {
        UpdateInstallProgress p = new UpdateInstallProgress();
        long g = p.begin("a", false).generation;
        assertNull("a second tap while preparing", p.begin("a", true));
        p.committed(g);
        assertNull("a second tap while Android checks", p.begin("a", true));
        p.answered();
        assertNotNull("free once Android answered", p.begin("a", false));
    }

    @Test
    public void aFailureFreesTheSlotForARetry() {
        UpdateInstallProgress p = new UpdateInstallProgress();
        long g = p.begin("a", false).generation;
        Snapshot failed = p.failed(g);
        assertEquals(Phase.FAILED, failed.phase);
        assertFalse(failed.busy());
        Snapshot retry = p.begin("a", false);
        assertNotNull(retry);
        assertEquals(g + 1, retry.generation);
    }

    @Test
    public void theCommitThrowingIsAFailureToo() {
        UpdateInstallProgress p = new UpdateInstallProgress();
        long g = p.begin("a", false).generation;
        p.committed(g);
        assertEquals(Phase.FAILED, p.failed(g).phase);
    }

    @Test
    public void copyPublishesOnlyWholePercentMoves() {
        UpdateInstallProgress p = new UpdateInstallProgress();
        long g = p.begin("a", false).generation;
        long total = 205 * MB;
        int published = 0;
        int last = 0;
        for (long done = 64 * 1024; done < total + 64 * 1024; done += 64 * 1024) {
            Snapshot s = p.copied(g, Math.min(done, total), total);
            if (s != null) {
                published++;
                assertTrue("percent never goes back", s.percent > last);
                last = s.percent;
            }
        }
        assertEquals(100, last);
        assertTrue("at most one post per percent: " + published, published <= 100);
    }

    @Test
    public void percentEdges() {
        assertEquals(0, UpdateInstallProgress.percentOf(0, 0));
        assertEquals(0, UpdateInstallProgress.percentOf(5, 0));
        assertEquals(0, UpdateInstallProgress.percentOf(-1, 10));
        assertEquals(99, UpdateInstallProgress.percentOf(999, 1000));
        assertEquals(100, UpdateInstallProgress.percentOf(1000, 1000));
        assertEquals(100, UpdateInstallProgress.percentOf(2000, 1000));
        // A 1 GiB file does not overflow the multiplication.
        assertEquals(50, UpdateInstallProgress.percentOf(512 * MB, 1024 * MB));
    }

    @Test
    public void lateCallsFromAnEarlierInstallChangeNothing() {
        UpdateInstallProgress p = new UpdateInstallProgress();
        long first = p.begin("a", false).generation;
        p.failed(first);
        long second = p.begin("a", false).generation;
        assertNull(p.copied(first, 1, 2));
        assertNull(p.committed(first));
        assertNull(p.failed(first));
        assertEquals(Phase.PREPARING, p.current().phase);
        assertEquals(second, p.current().generation);
        p.committed(second);
        assertNull("the first install's timeout", p.timedOut(first));
        assertEquals(Phase.INSTALLING, p.current().phase);
    }

    @Test
    public void androidAnswersOnlyAWaitingInstall() {
        UpdateInstallProgress p = new UpdateInstallProgress();
        assertNull("no install running", p.answered());
        assertNull("no install running", p.refused());
        long g = p.begin("a", false).generation;
        assertNull("still copying: Android has nothing to answer", p.answered());
        assertNull(p.refused());
        p.committed(g);
        assertEquals(Phase.IDLE, p.answered().phase);
        // The user cancelling Android's dialog: the refusal arrives after the
        // answer and must not turn the finished wait into a failure.
        assertNull(p.refused());
        assertEquals(Phase.IDLE, p.current().phase);
    }

    @Test
    public void androidRefusingTheSessionIsAFailure() {
        UpdateInstallProgress p = new UpdateInstallProgress();
        long g = p.begin("a", false).generation;
        p.committed(g);
        Snapshot refused = p.refused();
        assertEquals(Phase.FAILED, refused.phase);
        assertNotNull("free for a retry", p.begin("a", false));
    }

    @Test
    public void timeoutEndsOnlyTheWait() {
        UpdateInstallProgress p = new UpdateInstallProgress();
        long g = p.begin("a", false).generation;
        assertNull("not while copying", p.timedOut(g));
        p.committed(g);
        assertEquals(Phase.IDLE, p.timedOut(g).phase);
        assertNull("once", p.timedOut(g));
    }

    @Test
    public void copyAfterTheCommitChangesNothing() {
        UpdateInstallProgress p = new UpdateInstallProgress();
        long g = p.begin("a", false).generation;
        p.committed(g);
        assertNull(p.copied(g, 1, 2));
        assertNull(p.committed(g));
    }

    @Test
    public void theNotificationFlagRidesEveryTransition() {
        UpdateInstallProgress p = new UpdateInstallProgress();
        long g = p.begin("a", true).generation;
        assertTrue(p.copied(g, 1, 2).fromNotification);
        assertTrue(p.committed(g).fromNotification);
        assertTrue(p.answered().fromNotification);
        long h = p.begin("a", false).generation;
        assertFalse(p.failed(h).fromNotification);
    }
}

package limn.video;

import limn.video.VideoClock.Decision;

import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The timing policy of {@link VideoClock}, driven by a supplied clock so a whole stream is decided
 * with no real time passing: the present/hold/drop cadence against a caller, and the master a
 * player cannot trust (stalled, jumped, faster or slower than the stream, or a constant zero).
 */
class VideoClockTest {

    /** One 60 Hz refresh, in microseconds. */
    private static final long TICK_60HZ = 16_667;

    /** One 30-per-second picture interval, in microseconds. */
    private static final long FRAME_30 = 33_333;

    private long nanos;

    private long masterMicros;

    @Test
    void firstFrameAlwaysPresents() {
        VideoClock clock = clock();
        assertSame(Decision.PRESENT, clock.decide(0, FRAME_30));
        assertEquals(1, clock.presentedFrames());
        assertEquals(0, clock.driftMicros());
        assertEquals(0, clock.heldFrames());
        assertEquals(0, clock.droppedFrames());
    }

    @Test
    void firstFrameAnchorsANonZeroPtsBase() {
        VideoClock clock = clock();
        long base = 40_000_000_000L;
        assertSame(Decision.PRESENT, clock.decide(base, base + FRAME_30));
        advance(FRAME_30);
        assertSame(Decision.PRESENT, clock.decide(base + FRAME_30, base + 2 * FRAME_30),
                "the timeline is anchored to the first picture, whatever base it carries");
    }

    @Test
    void steady30fpsOn60HzCallerAlternates() {
        VideoClock clock = clock();
        Decision[] first = new Decision[8];
        long pts = 0;
        for (int tick = 0; tick < 20; tick++) {
            Decision decision = clock.decide(pts, pts + FRAME_30);
            if (tick < first.length) {
                first[tick] = decision;
            }
            if (decision == Decision.PRESENT) {
                pts += FRAME_30;
            }
            advance(TICK_60HZ);
        }

        assertArrayEquals(new Decision[] {
            Decision.PRESENT, Decision.HOLD, Decision.PRESENT, Decision.HOLD,
            Decision.PRESENT, Decision.HOLD, Decision.PRESENT, Decision.HOLD,
        }, first);
        assertEquals(10, clock.presentedFrames());
        assertEquals(10, clock.heldFrames());
        assertEquals(0, clock.droppedFrames());
    }

    @Test
    void steady60fpsPresentsEveryTick() {
        VideoClock clock = clock();
        long pts = 0;
        for (int tick = 0; tick < 20; tick++) {
            assertSame(Decision.PRESENT, clock.decide(pts, pts + TICK_60HZ), "tick " + tick);
            pts += TICK_60HZ;
            advance(TICK_60HZ);
        }
        assertEquals(20, clock.presentedFrames());
        assertEquals(0, clock.heldFrames());
        assertEquals(0, clock.droppedFrames());
    }

    @Test
    void lateFrameWithADueSuccessorDrops() {
        VideoClock clock = clock();
        clock.decide(0, FRAME_30);
        advance(100_000);

        assertSame(Decision.DROP, clock.decide(33_333, 66_666));
        assertSame(Decision.DROP, clock.decide(66_666, 99_999));
        assertSame(Decision.PRESENT, clock.decide(99_999, 133_332));
        assertEquals(2, clock.droppedFrames());
    }

    @Test
    void theLastQueuedFrameIsNeverDropped() {
        VideoClock clock = clock();
        clock.decide(0, FRAME_30);
        advance(100_000);

        assertSame(Decision.PRESENT, clock.decide(33_333, VideoClock.NO_PTS));
        assertSame(Decision.PRESENT, clock.decide(33_333));
        assertEquals(0, clock.droppedFrames(), "a queue that has run dry shows its last picture, however late");
    }

    @Test
    void anUnknownSuccessorTimestampCannotLicenseADrop() {
        assertEquals(VideoFrame.PTS_UNKNOWN, VideoClock.NO_PTS);

        VideoClock clock = clock();
        clock.decide(0, FRAME_30);
        advance(100_000);
        assertSame(Decision.PRESENT, clock.decide(33_333, VideoFrame.PTS_UNKNOWN));
    }

    @Test
    void aCandidateWithNoTimestampThrows() {
        VideoClock clock = clock();
        assertThrows(IllegalArgumentException.class, () -> clock.decide(VideoClock.NO_PTS, 0));
    }

    @Test
    void dropLoopTerminatesAtTheQueueTail() {
        VideoClock clock = clock();
        clock.decide(0, FRAME_30);
        advance(1_000_000);

        long[] queue = {0, 33_333, 66_666, 99_999, 133_332};
        int head = 0;
        int drops = 0;
        int presents = 0;
        while (head < queue.length) {
            long next = head + 1 < queue.length ? queue[head + 1] : VideoClock.NO_PTS;
            Decision decision = clock.decide(queue[head], next);
            if (decision == Decision.DROP) {
                drops++;
                head++;
                continue;
            }
            if (decision == Decision.PRESENT) {
                presents++;
                head++;
            }
            break;
        }

        assertEquals(4, drops);
        assertEquals(1, presents);
        assertEquals(queue.length, head, "the loop ends at the tail rather than spinning");
    }

    @Test
    void earlyInsideTheWindowPresentsAndOutsideHolds() {
        VideoClock clock = clock();
        clock.decide(0, FRAME_30);
        advance(100_000);

        assertSame(Decision.PRESENT, clock.decide(106_000, 139_333), "6 000 early is inside the window");
        assertSame(Decision.HOLD, clock.decide(109_000, 142_333), "9 000 early is outside it");
    }

    @Test
    void thresholdsAreTheDocumentedNumbers() {
        assertEquals(8_000L, VideoClock.EARLY_MICROS);
        assertEquals(200_000L, VideoClock.MASTER_STALL_MICROS);
        assertEquals(500_000L, VideoClock.MASTER_JUMP_MICROS);
        assertEquals(Long.MIN_VALUE, VideoClock.NO_PTS);
    }

    @Test
    void constantZeroMasterFallsBackToWall() {
        VideoClock clock = clock();
        clock.setMaster(() -> 0L);
        assertSame(Decision.PRESENT, clock.decide(0, VideoClock.NO_PTS));

        advance(199_000);
        clock.decide(FRAME_30, VideoClock.NO_PTS);
        assertFalse(clock.isMasterStalled(), "just inside the stall window");
        assertEquals(0, clock.stallCount());

        advance(1_001);
        clock.decide(FRAME_30, VideoClock.NO_PTS);
        assertTrue(clock.isMasterStalled(), "a machine with no audio device reports a constant zero");
        assertEquals(1, clock.stallCount());

        long position = clock.positionMicros();
        advance(500_000);
        assertEquals(position + 500_000, clock.positionMicros(), "the wall clock drives the picture now");
    }

    @Test
    void frozenMasterFallsBackThenRecovers() {
        VideoClock clock = clock();
        masterMicros = 0;
        clock.setMaster(() -> masterMicros);

        long pts = tick(clock, 0);
        for (int t = 0; t < 10; t++) {
            advance(TICK_60HZ);
            masterMicros += TICK_60HZ;
            pts = tick(clock, pts);
        }
        assertFalse(clock.isMasterStalled());

        for (int t = 0; t < 18; t++) {
            advance(TICK_60HZ);
            pts = tick(clock, pts);
        }
        assertTrue(clock.isMasterStalled(), "an unchanged reading for 300 006 micros is a dead device");
        assertEquals(1, clock.stallCount());

        long position = clock.positionMicros();
        advance(50_000);
        assertEquals(position + 50_000, clock.positionMicros(), "the fallback tracks wall time");

        for (int t = 0; t < 3; t++) {
            advance(TICK_60HZ);
            masterMicros += TICK_60HZ;
            pts = tick(clock, pts);
        }
        assertFalse(clock.isMasterStalled(), "a reading that moves clears the stall by itself");
        assertEquals(masterMicros, clock.positionMicros());
        assertEquals(0, clock.jumpCount(), "recovering from a stall is not a seek");
    }

    @Test
    void masterJumpForwardIsDetected() {
        VideoClock clock = clock();
        masterMicros = 0;
        clock.setMaster(() -> masterMicros);

        long pts = tick(clock, 0);
        for (int t = 0; t < 3; t++) {
            advance(TICK_60HZ);
            masterMicros += TICK_60HZ;
            pts = tick(clock, pts);
        }
        assertEquals(0, clock.jumpCount());

        advance(TICK_60HZ);
        masterMicros += 5_000_000;
        clock.decide(pts, VideoClock.NO_PTS);

        assertEquals(1, clock.jumpCount());
        assertEquals(masterMicros, clock.positionMicros(), "the new position is taken, not smoothed");
    }

    @Test
    void masterJumpBackwardIsDetected() {
        VideoClock clock = clock();
        masterMicros = 5_000_000;
        clock.setMaster(() -> masterMicros);
        clock.decide(5_000_000, VideoClock.NO_PTS);

        advance(TICK_60HZ);
        masterMicros = 1_000_000;
        assertSame(Decision.HOLD, clock.decide(5_000_000 + FRAME_30, VideoClock.NO_PTS),
                "a picture from the future waits while the caller flushes its queue");

        assertEquals(1, clock.jumpCount());
        assertEquals(1_000_000, clock.positionMicros());
    }

    @Test
    void smallIrregularityIsNotAJump() {
        VideoClock clock = clock();
        masterMicros = 0;
        clock.setMaster(() -> masterMicros);
        long pts = tick(clock, 0);

        advance(TICK_60HZ);
        masterMicros += TICK_60HZ + 400_000;
        clock.decide(pts, VideoClock.NO_PTS);

        assertEquals(0, clock.jumpCount(), "400 000 beyond elapsed is buffering, not a seek");
    }

    @Test
    void aMasterReportingInWholeSecondsIsCoarseNotSeeking() {
        VideoClock clock = clock();
        clock.setMaster(() -> nanos / 1_000_000_000L * 1_000_000L);

        long pts = tick(clock, 0);
        for (int t = 0; t < 300; t++) {
            advance(TICK_60HZ);
            pts = tick(clock, pts);
        }

        assertEquals(0, clock.jumpCount(),
                "each one-second step is one second of wall time, so nothing moved further than it should");
    }

    @Test
    void aFrozenMasterThatCatchesUpIsNotSeeking() {
        VideoClock clock = clock();
        masterMicros = 0;
        clock.setMaster(() -> masterMicros);

        long pts = tick(clock, 0);
        long frozenFor = 0;
        for (int t = 0; t < 120; t++) {
            advance(TICK_60HZ);
            frozenFor += TICK_60HZ;
            pts = tick(clock, pts);
        }
        assertTrue(clock.isMasterStalled());

        advance(TICK_60HZ);
        masterMicros += frozenFor + TICK_60HZ;
        clock.decide(pts, VideoClock.NO_PTS);

        assertFalse(clock.isMasterStalled(), "a reading that moves clears the stall");
        assertEquals(0, clock.jumpCount(),
                "a device that resumes at the position it should have reached has not seeked");
    }

    @Test
    void aMasterInstalledWhilePausedSurvivesTheResume() {
        VideoClock clock = clock();
        clock.decide(0, FRAME_30);
        clock.setPaused(true);

        advance(1_000_000);
        masterMicros = 0;
        clock.setMaster(() -> masterMicros);

        advance(1_000_000);
        clock.setPaused(false);

        advance(TICK_60HZ);
        masterMicros += TICK_60HZ;
        clock.decide(FRAME_30, VideoClock.NO_PTS);

        assertEquals(0, clock.jumpCount(),
                "the span before the master arrived is not time the master failed to report");
    }

    @Test
    void removingTheMasterContinuesFromThePositionItReached() {
        VideoClock clock = clock();
        masterMicros = 5_000_000;
        clock.setMaster(() -> masterMicros);
        clock.decide(masterMicros, VideoClock.NO_PTS);

        advance(TICK_60HZ);
        masterMicros += TICK_60HZ;
        clock.decide(masterMicros, VideoClock.NO_PTS);

        // The master moves once more with no decision in between, so the anchor left behind by the
        // last decision is stale: a handover that ignored the master's own position would snap back.
        advance(TICK_60HZ);
        masterMicros += TICK_60HZ;
        long before = clock.positionMicros();
        assertEquals(masterMicros, before);

        clock.setMaster(null);

        assertEquals(before, clock.positionMicros(), "the handover costs no media time");
        advance(TICK_60HZ);
        assertEquals(before + TICK_60HZ, clock.positionMicros(), "and the wall clock drives on from there");
        assertEquals(0, clock.jumpCount());
    }

    @Test
    void fastMasterDropsAndKeepsTracking() {
        VideoClock clock = clock();
        masterMicros = 0;
        clock.setMaster(() -> masterMicros);

        long pts = tick(clock, 0);
        for (int t = 0; t < 60; t++) {
            advance(FRAME_30);
            masterMicros += 2 * FRAME_30;
            pts = tick(clock, pts);
        }

        assertTrue(clock.droppedFrames() > 0, "twice the stream's rate cannot be shown picture by picture");
        assertTrue(clock.presentedFrames() >= 30,
                "60 ticks showed only " + clock.presentedFrames() + " pictures, so the picture froze"
                        + " rather than keeping up");
        assertTrue(Math.abs(clock.driftMicros()) <= 41_667,
                "drift stayed at " + clock.driftMicros() + ", beyond one picture interval");
    }

    @Test
    void slowMasterHoldsAndNeverDrops() {
        VideoClock clock = clock();
        masterMicros = 0;
        clock.setMaster(() -> masterMicros);

        long pts = tick(clock, 0);
        for (int t = 0; t < 60; t++) {
            advance(TICK_60HZ);
            masterMicros += TICK_60HZ / 2;
            pts = tick(clock, pts);
        }

        assertEquals(0, clock.droppedFrames(), "a clock that lags never licenses a drop");
        assertTrue(clock.presentedFrames() <= 20,
                "half rate showed " + clock.presentedFrames() + " of 60 ticks");
        assertTrue(Math.abs(clock.driftMicros()) <= 41_667,
                "drift stayed at " + clock.driftMicros() + ", beyond one picture interval");
    }

    @Test
    void pausedSuppressesStallDetection() {
        VideoClock clock = clock();
        masterMicros = 0;
        clock.setMaster(() -> masterMicros);

        long pts = tick(clock, 0);
        for (int t = 0; t < 2; t++) {
            advance(TICK_60HZ);
            masterMicros += TICK_60HZ;
            pts = tick(clock, pts);
        }

        clock.setPaused(true);
        long held = clock.heldFrames();
        for (int t = 0; t < 60; t++) {
            advance(TICK_60HZ);
            assertSame(Decision.HOLD, clock.decide(pts, pts + FRAME_30), "tick " + t + " of a pause");
        }
        assertEquals(held + 60, clock.heldFrames());
        assertFalse(clock.isMasterStalled(), "a paused master reports a frozen position by design");
        assertEquals(0, clock.stallCount());

        long paused = clock.positionMicros();
        clock.setPaused(false);
        advance(TICK_60HZ);
        masterMicros += TICK_60HZ;
        clock.decide(pts, pts + FRAME_30);

        assertEquals(0, clock.jumpCount(), "resuming is not a seek");
        assertEquals(paused + TICK_60HZ, clock.positionMicros(), "the pause cost no media time");
    }

    @Test
    void pausingWithNoMasterCostsNoMediaTime() {
        VideoClock clock = clock();
        clock.decide(0, FRAME_30);
        advance(FRAME_30);
        clock.decide(FRAME_30, VideoClock.NO_PTS);
        long before = clock.positionMicros();

        clock.setPaused(true);
        advance(5_000_000);
        assertEquals(before, clock.positionMicros(), "a frozen timeline does not move");

        clock.setPaused(false);

        assertEquals(before, clock.positionMicros(), "the wall clock ran through the pause; the timeline did not");
        advance(TICK_60HZ);
        assertEquals(before + TICK_60HZ, clock.positionMicros());
    }

    @Test
    void settingThePauseStateItAlreadyHasChangesNothing() {
        VideoClock clock = clock();
        clock.decide(0, FRAME_30);
        advance(FRAME_30);
        clock.decide(FRAME_30, VideoClock.NO_PTS);
        long before = clock.positionMicros();

        clock.setPaused(true);
        advance(1_000_000);
        clock.setPaused(true);
        advance(1_000_000);
        clock.setPaused(false);

        assertEquals(before, clock.positionMicros(),
                "a second pause must not re-date the first and swallow the span between them");

        clock.setPaused(false);
        advance(TICK_60HZ);
        assertEquals(before + TICK_60HZ, clock.positionMicros(),
                "and a second resume must not shift the timeline again");
    }

    @Test
    void theDefaultClockNeedsNoSuppliedTime() {
        VideoClock clock = new VideoClock();

        assertSame(Decision.PRESENT, clock.decide(0, FRAME_30), "the first picture anchors the timeline");
        assertSame(Decision.HOLD, clock.decide(24 * 60 * 60 * 1_000_000L, VideoClock.NO_PTS),
                "a picture a day into the stream is not due yet");

        assertThrows(NullPointerException.class, () -> new VideoClock(null));
    }

    @Test
    void wallClockDrivesAtTrueRate() {
        VideoClock clock = clock();
        long pts = 0;
        for (int t = 0; t < 180; t++) {
            pts = tick(clock, pts);
            advance(TICK_60HZ);
        }

        assertTrue(clock.presentedFrames() >= 89 && clock.presentedFrames() <= 91,
                "3 000 060 micros of wall showed " + clock.presentedFrames() + " pictures of 30-per-second content");
        assertEquals(0, clock.droppedFrames());
    }

    @Test
    void resetReAnchorsButKeepsCounters() {
        VideoClock clock = clock();
        clock.decide(0, FRAME_30);
        advance(100_000);
        clock.decide(33_333, 66_666);
        clock.decide(66_666, 99_999);
        long dropped = clock.droppedFrames();
        assertEquals(2, dropped);

        clock.reset();

        assertSame(Decision.PRESENT, clock.decide(9_000_000, 9_033_333), "the first picture after a seek is shown");
        assertEquals(0, clock.driftMicros());
        assertEquals(dropped, clock.droppedFrames(), "a seek is not a reason to lose a session's history");
    }

    @Test
    void decideReadsTheWallClockExactlyOnce() {
        CountingClock nanoClock = new CountingClock();
        VideoClock clock = new VideoClock(nanoClock);

        clock.decide(0, FRAME_30);
        assertEquals(1, nanoClock.reads);

        for (int t = 0; t < 10; t++) {
            nanoClock.nanos += TICK_60HZ * 1_000L;
            clock.decide(FRAME_30, 2 * FRAME_30);
        }
        assertEquals(11, nanoClock.reads, "two readings inside one decision would let its halves disagree");
    }

    @Test
    void decideReadsTheMasterAtMostOncePerCall() {
        CountingMaster master = new CountingMaster();
        VideoClock clock = clock();
        clock.setMaster(master);
        master.reads = 0;

        long pts = 0;
        for (int t = 0; t < 10; t++) {
            advance(TICK_60HZ);
            master.position += TICK_60HZ;
            clock.decide(pts, VideoClock.NO_PTS);
            pts += TICK_60HZ;
        }
        assertEquals(9, master.reads,
                "10 decisions read the master " + master.reads + " times; the first returns on the"
                        + " unstarted branch without reading it, and the other nine read it once each");

        clock.setPaused(true);
        int paused = master.reads;
        for (int t = 0; t < 5; t++) {
            advance(TICK_60HZ);
            clock.decide(pts, VideoClock.NO_PTS);
        }
        assertEquals(paused, master.reads, "a paused clock does not read the master at all");
    }

    @Test
    void decidingAllocatesNothing() {
        assumeTrue(AllocationProbe.isSupported(), "this virtual machine does not count thread allocation");

        VideoClock clock = clock();
        masterMicros = 0;
        clock.setMaster(() -> masterMicros);
        Runnable decisions = () -> {
            for (int t = 0; t < 10_000; t++) {
                advance(TICK_60HZ);
                masterMicros += TICK_60HZ;
                clock.decide(masterMicros, masterMicros + FRAME_30);
            }
        };

        long allocated = AllocationProbe.leastAllocatedBy(decisions, 3);

        assertEquals(0L, allocated,
                "ten thousand decisions allocated " + allocated + " bytes; a player asks for one every"
                        + " refresh, so anything here is garbage at the display's rate");
    }

    @Test
    void holdNeverAdvancesTheTimeline() {
        VideoClock clock = clock();
        clock.decide(0, FRAME_30);
        advance(40_000);
        assertSame(Decision.PRESENT, clock.decide(33_333, 100_000));
        long drift = clock.driftMicros();
        long presented = clock.presentedFrames();
        assertEquals(-6_667, drift);

        advance(1_000);
        assertSame(Decision.HOLD, clock.decide(100_000, 133_333));

        assertEquals(drift, clock.driftMicros(), "a hold shows nothing, so it measures nothing");
        assertEquals(presented, clock.presentedFrames());
    }

    private VideoClock clock() {
        return new VideoClock(() -> nanos);
    }

    private void advance(long micros) {
        nanos += micros * 1_000L;
    }

    /**
     * One tick of a player driving 30-per-second content: drains the drops the clock licenses, shows
     * at most one picture, and answers with the presentation time of the next candidate.
     */
    private static long tick(VideoClock clock, long pts) {
        Decision decision;
        long candidate = pts;
        while ((decision = clock.decide(candidate, candidate + FRAME_30)) == Decision.DROP) {
            candidate += FRAME_30;
        }
        return decision == Decision.PRESENT ? candidate + FRAME_30 : candidate;
    }

    /** A wall clock that counts its readings, so a decision that read twice is loud. */
    private static final class CountingClock implements LongSupplier {

        private long nanos;
        private int reads;

        @Override
        public long getAsLong() {
            reads++;
            return nanos;
        }
    }

    /** A master position that counts its readings. */
    private static final class CountingMaster implements VideoClock.MasterClock {

        private long position;
        private int reads;

        @Override
        public long positionMicros() {
            reads++;
            return position;
        }
    }
}

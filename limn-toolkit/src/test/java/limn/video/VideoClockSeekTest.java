package limn.video;

import limn.video.VideoClock.Decision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What {@link VideoClock#seekTo} is for: telling the difference between a timeline that was moved
 * and a master that ran away. Every one of these fails if the clock is left to infer it, which is
 * what the first two assert directly.
 */
class VideoClockSeekTest {

    private static final long FRAME_30 = 33_333;

    private long nanos;
    private long masterMicros;

    @Test
    void aSeekIsNotAJump() {
        VideoClock clock = clock();
        clock.setMaster(() -> masterMicros);
        play(clock, 0, 10);
        long before = clock.jumpCount();

        // A seek: the caller moves the master and tells the clock where the timeline went, in that
        // order, which is the order a player does it in.
        long target = 60_000_000;
        masterMicros = target;
        clock.seekTo(target);
        play(clock, target, 10);

        assertEquals(before, clock.jumpCount(),
                "a seek the clock was told about is not a master that ran away");
    }

    @Test
    void theSameMoveWithoutSeekToIsAJump() {
        VideoClock clock = clock();
        clock.setMaster(() -> masterMicros);
        play(clock, 0, 10);
        long before = clock.jumpCount();

        masterMicros = 60_000_000;
        play(clock, 60_000_000, 10);

        assertEquals(before + 1, clock.jumpCount(),
                "without being told, the clock reads the move as the master running away, which "
                        + "is what makes a player drop it, and what seekTo exists to prevent");
    }

    @Test
    void positionIsTheTargetUntilTheFirstPictureArrives() {
        VideoClock clock = clock();
        play(clock, 0, 5);
        clock.seekTo(12_345_678);
        assertEquals(12_345_678, clock.positionMicros());
        advance(500_000); // the pictures are still being decoded
        assertEquals(12_345_678, clock.positionMicros(),
                "the position does not creep forward while the pictures are being found");
        assertSame(Decision.PRESENT, clock.decide(12_345_678));
    }

    @Test
    void positionIsTheTargetEvenWithAMasterThatHasNotMovedYet() {
        VideoClock clock = clock();
        clock.setMaster(() -> masterMicros);
        play(clock, 0, 5);
        clock.seekTo(9_000_000);
        assertEquals(9_000_000, clock.positionMicros(),
                "an engine repositions asynchronously; polling it here would report the position "
                        + "being left");
    }

    @Test
    void theNextPictureIsAlwaysShownAndReAnchors() {
        VideoClock clock = clock();
        play(clock, 0, 30);
        clock.seekTo(5_000_000);
        assertSame(Decision.PRESENT, clock.decide(5_000_000, 5_000_000 + FRAME_30),
                "the first picture after a seek is shown whatever its timestamp");
        advance(FRAME_30);
        assertSame(Decision.PRESENT, clock.decide(5_000_000 + FRAME_30));
    }

    @Test
    void seekingWhilePausedMovesTheFrozenPositionAndKeepsThePause() {
        VideoClock clock = clock();
        play(clock, 0, 5);
        clock.setPaused(true);
        long held = clock.positionMicros();
        clock.seekTo(held + 7_000_000);
        assertEquals(held + 7_000_000, clock.positionMicros());
        assertSame(Decision.HOLD, clock.decide(held + 7_000_000),
                "a seek does not lift a pause");
    }

    @Test
    void resumingAfterASeekWhilePausedCostsNoMediaTime() {
        VideoClock clock = clock();
        clock.setPaused(true);
        clock.seekTo(4_000_000);
        advance(2_000_000); // paused for two seconds
        clock.setPaused(false);
        assertEquals(4_000_000, clock.positionMicros(),
                "the paused span is not spent as media time by the seek's anchor either");
    }

    @Test
    void aStalledMasterIsRearmedBySeeking() {
        VideoClock clock = clock();
        clock.setMaster(() -> masterMicros);
        clock.decide(0);
        advance(VideoClock.MASTER_STALL_MICROS + FRAME_30);
        clock.decide(FRAME_30);
        assertTrueStalled(clock);

        masterMicros = 3_000_000;
        clock.seekTo(3_000_000);
        assertFalse(clock.isMasterStalled(),
                "a device that was not advancing is given another chance by a seek rather than "
                        + "being counted as dead for the rest of the session");
    }

    @Test
    void resetForgetsASeeksAnchor() {
        VideoClock clock = clock();
        clock.seekTo(8_000_000);
        clock.reset();
        assertEquals(0, clock.positionMicros(),
                "reset means nothing has anchored the timeline, seek or no seek");
    }

    @Test
    void countersSurviveASeek() {
        VideoClock clock = clock();
        play(clock, 0, 10);
        long presented = clock.presentedFrames();
        clock.seekTo(1_000_000);
        assertEquals(presented, clock.presentedFrames(),
                "a seek is not a reason to lose a session's history");
        assertEquals(0, clock.driftMicros());
    }

    private static void assertTrueStalled(VideoClock clock) {
        org.junit.jupiter.api.Assertions.assertTrue(clock.isMasterStalled(),
                "the fixture needs a stalled master to be re-armed");
    }

    /** Shows {@code count} pictures at 30 per second from {@code fromPts}, master and wall in step. */
    private void play(VideoClock clock, long fromPts, int count) {
        for (int i = 0; i < count; i++) {
            long pts = fromPts + i * FRAME_30;
            clock.decide(pts, pts + FRAME_30);
            advance(FRAME_30);
            masterMicros = pts + FRAME_30;
        }
    }

    private VideoClock clock() {
        return new VideoClock(() -> nanos);
    }

    private void advance(long micros) {
        nanos += micros * 1_000L;
    }
}

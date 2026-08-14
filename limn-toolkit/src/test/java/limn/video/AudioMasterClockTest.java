package limn.video;

import limn.sound.Playback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audio master, and every way it can be absent or wrong. Each case is driven by a hand-turned
 * nanosecond clock, so hours of playback pass with no real time spent and nothing depends on how
 * loaded the machine running the suite is.
 */
class AudioMasterClockTest {

    /** A playback whose position the test sets, in seconds. */
    private static final class FakePlayback implements Playback {
        double seconds;
        boolean playing = true;
        int positionReads;

        @Override
        public void stop() {
            playing = false;
        }

        @Override
        public boolean isPlaying() {
            return playing;
        }

        @Override
        public void setGain(float gain) {
        }

        @Override
        public double positionSeconds() {
            positionReads++;
            return seconds;
        }
    }

    /** Wall clock a test turns by hand; nanoseconds, as {@link VideoClock} reads them. */
    private static final class Hand {
        long nanos;

        long get() {
            return nanos;
        }

        void advanceMicros(long micros) {
            nanos += micros * 1_000L;
        }
    }

    private static final long FRAME_MICROS = 33_333; // 30 per second

    @Test
    void secondsBecomeMicrosecondsRoundedToNearest() {
        FakePlayback playback = new FakePlayback();
        AudioMasterClock master = new AudioMasterClock(playback);

        playback.seconds = 1.0;
        assertEquals(1_000_000L, master.positionMicros());

        // 0.0000005 s is exactly half a microsecond: rounded up, not truncated away.
        playback.seconds = 0.0000005;
        assertEquals(1L, master.positionMicros());

        // The rate a truncating adapter drifts on. Read absolutely, never accumulated, so the
        // error is half a microsecond at any position rather than half a microsecond per picture.
        playback.seconds = 1001.0 / 30_000.0 * 30_000; // 1001 s to the last bit a double can hold
        assertEquals(1_001_000_000L, master.positionMicros());
    }

    @Test
    void nothingUsableReadsAsZeroRatherThanAsPoison() {
        FakePlayback playback = new FakePlayback();
        AudioMasterClock master = new AudioMasterClock(playback);

        playback.seconds = Double.NaN;
        assertEquals(0L, master.positionMicros(), "NaN is not a position");
        playback.seconds = -3;
        assertEquals(0L, master.positionMicros(), "a negative is not a position");
        playback.seconds = Double.POSITIVE_INFINITY;
        assertEquals(0L, master.positionMicros(), "infinity is not a position");
        playback.seconds = 0;
        assertEquals(0L, master.positionMicros());
    }

    @Test
    void theNullPlaybackIsRefusedAndTheHandleIsReadable() {
        assertThrows(NullPointerException.class, () -> new AudioMasterClock(null));
        AudioMasterClock master = new AudioMasterClock(Playback.NONE);
        assertSame(Playback.NONE, master.playback());
        assertEquals(0L, master.positionMicros(), "the null playback is a constant zero");
    }

    // ------------------------------------------------------ the absences, against a real clock

    @Test
    void noAudioDeviceAtAllStillPlaysAtTheRightRate() {
        // Playback.NONE is what Sounds hands back with no engine installed and on a machine with
        // no device. Its position is a constant zero, which is a stalled master and not a timeline.
        Hand hand = new Hand();
        VideoClock clock = new VideoClock(hand::get);
        clock.setMaster(new AudioMasterClock(Playback.NONE));

        assertEquals(VideoClock.Decision.PRESENT, clock.decide(0), "the first picture anchors");

        // A caller holds the SAME candidate until it is shown: a hold does not advance the
        // stream. Through the stall window every decision is a hold, because for all the clock
        // knows the device is about to speak; that hitch is the only cost of following a master
        // that never was.
        long shown = 1;
        long pts = FRAME_MICROS;
        for (long wall = FRAME_MICROS; wall <= 4_000_000; wall += FRAME_MICROS) {
            hand.advanceMicros(FRAME_MICROS);
            if (clock.decide(pts) == VideoClock.Decision.PRESENT) {
                shown++;
                pts += FRAME_MICROS;
            }
        }
        assertTrue(clock.isMasterStalled(), "a constant zero must be declared stalled");
        assertEquals(1, clock.stallCount());

        // 4 s of wall time at 30 per second is 120 pictures; the stall costs the first 200 ms of
        // them, and the video runs that far behind the wall for good. What matters is the rate
        // afterwards, not the hitch: it must not be stuck at one picture.
        assertTrue(shown > 100, "only " + shown + " pictures in 4 s: the wall clock never took over");
        assertTrue(shown <= 121, "showed more pictures than there were moments: " + shown);
    }

    @Test
    void aTrackThatHasNotStartedProducingAPositionYetIsJustAStallThatEnds() {
        Hand hand = new Hand();
        FakePlayback playback = new FakePlayback(); // 0 s: primed, queued, not yet sounding
        VideoClock clock = new VideoClock(hand::get);
        clock.setMaster(new AudioMasterClock(playback));

        assertEquals(VideoClock.Decision.PRESENT, clock.decide(0));
        hand.advanceMicros(VideoClock.MASTER_STALL_MICROS + FRAME_MICROS);
        clock.decide(FRAME_MICROS);
        assertTrue(clock.isMasterStalled(), "silence for longer than the stall window is a stall");

        // The device speaks. The very next reading that differs clears the stall by itself.
        playback.seconds = 0.25;
        hand.advanceMicros(FRAME_MICROS);
        clock.decide(2 * FRAME_MICROS);
        assertFalse(clock.isMasterStalled(), "a master that moved is not stalled any more");
    }

    @Test
    void aPausedTrackNeedsSetPausedAndIsOtherwiseIndistinguishableFromADeadDevice() {
        Hand hand = new Hand();
        FakePlayback playback = new FakePlayback();
        VideoClock clock = new VideoClock(hand::get);
        clock.setMaster(new AudioMasterClock(playback));
        clock.decide(0);

        // Told: the pause costs no media time and every decision through it is a hold.
        clock.setPaused(true);
        long heldBefore = clock.heldFrames();
        for (int i = 0; i < 60; i++) {
            hand.advanceMicros(FRAME_MICROS);
            assertEquals(VideoClock.Decision.HOLD, clock.decide(FRAME_MICROS),
                    "a paused clock shows nothing new");
        }
        assertEquals(heldBefore + 60, clock.heldFrames());
        assertEquals(0, clock.stallCount(), "a pause the clock knows about is not a stall");
        clock.setPaused(false);

        // Not told, the same frozen position is a dead device: the wall clock takes over and the
        // video runs straight through the pause. That is why setPaused is not optional.
        Hand ignorant = new Hand();
        FakePlayback frozen = new FakePlayback();
        VideoClock untold = new VideoClock(ignorant::get);
        untold.setMaster(new AudioMasterClock(frozen));
        untold.decide(0);
        for (long t = FRAME_MICROS; t <= 2_000_000; t += FRAME_MICROS) {
            ignorant.advanceMicros(FRAME_MICROS);
            untold.decide(t);
        }
        assertEquals(1, untold.stallCount(),
                "an untold pause reads as a stalled device: the failure setPaused exists to prevent");
    }

    @Test
    void aDeviceThatDiesMidTrackHandsTheTimelineToTheWallClockWithoutAStep() {
        Hand hand = new Hand();
        FakePlayback playback = new FakePlayback();
        VideoClock clock = new VideoClock(hand::get);
        clock.setMaster(new AudioMasterClock(playback));
        clock.decide(0);

        // Two seconds of healthy tracking.
        for (long t = FRAME_MICROS; t < 2_000_000; t += FRAME_MICROS) {
            hand.advanceMicros(FRAME_MICROS);
            playback.seconds = t / 1_000_000.0;
            clock.decide(t);
        }
        assertEquals(0, clock.stallCount());
        long lastGoodPosition = clock.positionMicros();

        // The device stops answering. The position must not fall back to zero and must not leap:
        // it carries on from where it had reached.
        for (long t = 2_000_000; t < 2_600_000; t += FRAME_MICROS) {
            hand.advanceMicros(FRAME_MICROS);
            clock.decide(t);
        }
        assertEquals(1, clock.stallCount());
        long afterStall = clock.positionMicros();
        assertTrue(afterStall >= lastGoodPosition,
                "the timeline went backwards at the stall: " + lastGoodPosition + " to " + afterStall);
        assertTrue(afterStall - lastGoodPosition < 700_000,
                "the timeline leapt at the stall by " + (afterStall - lastGoodPosition) + "us");
    }

    @Test
    void aLoopingTrackThatWrapsIsCountedAsASeekRatherThanAsDrift() {
        Hand hand = new Hand();
        FakePlayback playback = new FakePlayback();
        VideoClock clock = new VideoClock(hand::get);
        clock.setMaster(new AudioMasterClock(playback));
        clock.decide(0);

        for (long t = FRAME_MICROS; t < 5_000_000; t += FRAME_MICROS) {
            hand.advanceMicros(FRAME_MICROS);
            playback.seconds = t / 1_000_000.0;
            clock.decide(t);
        }
        assertEquals(0, clock.jumpCount(), "healthy tracking is not a jump");

        // The engine rewound the stream at the end of its data and reports in-track time again.
        hand.advanceMicros(FRAME_MICROS);
        playback.seconds = 0.01;
        clock.decide(5_000_000);
        assertEquals(1, clock.jumpCount(),
                "a wrap is the whole track backwards, far above the seek threshold");
    }

    @Test
    void aPositionMovingInStepsCoarserThanTheDisplayIsNotASeekAndNotAStall() {
        // A streaming engine reports in steps of a mixer period, and that period belongs to the
        // device rather than to the engine: measured, it varies by a factor of two between two
        // drivers on one machine, straddling a 60 Hz refresh. So the same reading does come back
        // twice and a caller cannot know when. The stall and jump timers are dated from the last
        // reading that CHANGED for exactly this reason; dated from the last reading TAKEN, every
        // step would be scored against one poll interval and counted as a seek.
        Hand hand = new Hand();
        FakePlayback playback = new FakePlayback();
        VideoClock clock = new VideoClock(hand::get);
        clock.setMaster(new AudioMasterClock(playback));
        clock.decide(0);

        long stepMicros = 21_333;  // one mixer period
        long refreshMicros = 16_666; // 60 Hz
        long wall = 0;
        for (int refresh = 1; refresh <= 600; refresh++) {
            hand.advanceMicros(refreshMicros);
            wall += refreshMicros;
            playback.seconds = (wall / stepMicros) * stepMicros / 1_000_000.0;
            clock.decide(wall);
        }
        assertEquals(0, clock.jumpCount(), "a coarse step is not a seek");
        assertEquals(0, clock.stallCount(), "a step shorter than the stall window is not a stall");
        assertEquals(601, playback.positionReads,
                "once when installed and once per decision: reading it twice inside one decision "
                        + "lets the two halves of that decision disagree");
    }

    @Test
    void theMasterIsReadOnceForEachDecisionAndOnceWhenInstalled() {
        Hand hand = new Hand();
        FakePlayback playback = new FakePlayback();
        VideoClock clock = new VideoClock(hand::get);

        clock.setMaster(new AudioMasterClock(playback));
        assertEquals(1, playback.positionReads, "installing arms the stall timer with one reading");

        clock.decide(0);   // the first decision anchors and does not read the master again
        hand.advanceMicros(FRAME_MICROS);
        playback.seconds = 0.033;
        clock.decide(FRAME_MICROS);
        assertEquals(2, playback.positionReads,
                "reading the master twice inside one decision lets its halves disagree");
    }
}

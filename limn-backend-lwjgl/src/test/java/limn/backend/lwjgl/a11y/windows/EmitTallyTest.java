package limn.backend.lwjgl.a11y.windows;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic behind the two numbers the guest run reports, checked where there is no guest.
 *
 * <p>The live run prints what this class computes and nothing else, so a mistake here is a wrong
 * number in ADR&nbsp;039 &sect;13.19 with a real run's authority behind it.
 */
class EmitTallyTest {

    @Test
    void aFrameIsWhatLiesBetweenTwoPublishesAndCountsOnlyItsOwnEvents() {
        EmitTally tally = new EmitTally();
        tally.published();
        tally.emitted(10, false);
        tally.emitted(10, false);
        tally.emitted(10, false);
        tally.published();
        tally.emitted(10, false);
        tally.published();
        tally.emitted(10, false);
        tally.emitted(10, false);
        tally.close();

        assertEquals(3, tally.frames());
        assertEquals(3, tally.maxPerFrame());
        assertEquals(2, tally.medianPerFrame(), "sorted 1, 2, 3: the middle one");
    }

    @Test
    void theOpenFrameIsReadableWhileItIsOpen() {
        EmitTally tally = new EmitTally();
        tally.published();
        tally.emitted(10, true);
        tally.emitted(10, false);
        assertEquals(2, tally.eventsInOpenFrame());
        assertEquals(1, tally.raisedInOpenFrame());
        assertEquals(0, tally.frames(), "not closed yet");
        tally.published();
        assertEquals(0, tally.eventsInOpenFrame(), "a publish opens a fresh frame");
        assertEquals(1, tally.frames());
    }

    @Test
    void closingTwiceClosesOnce() {
        EmitTally tally = new EmitTally();
        tally.published();
        tally.emitted(10, true);
        tally.close();
        tally.close();
        assertEquals(1, tally.frames());
        assertEquals(0, tally.eventsInOpenFrame());
    }

    @Test
    void nothingPublishedIsNoFrameAtAll() {
        EmitTally tally = new EmitTally();
        tally.close();
        assertEquals(0, tally.frames());
        assertEquals(0, tally.maxPerFrame());
        assertEquals(0, tally.medianPerFrame());
        assertEquals(0, tally.minRaiseNanos());
        assertEquals(0, tally.medianRaiseNanos());
        assertEquals(0, tally.maxRaiseNanos());
    }

    @Test
    void raisedAndSkippedAreKeptApartSoASkipCannotPullTheRaiseCostDown() {
        EmitTally tally = new EmitTally();
        tally.published();
        tally.emitted(300, true);
        tally.emitted(5, false);
        tally.emitted(100, true);
        tally.emitted(7, false);
        tally.emitted(200, true);
        tally.close();

        assertEquals(3, tally.raised());
        assertEquals(2, tally.skipped());
        assertEquals(100, tally.minRaiseNanos());
        assertEquals(200, tally.medianRaiseNanos());
        assertEquals(300, tally.maxRaiseNanos());
        assertEquals(7, tally.medianSkipNanos(), "sorted 5, 7: the upper middle");
        assertEquals(5, tally.eventsInOpenFrame() + tally.maxPerFrame(),
                "the frame counted all five, raised or not");
    }

    @Test
    void theSummaryCarriesEveryNumberTheRecordAsksFor() {
        EmitTally tally = new EmitTally();
        tally.published();
        tally.emitted(120, true);
        tally.emitted(3, false);
        tally.published();
        tally.emitted(80, true);
        tally.close();

        String summary = tally.summary();
        assertTrue(summary.contains("frames=2"), summary);
        assertTrue(summary.contains("max=2 median=2"), summary);
        assertTrue(summary.contains("raised=2 ns min=80 median=120 max=120"), summary);
        assertTrue(summary.contains("skipped=1 ns median=3"), summary);
        assertTrue(summary.contains("per frame=[2, 1]"), summary);
    }
}

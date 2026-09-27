package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decision 113 (PF-1): when the vsynced swap does not block — a covered macOS window, a driver that
 * ignores the interval — the loop sleeps to the end of the refresh period; when it does block, or the
 * window was idle, it sleeps nothing.
 */
class FramePacerTest {

    private static final long PERIOD = 16_666_667L;

    private final long[] now = {1_000_000_000L};
    private final List<Long> sleeps = new ArrayList<>();
    private final FramePacer pacer = new FramePacer(() -> now[0], nanos -> {
        sleeps.add(nanos);
        now[0] += nanos;
    }, PERIOD);

    @Test
    void swapsThatDidNotBlockAreFollowedByASleepToThePeriodsEndFromTheSecond() {
        assertEquals(0, pacer.afterVsyncedPresent(), "the first present has nothing to measure from");
        now[0] += 300_000; // the next present came 0.3 ms later: the swap did not wait for the blank
        assertEquals(0, pacer.afterVsyncedPresent(), "one short present could be macOS's own");
        now[0] += 300_000;
        assertEquals(PERIOD - 300_000, pacer.afterVsyncedPresent(), "two in a row are not");
        now[0] += 300_000;
        assertEquals(PERIOD - 300_000, pacer.afterVsyncedPresent(), "and every one after it");
        assertEquals(List.of(PERIOD - 300_000, PERIOD - 300_000), sleeps);
    }

    /**
     * A visible macOS window's blocking swap, as measured on a 120 Hz ProMotion display: a short
     * return is always followed by a long one. Slept after each short one, the loop lost 5 to 8
     * frames a second of the 113 to 117 it ran at unpaced.
     */
    @Test
    void theShortThenLongReturnsOfABlockingMacSwapAreNeverSlept() {
        FramePacer at120 = new FramePacer(() -> now[0], nanos -> {
            sleeps.add(nanos);
            now[0] += nanos;
        }, 8_333_333L);
        at120.afterVsyncedPresent();
        long[] measured = {2_900_000, 14_000_000, 8_340_000, 3_100_000, 13_800_000, 8_330_000,
                2_130_000, 14_500_000};
        for (long interval : measured) {
            now[0] += interval;
            assertEquals(0, at120.afterVsyncedPresent());
        }
        assertEquals(List.of(), sleeps);
    }

    @Test
    void aSwapThatBlockedAndAPresentAfterAnIdleStretchSleepNothing() {
        pacer.afterVsyncedPresent();
        now[0] += PERIOD; // the swap waited for the blank, as it should
        assertEquals(0, pacer.afterVsyncedPresent());
        now[0] += 5_000_000_000L; // on-demand rendering: the next frame came much later
        assertEquals(0, pacer.afterVsyncedPresent());
        now[0] += PERIOD / 2; // at exactly half a period the swap is taken to have blocked
        assertEquals(0, pacer.afterVsyncedPresent());
        assertEquals(List.of(), sleeps);
    }

    @Test
    void anUnknownRefreshRateFallsBackToSixtyHertz() {
        FramePacer unknown = new FramePacer(() -> 0, nanos -> { }, 0);
        assertEquals(1_000_000_000L / 60, unknown.periodNanos());
    }
}

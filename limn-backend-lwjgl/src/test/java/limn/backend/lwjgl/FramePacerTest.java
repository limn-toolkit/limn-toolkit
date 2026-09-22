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
    void aSwapThatDidNotBlockIsFollowedByASleepToThePeriodsEnd() {
        assertEquals(0, pacer.afterVsyncedPresent(), "the first present has nothing to measure from");
        now[0] += 300_000; // the next present came 0.3 ms later: the swap did not wait for the blank
        assertEquals(PERIOD - 300_000, pacer.afterVsyncedPresent());
        now[0] += 300_000;
        assertEquals(PERIOD - 300_000, pacer.afterVsyncedPresent(), "and every one after it");
        assertEquals(List.of(PERIOD - 300_000, PERIOD - 300_000), sleeps);
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

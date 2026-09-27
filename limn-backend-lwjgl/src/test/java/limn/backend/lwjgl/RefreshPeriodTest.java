package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A window is paced to the monitor it is on, and a blocking swap on a fast monitor is never slept:
 * the period used to be the primary monitor's, so a window on a 144 Hz monitor beside a 60 Hz
 * primary had every frame slept to 60 fps.
 */
class RefreshPeriodTest {

    private static final long HZ_60 = 1_000_000_000L / 60;
    private static final long HZ_144 = 1_000_000_000L / 144;

    /** A 60 Hz primary and a 144 Hz monitor to its right. */
    private static final List<RefreshPeriod.Monitor> DESK = List.of(
            new RefreshPeriod.Monitor(0, 0, 1920, 1080, 60),
            new RefreshPeriod.Monitor(1920, 0, 2560, 1440, 144));

    @Test
    void aWindowIsPacedToTheMonitorItsCentreIsOn() {
        assertEquals(HZ_60, RefreshPeriod.forWindow(true, 900, 500, DESK));
        assertEquals(HZ_144, RefreshPeriod.forWindow(true, 2500, 500, DESK));
    }

    @Test
    void draggedAcrossTheSeamTheWindowTakesTheNewMonitorsRate() {
        // A 600-wide window dragged right: the centre crosses x = 1920 at x = 1620.
        assertEquals(HZ_60, RefreshPeriod.forWindow(true, 1619 + 300, 400, DESK));
        assertEquals(HZ_144, RefreshPeriod.forWindow(true, 1620 + 300, 400, DESK));
    }

    @Test
    void whereTheMonitorCannotBeKnownThePeriodIsTheShortestThereIs() {
        assertEquals(HZ_144, RefreshPeriod.forWindow(false, 0, 0, DESK), "Wayland tells no position");
        assertEquals(HZ_144, RefreshPeriod.forWindow(true, -5000, 0, DESK), "a centre off every monitor");
        List<RefreshPeriod.Monitor> unknownRate = List.of(
                new RefreshPeriod.Monitor(0, 0, 1920, 1080, 0),
                new RefreshPeriod.Monitor(1920, 0, 1920, 1080, 75));
        assertEquals(1_000_000_000L / 75, RefreshPeriod.forWindow(true, 900, 500, unknownRate),
                "a monitor that states no rate");
        assertEquals(0, RefreshPeriod.forWindow(true, 900, 500,
                List.of(new RefreshPeriod.Monitor(0, 0, 1920, 1080, 0))), "nothing states one");
        assertEquals(0, RefreshPeriod.forWindow(true, 900, 500, List.of()), "no monitor at all");
    }

    /**
     * What the right period buys: 144 Hz presents that each blocked are slept under the primary's
     * period and left alone under the window's own.
     */
    @Test
    void blockingPresentsOnAFastMonitorAreSleptOnlyUnderTheWrongPeriod() {
        assertEquals(0, sleptAtHz144(RefreshPeriod.forWindow(true, 2500, 500, DESK)));
        assertEquals(9, sleptAtHz144(HZ_60), "the primary's period slept nine of ten");
    }

    /** How many of ten presents 1/144 s apart the pacer slept after, under {@code period}. */
    private static int sleptAtHz144(long period) {
        long[] now = {1_000_000_000L};
        List<Long> sleeps = new ArrayList<>();
        FramePacer pacer = new FramePacer(() -> now[0], nanos -> {
            sleeps.add(nanos);
            now[0] += nanos;
        }, period);
        for (int i = 0; i < 10; i++) {
            pacer.afterVsyncedPresent();
            now[0] += HZ_144;
        }
        return sleeps.size();
    }

    /** A variable-refresh display paces slower than its mode's rate, never faster: nothing sleeps. */
    @Test
    void aVariableRefreshDisplaySlowingDownIsNeverSlept() {
        long[] now = {1_000_000_000L};
        List<Long> sleeps = new ArrayList<>();
        FramePacer pacer = new FramePacer(() -> now[0], nanos -> sleeps.add(nanos), 1_000_000_000L / 120);
        long[] intervals = {8_333_333, 11_000_000, 16_700_000, 25_000_000, 9_000_000, 8_333_333};
        pacer.afterVsyncedPresent();
        for (long interval : intervals) {
            now[0] += interval;
            pacer.afterVsyncedPresent();
        }
        assertEquals(List.of(), sleeps);
    }
}

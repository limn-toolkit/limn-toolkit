package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR 046 §5: presses in a row, counted with the interval the platform gives, as the platform does. */
class ClickCounterTest {

    private final long[] now = {1_000_000_000L};
    private final ClickCounter clicks = new ClickCounter(() -> now[0], 300_000_000L);

    @Test
    void pressesOfOneButtonNearEachOtherWithinTheIntervalCountUp() {
        assertEquals(1, clicks.press(0, 10, 10));
        now[0] += 200_000_000L;
        assertEquals(2, clicks.press(0, 12, 11), "within the interval and the slop: a double click");
        assertEquals(2, clicks.release(), "its release carries the same count");
        now[0] += 200_000_000L;
        assertEquals(3, clicks.press(0, 12, 11), "and a third");
    }

    @Test
    void theIntervalTheDistanceAndTheButtonEachStartACountAgain() {
        clicks.press(0, 10, 10);
        now[0] += 400_000_000L;
        assertEquals(1, clicks.press(0, 10, 10), "past the platform's interval");
        now[0] += 100_000_000L;
        assertEquals(1, clicks.press(0, 10 + ClickCounter.SLOP + 1, 10), "too far away");
        now[0] += 100_000_000L;
        assertEquals(1, clicks.press(1, 10 + ClickCounter.SLOP + 1, 10), "another button");
    }

    @Test
    void anUnreadableIntervalFallsBackToHalfASecond() {
        assertEquals(ClickCounter.DEFAULT_INTERVAL_NANOS, new ClickCounter(() -> 0, 0).intervalNanos());
        assertTrue(ClickCounter.forThisPlatform().intervalNanos() > 0, "whatever this machine answers");
    }
}

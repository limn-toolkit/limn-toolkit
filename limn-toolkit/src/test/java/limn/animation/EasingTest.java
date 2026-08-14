package limn.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EasingTest {

    private static final Easing[] ALL = {
            Easing.LINEAR, Easing.EASE_IN, Easing.EASE_OUT, Easing.EASE_IN_OUT
    };

    @Test
    void everyCurveRunsFromZeroToOne() {
        for (Easing e : ALL) {
            assertEquals(0f, e.apply(0f), 1e-5f, "starts at 0");
            assertEquals(1f, e.apply(1f), 1e-5f, "ends at 1");
        }
    }

    @Test
    void linearIsTheIdentity() {
        assertEquals(0.25f, Easing.LINEAR.apply(0.25f), 0);
        assertEquals(0.5f, Easing.LINEAR.apply(0.5f), 0);
    }

    @Test
    void easeOutLeadsAndEaseInLagsLinearEarlyOn() {
        float t = 0.5f;
        assertTrue(Easing.EASE_OUT.apply(t) > t, "ease-out is ahead of linear");
        assertTrue(Easing.EASE_IN.apply(t) < t, "ease-in is behind linear");
    }

    @Test
    void everyCurveIsMonotonicNonDecreasing() {
        for (Easing e : ALL) {
            float prev = -1;
            for (int i = 0; i <= 20; i++) {
                float v = e.apply(i / 20f);
                assertTrue(v >= prev - 1e-5f, "monotonic: " + v + " after " + prev);
                prev = v;
            }
        }
    }

    @Test
    void springyCurvesPinTheEndpointsButOvershootBetween() {
        for (Easing e : new Easing[] {Easing.RUBBER, Easing.BOUNCE}) {
            assertEquals(0f, e.apply(0f), 1e-4f, "starts at 0");
            assertEquals(1f, e.apply(1f), 1e-4f, "ends at 1");
        }
        // RUBBER is elastic: it must overshoot past 1 somewhere in the middle.
        float maxRubber = 0;
        for (int i = 0; i <= 100; i++) {
            maxRubber = Math.max(maxRubber, Easing.RUBBER.apply(i / 100f));
        }
        assertTrue(maxRubber > 1.01f, "rubber overshoots the target: " + maxRubber);
    }
}

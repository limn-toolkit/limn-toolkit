package limn.animation;

import limn.scene.Scene;
import limn.scene.SystemPreferences;
import limn.scene.SystemPreferences.ColorScheme;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A user who asked the system for less motion gets none from the toolkit: a transition's first frame
 * is its last, a fade ends in the frame it starts, and what says that work goes on steps instead of
 * gliding.
 */
class ReducedMotionTest {

    private static final SystemPreferences REDUCED = new SystemPreferences(ColorScheme.LIGHT, true, false, false);

    private HeadlessUi ui;

    @BeforeEach
    void install() {
        ui = new HeadlessUi();
    }

    @AfterEach
    void uninstall() {
        SystemPreferences.report(SystemPreferences.NONE);
        ui.close();
    }

    private static TransitionTest.Owner attached() {
        TransitionTest.Owner owner = new TransitionTest.Owner();
        new Scene(owner);
        return owner;
    }

    @Test
    void aTransitionsFirstFrameIsItsLast() {
        SystemPreferences.report(REDUCED);
        Transition t = new Transition(attached(), 0).duration(0.2);
        t.to(1f);
        assertFalse(t.isAnimating());
        assertEquals(1f, t.value(), 0f);
    }

    @Test
    void aTransitionAnimatesAgainOnceMotionIsBack() {
        SystemPreferences.report(REDUCED);
        Transition t = new Transition(attached(), 0).duration(0.2);
        t.to(1f);
        SystemPreferences.report(SystemPreferences.NONE);
        t.to(0f);
        assertTrue(t.isAnimating());
    }

    @Test
    void durationsAndFadesEndAtOnce() {
        assertEquals(0.16, Motion.duration(0.16), 0);
        assertEquals(0.5, Motion.progress(0.08, 0.16), 1e-9);
        SystemPreferences.report(REDUCED);
        assertEquals(0, Motion.duration(0.16), 0);
        assertEquals(1, Motion.progress(0.08, 0.16), 0);
        assertEquals(1, Motion.progress(0, 0.16), 0, "a frame of no length does not make NaN");
        assertEquals(1, Motion.progress(0, 0), 0);
    }

    @Test
    void steppedMotionHoldsUntilAQuarterSecondHasPassed() {
        assertEquals(0.016, Motion.stepped(0.016, 0), 0, "every frame moves while nothing is reduced");
        SystemPreferences.report(REDUCED);
        assertEquals(0, Motion.stepped(0.1, 0), 0);
        assertEquals(0, Motion.stepped(0.1, 0.1), 0);
        assertEquals(0.3, Motion.stepped(0.1, 0.2), 1e-9, "the held time moves at once");
    }
}

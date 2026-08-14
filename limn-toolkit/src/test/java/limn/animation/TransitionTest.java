package limn.animation;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticks are driven deterministically by calling {@link Transition#tick(double)}
 * directly (the scene's real ticker is registered but never fired here).
 */
class TransitionTest {

    private ExecutorService workers;
    private UiRuntime runtime;

    /** Minimal owner; wiring it into a Scene gives it a non-null {@code scene()}. */
    static final class Owner extends Widget {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(10, 10);
        }
    }

    private Owner attached() {
        Owner owner = new Owner();
        new Scene(owner); // root gets its scene set in the constructor
        return owner;
    }

    @BeforeEach
    void installRuntime() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
    }

    @AfterEach
    void uninstallRuntime() {
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    @Test
    void easesFromTheCurrentValueToTheTargetOverTheDuration() {
        Transition t = new Transition(attached(), 0).duration(0.2).easing(Easing.LINEAR);
        t.to(1f);
        assertTrue(t.isAnimating());
        assertEquals(0f, t.value(), 1e-4f);

        t.tick(0.1); // half the duration
        assertEquals(0.5f, t.value(), 1e-3f);
        t.tick(0.1); // reaches the end
        assertEquals(1f, t.value(), 1e-4f);
        assertFalse(t.isAnimating(), "settles when it arrives");
    }

    @Test
    void jumpsWhenDisabled() {
        Transition t = new Transition(attached(), 0).duration(0.2).enabled(false);
        t.to(1f);
        assertEquals(1f, t.value(), 0, "disabled → no animation, straight to target");
        assertFalse(t.isAnimating());
    }

    @Test
    void jumpsWhenTheOwnerHasNoScene() {
        Transition t = new Transition(new Owner(), 0).duration(0.2); // detached owner
        t.to(1f);
        assertEquals(1f, t.value(), 0, "headless/detached → final state, no ticker");
        assertFalse(t.isAnimating());
    }

    @Test
    void snapCancelsARunningAnimation() {
        Transition t = new Transition(attached(), 0).duration(0.2).easing(Easing.LINEAR);
        t.to(1f);
        t.tick(0.1);
        t.snap(0.25f);
        assertEquals(0.25f, t.value(), 0);
        assertFalse(t.isAnimating());
        assertFalse(t.tick(0.1), "a stale scheduled tick after snap stops itself");
        assertEquals(0.25f, t.value(), 0);
    }

    @Test
    void reTargetingMidAnimationStartsFromTheCurrentValue() {
        Transition t = new Transition(attached(), 0).duration(0.2).easing(Easing.LINEAR);
        t.to(1f);
        t.tick(0.1); // value 0.5
        t.to(0f);    // reverse from wherever we are
        t.tick(0.1); // half of 0.5 -> 0
        assertEquals(0.25f, t.value(), 1e-3f);
    }

    @Test
    void pausesInsideAHiddenContainerAndResumesWhenShownAgain() {
        Owner parent = new Owner();
        Owner child = new Owner();
        parent.add(child);
        new Scene(parent); // scene propagates to the child
        Transition t = new Transition(child, 0).duration(0.2).easing(Easing.LINEAR);
        t.to(1f);

        t.tick(0.1); // child is showing → advances
        assertEquals(0.5f, t.value(), 1e-3f);

        parent.setVisible(false); // child no longer showing
        assertFalse(t.tick(0.05), "a hidden transition unregisters (pauses) itself");
        assertEquals(0.5f, t.value(), 1e-3f, "frozen while hidden: did not advance");
        assertTrue(t.isAnimating(), "still logically animating, just paused");

        parent.setVisible(true);
        t.value(); // reading during a paint re-arms the paused transition
        t.tick(0.1); // resumes from where it froze
        assertEquals(1f, t.value(), 1e-3f);
    }

    @Test
    void repeatPingPongsBetweenTheEndpoints() {
        Transition t = new Transition(attached(), 0).duration(0.2).easing(Easing.LINEAR).repeat(true);
        t.to(1f);
        t.tick(0.2); // reaches 1, then flips direction
        assertEquals(1f, t.value(), 1e-4f);
        assertTrue(t.isAnimating(), "keeps going because it repeats");
        t.tick(0.1); // now heading 1 -> 0
        assertEquals(0.5f, t.value(), 1e-3f);
        t.tick(0.1); // back at 0
        assertEquals(0f, t.value(), 1e-4f);
        assertTrue(t.isAnimating());
    }
}

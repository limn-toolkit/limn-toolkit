package limn.components;

import limn.animation.Easing;
import limn.animation.Transition;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Transition} driven by the real {@link Scene} frame ticker, end to end. */
class TransitionIntegrationTest extends ComponentTestBase {

    private final AtomicLong clock = new AtomicLong();

    static final class Host extends Widget {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(10, 10);
        }
    }

    @Test
    void animatesToTheTargetAcrossRealFrames() {
        Host host = new Host();
        Scene scene = new Scene(host, clock::get);
        FakeCanvas canvas = new FakeCanvas(10, 10);
        scene.renderFrame(canvas);

        Transition t = new Transition(host).duration(0.1).easing(Easing.LINEAR);
        t.to(1f);
        frame(scene, canvas, 0);  // first tick establishes the frame clock (dt == 0)
        frame(scene, canvas, 50); // half
        assertEquals(0.5f, t.value(), 1e-2f);
        frame(scene, canvas, 50); // done
        assertEquals(1f, t.value(), 1e-3f);
    }

    @Test
    void aTickerThatStartsAnotherAnimationDoesNotConcurrentlyModify() {
        Host host = new Host();
        Scene scene = new Scene(host, clock::get);
        FakeCanvas canvas = new FakeCanvas(10, 10);
        scene.renderFrame(canvas);

        Transition chained = new Transition(host).duration(0.1).easing(Easing.LINEAR);
        AtomicBoolean started = new AtomicBoolean();
        // A ticker that, mid-tick, registers a second animation: must not throw.
        scene.addTicker(dt -> {
            if (started.compareAndSet(false, true)) {
                chained.to(1f); // addTicker from inside tickAnimations
            }
            return false;
        });

        frame(scene, canvas, 16); // runs the outer ticker; chained joins next frame
        assertTrue(started.get());
        frame(scene, canvas, 0);   // chained's first frame (dt == 0, by contract)
        frame(scene, canvas, 100); // chained animation completes
        assertEquals(1f, chained.value(), 1e-3f);
    }

    @Test
    void aTransitionStartedWhileAnotherRunsStillBeginsAtZero() {
        Host host = new Host();
        Scene scene = new Scene(host, clock::get);
        FakeCanvas canvas = new FakeCanvas(10, 10);
        scene.renderFrame(canvas);

        Transition a = new Transition(host).duration(1.0).easing(Easing.LINEAR);
        a.to(1f);
        frame(scene, canvas, 0);   // prime a
        frame(scene, canvas, 100); // a is now mid-flight; the ticker set is non-empty

        Transition b = new Transition(host).duration(1.0).easing(Easing.LINEAR);
        b.to(1f);
        frame(scene, canvas, 100); // b's first frame: must be dt==0, not a full frame
        assertEquals(0f, b.value(), 1e-4f, "starts at its from value even though another animation was running");
        frame(scene, canvas, 100);
        assertTrue(b.value() > 0.05f, "then advances normally");
    }

    private void frame(Scene scene, FakeCanvas canvas, long millis) {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
        scene.renderFrame(canvas);
    }
}

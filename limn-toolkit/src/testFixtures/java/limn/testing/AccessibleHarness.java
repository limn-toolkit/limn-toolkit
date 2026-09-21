package limn.testing;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.concurrent.UiRuntime;
import limn.graphics.Canvas;
import limn.graphics.Font;
import limn.graphics.TextMetrics;
import limn.scene.Change;
import limn.scene.Scene;
import limn.scene.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A widget bound to a headless scene whose window hands out a bridge that keeps what it is
 * given: what a per-shape contract needs to read the tree a real widget publishes and to drive
 * a verb into it the way a platform does. It is the mechanics of the toolkit's own
 * {@code AccessibleComponentTestBase}, without JUnit, so that a contract in these fixtures can run
 * over a widget from any module and, one day, from an application.
 *
 * <p>Time does not pass unless {@link #advanceTime} says it does, for the reason that base gives:
 * a measurement that lands in the middle of a fade depends on the machine, not on the widget.
 * A verb is performed from a thread that is not the UI thread, which is the path a bridge takes
 * (ADR 039 §1.9), and the host must return without blocking it. The scene is 400 by 300, the
 * stub window's size; a subject that wants a smaller box wraps its widget in a
 * {@code SizedBox}.
 */
public final class AccessibleHarness {

    /** The scene's width and height, which are the stub window's. */
    public static final float WIDTH = 400;
    public static final float HEIGHT = 300;

    private final UiRuntime runtime;
    private final long[] nanos = {TimeUnit.SECONDS.toNanos(1)};
    private final Canvas canvas = new MeasuringCanvas();

    /** The window the scene is bound to; its {@link StubWindow#accessibility} is the bridge. */
    public final StubWindow window = new StubWindow();

    /** The bridge, listening from the first frame, holding every tree and event published. */
    public final RecordingAccessibilityBridge bridge = RecordingAccessibilityBridge.listening();

    /** The scene the root is bound in. */
    public final Scene scene;

    /** Every change the scene announced since the last {@link #clearObservations()}. */
    public final List<Change> changes = new ArrayList<>();

    /**
     * Binds {@code root} and renders the first frame, so that {@link #tree()} answers at once.
     *
     * @param runtime the installed runtime, whose queue a performed verb is drained through
     * @param root    what to bind; a widget, or a box around one
     */
    public AccessibleHarness(UiRuntime runtime, Widget root) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(root, "root");
        window.accessibility = bridge;
        scene = new Scene(root, () -> nanos[0]);
        scene.bind(window);
        scene.observeChanges((source, change) -> changes.add(change));
        frame();
        clearObservations();
    }

    /** Renders one frame at the current scene time. */
    public void frame() {
        scene.renderFrame(canvas);
    }

    /**
     * Moves scene time forward in steps no longer than the scene's own tick, damaging
     * {@code damage} before each frame so that an animation on it advances.
     *
     * @param millis how far to move
     * @param damage the widget to invalidate each frame, or null
     */
    public void advanceTime(long millis, Widget damage) {
        long step = (long) (Scene.MAX_TICK_SECONDS * 1000);
        for (long left = millis; left > 0; left -= step) {
            nanos[0] += TimeUnit.MILLISECONDS.toNanos(Math.min(step, left));
            if (damage != null) {
                damage.invalidate();
            }
            frame();
        }
    }

    /** Forgets the events and changes seen so far; the trees stay. */
    public void clearObservations() {
        bridge.events.clear();
        changes.clear();
    }

    /** @return the tree published last */
    public AccessibleTree tree() {
        return bridge.tree();
    }

    /** Gives {@code widget} the keyboard and renders a frame, so the cursor is published. */
    public void focus(Widget widget) {
        scene.requestFocus(widget);
        frame();
    }

    /**
     * Performs a verb the way a platform does: from another thread, through the host, then
     * drains the runtime and renders a frame so the tree shows what the verb did.
     *
     * @param nodeId the node addressed
     * @param action the verb
     * @param arg    its argument, or {@link Accessible.Argument#NONE}
     * @return what the host answered: whether the verb was accepted
     */
    public boolean perform(long nodeId, Accessible.Action action, Accessible.Argument arg) {
        AtomicBoolean accepted = new AtomicBoolean();
        Thread caller = new Thread(
                () -> accepted.set(bridge.host.perform(nodeId, action, arg)), "platform-thread");
        caller.start();
        try {
            caller.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while the host performed " + action, e);
        }
        if (caller.isAlive()) {
            throw new AssertionError("the host must never block its caller; it is still in "
                    + action + " on node " + nodeId);
        }
        runtime.drain();
        frame();
        return accepted.get();
    }

    /**
     * The node named {@code name} in the tree published last.
     *
     * @throws AssertionError with the whole tree when there is none
     */
    public AccessibleNode node(String name) {
        AccessibleTree tree = tree();
        AccessibleNode found = AccessibleTrees.named(tree, name);
        if (found == null) {
            throw new AssertionError("no node named \"" + name + "\" in "
                    + AccessibleTrees.describe(tree));
        }
        return found;
    }

    /**
     * The node with id {@code id} in the tree published last.
     *
     * @throws AssertionError with the whole tree when there is none
     */
    public AccessibleNode node(long id) {
        AccessibleTree tree = tree();
        AccessibleNode found = tree.find(id);
        if (found == null) {
            throw new AssertionError("no node " + id + " in " + AccessibleTrees.describe(tree));
        }
        return found;
    }

    /** @return the tree published last, one line per node, for a message */
    public String describe() {
        return AccessibleTrees.describe(tree());
    }

    /** The null canvas, measuring text with the fixed ruler so that layouts have widths. */
    private static final class MeasuringCanvas extends NoopCanvas {
        MeasuringCanvas() {
            super(WIDTH, HEIGHT);
        }

        @Override
        public TextMetrics measureText(String text, Font font) {
            return TestRulers.FIXED.measure(text, font);
        }
    }
}

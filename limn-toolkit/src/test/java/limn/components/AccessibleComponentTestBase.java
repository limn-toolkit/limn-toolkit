package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.graphics.Canvas;
import limn.scene.Scene;
import limn.scene.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import limn.testing.AccessibleTrees;
import limn.testing.RecordingAccessibilityBridge;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A component bound to a scene whose window hands out a bridge that keeps what it is given: the
 * base every per-component accessibility test in this package is written against.
 *
 * <p>It is deliberately not the scene package's {@code AccessibleTestBase}. That one builds
 * purpose-made probe widgets to test the mechanism — the walk, the difference, the identity rule —
 * and its doubles are package-private to {@code limn.scene}. What a component owes is the opposite
 * question: given the real widget and nothing but its public API, is what reaches the tree true?
 * So there are no probes here. A test drives the component's own setters, renders a frame, and
 * reads the tree the scene published.
 *
 * <p>Nothing here ever constructs a tree or a node. A test that built its own snapshot would be
 * asserting against its own idea of the walk rather than against the walk.
 */
abstract class AccessibleComponentTestBase extends ComponentTestBase {

    /** The scene under test, bound in {@link #bind}. */
    protected Scene scene;

    /**
     * The nanosecond every scene bound here reads, moved only by {@link #advanceTime} and
     * {@link #settleAnimations}.
     *
     * <p><b>Time does not pass in these tests unless a test says it does.</b> What a settle has to
     * guarantee is that every animation has finished before a measurement starts, and a span of
     * wall time does not guarantee it: how far a transition gets depends on how fast the machine
     * happens to be running, which is not a property of anything under test. That is not a
     * hypothetical &mdash; it is the recorded cause of one measurement landing in the middle of a
     * fade on a cold virtual machine, chased and fixed once already for the combo box's popup.
     * Held still, a widget is at the instant the test put it at, on every machine and in every
     * order.
     *
     * <p>It is worth being exact about what this is <em>not</em> for. It is not what made the
     * allocation comparisons flaky; that was the comparison itself, and the fix for it is in
     * {@link AllocationProbe#typicalAllocatedByEach}. Freezing time here without that fix made
     * those failures more frequent rather than less, measured against the wall-clock settle in
     * paired runs.
     */
    private final long[] nanos = {TimeUnit.SECONDS.toNanos(1)};

    /** The window it is bound to, whose {@link StubWindow#accessibility} is the double below. */
    protected StubWindow window;

    /** Every tree and event the scene has published since {@link #bind}. */
    protected RecordingAccessibilityBridge bridge;

    /** The canvas frames are rendered into; the scene's box is this canvas's. */
    protected Canvas canvas;


    /**
     * Binds {@code root} to a 400&nbsp;&times;&nbsp;300 window whose bridge is listening, renders
     * the first frame, and forgets the events that frame produced.
     *
     * @param root the widget under test
     */
    protected void bind(Widget root) {
        bind(root, new StubWindow());
    }

    /**
     * The same, over a window a test chose: for a component whose behaviour depends on what the
     * platform underneath it can do.
     *
     * <p>The case that needs it is a menu. A popup goes into a window of its own wherever the
     * platform can place one, and no headless test can create one — {@link StubWindow#backend()}
     * says so by throwing. A window that answers {@code false} to
     * {@link StubWindow#supportsAbsolutePositioning()} is Wayland, where the toolkit's documented
     * fallback is an in-scene overlay, and that overlay is a thing the tree can be asked about.
     *
     * @param root the widget under test
     * @param over the window to bind it to; its bridge is replaced with the recording one
     */
    protected void bind(Widget root, StubWindow over) {
        bridge = RecordingAccessibilityBridge.listening();
        window = over;
        window.accessibility = bridge;
        canvas = new FakeCanvas(400, 300);
        scene = new Scene(root, () -> nanos[0]);
        scene.bind(window);
        frame();
        bridge.events.clear();
    }

    /**
     * Moves the scene's clock forward and renders a frame for each step of it.
     *
     * <p>In steps no larger than the tick clamp, because a scene clamps how much time one frame
     * may account for: a single jump of a second would advance an animation by
     * {@link Scene#MAX_TICK_SECONDS} and leave the rest of the second unspent.
     *
     * @param millis how much time to spend
     * @param damage the widget to invalidate before each frame, so the frames are real ones, or
     *               {@code null} to let the scene decide what to redraw
     */
    protected void advanceTime(long millis, Widget damage) {
        long step = (long) (Scene.MAX_TICK_SECONDS * 1000);
        for (long left = millis; left > 0; left -= step) {
            nanos[0] += TimeUnit.MILLISECONDS.toNanos(Math.min(step, left));
            if (damage != null) {
                damage.invalidate();
            }
            frame();
        }
    }

    /**
     * Moves time past every transition the toolkit runs and leaves it there, which is what a
     * measurement needs before it is taken.
     *
     * <p>Three seconds covers the longest of them by a wide margin &mdash; a revealed scroll bar
     * holds for over one before it starts to fade &mdash; and the clock is not moved again
     * afterwards, so nothing can expire or animate inside the window being measured. That is the
     * whole point: an allocation figure taken while a fade is mid-flight is a figure for the fade.
     *
     * @param damage the widget to invalidate before each frame
     */
    protected void settleAnimations(Widget damage) {
        advanceTime(3000, damage);
    }

    /** Renders one frame, which is what turns a setter into a published tree. */
    protected void frame() {
        scene.renderFrame(canvas);
    }

    /** @return the tree the bridge is currently holding */
    protected AccessibleTree tree() {
        return bridge.tree();
    }

    /**
     * @param name the accessible name to look for
     * @return the one node carrying it
     * @throws AssertionError when no node does
     */
    protected AccessibleNode node(String name) {
        AccessibleTree tree = tree();
        AccessibleNode found = AccessibleTrees.named(tree, name);
        if (found == null) {
            throw new AssertionError("no node named \"" + name + "\" in " + describe(tree));
        }
        return found;
    }

    /**
     * @param id a node's identifier
     * @return the node carrying it in the newest tree
     * @throws AssertionError with the whole tree when nothing carries it
     */
    protected AccessibleNode node(long id) {
        AccessibleTree tree = tree();
        AccessibleNode found = tree.find(id);
        if (found == null) {
            throw new AssertionError("no node " + id + " in " + describe(tree));
        }
        return found;
    }

    /**
     * @param parent the node to read the children of
     * @return its children, in tree order
     */
    protected List<AccessibleNode> childrenOf(AccessibleNode parent) {
        List<AccessibleNode> found = new ArrayList<>();
        AccessibleTree tree = tree();
        for (int at = parent.firstChild(); at != AccessibleNode.NONE;
                at = tree.node(at).nextSibling()) {
            found.add(tree.node(at));
        }
        return found;
    }

    /**
     * @param role the role to look for
     * @return the one node carrying it
     * @throws AssertionError when no node does, or when more than one does
     */
    protected AccessibleNode node(Accessible.Role role) {
        AccessibleTree tree = tree();
        AccessibleNode found = null;
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) {
                if (found != null) {
                    throw new AssertionError("more than one " + role + " in " + describe(tree));
                }
                found = tree.node(i);
            }
        }
        if (found == null) {
            throw new AssertionError("no " + role + " in " + describe(tree));
        }
        return found;
    }

    /**
     * Asks the scene to perform an action <em>from another thread</em>, as a bridge does on two of
     * the three platforms, and drains the queue the call posts into.
     *
     * @param nodeId the node to act on
     * @param action what to ask of it
     * @param arg    the argument, or {@link Accessible.Argument#NONE}
     * @return whether the scene accepted it, which is not the same as done
     * @throws InterruptedException if the wait for the calling thread is interrupted
     */
    protected boolean perform(long nodeId, Accessible.Action action, Accessible.Argument arg)
            throws InterruptedException {
        AtomicBoolean accepted = new AtomicBoolean();
        Thread caller = new Thread(
                () -> accepted.set(bridge.host.perform(nodeId, action, arg)), "platform-thread");
        caller.start();
        caller.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(caller.isAlive(), "the host must never block its caller");
        runtime.drain();
        return accepted.get();
    }

    /**
     * The same facet published read-only, which is how a node that is not {@code ENABLED}
     * publishes its value: the walk withdraws the {@code SET_VALUE} a writable facet implies from
     * it (semantics 5; ADR 039 §1.5, amended 2026-09-15).
     *
     * @param facet a writable facet
     * @return that facet with {@code readOnly} set
     */
    protected static limn.accessibility.ValueFacet readOnly(limn.accessibility.ValueFacet facet) {
        return new limn.accessibility.ValueFacet(facet.value(), facet.min(), facet.max(),
                facet.step(), facet.text(), true, facet.empty());
    }

    /**
     * @param state the state to look for
     * @return every node carrying it, in tree order
     */
    protected List<AccessibleNode> nodesWith(Accessible.State state) {
        return AccessibleTrees.withState(tree(), state);
    }

    /**
     * @param tree the tree to render as one line per node, for a failure message
     * @return the rendering
     */
    protected static String describe(AccessibleTree tree) {
        return AccessibleTrees.describe(tree);
    }
}

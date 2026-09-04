package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.graphics.Canvas;
import limn.scene.Scene;
import limn.scene.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** The window it is bound to, whose {@link StubWindow#accessibility} is the double below. */
    protected StubWindow window;

    /** Every tree and event the scene has published since {@link #bind}. */
    protected RecordingBridge bridge;

    /** The canvas frames are rendered into; the scene's box is this canvas's. */
    protected Canvas canvas;

    /**
     * A bridge that keeps what it is handed instead of talking to a platform.
     *
     * <p>Every member of {@link AccessibilityBridge} has a default, so a double that answers a
     * listening platform and remembers what arrived is this short. It claims to be listening from
     * the start, because a component test is always asking what an assistive technology would be
     * told; the scene's own gate — that a quiet frame costs nothing when nothing is listening — is
     * the scene package's to prove and is proved there.
     */
    static final class RecordingBridge implements AccessibilityBridge {

        /** What {@link #isListening()} answers. */
        boolean listening = true;

        /** Every tree handed over, newest last. */
        final List<AccessibleTree> published = new ArrayList<>();

        /** Every event handed over, in order. */
        final List<AccessibleEvent> events = new ArrayList<>();

        /**
         * The scene-side object a real bridge calls to perform an action, kept so that a test can
         * stand where a bridge stands. It is the only way in: a component test may not call a
         * widget's hook itself, because half of what the path guarantees — the identifier
         * resolving, the ancestor chain being enabled, the post landing on the thread that owns
         * the tree — happens on the way there.
         */
        Host host;

        @Override
        public boolean isListening() {
            return listening;
        }

        @Override
        public void attach(Host attached) {
            host = attached;
        }

        @Override
        public void detach() {
            host = null;
        }

        @Override
        public void publish(AccessibleTree tree, boolean reentrant) {
            published.add(tree);
        }

        @Override
        public void emit(AccessibleEvent event) {
            events.add(event);
        }

        /** @return the most recently published tree, or the empty one */
        AccessibleTree tree() {
            return published.isEmpty() ? AccessibleTree.EMPTY : published.get(published.size() - 1);
        }

        /**
         * @param type the kind to count
         * @return how many events of that kind have been handed over
         */
        long countOf(AccessibleEvent.Type type) {
            return events.stream().filter(event -> event.type() == type).count();
        }
    }

    /**
     * Binds {@code root} to a 400&nbsp;&times;&nbsp;300 window whose bridge is listening, renders
     * the first frame, and forgets the events that frame produced.
     *
     * @param root the widget under test
     */
    protected void bind(Widget root) {
        bridge = new RecordingBridge();
        window = new StubWindow();
        window.accessibility = bridge;
        canvas = new FakeCanvas(400, 300);
        scene = new Scene(root);
        scene.bind(window);
        frame();
        bridge.events.clear();
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
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).name().equals(name)) {
                return tree.node(i);
            }
        }
        throw new AssertionError("no node named \"" + name + "\" in " + describe(tree));
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
     * @param state the state to look for
     * @return every node carrying it, in tree order
     */
    protected List<AccessibleNode> nodesWith(Accessible.State state) {
        List<AccessibleNode> found = new ArrayList<>();
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).has(state)) {
                found.add(tree.node(i));
            }
        }
        return found;
    }

    /**
     * @param tree the tree to render as one line per node, for a failure message
     * @return the rendering
     */
    protected static String describe(AccessibleTree tree) {
        StringBuilder out = new StringBuilder("\n");
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            out.append("  ").append(i).append(' ').append(node.role())
                    .append(" \"").append(node.name()).append("\" ")
                    .append(node.states())
                    .append(" box=").append(node.x()).append(',').append(node.y())
                    .append(' ').append(node.width()).append('x').append(node.height())
                    .append(" parent=").append(node.parent())
                    .append('\n');
        }
        return out.toString();
    }
}

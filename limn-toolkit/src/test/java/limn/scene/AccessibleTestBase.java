package limn.scene;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.ToggleFacet;
import limn.concurrent.UiRuntime;
import limn.graphics.ShapedText;
import limn.i18n.I18nString;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.RecordingAccessibilityBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A scene bound to a window whose bridge is a double, for every test of the accessible tree.
 *
 * <p>Phase three of this work is the mechanism — the walk, the difference, the identity rule, the
 * publish step and the backend seam — and not what any particular component says about itself,
 * which is a per-component pipeline of its own. So these tests drive purpose-built widgets that
 * describe themselves through the same hooks a component will: a defect in the mechanism fails
 * here, and a defect in what a checkbox says about itself fails in the checkbox's own step.
 */
abstract class AccessibleTestBase {

    /** A widget that says whatever the test told it to, through the hooks a component uses. */
    static class Probe extends Widget {
        Accessible.Role role;
        I18nString name;
        I18nString description;
        ToggleFacet.State toggle;
        Double value;
        boolean valueReadOnly;
        String valueText;
        long valueWitness;
        String text;
        long textWitness;
        int caret;
        int selectionStart;
        int selectionEnd;
        boolean active;
        Boolean selected;
        Accessible.Action[] actions;
        float prefWidth = 40;
        float prefHeight = 20;

        /** What an action asked of this widget, so a test can see the widget itself perform it. */
        final List<String> performed = new ArrayList<>();
        boolean accepts = true;

        Probe() {
        }

        Probe(Accessible.Role role, String name) {
            this.role = role;
            this.name = I18nString.literal(name);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(prefWidth, prefHeight);
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            if (role != null) {
                a.role(role);
            }
            if (name != null) {
                a.name(name);
            }
            if (description != null) {
                a.description(description);
            }
            if (toggle != null) {
                a.toggle(toggle);
            }
            if (value != null) {
                a.value(value, 0, 100, 1, valueReadOnly);
                a.valueText(valueText, valueWitness);
            }
            if (text != null) {
                a.text(text, textWitness, caret, ShapedText.Affinity.DOWNSTREAM,
                        selectionStart, selectionEnd, 1, null, false);
            }
            if (selected != null) {
                a.selectionItem(selected, 1, 1);
            }
            if (active) {
                a.state(Accessible.State.ACTIVE);
            }
            if (actions != null) {
                a.action(actions);
            }
        }

        @Override
        protected boolean onAccessibilityAction(Accessible.Action action,
                                                Accessible.Argument arg) {
            performed.add(action + "(" + arg + ")");
            return accepts;
        }

        @Override
        protected boolean onSyntheticAction(long key, Accessible.Action action,
                                            Accessible.Argument arg) {
            performed.add("child " + key + ": " + action);
            return accepts;
        }
    }

    /** A container that is scaffolding unless a test gives it something to say. */
    static class Group extends Widget {
        @Override
        protected Size onMeasure(Constraints constraints) {
            float width = 0;
            float height = 0;
            for (int i = 0; i < children().size(); i++) {
                Size child = children().get(i).measure(constraints);
                width = Math.max(width, child.width());
                height += child.height();
            }
            return constraints.constrain(width, height);
        }

        @Override
        protected void onLayout() {
            float y = 0;
            for (int i = 0; i < children().size(); i++) {
                Widget child = children().get(i);
                Size size = child.measure(Constraints.loose(width(), height()));
                child.layoutBox(0, y, size.width(), size.height());
                y += size.height();
            }
        }
    }

    protected HeadlessUi ui;
    protected UiRuntime runtime;
    protected final AtomicLong nanos = new AtomicLong();
    protected RecordingWindow window;
    protected RecordingAccessibilityBridge bridge;
    protected Scene scene;
    protected NoopCanvas canvas;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi(nanos::get);
        runtime = ui.runtime();
        canvas = new NoopCanvas(200, 200);
    }

    @AfterEach
    void uninstallRuntime() {
        ui.close();
    }

    /** Binds {@code root} to a window whose bridge is listening, and settles the first frame. */
    protected void bind(Widget root) {
        bind(root, true);
    }

    /**
     * Binds {@code root} to a window with a recording bridge.
     *
     * @param root      the scene's root widget
     * @param listening whether the double claims an assistive technology is reading
     */
    protected void bind(Widget root, boolean listening) {
        bridge = new RecordingAccessibilityBridge();
        bridge.listening = listening;
        window = new RecordingWindow();
        window.accessibility = bridge;
        scene = new Scene(root, nanos::get);
        scene.bind(window);
        frame();
        bridge.events.clear();
        window.frameRequests = 0;
    }

    /** Renders one frame. */
    protected void frame() {
        scene.renderFrame(canvas);
    }

    /** Renders one re-present frame: the same pixels into the other buffer. */
    protected void rePresentFrame() {
        scene.renderFrame(canvas, true);
    }

    /** @return the tree the bridge is currently holding */
    protected AccessibleTree tree() {
        return bridge.tree();
    }

    /**
     * @param name the name to look for
     * @return the node carrying it
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

    /** @param tree the tree to render as one line per node, for a failure message */
    protected static String describe(AccessibleTree tree) {
        StringBuilder out = new StringBuilder("\n");
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            out.append("  ").append(i).append(' ').append(node.role())
                    .append(" \"").append(node.name()).append("\" ")
                    .append(node.states()).append(" parent=").append(node.parent())
                    .append('\n');
        }
        return out.toString();
    }

    /** @return every node carrying a state, in tree order */
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
}

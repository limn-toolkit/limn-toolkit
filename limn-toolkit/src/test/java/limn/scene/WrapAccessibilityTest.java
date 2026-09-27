package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.scene.layout.Wrap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link Wrap} becomes in the accessible tree, which is nothing, and what survives it being
 * nothing.
 *
 * <p>It declares no role, name, action or state, does not paint, does not clip and is never
 * focusable of its own accord, so the predicate {@code RowAccessibilityTest} describes deletes it
 * and hoists its children into its parent's place. What the deletion leaves behind is the boxes:
 * the break into runs, the per-run alignment and the right-to-left reflection are the published
 * {@code x} and {@code y} of every control underneath. The one thing a wrap adds to a row's story
 * is that a child can change runs, which moves it down and back to the leading edge; to a reader
 * that is a move, and the order it reads in is still the order the children were added in.
 *
 * <p>The canvas is 200 points wide, and the three probes are 80, 70 and 60 with a gap of 10, so the
 * first two share a run and the third starts the next.
 */
class WrapAccessibilityTest extends AccessibleTestBase {

    private static Probe probe(String name, float width) {
        Probe probe = new Probe(Accessible.Role.BUTTON, name);
        probe.prefWidth = width;
        return probe;
    }

    private static Wrap threeProbes() {
        Wrap wrap = new Wrap().gap(10).runGap(5);
        wrap.add(probe("first", 80));
        wrap.add(probe("second", 70));
        wrap.add(probe("third", 60));
        return wrap;
    }

    private List<String> readingOrder() {
        List<String> order = new ArrayList<>();
        AccessibleTree tree = tree();
        for (int i = 1; i < tree.nodeCount(); i++) {
            order.add(tree.node(i).name());
        }
        return order;
    }

    @Test
    void theWrapIsNoNodeAndItsChildrenHoistIntoTheWindow() {
        bind(threeProbes());
        frame();

        AccessibleTree tree = tree();
        assertEquals(4, tree.nodeCount(),
                "a window and three buttons, and nothing for the runs: " + describe(tree));
        for (String name : List.of("first", "second", "third")) {
            assertEquals(0, node(name).parent(), name + " hoists into the window's place");
        }
    }

    /**
     * The runs are geometry and not structure: in both directions the tree reads the children in
     * the order they were added, the third one below the other two, and mirroring moves each run to
     * the other edge without turning the order around.
     */
    @Test
    void theReadingOrderIsTheChildOrderRunAfterRunInBothDirections() {
        Wrap wrap = threeProbes();
        bind(wrap);
        frame();

        assertEquals(List.of("first", "second", "third"), readingOrder());
        assertEquals(0f, node("first").x());
        assertEquals(90f, node("second").x());
        assertEquals(0f, node("third").x());
        assertEquals(25f, node("third").y(), "on the second run");

        wrap.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertEquals(List.of("first", "second", "third"), readingOrder(),
                "reading order is logical and does not turn around");
        assertEquals(120f, node("first").x(), "and the boxes are physical, so they do");
        assertEquals(40f, node("second").x());
        assertEquals(140f, node("third").x(), "each run from the right edge");
        assertEquals(25f, node("third").y(), "and the runs still go down");
        assertEquals(80f, node("first").width(), "a reflection is not a resize");
    }

    /** Tab walks the children in the order they were added, across runs, whichever way they read. */
    @Test
    void tabVisitsTheChildrenInTheOrderTheyWereAdded() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            Wrap wrap = threeProbes();
            for (Widget<?> child : wrap.children()) {
                child.setFocusable(true);
            }
            wrap.setLayoutDirection(direction);
            bind(wrap);

            List<Widget<?>> visited = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                scene.focusTraverse(false);
                visited.add(scene.focusedWidget());
            }
            assertEquals(wrap.children(), visited, direction + ": Tab follows the children");
        }
    }

    /**
     * A child pushed onto the next run moves, and a reader holding it keeps holding it: a wider
     * gap sends the second probe down to the third's run, which is a change of bounds and nothing
     * else.
     */
    @Test
    void aChildThatChangesRunIsMovedAndNotRebuilt() {
        Wrap wrap = threeProbes();
        bind(wrap);
        frame();
        long second = node("second").id();
        bridge.clear();

        wrap.gap(60); // 80 + 60 + 70 is past 200; 70 + 60 + 60 is not
        frame();

        assertEquals(0f, node("second").x(), "the second probe opens the second run");
        assertEquals(25f, node("second").y());
        assertEquals(130f, node("third").x(), "and the third follows it there");
        assertEquals(25f, node("third").y());
        assertEquals(second, node("second").id(), "identity is not a coordinate");
        assertNotNull(bridge.first(AccessibleEvent.Type.BOUNDS_CHANGED), "" + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "nothing was added or removed: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), "" + bridge.events);
    }

    /** The escape hatch a row has, unchanged: a named wrap is a group over its own box. */
    @Test
    void namingAWrapMaterialisesAGroupOverItsOwnBox() {
        Wrap wrap = threeProbes();
        bind(wrap);
        frame();
        long first = node("first").id();

        wrap.setAccessibleName("Tags");
        frame();

        AccessibleNode named = node("Tags");
        assertEquals(Accessible.Role.GROUP, named.role());
        assertEquals(wrap.width(), named.width());
        assertEquals(wrap.height(), named.height());
        assertEquals(tree().indexOf(named.id()), node("first").parent(), describe(tree()));
        assertEquals(tree().indexOf(named.id()), node("third").parent(),
                "every run's children, not only the first run's");
        assertEquals(first, node("first").id(), "naming the set is not renaming its members");
        assertTrue(named.has(Accessible.State.SHOWING), describe(tree()));
    }
}

package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The invariant that keeps the tree honest: <b>the nodes published focusable, in tree order, are
 * exactly the sequence the Tab key walks.</b>
 *
 * <p>Stated on that bit and not on enabled, which is a strictly larger set: a label, a scroll bar
 * and a rule are all enabled and none of them is a tab stop, and a screen reader reads far more of
 * a window than a keyboard can land on. Enabled is what may be <em>operated</em>; focusable is what
 * Tab reaches; and if the two ever disagree with the keyboard, the tree is wrong and the keyboard
 * is right.
 */
class AccessibleFocusOrderTest extends AccessibleTestBase {

    private Probe stop(String name) {
        Probe probe = new Probe(Accessible.Role.BUTTON, name);
        probe.setFocusable(true);
        return probe;
    }

    /** Every widget the scene's own traversal reaches, in the order it reaches them. */
    private List<String> tabOrder() {
        List<String> order = new ArrayList<>();
        scene.requestFocus(null);
        for (int i = 0; i < 32; i++) {
            scene.focusTraverse(false);
            Widget<?> focused = scene.focusedWidget();
            if (focused == null) {
                break;
            }
            String name = ((Probe) focused).name.get();
            if (order.contains(name)) {
                break; // wrapped
            }
            order.add(name);
        }
        return order;
    }

    private List<String> publishedFocusOrder() {
        List<String> order = new ArrayList<>();
        for (AccessibleNode node : nodesWith(Accessible.State.FOCUSABLE)) {
            order.add(node.name());
        }
        return order;
    }

    @Test
    void readingOrderIsTabOrder() {
        Group root = new Group();
        Group first = new Group();
        first.add(stop("one"));
        first.add(new Probe(Accessible.Role.LABEL, "a label, not a tab stop"));
        first.add(stop("two"));
        Group second = new Group();
        second.add(stop("three"));
        root.add(first);
        root.add(second);
        bind(root);
        frame();

        assertEquals(List.of("one", "two", "three"), tabOrder());
        assertEquals(tabOrder(), publishedFocusOrder(), describe(tree()));
    }

    /**
     * Reading order is tree order, which is paint order, and never a sort by geometry. A tree
     * sorted on x would be right in a left-to-right interface and backwards in a right-to-left one;
     * the toolkit mirrors by placing rather than by transforming, so after layout every box is
     * physical in both directions and only the tree says what comes first.
     */
    @Test
    void readingOrderIsTreeOrderAndNotGeometricOrder() {
        Group root = new Group();
        root.add(stop("first"));
        root.add(stop("second"));
        bind(root);
        // Place them in the opposite order along x, without touching the tree.
        root.children().get(0).layoutBox(100, 0, 40, 20);
        root.children().get(1).layoutBox(0, 20, 40, 20);
        scene.requestRender();
        frame();

        assertEquals(List.of("first", "second"), publishedFocusOrder());
        assertTrue(node("first").x() > node("second").x(),
                "the later node really is to the left of the earlier one");
    }

    @Test
    void aDisabledContainerTakesEveryControlInsideItOutOfBothSets() {
        Group root = new Group();
        Group form = new Group();
        form.add(stop("name"));
        form.add(stop("email"));
        root.add(form);
        root.add(stop("cancel"));
        bind(root);
        frame();
        assertEquals(List.of("name", "email", "cancel"), publishedFocusOrder());

        form.setEnabled(false);
        frame();

        assertEquals(List.of("cancel"), publishedFocusOrder(), describe(tree()));
        assertFalse(node("name").has(Accessible.State.ENABLED),
                "a control inside a disabled container is disabled, whatever its own flag says");
        assertTrue(node("name").has(Accessible.State.VISIBLE),
                "disabled is not hidden: a reader may still want to read the form");
        assertEquals(tabOrder(), publishedFocusOrder());
    }

    @Test
    void aHiddenContainerTakesEveryControlInsideItOutOfTheVisibleSetToo() {
        Group root = new Group();
        Group page = new Group();
        page.add(stop("hidden"));
        root.add(page);
        root.add(stop("shown"));
        bind(root);
        frame();

        page.setVisible(false);
        frame();

        assertFalse(node("hidden").has(Accessible.State.VISIBLE));
        assertFalse(node("hidden").has(Accessible.State.FOCUSABLE));
        assertEquals(List.of("shown"), publishedFocusOrder());
    }

    /**
     * And the transparency verdict does not move when a container is disabled. Evaluating it on the
     * inherited bits rather than the widget's own declarations would make a disabled form grow a
     * grouping box for every scaffold container inside it, re-shaping the tree on a property change
     * that is not about structure at all.
     */
    @Test
    void aDisabledFormGrowsNoSkeleton() {
        Group root = new Group();
        Group form = new Group();
        Group row = new Group();
        row.add(stop("name"));
        form.add(row);
        root.add(form);
        bind(root);
        frame();
        int before = tree().nodeCount();

        form.setEnabled(false);
        frame();

        assertEquals(before, tree().nodeCount(),
                "disabling a container must not materialise its scaffolding: " + describe(tree()));
    }
}

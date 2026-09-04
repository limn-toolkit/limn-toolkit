package limn.scene;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identity is a function of the widget tree alone, and never of the published one.
 *
 * <p>This is the test the rule exists for, and identity churn is the defect it is written against,
 * because churn is invisible from inside the toolkit: every tree published is correct, every event
 * is correct, and the only symptom is a screen reader whose cursor keeps jumping back to the top of
 * the window for reasons its user cannot see.
 */
class AccessibleIdentityTest extends AccessibleTestBase {

    private List<Long> identifiers() {
        List<Long> found = new ArrayList<>();
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            found.add(tree.node(i).id());
        }
        return found;
    }

    private long idOf(String name) {
        return node(name).id();
    }

    /**
     * A container becoming interesting re-shapes the tree and re-keys nothing. Under a key derived
     * from the published parent this one property change would invalidate every element beneath it
     * at once.
     */
    @Test
    void namingATransparentAncestorChangesTheShapeAndNoIdentifierBelowIt() {
        Group root = new Group();
        Group scaffold = new Group();
        scaffold.add(new Probe(Accessible.Role.BUTTON, "one"));
        scaffold.add(new Probe(Accessible.Role.BUTTON, "two"));
        root.add(scaffold);
        bind(root);
        frame();
        long one = idOf("one");
        long two = idOf("two");
        int shapeBefore = tree().nodeCount();
        bridge.events.clear();

        scaffold.setAccessibleName("Actions");
        frame();

        assertNotEquals(shapeBefore, tree().nodeCount(), "the shape moved");
        assertEquals(one, idOf("one"), "and not one identifier below it");
        assertEquals(two, idOf("two"));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "structural, not a wave of destructions: " + bridge.events);
        assertTrue(bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED) > 0);
    }

    /**
     * The same for a scaffold widget that becomes focusable, which is the one entry point in the
     * toolkit that reaches nothing else at all: without its invalidation the change would surface
     * on some later unrelated repaint, or never.
     */
    @Test
    void makingAScaffoldWidgetFocusableChangesTheShapeAndNoIdentifier() {
        Group root = new Group();
        Group scaffold = new Group();
        scaffold.add(new Probe(Accessible.Role.BUTTON, "one"));
        root.add(scaffold);
        bind(root);
        frame();
        long one = idOf("one");
        int before = tree().nodeCount();
        window.frameRequests = 0;

        scaffold.setFocusable(true);
        assertEquals(1, window.frameRequests, "it has a flag to set and no frame of its own");
        frame();

        assertEquals(before + 1, tree().nodeCount(), describe(tree()));
        assertEquals(one, idOf("one"));
    }

    @Test
    void pushingALayerAboveASubtreeReKeysNothingInIt() {
        Group root = new Group();
        root.add(new Probe(Accessible.Role.BUTTON, "one"));
        bind(root);
        frame();
        long one = idOf("one");

        Group dialog = new Group();
        dialog.add(new Probe(Accessible.Role.BUTTON, "confirm"));
        scene.pushOverlay(dialog);
        frame();

        assertEquals(one, idOf("one"));
    }

    /** Moving a widget in the tree keeps its identity, because it is the same object. */
    @Test
    void movingAWidgetKeepsItsIdentity() {
        Group root = new Group();
        Group left = new Group();
        left.setAccessibleName("left");
        Group right = new Group();
        right.setAccessibleName("right");
        Probe moved = new Probe(Accessible.Role.BUTTON, "moved");
        left.add(moved);
        root.add(left);
        root.add(right);
        bind(root);
        frame();
        long id = idOf("moved");

        left.remove(moved);
        right.add(moved);
        frame();

        assertEquals(id, idOf("moved"),
                "a client holding the element finds it still valid, which is what it would want");
    }

    @Test
    void noTwoNodesShareAnIdentifier() {
        Group root = new Group();
        for (int i = 0; i < 12; i++) {
            root.add(new Probe(Accessible.Role.BUTTON, "button " + i));
        }
        bind(root);
        frame();

        List<Long> all = identifiers();
        Set<Long> unique = new HashSet<>(all);
        assertEquals(all.size(), unique.size(), describe(tree()));
        assertTrue(unique.stream().noneMatch(id -> id == 0), "zero is never an identifier");
    }

    /**
     * A container that pools its children owns their identity, and nothing else can. This is the
     * case the widget-keyed rule cannot serve: a cell recycled from row three to row nine would
     * otherwise tell an assistive technology that row three <em>became</em> row nine.
     */
    @Test
    void aPooledCellKeyedByItsParentCarriesTheDataIndexAndNotTheWidget() {
        Pool pool = new Pool();
        pool.firstRow = 3;
        bind(pool);
        frame();
        long rowThree = node("row 3").id();
        long rowFour = node("row 4").id();
        assertNotEquals(rowThree, rowFour);

        pool.firstRow = 8;                  // scroll: the same two widgets, different data
        pool.markNeedsLayout();
        frame();
        assertEquals(AccessibleNode.NONE, tree().indexOf(rowThree),
                "row three is not on screen, so it is not in the tree: " + describe(tree()));
        assertNotEquals(rowThree, node("row 8").id(),
                "the recycled cell must not carry row three's element to row eight");

        pool.firstRow = 3;                  // and back
        pool.markNeedsLayout();
        frame();

        assertEquals(rowThree, node("row 3").id(),
                "row three's element is the one a client was already holding: " + describe(tree()));
        assertEquals(rowFour, node("row 4").id());
    }

    /** A list that mounts two cells and rebinds them to whatever rows are in view. */
    private static final class Pool extends Widget {
        int firstRow;
        private final Probe[] cells = {new Probe(), new Probe()};

        Pool() {
            for (Probe cell : cells) {
                cell.role = Accessible.Role.LIST_ITEM;
                add(cell);
            }
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(100, 40);
        }

        @Override
        protected void onLayout() {
            for (int i = 0; i < cells.length; i++) {
                cells[i].name = limn.i18n.I18nString.literal("row " + (firstRow + i));
                cells[i].layoutBox(0, i * 20, 100, 20);
            }
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.LIST);
            a.selection(false, false);
        }

        @Override
        protected void onAccessibilityChild(Widget child, Accessibility a) {
            for (int i = 0; i < cells.length; i++) {
                if (cells[i] == child) {
                    a.key(firstRow + i);        // the data index, which is what identity is here
                    a.selectionItem(false, firstRow + i + 1, 100);
                }
            }
        }
    }
}

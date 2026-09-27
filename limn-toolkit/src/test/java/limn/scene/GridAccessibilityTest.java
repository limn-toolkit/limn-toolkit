package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.scene.layout.Grid;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link Grid} becomes in the accessible tree, which is nothing, and what survives it being
 * nothing.
 *
 * <p>It declares no role, name, action or state, does not paint, does not clip and is never
 * focusable of its own accord, so the predicate {@code RowAccessibilityTest} describes deletes it
 * and hoists its children into its parent's place. What the deletion leaves behind is the boxes:
 * the columns, the rows, the alignment within each and the right-to-left reflection are the
 * published {@code x} and {@code y} of every control underneath. The order they are read in is
 * the order they were added in, which is the grid's own order, row by row, so a caption is read
 * just before the field beside it in both directions and nothing has to be told what that order
 * is.
 *
 * <p>The canvas is 200 points wide. Two rows of a caption and a field, captions 40 and 60 wide and
 * fields 70 and 50 with a gap of 10: a caption column of 60 and a field column of 70.
 */
class GridAccessibilityTest extends AccessibleTestBase {

    private static Probe probe(String name, float width) {
        Probe probe = new Probe(Accessible.Role.BUTTON, name);
        probe.prefWidth = width;
        return probe;
    }

    private static Grid form() {
        Grid grid = new Grid(2).gap(10).rowGap(5);
        grid.add(probe("Name", 40));
        grid.add(probe("name field", 70));
        grid.add(probe("Email", 60));
        grid.add(probe("email field", 50));
        return grid;
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
    void theGridIsNoNodeAndItsChildrenHoistIntoTheWindow() {
        bind(form());
        frame();

        AccessibleTree tree = tree();
        assertEquals(5, tree.nodeCount(),
                "a window and four buttons, and nothing for the rows or columns: "
                        + describe(tree));
        for (String name : List.of("Name", "name field", "Email", "email field")) {
            assertEquals(0, node(name).parent(), name + " hoists into the window's place");
        }
    }

    /**
     * The rows and columns are geometry and not structure: in both directions the tree reads the
     * children in the order they were added, each caption before its field, and mirroring moves
     * the first column to the right edge without turning the order around.
     */
    @Test
    void theReadingOrderIsRowByRowInBothDirections() {
        Grid grid = form();
        bind(grid);
        frame();

        List<String> rows = List.of("Name", "name field", "Email", "email field");
        assertEquals(rows, readingOrder());
        assertEquals(0f, node("Name").x());
        assertEquals(70f, node("name field").x(), "the caption column's 60 and the gap");
        assertEquals(0f, node("Email").x());
        assertEquals(25f, node("Email").y(), "on the second row");
        assertEquals(70f, node("email field").x(), "in the column the first field set");

        grid.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertEquals(rows, readingOrder(), "reading order is logical and does not turn around");
        assertEquals(160f, node("Name").x(), "and the boxes are physical, so they do: the caption "
                + "column is at the right edge");
        assertEquals(60f, node("name field").x(), "and the field column to the left of it");
        assertEquals(140f, node("Email").x(),
                "a caption at the start of its column, which is its right edge");
        assertEquals(80f, node("email field").x());
        assertEquals(25f, node("Email").y(), "and the rows still go down");
        assertEquals(40f, node("Name").width(), "a reflection is not a resize");
    }

    /** Tab walks the children in the order they were added, row by row, whichever way they read. */
    @Test
    void tabVisitsTheChildrenRowByRow() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            Grid grid = form();
            for (Widget<?> child : grid.children()) {
                child.setFocusable(true);
            }
            grid.setLayoutDirection(direction);
            bind(grid);

            List<Widget<?>> visited = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                scene.focusTraverse(false);
                visited.add(scene.focusedWidget());
            }
            assertEquals(grid.children(), visited, direction + ": Tab follows the rows");
        }
    }

    /**
     * A wider caption moves every field, and a reader holding one keeps holding it: a column that
     * widens is a change of bounds and nothing else.
     */
    @Test
    void aColumnThatWidensMovesItsNeighboursAndRebuildsNothing() {
        Grid grid = form();
        bind(grid);
        frame();
        long field = node("email field").id();
        bridge.clear();

        grid.gap(40);
        frame();

        assertEquals(100f, node("name field").x(), "the field column moved with the gap");
        assertEquals(100f, node("email field").x());
        assertEquals(field, node("email field").id(), "identity is not a coordinate");
        assertNotNull(bridge.first(AccessibleEvent.Type.BOUNDS_CHANGED), "" + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "nothing was added or removed: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), "" + bridge.events);
    }

    /** The escape hatch a row has, unchanged: a named grid is a group over its own box. */
    @Test
    void namingAGridMaterialisesAGroupOverItsOwnBox() {
        Grid grid = form();
        bind(grid);
        frame();
        long first = node("Name").id();

        grid.setAccessibleName("Account");
        frame();

        AccessibleNode named = node("Account");
        assertEquals(Accessible.Role.GROUP, named.role());
        assertEquals(grid.width(), named.width());
        assertEquals(grid.height(), named.height());
        assertEquals(tree().indexOf(named.id()), node("Name").parent(), describe(tree()));
        assertEquals(tree().indexOf(named.id()), node("email field").parent(),
                "every row's cells, not only the first row's");
        assertEquals(first, node("Name").id(), "naming the set is not renaming its members");
        assertTrue(named.has(Accessible.State.SHOWING), describe(tree()));
    }
}

package limn.scene;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A box of negative size is published as an empty one and never stops the tree.
 *
 * <p>A synthetic child's box is whatever its widget hands over, and a width that is a difference
 * (a list's width less its insets) goes negative while the list has not been laid out. Published
 * as given, the node's next move made the comparison build a rectangle of negative size, which
 * throws: the publish failed on every frame after, and the tree a reader held stayed as it was
 * for as long as the node existed, whatever else changed in the window.
 */
class AccessibleNegativeBoxTest extends AccessibleTestBase {

    /** A list whose one row takes the width and the name the test gives it. */
    private static final class Rows extends Widget<Rows> {
        float rowWidth = 30;
        float rowHeight = 20;
        String rowName = "Open";

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(160, 60);
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.LIST);
            a.name(I18nString.literal("Status"));
            a.child(1);
            a.bounds(6, 6, rowWidth, rowHeight);
            a.role(Accessible.Role.LIST_ITEM);
            a.name(I18nString.literal(rowName));
            a.endChild();
        }

        void change() {
            invalidateAccessible();
            invalidate();
        }
    }

    private AccessibleNode row() {
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == Accessible.Role.LIST_ITEM) {
                return tree.node(i);
            }
        }
        throw new AssertionError("no row in " + describe(tree));
    }

    @Test
    void aBoxThatTurnsNegativeIsPublishedEmptyAndTheTreeGoesOnPublishing() {
        Rows rows = new Rows();
        bind(rows);
        assertEquals(30f, row().width());

        rows.rowWidth = -12;
        rows.change();
        frame();
        AccessibleNode row = row();
        assertEquals(0f, row.width(), "a negative width is published as none" + describe(tree()));
        assertEquals(20f, row.height());
        assertNotNull(row.bounds(), "and a bridge can read it as a rectangle");
        boolean moved = false;
        for (AccessibleEvent event : bridge.events) {
            moved |= event.type() == AccessibleEvent.Type.BOUNDS_CHANGED;
        }
        assertTrue(moved, "its move was told");

        rows.rowName = "Paid";
        rows.rowWidth = -40;
        rows.rowHeight = -3;
        rows.change();
        frame();
        assertEquals("Paid", row().name(), "a later change is still published" + describe(tree()));
        assertEquals(0f, row().height());

        rows.rowName = "Shipped";
        rows.rowWidth = 148;
        rows.rowHeight = 30;
        rows.change();
        frame();
        assertEquals("Shipped", row().name(), describe(tree()));
        assertEquals(148f, row().width());
        assertEquals(30f, row().height());
    }

    @Test
    void aBoxNegativeFromItsFirstWalkDoesNotStopTheNextOne() {
        Rows rows = new Rows();
        rows.rowWidth = -12;
        bind(rows);
        assertEquals(0f, row().width(), describe(tree()));

        rows.rowWidth = -6;
        rows.rowName = "Paid";
        rows.change();
        frame();
        assertEquals("Paid", row().name(), "published" + describe(tree()));

        rows.rowWidth = 148;
        rows.change();
        frame();
        assertEquals(148f, row().width(), describe(tree()));
    }
}

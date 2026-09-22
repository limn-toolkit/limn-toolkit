package limn.scene;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.components.ScrollView;
import limn.i18n.I18nString;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A synthetic child is narrowed by its owner's clipping ancestors as a widget child is (decision
 * 100, 2026-09-22): a row a widget draws below the fold of the scroll pane it sits in publishes
 * without {@code SHOWING}, the rows inside the pane keep it, and with no clipping ancestor the
 * owner's bit reaches every child, wherever the widget put it. Found by the rows contract's
 * invariants over a {@code CalendarView} in a {@code ScrollView} (ADR 045 §8).
 */
class AccessibleClipTest extends AccessibleTestBase {

    /** Five rows of twenty points, and one empty band, drawn by the widget itself. */
    private static final class Rows extends Widget {
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(200, 100);
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.LIST);
            a.name(I18nString.literal("rows"));
            a.selection(false, false);
            for (int i = 0; i < 5; i++) {
                a.child(i + 1);
                a.bounds(0, i * 20, 200, 20);
                a.role(Accessible.Role.LIST_ITEM);
                a.name(I18nString.literal("row " + i));
                a.selectionItem(false, i + 1, 5);
                a.endChild();
            }
            // An empty band on the pane's own edge: no pixels, but not off screen for that.
            a.child(9);
            a.bounds(0, 50, 200, 0);
            a.role(Accessible.Role.GROUP);
            a.name(I18nString.literal("band"));
            a.endChild();
        }
    }

    @Test
    void aRowBeyondTheScrollPaneIsNotShowing() {
        ScrollView scroll = new ScrollView(new Rows());
        bind(boxed(new SizedBox(200, 50, scroll)));
        frame();
        assertTrue(showing("row 0"), "the first row is inside the pane");
        assertTrue(showing("row 2"), "the third row is inside the pane");
        assertFalse(showing("row 3"), "the fourth row lies below the pane's fold");
        assertFalse(showing("row 4"), "and so does the fifth");
        assertTrue(showing("band"), "an empty band on the fold is not sent off screen by its emptiness");
        assertTrue(node("rows").has(Accessible.State.SHOWING), "the owner itself shows");
    }

    @Test
    void scrollingThePaneBringsTheRowBackOnScreen() {
        ScrollView scroll = new ScrollView(new Rows());
        bind(boxed(new SizedBox(200, 50, scroll)));
        frame();
        scroll.scrollTo(0, 50);
        frame();
        assertFalse(showing("row 0"), "the first row scrolled above the pane");
        assertTrue(showing("row 3"), "the fourth row is inside it now");
        assertTrue(showing("row 4"), "and the fifth");
    }

    @Test
    void withNoClippingAncestorEveryChildKeepsItsOwnersBit() {
        bind(boxed(new SizedBox(200, 50, new Rows())));
        frame();
        for (int i = 0; i < 5; i++) {
            assertTrue(showing("row " + i), "row " + i + " keeps its owner's bit: nothing clips");
        }
    }

    /** A root box stretches to the scene; under a column it keeps its size. */
    private static Widget boxed(Widget box) {
        Column root = new Column();
        root.add(box);
        return root;
    }

    private boolean showing(String name) {
        AccessibleNode n = node(name);
        return n.has(Accessible.State.SHOWING);
    }
}

package limn.components;

import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.Stack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the three containers do with a direction, and — just as much the point — what they do not.
 *
 * <p>The end state this pins is deliberately a halfway one: a {@link Row} of children in a
 * right-to-left subtree is in the right order with the right gaps, and each child still paints its
 * own contents left to right, because no widget reads the axis yet. That looks wrong and is
 * supposed to.
 */
class ContainerMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;

    /** Fixed-preferred-size leaf. */
    private static final class Box extends Widget {
        private final float prefWidth;

        Box(float prefWidth) {
            this.prefWidth = prefWidth;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(prefWidth, 20);
        }
    }

    private static void layout(Widget w, float width, float height) {
        w.measure(Constraints.tight(width, height));
        w.layoutBox(0, 0, width, height);
    }

    // ------------------------------------------------------------------- Flex

    @Test
    void aRightToLeftRowRunsFromTheRightEdgeAndKeepsItsGaps() {
        Box first = new Box(30);
        Box second = new Box(40);
        Box third = new Box(20);
        Row row = new Row();
        row.gap(10);
        row.add(first);
        row.add(second);
        row.add(third);
        row.setLayoutDirection(LayoutDirection.RTL);
        layout(row, 200, 20);

        // First child hard against the right edge, then leftwards with the gap between.
        assertEquals(170, first.x(), EPS, "the first child starts at the leading edge");
        assertEquals(120, second.x(), EPS, "30 wide plus a 10 gap to the left of it");
        assertEquals(90, third.x(), EPS, "40 wide plus a 10 gap again");
        assertEquals(200, first.x() + first.width(), EPS, "flush with the right edge");
        assertEquals(10, first.x() - second.x() - second.width(), EPS,
                "the gap survived the reflection");
        assertEquals(10, second.x() - third.x() - third.width(), EPS, "and so did the next one");
    }

    @Test
    void aLeftToRightRowIsUntouched() {
        Box first = new Box(30);
        Box second = new Box(40);
        Row row = new Row();
        row.gap(10);
        row.add(first);
        row.add(second);
        layout(row, 200, 20);

        assertEquals(0, first.x(), EPS);
        assertEquals(40, second.x(), EPS);
    }

    @Test
    void endAlignmentMovesToTheLeadingEdgeWithNoChangeInTheCaller() {
        // Dialog's button row is MainAlignment.END in a Row, which is why it needs no change of
        // its own: a mirrored Row moves Cancel/OK to the leading edge for it.
        Box only = new Box(30);
        Row row = new Row();
        row.mainAlignment(Flex.MainAlignment.END);
        row.add(only);
        row.setLayoutDirection(LayoutDirection.RTL);
        layout(row, 200, 20);

        assertEquals(0, only.x(), EPS, "END is the left edge when reading right to left");
    }

    @Test
    void aColumnIsNotASite() {
        Box first = new Box(30);
        Box second = new Box(30);
        Column column = new Column();
        column.add(first);
        column.add(second);
        column.setLayoutDirection(LayoutDirection.RTL);
        layout(column, 200, 100);

        assertEquals(0, first.x(), EPS, "direction is the reading axis; a column's main axis is not");
        assertEquals(0, second.x(), EPS);
    }

    @Test
    void aRowInheritsTheDirectionRatherThanDeclaringIt() {
        Box child = new Box(30);
        Row inner = new Row();
        inner.add(child);
        Row outer = new Row();
        outer.add(inner);
        outer.setLayoutDirection(LayoutDirection.RTL);
        layout(outer, 200, 20);

        assertEquals(170, inner.x(), EPS, "the inner row is placed at the leading edge...");
        assertEquals(0, child.x(), EPS, "...and mirrors inside its own 30pt box, which is all of it");
    }

    // ---------------------------------------------------------------- Padding

    @Test
    void paddingPlacesTheChildAtTheLeadingInsetAndKeepsTheSameWidth() {
        Box ltrChild = new Box(10);
        Padding ltr = new Padding(new Insets(4, 30, 4, 10), ltrChild);
        layout(ltr, 200, 20);
        assertEquals(10, ltrChild.x(), EPS, "the left inset, reading left to right");
        assertEquals(160, ltrChild.width(), EPS);

        Box rtlChild = new Box(10);
        Padding rtl = new Padding(new Insets(4, 30, 4, 10), rtlChild);
        rtl.setLayoutDirection(LayoutDirection.RTL);
        layout(rtl, 200, 20);
        assertEquals(30, rtlChild.x(), EPS, "the right inset becomes the leading one");
        assertEquals(160, rtlChild.width(), EPS, "and the width is the same, because measure sums");
    }

    // ------------------------------------------------------------------ Stack

    @Test
    void stacksNinePhysicalConstantsStayPhysical() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            Box child = new Box(40);
            Stack stack = new Stack();
            stack.alignment(Stack.Alignment.TOP_RIGHT);
            stack.add(child);
            stack.setLayoutDirection(direction);
            layout(stack, 200, 20);
            assertEquals(160, child.x(), EPS, direction + ": TOP_RIGHT names a corner of the box");
        }
    }

    @Test
    void stacksSixLogicalConstantsFollowTheDirection() {
        Box ltrStart = new Box(40);
        Stack ltr = new Stack();
        ltr.alignment(Stack.Alignment.TOP_START);
        ltr.add(ltrStart);
        layout(ltr, 200, 20);
        assertEquals(0, ltrStart.x(), EPS, "START is the left edge reading left to right");

        Box rtlStart = new Box(40);
        Stack rtl = new Stack();
        rtl.alignment(Stack.Alignment.BOTTOM_START);
        rtl.add(rtlStart);
        rtl.setLayoutDirection(LayoutDirection.RTL);
        layout(rtl, 200, 40);
        assertEquals(160, rtlStart.x(), EPS, "and the right edge reading right to left");
        assertEquals(20, rtlStart.y(), EPS, "the vertical half is untouched by the direction");

        Box rtlEnd = new Box(40);
        Stack end = new Stack();
        end.alignment(Stack.Alignment.CENTER_END);
        end.add(rtlEnd);
        end.setLayoutDirection(LayoutDirection.RTL);
        layout(end, 200, 40);
        assertEquals(0, rtlEnd.x(), EPS, "END is the left edge reading right to left");
        assertEquals(10, rtlEnd.y(), EPS);
    }

    @Test
    void theDefaultAlignmentIsUnchangedSoNoExistingStackMoves() {
        Box child = new Box(40);
        Stack stack = new Stack();
        stack.add(child);
        stack.setLayoutDirection(LayoutDirection.RTL);
        layout(stack, 200, 20);
        assertEquals(0, child.x(), EPS, "TOP_LEFT is still the default and still physical");
    }
}

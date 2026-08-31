package limn.components;

import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a horizontal scroll starts, and which way it runs: {@code scrollX == 0} is the
 * <b>leading</b> edge, which is the right edge in a right-to-left subtree.
 *
 * <p>The alternative — zero stays the left edge and the range goes negative in RTL — was
 * rejected because the web shipped both and the bug reports were all in one direction. The
 * consequence asserted here is that every clamp keeps its form, {@code maxOffsetX()} stays a
 * positive magnitude, and "scrolled to the start" is {@code 0} in both directions, so a widget
 * that resets a scroll on a content change needs no branch and cannot get it wrong.
 *
 * <p>This is pinned before anything consumes it, deliberately: taken late, the convention has to
 * be un-taken in five files.
 */
class ScrollOriginTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;

    /** Fixed-preferred-size leaf. */
    private static final class Box extends Widget {
        private final float prefWidth;
        private final float prefHeight;

        Box(float prefWidth, float prefHeight) {
            this.prefWidth = prefWidth;
            this.prefHeight = prefHeight;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(prefWidth, prefHeight);
        }
    }

    /** Content twice the viewport, laid out at 100x50, in the direction asked for. */
    private static ScrollView scroller(LayoutDirection direction, Box content) {
        ScrollView scroll = new ScrollView(content, true, false);
        scroll.setLayoutDirection(direction);
        scroll.measure(Constraints.tight(100, 50));
        scroll.layoutBox(0, 0, 100, 50);
        return scroll;
    }

    @Test
    void zeroIsTheLeadingEdgeSoRtlContentStartsAgainstTheRightEdge() {
        Box content = new Box(200, 50);
        ScrollView scroll = scroller(LayoutDirection.RTL, content);

        assertEquals(0, scroll.offsetX(), EPS, "a fresh scroller is at the start");
        assertEquals(100, content.x() + content.width(), EPS,
                "the content's right edge sits on the viewport's right edge");
    }

    @Test
    void scrollingForwardInRtlMovesTheContentRight() {
        Box content = new Box(200, 50);
        ScrollView scroll = scroller(LayoutDirection.RTL, content);
        float before = content.x();

        scroll.scrollBy(10, 0);

        assertEquals(10, scroll.offsetX(), EPS, "the offset is a positive magnitude");
        assertEquals(before + 10, content.x(), EPS,
                "advancing the scroll reveals content on the trailing side, which is the left");
    }

    @Test
    void theScrollExtentIsTheSamePositiveNumberInBothDirections() {
        Box ltrContent = new Box(200, 50);
        Box rtlContent = new Box(200, 50);

        assertEquals(100, scroller(LayoutDirection.LTR, ltrContent).maxOffsetX(), EPS);
        assertEquals(100, scroller(LayoutDirection.RTL, rtlContent).maxOffsetX(), EPS,
                "maxOffsetX is a magnitude, not a coordinate; the range stays [0, max]");
    }

    @Test
    void theClampKeepsItsFormAndItsEndsInBothDirections() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            ScrollView scroll = scroller(direction, new Box(200, 50));

            scroll.scrollBy(-10, 0);
            assertEquals(0, scroll.offsetX(), EPS, direction + ": clamped at the start");

            scroll.scrollBy(10_000, 0);
            assertEquals(100, scroll.offsetX(), EPS, direction + ": clamped at the extent");
        }
    }

    @Test
    void revealingARectScrollsTheMinimumInBothDirections() {
        // revealRect is handed the rect in the scroller's own PHYSICAL local coordinates, so the
        // direction is not in the numbers it receives: what changes is which way offsetX moves
        // the content. The content's own position is therefore the direction-free assertion, and
        // it is the same in both — which is the point. Asserted in both directions because the
        // arithmetic that serves one is the arithmetic that breaks the other.
        for (LayoutDirection direction : LayoutDirection.values()) {
            Box content = new Box(200, 50);
            ScrollView scroll = scroller(direction, content);
            scroll.scrollBy(50, 0);
            assertEquals(-50, content.x(), EPS, direction + ": halfway, either way round");

            // A 20pt rect hanging 10pt off the left of the viewport: bring its near edge to 0.
            scroll.revealRect(-10, 0, 20, 50);
            assertEquals(-40, content.x(), EPS, direction + ": scrolled back by exactly 10");

            // And one hanging 15pt off the right: bring its far edge to the far viewport edge.
            scroll.revealRect(95, 0, 20, 50);
            assertEquals(-55, content.x(), EPS, direction + ": scrolled on by exactly 15");
        }
    }
}

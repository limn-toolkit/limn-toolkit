package limn.components;

import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ------------------------------------------------- which side the bar takes

    /** A scroller that overflows vertically, so its vertical bar is real. */
    private static ScrollView vertical(LayoutDirection direction, ScrollGutters.Layout layout) {
        ScrollView scroll = new ScrollView(new Box(100, 400), false, true);
        scroll.setBarLayout(layout);
        scroll.setScrollbarPolicy(ScrollBar.Policy.ALWAYS);
        scroll.setLayoutDirection(direction);
        scroll.measure(Constraints.tight(100, 100));
        scroll.layoutBox(0, 0, 100, 100);
        return scroll;
    }

    @Test
    void theVerticalBarTakesTheSideReadingEndsOn() {
        float t = ScrollBar.thickness();
        ScrollView ltr = vertical(LayoutDirection.LTR, ScrollGutters.Layout.OVERLAY);
        assertEquals(100 - t, ltr.verticalBar().x(), EPS, "the default is unchanged: the right");

        ScrollView rtl = vertical(LayoutDirection.RTL, ScrollGutters.Layout.OVERLAY);
        assertEquals(0, rtl.verticalBar().x(), EPS,
                "reading right to left, the side reading ends on is the left");
    }

    @Test
    void aReservedGutterMovesTheContentOffTheBarRatherThanUnderIt() {
        // The fifth site, and the one a screenshot shows first: under RESERVED the viewport and
        // the box differ by the gutter, so the content has to start after the strip the bar took
        // rather than at the box's own edge.
        float t = ScrollBar.thickness();
        Box content = new Box(100, 400);
        ScrollView rtl = new ScrollView(content, false, true);
        rtl.setBarLayout(ScrollGutters.Layout.RESERVED);
        rtl.setScrollbarPolicy(ScrollBar.Policy.ALWAYS);
        rtl.setLayoutDirection(LayoutDirection.RTL);
        rtl.measure(Constraints.tight(100, 100));
        rtl.layoutBox(0, 0, 100, 100);

        assertEquals(0, rtl.verticalBar().x(), EPS, "the bar is on the left");
        assertEquals(t, content.x(), EPS, "and the content starts after it, not under it");
        assertEquals(100 - t, content.width(), EPS, "keeping the whole viewport");
    }

    @Test
    void theClearCornerSquareIsOnTheBarsOwnSide() {
        // With both bars the horizontal one is shortened so their thumbs never overlap. The
        // square it leaves has to be under the vertical bar, which is not always the right.
        float t = ScrollBar.thickness();
        ScrollView ltr = new ScrollView(new Box(400, 400), true, true);
        ltr.setScrollbarPolicy(ScrollBar.Policy.ALWAYS);
        ltr.measure(Constraints.tight(100, 100));
        ltr.layoutBox(0, 0, 100, 100);

        ScrollView rtl = new ScrollView(new Box(400, 400), true, true);
        rtl.setScrollbarPolicy(ScrollBar.Policy.ALWAYS);
        rtl.setLayoutDirection(LayoutDirection.RTL);
        rtl.measure(Constraints.tight(100, 100));
        rtl.layoutBox(0, 0, 100, 100);

        assertEquals(0, horizontalBarOf(ltr).x(), EPS, "the default is unchanged");
        assertEquals(t, horizontalBarOf(rtl).x(), EPS,
                "the square is under the vertical bar, which has moved to the left");
        assertEquals(horizontalBarOf(ltr).width(), horizontalBarOf(rtl).width(), EPS,
                "and it is the same length either way");
    }

    /** The horizontal bar: the one child of the scroller that is not the content or the v-bar. */
    private static Widget horizontalBarOf(ScrollView scroll) {
        Widget found = null;
        for (Widget child : scroll.children()) {
            if (child instanceof ScrollBar bar && bar != scroll.verticalBar()) {
                found = bar;
            }
        }
        assertTrue(found != null, "the scroller has a horizontal bar");
        return found;
    }
}

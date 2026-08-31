package limn.components;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Icon;
import limn.graphics.Paint;
import limn.input.Keys;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TabbedPane} read right to left: where the header run starts and which way it advances,
 * which end the three overflow controls sit on, which way the scroll chevrons point, where the
 * icon sits inside a tab, and which tab an arrow key selects.
 *
 * <p>Every expectation is arithmetic against {@link #RULER}'s 10pt clusters and the
 * {@link SizeTokens} row rather than a picture, for the reason the bidi tests give: a screenshot
 * is the wrong instrument for a strip that is inside out by one tab width and the right one for
 * nothing here. It also carries the four decisions this widget does <em>not</em> take &mdash; Home
 * and End, the centred alignment, the centred icon+label block and the list chevron &mdash; so a
 * later sweep cannot quietly mirror them.
 */
class TabbedPaneMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final ControlSize STEP = ControlSize.MEDIUM;
    private static final SizeTokens T = SizeTokens.of(STEP);

    /** The strip's extent under the degenerate ruler: {@code lineHeight + 2 * tabPadV}. */
    private static final float STRIP_H =
            RULER.measure("Hg", T.body()).lineHeight() + 2 * T.tabPadV();

    /** A two-glyph title's header: {@code 10 * glyphs + 2 * tabPadH}. */
    private static final float TAB_W = 20 + 2 * T.tabPadH();

    /** Wide enough for three of those with room to spare: the strip does not overflow. */
    private static final float WIDE = 400;

    /** Narrow enough that eight five-glyph headers do not fit: the strip does. */
    private static final float NARROW = 240;

    private TabbedPane tabs;
    private Scene scene;

    // ------------------------------------------------------------------ scaffolding

    private void build(LayoutDirection direction, TabbedPane.TabAlignment alignment) {
        tabs = new TabbedPane().setAlignment(alignment);
        tabs.setControlSize(STEP);
        tabs.setLayoutDirection(direction);
        tabs.addTab("AA", new SizedBox(10, 10));
        tabs.addTab("BB", new SizedBox(10, 10));
        tabs.addTab("CC", new SizedBox(10, 10));
        scene = new Scene(tabs);
        scene.setTextRuler(RULER);
        scene.layoutPass(WIDE, 200);
    }

    /** Eight five-glyph tabs in a pane that cannot hold two of them: the overflow layout. */
    private void buildOverflowing(LayoutDirection direction) {
        tabs = new TabbedPane();
        tabs.setControlSize(STEP);
        tabs.setLayoutDirection(direction);
        for (int i = 1; i <= 8; i++) {
            tabs.addTab("Tab " + i, new SizedBox(10, 10));
        }
        scene = new Scene(tabs);
        scene.setTextRuler(RULER);
        scene.layoutPass(NARROW, 200);
    }

    // The pane adds the viewport and then the three controls, in this order, so these four
    // indices are the pane's own construction order and not a guess about the tree.
    private Widget strip() {
        return tabs.children().get(0);
    }

    private Widget prevButton() {
        return tabs.children().get(1);
    }

    private Widget nextButton() {
        return tabs.children().get(2);
    }

    private Widget listButton() {
        return tabs.children().get(3);
    }

    /** Header {@code index}, in strip-local coordinates: the headers are the strip's children. */
    private Widget header(int index) {
        return strip().children().get(index);
    }

    private void click(float x, float y) {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
    }

    /** Selects and focuses header {@code index} through the pointer, wherever it was placed. */
    private void clickHeader(int index) {
        Widget h = header(index);
        click(strip().x() + h.x() + h.width() / 2, STRIP_H / 2);
    }

    private void press(int key) {
        scene.keyEvent(key, true, false, 0);
        scene.inputBatchEnded();
    }

    private TraceCanvas render(float width) {
        TraceCanvas canvas = new TraceCanvas(width, 200);
        scene.renderFrame(canvas);
        return canvas;
    }

    // ------------------------------------------------------------- the header run

    @Test
    void theHeaderRunStartsOnTheEdgeReadingStartsFromAndAdvancesAwayFromIt() {
        build(LayoutDirection.RTL, TabbedPane.TabAlignment.LEFT);
        assertEquals(WIDE - TAB_W, header(0).x(), EPS, "the first tab is flush with the right");
        assertEquals(WIDE - 2 * TAB_W, header(1).x(), EPS);
        assertEquals(WIDE - 3 * TAB_W, header(2).x(), EPS, "and the run advances leftwards");
    }

    @Test
    void theHeaderRunIsUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR, TabbedPane.TabAlignment.LEFT);
        assertEquals(0, header(0).x(), EPS);
        assertEquals(TAB_W, header(1).x(), EPS);
        assertEquals(2 * TAB_W, header(2).x(), EPS);
    }

    @Test
    void aTrailingAlignedStripLandsOnTheEdgeReadingEndsOn() {
        // The run reflects as a whole, so the physically-named RIGHT is the trailing edge and
        // lands on the left. Pinned because it is the one published meaning this work changes.
        build(LayoutDirection.RTL, TabbedPane.TabAlignment.RIGHT);
        assertEquals(2 * TAB_W, header(0).x(), EPS);
        assertEquals(0, header(2).x(), EPS, "the last tab is flush with the left");

        build(LayoutDirection.LTR, TabbedPane.TabAlignment.RIGHT);
        assertEquals(WIDE - 3 * TAB_W, header(0).x(), EPS);
        assertEquals(WIDE - TAB_W, header(2).x(), EPS);
    }

    @Test
    void aCentredStripDoesNotMoveAndOnlyItsOrderReverses() {
        // CENTER does not mirror: reflecting a centred run about the same centre returns it, so
        // the run occupies exactly the same span and only the tabs inside it change places.
        float start = (WIDE - 3 * TAB_W) / 2;

        build(LayoutDirection.LTR, TabbedPane.TabAlignment.CENTER);
        assertEquals(start, header(0).x(), EPS);
        assertEquals(start + 2 * TAB_W, header(2).x(), EPS);

        build(LayoutDirection.RTL, TabbedPane.TabAlignment.CENTER);
        assertEquals(start + 2 * TAB_W, header(0).x(), EPS, "same span, reversed order");
        assertEquals(start, header(2).x(), EPS);
    }

    @Test
    void thePointerAgreesWithTheMirroredPlacement() {
        // The headers are real child widgets, so the framework's hit test follows their boxes;
        // this is the assertion that says the boxes are where a reader would aim.
        build(LayoutDirection.RTL, TabbedPane.TabAlignment.LEFT);
        click(WIDE - 1.5f * TAB_W, STRIP_H / 2);
        assertEquals(1, tabs.selectedIndex(), "the middle tab is in the middle of the strip");
        click(WIDE - TAB_W / 2, STRIP_H / 2);
        assertEquals(0, tabs.selectedIndex(), "and the first one is under the right edge");
    }

    // ------------------------------------------------------------------ arrow keys

    @Test
    void leftSelectsTheTabThatIsVisuallyToTheLeft() {
        build(LayoutDirection.RTL, TabbedPane.TabAlignment.LEFT);
        clickHeader(1);
        assertEquals(1, tabs.selectedIndex());

        press(Keys.LEFT);
        assertEquals(2, tabs.selectedIndex(), "leftwards is later in the order reading right to left");
        press(Keys.RIGHT);
        assertEquals(1, tabs.selectedIndex());
        press(Keys.RIGHT);
        assertEquals(0, tabs.selectedIndex(), "and rightwards runs back toward the first tab");
    }

    @Test
    void theArrowKeysAreUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR, TabbedPane.TabAlignment.LEFT);
        clickHeader(1);
        press(Keys.LEFT);
        assertEquals(0, tabs.selectedIndex());
        press(Keys.RIGHT);
        assertEquals(1, tabs.selectedIndex());
        press(Keys.RIGHT);
        assertEquals(2, tabs.selectedIndex());
    }

    @Test
    void homeAndEndNameTheEndsOfTheTabOrderInBothDirections() {
        // They do NOT mirror: Home is the first tab and End the last one, whichever side of the
        // screen those two are on. Same reasoning that leaves a slider's Home and End at min
        // and max.
        for (LayoutDirection direction : LayoutDirection.values()) {
            build(direction, TabbedPane.TabAlignment.LEFT);
            clickHeader(1);
            press(Keys.HOME);
            assertEquals(0, tabs.selectedIndex(), direction + ": Home is the first tab");
            press(Keys.END);
            assertEquals(2, tabs.selectedIndex(), direction + ": End is the last one");
        }
    }

    // -------------------------------------------------------------- overflow controls

    @Test
    void theThreeOverflowControlsSwapEndsKeepingTheirOrder() {
        buildOverflowing(LayoutDirection.LTR);
        float button = prevButton().width();
        assertEquals(0, prevButton().x(), EPS);
        assertEquals(NARROW - 2 * button, nextButton().x(), EPS);
        assertEquals(NARROW - button, listButton().x(), EPS);
        assertEquals(button, strip().x(), EPS);

        buildOverflowing(LayoutDirection.RTL);
        assertEquals(NARROW - button, prevButton().x(), EPS, "PREV is on the reading start");
        assertEquals(button, nextButton().x(), EPS);
        assertEquals(0, listButton().x(), EPS, "LIST stays the outermost of the pair");
        assertEquals(2 * button, strip().x(), EPS, "the viewport slides to the other inset");
        assertEquals(NARROW - 3 * button, strip().width(), EPS, "and keeps its width");
    }

    @Test
    void theHeaderRunStartsFlushWithTheStripsLeadingEdgeUnderOverflow() {
        buildOverflowing(LayoutDirection.LTR);
        assertEquals(0, header(0).x(), EPS);

        buildOverflowing(LayoutDirection.RTL);
        assertEquals(strip().width(), header(0).x() + header(0).width(), EPS);
    }

    @Test
    void aScrollExtentIsAPositiveMagnitudeAndZeroIsTheLeadingEdge() {
        // The dead-side test is direction-free because the offset is a distance from the leading
        // edge in both: at rest there is nothing behind the first tab, whichever side it is on.
        for (LayoutDirection direction : LayoutDirection.values()) {
            buildOverflowing(direction);
            assertFalse(prevButton().isEnabled(), direction + ": nothing to scroll back to");
            assertTrue(nextButton().isEnabled(), direction + ": and plenty to scroll toward");
        }
    }

    @Test
    void theNextChevronMovesTheRunTheSameDistanceTheOtherWay() {
        buildOverflowing(LayoutDirection.LTR);
        float step = strip().width() * 0.75f; // SCROLL_STEP_FRACTION of the viewport
        float before = header(0).x();
        click(nextButton().x() + nextButton().width() / 2, STRIP_H / 2);
        assertEquals(before - step, header(0).x(), EPS, "the run travels toward -x");

        buildOverflowing(LayoutDirection.RTL);
        float rtlBefore = header(0).x();
        click(nextButton().x() + nextButton().width() / 2, STRIP_H / 2);
        assertEquals(rtlBefore + step, header(0).x(), EPS, "the same distance, toward +x");

        // The shift is applied immediately (the Scrollable contract re-reads coordinates inside
        // one pass) and recomputed from the offset on the next layout; the two must agree, or a
        // scroll would jump by a content width one frame later.
        float applied = header(0).x();
        scene.relayout();
        scene.layoutPass(NARROW, 200);
        assertEquals(applied, header(0).x(), EPS);
    }

    @Test
    void selectingTheLastTabRevealsItInBothDirections() {
        // The reveal arithmetic is measured in logical distances from the leading edge, so it is
        // correct in both directions once the run is placed and must NOT be flipped a second time.
        for (LayoutDirection direction : LayoutDirection.values()) {
            buildOverflowing(direction);
            tabs.setSelectedIndex(7);
            scene.layoutPass(NARROW, 200);
            Widget last = header(7);
            assertTrue(last.x() >= -EPS,
                    direction + ": the last tab hangs off the left at " + last.x());
            assertTrue(last.x() + last.width() <= strip().width() + EPS,
                    direction + ": the last tab hangs off the right at " + last.x());
        }
    }

    // ---------------------------------------------------------------- the chevrons

    /**
     * The x the two strokes of a chevron meet at: its point. The pane draws its separator first
     * and the three controls in order after it, so the strokes arrive in a fixed order and each
     * one is in its own button's coordinates.
     */
    private static float apexOf(TraceCanvas canvas, int button) {
        return canvas.lines.get(1 + 2 * button)[1];
    }

    @Test
    void theScrollChevronsTurnAroundAndTheListChevronDoesNot() {
        float s = T.tabChevron();

        buildOverflowing(LayoutDirection.LTR);
        float cx = prevButton().width() / 2;
        TraceCanvas ltr = render(NARROW);
        assertEquals(cx - s / 2, apexOf(ltr, 0), EPS, "PREV points toward the start: left");
        assertEquals(cx + s / 2, apexOf(ltr, 1), EPS, "NEXT points toward the end: right");
        assertEquals(cx, apexOf(ltr, 2), EPS, "LIST points down, symmetric about its centre");

        buildOverflowing(LayoutDirection.RTL);
        cx = prevButton().width() / 2;
        TraceCanvas rtl = render(NARROW);
        assertEquals(cx + s / 2, apexOf(rtl, 0), EPS, "the start of the order is now the right");
        assertEquals(cx - s / 2, apexOf(rtl, 1), EPS, "and the end of it is the left");
        assertEquals(cx, apexOf(rtl, 2), EPS,
                "the list chevron is a vertical affordance and does not mirror");
    }

    // ------------------------------------------------------------ the icon and label

    @Test
    void theIconTakesTheSlotReadingStartsFromAndTheBlockDoesNotMove() {
        RecordingIcon icon = new RecordingIcon();
        float blockLeft = T.tabPadH(); // (headerWidth - contentWidth) / 2 reduces to the pad
        float gap = T.tabIconGap();
        float labelW = RULER.measure("AB", T.body()).width();

        buildWithIcon(LayoutDirection.LTR, icon);
        TraceCanvas ltr = render(WIDE);
        assertEquals(blockLeft, icon.paintedX, EPS, "the icon leads, on the left");
        assertEquals(blockLeft + T.tabIconSize() + gap, ltr.textAt(0), EPS,
                "and the label follows it");

        buildWithIcon(LayoutDirection.RTL, icon);
        TraceCanvas rtl = render(WIDE);
        assertEquals(blockLeft, rtl.textAt(0), EPS, "the label is now the left half");
        assertEquals(blockLeft + labelW + gap, icon.paintedX, EPS,
                "and the icon leads from the right of it");

        // The block itself is centred in the tab, so its left edge is a centre and does not move:
        // what mirrors is the order of the two things inside it, not the block.
        assertEquals(blockLeft, Math.min(icon.paintedX, rtl.textAt(0)), EPS);
    }

    /** One two-glyph tab carrying {@code icon}, with content that draws no text of its own. */
    private void buildWithIcon(LayoutDirection direction, Icon icon) {
        tabs = new TabbedPane();
        tabs.setControlSize(STEP);
        tabs.setLayoutDirection(direction);
        tabs.addTab("AB", icon, new SizedBox(10, 10));
        scene = new Scene(tabs);
        scene.setTextRuler(RULER);
        scene.layoutPass(WIDE, 200);
    }

    /** Records where it was asked to paint; never rasterized, so it needs no bitmap. */
    private static final class RecordingIcon implements Icon {
        float paintedX = Float.NaN;

        @Override
        public limn.graphics.Image image(int pixelSize, boolean dark) {
            throw new UnsupportedOperationException("measure-only");
        }

        @Override
        public void paint(Canvas canvas, float x, float y, float size, Color tint, boolean dark) {
            paintedX = x;
        }
    }

    // ------------------------------------------------- the indicator's held edges

    @Test
    void theIndicatorSnapsWhenTheDirectionChangesUnderIt() {
        // The two transitions hold physical edges across frames behind a hand-written key, and
        // ShapedText.matches cannot see this one: a direction change relocates every header at
        // once, so it has to snap the way a scroll does. Without the direction in that key a flip
        // that lands on the same frame as a tab change reads as an ordinary slide.
        build(LayoutDirection.LTR, TabbedPane.TabAlignment.LEFT);
        float inset = T.tabPadH() / 2;
        assertEquals(inset, render(WIDE).barAt(0), EPS, "the indicator starts under tab 0");

        tabs.setLayoutDirection(LayoutDirection.RTL);
        tabs.setSelectedIndex(1);
        scene.layoutPass(WIDE, 200);
        assertEquals(header(1).x() + inset, render(WIDE).barAt(0), EPS,
                "the flip arrives placed, not part-way across the reflected strip");
    }

    @Test
    void anOrdinaryTabChangeStillSlides() {
        // The other half of the key: a tab change taken in ONE direction must still animate, or
        // the fix above would have replaced the slide with a jump everywhere.
        build(LayoutDirection.LTR, TabbedPane.TabAlignment.LEFT);
        float inset = T.tabPadH() / 2;
        render(WIDE);

        tabs.setSelectedIndex(1);
        scene.layoutPass(WIDE, 200);
        float drawn = render(WIDE).barAt(0);
        assertTrue(drawn < header(1).x() + inset - 1,
                "the indicator is still back at tab 0's edge, easing across: " + drawn);
    }

    // --------------------------------------------------------------------- the canvas

    /**
     * Records the x of what was drawn, in the drawing widget's own coordinates. Attribution is by
     * paint order rather than by transform, because {@link FakeCanvas} treats a translate as a
     * no-op and the order a widget paints its children in is fixed.
     */
    private static final class TraceCanvas extends FakeCanvas {
        /** Each stroke as {@code {x1, x2}}; the pane's separator is the first of them. */
        private final List<float[]> lines = new ArrayList<>();
        /** The left edge of each run of text. */
        private final List<Float> texts = new ArrayList<>();
        /** The left edge of each filled bar; the selected-tab indicator is the only one. */
        private final List<Float> bars = new ArrayList<>();

        TraceCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth,
                Paint paint) {
            lines.add(new float[] {x1, x2});
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            texts.add(x);
        }

        @Override
        public void fillRect(float x, float y, float width, float height, Paint paint) {
            bars.add(x);
        }

        /** Unboxed, so an assertion picks the primitive comparison with a tolerance. */
        float textAt(int index) {
            return texts.get(index);
        }

        float barAt(int index) {
            return bars.get(index);
        }
    }
}

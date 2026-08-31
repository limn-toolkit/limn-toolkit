package limn.components;

import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ListView} read right to left: which side the scrollbar's strip is on, where a row's
 * left edge goes once that strip has moved, and the sites that stay where they are.
 *
 * <p>The two are one decision. Under {@link ScrollGutters.Layout#RESERVED} the rows are measured
 * into what the bar leaves, so moving the bar without moving the row origin paints every row
 * under the bar &mdash; a defect a screenshot of a list of grey boxes shows nothing of, which is
 * why every expectation here is arithmetic against the box and {@link ScrollBar#thickness()}.
 */
class ListViewMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final float BOX = 200;
    private static final float HEIGHT = 100;
    private static final float ROW_HEIGHT = 40;
    /** Enough rows to overflow a 100pt viewport several times, so a strip is always reserved. */
    private static final int ROWS = 40;

    private static final float STRIP = ScrollBar.thickness();
    /** Width left for a row once the bar has taken its strip. */
    private static final float VIEWPORT = BOX - STRIP;

    /** A row of fixed height that paints nothing: only its box is ever asserted. */
    private static final class Cell extends Widget {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), ROW_HEIGHT);
        }
    }

    private ListView list;
    private Scene scene;

    private void build(LayoutDirection direction, ScrollGutters.Layout barLayout) {
        list = new ListView(new ListView.Adapter() {
            @Override
            public int rowCount() {
                return ROWS;
            }

            @Override
            public Widget rowAt(int index) {
                return new Cell();
            }
        }).setBarLayout(barLayout).setScrollbarPolicy(ScrollBar.Policy.ALWAYS);
        // ALWAYS so the bar's visibility is not a fade in the middle of a frame: the strips key
        // on overflow rather than on visibility, so this changes no geometry, only the timing.
        list.setLayoutDirection(direction);
        scene = new Scene(list);
        scene.setTextRuler(RULER);
        scene.layoutPass(BOX, HEIGHT);
    }

    /** The list's own vertical bar, found through the tree rather than through new API. */
    private ScrollBar bar() {
        for (Widget child : list.children()) {
            if (child instanceof ScrollBar found) {
                return found;
            }
        }
        throw new AssertionError("the list mounted no scrollbar");
    }

    /** Every mounted row, in no particular order; the bar is not one. */
    private List<Widget> rows() {
        List<Widget> rows = new ArrayList<>();
        for (Widget child : list.children()) {
            if (!(child instanceof ScrollBar)) {
                rows.add(child);
            }
        }
        assertFalse(rows.isEmpty(), "no row was ever mounted");
        return rows;
    }

    // ------------------------------------------------------------ the bar's side

    @Test
    void theReservedStripIsOnTheLeftReadingRightToLeft() {
        build(LayoutDirection.RTL, ScrollGutters.Layout.RESERVED);
        assertEquals(0, bar().x(), EPS, "the bar is on the trailing side, which is the left");
        assertEquals(STRIP, bar().width(), EPS, "and it is still one thickness wide");
    }

    @Test
    void theReservedStripStaysOnTheRightReadingLeftToRight() {
        build(LayoutDirection.LTR, ScrollGutters.Layout.RESERVED);
        assertEquals(BOX - STRIP, bar().x(), EPS, "the default must not have moved");
    }

    // ----------------------------------------------------------- the row origin

    @Test
    void everyRowStartsBeyondTheStripReadingRightToLeft() {
        build(LayoutDirection.RTL, ScrollGutters.Layout.RESERVED);
        for (Widget row : rows()) {
            assertEquals(STRIP, row.x(), EPS, "a row still started at the box's left edge");
            assertEquals(VIEWPORT, row.width(), EPS);
            assertEquals(BOX, row.x() + row.width(), EPS,
                    "reading starts at the box's right edge, with nothing between");
        }
    }

    @Test
    void noRowOverlapsTheBarInEitherDirection() {
        // The whole point of RESERVED, stated as the one inequality that holds both ways round.
        for (LayoutDirection direction : LayoutDirection.values()) {
            build(direction, ScrollGutters.Layout.RESERVED);
            float barLeft = bar().x();
            float barRight = barLeft + bar().width();
            for (Widget row : rows()) {
                float rowLeft = row.x();
                float rowRight = rowLeft + row.width();
                assertTrue(rowRight <= barLeft + EPS || rowLeft >= barRight - EPS,
                        "a row ran under the bar reading " + direction
                                + ": row [" + rowLeft + ", " + rowRight
                                + "] against bar [" + barLeft + ", " + barRight + "]");
            }
        }
    }

    @Test
    void everyRowStartsAtTheOriginReadingLeftToRight() {
        build(LayoutDirection.LTR, ScrollGutters.Layout.RESERVED);
        for (Widget row : rows()) {
            assertEquals(0, row.x(), EPS, "the default must not have moved");
            assertEquals(VIEWPORT, row.width(), EPS);
        }
    }

    @Test
    void anOverlaidBarLeavesTheRowsOnTheOriginBothWaysRound() {
        // OVERLAY reserves nothing, so `box - w` is zero and there is no origin to move: the rows
        // keep the whole box in both directions and only the bar changes sides.
        build(LayoutDirection.RTL, ScrollGutters.Layout.OVERLAY);
        assertEquals(0, bar().x(), EPS);
        for (Widget row : rows()) {
            assertEquals(0, row.x(), EPS);
            assertEquals(BOX, row.width(), EPS);
        }
    }

    @Test
    void theRowOriginSurvivesAScroll() {
        // scrollBy moves the mounted rows by hand and the next pass re-places them, twice when
        // the over-scroll retry fires. Both paths have to keep the same x.
        build(LayoutDirection.RTL, ScrollGutters.Layout.RESERVED);
        list.scrollBy(3 * ROW_HEIGHT + 7);
        scene.renderFrame(new FakeCanvas(BOX, HEIGHT));
        assertTrue(list.firstVisibleIndex() > 0, "the list did not actually scroll");
        for (Widget row : rows()) {
            assertEquals(STRIP, row.x(), EPS, "a row lost its origin across a scroll");
        }
    }

    @Test
    void aPointerOnTheLeadingSideFindsARowAndOneOnTheStripDoesNot() {
        // hitTest is expressed against the placed x, so this is the placement asserted a second
        // way: through the path a click actually takes.
        build(LayoutDirection.RTL, ScrollGutters.Layout.RESERVED);
        List<Widget> rtlRows = rows();
        assertTrue(rtlRows.contains(list.hitTest(BOX - 5, ROW_HEIGHT / 2)),
                "the far right of a right-to-left list is a row");
        assertFalse(rtlRows.contains(list.hitTest(STRIP / 2, ROW_HEIGHT / 2)),
                "and the strip belongs to the bar, not to a row");

        build(LayoutDirection.LTR, ScrollGutters.Layout.RESERVED);
        List<Widget> ltrRows = rows();
        assertTrue(ltrRows.contains(list.hitTest(5, ROW_HEIGHT / 2)),
                "the default must not have moved");
        assertFalse(ltrRows.contains(list.hitTest(BOX - STRIP / 2, ROW_HEIGHT / 2)));
    }

    // ------------------------------------------------- what does NOT mirror here

    @Test
    void thereIsNoHorizontalKeyAxisToMirror() {
        // ListView is absent from the arrow-key decisions because it has no Left/Right handler at
        // all: a list walks up and down. Neither key may ever acquire a meaning here, in either
        // direction, so both are asserted inert rather than merely unmirrored.
        for (LayoutDirection direction : LayoutDirection.values()) {
            build(direction, ScrollGutters.Layout.RESERVED);
            list.setSelectedIndex(1);
            list.requestFocus();
            for (int key : new int[] {Keys.LEFT, Keys.RIGHT}) {
                scene.keyEvent(key, true, false, 0);
                scene.inputBatchEnded();
                assertEquals(1, list.selectedIndex(),
                        "a horizontal key moved the selection reading " + direction);
            }
        }
    }

    @Test
    void homeAndEndStayLogicalReadingRightToLeft() {
        // They name the first and the last row, not a side, so they say the same thing in both
        // directions and mirroring them would make End select the row it is already on.
        build(LayoutDirection.RTL, ScrollGutters.Layout.RESERVED);
        list.requestFocus();

        scene.keyEvent(Keys.END, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(ROWS - 1, list.selectedIndex(), "End is the last row in both directions");

        scene.keyEvent(Keys.HOME, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(0, list.selectedIndex(), "and Home is the first");
    }

    @Test
    void theSelectionRingAndTheViewportClipDoNotMove() {
        // Both are symmetric about the box centre: the ring spans [inset, box - inset] and the
        // clip is the whole box, deliberately and not the viewport. Reflecting either yields
        // itself, so the two frames must be identical drawing for drawing.
        Recording ltr = paintWithSelection(LayoutDirection.LTR);
        Recording rtl = paintWithSelection(LayoutDirection.RTL);

        float inset = Strokes.FOCUS_RING_THIN;
        RoundRect ring = ltr.selectionRing();
        assertEquals(inset, ring.x(), EPS, "the ring starts one half-stroke in from the left");
        assertEquals(BOX - 2 * inset, ring.width(), EPS, "and ends one half-stroke in from right");
        RoundRect mirrored = rtl.selectionRing();
        assertEquals(ring.x(), mirrored.x(), EPS, "the ring is not a reading-axis site");
        assertEquals(ring.width(), mirrored.width(), EPS);

        assertEquals(ltr.clips, rtl.clips, "the clip is the whole box, which reflects to itself");
    }

    private Recording paintWithSelection(LayoutDirection direction) {
        build(direction, ScrollGutters.Layout.RESERVED);
        list.setSelectedIndex(1);
        Recording canvas = new Recording(BOX, HEIGHT);
        scene.renderFrame(canvas);
        return canvas;
    }

    /** Records the geometry of the two calls that must read the same in both directions. */
    private static final class Recording extends FakeCanvas {
        final List<RoundRect> roundRects = new ArrayList<>();
        final List<String> clips = new ArrayList<>();

        Recording(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) {
            roundRects.add(roundRect);
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
            clips.add(x + "," + y + "," + w + "," + h);
        }

        /** The one stroked rect that is a row tall and nearly a box wide. */
        RoundRect selectionRing() {
            float inset = Strokes.FOCUS_RING_THIN;
            RoundRect found = null;
            for (RoundRect candidate : roundRects) {
                if (Math.abs(candidate.width() - (BOX - 2 * inset)) < EPS
                        && Math.abs(candidate.height() - (ROW_HEIGHT - 2 * inset)) < EPS) {
                    found = candidate;
                }
            }
            assertNotNull(found, "the selected row drew no ring: " + roundRects);
            return found;
        }
    }
}

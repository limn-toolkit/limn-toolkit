package limn.components;

import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.input.Keys;
import limn.scene.Insets;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.layout.Padding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScrollBar} read right to left: which end of a horizontal track holds the thumb, which way
 * a track click pages, which way a drag runs, and which side of its strip a vertical thumb hugs.
 *
 * <p>Every expectation is arithmetic against the bar's own constants rather than a picture. A bar
 * whose drag is mirrored but whose thumb is not looks perfectly ordinary in a screenshot for the
 * one instant the shot is taken, and slides away from the pointer the moment a hand touches it.
 *
 * <p>The geometry all comes out of one set of numbers. Both bars are laid out 204pt along their
 * own axis and {@link ScrollBar#thickness()} (15pt) across it, over content 800pt long in a 200pt
 * viewport. So: a 200pt track inside a 2pt margin, a 50pt thumb (a quarter of the track), 150pt of
 * travel, and a maximum offset of 600. Reading left to right the thumb therefore starts at 2 and
 * ends at 152; reading right to left those two ends trade places, and the offset that names each
 * one does not move at all.
 */
class ScrollBarMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    /** The bar's length along its own axis: a 200pt track once both margins are taken. */
    private static final float LONG = 204;
    /** The bar's extent across its own axis. */
    private static final float ACROSS = ScrollBar.thickness();
    private static final float MARGIN = 2;
    /** Where the thumb sits at the leading end of the track. */
    private static final float NEAR = MARGIN;
    /** Where the thumb sits at the trailing end: 2pt of margin plus 150pt of travel. */
    private static final float FAR = 152;
    /** The thumb's own length: a quarter of the 200pt track. */
    private static final float THUMB = 50;
    /** The idle thumb thickness, before any hover widens it. */
    private static final float THIN = 5;
    private static final float MAX_OFFSET = 600;
    private static final float VIEWPORT = 200;

    private Scene scene;

    private static final class Model implements ScrollBar.Model {
        float offset;

        @Override
        public float contentLength() {
            return 800;
        }

        @Override
        public float viewportLength() {
            return VIEWPORT;
        }

        @Override
        public float offset() {
            return offset;
        }

        @Override
        public void setOffset(float value) {
            offset = value; // the host would clamp; recorded raw here, so a sign error shows
        }
    }

    private ScrollBar mount(ScrollBar.Orientation orientation, LayoutDirection direction,
            Model model) {
        ScrollBar bar = new ScrollBar(orientation, model).setPolicy(ScrollBar.Policy.ALWAYS);
        bar.setLayoutDirection(direction);
        scene = new Scene(new Padding(Insets.NONE, bar));
        boolean vertical = orientation == ScrollBar.Orientation.VERTICAL;
        scene.layoutPass(vertical ? ACROSS : LONG, vertical ? LONG : ACROSS);
        return bar;
    }

    /** The rectangle the bar actually paints its thumb into, in the bar's own box. */
    private RoundRect thumb(ScrollBar.Orientation orientation, LayoutDirection direction,
            float offset) {
        Model model = new Model();
        model.offset = offset;
        mount(orientation, direction, model);
        boolean vertical = orientation == ScrollBar.Orientation.VERTICAL;
        ThumbCanvas canvas = new ThumbCanvas(vertical ? ACROSS : LONG, vertical ? LONG : ACROSS);
        scene.renderFrame(canvas);
        assertNotNull(canvas.thumb, "an ALWAYS bar over overflowing content paints its thumb");
        return canvas.thumb;
    }

    /** Captures the one rounded rect a bar paints: its thumb. */
    private static final class ThumbCanvas extends FakeCanvas {
        RoundRect thumb;

        ThumbCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void fillRoundRect(RoundRect roundRect, Paint paint) {
            thumb = roundRect;
        }
    }

    private void press(float x, float y) {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.inputBatchEnded();
    }

    private void release(float x, float y) {
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
    }

    private void dragTo(float x, float y) {
        scene.mouseMoved(x, y);
        scene.inputBatchEnded();
    }

    // ------------------------------------------------- where a horizontal thumb rests

    @Test
    void aHorizontalThumbRestsAtTheRightEndOfItsTrackReadingRightToLeft() {
        // An offset of zero is the LEADING edge in both directions, and reading right to left the
        // leading edge is the right one. The offset stays the same positive magnitude; only the
        // coordinate it maps to knows which way the layout reads.
        assertEquals(FAR, thumb(ScrollBar.Orientation.HORIZONTAL, LayoutDirection.RTL, 0).x(), EPS,
                "an unscrolled right-to-left bar parks its thumb against the right margin");
        assertEquals(NEAR,
                thumb(ScrollBar.Orientation.HORIZONTAL, LayoutDirection.RTL, MAX_OFFSET).x(), EPS,
                "and scrolling to the end walks it all the way to the left margin");
        assertEquals(77,
                thumb(ScrollBar.Orientation.HORIZONTAL, LayoutDirection.RTL, MAX_OFFSET / 2).x(),
                EPS, "half the offset is the middle of the track from either end");
    }

    @Test
    void aHorizontalThumbIsUnchangedReadingLeftToRight() {
        assertEquals(NEAR, thumb(ScrollBar.Orientation.HORIZONTAL, LayoutDirection.LTR, 0).x(), EPS);
        assertEquals(FAR,
                thumb(ScrollBar.Orientation.HORIZONTAL, LayoutDirection.LTR, MAX_OFFSET).x(), EPS);
        assertEquals(77,
                thumb(ScrollBar.Orientation.HORIZONTAL, LayoutDirection.LTR, MAX_OFFSET / 2).x(),
                EPS);
    }

    @Test
    void theTrackAndTheThumbKeepTheirLengthsInBothDirections() {
        // Track length, thumb length and the maximum offset are magnitudes. Mirroring is a
        // placement decision; a magnitude that changed with the direction would be a scaling one.
        RoundRect ltr = thumb(ScrollBar.Orientation.HORIZONTAL, LayoutDirection.LTR, 0);
        RoundRect rtl = thumb(ScrollBar.Orientation.HORIZONTAL, LayoutDirection.RTL, 0);
        assertEquals(THUMB, ltr.width(), EPS);
        assertEquals(THUMB, rtl.width(), EPS, "the same quarter-track thumb, at the other end");
        assertEquals(LONG - MARGIN, rtl.x() + rtl.width(), EPS,
                "and it is flush against the far margin, not merely near it");
    }

    // ----------------------------------------------- what does NOT move on a horizontal bar

    @Test
    void theHorizontalThumbsCrossAxisDoesNotMirror() {
        // The y of a horizontal thumb is a cross-axis coordinate: it says the thumb hugs the
        // bottom of its strip, which is a fact about the strip and not about reading order.
        RoundRect ltr = thumb(ScrollBar.Orientation.HORIZONTAL, LayoutDirection.LTR, 0);
        RoundRect rtl = thumb(ScrollBar.Orientation.HORIZONTAL, LayoutDirection.RTL, 0);
        assertEquals(ACROSS - MARGIN - THIN, ltr.y(), EPS);
        assertEquals(ltr.y(), rtl.y(), EPS, "the thumb stays on the bottom of the strip");
        assertEquals(ltr.height(), rtl.height(), EPS);
    }

    // ---------------------------------------------------- what a VERTICAL bar does and does not

    @Test
    void aVerticalThumbHugsTheOuterEdgeOfItsStripInBothDirections() {
        // The host hangs the strip on the trailing side of the content, which is the left edge
        // reading right to left, so the thumb has to hug the other side of the strip or it floats
        // a strip's width in from the window edge.
        RoundRect ltr = thumb(ScrollBar.Orientation.VERTICAL, LayoutDirection.LTR, 0);
        RoundRect rtl = thumb(ScrollBar.Orientation.VERTICAL, LayoutDirection.RTL, 0);
        assertEquals(ACROSS - MARGIN, ltr.x() + ltr.width(), EPS,
                "left to right the thumb's right edge is one margin in from the strip's right");
        assertEquals(MARGIN, rtl.x(), EPS,
                "right to left its left edge is one margin in from the strip's left");
        assertEquals(ltr.width(), rtl.width(), EPS);
    }

    @Test
    void aVerticalThumbsTravelDoesNotMirror() {
        // A vertical bar's thumbStart is a y. Nothing in a right-to-left layout moves it, and the
        // guard that keeps this true is the same orientation test that makes the horizontal
        // mirroring safe. If a later sweep mirrors the axis instead of the direction, this fails.
        for (float offset : new float[] {0, MAX_OFFSET / 2, MAX_OFFSET}) {
            assertEquals(thumb(ScrollBar.Orientation.VERTICAL, LayoutDirection.LTR, offset).y(),
                    thumb(ScrollBar.Orientation.VERTICAL, LayoutDirection.RTL, offset).y(), EPS,
                    "the vertical thumb sits at the same y at offset " + offset);
        }
        assertEquals(NEAR, thumb(ScrollBar.Orientation.VERTICAL, LayoutDirection.RTL, 0).y(), EPS,
                "an unscrolled vertical bar still starts at the top");
        assertEquals(FAR,
                thumb(ScrollBar.Orientation.VERTICAL, LayoutDirection.RTL, MAX_OFFSET).y(), EPS,
                "and still ends at the bottom");
    }

    @Test
    void aVerticalBarDragsAndPagesTheSameWayInBothDirections() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            Model paging = new Model();
            mount(ScrollBar.Orientation.VERTICAL, direction, paging);
            press(7, 180); // well below the thumb, which spans y 2..52 at offset 0
            release(7, 180);
            assertEquals(VIEWPORT, paging.offset, EPS,
                    "down the track is forward, reading " + direction);

            Model dragged = new Model();
            mount(ScrollBar.Orientation.VERTICAL, direction, dragged);
            press(7, 10); // 8pt into a thumb that starts at y 2
            dragTo(7, 110); // its start is now y 102: 100 of 150 points of travel
            assertEquals(400, dragged.offset, EPS,
                    "and dragging down scrolls forward, reading " + direction);
        }
    }

    // ------------------------------------------------------------------------ the drag

    @Test
    void draggingAMirroredHorizontalThumbFollowsThePointer() {
        Model model = new Model();
        mount(ScrollBar.Orientation.HORIZONTAL, LayoutDirection.RTL, model);
        press(160, 7); // 8pt into a thumb that starts at x 152 with the offset at zero
        dragTo(60, 7); // its start is now x 52: 100 of 150 points of travel, from the right
        assertEquals(400, model.offset, EPS, "leftward is forward reading right to left");

        dragTo(110, 7); // back to x 102: 50 points of travel
        assertEquals(200, model.offset, EPS, "and rightward walks the offset back down");
    }

    @Test
    void draggingAHorizontalThumbIsUnchangedReadingLeftToRight() {
        Model model = new Model();
        mount(ScrollBar.Orientation.HORIZONTAL, LayoutDirection.LTR, model);
        press(10, 7); // 8pt into a thumb that starts at x 2
        dragTo(60, 7);
        assertEquals(200, model.offset, EPS);

        dragTo(110, 7);
        assertEquals(400, model.offset, EPS);
    }

    @Test
    void aMirroredDragKeepsTheOffsetAPositiveMagnitude() {
        // The clamp keeps its form under mirroring: the offset is an extent from the leading
        // edge, so it stays inside [0, maxOffset] however far past either end the pointer runs.
        Model model = new Model();
        mount(ScrollBar.Orientation.HORIZONTAL, LayoutDirection.RTL, model);
        press(160, 7);
        dragTo(0, 7); // past the left margin, which reading right to left is past the end
        assertEquals(MAX_OFFSET, model.offset, EPS, "clamped to the end, not overshot");

        dragTo(203, 7); // and past the right margin, which is past the start
        assertEquals(0, model.offset, EPS, "clamped to zero, never negative");
        assertTrue(model.offset >= 0, "the offset is a magnitude in both directions");
    }

    // ---------------------------------------------------------------------- track paging

    @Test
    void clickingTheTrackOfAMirroredBarPagesTowardThePointer() {
        // The pointer names a side of the thumb, and the side names a direction of travel only
        // after the same mapping the thumb itself uses. Mirror the thumb and not this, and a
        // click on the empty track pages the content away from where it was aimed.
        Model model = new Model();
        mount(ScrollBar.Orientation.HORIZONTAL, LayoutDirection.RTL, model);
        press(20, 7); // left of a thumb that spans x 152..202: forward, reading right to left
        release(20, 7);
        assertEquals(VIEWPORT, model.offset, EPS, "leftward of the thumb is one page forward");

        Model back = new Model();
        back.offset = MAX_OFFSET;
        mount(ScrollBar.Orientation.HORIZONTAL, LayoutDirection.RTL, back);
        press(180, 7); // right of a thumb that now spans x 2..52
        release(180, 7);
        assertEquals(MAX_OFFSET - VIEWPORT, back.offset, EPS, "and rightward is one page back");
    }

    @Test
    void clickingTheTrackIsUnchangedReadingLeftToRight() {
        Model model = new Model();
        mount(ScrollBar.Orientation.HORIZONTAL, LayoutDirection.LTR, model);
        press(180, 7);
        release(180, 7);
        assertEquals(VIEWPORT, model.offset, EPS);

        Model back = new Model();
        back.offset = MAX_OFFSET;
        mount(ScrollBar.Orientation.HORIZONTAL, LayoutDirection.LTR, back);
        press(20, 7);
        release(20, 7);
        assertEquals(MAX_OFFSET - VIEWPORT, back.offset, EPS);
    }
}

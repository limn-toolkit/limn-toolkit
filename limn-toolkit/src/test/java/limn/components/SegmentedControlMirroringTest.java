package limn.components;

import limn.graphics.Font;
import limn.graphics.Paint;
import limn.input.Keys;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SegmentedControl} read right to left: where the segments land, where a click lands, which
 * gutter holds the back chevron, and which way the arrow keys walk.
 *
 * <p>Every expectation is arithmetic against {@link #RULER}'s 10pt glyphs rather than a picture.
 * The whole failure mode this control has under mirroring is a hit test that disagrees with the
 * paint by exactly one mapping, and a screenshot cannot see that at all: it shows a strip that
 * looks right and selects the wrong segment.
 *
 * <p>Three one-glyph segments at MEDIUM, so a segment is {@code max(24, 10 + 2 * segPadH)} wide and
 * the track is three of those. The scene is sized to the track in the placement cases, so the
 * track is not centred away from zero and the coordinates here are the ones a click lands on.
 */
class SegmentedControlMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final SizeTokens T = SizeTokens.of(ControlSize.MEDIUM);
    /** One 10pt label plus its two gutters, floored at the hit target: every segment here. */
    private static final float SEG = Math.max(Strokes.MIN_HIT_TARGET, 10 + 2 * T.segPadH());
    private static final float TRACK = 3 * SEG;
    private static final float HEIGHT = 40;
    /** A label's offset inside its own segment: centred, and so the same in both directions. */
    private static final float LABEL_INSET = (SEG - 10) / 2;

    /** Narrower than the track, so the strip becomes a scrolled viewport with two chevrons. */
    private static final float NARROW = 100;
    /** {@code min(height, trackWidth / 4)}, the control's own rule for a gutter. */
    private static final float GUTTER = Math.min(HEIGHT, NARROW / 4);
    private static final float VIEW_LEFT = GUTTER;
    private static final float VIEW_WIDTH = NARROW - 2 * GUTTER;
    /** Centres of the two physical gutters; the track fills the narrow box, so it starts at 0. */
    private static final float LEFT_GUTTER = VIEW_LEFT / 2;
    private static final float RIGHT_GUTTER = (VIEW_LEFT + VIEW_WIDTH + NARROW) / 2;
    /** Inside the viewport in both directions, and the point every scroll case clicks. */
    private static final float PROBE = 60;

    private SegmentedControl control;
    private Scene scene;

    private void build(LayoutDirection direction, float width) {
        control = new SegmentedControl(List.of("A", "B", "C"));
        control.setLayoutDirection(direction);
        scene = new Scene(control);
        scene.setTextRuler(RULER);
        scene.layoutPass(width, HEIGHT);
    }

    private void click(float localX) {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, localX, HEIGHT / 2);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, localX, HEIGHT / 2);
        scene.inputBatchEnded();
    }

    private void press(int key) {
        scene.keyEvent(key, true, false, 0);
        scene.keyEvent(key, false, false, 0);
        scene.inputBatchEnded();
    }

    private InkCanvas paint(float width) {
        InkCanvas canvas = new InkCanvas(width, HEIGHT);
        scene.renderFrame(canvas);
        return canvas;
    }

    /** Records the ink whose x this control decides: the pill, the labels and the chevrons. */
    private static final class InkCanvas extends FakeCanvas {
        /** {x, y, w, h} per filled round rect: the track first, then the selected pill. */
        final List<float[]> fills = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<Float> labelX = new ArrayList<>();
        /** {x1, y1, x2, y2} per stroke; the chevrons are the only lines this control draws. */
        final List<float[]> lines = new ArrayList<>();
        final List<Paint> linePaints = new ArrayList<>();
        final List<float[]> clips = new ArrayList<>();

        InkCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void fillRoundRect(float x, float y, float w, float h, float radius, Paint paint) {
            fills.add(new float[] { x, y, w, h });
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            labels.add(text);
            labelX.add(x);
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth,
                             Paint paint) {
            lines.add(new float[] { x1, y1, x2, y2 });
            linePaints.add(paint);
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
            clips.add(new float[] { x, y, w, h });
        }

        /** The x a label was drawn at, or NaN when that segment was not drawn at all. */
        float xOf(String label) {
            int at = labels.indexOf(label);
            return at < 0 ? Float.NaN : labelX.get(at);
        }
    }

    // ------------------------------------------------------------------ placement

    @Test
    void theFirstSegmentIsAtTheRightReadingRightToLeft() {
        build(LayoutDirection.RTL, TRACK);
        InkCanvas canvas = paint(TRACK);

        // The strip is placed from the edge reading starts at, so the first segment's cell is the
        // last SEG of the track and the third segment's is the first.
        assertEquals(2 * SEG + LABEL_INSET, canvas.xOf("A"), EPS,
                "the first segment is on the right");
        assertEquals(SEG + LABEL_INSET, canvas.xOf("B"), EPS);
        assertEquals(LABEL_INSET, canvas.xOf("C"), EPS, "and the last is on the left");

        // The pill is the second filled round rect: the track is the first.
        float[] pill = canvas.fills.get(1);
        assertEquals(2 * SEG + T.segInset(), pill[0], EPS, "the pill is on the selected cell");
        assertEquals(SEG - 2 * T.segInset(), pill[2], EPS, "and is one segment wide either way");
    }

    @Test
    void thePlacementIsUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR, TRACK);
        InkCanvas canvas = paint(TRACK);

        assertEquals(LABEL_INSET, canvas.xOf("A"), EPS);
        assertEquals(SEG + LABEL_INSET, canvas.xOf("B"), EPS);
        assertEquals(2 * SEG + LABEL_INSET, canvas.xOf("C"), EPS);

        float[] pill = canvas.fills.get(1);
        assertEquals(T.segInset(), pill[0], EPS);
        assertEquals(SEG - 2 * T.segInset(), pill[2], EPS);
    }

    /**
     * The track is centred in whatever box the parent hands over and the viewport is centred in the
     * track, so neither moves: only what is inside them does. A direction branch added to either
     * would slide the whole control off the layout it was given.
     */
    @Test
    void theTrackAndTheViewportDoNotMove() {
        build(LayoutDirection.LTR, 400);
        InkCanvas ltr = paint(400);
        build(LayoutDirection.RTL, 400);
        InkCanvas rtl = paint(400);

        assertEquals(ltr.fills.get(0)[0], rtl.fills.get(0)[0], EPS, "the track keeps its x");
        assertEquals(ltr.fills.get(0)[2], rtl.fills.get(0)[2], EPS, "and its width");

        // Every clip, not just the viewport's: a direction branch anywhere in the clipping is a
        // strip that reads correctly and is cut off on the wrong side.
        assertEquals(ltr.clips.size(), rtl.clips.size(), "the same clips are pushed either way");
        for (int i = 0; i < ltr.clips.size(); i++) {
            assertEquals(ltr.clips.get(i)[0], rtl.clips.get(i)[0], EPS, "clip " + i + " x");
            assertEquals(ltr.clips.get(i)[2], rtl.clips.get(i)[2], EPS, "clip " + i + " width");
        }
    }

    // ----------------------------------------------------------------- hit testing

    @Test
    void aClickLandsOnTheSegmentDrawnUnderItReadingRightToLeft() {
        build(LayoutDirection.RTL, TRACK);

        click(TRACK - 5);
        assertEquals(0, control.selectedIndex(), "the first segment is the one on the right");
        click(TRACK / 2);
        assertEquals(1, control.selectedIndex());
        click(5);
        assertEquals(2, control.selectedIndex(), "and the last is the one on the left");
    }

    @Test
    void hitTestingIsUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR, TRACK);

        click(5);
        assertEquals(0, control.selectedIndex());
        click(TRACK / 2);
        assertEquals(1, control.selectedIndex());
        click(TRACK - 5);
        assertEquals(2, control.selectedIndex());
    }

    /**
     * The hit test is the exact inverse of the placement: the segment a click selects is the one
     * whose label was drawn under the pointer. This is the failure a screenshot cannot show, so it
     * is asserted as one statement rather than as two coordinates that happen to agree.
     */
    @Test
    void theClickAndThePaintAgreeAboutWhichSegmentIsWhere() {
        build(LayoutDirection.RTL, TRACK);
        InkCanvas canvas = paint(TRACK);

        click(canvas.xOf("C"));
        assertEquals(2, control.selectedIndex(), "the click landed on the label it was aimed at");
    }

    // ------------------------------------------------------------------ arrow keys

    @Test
    void theArrowKeysWalkWithTheStripReadingRightToLeft() {
        build(LayoutDirection.RTL, TRACK);
        scene.requestFocus(control);

        // Left selects the segment drawn on the left, which is the NEXT one reading right to left.
        press(Keys.LEFT);
        assertEquals(1, control.selectedIndex(), "Left moves towards the segment on the left");
        press(Keys.LEFT);
        assertEquals(2, control.selectedIndex());
        press(Keys.LEFT);
        assertEquals(2, control.selectedIndex(), "and stops at the end rather than wrapping");
        press(Keys.RIGHT);
        assertEquals(1, control.selectedIndex(), "Right comes back the other way");
    }

    @Test
    void theArrowKeysAreUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR, TRACK);
        scene.requestFocus(control);

        press(Keys.RIGHT);
        assertEquals(1, control.selectedIndex());
        press(Keys.LEFT);
        assertEquals(0, control.selectedIndex());
        press(Keys.LEFT);
        assertEquals(0, control.selectedIndex(), "and stops at the end rather than wrapping");
    }

    /**
     * Home and End name the first and last <em>segment</em> and not a side, so they do not mirror:
     * End selects the last segment in both directions, which reading right to left is the one drawn
     * on the left. A sweep that mirrored them would make End select whatever is under the right
     * edge, which is the first segment, and the two ends of the strip would become unreachable.
     */
    @Test
    void homeAndEndNameASegmentAndDoNotMirror() {
        build(LayoutDirection.RTL, TRACK);
        scene.requestFocus(control);

        press(Keys.END);
        assertEquals(2, control.selectedIndex(), "End is the last segment in both directions");
        press(Keys.HOME);
        assertEquals(0, control.selectedIndex(), "and Home the first");

        // The segment End names is the one drawn on the LEFT here: the same one a click on the
        // left edge selects. The key stayed logical and the strip moved under it.
        press(Keys.END);
        int fromKey = control.selectedIndex();
        click(5);
        assertEquals(fromKey, control.selectedIndex(),
                "End and the left edge are the same segment");
    }

    // -------------------------------------------------------------------- chevrons

    /**
     * The back chevron sits in the gutter reading starts from, so at rest &mdash; when there is
     * nothing behind the strip to scroll back to &mdash; the disabled arrow is the left one
     * reading left to right and the right one reading right to left.
     */
    @Test
    void theDeadChevronIsInTheGutterReadingStartsFrom() {
        build(LayoutDirection.LTR, NARROW);
        assertEquals(LEFT_GUTTER, deadChevronCentre(paint(NARROW)), EPS,
                "left to right, the arrow with nothing behind it is on the left");

        build(LayoutDirection.RTL, NARROW);
        assertEquals(RIGHT_GUTTER, deadChevronCentre(paint(NARROW)), EPS,
                "right to left, it is on the right");
    }

    /**
     * The chevrons' ink does not mirror: each arrow points away from the viewport it sits beside,
     * in both directions. What mirrors is which of them scrolls back, and that is a meaning rather
     * than a shape &mdash; an arrow drawn pointing into the strip would be pointing at the content
     * it takes away.
     */
    @Test
    void bothChevronsAlwaysPointAwayFromTheStrip() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            build(direction, NARROW);
            InkCanvas canvas = paint(NARROW);
            assertEquals(4, canvas.lines.size(), direction + " draws two strokes per chevron");

            assertTrue(tipNear(canvas, LEFT_GUTTER) < LEFT_GUTTER,
                    direction + " should point the left gutter's arrow left");
            assertTrue(tipNear(canvas, RIGHT_GUTTER) > RIGHT_GUTTER,
                    direction + " should point the right gutter's arrow right");
        }
    }

    /**
     * The centre of the one chevron drawn in the disabled ink; fails unless there is exactly one.
     */
    private static float deadChevronCentre(InkCanvas canvas) {
        Paint dead = Theme.current().disabledText;
        float centre = Float.NaN;
        int found = 0;
        for (int i = 0; i < canvas.lines.size(); i++) {
            if (!dead.equals(canvas.linePaints.get(i))) {
                continue;
            }
            found++;
            float[] line = canvas.lines.get(i);
            centre = (line[0] + line[2]) / 2;
        }
        assertEquals(2, found, "exactly one chevron is dead at rest, and it is two strokes");
        return centre;
    }

    /**
     * The x of the tip of the chevron centred on {@code cx}: the point both of its strokes meet at,
     * which is the one on the vertical centre line.
     */
    private static float tipNear(InkCanvas canvas, float cx) {
        for (float[] line : canvas.lines) {
            if (Math.abs((line[0] + line[2]) / 2 - cx) > EPS) {
                continue;
            }
            return line[1] == HEIGHT / 2 ? line[0] : line[2];
        }
        throw new AssertionError("no chevron was drawn at " + cx);
    }

    // --------------------------------------------------------------------- scroll

    /**
     * A chevron scrolls the strip the way it points, and which gutter that is has swapped: the left
     * gutter is the forward arrow reading right to left, so a click there advances the strip and
     * the point that was over the first segment is over a later one.
     */
    @Test
    void theLeftGutterScrollsForwardReadingRightToLeft() {
        build(LayoutDirection.RTL, NARROW);
        click(PROBE);
        assertEquals(0, control.selectedIndex(), "unscrolled, the probe is over the first segment");

        click(LEFT_GUTTER);
        scene.layoutPass(NARROW, HEIGHT);
        click(PROBE);
        assertEquals(1, control.selectedIndex(), "the strip advanced under the probe");
    }

    @Test
    void theGuttersAreUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR, NARROW);

        click(LEFT_GUTTER); // the BACK arrow here, and there is nothing behind the strip yet
        scene.layoutPass(NARROW, HEIGHT);
        click(PROBE);
        assertEquals(0, control.selectedIndex(), "a dead back arrow moves nothing");

        click(RIGHT_GUTTER); // and the forward one is the right gutter, as it always was
        scene.layoutPass(NARROW, HEIGHT);
        click(PROBE);
        assertEquals(1, control.selectedIndex(), "the strip advanced under the probe");
    }

    /**
     * The wheel is a physical gesture and its sign does not mirror. The strip's origin already
     * turns an advancing offset into the opposite physical movement, so one detent advances the
     * strip by the same amount either way; flipping the sign here too would scroll backwards.
     */
    @Test
    void oneWheelDetentAdvancesTheStripByTheSameAmountInBothDirections() {
        int[] afterDetent = new int[2];
        LayoutDirection[] directions = { LayoutDirection.LTR, LayoutDirection.RTL };
        for (int i = 0; i < directions.length; i++) {
            build(directions[i], NARROW);
            scene.scrolled(0, -1, PROBE, HEIGHT / 2);
            scene.inputBatchEnded();
            scene.layoutPass(NARROW, HEIGHT);
            click(PROBE);
            afterDetent[i] = control.selectedIndex();
        }
        assertEquals(1, afterDetent[0], "one detent moves the strip on by one segment's worth");
        assertEquals(afterDetent[0], afterDetent[1],
                "and the same detent does the same either way");
    }
}

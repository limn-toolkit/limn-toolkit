package limn.components;

import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.RoundRect;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Checkbox} read right to left: which edge the indicator sits on, where the label starts,
 * which end of a switch track OFF rests at, and the two things that must <em>not</em> move — the
 * check mark, and the arrow keys this widget has never had.
 *
 * <p>Every expectation is arithmetic against the tokens and {@link #RULER}'s 10pt clusters rather
 * than a picture. A row that is inside out is obvious in a screenshot; a check mark that has been
 * reflected inside a correctly placed box is not, and it is exactly the kind of thing a later
 * sweep over the paint code does by accident.
 */
class CheckboxMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final float WIDTH = 200;
    private static final float HEIGHT = 40;
    private static final String LABEL = "option";
    /** {@link #RULER} is 10pt per code point, so the label's width is an exact integer. */
    private static final float LABEL_WIDTH = 10 * LABEL.length();

    private final AtomicLong clock = new AtomicLong();
    private Checkbox checkbox;
    private Scene scene;
    private InkCanvas canvas;
    private SizeTokens t;

    private void build(Checkbox.Variant variant, LayoutDirection direction, boolean checked) {
        checkbox = new Checkbox(variant, LABEL);
        // Toggled before the widget has a scene, where setChecked snaps instead of easing: the
        // geometry below is then the settled one, and no frame budget decides it.
        checkbox.setChecked(checked);
        checkbox.setLayoutDirection(direction);
        scene = new Scene(checkbox, clock::get);
        scene.setTextRuler(RULER);
        canvas = new InkCanvas(WIDTH, HEIGHT);
        scene.renderFrame(canvas); // initial layout + paint; the root fills the canvas
        t = Theme.current().tokensFor(checkbox);
    }

    private void frame(long millis) {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
        scene.renderFrame(canvas);
    }

    private void click(float x) {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, HEIGHT / 2);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, HEIGHT / 2);
        scene.inputBatchEnded();
    }

    // ------------------------------------------------------------- the box, and its label

    @Test
    void theBoxAndItsLabelAreUnchangedReadingLeftToRight() {
        build(Checkbox.Variant.BOX, LayoutDirection.LTR, false);
        assertEquals(0, canvas.firstFill().x(), EPS, "the box is flush with the left edge");
        assertEquals(Strokes.HALF_PIXEL_INSET, canvas.firstStroke().x(), EPS,
                "and its border takes the same origin, half a pixel in");
        assertEquals(t.indicator() + t.gapLabel(), canvas.textX, EPS,
                "the label starts one gap past the box");
    }

    @Test
    void theBoxSitsOnTheFarEdgeAndTheLabelRunsBackFromItReadingRightToLeft() {
        build(Checkbox.Variant.BOX, LayoutDirection.RTL, false);
        float left = WIDTH - t.indicator();
        assertEquals(left, canvas.firstFill().x(), EPS, "the box is flush with the right edge");
        assertEquals(left + Strokes.HALF_PIXEL_INSET, canvas.firstStroke().x(), EPS,
                "the border moved with the fill; a border left behind separates from its box");

        // drawText places a line by its LEFT edge, so the label's own x is a whole label width
        // back from where reading starts.
        assertEquals(left - t.gapLabel() - LABEL_WIDTH, canvas.textX, EPS);
        assertEquals(left - t.gapLabel(), canvas.textX + LABEL_WIDTH, EPS,
                "the label ends exactly one gap short of the box, and never under it");
    }

    @Test
    void theFocusRingFollowsTheIndicatorToTheEdgeItSitsOn() {
        build(Checkbox.Variant.BOX, LayoutDirection.RTL, false);
        scene.requestFocus(checkbox);
        frame(0);
        frame(40);
        frame(40); // part way through a 140ms fade: the ring is painted, alpha aside

        float gap = Strokes.FOCUS_GAP_INDICATOR;
        RoundRect ring = canvas.strokeOfWidth(t.indicator() + 2 * gap);
        assertNotNull(ring, "the focus ring was never painted: " + canvas.strokes);
        assertEquals(WIDTH - t.indicator() - gap, ring.x(), EPS,
                "a ring still hugging x == 0 would be a rectangle around empty ground");
    }

    @Test
    void theFocusRingIsUnchangedReadingLeftToRight() {
        build(Checkbox.Variant.BOX, LayoutDirection.LTR, false);
        scene.requestFocus(checkbox);
        frame(0);
        frame(40);
        frame(40);

        float gap = Strokes.FOCUS_GAP_INDICATOR;
        RoundRect ring = canvas.strokeOfWidth(t.indicator() + 2 * gap);
        assertNotNull(ring, "the focus ring was never painted: " + canvas.strokes);
        assertEquals(-gap, ring.x(), EPS);
    }

    // --------------------------------------------- the check mark DOES NOT MIRROR

    @Test
    void theCheckMarkIsCarriedWithTheBoxAndNeverReflectedInsideIt() {
        build(Checkbox.Variant.BOX, LayoutDirection.LTR, true);
        List<float[]> ltr = canvas.pathPoints;
        assertEquals(3, ltr.size(), "the tick is three points");

        build(Checkbox.Variant.BOX, LayoutDirection.RTL, true);
        List<float[]> rtl = canvas.pathPoints;
        assertEquals(3, rtl.size());

        // The whole tick is the left-to-right tick plus the box's new left edge. Reflecting it
        // instead would keep the same three x values within the box and reverse their order, which
        // is what "no platform mirrors a tick" forbids.
        float left = WIDTH - t.indicator();
        for (int i = 0; i < 3; i++) {
            assertEquals(ltr.get(i)[0] + left, rtl.get(i)[0], EPS,
                    "point " + i + " is translated, not reflected");
            assertEquals(ltr.get(i)[1], rtl.get(i)[1], EPS, "point " + i + " did not move vertically");
        }
        assertTrue(rtl.get(0)[0] < rtl.get(1)[0] && rtl.get(1)[0] < rtl.get(2)[0],
                "the short arm still starts on the left of the long one");
    }

    // ------------------------------------------------------------------ the switch

    @Test
    void theSwitchTrackAndItsOffThumbAreUnchangedReadingLeftToRight() {
        build(Checkbox.Variant.SWITCH, LayoutDirection.LTR, false);
        assertEquals(0, canvas.firstFill().x(), EPS, "the track is flush with the left edge");
        assertEquals(thumbRadius() + t.switchThumbInset(), canvas.thumbX(), EPS,
                "OFF rests at the low end of the travel, which is the left");
    }

    @Test
    void theSwitchOnThumbIsUnchangedReadingLeftToRight() {
        build(Checkbox.Variant.SWITCH, LayoutDirection.LTR, true);
        assertEquals(t.switchTrackW() - t.switchThumbInset() - thumbRadius(), canvas.thumbX(), EPS);
    }

    @Test
    void theSwitchTrackMovesToTheFarEdgeReadingRightToLeft() {
        build(Checkbox.Variant.SWITCH, LayoutDirection.RTL, false);
        assertEquals(WIDTH - t.switchTrackW(), canvas.firstFill().x(), EPS);
        assertEquals(WIDTH - t.switchTrackW() + Strokes.HALF_PIXEL_INSET,
                canvas.firstStroke().x(), EPS, "the track's outline moved with its fill");
    }

    @Test
    void theOffThumbRestsAtTheLeadingEndOfTheTrackWhicheverEndThatIs() {
        // The thumb's travel is a value axis, so OFF is the leading end: the right one here. A
        // switch whose thumb sat at the left when off would read as ON to anyone reading the row.
        build(Checkbox.Variant.SWITCH, LayoutDirection.RTL, false);
        assertEquals(WIDTH - t.switchThumbInset() - thumbRadius(), canvas.thumbX(), EPS);
        assertTrue(canvas.thumbX() > WIDTH - t.switchTrackW() / 2,
                "OFF is on the leading half of the track, which reading right to left is the right");

        build(Checkbox.Variant.SWITCH, LayoutDirection.RTL, true);
        assertEquals(WIDTH - t.switchTrackW() + t.switchThumbInset() + thumbRadius(),
                canvas.thumbX(), EPS, "ON is the other end of the same travel");
    }

    @Test
    void theThumbTravelsTheSameDistanceInBothDirections() {
        build(Checkbox.Variant.SWITCH, LayoutDirection.LTR, false);
        float ltrOff = canvas.thumbX();
        build(Checkbox.Variant.SWITCH, LayoutDirection.LTR, true);
        float ltrTravel = canvas.thumbX() - ltrOff;

        build(Checkbox.Variant.SWITCH, LayoutDirection.RTL, false);
        float rtlOff = canvas.thumbX();
        build(Checkbox.Variant.SWITCH, LayoutDirection.RTL, true);
        float rtlTravel = rtlOff - canvas.thumbX();

        assertEquals(ltrTravel, rtlTravel, EPS,
                "the ends swapped; the distance between them is not a direction's business");
    }

    private float thumbRadius() {
        return t.switchTrackH() / 2 - t.switchThumbInset();
    }

    // ------------------------------------- what must not move: measure, keys, pointer

    @Test
    void theMeasuredSizeDoesNotDependOnTheDirection() {
        build(Checkbox.Variant.BOX, LayoutDirection.LTR, false);
        Size ltr = checkbox.measure(Constraints.loose(500, 200));

        build(Checkbox.Variant.BOX, LayoutDirection.RTL, false);
        Size rtl = checkbox.measure(Constraints.loose(500, 200));

        assertEquals(ltr.width(), rtl.width(), EPS, "indicator + gap + label is a sum, not a side");
        assertEquals(ltr.height(), rtl.height(), EPS);
    }

    @Test
    void theHorizontalArrowsDoNothingInEitherDirection() {
        // A checkbox is not one of the arrow-key sites: Space and Enter toggle it and the arrows
        // belong to whatever is navigating around it. Asserted in both directions so that a later
        // mirroring sweep cannot add a key handler here and call it symmetry.
        for (LayoutDirection direction : LayoutDirection.values()) {
            build(Checkbox.Variant.BOX, direction, false);
            scene.requestFocus(checkbox);
            scene.keyEvent(Keys.LEFT, true, false, 0);
            scene.keyEvent(Keys.RIGHT, true, false, 0);
            scene.inputBatchEnded();
            assertFalse(checkbox.isChecked(), "an arrow toggled the box reading " + direction);
        }
    }

    @Test
    void theWholeRowStaysThePointerTargetInBothDirections() {
        // There is no x in the toggle's mouse handling and mirroring must not introduce one: the
        // target is the row the widget was given, at both of its ends.
        build(Checkbox.Variant.BOX, LayoutDirection.RTL, false);
        click(WIDTH - 9);
        assertTrue(checkbox.isChecked(), "a press over the indicator toggles");
        click(9);
        assertFalse(checkbox.isChecked(), "and so does one over the far end of the label");
    }

    @Test
    void spaceStillTogglesReadingRightToLeft() {
        build(Checkbox.Variant.SWITCH, LayoutDirection.RTL, false);
        scene.requestFocus(checkbox);
        scene.keyEvent(Keys.SPACE, true, false, 0);
        scene.inputBatchEnded();
        assertTrue(checkbox.isChecked());
    }

    // ----------------------------------------------------------------------- the canvas

    /** Records the geometry of the ink, which is what every expectation above reads. */
    private static final class InkCanvas extends FakeCanvas {
        final List<RoundRect> fills = new ArrayList<>();
        final List<RoundRect> strokes = new ArrayList<>();
        /** {@code cx, cy, radius} per circle; the switch thumb is the only one. */
        final List<float[]> circles = new ArrayList<>();
        /** {@code x, y} per flattened path point: the three corners of the check mark. */
        final List<float[]> pathPoints = new ArrayList<>();
        float textX = Float.NaN;

        InkCanvas(float width, float height) {
            super(width, height);
        }

        RoundRect firstFill() {
            assertFalse(fills.isEmpty(), "nothing was filled");
            return fills.get(0);
        }

        RoundRect firstStroke() {
            assertFalse(strokes.isEmpty(), "nothing was stroked");
            return strokes.get(0);
        }

        /** @return the stroked rect of that width, or null; the focus ring is the wide one */
        RoundRect strokeOfWidth(float width) {
            for (RoundRect r : strokes) {
                if (Math.abs(r.width() - width) < EPS) {
                    return r;
                }
            }
            return null;
        }

        float thumbX() {
            assertEquals(1, circles.size(), "the thumb is the only circle a switch paints");
            return circles.get(0)[0];
        }

        @Override
        public void fillRoundRect(RoundRect roundRect, Paint paint) {
            fills.add(roundRect);
        }

        @Override
        public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) {
            strokes.add(roundRect);
        }

        @Override
        public void fillCircle(float cx, float cy, float radius, Paint paint) {
            circles.add(new float[] {cx, cy, radius});
        }

        @Override
        public void drawPath(Path2D path, float strokeWidth, Paint paint) {
            path.flatten(0.01f, new Path2D.Flattened() {
                @Override
                public void moveTo(float x, float y) {
                    pathPoints.add(new float[] {x, y});
                }

                @Override
                public void lineTo(float x, float y) {
                    pathPoints.add(new float[] {x, y});
                }

                @Override
                public void closePath() {
                }
            });
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            textX = x;
        }
    }
}

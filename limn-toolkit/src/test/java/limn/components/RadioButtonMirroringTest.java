package limn.components;

import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.layout.Column;
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
 * {@link RadioButton} read right to left: which edge the ring sits on, where the label runs back
 * from, which way the horizontal arrows walk a {@link ButtonGroup}, and the things that must
 * <em>not</em> move &mdash; the vertical arrows, the dot inside its ring, and the measured width.
 *
 * <p>Every expectation is arithmetic against the tokens and {@link #RULER}'s 10pt clusters rather
 * than a picture. A row that is inside out shows up in a screenshot; a dot that has been reflected
 * a second time inside a correctly placed ring, or an Up key that walks a column backwards, does
 * not, and both are exactly what a later sweep over this file would do by accident.
 */
class RadioButtonMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final float WIDTH = 200;
    private static final float HEIGHT = 40;
    private static final String LABEL = "option";
    /** {@link #RULER} is 10pt per code point, so the label's width is an exact integer. */
    private static final float LABEL_WIDTH = 10 * LABEL.length();
    /** No strong character at all: the one string whose direction only the row can decide. */
    private static final String NEUTRAL_LABEL = "42";

    private final AtomicLong clock = new AtomicLong();
    private RadioButton radio;
    private Scene scene;
    private InkCanvas canvas;
    private SizeTokens t;
    private ButtonGroup group;
    private Scene groupScene;

    private void build(LayoutDirection direction, boolean selected) {
        radio = new RadioButton(LABEL);
        if (selected) {
            // Selected before the widget has a scene, where the transition snaps instead of
            // easing: the dot below is then the settled one and no frame budget decides it.
            radio.select();
        }
        radio.setLayoutDirection(direction);
        scene = new Scene(radio, clock::get);
        scene.setTextRuler(RULER);
        canvas = new InkCanvas(WIDTH, HEIGHT);
        scene.renderFrame(canvas); // initial layout + paint; the root fills the canvas
        t = Theme.current().tokensFor(radio);
    }

    /**
     * Advances the clock and renders again. The canvas accumulates rather than resetting: a frame
     * is free to damage nothing, and an expectation that assumed every frame repaints would then
     * be reading an empty list rather than the geometry it asked about.
     */
    private void frame(long millis) {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
        scene.renderFrame(canvas);
    }

    private void click(float x) {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, HEIGHT / 2);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, HEIGHT / 2);
        scene.inputBatchEnded();
    }

    // --------------------------------------------------------- the ring, and its label

    @Test
    void theRingAndItsLabelAreUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR, false);
        assertEquals(t.indicator() / 2, canvas.ring()[0], EPS,
                "the ring is centred half an indicator in from the left edge");
        assertEquals(t.indicator() + t.gapLabel(), canvas.textX, EPS,
                "and the label starts one gap past it");
    }

    @Test
    void theRingSitsOnTheFarEdgeAndTheLabelRunsBackFromItReadingRightToLeft() {
        build(LayoutDirection.RTL, false);
        assertEquals(WIDTH - t.indicator() / 2, canvas.ring()[0], EPS,
                "the ring is flush with the right edge, which is where reading starts");

        // A line is placed by its LEFT edge in either direction, so the label's own x is a whole
        // label width back from the gap it ends at.
        assertEquals(WIDTH - t.indicator() - t.gapLabel() - LABEL_WIDTH, canvas.textX, EPS);
        assertEquals(WIDTH - t.indicator() - t.gapLabel(), canvas.textX + LABEL_WIDTH, EPS,
                "the label ends exactly one gap short of the ring, and never under it");
    }

    @Test
    void theRingKeepsItsRadiusAndItsHeightWhenItChangesEdge() {
        build(LayoutDirection.LTR, false);
        float[] ltr = canvas.ring();
        build(LayoutDirection.RTL, false);
        float[] rtl = canvas.ring();

        assertEquals(ltr[1], rtl[1], EPS, "the ring did not move vertically");
        assertEquals(ltr[2], rtl[2], EPS,
                "and mirroring is a placement, so the radius is not in it");
    }

    // ------------------------------------------ the dot DOES NOT MIRROR INSIDE ITS RING

    @Test
    void theDotIsCarriedWithTheRingAndNeverReflectedInsideIt() {
        build(LayoutDirection.LTR, true);
        float[] ltrRing = canvas.ring();
        float[] ltrDot = canvas.dot();
        assertEquals(ltrRing[0], ltrDot[0], EPS, "the dot is concentric with the ring");

        build(LayoutDirection.RTL, true);
        float[] rtlRing = canvas.ring();
        float[] rtlDot = canvas.dot();

        // The whole indicator is translated to the other edge. Reflecting the dot a second time
        // against the widget would put it a full width away from the ring it belongs in, which is
        // the defect a single reflected centre exists to make impossible.
        assertEquals(rtlRing[0], rtlDot[0], EPS, "the dot is still concentric with the ring");
        assertEquals(ltrDot[0] + WIDTH - t.indicator(), rtlDot[0], EPS,
                "the dot is translated with its ring, not reflected within it");
        assertEquals(ltrDot[1], rtlDot[1], EPS, "and it did not move vertically");
        assertEquals(ltrDot[2], rtlDot[2], EPS, "nor change size");
    }

    // ------------------------------------------------------------------- the focus ring

    @Test
    void theFocusCircleFollowsTheRingToTheEdgeItSitsOn() {
        build(LayoutDirection.RTL, false);
        scene.requestFocus(radio);
        frame(0);
        frame(40);
        frame(40); // part way through the focus fade: the circle is painted, alpha aside

        float[] focus = canvas.circleOfRadius(t.indicator() / 2 + Strokes.FOCUS_GAP_INDICATOR);
        assertNotNull(focus, "the focus circle was never painted: " + canvas.strokedCircles.size());
        assertEquals(WIDTH - t.indicator() / 2, focus[0], EPS,
                "a circle still centred on x == indicator/2 would ring empty ground");
    }

    @Test
    void theFocusCircleIsUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR, false);
        scene.requestFocus(radio);
        frame(0);
        frame(40);
        frame(40);

        float[] focus = canvas.circleOfRadius(t.indicator() / 2 + Strokes.FOCUS_GAP_INDICATOR);
        assertNotNull(focus, "the focus circle was never painted: " + canvas.strokedCircles.size());
        assertEquals(t.indicator() / 2, focus[0], EPS);
    }

    // ---------------------------------------------------- the label's shaping fallback

    @Test
    void aLabelWithNoStrongCharacterTakesTheRowsOwnDirection() {
        // Decision 7: the row's direction is the shaper's neutral fallback. "42" has no strong
        // character, so it is the one label the fallback alone decides.
        assertEquals(ShapedText.Direction.LTR, shapedBaseOf(NEUTRAL_LABEL, LayoutDirection.LTR));
        assertEquals(ShapedText.Direction.RTL, shapedBaseOf(NEUTRAL_LABEL, LayoutDirection.RTL));
    }

    @Test
    void aLatinLabelStillReadsLeftToRightInsideARightToLeftRow() {
        // ...and the fallback is a fallback, not an imposition: a strong character outranks it,
        // so a Latin option in an Arabic form still reads left to right.
        assertEquals(ShapedText.Direction.LTR, shapedBaseOf(LABEL, LayoutDirection.RTL));
    }

    /** @return the base direction the label was shaped for, recorded off the ruler */
    private ShapedText.Direction shapedBaseOf(String label, LayoutDirection direction) {
        RecordingRuler ruler = new RecordingRuler();
        RadioButton subject = new RadioButton(label);
        subject.setLayoutDirection(direction);
        Scene subjectScene = new Scene(subject);
        subjectScene.setTextRuler(ruler);
        subjectScene.renderFrame(new InkCanvas(WIDTH, HEIGHT));
        assertFalse(ruler.bases.isEmpty(), "the label was never shaped");
        return ruler.bases.get(ruler.bases.size() - 1);
    }

    // ------------------------------------------------- the arrows: the horizontal half

    @Test
    void theHorizontalArrowsAreUnchangedReadingLeftToRight() {
        buildGroup(LayoutDirection.LTR);
        arrow(Keys.RIGHT);
        assertEquals(2, group.selectedIndex(), "Right is the next member");
        arrow(Keys.LEFT);
        assertEquals(1, group.selectedIndex(), "and Left is the previous one");
    }

    @Test
    void theHorizontalArrowsWalkTheOtherWayReadingRightToLeft() {
        buildGroup(LayoutDirection.RTL);
        // Left must select the visually-left member, which reading right to left is the LATER one,
        // or the keyboard disagrees with the row the pointer sees.
        arrow(Keys.LEFT);
        assertEquals(2, group.selectedIndex());
        arrow(Keys.RIGHT);
        assertEquals(1, group.selectedIndex());
    }

    @Test
    void theHorizontalArrowsWrapTheSameWayTheySelect() {
        buildGroup(LayoutDirection.RTL);
        arrow(Keys.RIGHT);
        assertEquals(0, group.selectedIndex());
        arrow(Keys.RIGHT);
        assertEquals(2, group.selectedIndex(),
                "the start wraps to the end, in the row's own order");
    }

    // ------------------------------------- the arrows: the vertical half DOES NOT MIRROR

    @Test
    void theVerticalArrowsAreUntouchedInEitherDirection() {
        // The reading direction is a horizontal axis and a column's order is not on it. Asserted
        // in both directions because Up and Down used to share their arms with Left and Right,
        // and mirroring a shared arm would have taken the vertical axis with it.
        for (LayoutDirection direction : LayoutDirection.values()) {
            buildGroup(direction);
            arrow(Keys.DOWN);
            assertEquals(2, group.selectedIndex(), "Down is the next member reading " + direction);
            arrow(Keys.UP);
            assertEquals(1, group.selectedIndex(), "Up is the previous one reading " + direction);
        }
    }

    // ------------------------------------------- what else must not move: size, pointer

    @Test
    void theMeasuredSizeDoesNotDependOnTheDirection() {
        build(LayoutDirection.LTR, false);
        Size ltr = radio.measure(Constraints.loose(500, 200));

        build(LayoutDirection.RTL, false);
        Size rtl = radio.measure(Constraints.loose(500, 200));

        assertEquals(ltr.width(), rtl.width(), EPS, "indicator + gap + label is a sum, not a side");
        assertEquals(ltr.height(), rtl.height(), EPS);
    }

    @Test
    void theWholeRowStaysThePointerTargetInBothDirections() {
        // There is no x in the radio's mouse handling and mirroring must not introduce one: the
        // target is the row the widget was given, at both of its ends.
        build(LayoutDirection.RTL, false);
        click(WIDTH - 9);
        assertTrue(radio.isSelected(), "a press over the ring selects");

        build(LayoutDirection.RTL, false);
        click(9);
        assertTrue(radio.isSelected(), "and so does one over the far end of the label");
    }

    @Test
    void spaceStillSelectsReadingRightToLeft() {
        build(LayoutDirection.RTL, false);
        scene.requestFocus(radio);
        scene.keyEvent(Keys.SPACE, true, false, 0);
        scene.inputBatchEnded();
        assertTrue(radio.isSelected());
    }

    // ------------------------------------------------------------------------- the group

    /** Three radios in a column, the middle one selected and focused, reading {@code direction}. */
    private void buildGroup(LayoutDirection direction) {
        RadioButton a = new RadioButton("A");
        RadioButton b = new RadioButton("B");
        RadioButton c = new RadioButton("C");
        group = new ButtonGroup().add(a).add(b).add(c);
        Column column = new Column();
        column.add(a);
        column.add(b);
        column.add(c);
        // Declared on the container, so the members inherit it: that is how an application states
        // a direction, and it is what the members have to read at the moment the key arrives.
        column.setLayoutDirection(direction);
        groupScene = new Scene(column);
        groupScene.setTextRuler(RULER);
        groupScene.layoutPass(WIDTH, 200);
        group.setSelectedIndex(1);
        groupScene.requestFocus(group.selected());
    }

    private void arrow(int key) {
        groupScene.keyEvent(key, true, false, 0);
        groupScene.keyEvent(key, false, false, 0);
        groupScene.inputBatchEnded();
    }

    // ----------------------------------------------------------------------- the fakes

    /** Records the geometry of the ink, which is what every expectation above reads. */
    private static final class InkCanvas extends FakeCanvas {
        /** {@code cx, cy, radius} per stroked circle: the ring, then the focus circle. */
        final List<float[]> strokedCircles = new ArrayList<>();
        /** {@code cx, cy, radius} per filled circle; the selected dot is the only one. */
        final List<float[]> filledCircles = new ArrayList<>();
        float textX = Float.NaN;

        InkCanvas(float width, float height) {
            super(width, height);
        }

        /** @return the ring: the first circle a radio strokes, and the one the rest are built on */
        float[] ring() {
            assertFalse(strokedCircles.isEmpty(), "no circle was stroked");
            return strokedCircles.get(0);
        }

        float[] dot() {
            assertEquals(1, filledCircles.size(), "the dot is the only circle a radio fills");
            return filledCircles.get(0);
        }

        /** @return the stroked circle of that radius, or null; the focus circle is the wide one */
        float[] circleOfRadius(float radius) {
            for (float[] circle : strokedCircles) {
                if (Math.abs(circle[2] - radius) < EPS) {
                    return circle;
                }
            }
            return null;
        }

        @Override
        public void drawCircle(float cx, float cy, float radius, float strokeWidth, Paint paint) {
            strokedCircles.add(new float[] {cx, cy, radius});
        }

        @Override
        public void fillCircle(float cx, float cy, float radius, Paint paint) {
            filledCircles.add(new float[] {cx, cy, radius});
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            // A shaped line reaches a canvas with no glyph placement of its own through this same
            // call, so one override catches both forms.
            textX = x;
        }
    }

    /**
     * {@link #RULER}'s arithmetic, plus the base direction every {@code shape} was asked for:
     * the direction a label was shaped for is not recoverable from the line afterwards under a
     * fake that reports no glyphs, and it is the whole of what Decision 7 changes here.
     */
    private static final class RecordingRuler implements TextRuler {
        final List<ShapedText.Direction> bases = new ArrayList<>();

        @Override
        public TextMetrics measure(String text, Font font) {
            return RULER.measure(text, font);
        }

        @Override
        public ShapedText shape(String text, Font font, ShapedText.Direction base) {
            bases.add(base);
            return TextRuler.super.shape(text, font, base);
        }
    }
}

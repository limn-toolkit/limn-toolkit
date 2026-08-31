package limn.components;

import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.RoundRect;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ComboBox} read right to left: where the selected label sits, which gutter the chevron
 * takes, which side of a row the marker and the label start on, and which edge the dropdown's
 * scrollbar is reserved against.
 *
 * <p>Every expectation is arithmetic against {@link #RULER}'s 10pt clusters and the MEDIUM
 * {@link SizeTokens} row, never a picture: a screenshot cannot tell a label placed at the right
 * edge from one that merely happens to end there, and it says nothing at all about the clip band
 * behind it.
 *
 * <p>What this widget must <em>not</em> do is asserted too, so that a later sweep cannot quietly
 * mirror it: the chevron is a triangle rather than an arrow and keeps its shape, the field's
 * border and a row's highlight stripe are their own mirror images, the field measures the same
 * either way, the horizontal arrow keys are not this widget's keys at all, Home and End name the
 * ends of the list rather than its sides, type-ahead matches the logical start of a label, and
 * the list itself does not move, because it is the field's own width.
 */
class ComboBoxMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final SizeTokens T = SizeTokens.of(ControlSize.MEDIUM);

    /** "one" and "two" are 3 clusters at 10pt; "three" is 5. */
    private static final float SHORT = 30;
    private static final float LONG = 50;

    /** The field alone in its scene. */
    private static final float FIELD_W = 240;
    private static final float FIELD_H = 32;

    /** The field inside a scene wide enough to hold a dropdown beside it. */
    private static final float SCENE_W = 400;
    private static final float SCENE_H = 300;
    private static final float COMBO_X = 100;
    private static final float COMBO_W = 120;

    private ComboBox combo;
    private Scene scene;

    // --------------------------------------------------------------- fixtures

    /** The field on its own: no list, so every mark recorded is the field's. */
    private Recorder paintField(LayoutDirection direction) {
        combo = new ComboBox(List.of("one", "two", "three"));
        combo.setLayoutDirection(direction);
        scene = new Scene(combo);
        scene.setTextRuler(RULER);
        scene.layoutPass(FIELD_W, FIELD_H);
        return paint(FIELD_W, FIELD_H);
    }

    /**
     * The field at a known x inside a wider scene, with its list open in that same scene. The
     * in-scene presentation is the one a headless test can drive: the native one needs a window
     * to put its panel in.
     */
    private Recorder paintList(LayoutDirection direction) {
        combo = new ComboBox(List.of("one", "two", "three"));
        combo.setLayoutDirection(direction);
        combo.setDisplayMode(DisplayMode.IN_SCENE);
        scene = new Scene(new Anchor(combo));
        scene.setTextRuler(RULER);
        scene.layoutPass(SCENE_W, SCENE_H);
        combo.open();
        scene.layoutPass(SCENE_W, SCENE_H); // lay the pushed overlay out
        assertTrue(combo.isInSceneForTest(), "the list is in this scene, where it can be recorded");
        return paint(SCENE_W, SCENE_H);
    }

    private Recorder paint(float width, float height) {
        Recorder recorder = new Recorder(width, height);
        scene.renderFrame(recorder);
        return recorder;
    }

    /**
     * A root that gives the combo a box narrower than the scene, at a known x, so that a list
     * hung from one of the field's edges is distinguishable from a list hung from the scene's.
     */
    private static final class Anchor extends Widget {

        private final ComboBox child;

        Anchor(ComboBox child) {
            this.child = child;
            add(child);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            child.measure(Constraints.tight(COMBO_W, FIELD_H));
            child.layoutBox(COMBO_X, 0, COMBO_W, FIELD_H);
        }
    }

    // ------------------------------------------------------------- the field

    @Test
    void theLabelStartsAtThePadAndTheChevronKeepsItsGutterReadingLeftToRight() {
        Recorder painted = paintField(LayoutDirection.LTR);

        assertEquals(T.fieldPadH(), painted.runX("one"), EPS, "the label starts at the pad");
        assertEquals(FIELD_W - T.comboCaretCenterX(), painted.chevronCentreX(), EPS,
                "and the chevron sits in the gutter at the far end");
        assertEquals(0, painted.onlyBand(FIELD_W - T.comboTextClip()), EPS,
                "the strip reserved for the chevron is the right-hand one");
    }

    @Test
    void anUndeclaredComboIsTheLeftToRightGeometry() {
        // The process default is LTR and mirroring is opt-in per subtree: a combo that declares
        // nothing must paint exactly what it painted before this axis existed.
        combo = new ComboBox(List.of("one", "two", "three"));
        scene = new Scene(combo);
        scene.setTextRuler(RULER);
        scene.layoutPass(FIELD_W, FIELD_H);
        Recorder painted = paint(FIELD_W, FIELD_H);

        assertEquals(T.fieldPadH(), painted.runX("one"), EPS);
        assertEquals(FIELD_W - T.comboCaretCenterX(), painted.chevronCentreX(), EPS);
    }

    @Test
    void theLabelIsFlushAgainstTheEdgeReadingStartsFromAndTheChevronTakesTheOtherGutter() {
        Recorder painted = paintField(LayoutDirection.RTL);

        // drawText places the LEFT edge of the run's box, so a label ending at the right pad
        // starts its box its own width back from there.
        assertEquals(FIELD_W - T.fieldPadH() - SHORT, painted.runX("one"), EPS,
                "the label ends where reading starts");
        assertEquals(T.comboCaretCenterX(), painted.chevronCentreX(), EPS,
                "the chevron's gutter is the left one");
        assertEquals(T.comboTextClip(), painted.onlyBand(FIELD_W - T.comboTextClip()), EPS,
                "and the strip reserved for it moved with it, so the band is the rest of the box");
    }

    @Test
    void aLongerLabelStillEndsAtTheSamePlace() {
        // The one part of this that is not a sign flip: the mirrored x is composed from the run's
        // own width, so a wider label has to grow into the field rather than out of it.
        combo = new ComboBox(List.of("three"));
        combo.setLayoutDirection(LayoutDirection.RTL);
        scene = new Scene(combo);
        scene.setTextRuler(RULER);
        scene.layoutPass(FIELD_W, FIELD_H);

        assertEquals(FIELD_W - T.fieldPadH() - LONG, paint(FIELD_W, FIELD_H).runX("three"), EPS);
    }

    // ------------------------------------------------- what the field does NOT do

    @Test
    void theChevronIsTheSameTriangleInBothDirections() {
        // ADR 032 Finding 4: this path points up and down. It is symmetric about its centre and
        // no platform turns it over, so only the gutter it sits in changes sides.
        List<Float> ltr = paintField(LayoutDirection.LTR).paths;
        List<Float> rtl = paintField(LayoutDirection.RTL).paths;

        assertEquals(3, ltr.size(), "three points: two feet and an apex");
        assertEquals(ltr.size(), rtl.size());
        for (int i = 0; i < ltr.size(); i++) {
            float fromLtrCentre = ltr.get(i) - (FIELD_W - T.comboCaretCenterX());
            float fromRtlCentre = rtl.get(i) - T.comboCaretCenterX();
            assertEquals(fromLtrCentre, fromRtlCentre, EPS,
                    "point " + i + " sits the same way about its own centre");
        }
        assertEquals(ltr.get(1), (ltr.get(0) + ltr.get(2)) / 2, EPS, "the apex is the midpoint");
        assertEquals(rtl.get(1), (rtl.get(0) + rtl.get(2)) / 2, EPS);
    }

    @Test
    void theBorderIsTheSameBoxInBothDirections() {
        // A full box inset symmetrically maps onto itself under a flip; adding a branch to one
        // is how a widget acquires a direction bug that no direction ever produced.
        RoundRect ltr = paintField(LayoutDirection.LTR).roundRect(FIELD_W - Strokes.BORDER);
        RoundRect rtl = paintField(LayoutDirection.RTL).roundRect(FIELD_W - Strokes.BORDER);

        assertEquals(ltr.x(), rtl.x(), EPS);
        assertEquals(ltr.width(), rtl.width(), EPS);
    }

    @Test
    void theFieldMeasuresTheSameInBothDirections() {
        // Why a direction change must not close an open popup window: the pad is on both sides
        // and the caret gutter is a magnitude, so the box the window was sized to is unchanged.
        assertEquals(measuredWidth(LayoutDirection.LTR), measuredWidth(LayoutDirection.RTL), EPS);
    }

    private float measuredWidth(LayoutDirection direction) {
        ComboBox box = new ComboBox(List.of("one", "two", "three"));
        box.setLayoutDirection(direction);
        Scene host = new Scene(box);
        host.setTextRuler(RULER);
        return box.measure(Constraints.loose(1000, 1000)).width();
    }

    // ------------------------------------------------------------- the list

    @Test
    void theRowsMarkerAndLabelStartOnTheLeftReadingLeftToRight() {
        Recorder painted = paintList(LayoutDirection.LTR);
        float panel = painted.panelOriginX();
        float textStart = T.popupRowInsetX() + T.popupMarkerCol();
        float band = COMBO_W - T.popupRowInsetX() - textStart;

        assertEquals(COMBO_X, panel, EPS, "the list hangs on the field");
        assertEquals(panel + T.popupRowInsetX() + T.popupDotCol(), painted.onlyCircle(), EPS,
                "the marker column is the row's left one");
        // "three" and "two" rather than the selected "one", which the field draws as well.
        assertEquals(panel + textStart, painted.runX("three"), EPS, "and the label follows it");
        assertEquals(panel + textStart, painted.bandsOfWidth(band).get(0).x(), EPS,
                "the row's clip starts where its label does");
        assertEquals(panel + COMBO_W - ScrollBar.thickness() - T.popupBarInsetX(),
                painted.barOriginX(), EPS, "the bar's column is reserved on the right");
    }

    @Test
    void theRowsMarkerLabelAndBarAllMoveToTheOtherSideReadingRightToLeft() {
        Recorder painted = paintList(LayoutDirection.RTL);
        float panel = painted.panelOriginX();
        float textStart = COMBO_W - T.popupRowInsetX() - T.popupMarkerCol();
        float band = textStart - T.popupRowInsetX();

        assertEquals(panel + COMBO_W - T.popupRowInsetX() - T.popupDotCol(),
                painted.onlyCircle(), EPS, "the marker column is measured from the right");
        assertEquals(panel + textStart - LONG, painted.runX("three"), EPS,
                "the label ends at the marker column, so its box starts its own width back");
        assertEquals(panel + textStart - SHORT, painted.runX("two"), EPS,
                "and a shorter label ends at that same column");
        assertEquals(panel + T.popupRowInsetX(), painted.bandsOfWidth(band).get(0).x(), EPS,
                "the row's clip runs from the far inset to where the label starts");
        assertEquals(panel + T.popupBarInsetX(), painted.barOriginX(), EPS,
                "the bar's column is reserved on the left");
    }

    @Test
    void theRowBandKeepsItsWidthAndTheHighlightStripeIsItsOwnMirror() {
        // The band is the same magnitude either way, because the marker column and the far inset
        // only trade sides; the stripe is inset equally on both sides and cannot move at all.
        float band = COMBO_W - 2 * T.popupRowInsetX() - T.popupMarkerCol();
        float stripe = COMBO_W - 2 * T.popupRowInsetX();

        Recorder ltr = paintList(LayoutDirection.LTR);
        Recorder rtl = paintList(LayoutDirection.RTL);

        assertEquals(3, ltr.bandsOfWidth(band).size(), "one band per visible row");
        assertEquals(3, rtl.bandsOfWidth(band).size());
        assertEquals(ltr.roundRect(stripe).x(), rtl.roundRect(stripe).x(), EPS,
                "the highlight stripe is where it was");
    }

    // -------------------------------------------------- what the list does NOT do

    @Test
    void theListStillHangsOnTheFieldInBothDirections() {
        // A leading-aligned box the width of the field lands on the field from either edge, so
        // this site mirrors in form and not in arithmetic. Asserted so that a later change to
        // the list's width cannot make it move by accident.
        assertEquals(paintList(LayoutDirection.LTR).panelOriginX(),
                paintList(LayoutDirection.RTL).panelOriginX(), EPS);
    }

    @Test
    void theHorizontalArrowKeysAreStillNotThisWidgetsKeys() {
        // ComboBox is not one of ADR 032's eleven arrow-key widgets: its open list answers the
        // vertical keys only. Mirroring a key it does not handle would mean handling it, which is
        // a behaviour change dressed as a direction fix.
        paintList(LayoutDirection.RTL);
        int before = combo.highlightedIndex();

        key(Keys.LEFT);
        key(Keys.RIGHT);

        assertEquals(before, combo.highlightedIndex(), "neither arrow moves the highlight");
        assertTrue(combo.isOpen(), "and neither closes the list");
    }

    @Test
    void homeAndEndStillNameTheEndsOfTheListAndNotItsSides() {
        paintList(LayoutDirection.RTL);

        key(Keys.END);
        assertEquals(2, combo.highlightedIndex(), "End is the last item in either direction");
        key(Keys.HOME);
        assertEquals(0, combo.highlightedIndex(), "and Home is the first");
    }

    @Test
    void typeAheadStillMatchesTheLogicalStartOfALabel() {
        // A prefix is the first character typed, which is index 0 of the string in either
        // direction; nothing about the search reverses.
        paintList(LayoutDirection.RTL);

        scene.charTyped('t');
        scene.inputBatchEnded();
        assertEquals(1, combo.highlightedIndex(), "\"two\" is the first label starting with t");
    }

    private void key(int keyCode) {
        scene.keyEvent(keyCode, true, false, 0);
        scene.keyEvent(keyCode, false, false, 0);
        scene.inputBatchEnded();
    }

    // ---------------------------------------------------------- the shaper's base

    @Test
    void aLabelWithNoStrongCharacterShapesAgainstTheFieldsOwnDirection() {
        // The seam ADR 032 Decision 7 names: the canvas has no widget to ask and falls back to
        // left to right, so a label that is all digits and punctuation would read the wrong way
        // inside a right-to-left form. Counted rather than measured, because the fake ruler is
        // font-blind and the width difference is a property of the faces.
        assertEquals(List.of(ShapedText.Direction.RTL), basesShapedFor(LayoutDirection.RTL),
                "an all-neutral label falls back to the direction of the form around it");
        assertEquals(List.of(ShapedText.Direction.LTR), basesShapedFor(LayoutDirection.LTR),
                "and left to right is unchanged");
    }

    @Test
    void aLatinLabelStillReadsLeftToRightInsideARightToLeftCombo() {
        // The first-strong rule still decides everything a strong character can decide; the
        // resolved direction is the fallback and not an imposition.
        combo = new ComboBox(List.of("one"));
        combo.setLayoutDirection(LayoutDirection.RTL);
        RecordingRuler ruler = new RecordingRuler();
        scene = new Scene(combo);
        scene.setTextRuler(ruler);
        scene.layoutPass(FIELD_W, FIELD_H);
        paint(FIELD_W, FIELD_H);

        assertEquals(List.of(ShapedText.Direction.LTR), ruler.bases);
    }

    private List<ShapedText.Direction> basesShapedFor(LayoutDirection direction) {
        ComboBox box = new ComboBox(List.of("1/2"));
        box.setLayoutDirection(direction);
        RecordingRuler ruler = new RecordingRuler();
        Scene host = new Scene(box);
        host.setTextRuler(ruler);
        host.layoutPass(FIELD_W, FIELD_H);
        host.renderFrame(new FakeCanvas(FIELD_W, FIELD_H));
        return ruler.bases;
    }

    /** {@link #RULER}, plus a note of the base direction every shaped run was asked for. */
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

    // ------------------------------------------------------------ the instrument

    /**
     * Every mark a frame makes, in <b>scene</b> coordinates: {@code translate} is followed
     * through the save stack, so a row painted inside the dropdown panel reports where it landed
     * on screen rather than where it landed inside its own panel.
     */
    private static final class Recorder extends FakeCanvas {

        /** One drawn run: its text, and the x its box's LEFT edge was placed at. */
        record Run(String text, float x) {
        }

        /** One clip: its left edge and its width. */
        record Band(float x, float width) {
        }

        final List<Run> runs = new ArrayList<>();
        final List<Band> bands = new ArrayList<>();
        final List<RoundRect> roundRects = new ArrayList<>();
        final List<Float> circles = new ArrayList<>();
        /** Every point of every path drawn, in order: the chevron is the only one here. */
        final List<Float> paths = new ArrayList<>();
        /**
         * The scene x each child widget was translated to, in paint order: a container
         * translates to a child's box and then paints it. With the list open the last two
         * entries are therefore the panel, the overlay layer's only child, and the panel's
         * scrollbar, which is the panel's only child and has none of its own.
         */
        final List<Float> childOrigins = new ArrayList<>();

        private final Deque<Float> saved = new ArrayDeque<>();
        private float originX;

        Recorder(float width, float height) {
            super(width, height);
        }

        float panelOriginX() {
            return childOrigins.get(childOrigins.size() - 2);
        }

        float barOriginX() {
            return childOrigins.get(childOrigins.size() - 1);
        }

        /** The selected row's marker: the only circle either presentation paints. */
        float onlyCircle() {
            assertEquals(1, circles.size(), "expected one marker dot, got " + circles);
            return circles.get(0);
        }

        /** Where the run of {@code text} was drawn; it must have been drawn exactly once. */
        float runX(String text) {
            List<Run> matching = runs.stream().filter(r -> r.text().equals(text)).toList();
            assertEquals(1, matching.size(), "expected one run of \"" + text + "\", got " + runs);
            return matching.get(0).x();
        }

        /** The left edge of the one clip of exactly this width. */
        float onlyBand(float width) {
            List<Band> matching = bandsOfWidth(width);
            assertEquals(1, matching.size(), "expected one band " + width + " wide: " + bands);
            return matching.get(0).x();
        }

        List<Band> bandsOfWidth(float width) {
            return bands.stream().filter(b -> Math.abs(b.width() - width) < EPS).toList();
        }

        /** The one rounded rect of exactly this width, in scene coordinates. */
        RoundRect roundRect(float width) {
            List<RoundRect> matching = roundRects.stream()
                    .filter(r -> Math.abs(r.width() - width) < EPS).toList();
            assertEquals(1, matching.size(),
                    "expected one round rect " + width + " wide: " + roundRects);
            return matching.get(0);
        }

        @Override
        public void save() {
            super.save();
            saved.push(originX);
        }

        @Override
        public void restore() {
            super.restore();
            if (!saved.isEmpty()) {
                originX = saved.pop();
            }
        }

        @Override
        public void restoreToCount(int count) {
            while (saved.size() > count) {
                originX = saved.pop();
            }
            super.restoreToCount(count);
        }

        @Override
        public void translate(float dx, float dy) {
            originX += dx;
            childOrigins.add(originX);
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
            bands.add(new Band(originX + x, w));
        }

        @Override
        public void fillRoundRect(RoundRect rect, Paint paint) {
            record(rect);
        }

        @Override
        public void drawRoundRect(RoundRect rect, float strokeWidth, Paint paint) {
            record(rect);
        }

        private void record(RoundRect rect) {
            roundRects.add(new RoundRect(originX + rect.x(), rect.y(), rect.width(),
                    rect.height(), rect.topLeft(), rect.topRight(),
                    rect.bottomRight(), rect.bottomLeft()));
        }

        @Override
        public void fillCircle(float cx, float cy, float radius, Paint paint) {
            circles.add(originX + cx);
        }

        @Override
        public void drawPath(Path2D path, float strokeWidth, Paint paint) {
            float at = originX;
            path.flatten(0.05f, new Path2D.Flattened() {
                @Override
                public void moveTo(float x, float y) {
                    paths.add(at + x);
                }

                @Override
                public void lineTo(float x, float y) {
                    paths.add(at + x);
                }

                @Override
                public void closePath() {
                }
            });
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            runs.add(new Run(text, originX + x));
        }

        /** The chevron's apex, which is its centre: the point the two feet are symmetric about. */
        float chevronCentreX() {
            assertFalse(paths.isEmpty(), "the chevron was not painted");
            return paths.get(1);
        }
    }
}

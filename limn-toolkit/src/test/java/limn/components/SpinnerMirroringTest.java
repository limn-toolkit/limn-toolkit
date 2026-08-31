package limn.components;

import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Spinner} read right to left: which side the stepper column is on, where the value is
 * flush, where a click lands, and the four things that do <em>not</em> move with the direction.
 *
 * <p>Every expectation is arithmetic against {@link #RULER}'s 10pt clusters rather than a picture:
 * the failures this guards against — a run drawn under the stepper column, a click one character
 * off, a scroll offset that grew the wrong way — are a few points wide and a screenshot is the
 * wrong instrument for them.
 */
class SpinnerMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final SizeTokens MEDIUM = SizeTokens.MEDIUM;
    private static final float BOX_W = MEDIUM.spinnerWidth();      // 140
    private static final float BOX_H = MEDIUM.controlHeight();     // 32
    private static final float BUTTON_W = MEDIUM.spinnerButtonW(); // 26
    private static final float PAD = MEDIUM.spacingMedium();       // 12
    private static final float UP_Y = BOX_H / 4;
    private static final float MID_Y = BOX_H / 2;

    /** Reading left to right the value area is [0, 114) and the stepper column [114, 140). */
    private static final float LTR_VALUE_END = BOX_W - BUTTON_W;
    /** Reading right to left the two swap: the column is [0, 26) and the value area [26, 140). */
    private static final float RTL_VALUE_START = BUTTON_W;
    /** The x reading starts from in a mirrored box: the value area's far edge, one pad in. */
    private static final float RTL_TEXT_END = BOX_W - PAD;

    private Spinner spinner;
    private Scene scene;

    private void build(Spinner s, LayoutDirection direction) {
        spinner = s;
        spinner.setLayoutDirection(direction);
        scene = new Scene(spinner);
        scene.setTextRuler(RULER);
        scene.layoutPass(BOX_W, BOX_H);
    }

    private void click(float x, float y) {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
    }

    private void press(int key) {
        scene.keyEvent(key, true, false, 0);
        scene.inputBatchEnded();
    }

    private void typeInto(String text) {
        scene.requestFocus(spinner);
        text.codePoints().forEach(scene::charTyped);
        scene.inputBatchEnded();
    }

    private Recorder render() {
        Recorder recorder = new Recorder();
        scene.renderFrame(recorder);
        return recorder;
    }

    // ------------------------------------------------------- the two columns

    @Test
    void theStepperColumnAndTheValueAreaSwapSides() {
        build(new Spinner(0, 10, 1).setValue(5), LayoutDirection.RTL);

        click(5, UP_Y);
        assertEquals(6.0, spinner.value(), "the up arrow is on the left, and the pointer found it");
        assertFalse(spinner.isEditing(), "an arrow is not a place to type");

        click(BOX_W - 13, MID_Y);
        assertTrue(spinner.isEditing(), "and the far side of the box is the value, so it takes a caret");
    }

    @Test
    void theColumnsAreUnchangedReadingLeftToRight() {
        build(new Spinner(0, 10, 1).setValue(5), LayoutDirection.LTR);

        click(BOX_W - 13, UP_Y);
        assertEquals(6.0, spinner.value(), "the up arrow is still on the right");

        click(5, MID_Y);
        assertTrue(spinner.isEditing(), "and the left of the box is still the value");
    }

    @Test
    void theSeamFencesTheValueOnTheSideTheColumnIsOn() {
        build(new Spinner(0, 10, 1).setValue(5), LayoutDirection.RTL);
        Recorder mirrored = render();
        assertEquals(2, mirrored.lines.size(), "two dividers: the seam and the up/down split");
        // The seam is the column's INNER edge. At the column's outer edge it would sit on the
        // box's own border, and the value would be left unfenced.
        assertEquals(RTL_VALUE_START, mirrored.lines.get(0)[0], EPS, "the seam is at the column's right");
        assertEquals(RTL_VALUE_START, mirrored.lines.get(0)[2], EPS, "and it is vertical");
        assertEquals(0f, mirrored.lines.get(1)[0], EPS, "the up/down split starts at the box edge");
        assertEquals(BUTTON_W, mirrored.lines.get(1)[2], EPS, "and stops at the seam, not at width()");

        build(new Spinner(0, 10, 1).setValue(5), LayoutDirection.LTR);
        Recorder plain = render();
        assertEquals(LTR_VALUE_END, plain.lines.get(0)[0], EPS);
        assertEquals(LTR_VALUE_END, plain.lines.get(1)[0], EPS);
        assertEquals(BOX_W, plain.lines.get(1)[2], EPS, "the split still reaches the right edge");
    }

    // ---------------------------------------------------------- the value

    @Test
    void theValueIsFlushWithTheEdgeReadingStartsFrom() {
        build(new Spinner(0, 10, 1).setValue(5), LayoutDirection.RTL);
        // "5" is one 10pt cluster, so its left edge sits 10pt back from the pad on the right.
        assertEquals(RTL_TEXT_END - 10, render().xOf("5"), EPS);
    }

    @Test
    void theValueIsUnchangedReadingLeftToRight() {
        build(new Spinner(0, 10, 1).setValue(5), LayoutDirection.LTR);
        assertEquals(PAD, render().xOf("5"), EPS);
    }

    @Test
    void theTimeRunTravelsWholeAndKeepsTheHoursLeftOfTheMinutes() {
        build(Spinner.time().setValue(7 * 60 + 30), LayoutDirection.RTL);
        Recorder r = render();
        // "07:30" is 50pt. Only the run's ORIGIN mirrors: a run of digits keeps its own order
        // inside a right-to-left form, so hh, the colon and mm stay in that order across it.
        float runStart = RTL_TEXT_END - 50;
        assertEquals(runStart, r.xOf("07"), EPS, "the hours lead the run");
        assertEquals(runStart + 20, r.xOf(":"), EPS);
        assertEquals(runStart + 30, r.xOf("30"), EPS);
        assertTrue(r.xOf("07") < r.xOf("30"),
                "the hours must stay VISUALLY left of the minutes: hh:mm does not mirror");
    }

    @Test
    void theTimeRunIsUnchangedReadingLeftToRight() {
        build(Spinner.time().setValue(7 * 60 + 30), LayoutDirection.LTR);
        Recorder r = render();
        assertEquals(PAD, r.xOf("07"), EPS);
        assertEquals(PAD + 20, r.xOf(":"), EPS);
        assertEquals(PAD + 30, r.xOf("30"), EPS);
    }

    // ------------------------------------------------------ the two arrows drawn

    @Test
    void theSteppersOwnArrowsPointUpAndDownInBothDirections() {
        build(new Spinner(0, 10, 1).setValue(5), LayoutDirection.RTL);
        List<float[]> mirrored = render().paths;
        assertEquals(2, mirrored.size(), "one arrow per half of the column");
        assertSymmetricAbout(BUTTON_W / 2, mirrored.get(0), "the up arrow");
        assertSymmetricAbout(BUTTON_W / 2, mirrored.get(1), "the down arrow");
        assertTrue(mirrored.get(0)[3] < mirrored.get(0)[1], "the first arrow still points up");
        assertTrue(mirrored.get(1)[3] > mirrored.get(1)[1], "and the second still points down");

        build(new Spinner(0, 10, 1).setValue(5), LayoutDirection.LTR);
        List<float[]> plain = render().paths;
        assertSymmetricAbout(BOX_W - BUTTON_W / 2, plain.get(0), "the unmirrored up arrow");
        assertSymmetricAbout(BOX_W - BUTTON_W / 2, plain.get(1), "the unmirrored down arrow");
    }

    /** A triangle of three points, centred on {@code cx} and mirror-symmetric about it. */
    private static void assertSymmetricAbout(float cx, float[] triangle, String what) {
        assertEquals(6, triangle.length, what + " is three points");
        assertEquals(cx, triangle[2], EPS, what + " has its apex on the column's centre");
        assertEquals(2 * cx, triangle[0] + triangle[4], EPS, what + " is symmetric about it");
    }

    // ---------------------------------------------------------- hit testing

    @Test
    void aClickPicksTheTimeFieldThePaintPutUnderThePointer() {
        // The hours/minutes boundary has to be measured from the run's own origin. Observed
        // through the step it takes, because that is what the field selection is FOR: the hours
        // step by an hour and the minutes by the step.
        build(Spinner.time().setValue(7 * 60 + 30).setEditable(false), LayoutDirection.RTL);
        scene.requestFocus(spinner);

        click(100, MID_Y); // inside the hours, which run [78, 108) in a mirrored box
        press(Keys.UP);
        assertEquals(8 * 60 + 30.0, spinner.value(), "a click on the hours steps the hours");

        click(115, MID_Y); // past the colon: the minutes
        press(Keys.UP);
        assertEquals(8 * 60 + 31.0, spinner.value(), "and a click on the minutes steps the minutes");
    }

    @Test
    void aClickPicksTheSameTimeFieldReadingLeftToRight() {
        build(Spinner.time().setValue(7 * 60 + 30).setEditable(false), LayoutDirection.LTR);
        scene.requestFocus(spinner);

        click(30, MID_Y); // the hours run [12, 42)
        press(Keys.UP);
        assertEquals(8 * 60 + 30.0, spinner.value());

        click(60, MID_Y);
        press(Keys.UP);
        assertEquals(8 * 60 + 31.0, spinner.value());
    }

    // --------------------------------------------------------- the arrow keys

    @Test
    void theValueArrowsMirrorTheAxisTheyNudge() {
        build(new Spinner(0, 10, 1).setValue(5), LayoutDirection.RTL);
        scene.requestFocus(spinner);

        press(Keys.LEFT);
        assertEquals(6.0, spinner.value(), "the high end is on the left, so Left steps up");
        press(Keys.RIGHT);
        assertEquals(5.0, spinner.value(), "and Right steps down");
    }

    @Test
    void theValueArrowsAreUnchangedReadingLeftToRight() {
        build(new Spinner(0, 10, 1).setValue(5), LayoutDirection.LTR);
        scene.requestFocus(spinner);

        press(Keys.LEFT);
        assertEquals(4.0, spinner.value());
        press(Keys.RIGHT);
        assertEquals(5.0, spinner.value());
    }

    @Test
    void theTimeFieldArrowsDoNotMirror() {
        // Left names the hours and Right the minutes because that is where those two fields are
        // drawn, and they are drawn there in both directions. Nothing here may swap.
        build(Spinner.time().setValue(7 * 60 + 30), LayoutDirection.RTL);
        scene.requestFocus(spinner);

        press(Keys.LEFT);
        press(Keys.UP);
        assertEquals(8 * 60 + 30.0, spinner.value(), "Left still selects the hours");

        press(Keys.RIGHT);
        press(Keys.UP);
        assertEquals(8 * 60 + 31.0, spinner.value(), "and Right still selects the minutes");
    }

    @Test
    void homeAndEndNameTheValueAndNotASide() {
        build(new Spinner(0, 10, 1).setValue(5), LayoutDirection.RTL);
        scene.requestFocus(spinner);

        press(Keys.HOME);
        assertEquals(0.0, spinner.value(), "Home is min in every direction");
        press(Keys.END);
        assertEquals(10.0, spinner.value(), "and End is max");
    }

    // ------------------------------------------------------------ typed text

    @Test
    void theTypedTextIsFlushWithTheEdgeReadingStartsFrom() {
        build(new Spinner(0, 100, 1), LayoutDirection.RTL);
        typeInto("12");

        Recorder r = render();
        assertEquals(RTL_TEXT_END - 20, r.xOf("12"), EPS, "the typed run ends on the leading edge");
        assertEquals(RTL_TEXT_END, r.caretX(), EPS, "and the caret is at the end of what was typed");
    }

    @Test
    void theTypedTextIsUnchangedReadingLeftToRight() {
        build(new Spinner(0, 100, 1), LayoutDirection.LTR);
        typeInto("12");

        Recorder r = render();
        assertEquals(PAD, r.xOf("12"), EPS);
        assertEquals(PAD + 20, r.caretX(), EPS);
    }

    @Test
    void aClickInTheTypedTextMapsThroughTheSameOriginThePaintUses() {
        // The pointer-to-text conversion is the inverse of the paint origin, and a sign error in
        // it is invisible in a screenshot and wrong on every click.
        build(new Spinner(0, 100, 1), LayoutDirection.RTL);
        typeInto("12");

        click(RTL_TEXT_END - 20, MID_Y);
        assertEquals(RTL_TEXT_END - 20, render().caretX(), EPS, "the far end of the run is index 0");

        click(RTL_TEXT_END - 10, MID_Y);
        assertEquals(RTL_TEXT_END - 10, render().caretX(), EPS, "and the middle is index 1");

        click(RTL_TEXT_END, MID_Y);
        assertEquals(RTL_TEXT_END, render().caretX(), EPS, "and the near end is the end of the text");
    }

    @Test
    void theEditArrowsStepAcrossTheScreenAndAreNotMirrored() {
        // Left and Right inside an edit are VISUAL: they step one cluster that way on the line
        // actually drawn. Mirroring them on top of that would step the caret the wrong way twice.
        build(new Spinner(0, 100, 1), LayoutDirection.RTL);
        typeInto("12");

        press(Keys.LEFT);
        assertEquals(RTL_TEXT_END - 10, render().caretX(), EPS, "Left moved the caret left on screen");

        build(new Spinner(0, 100, 1), LayoutDirection.LTR);
        typeInto("12");
        press(Keys.LEFT);
        assertEquals(PAD + 10, render().caretX(), EPS, "and does the same reading left to right");
    }

    @Test
    void aRunLongerThanTheBoxKeepsItsCaretInsideTheValueArea() {
        // The scroll offset stays a positive magnitude with zero at the leading edge, so only the
        // step that turns it into a coordinate knows a direction. Get that step wrong and the
        // caret leaves the box the moment the text overflows.
        build(new Spinner(0, 100, 1), LayoutDirection.RTL);
        typeInto("12345678901234567890"); // 200pt of text in a 90pt window

        assertEquals(RTL_TEXT_END, render().caretX(), EPS, "typing stays flush on the leading edge");

        // Home and End name the PARAGRAPH's two edges, which in a right-to-left form are the right
        // and the left one -- TextEditModel.moveHome takes UPSTREAM affinity for exactly this and
        // says why. A run of digits inside that form is one embedded left-to-right run, so index 0
        // is a split caret: the paragraph's start edge is its right, and the run's own start is its
        // left. Home means the first, and asking the shaped line is the only way to get it; the
        // width of a prefix has no side, so it could only ever answer the second.
        press(Keys.HOME);
        assertEquals(RTL_TEXT_END, render().caretX(), EPS,
                "Home names the edge the paragraph starts on, which needs no scroll to reach");

        press(Keys.END);
        assertEquals(RTL_VALUE_START + PAD, render().caretX(), EPS,
                "End is the paragraph's other edge, scrolled into view rather than out of the box");
    }

    @Test
    void aRunLongerThanTheBoxIsUnchangedReadingLeftToRight() {
        build(new Spinner(0, 100, 1), LayoutDirection.LTR);
        typeInto("12345678901234567890");

        assertEquals(LTR_VALUE_END - PAD, render().caretX(), EPS);

        press(Keys.HOME);
        assertEquals(PAD, render().caretX(), EPS);
    }

    // ------------------------------------------------------------- recording

    /** What one frame drew: the strings and their origins, the dividers, and the two arrows. */
    private static final class Recorder extends FakeCanvas {
        final List<String> texts = new ArrayList<>();
        final List<Float> textXs = new ArrayList<>();
        final List<float[]> lines = new ArrayList<>();
        final List<float[]> paths = new ArrayList<>();

        Recorder() {
            super(BOX_W, BOX_H);
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            texts.add(text);
            textXs.add(x);
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth,
                Paint paint) {
            lines.add(new float[] {x1, y1, x2, y2});
        }

        @Override
        public void drawPath(Path2D path, float strokeWidth, Paint paint) {
            List<Float> points = new ArrayList<>();
            path.flatten(0.1f, new Path2D.Flattened() {
                @Override
                public void moveTo(float x, float y) {
                    points.add(x);
                    points.add(y);
                }

                @Override
                public void lineTo(float x, float y) {
                    points.add(x);
                    points.add(y);
                }

                @Override
                public void closePath() {
                }
            });
            float[] flat = new float[points.size()];
            for (int i = 0; i < flat.length; i++) {
                flat[i] = points.get(i);
            }
            paths.add(flat);
        }

        /** Where {@code text} was drawn from. */
        float xOf(String text) {
            int at = texts.indexOf(text);
            assertTrue(at >= 0, "nothing drew \"" + text + "\", only " + texts);
            return textXs.get(at);
        }

        /**
         * The caret's column. It is the first line the box draws: the value is painted before the
         * stepper column, so a frame with a caret carries three lines and one without carries the
         * two dividers alone.
         */
        float caretX() {
            assertEquals(3, lines.size(), "expected a caret and the two dividers");
            return lines.get(0)[0];
        }
    }
}

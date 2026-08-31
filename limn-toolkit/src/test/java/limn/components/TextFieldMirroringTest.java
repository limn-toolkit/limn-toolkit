package limn.components;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Icon;
import limn.graphics.Paint;
import limn.graphics.ShapedText;
import limn.input.Keys;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TextField} read right to left: where the text starts, where the caret sits, which side
 * the icons are on, and where a click lands.
 *
 * <p>Every expectation is arithmetic against {@link #RULER}'s 10pt clusters rather than a picture,
 * for the reason the bidi caret tests give: a screenshot is the wrong instrument for geometry that
 * is a fraction of a point wrong, and the right one for a layout that is inside out.
 */
class TextFieldMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final float WIDTH = 240;
    private static final float PAD = SizeTokens.of(ControlSize.MEDIUM).fieldPadH();
    /** The x of the content area's trailing edge in a right-to-left field: where reading starts. */
    private static final float RTL_START = WIDTH - PAD;

    /** Never rasterized: the tests that use it measure and hit-test, they never paint it. */
    private static final Icon MEASURE_ONLY = (pixelSize, dark) -> {
        throw new UnsupportedOperationException("measure-only");
    };

    private TextField field;
    private Scene scene;

    private void build(LayoutDirection direction) {
        field = new TextField();
        field.setLayoutDirection(direction);
        scene = new Scene(field);
        scene.setTextRuler(RULER);
        scene.layoutPass(WIDTH, 32);
        scene.requestFocus(field);
    }

    private void type(String text) {
        text.codePoints().forEach(scene::charTyped);
        scene.inputBatchEnded();
    }

    /** The x of the one vertical line a focused, unselected field draws: its caret. */
    private float paintedCaretX() {
        CaretCanvas canvas = new CaretCanvas(WIDTH, 32);
        scene.renderFrame(canvas);
        return canvas.caretX;
    }

    private static final class CaretCanvas extends FakeCanvas {
        float caretX = Float.NaN;

        CaretCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth,
                Paint paint) {
            caretX = x1;
        }
    }

    // ------------------------------------------------------------- the origin

    @Test
    void anEmptyFieldPutsItsCaretOnTheTrailingEdgeReadingRightToLeft() {
        build(LayoutDirection.RTL);
        assertEquals(RTL_START, paintedCaretX(), EPS, "reading starts at the right");
    }

    @Test
    void anEmptyFieldIsUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR);
        assertEquals(PAD, paintedCaretX(), EPS);
    }

    @Test
    void aShortLineIsFlushAgainstTheEdgeReadingStartsFrom() {
        build(LayoutDirection.RTL);
        type("abc");
        // The caret is at the end of "abc", which is 30pt along a line whose left edge sits 30pt
        // back from the content's right edge: the two cancel and the caret is on the right edge.
        assertEquals(RTL_START, paintedCaretX(), EPS, "the caret follows the typing");

        field.model().setCaret(new ShapedText.Position(0, ShapedText.Affinity.DOWNSTREAM), false);
        assertEquals(RTL_START - 30, paintedCaretX(), EPS,
                "and index 0 is 30pt back, because the run itself still reads left to right");
    }

    @Test
    void theCaretRectAgreesWithThePaintedCaret() {
        build(LayoutDirection.RTL);
        type("abc");
        // caretRect is what a blink repaints; a rect that named a different column than the paint
        // would leave the old caret on screen and the new one undrawn.
        assertEquals(paintedCaretX(), field.caretRect().x(), EPS);
    }

    // ------------------------------------------------------------- hit testing

    @Test
    void aClickMapsThroughTheSameOriginThePaintUses() {
        build(LayoutDirection.RTL);
        type("abc");

        click(RTL_START - 30);
        assertEquals(0, field.model().caret().charIndex(), "the far end of the run is index 0");

        click(RTL_START);
        assertEquals(3, field.model().caret().charIndex(), "and the near end is the end of the text");
    }

    private void click(float localX) {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, localX, 16);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, localX, 16);
        scene.inputBatchEnded();
    }

    // ------------------------------------------------------------------ icons

    @Test
    void theLeadingIconAndTheTrailingButtonSwapSidesTogether() {
        build(LayoutDirection.RTL);
        Icon icon = MEASURE_ONLY;
        field.setLeadingIcon(icon);
        field.setTrailingButton(icon, () -> { });
        scene.layoutPass(WIDTH, 32);
        type("a");

        // caretRect rather than a painted frame: these icons are never rasterized, and the rect
        // is composed from the same origin the paint is.
        SizeTokens t = Theme.current().tokensFor(field);
        float leadingInset = PAD + t.fieldIcon() + t.gapIcon();
        assertEquals(WIDTH - leadingInset, field.caretRect().x(), EPS,
                "the caret is inside the leading pad, on the leading side");

        // ...and the trailing button's own hit region is on the left, which is what makes the
        // pointer agree with the paint. A press there must run the action, not move the caret.
        boolean[] fired = {false};
        field.setTrailingButton(icon, () -> fired[0] = true);
        scene.layoutPass(WIDTH, 32);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, t.fieldTrailing() / 2, 16);
        scene.inputBatchEnded();
        assertTrue(fired[0], "the trailing button is on the trailing side, which is the left");
    }

    // -------------------------------------------------- the held line's cache key

    @Test
    void theHeldDisplayLineIsReShapedWhenTheDirectionChanges() {
        // TextField keys its held line by hand rather than through ShapedText.matches, so the
        // direction has to be in THAT key: matches gaining a direction does nothing for a cache
        // that never calls it. Counted rather than measured, because the fake ruler is font-blind
        // and the real width difference is a property of the faces.
        CountingField counting = new CountingField();
        Scene countingScene = new Scene(counting);
        countingScene.setTextRuler(RULER);
        countingScene.layoutPass(WIDTH, 32);
        counting.setText("42"); // no strong character: the neutral fallback decides it alone

        countingScene.renderFrame(new FakeCanvas(WIDTH, 32));
        int afterFirst = counting.shapes;
        countingScene.renderFrame(new FakeCanvas(WIDTH, 32));
        assertEquals(afterFirst, counting.shapes, "nothing changed, so nothing re-shapes");

        counting.setLayoutDirection(LayoutDirection.RTL);
        countingScene.renderFrame(new FakeCanvas(WIDTH, 32));
        assertEquals(afterFirst + 1, counting.shapes, "the direction did, so the held line is stale");
    }

    private static final class CountingField extends TextField {
        int shapes;

        @Override
        protected ShapedText shapeDisplay(String text, Font font) {
            shapes++;
            return super.shapeDisplay(text, font);
        }
    }

    // ------------------------------------------------------------ the scroll

    @Test
    void aLineLongerThanTheFieldKeepsTheCaretInViewAndTheScrollNonNegative() {
        build(LayoutDirection.RTL);
        type("abcdefghijklmnopqrstuvwxyz"); // 260pt against a 240pt box

        float caret = paintedCaretX();
        assertTrue(caret >= PAD - EPS && caret <= RTL_START + EPS,
                "the caret stayed inside the content area, at " + caret);
    }
}

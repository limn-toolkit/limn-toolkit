package limn.components;

import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
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
 * {@link TextArea} read right to left: where content space sits, where each line sits inside it,
 * and what a direction change does to the lines it is holding.
 *
 * <p>The area has one thing a field does not — a content space wider than one line — so it has one
 * decision a field does not: a short line and a long one share the edge reading <em>starts</em>
 * from, not the one it ends on. That is what is pinned here.
 */
class TextAreaMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final float WIDTH = 200;
    private static final float PAD_X = SizeTokens.of(ControlSize.MEDIUM).fieldPadH();
    private static final float PAD_Y = SizeTokens.of(ControlSize.MEDIUM).areaPad();
    /** The x every line's trailing edge lands on reading right to left: where reading starts. */
    private static final float RTL_START = WIDTH - PAD_X;

    private TextArea area;
    private Scene scene;

    private void build(LayoutDirection direction, String text) {
        area = new TextArea();
        area.setLayoutDirection(direction);
        scene = new Scene(area);
        scene.setTextRuler(RULER);
        scene.layoutPass(WIDTH, 100);
        area.setText(text);
        scene.requestFocus(area);
    }

    /** Records where text and the caret actually land, translate included. */
    private static final class GeometryCanvas extends FakeCanvas {
        private float tx;
        private float ty;
        final List<float[]> texts = new ArrayList<>();
        float caretX = Float.NaN;

        GeometryCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void translate(float dx, float dy) {
            tx += dx;
            ty += dy;
            super.translate(dx, dy);
        }

        @Override
        public void save() {
            super.save();
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            texts.add(new float[]{tx + x, ty + y});
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth,
                Paint paint) {
            caretX = tx + x1;
        }
    }

    private GeometryCanvas paint() {
        GeometryCanvas canvas = new GeometryCanvas(WIDTH, 100);
        scene.renderFrame(canvas);
        return canvas;
    }

    // -------------------------------------------------- content space and its lines

    @Test
    void everyLineIsFlushAgainstTheEdgeReadingStartsFrom() {
        build(LayoutDirection.RTL, "abc\nabcdef");
        GeometryCanvas canvas = paint();

        assertEquals(2, canvas.texts.size(), "both lines drawn");
        // 30pt and 60pt of text; the short one is NOT left-aligned with the long one, it shares
        // the edge reading starts from. That is the decision a field never has to take.
        assertEquals(RTL_START - 30, canvas.texts.get(0)[0], EPS, "the short line, flush right");
        assertEquals(RTL_START - 60, canvas.texts.get(1)[0], EPS, "the long line, flush right");
    }

    @Test
    void everyLineIsUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR, "abc\nabcdef");
        GeometryCanvas canvas = paint();
        assertEquals(PAD_X, canvas.texts.get(0)[0], EPS);
        assertEquals(PAD_X, canvas.texts.get(1)[0], EPS);
    }

    @Test
    void theCaretSitsWhereItsOwnLineWasDrawn() {
        build(LayoutDirection.RTL, "abc\nabcdef");
        area.model().setCursor(0, false);
        assertEquals(RTL_START - 30, paint().caretX, EPS,
                "index 0 of the short line, which is 30pt back from the edge it starts on");

        area.model().setCursor(3, false); // end of the first line
        assertEquals(RTL_START, paint().caretX, EPS);
    }

    @Test
    void theCaretRectAgreesWithThePaintedCaret() {
        build(LayoutDirection.RTL, "abc\nabcdef");
        area.model().setCursor(0, false);
        assertEquals(paint().caretX, area.caretRect().x(), EPS);
    }

    // ------------------------------------------------------------- hit testing

    @Test
    void aClickMapsThroughTheSameOriginsThePaintUses() {
        build(LayoutDirection.RTL, "abc\nabcdef");

        click(RTL_START - 30, PAD_Y + 1);
        assertEquals(0, area.model().caret().charIndex(), "the far end of the short line");

        click(RTL_START, PAD_Y + 1);
        assertEquals(3, area.model().caret().charIndex(), "and its near end");
    }

    private void click(float localX, float localY) {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, localX, localY);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, localX, localY);
        scene.inputBatchEnded();
    }

    // ------------------------------------------------ the held lines' cache key

    @Test
    void everyHeldLineIsReShapedWhenTheDirectionItFallsBackToChanges() {
        // TextArea keys its window and its spill by hand rather than through ShapedText.matches,
        // so the direction has to be in THAT key. The ruler records what it was asked for, which
        // proves both halves at once: the three-argument overload is reached, and a change of
        // direction drops what was held.
        //
        // The fixture is one line with no strong character and one with a Latin one, because that
        // is the whole of the decision: the widget's direction is the NEUTRAL FALLBACK, and the
        // first-strong rule still decides everything a strong character can decide. "42" in an
        // Arabic form reads right to left; "abc" in the same form does not.
        RecordingRuler ruler = new RecordingRuler();
        TextArea recorded = new TextArea();
        Scene host = new Scene(recorded);
        host.setTextRuler(ruler);
        host.layoutPass(WIDTH, 100);
        recorded.setText("42\nabc");

        host.renderFrame(new FakeCanvas(WIDTH, 100));
        int afterFirst = ruler.bases.size();
        assertTrue(afterFirst >= 2, "both lines shaped");
        assertTrue(ruler.bases.stream().allMatch(b -> b == ShapedText.Direction.LTR),
                "left to right by default, neutral line included");

        host.renderFrame(new FakeCanvas(WIDTH, 100));
        assertEquals(afterFirst, ruler.bases.size(), "nothing changed, so nothing re-shapes");

        ruler.bases.clear();
        recorded.setLayoutDirection(LayoutDirection.RTL);
        host.renderFrame(new FakeCanvas(WIDTH, 100));
        assertEquals(2, ruler.bases.size(), "both held lines were dropped and rebuilt");
        assertEquals(1, ruler.bases.stream().filter(b -> b == ShapedText.Direction.RTL).count(),
                "the neutral line took the interface's direction...");
        assertEquals(1, ruler.bases.stream().filter(b -> b == ShapedText.Direction.LTR).count(),
                "...and the Latin one kept its own, because a strong character already decided it");
    }

    /** {@link ComponentTestBase#RULER}'s metrics, plus a log of the directions it was asked for. */
    private static final class RecordingRuler implements TextRuler {
        final List<ShapedText.Direction> bases = new ArrayList<>();

        @Override
        public TextMetrics measure(String text, Font font) {
            return new TextMetrics(10f * (int) text.codePoints().count(), 8, 2, 12);
        }

        @Override
        public ShapedText shape(String text, Font font, ShapedText.Direction base) {
            bases.add(base);
            return TextRuler.super.shape(text, font, base);
        }
    }

    // ------------------------------------------------------------- the scroll

    @Test
    void aLineWiderThanTheColumnKeepsTheCaretInViewAndTheScrollNonNegative() {
        build(LayoutDirection.RTL, "abcdefghijklmnopqrstuvwxyz"); // 260pt against a 200pt box
        area.model().setCursor(26, false);
        area.scrollBy(0, 0); // force the clamp

        assertTrue(area.scrollXOffset() >= 0,
                "scrollX is a magnitude in both directions, and it was "
                        + area.scrollXOffset());
        float caret = paint().caretX;
        assertTrue(caret >= PAD_X - EPS && caret <= RTL_START + EPS,
                "the caret stayed inside the text column, at " + caret);
    }
}

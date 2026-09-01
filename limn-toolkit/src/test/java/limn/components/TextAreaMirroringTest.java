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
    /**
     * alef, bet, gimel: three strong right-to-left characters, one char apiece. The only literal
     * right-to-left text in this file, so no source line here mixes directions and reorders in an
     * editor; the soft-wrap fixtures build from this one constant, because they are about where
     * a right-to-left ROW starts and need text whose logical start is its visual right.
     */
    private static final String HEB = "אבג";
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
        final List<float[]> clips = new ArrayList<>();
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
        public void clipRect(float x, float y, float w, float h) {
            clips.add(new float[]{tx + x, w});
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

    // ------------------------------------------------- which side the bar takes

    @Test
    void aReservedGutterMovesTheTextColumnOffTheBarRatherThanUnderIt() {
        // The defect this catches, seen on screen first: the vertical bar moved to the side
        // reading ends on, and the text column stayed anchored to the pad, so the first glyph of
        // every line sat under the thumb. The column's own clip is the direct evidence, because
        // it is the expression that says where the column begins.
        float t = ScrollBar.thickness();

        assertEquals(PAD_X - Strokes.AA_BLEED, columnClipLeft(LayoutDirection.LTR), EPS,
                "the default is unchanged: the column starts at the pad");
        assertEquals(PAD_X + t - Strokes.AA_BLEED, columnClipLeft(LayoutDirection.RTL), EPS,
                "reading right to left the bar took the left, so the column starts after it");
    }

    /** Where the text column's clip begins, with a reserved gutter and a bar that is real. */
    private float columnClipLeft(LayoutDirection direction) {
        build(direction, "x");
        area.setBarLayout(ScrollGutters.Layout.RESERVED);
        area.setText(longEnoughToScroll());
        scene.layoutPass(WIDTH, 100);
        List<float[]> clips = paint().clips;
        // The column's clip is the narrow one: the scene's own full-box clip is the width of the
        // widget, and this one is short by two pads and the strip.
        float narrowest = Float.MAX_VALUE;
        float left = Float.NaN;
        for (float[] c : clips) {
            if (c[1] < narrowest) {
                narrowest = c[1];
                left = c[0];
            }
        }
        assertTrue(narrowest < WIDTH, "the column's clip is narrower than the box");
        return left;
    }

    /** Enough lines that the vertical bar is real under a RESERVED gutter. */
    private static String longEnoughToScroll() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("line ").append(i).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- soft wrap

    /**
     * Six Hebrew words, 23 chars = 230 pt against the 176 pt column: the break backs up to the
     * boundary after the fourth word, so the rows are chars [0, 16) and [16, 23) — 150 and
     * 70 pt drawn, in a content space exactly as wide as the column.
     */
    private static String sixWords() {
        return HEB + " " + HEB + " " + HEB + " " + HEB + " " + HEB + " " + HEB;
    }

    @Test
    void softWrapRowsShareTheEdgeReadingStartsFrom() {
        build(LayoutDirection.RTL, "");
        area.setSoftWrap(true);
        area.setText(sixWords());
        // The start of each row — reading's start, the right edge — is the same x for the wide
        // first row and the narrow second, exactly the rule unwrapped lines already obey.
        area.model().setCursor(0, false);
        assertEquals(RTL_START, area.caretRect().x(), EPS);
        area.model().setCaret(new ShapedText.Position(16, ShapedText.Affinity.DOWNSTREAM), false);
        assertEquals(RTL_START, area.caretRect().x(), EPS);
        assertEquals(PAD_Y + 12, area.caretRect().y(), EPS);
        assertEquals(0, area.scrollXOffset(), EPS, "wrapped, nothing overflows the reading axis");
    }

    @Test
    void softWrapClickAtTheStartEdgeOfTheSecondRowLandsOnItsFirstCharacter() {
        build(LayoutDirection.RTL, "");
        area.setSoftWrap(true);
        area.setText(sixWords());
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, RTL_START - 1, PAD_Y + 12 + 1);
        scene.inputBatchEnded();
        assertEquals(16, area.model().cursor(),
                "the second row's first character sits at the right edge, where reading starts");
    }
}

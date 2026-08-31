package limn.components;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.graphics.Paint;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.i18n.I18n;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Label} read right to left: which end the icon takes, where a line of text starts, and
 * which direction the values it holds between passes were shaped for.
 *
 * <p>Every expectation is arithmetic against {@link #RULER}'s 10pt clusters rather than a
 * picture. A screenshot is the wrong instrument twice over here: it cannot see a held line shaped
 * for yesterday's direction at all, and it makes an inside-out layout look merely unfamiliar.
 *
 * <p>The cases that assert something does <b>not</b> move are as much the point as the ones that
 * assert something does. A later sweep looking for "every horizontal coordinate" will find
 * {@link Label.HAlign#CENTER}, the vertical alignment and the icon glyph itself, and each of them
 * has a test here saying they are already right.
 */
class LabelMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final float WIDTH = 200;
    private static final float HEIGHT = 40;
    /** "abc" under {@link #RULER}: three clusters at 10pt. */
    private static final float ABC = 30;

    /**
     * Alef, bet, gimel, as one constant and in one place, so no source line in this file mixes
     * directions and reorders under an editor.
     */
    private static final String HEBREW = "אבג";

    private Label label;
    private Scene scene;
    private Locale locale;

    /** The wrap walk asks {@link java.text.BreakIterator} for the UI language; pin it. */
    @BeforeEach
    void pinLocale() {
        locale = I18n.locale();
        I18n.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void restoreLocale() {
        I18n.setLocale(locale);
    }

    private void build(LayoutDirection direction, String text) {
        label = new Label(text);
        label.setLayoutDirection(direction);
        scene = new Scene(label);
        scene.setTextRuler(RULER);
        layout();
    }

    private void layout() {
        scene.layoutPass(WIDTH, HEIGHT);
    }

    private TextRecorder painted() {
        TextRecorder canvas = new TextRecorder(WIDTH, HEIGHT);
        scene.renderFrame(canvas);
        return canvas;
    }

    /** The x the one and only line was drawn at, which is the left edge of the shaped run. */
    private float paintedX() {
        List<Float> xs = painted().xs;
        assertEquals(1, xs.size(), "the fixture is a single-line label");
        return xs.get(0);
    }

    // ------------------------------------------------------ where a line starts

    @Test
    void startIsTheLeftEdgeReadingLeftToRight() {
        build(LayoutDirection.LTR, "abc");
        assertEquals(0, paintedX(), EPS, "the default is unchanged");
    }

    @Test
    void startIsTheRightEdgeReadingRightToLeft() {
        build(LayoutDirection.RTL, "abc");
        // START names where reading STARTS. Right to left that is the region's right edge, so the
        // run's left edge is the box less its own width.
        assertEquals(WIDTH - ABC, paintedX(), EPS);
    }

    @Test
    void endIsTheRightEdgeReadingLeftToRightAndTheLeftEdgeReadingRightToLeft() {
        build(LayoutDirection.LTR, "abc");
        label.setAlign(Label.HAlign.END, Label.VAlign.CENTER);
        layout();
        assertEquals(WIDTH - ABC, paintedX(), EPS, "the default is unchanged");

        build(LayoutDirection.RTL, "abc");
        label.setAlign(Label.HAlign.END, Label.VAlign.CENTER);
        layout();
        assertEquals(0, paintedX(), EPS, "reading ends on the left");
    }

    /**
     * DOES NOT MIRROR. A centre is the same number in both directions, and the arm of the
     * alignment switch that must never grow a direction branch.
     */
    @Test
    void centreDoesNotMove() {
        build(LayoutDirection.LTR, "abc");
        label.setAlign(Label.HAlign.CENTER, Label.VAlign.CENTER);
        layout();
        float ltr = paintedX();
        assertEquals((WIDTH - ABC) / 2, ltr, EPS);

        build(LayoutDirection.RTL, "abc");
        label.setAlign(Label.HAlign.CENTER, Label.VAlign.CENTER);
        layout();
        assertEquals(ltr, paintedX(), EPS, "a centred line is centred either way");
    }

    /**
     * DOES NOT MIRROR. {@link Label.VAlign} is the other axis; a mirrored label's baselines are
     * the same numbers, and a sweep that reflected them would move text nothing asked to move.
     */
    @Test
    void verticalAlignmentDoesNotMove() {
        build(LayoutDirection.LTR, "abc");
        label.setAlign(Label.HAlign.START, Label.VAlign.BOTTOM);
        layout();
        float ltr = painted().ys.get(0);
        assertEquals(HEIGHT - 12 + 8, ltr, EPS, "the last line box, plus the ascent");

        build(LayoutDirection.RTL, "abc");
        label.setAlign(Label.HAlign.START, Label.VAlign.BOTTOM);
        layout();
        assertEquals(ltr, painted().ys.get(0), EPS);
    }

    // ------------------------------------------------------------------- the icon

    @Test
    void theIconTakesTheEndReadingStartsFromAndTheTextTakesTheRest() {
        build(LayoutDirection.LTR, "abc");
        RecordingIcon icon = new RecordingIcon();
        label.setIcon(icon);
        layout();
        SizeTokens t = Theme.current().tokensFor(label);
        float iconSpace = t.iconBox() + t.gapLabel();
        // Painted first: an icon records where it was drawn, and a layout pass draws nothing.
        assertEquals(iconSpace, paintedX(), EPS, "the text starts past the gutter");
        assertEquals(0, icon.x, EPS, "the default is unchanged");

        build(LayoutDirection.RTL, "abc");
        RecordingIcon mirrored = new RecordingIcon();
        label.setIcon(mirrored);
        layout();
        float mirroredTextX = paintedX();
        assertEquals(WIDTH - t.iconBox(), mirrored.x, EPS, "leading is the right edge");
        // The text region is what is left of the box, and START inside it is its right edge: the
        // gutter is on the far side, so the region begins at the box's own left edge.
        assertEquals(WIDTH - iconSpace - ABC, mirroredTextX, EPS);
    }

    /**
     * The room the text gets is a magnitude and not an x: the icon claims the same width from
     * either end, so only the edge it is measured from moved.
     */
    @Test
    void theTextRegionIsTheSameWidthInBothDirections() {
        build(LayoutDirection.RTL, "abc");
        label.setIcon(new RecordingIcon());
        label.setAlign(Label.HAlign.END, Label.VAlign.CENTER);
        layout();
        assertEquals(0, paintedX(), EPS, "END is the region's left edge, which is the box's");

        build(LayoutDirection.RTL, "abc");
        label.setIcon(new RecordingIcon());
        layout();
        SizeTokens t = Theme.current().tokensFor(label);
        // START minus END is the region's width less the line's: the same subtraction the other
        // direction makes, which is what says the region did not change size.
        assertEquals(WIDTH - (t.iconBox() + t.gapLabel()) - ABC, paintedX(), EPS);
    }

    /**
     * DOES NOT MIRROR by default. The square moves; what is drawn inside it is the application's
     * decision, and the default is that a glyph is drawn as authored. A wrong default here flips
     * every brand mark in an application rather than one arrow.
     */
    @Test
    void anIconGlyphIsDrawnAsAuthoredUnlessTheCallThatPlacedItSaysOtherwise() {
        build(LayoutDirection.RTL, "abc");
        RecordingIcon plain = new RecordingIcon();
        label.setIcon(plain);
        layout();
        painted();
        assertEquals(Boolean.FALSE, plain.mirrored, "an unclassified icon never turns around");

        RecordingIcon directional = new RecordingIcon();
        label.setIcon(directional, Icon.Mirroring.IN_RTL);
        layout();
        painted();
        assertEquals(Boolean.TRUE, directional.mirrored, "and one that says it is, does");

        // ...and the flag alone is not the answer: it takes the axis too.
        build(LayoutDirection.LTR, "abc");
        RecordingIcon unmirrored = new RecordingIcon();
        label.setIcon(unmirrored, Icon.Mirroring.IN_RTL);
        layout();
        painted();
        assertEquals(Boolean.FALSE, unmirrored.mirrored, "the default direction is unchanged");
    }

    // ------------------------------------------------- the direction the values carry

    /**
     * The seam of the whole file: a string with no strong character of its own takes the label's
     * own direction, and the held paragraph is stale when that direction changes. Recorded at the
     * ruler rather than measured, because the fake ruler is font-blind and the width difference a
     * direction makes is a property of the faces.
     */
    @Test
    void aStringWithNoStrongCharacterTakesTheLabelsOwnDirection() {
        Label counter = new Label("42");
        BaseRecordingRuler ruler = new BaseRecordingRuler();
        Scene countingScene = new Scene(counter);
        countingScene.setTextRuler(ruler);
        countingScene.layoutPass(WIDTH, HEIGHT);
        assertTrue(ruler.shaped.contains("42@LTR"), "the default is unchanged: " + ruler.shaped);
        assertFalse(ruler.shaped.contains("42@RTL"), "and nothing shaped it the other way");

        ruler.shaped.clear();
        counter.setLayoutDirection(LayoutDirection.RTL);
        countingScene.layoutPass(WIDTH, HEIGHT);
        assertTrue(ruler.shaped.contains("42@RTL"),
                "the held paragraph is stale across a direction change: " + ruler.shaped);
    }

    /**
     * A wrapped line is shaped at the PARAGRAPH's base and never at its own first-strong answer.
     * A line of digits inside a right-to-left paragraph that re-derived its own base would
     * disagree with the paragraph that decided where it broke, and be wrong by a fraction of a
     * point in every geometry question asked of it afterwards.
     */
    @Test
    void aWrappedLineInheritsTheParagraphsBase() {
        Font f = Font.of(12);
        ShapedText paragraph = RULER.shape(HEBREW + " 12 34", f, ShapedText.Direction.RTL);
        List<ShapedText> out = new ArrayList<>();
        Label.wrapText(paragraph, 40, RULER, out);

        assertEquals(List.of(HEBREW, "12", "34"), out.stream().map(ShapedText::text).toList());
        for (ShapedText line : out) {
            assertEquals(ShapedText.Direction.RTL, line.baseDirection(),
                    "this line took its own base and not the paragraph's: " + line.text());
        }
    }

    @Test
    void aWrappedLineInheritsALeftToRightParagraphsBaseToo() {
        Font f = Font.of(12);
        ShapedText paragraph = RULER.shape("abc " + HEBREW, f, ShapedText.Direction.LTR);
        List<ShapedText> out = new ArrayList<>();
        Label.wrapText(paragraph, 40, RULER, out);

        assertEquals(List.of("abc", HEBREW), out.stream().map(ShapedText::text).toList());
        for (ShapedText line : out) {
            assertEquals(ShapedText.Direction.LTR, line.baseDirection(),
                    "a Hebrew word does not turn its English paragraph around: " + line.text());
        }
    }

    /** The one line an all-whitespace paragraph holds carries a direction like any other. */
    @Test
    void theEmptyLineFloorCarriesTheParagraphsBase() {
        Font f = Font.of(12);
        ShapedText paragraph = RULER.shape("   ", f, ShapedText.Direction.RTL);
        List<ShapedText> out = new ArrayList<>();
        Label.wrapText(paragraph, 40, RULER, out);

        assertEquals(1, out.size(), "the floor still emits exactly one line");
        assertEquals("", out.get(0).text());
        assertEquals(ShapedText.Direction.RTL, out.get(0).baseDirection());
    }

    /**
     * The ellipsis is cut in logical order and lands wherever the line's own direction puts it,
     * with no branch. That only holds if the base travels with the re-shape: a kept prefix of
     * digits and an ellipsis are both neutral, so left to themselves they read left to right and
     * the ellipsis lands at the wrong end of a right-to-left line.
     */
    @Test
    void anEllipsisOfWhollyNeutralTextIsShapedAtTheLinesOwnBase() {
        Font f = Font.of(12);
        ShapedText line = RULER.shape("12345678", f, ShapedText.Direction.RTL);
        ShapedText shown = Label.ellipsize(line, 55, RULER);

        assertEquals("1234…", shown.text(), "cut in logical order: 55pt less the ellipsis");
        assertEquals(ShapedText.Direction.RTL, shown.baseDirection());
        assertEquals(50, shown.metrics().width(), EPS);
        // The ellipsis is the LAST character and the FIRST box on the line; the digits follow it.
        assertEquals(0, shown.selection(4, 5).get(0).x0(), EPS);
        assertEquals(10, shown.selection(4, 5).get(0).x1(), EPS);
    }

    @Test
    void anEllipsisOfWhollyNeutralTextIsUnchangedReadingLeftToRight() {
        Font f = Font.of(12);
        ShapedText line = RULER.shape("12345678", f, ShapedText.Direction.LTR);
        ShapedText shown = Label.ellipsize(line, 55, RULER);

        assertEquals("1234…", shown.text());
        assertEquals(ShapedText.Direction.LTR, shown.baseDirection());
        assertEquals(40, shown.selection(4, 5).get(0).x0(), EPS, "the ellipsis is on the right");
    }

    /**
     * A size and not an x. The box a label asks for is the same in both directions under a
     * font-blind ruler, so a container laying one out cannot see the direction at all.
     */
    @Test
    void theMeasuredBoxIsTheSameInBothDirections() {
        build(LayoutDirection.LTR, "abc");
        Size ltr = label.measure(Constraints.loose(500, 500));

        build(LayoutDirection.RTL, "abc");
        Size rtl = label.measure(Constraints.loose(500, 500));
        assertEquals(ltr.width(), rtl.width(), EPS);
        assertEquals(ltr.height(), rtl.height(), EPS);
    }

    // ------------------------------------------------------------------ fixtures

    /** Records where each line was drawn; a Label draws nothing else. */
    private static final class TextRecorder extends FakeCanvas {

        private final List<Float> xs = new ArrayList<>();
        private final List<Float> ys = new ArrayList<>();

        TextRecorder(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            xs.add(x);
            ys.add(y);
        }
    }

    /**
     * Records where its square was placed and whether it was told to turn around. Never
     * rasterized: the flip is the canvas's business and this test is about the two flags that
     * decide it.
     */
    private static final class RecordingIcon implements Icon {

        private float x = Float.NaN;
        private Boolean mirrored;

        @Override
        public Image image(int pixelSize, boolean dark) {
            throw new UnsupportedOperationException("measure-only");
        }

        @Override
        public void paint(Canvas canvas, float x, float y, float size, Color tint, boolean dark,
                boolean mirrored) {
            this.x = x;
            this.mirrored = mirrored;
        }
    }

    /**
     * {@link #RULER}, plus a note of the base direction every shaping was asked for. The direction
     * a value was shaped for is not visible in its width under a font-blind ruler, so it is caught
     * at the call instead.
     */
    private static final class BaseRecordingRuler implements TextRuler {

        private final List<String> shaped = new ArrayList<>();

        @Override
        public TextMetrics measure(String text, Font font) {
            return RULER.measure(text, font);
        }

        @Override
        public ShapedText shape(String text, Font font, ShapedText.Direction base) {
            shaped.add(text + "@" + base);
            return TextRuler.super.shape(text, font, base);
        }
    }
}

package limn.components;

import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ColorPickerButton} read right to left: which side the chip is on, where its caption
 * lands, what the shaper is told about a caption that says nothing either way, and the several
 * things in this control that must stay exactly where they are.
 *
 * <p>The button is a chip followed by a caption, which is one decision in two statements, so
 * most of what is asserted here is the relationship between the two: the gap between them is
 * the same token in both directions, and the pair together is inset by the same pad. A chip
 * that mirrored while its caption did not would be a button with its colour sitting in the
 * middle of its own text, and that is a picture, not an exception.
 *
 * <p>Every expectation is arithmetic against {@link #RULER}'s 10pt clusters and the size
 * tokens the button actually resolved, taken off the calls the widget made. A screenshot
 * cannot tell a caption placed for one direction and shaped for the other from a correct one,
 * and that is precisely the failure this file is written against.
 */
class ColorPickerButtonMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final float WIDTH = 240;
    private static final float HEIGHT = 40;
    /** The default caption: the colour's hex, Latin and digits and so strongly left to right. */
    private static final String HEX = "#F59E0B";
    /** A caption with no strong character in it at all, which only the fallback can decide. */
    private static final String NEUTRAL = "12:34";

    private ColorPickerButton button;
    private Scene scene;

    private void build(LayoutDirection direction) {
        build(direction, RULER);
    }

    private void build(LayoutDirection direction, TextRuler ruler) {
        button = new ColorPickerButton(Color.rgb(0xF59E0B));
        button.setLayoutDirection(direction);
        scene = new Scene(button);
        scene.setTextRuler(ruler);
        // The root is measured at tight constraints, so the button's box is exactly this.
        scene.layoutPass(WIDTH, HEIGHT);
    }

    private SizeTokens tokens() {
        return Theme.current().tokensFor(button);
    }

    /** The caption's width under the deterministic ruler: 10pt per code point. */
    private float captionWidth(String caption) {
        return RULER.measure(caption, tokens().body()).width();
    }

    // ------------------------------------------------------------------- the chip

    @Test
    void theChipSitsAgainstTheTrailingEdgeReadingRightToLeft() {
        build(LayoutDirection.RTL);
        SizeTokens t = tokens();

        assertEquals(WIDTH - t.padH() - t.iconBox(), chipX(paint()), EPS,
                "the chip leads the pair, and reading right to left the lead is the right");
    }

    @Test
    void theChipIsUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR);
        SizeTokens t = tokens();

        assertEquals(t.padH(), chipX(paint()), EPS);
    }

    @Test
    void theChipIsTheSameSquareInBothDirections() {
        // Only its x is a decision. A chip that changed shape with the direction would mean the
        // reflection had been applied to a magnitude somewhere.
        build(LayoutDirection.RTL);
        SizeTokens t = tokens();
        Fill rtl = chipFill(paint());

        build(LayoutDirection.LTR);
        Fill ltr = chipFill(paint());

        assertEquals(t.iconBox(), rtl.w(), EPS);
        assertEquals(ltr.w(), rtl.w(), EPS, "the swatch is a square, not an axis");
        assertEquals(ltr.h(), rtl.h(), EPS);
        assertEquals(ltr.y(), rtl.y(), EPS, "and its y is not this ADR's business");
    }

    // ---------------------------------------------------------------- the caption

    @Test
    void theCaptionFollowsTheChipInwardsReadingRightToLeft() {
        build(LayoutDirection.RTL);
        SizeTokens t = tokens();
        float text = captionWidth(HEX);

        // drawText places a line by its LEFT edge whichever way the line runs, so the caption's
        // own width is part of the leading x here and part of nothing at all in the other one.
        assertEquals(WIDTH - t.padH() - t.iconBox() - t.gapIcon() - text, captionX(paint()), EPS,
                "the caption starts where the chip's advance ends, measured from the right");
    }

    @Test
    void theCaptionIsUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR);
        SizeTokens t = tokens();

        assertEquals(t.padH() + t.iconBox() + t.gapIcon(), captionX(paint()), EPS);
    }

    @Test
    void theGapBetweenTheChipAndItsCaptionIsTheSameTokenInBothDirections() {
        // The pair, rather than either half: this is what fails if one site is mirrored and the
        // other is left behind, and it fails by a whole chip's width rather than by a rounding.
        build(LayoutDirection.RTL);
        SizeTokens t = tokens();
        Frame rtl = paint();
        assertEquals(t.gapIcon(),
                chipX(rtl) - (captionX(rtl) + captionWidth(HEX)), EPS,
                "reading right to left the caption ends where the chip's gap begins");

        build(LayoutDirection.LTR);
        Frame ltr = paint();
        assertEquals(t.gapIcon(), captionX(ltr) - (chipX(ltr) + t.iconBox()), EPS);
    }

    @Test
    void anEmptyCaptionLeavesTheChipOnTheLeadingEdgeAndNothingElse() {
        build(LayoutDirection.RTL);
        button.setText("");
        scene.layoutPass(WIDTH, HEIGHT);
        SizeTokens t = tokens();
        Frame frame = paint();

        assertEquals(WIDTH - t.padH() - t.iconBox(), chipX(frame), EPS,
                "with no caption the chip is still the leading item, and still on the lead");
        assertTrue(frame.texts().isEmpty(), "an empty caption paints no line to place");
    }

    // ------------------------------------------------------------------ the box

    @Test
    void theBoxIsMeasuredTheSameInBothDirections() {
        // The classic mistake this guards: reflecting a measure. The width is a total with a pad
        // on each side, so it carries no direction, and a button that resized when the direction
        // changed would move every widget beside it.
        build(LayoutDirection.LTR);
        SizeTokens t = tokens();
        float expected = captionWidth(HEX) + t.iconBox() + t.gapIcon() + 2 * t.padH();
        float ltr = button.measure(Constraints.loose(WIDTH, HEIGHT)).width();

        build(LayoutDirection.RTL);
        float rtl = button.measure(Constraints.loose(WIDTH, HEIGHT)).width();

        assertEquals(expected, ltr, EPS);
        assertEquals(ltr, rtl, EPS, "the direction is not a size");
    }

    @Test
    void theFaceAndItsBorderCoverTheWholeBoxInBothDirections() {
        build(LayoutDirection.RTL);
        Frame rtl = paint();
        float inset = Strokes.HALF_PIXEL_INSET;

        assertTrue(hasRoundRect(rtl, 0, 0, WIDTH, HEIGHT),
                "the face is the whole box; there is no side of it to choose");
        assertTrue(hasRoundRect(rtl, inset, inset, WIDTH - 2 * inset, HEIGHT - 2 * inset),
                "and the border is a symmetric inset of it");

        build(LayoutDirection.LTR);
        Frame ltr = paint();
        assertTrue(hasRoundRect(ltr, 0, 0, WIDTH, HEIGHT));
        assertTrue(hasRoundRect(ltr, inset, inset, WIDTH - 2 * inset, HEIGHT - 2 * inset));
    }

    // ------------------------------------------------------ the shaper's fallback

    @Test
    void aCaptionWithNothingStrongInItTakesTheButtonsDirection() {
        // The neutral fallback, which is the only thing the widget gets to say about shaping.
        RecordingRuler ruler = new RecordingRuler();
        build(LayoutDirection.RTL, ruler);
        button.setText(NEUTRAL);
        scene.layoutPass(WIDTH, HEIGHT);
        paint();

        assertEquals(ShapedText.Direction.RTL, ruler.baseFor(NEUTRAL),
                "nothing in the string decides it, so the interface around it does");
    }

    @Test
    void aCaptionWithNothingStrongInItIsUnchangedReadingLeftToRight() {
        RecordingRuler ruler = new RecordingRuler();
        build(LayoutDirection.LTR, ruler);
        button.setText(NEUTRAL);
        scene.layoutPass(WIDTH, HEIGHT);
        paint();

        assertEquals(ShapedText.Direction.LTR, ruler.baseFor(NEUTRAL));
    }

    @Test
    void aLatinCaptionStillReadsLeftToRightInsideARightToLeftButton() {
        // A fallback and not an imposition: the first-strong rule still decides everything a
        // strong character can decide, and the default caption is a hex.
        RecordingRuler ruler = new RecordingRuler();
        build(LayoutDirection.RTL, ruler);
        paint();

        assertEquals(ShapedText.Direction.LTR, ruler.baseFor(HEX),
                "the hex is Latin and digits; only where it is placed moved");
    }

    @Test
    void theCaptionStringIsNeverRewrittenByTheDirection() {
        // Directional characters are the shaper's business. A widget that reversed a string or
        // swapped a bracket by hand would double up with what the shaper already did.
        build(LayoutDirection.RTL);
        button.setText("(a)");
        scene.layoutPass(WIDTH, HEIGHT);

        assertEquals("(a)", button.text());
        assertEquals("(a)", captionLine(paint()).text(),
                "the characters handed to the canvas are the characters that were set");
    }

    // ---------------------------------------------------- what must NOT turn round

    @Test
    void theHorizontalArrowsAreNotThisButtonsInEitherDirection() {
        // This control has no value axis to walk: it answers Enter and Space and nothing else.
        // Asserted so that a later sweep adding arrows "for consistency with the rails" has to
        // change a test that says why they are not here.
        build(LayoutDirection.RTL);
        button.requestFocus();
        assertTrue(button.isFocused());
        Color before = button.color();

        press(Keys.LEFT);
        press(Keys.RIGHT);

        assertFalse(button.isPickerOpen(), "an arrow key is not this button's to act on");
        assertEquals(before, button.color(), "and it certainly does not nudge the colour");
    }

    @Test
    void spaceStillOpensThePickerReadingRightToLeft() {
        build(LayoutDirection.RTL);
        button.requestFocus();

        scene.keyEvent(Keys.SPACE, true, false, 0);
        scene.inputBatchEnded();
        scene.keyEvent(Keys.SPACE, false, false, 0);
        scene.inputBatchEnded();

        assertTrue(button.isPickerOpen(),
                "the keys that do belong to this control are untouched by the direction");
    }

    // ------------------------------------------------------------------- driving

    private void press(int key) {
        scene.keyEvent(key, true, false, 0);
        scene.inputBatchEnded();
        scene.keyEvent(key, false, false, 0);
        scene.inputBatchEnded();
    }

    // ------------------------------------------------------------- reading a frame

    /** One recorded fill. */
    private record Fill(float x, float y, float w, float h, Paint paint) {
    }

    /** One recorded line of text, at the x its left edge was placed on. */
    private record Line(String text, float x, float y) {
    }

    /** Everything one frame painted by this button, which is the whole scene here. */
    private record Frame(List<Fill> rects, List<Fill> roundRects, List<Line> texts) {
    }

    private Frame paint() {
        // Damaged whole, so the frame is a full one: a partial pass could cull the very widget
        // the assertion is about and report it as having painted nothing.
        button.invalidate();
        GeometryCanvas canvas = new GeometryCanvas(WIDTH, HEIGHT);
        scene.renderFrame(canvas);
        return new Frame(canvas.rects, canvas.roundRects, canvas.texts);
    }

    /**
     * The square fill the chip painted its colour into, matched on the colour as well as the
     * box: an enabled button paints the value itself, so nothing else in the frame can answer.
     */
    private Fill chipFill(Frame frame) {
        float chip = tokens().iconBox();
        for (Fill fill : frame.rects()) {
            if (Math.abs(fill.w() - chip) < EPS && Math.abs(fill.h() - chip) < EPS
                    && button.color().equals(fill.paint())) {
                return fill;
            }
        }
        throw new AssertionError("the button painted no chip");
    }

    private float chipX(Frame frame) {
        return chipFill(frame).x();
    }

    /** The one line of text this button paints. */
    private Line captionLine(Frame frame) {
        if (frame.texts().isEmpty()) {
            throw new AssertionError("the button painted no caption");
        }
        return frame.texts().get(0);
    }

    /** The x the caption's left edge was placed on. */
    private float captionX(Frame frame) {
        return captionLine(frame).x();
    }

    private boolean hasRoundRect(Frame frame, float x, float y, float w, float h) {
        for (Fill fill : frame.roundRects()) {
            if (Math.abs(fill.x() - x) < EPS && Math.abs(fill.y() - y) < EPS
                    && Math.abs(fill.w() - w) < EPS && Math.abs(fill.h() - h) < EPS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records the geometry this button paints. The button is the scene's root and is laid out
     * at the origin, so a local coordinate and a scene one are the same number here and no
     * transform has to be followed.
     */
    private static final class GeometryCanvas extends FakeCanvas {

        private final List<Fill> rects = new ArrayList<>();
        private final List<Fill> roundRects = new ArrayList<>();
        private final List<Line> texts = new ArrayList<>();

        GeometryCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
            rects.add(new Fill(x, y, w, h, paint));
        }

        @Override
        public void fillRoundRect(RoundRect roundRect, Paint paint) {
            roundRects.add(new Fill(roundRect.x(), roundRect.y(),
                    roundRect.width(), roundRect.height(), paint));
        }

        @Override
        public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) {
            roundRects.add(new Fill(roundRect.x(), roundRect.y(),
                    roundRect.width(), roundRect.height(), paint));
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            texts.add(new Line(text, x, y));
        }
    }

    /**
     * The deterministic ruler with one thing added: what base direction it was asked to shape
     * each string in. That question has no geometry under this ruler — every cluster is 10pt
     * whichever way it is ordered — and it is exactly the one a caption placed correctly and
     * shaped wrongly would answer wrong.
     */
    private static final class RecordingRuler implements TextRuler {

        private final List<String> texts = new ArrayList<>();
        private final List<ShapedText.Direction> bases = new ArrayList<>();

        @Override
        public TextMetrics measure(String text, Font font) {
            return RULER.measure(text, font);
        }

        @Override
        public ShapedText shape(String text, Font font, ShapedText.Direction base) {
            texts.add(text);
            bases.add(base);
            return TextRuler.super.shape(text, font, base);
        }

        ShapedText.Direction baseFor(String text) {
            for (int i = texts.size() - 1; i >= 0; i--) {
                if (texts.get(i).equals(text)) {
                    return bases.get(i);
                }
            }
            throw new AssertionError("nothing shaped " + text);
        }
    }
}

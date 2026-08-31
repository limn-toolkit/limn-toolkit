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
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Button} read right to left: which end of the caption block the icon takes, which end the
 * caption takes, and which direction the caption it holds between passes was shaped for.
 *
 * <p>Every expectation is arithmetic against {@link #RULER}'s 10pt clusters rather than a picture.
 * A screenshot is the wrong instrument twice over here: it cannot see a held caption shaped for
 * yesterday's direction at all, and it makes an inside-out block look merely unfamiliar.
 *
 * <p>The cases that assert something does <b>not</b> move are as much the point as the ones that
 * assert something does. A sweep looking for "every horizontal coordinate" will find the block's
 * centre, the baseline, the primary mouse button and the two arrow keys, and each of them has a
 * case here saying it is already right.
 */
class ButtonMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final float WIDTH = 200;
    private static final float HEIGHT = 40;
    /** "abc" under {@link #RULER}: three clusters at 10pt. */
    private static final float ABC = 30;

    private Button button;
    private Scene scene;
    private final AtomicInteger fired = new AtomicInteger();

    private void build(LayoutDirection direction, String text) {
        button = new Button(text);
        button.onAction(fired::incrementAndGet);
        button.setLayoutDirection(direction);
        scene = new Scene(button);
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

    /** The x the caption was drawn at, which is the left edge of the shaped run. */
    private float paintedTextX() {
        List<Float> xs = painted().xs;
        assertEquals(1, xs.size(), "a button draws exactly one caption");
        return xs.get(0);
    }

    /** Room the icon claims beside a non-empty caption: its square and the gap after it. */
    private float iconAdvance() {
        SizeTokens t = Theme.current().tokensFor(button);
        return t.iconBox() + t.gapIcon();
    }

    /**
     * The left edge of the centred block holding an icon and {@code "abc"}: the one coordinate
     * both directions are measured from, and the one neither of them moves.
     */
    private float blockX() {
        return (WIDTH - (iconAdvance() + ABC)) / 2;
    }

    // ------------------------------------------------------- the centred block

    /**
     * DOES NOT MOVE. A text-only button is one horizontal decision and it is a centre, so the
     * overwhelming majority of buttons draw the same coordinates in both directions. This is the
     * expression that must never grow a direction branch.
     */
    @Test
    void aCaptionWithNoIconIsCentredInBothDirections() {
        build(LayoutDirection.LTR, "abc");
        float ltr = paintedTextX();
        assertEquals((WIDTH - ABC) / 2, ltr, EPS, "the default is unchanged");

        build(LayoutDirection.RTL, "abc");
        assertEquals(ltr, paintedTextX(), EPS, "a centred caption is centred either way");
    }

    // -------------------------------------------------------------- the icon

    @Test
    void theIconTakesTheBlocksLeftEndReadingLeftToRight() {
        build(LayoutDirection.LTR, "abc");
        RecordingIcon icon = new RecordingIcon();
        button.setIcon(icon);
        layout();

        float textX = paintedTextX();
        assertEquals(blockX(), icon.x, EPS, "the default is unchanged: the icon leads on the left");
        assertEquals(blockX() + iconAdvance(), textX, EPS, "and the caption follows the gutter");
    }

    @Test
    void theIconTakesTheBlocksRightEndReadingRightToLeft() {
        build(LayoutDirection.RTL, "abc");
        RecordingIcon icon = new RecordingIcon();
        button.setIcon(icon);
        layout();

        SizeTokens t = Theme.current().tokensFor(button);
        float textX = paintedTextX();
        // Leading is the end reading STARTS from, which is the block's right end; the square sits
        // its own width back from it, and the caption takes everything to its left.
        assertEquals(blockX() + iconAdvance() + ABC - t.iconBox(), icon.x, EPS);
        assertEquals(blockX(), textX, EPS, "the caption begins at the block's own left edge");
    }

    /**
     * The block itself is the same box in both directions: only the order of the two things
     * inside it changed. Asserted as the span from the leftmost ink to the rightmost, because
     * that is the number a centre promises and the one a swapped pair must not disturb.
     */
    @Test
    void theBlockOccupiesTheSameSpanInBothDirections() {
        build(LayoutDirection.LTR, "abc");
        RecordingIcon ltrIcon = new RecordingIcon();
        button.setIcon(ltrIcon);
        layout();
        // Painted first, always: an icon records where it was drawn, and a layout pass draws
        // nothing.
        float ltrTextX = paintedTextX();
        SizeTokens t = Theme.current().tokensFor(button);
        float ltrLeft = Math.min(ltrIcon.x, ltrTextX);
        float ltrRight = Math.max(ltrIcon.x + t.iconBox(), ltrTextX + ABC);

        build(LayoutDirection.RTL, "abc");
        RecordingIcon rtlIcon = new RecordingIcon();
        button.setIcon(rtlIcon);
        layout();
        float rtlTextX = paintedTextX();
        float rtlLeft = Math.min(rtlIcon.x, rtlTextX);
        float rtlRight = Math.max(rtlIcon.x + t.iconBox(), rtlTextX + ABC);

        assertEquals(ltrLeft, rtlLeft, EPS, "the block did not move, its contents traded ends");
        assertEquals(ltrRight, rtlRight, EPS);
        assertEquals(blockX(), rtlLeft, EPS);
    }

    /** With no caption the icon is alone in the block, and alone is the same place either way. */
    @Test
    void anIconOnlyButtonPutsTheSquareInTheSamePlaceInBothDirections() {
        build(LayoutDirection.LTR, "");
        RecordingIcon ltrIcon = new RecordingIcon();
        button.setIcon(ltrIcon);
        layout();
        painted();
        SizeTokens t = Theme.current().tokensFor(button);
        assertEquals((WIDTH - t.iconBox()) / 2, ltrIcon.x, EPS, "the default is unchanged");

        build(LayoutDirection.RTL, "");
        RecordingIcon rtlIcon = new RecordingIcon();
        button.setIcon(rtlIcon);
        layout();
        painted();
        // The gap is not claimed when there is no caption to be separated from, so a mirrored
        // icon-only button must not pick one up on the other side.
        assertEquals(ltrIcon.x, rtlIcon.x, EPS);
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
        button.setIcon(plain);
        layout();
        painted();
        assertEquals(Boolean.FALSE, plain.mirrored, "an unclassified icon never turns around");

        RecordingIcon directional = new RecordingIcon();
        button.setIcon(directional, Icon.Mirroring.IN_RTL);
        layout();
        painted();
        assertEquals(Boolean.TRUE, directional.mirrored, "and one that says it is, does");

        // ...and the flag alone is not the answer: it takes the axis too.
        build(LayoutDirection.LTR, "abc");
        RecordingIcon unmirrored = new RecordingIcon();
        button.setIcon(unmirrored, Icon.Mirroring.IN_RTL);
        layout();
        painted();
        assertEquals(Boolean.FALSE, unmirrored.mirrored, "the default direction is unchanged");
    }

    // ------------------------------------------------- the direction the caption carries

    /**
     * The seam of the whole file: a caption with no strong character of its own takes the
     * button's own direction, and the held value is stale when that direction changes. Recorded
     * at the ruler rather than measured, because the fake ruler is font-blind and the width
     * difference a direction makes is a property of the faces.
     */
    @Test
    void aCaptionWithNoStrongCharacterTakesTheButtonsOwnDirection() {
        Button counter = new Button("42");
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
                "the held caption is stale across a direction change: " + ruler.shaped);
    }

    /**
     * The direction is the shaper's NEUTRAL FALLBACK and never an imposition: the first-strong
     * rule still decides everything a strong character can decide, so a Latin caption in a
     * right-to-left form still reads left to right.
     */
    @Test
    void aStrongCaptionKeepsItsOwnDirectionInsideAMirroredButton() {
        Button latin = new Button("abc");
        BaseRecordingRuler ruler = new BaseRecordingRuler();
        Scene countingScene = new Scene(latin);
        countingScene.setTextRuler(ruler);
        latin.setLayoutDirection(LayoutDirection.RTL);
        countingScene.layoutPass(WIDTH, HEIGHT);

        assertTrue(ruler.shaped.contains("abc@LTR"),
                "a strong character outranks the fallback: " + ruler.shaped);
        assertFalse(ruler.shaped.contains("abc@RTL"), "the button imposed nothing");
    }

    /** The held caption is shaped once and reused: nothing about a repeat pass re-shapes it. */
    @Test
    void theHeldCaptionSurvivesAPassThatChangedNothing() {
        Button counter = new Button("42");
        BaseRecordingRuler ruler = new BaseRecordingRuler();
        Scene countingScene = new Scene(counter);
        countingScene.setTextRuler(ruler);
        countingScene.layoutPass(WIDTH, HEIGHT);
        countingScene.renderFrame(new FakeCanvas(WIDTH, HEIGHT));
        int afterFirst = ruler.shaped.size();

        countingScene.renderFrame(new FakeCanvas(WIDTH, HEIGHT));
        assertEquals(afterFirst, ruler.shaped.size(), "nothing changed, so nothing re-shapes");
    }

    // -------------------------------------------------------- what is not an x

    /**
     * A size and not an x. The box a button asks for is the same in both directions under a
     * font-blind ruler, so a container laying one out cannot see the direction at all.
     */
    @Test
    void theMeasuredBoxIsTheSameInBothDirections() {
        build(LayoutDirection.LTR, "abc");
        button.setIcon(new RecordingIcon());
        Size ltr = button.measure(Constraints.loose(500, 500));

        build(LayoutDirection.RTL, "abc");
        button.setIcon(new RecordingIcon());
        Size rtl = button.measure(Constraints.loose(500, 500));
        assertEquals(ltr.width(), rtl.width(), EPS);
        assertEquals(ltr.height(), rtl.height(), EPS);
    }

    /**
     * DOES NOT MOVE. The baseline is the other axis: a mirrored button's caption sits on the same
     * line, and a sweep that reflected it would move text nothing asked to move.
     */
    @Test
    void theBaselineIsTheSameInBothDirections() {
        BaselineButton ltr = new BaselineButton("abc");
        Scene ltrScene = new Scene(ltr);
        ltrScene.setTextRuler(RULER);
        ltrScene.layoutPass(WIDTH, HEIGHT);

        BaselineButton rtl = new BaselineButton("abc");
        rtl.setLayoutDirection(LayoutDirection.RTL);
        Scene rtlScene = new Scene(rtl);
        rtlScene.setTextRuler(RULER);
        rtlScene.layoutPass(WIDTH, HEIGHT);

        assertEquals((HEIGHT - 10) / 2 + 8, ltr.baseline(), EPS, "the band, plus the ascent");
        assertEquals(ltr.baseline(), rtl.baseline(), EPS);
        // And the baseline a BASELINE row aligns on is the one the paint actually drew.
        TextRecorder canvas = new TextRecorder(WIDTH, HEIGHT);
        rtlScene.renderFrame(canvas);
        assertEquals(rtl.baseline(), canvas.ys.get(0), EPS);
    }

    /**
     * DOES NOT MIRROR. {@code MOUSE_LEFT} is a button identity on the pointing device and not a
     * screen side; it answers a search for LEFT, and mirroring it would leave the primary button
     * unable to press anything in a right-to-left interface.
     */
    @Test
    void thePrimaryMouseButtonStillPressesReadingRightToLeft() {
        build(LayoutDirection.RTL, "abc");
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);
        scene.inputBatchEnded();
        assertTrue(button.isArmed(), "the press arms it whichever way the button reads");

        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 10, 10);
        scene.inputBatchEnded();
        assertEquals(1, fired.get());
    }

    /**
     * NOT A SITE. A button takes Enter and Space only, so it has no horizontal arrow decision to
     * mirror; a later sweep that gave it one would be inventing a key binding, not mirroring one.
     */
    @Test
    void theHorizontalArrowsDoNothingInEitherDirection() {
        build(LayoutDirection.LTR, "abc");
        scene.requestFocus(button);
        pressAndRelease(Keys.LEFT);
        pressAndRelease(Keys.RIGHT);
        assertEquals(0, fired.get(), "the default is unchanged");

        build(LayoutDirection.RTL, "abc");
        scene.requestFocus(button);
        pressAndRelease(Keys.LEFT);
        pressAndRelease(Keys.RIGHT);
        assertEquals(0, fired.get(), "and a mirrored button has no arrow binding either");

        // Enter and Space are what a button does answer, and they are not directional at all.
        pressAndRelease(Keys.SPACE);
        assertEquals(1, fired.get());
    }

    private void pressAndRelease(int key) {
        scene.keyEvent(key, true, false, 0);
        scene.keyEvent(key, false, false, 0);
        scene.inputBatchEnded();
    }

    // ------------------------------------------------------------------ fixtures

    /** Exposes the protected baseline hook: a subclass may reach it, a bare test cannot. */
    private static final class BaselineButton extends Button {
        BaselineButton(String text) {
            super(text);
        }

        float baseline() {
            return baselineOffset();
        }
    }

    /** Records where the caption was drawn; a button draws no other text. */
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

package limn.components;

import limn.graphics.Paint;
import limn.input.Keys;
import limn.scene.ControlSize;
import limn.scene.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PasswordField}'s masking: the substitution and the dot's geometry.
 *
 * <p>Two properties, and everything here is one or the other:
 * <ul>
 *   <li><b>One mask code point per source code point, at every step.</b> The mask is the one
 *       place in the text cluster where the painted form is not the model's string; break the
 *       count and {@code TextEditModel}'s offsets desynchronise from what is painted: the caret
 *       drifts, clicks land on a neighbour, selection highlights the wrong run.</li>
 *   <li><b>The dot is one fixed fraction of the body font, at every step.</b> It replaced a
 *       per-step glyph table whose ink went 0.11 / 0.11 / 0.20 / 0.90 / 0.90&nbsp;em: a
 *       4.5&times; cliff between MEDIUM and LARGE that no type ramp asked for, and a mask that
 *       needed a font it could not count on having. These tests read the painted circles, so
 *       they fail on <em>any</em> return to a typeset mask, not just on a wrong size.</li>
 * </ul>
 *
 * <p>The expected ratios are written out here as literals rather than read from the component:
 * a test that reuses the constant under test cannot catch a wrong constant.
 */
class PasswordFieldTest extends ComponentTestBase {

    /** MUSICAL SYMBOL G CLEF, U+1D11E: one code point, two chars. */
    private static final String CLEF = "𝄞";

    /** Mixed BMP + astral: 3 code points, 4 chars; the two counts must never be conflated. */
    private static final String SECRET = "a" + CLEF + "b";

    /** {@code PasswordField.DOT_DIAMETER}, restated. */
    private static final float DIAMETER = 0.36f;

    /** {@code PasswordField.DOT_ADVANCE}, restated. */
    private static final float ADVANCE = 0.56f;

    private static final float EPSILON = 1e-3f;

    @AfterEach
    void restoreProcessDefault() {
        ControlSize.setProcessDefault(ControlSize.MEDIUM);
    }

    // --------------------------------------------- the count invariant, per step

    @Test
    void everyStepMasksOneCodePointPerSourceCodePoint() {
        PasswordField field = new PasswordField();
        field.setText(SECRET);
        int sourceCodePoints = SECRET.codePointCount(0, SECRET.length());
        assertEquals(3, sourceCodePoints, "the fixture itself: 3 code points in 4 chars");

        for (ControlSize step : ControlSize.values()) {
            field.setControlSize(step);
            String shown = field.displayText();
            assertEquals(sourceCodePoints, shown.codePointCount(0, shown.length()),
                    "one dot per source code point at " + step);
            // The mask char must be BMP: with an astral one the char length would double and
            // every char offset taken over the display string would shift.
            assertEquals(sourceCodePoints, shown.length(),
                    "the mask char is a single char at " + step);
        }
    }

    @Test
    void aSurrogatePairMasksToASingleDot() {
        PasswordField field = new PasswordField();
        field.setText(CLEF);
        for (ControlSize step : ControlSize.values()) {
            field.setControlSize(step);
            assertEquals(1, field.displayText().codePointCount(0, 1),
                    "two chars, one code point, ONE dot at " + step
                            + ": counting chars would leak that the character is astral");
        }
    }

    @Test
    void thePrefixMaskCountsExactlyTheCodePointsBeforeTheCaret() {
        // displayPrefix is the only bridge from a model offset to a painted x: the caret's x is
        // the width of the mask of the prefix. Its count must agree with displayText's at every
        // code-point boundary, including the two halves of a surrogate pair.
        PasswordField field = new PasswordField();
        field.setText(SECRET);
        for (ControlSize step : ControlSize.values()) {
            field.setControlSize(step);
            assertEquals(0, field.displayPrefix(0).length(), "nothing before the head at " + step);
            assertEquals(1, field.displayPrefix(1).length(), "after 'a' at " + step);
            assertEquals(2, field.displayPrefix(3).length(),
                    "after the whole surrogate pair at " + step);
            assertEquals(field.displayText(), field.displayPrefix(SECRET.length()),
                    "the full prefix is the full mask at " + step);
        }
    }

    // ------------------------------------------------------ the dot, as painted

    @Test
    void theMaskIsDrawnAndNotTypeset() {
        // The regression that motivated the geometry: U+25CF is absent from the bundled
        // last-resort face, so the old display-step mask resolved through the lazily-loaded
        // fallback chain and the first frame in a fresh process painted .notdef tofu. A circle
        // needs no font, so a masked field must ask for no text at all.
        Dots dots = paint(ControlSize.XLARGE, "abcd", false);
        assertEquals(4, dots.circles.size(), "one circle per code point");
        assertEquals(List.of(), dots.texts, "a masked field typesets nothing");
    }

    @Test
    void theDotIsTheSameFractionOfTheFontAtEveryStep() {
        // THE property. The old glyph ramp painted 1.2pt of ink at XSMALL and 17.1pt at XLARGE
        // against a type ramp that only spans 11 -> 19pt; here the dot IS the type ramp.
        for (ControlSize step : ControlSize.values()) {
            float size = SizeTokens.of(step).body().size();
            Dots dots = paint(step, "abcd", false);
            assertEquals(DIAMETER * size / 2, dots.circles.get(0).radius(), EPSILON,
                    "the dot is " + DIAMETER + " of the body font at " + step);
            assertEquals(ADVANCE * size, dots.pitch(), EPSILON,
                    "and so is its pitch at " + step);
        }
    }

    @Test
    void theDotsAreEvenlySpacedAndNeverFuse() {
        // What the per-step glyph table existed to dodge: BULLET at 11pt fused into a dashed
        // rule. A gap is a number here, not a glyph choice, so it holds at every step.
        for (ControlSize step : ControlSize.values()) {
            Dots dots = paint(step, "abcdefgh", false);
            List<Circle> circles = dots.circles;
            for (int i = 1; i < circles.size(); i++) {
                assertEquals(dots.pitch(), circles.get(i).cx() - circles.get(i - 1).cx(), EPSILON,
                        "dot " + i + " keeps the pitch at " + step);
            }
            assertTrue(dots.pitch() > 2 * circles.get(0).radius(),
                    step + " leaves ink between the dots: pitch " + dots.pitch()
                            + " vs diameter " + 2 * circles.get(0).radius());
        }
    }

    @Test
    void theDotsSitOnTheFieldsCentreLine() {
        // A dot has no baseline to sit on. It is centred in the ink box (the same band the
        // selection fill and the caret span), which TextField centres in the widget.
        for (ControlSize step : ControlSize.values()) {
            Dots dots = paint(step, "abcd", false);
            for (Circle c : dots.circles) {
                assertEquals(dots.height / 2, c.cy(), EPSILON,
                        "the dots are centred at " + step);
            }
        }
    }

    @Test
    void theCaretLandsInTheGapAfterTheDotItFollows() {
        // Measure and paint must agree about where the n-th dot is, or the caret drifts from the
        // character it edits: the caret's x is the MEASURED prefix, the dot's centre is PAINTED.
        // The caret at the head of the run is the run's own left edge, so the two can be read
        // against each other without assuming where the field puts its text.
        Dots head = paint(ControlSize.MEDIUM, "abcd", 0);
        Dots tail = paint(ControlSize.MEDIUM, "abcd", 4);
        float cell = head.pitch();
        assertEquals(head.caretX + cell / 2, head.circles.get(0).cx(), EPSILON,
                "the first dot is centred half a cell into the run");
        assertEquals(head.caretX + 4 * cell, tail.caretX, EPSILON,
                "and the caret after four dots is four whole cells along");
        assertEquals(tail.circles.get(3).cx() + cell / 2, tail.caretX, EPSILON,
                "which is half a cell past the last dot, in the gap, not on the ink");
    }

    // ------------------------------------------- the invariant, observed as behaviour

    @Test
    void aClickLandsAfterAnAstralCharacterRatherThanInsideIt() {
        // The count invariant made observable. Two dots' worth of x must resolve to the model
        // offset AFTER the surrogate pair (char 3, not char 1). A mask that emitted one dot per
        // char instead would put two dots' width inside the pair and this click would land on
        // char 1, mid-character.
        ControlSize step = ControlSize.XLARGE; // deliberately not MEDIUM: the step must not matter
        PasswordField field = new PasswordField();
        field.setControlSize(step);
        Scene scene = new Scene(field);
        scene.setTextRuler(RULER);
        scene.layoutPass(240, 50);
        scene.requestFocus(field);
        field.setText(SECRET);

        // Text starts at the step's own left pad, and a dot's cell is the step's own type ramp
        // times the mask advance: derived, never baked literals.
        float textLeft = SizeTokens.of(step).fieldPadH();
        float cell = ADVANCE * SizeTokens.of(step).body().size();
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, textLeft + 2 * cell, 10);
        scene.inputBatchEnded();

        assertEquals(3, field.model().cursor(),
                "the caret sits on a code-point boundary, past the whole surrogate pair");
    }

    // ------------------------------------------------------------------- reveal

    @Test
    void revealingTypesetsTheSecretAndHidingGoesBackToDots() {
        PasswordField field = new PasswordField();
        field.setText(SECRET);

        field.setRevealed(true);
        assertEquals(SECRET, field.displayText(), "revealed text is the model's own string");
        assertEquals("a" + CLEF, field.displayPrefix(3), "and so is the revealed prefix");
        Dots shown = paint(ControlSize.MEDIUM, SECRET, true);
        assertEquals(List.of(SECRET), shown.texts, "revealed, it is typeset like any field");
        assertEquals(List.of(), shown.circles, "and no dot is drawn");

        field.setRevealed(false);
        assertEquals(3, field.displayText().length(), "back to one dot per code point");
    }

    @Test
    void theProcessDefaultReachesAFieldThatDeclaresNothing() {
        // A password field inside a SMALL form declares no step of its own; the dot still has to
        // follow the resolved one, which is why the step is read per paint and never cached.
        PasswordField field = new PasswordField();
        field.setText("abcd");
        Scene scene = new Scene(field);
        scene.setTextRuler(SCALED_RULER);
        scene.layoutPass(240, 60);

        float medium = firstRadius(scene);
        ControlSize.setProcessDefault(ControlSize.SMALL);
        scene.layoutPass(240, 60);
        float small = firstRadius(scene);

        assertEquals(DIAMETER * SizeTokens.of(ControlSize.MEDIUM).body().size() / 2, medium,
                EPSILON, "MEDIUM by default");
        assertEquals(DIAMETER * SizeTokens.of(ControlSize.SMALL).body().size() / 2, small,
                EPSILON, "the inherited step re-resolves, no stale memo");
        assertNotEquals(medium, small, "and the two steps really do differ");
    }

    // ------------------------------------------------------------------ harness

    private record Circle(float cx, float cy, float radius) {
    }

    /** One frame of a masked field: the dots, any typeset run, and the caret. */
    private static final class Dots extends FakeCanvas {
        private final List<Circle> circles = new ArrayList<>();
        private final List<String> texts = new ArrayList<>();
        private final float height;
        private float caretX = Float.NaN;

        Dots(float width, float height) {
            super(width, height);
            this.height = height;
        }

        float pitch() {
            return circles.get(1).cx() - circles.get(0).cx();
        }

        @Override
        public void fillCircle(float cx, float cy, float radius, Paint paint) {
            circles.add(new Circle(cx, cy, radius));
        }

        @Override
        public void drawText(String text, float x, float baseline, limn.graphics.Font font,
                             Paint paint) {
            texts.add(text);
        }

        /** The caret is the only vertical line a resting field draws. */
        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth,
                             Paint paint) {
            if (x1 == x2) {
                caretX = x1;
            }
        }
    }

    /** One frame, unfocused: dots only, no caret. */
    private Dots paint(ControlSize step, String text, boolean revealed) {
        return paint(step, text, revealed, -1);
    }

    /** One frame with the caret parked at {@code cursor} (a char offset in the source). */
    private Dots paint(ControlSize step, String text, int cursor) {
        return paint(step, text, false, cursor);
    }

    /**
     * Paints one frame of a field at {@code step} and returns what the canvas was asked for.
     * Uses {@link #SCALED_RULER}: the dot is a fraction of the font, so a font-blind ruler would
     * make every step look alike and prove nothing.
     */
    private Dots paint(ControlSize step, String text, boolean revealed, int cursor) {
        PasswordField field = new PasswordField();
        field.setControlSize(step);
        field.setRevealed(revealed);
        Scene scene = new Scene(field);
        scene.setTextRuler(SCALED_RULER);
        scene.layoutPass(320, 80);
        field.setText(text);
        if (cursor >= 0) {
            scene.requestFocus(field);
            field.model().setCursor(cursor, false);
        }
        Dots canvas = new Dots(320, 80);
        scene.renderFrame(canvas);
        return canvas;
    }

    private static float firstRadius(Scene scene) {
        Dots canvas = new Dots(320, 80);
        scene.renderFrame(canvas);
        return canvas.circles.get(0).radius();
    }
}

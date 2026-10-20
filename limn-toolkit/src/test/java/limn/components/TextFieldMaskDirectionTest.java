package limn.components;

import limn.graphics.Icon;
import limn.graphics.Paint;
import limn.graphics.ShapedText;
import limn.input.Keys;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A masked {@link TextField} in an Arabic or a Hebrew layout lays its text out left to right: the
 * digit groups of a card or a phone number in the order they are written, the caret, the
 * selection and a click on that order, the guide continuing the text to the right and the
 * placeholder standing where the text will, while the icons and the trailing button still follow
 * the layout. Read from the shaped
 * line's runs and caret positions, and from where the paint places the line and the caret, never
 * from pixels.
 *
 * <p>Every expectation is arithmetic against {@link #RULER}'s 10pt clusters, which reorder by the
 * bidirectional algorithm as a shaper's do.
 */
class TextFieldMaskDirectionTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final float WIDTH = 400;
    private static final float PAD = SizeTokens.of(ControlSize.MEDIUM).fieldPadH();
    private static final Locale[] RIGHT_TO_LEFT = {Locale.forLanguageTag("ar"), Locale.forLanguageTag("he")};

    private static final String CARD = "0000 0000 0000 0000";
    private static final String PHONE = "+1 (000) 000-0000";

    /** Never rasterized: these tests measure and hit-test, they never paint an icon. */
    private static final Icon MEASURE_ONLY = (pixelSize, dark) -> {
        throw new UnsupportedOperationException("measure-only");
    };

    private TextField field;
    private Scene scene;

    private void build(Locale locale, String pattern) {
        field = new TextField().setPlaceholder("x").setMask(pattern);
        field.setLocale(locale);
        field.setLayoutDirection(LayoutDirection.forLocale(locale));
        scene = new Scene(field);
        scene.setTextRuler(RULER);
        scene.layoutPass(WIDTH, 32);
        scene.requestFocus(field);
    }

    /** What one frame drew: the x of each line of text with its text, and the caret's x. */
    private static final class Drawn extends FakeCanvas {
        final List<String> texts = new ArrayList<>();
        final List<ShapedText> lines = new ArrayList<>();
        final List<Float> textX = new ArrayList<>();
        float caretX = Float.NaN;
        final List<float[]> boxes = new ArrayList<>();

        Drawn() {
            super(WIDTH, 32);
        }

        @Override
        public void drawText(ShapedText text, float x, float y, Paint paint) {
            texts.add(text.text());
            lines.add(text);
            textX.add(x);
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, Paint paint) {
            caretX = x1;
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
            boxes.add(new float[]{x, w});
        }
    }

    private Drawn frame() {
        Drawn canvas = new Drawn();
        scene.renderFrame(canvas);
        return canvas;
    }

    /** The line's runs in visual order run left to right, and their characters in written order. */
    private static void assertWrittenOrder(ShapedText line, String context) {
        assertSame(ShapedText.Direction.LTR, line.baseDirection(), context + ": a left-to-right paragraph");
        int next = 0;
        for (ShapedText.Run run : line.runs()) {
            assertEquals(next, run.charStart(), context + ": the runs, left to right, in written order " + line.runs());
            assertEquals(0, run.level() % 2, context + ": no run reads right to left " + line.runs());
            next = run.charEnd();
        }
        String text = line.text();
        float previous = -1;
        for (int i = 0; i <= text.length(); i++) {
            float x = line.caretX(i, ShapedText.Affinity.DOWNSTREAM);
            assertTrue(x > previous, context + ": each character to the right of the one before, at " + i);
            previous = x;
        }
    }

    // ----------------------------------------------------- the digit groups in written order

    @Test
    void aCardAndAPhoneNumberShowTheirGroupsInWrittenOrderInArabicAndHebrew() {
        for (Locale locale : RIGHT_TO_LEFT) {
            build(locale, CARD);
            field.setText("4111111111111112");
            assertEquals("4111 1111 1111 1112", field.text());
            assertWrittenOrder(field.displayLine(), locale + " card");
            // The line starts at the content's left edge, which the paint draws it from.
            Drawn drawn = frame();
            assertEquals(PAD, drawn.textX.get(drawn.texts.indexOf("4111 1111 1111 1112")), EPS,
                    locale + ": not 1112 1111 1111 4111 flush right");

            build(locale, PHONE);
            field.setText("4155550123");
            assertEquals("+1 (415) 555-0123", field.text());
            assertWrittenOrder(field.displayLine(), locale + " phone");
        }
    }

    @Test
    void aHebrewLetterMaskStillReadsRightToLeftInsideItsLeftToRightLine() {
        build(Locale.forLanguageTag("he"), "AAA-AAA");
        field.setText("אבגדהו"); // alef to vav
        assertEquals("אבג-דהו", field.text());
        ShapedText line = field.displayLine();
        assertSame(ShapedText.Direction.LTR, line.baseDirection());
        // One run of Hebrew, the dash between letters taken into it: alef on the right, the first
        // group to the right of the second, as a Hebrew reader reads it. No letter reversed alone.
        float previous = Float.MAX_VALUE;
        for (int i = 0; i < 7; i++) {
            float right = line.caretX(i, ShapedText.Affinity.DOWNSTREAM);
            assertTrue(right < previous, "each letter to the left of the one before, at " + i);
            previous = right;
        }
        assertTrue(line.caretX(3, ShapedText.Affinity.DOWNSTREAM) > line.caretX(4, ShapedText.Affinity.DOWNSTREAM));
        Drawn drawn = frame();
        assertEquals(PAD, drawn.textX.get(drawn.texts.indexOf(field.text())), EPS,
                "laid out from the content's left edge, as every masked line is");
    }

    @Test
    void settingAMaskReShapesTextAlreadyHeldInALeftToRightLayout() {
        // The held line is keyed on its direction, which a left-to-right layout does not change:
        // the mask arriving has to drop it itself.
        field = new TextField();
        scene = new Scene(field);
        scene.setTextRuler(RULER);
        scene.layoutPass(WIDTH, 32);
        field.setText("אבג-דהו");
        frame();
        assertSame(ShapedText.Direction.RTL, field.displayLine().baseDirection(), "Hebrew decides alone");
        field.setMask("AAA-AAA");
        assertSame(ShapedText.Direction.LTR, field.displayLine().baseDirection(), "masked: left to right");
        field.setMask(null);
        assertSame(ShapedText.Direction.RTL, field.displayLine().baseDirection(), "and back");
    }

    // ----------------------------------------------------- the caret, the selection and a click

    @Test
    void theArrowsTheSelectionAndAClickFollowTheWrittenOrder() {
        for (Locale locale : RIGHT_TO_LEFT) {
            build(locale, CARD);
            field.setText("4111111111111112");
            field.setCaretPosition(0);
            assertEquals(PAD, frame().caretX, EPS, locale + ": index 0 is the left edge");
            drive(scene).press(Keys.RIGHT);
            assertEquals(1, field.caretPosition(), locale + ": Right moves on through the number");
            assertEquals(PAD + 10, frame().caretX, EPS);
            assertEquals(field.caretRect().x(), frame().caretX, EPS, "the caret box agrees with the paint");
            drive(scene).press(Keys.END);
            drive(scene).press(Keys.LEFT);
            assertEquals(18, field.caretPosition(), locale + ": Left moves back");

            field.setCaretPosition(0);
            for (int i = 0; i < 4; i++) {
                drive(scene).press(Keys.RIGHT, Keys.MOD_SHIFT);
            }
            assertEquals(0, field.selectionStart());
            assertEquals(4, field.selectionEnd(), locale + ": Shift+Right selects the first group");
            List<float[]> boxes = frame().boxes;
            float[] band = boxes.get(boxes.size() - 1);
            assertEquals(PAD, band[0], EPS, locale + ": the band covers 4111, on the left");
            assertEquals(40, band[1], EPS);

            click(PAD + 2);
            assertEquals(0, field.caretPosition(), locale + ": a click at the left edge is index 0");
            click(PAD + 151);
            assertEquals(15, field.caretPosition(), locale + ": a click before the last group's 1");
        }
    }

    private void click(float localX) {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, localX, 16);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, localX, 16);
        drive(scene).inputBatchEnded();
    }

    // ------------------------------------------------------------------------------ the guide

    @Test
    void theGuideContinuesTheTextToTheRightAndTheCaretStandsPastItsLiterals() {
        for (Locale locale : RIGHT_TO_LEFT) {
            build(locale, CARD);
            drive(scene).type("4111");
            drive(scene).inputBatchEnded();
            Drawn drawn = frame();
            assertEquals(PAD, drawn.textX.get(drawn.texts.indexOf("4111")), EPS);
            assertEquals(PAD + 40, drawn.textX.get(drawn.texts.indexOf(" ____ ____ ____")), EPS,
                    locale + ": the positions still to fill, after the text");
            assertEquals(PAD + 50, drawn.caretX, EPS, locale + ": past the guide's space, where the 1 will appear");
            assertEquals(PAD + 50, field.caretRect().x(), EPS, "the caret box the input method is given");

            build(locale, "'DE'00 0000");
            drawn = frame();
            assertEquals(PAD, drawn.textX.get(drawn.texts.indexOf("DE__ ____")), EPS,
                    locale + ": the guide of an empty field starts where the text will");
            assertEquals(PAD + 20, drawn.caretX, EPS, locale + ": the caret past DE");
        }
    }

    // ---------------------------------------------------------- the chrome follows the layout

    @Test
    void theIconsAndTheTrailingButtonFollowTheLayoutAndThePlaceholderStandsWhereTheTextWill() {
        Locale arabic = Locale.forLanguageTag("ar");
        build(arabic, CARD);
        SizeTokens t = Theme.current().tokensFor(field);
        field.setLeadingIcon(MEASURE_ONLY);
        field.setTrailingButton(MEASURE_ONLY, () -> { });
        scene.layoutPass(WIDTH, 32);
        field.setText("4111");
        field.setCaretPosition(0);
        // The trailing button is on the left, so the content starts after it; the leading icon is
        // on the right, where the content ends.
        assertEquals(t.fieldTrailing(), field.caretRect().x(), EPS, "the text starts past the button on the left");

        // Without the keyboard and empty, the placeholder stands where the text will: an example
        // value reads in written order, not 555-0123 (415) 1+, and words in Arabic still read
        // right to left, from the same edge.
        build(arabic, PHONE);
        field.setPlaceholder("+1 (415) 555-0123");
        scene.requestFocus(null);
        Drawn drawn = frame();
        assertEquals(PAD, drawn.textX.get(drawn.texts.indexOf("+1 (415) 555-0123")), EPS);
        assertWrittenOrder(drawn.lines.get(drawn.texts.indexOf("+1 (415) 555-0123")), "the placeholder");
        build(arabic, CARD);
        field.setPlaceholder("\u0631\u0642\u0645"); // three Arabic letters
        scene.requestFocus(null);
        drawn = frame();
        assertEquals(PAD, drawn.textX.get(drawn.texts.indexOf("\u0631\u0642\u0645")), EPS);
        assertSame(ShapedText.Direction.RTL, drawn.lines.get(drawn.texts.indexOf("\u0631\u0642\u0645")).baseDirection());
    }
}

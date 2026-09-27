package limn.components;

import limn.graphics.Color;
import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.graphics.ShapedText;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * {@link Spinner}'s number entry in the language it is read in: the decimal separator and the
 * minus sign a locale writes, the tolerances a typed number has always had, the refusal a
 * number the locale cannot read now gets, and the clock face that none of this touches.
 *
 * <p>Every case names its locale and the suite restores whatever was in effect before, because
 * the language is process-wide state and a test that inherited it would be asserting the
 * machine it ran on: this suite was written on a machine whose default is {@code pt-BR}, where
 * {@code "0.50"} is not what a spinner shows.
 */
class SpinnerLocaleTest extends ComponentTestBase {

    private static final Locale GERMAN = Locale.forLanguageTag("de-DE");
    private static final Locale SWEDISH = Locale.forLanguageTag("sv-SE");
    /** Egypt, where CLDR writes Arabic in Arabic-Indic digits; a bare {@code ar} writes Latin. */
    private static final Locale ARABIC_EG = Locale.forLanguageTag("ar-EG");
    private static final Locale PERSIAN = Locale.forLanguageTag("fa-IR");
    private static final String MINUS = "\u2212";

    private static final float BOX_W = SizeTokens.MEDIUM.spinnerWidth();
    private static final float BOX_H = SizeTokens.MEDIUM.controlHeight();

    private Locale original;
    private Spinner spinner;
    private Scene scene;
    private AtomicReference<Double> changed;
    private TextFieldTest.MockClipboard clipboard;
    /** Drives the refusal's fade; a wall clock would make "has it faded" a race. */
    private final AtomicLong clock = new AtomicLong();

    @BeforeEach
    void pinTheLanguage() {
        original = I18n.locale();
        I18n.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void restoreTheLanguage() {
        I18n.setLocale(original);
    }

    private void build(Spinner s) {
        spinner = s;
        changed = new AtomicReference<>();
        spinner.onChange(changed::set);
        scene = new Scene(spinner, clock::get);
        scene.setTextRuler(RULER);
        clipboard = new TextFieldTest.MockClipboard();
        scene.setClipboard(clipboard);
        scene.layoutPass(BOX_W, BOX_H);
    }

    private void typeInto(String text) {
        scene.requestFocus(spinner);
        text.codePoints().forEach(drive(scene)::charTyped);
        drive(scene).inputBatchEnded();
    }

    private void press(int key, int modifiers) {
        drive(scene).keyEvent(key, true, false, modifiers);
        drive(scene).inputBatchEnded();
    }

    private void typeAndCommit(String text) {
        typeInto(text);
        press(Keys.ENTER, 0);
    }

    // ------------------------------------------------------------------ display

    @Test
    void aGermanSpinnerWritesTheDecimalComma() {
        I18n.setLocale(GERMAN);
        build(new Spinner(0, 1, 0.25).setValue(0.5));
        assertEquals("0,50", spinner.text(), "German writes a decimal comma");
        assertTrue(painted().contains("0,50"), "and the box paints what text() says");

        I18n.setLocale(Locale.ENGLISH);
        assertEquals("0.50", spinner.text(), "a switch back reaches the same spinner");
    }

    @Test
    void aSwedishNegativeIsWrittenWithATrueMinusSign() {
        I18n.setLocale(SWEDISH);
        build(new Spinner(-10, 10, 0.5).setValue(-1.5));
        assertEquals(MINUS + "1,5", spinner.text(),
                "Swedish writes U+2212 and a decimal comma, as its own formatter does");
    }

    @Test
    void theDigitsStayTheLocalesAndTheSeparatorsFollowThem() {
        I18n.setLocale(ARABIC_EG);
        build(new Spinner(-10, 10, 0.5).setValue(-1.5));
        assertEquals("-١٫٥", spinner.text(),
                "Arabic-Indic digits with the Arabic decimal separator, and no bidi mark in the sign");

        I18n.setLocale(PERSIAN);
        assertEquals(MINUS + "۱٫۵", spinner.text(),
                "Persian: its extended digits, the Arabic decimal separator and a true minus");

        I18n.setLocale(Locale.forLanguageTag("ar"));
        assertEquals("-1.5", spinner.text(),
                "a bare ar writes Latin digits, so it writes the point that goes with them, "
                        + "not the platform's Arabic separator between Latin digits");
    }

    @Test
    void aLocaleThatAlreadyWritesTheDigitsShownKeepsItsOwnSeparators() {
        // Naming the Latin digits Serbian in Bosnia already writes makes the JDK answer a point
        // for its decimal comma, so a system is named only where the platform's digits differ.
        I18n.setLocale(Locale.forLanguageTag("sr-BA"));
        build(new Spinner(-10, 10, 0.25).setValue(-0.5));
        assertEquals("-0,50", spinner.text());
        typeAndCommit("1,25");
        assertEquals(1.25, spinner.value(), 1e-9, "and it reads the comma it writes");
    }

    @Test
    void aTieRoundsUpAsItAlwaysHas() {
        build(new Spinner(0, 10, 0.01).setSnapToStep(false).setValue(0.125));
        assertEquals("0.13", spinner.text(), "an exact tie rounds up, not to the even neighbour");
        spinner.setValue(1.005);
        assertEquals("1.01", spinner.text(),
                "and a tie is judged on the decimal the value prints as, not its binary expansion");

        build(new Spinner(0, 10, 1).setSnapToStep(false).setValue(2.5));
        assertEquals("3", spinner.text());

        I18n.setLocale(GERMAN);
        build(new Spinner(0, 10, 0.01).setSnapToStep(false).setValue(0.125));
        assertEquals("0,13", spinner.text(), "the same rounding in every language");
    }

    @Test
    void theBoxIsMeasuredInTheLocalesOwnFormat() {
        // The minus and the separator are part of the extremes' width; the measured box and the
        // painted value must come from the one format, or a long negative slides under the arrows.
        I18n.setLocale(SWEDISH);
        build(new Spinner(-1e6, 0, 0.01));
        spinner.setValue(-1e6);
        assertEquals(MINUS + "1000000,00", spinner.text());
        assertEquals(2 * SizeTokens.MEDIUM.spacingMedium() + 110 + SizeTokens.MEDIUM.spinnerButtonW(),
                spinner.measure(limn.scene.Constraints.loose(1000, 200)).width(), 1e-3,
                "eleven characters at 10pt, as the English rendering of the same bound");
    }

    // -------------------------------------------------------------------- entry

    @Test
    void aGermanPointIsNotReadAsADecimalPoint() {
        // The misread that decided the design: a German reader writes 1.234 for one thousand two
        // hundred and thirty-four. With grouping off the point is not a character the format
        // reads, so the text is refused: never read as 1.234, and never guessed into 1234 either,
        // which only a grouping parse could do and which would read a slipped 1.5 as 15.
        I18n.setLocale(GERMAN);
        build(new Spinner(0, 10_000, 0.001).setValue(5));
        typeAndCommit("1.234");
        assertEquals(5.0, spinner.value(), "1.234 is refused under German, not read as 1.234");
        assertNull(changed.get());
        assertFalse(spinner.isEditing(), "and the editor closed on the value that stood");

        typeAndCommit("1,5");
        assertEquals(1.5, spinner.value(), 1e-9, "the German decimal comma commits");
    }

    @Test
    void anEnglishCommaStillReadsAsADecimalPoint() {
        build(new Spinner(0, 10, 0.1));
        typeAndCommit("1,5");
        assertEquals(1.5, spinner.value(), 1e-9, "the tolerance a typed number has always had");
    }

    @Test
    void aPointReadsAsTheDecimalSeparatorWhereTheLocaleDoesNotGroupWithIt() {
        // Swedish groups with a space, so a point in a Swedish number can only be a decimal.
        I18n.setLocale(SWEDISH);
        build(new Spinner(0, 10, 0.1));
        typeAndCommit("2.5");
        assertEquals(2.5, spinner.value(), 1e-9);
    }

    @Test
    void aSwedishKeyboardsHyphenAndATrueMinusBothCommitANegative() {
        I18n.setLocale(SWEDISH);
        build(new Spinner(-10, 10, 0.5).setValue(1));
        typeAndCommit("-2,5");
        assertEquals(-2.5, spinner.value(), 1e-9, "the ASCII hyphen a keyboard types");

        typeAndCommit(MINUS + "3");
        assertEquals(-3.0, spinner.value(), 1e-9, "and the U+2212 the display itself shows");
    }

    @Test
    void aLeadingPlusIsAccepted() {
        build(new Spinner(-10, 10, 1).setValue(0));
        typeAndCommit("+3");
        assertEquals(3.0, spinner.value());

        I18n.setLocale(GERMAN);
        build(new Spinner(-10, 10, 0.5).setValue(0));
        typeAndCommit("+3,5");
        assertEquals(3.5, spinner.value(), 1e-9);
        typeAndCommit("+-3");
        assertEquals(3.5, spinner.value(), 1e-9, "one sign, not two");
    }

    @Test
    void anArabicKeyboardTypesTheArabicDecimalSeparator() {
        I18n.setLocale(ARABIC_EG);
        build(new Spinner(0, 10, 0.5).setValue(1));
        typeAndCommit("٢٫٥");
        assertEquals(2.5, spinner.value(), 1e-9, "٢٫٥ is two and a half, not twenty-five");

        typeAndCommit("3.5");
        assertEquals(3.5, spinner.value(), 1e-9,
                "and the point Arabic does not group with still reads as a decimal");
    }

    @Test
    void theDisplayCommitsToItselfInEveryLanguage() {
        // The editor is seeded from the display, so an edit committed untouched must be the
        // identity whatever the locale wrote: separator, minus sign and digits alike.
        for (String tag : new String[]{"en", "de-DE", "sv-SE", "fr-FR", "de-CH", "pt-BR",
                "ar-EG", "fa-IR", "he", "fi", "ar"}) {
            I18n.setLocale(Locale.forLanguageTag(tag));
            build(new Spinner(-1000, 1000, 0.01).setValue(-123.45));
            String shown = spinner.text();
            scene.requestFocus(spinner);
            settle();
            press(Keys.A, Keys.MOD_CONTROL);
            assertTrue(spinner.isEditing(), tag);
            press(Keys.ENTER, 0);
            assertEquals(-123.45, spinner.value(), 1e-9, tag + " committed " + shown + " as something else");
            assertNull(changed.get(), tag + ": nothing moved, so nothing is reported");
            assertColor(restingBorder(), border(), tag + ": and nothing was refused");
        }
    }

    @Test
    void altGrStillTypesTheGermanComma() {
        // German and Brazilian layouts reach characters through AltGr, which Windows reports as
        // Ctrl+Alt; the key event must not be taken for a shortcut and swallow what follows it.
        I18n.setLocale(GERMAN);
        build(new Spinner(0, 10, 0.5).setValue(1));
        typeInto("2");
        press(Keys.COMMA, Keys.MOD_CONTROL | Keys.MOD_ALT);
        typeInto(",5");
        press(Keys.ENTER, 0);
        assertEquals(2.5, spinner.value(), 1e-9);
    }

    // ---------------------------------------------------------------- refusal

    @Test
    void refusedTextRevertsWithAFlashOfTheDangerColour() {
        build(new Spinner(0, 100, 1).setValue(3));
        settle();
        assertColor(restingBorder(), border(), "a resting spinner is outlined as always");

        clipboard.value = "12 px";
        scene.requestFocus(spinner);
        settle();
        press(Keys.V, Keys.MOD_CONTROL);
        press(Keys.ENTER, 0);

        assertEquals(3.0, spinner.value(), "the refused text commits nothing");
        assertNull(changed.get());
        assertFalse(spinner.isEditing(), "the editor reverts to the value that stood");
        assertEquals("3", spinner.text());
        assertColor(Theme.of(spinner).danger(), border(), "and the border says it was refused");

        settle();
        assertColor(restingBorder(), border(),
                "the flash is brief, and ends on the border the spinner had before it");
    }

    @Test
    void anAcceptedCommitAndAnEscapeDoNotFlash() {
        build(new Spinner(0, 100, 1).setValue(3));
        scene.requestFocus(spinner);
        settle();
        typeAndCommit("42");
        assertEquals(42.0, spinner.value());
        assertColor(restingBorder(), border(), "a number that commits is not a refusal");

        typeInto("4-");
        press(Keys.ESCAPE, 0);
        assertEquals(42.0, spinner.value());
        assertColor(restingBorder(), border(), "and Escape is the user's own revert, not a refusal");
    }

    @Test
    void focusLeavingWithRefusedTextFlashesToo() {
        I18n.setLocale(GERMAN);
        build(new Spinner(0, 10_000, 1).setValue(7));
        typeInto("1.234");
        scene.requestFocus(null);
        drive(scene).inputBatchEnded();
        assertEquals(7.0, spinner.value(), "blur commits, and the commit is refused");
        assertColor(Theme.of(spinner).danger(), border(), "the same answer as Enter gets");
    }

    @Test
    void aStepOverRefusedTextFlashesAndStepsNowhere() {
        // Up mid-edit steps from what is typed, and 1.234 in German is not a number to step from.
        // It used to step from the value behind it and write the result over the text, so what was
        // typed vanished with no sign that it had been refused.
        I18n.setLocale(GERMAN);
        build(new Spinner(0, 10_000, 1).setValue(7));
        scene.requestFocus(spinner);
        settle();
        typeInto("1.234");
        press(Keys.UP, 0);

        assertEquals(7.0, spinner.value(), "the value stands, as it does when Enter is refused");
        assertNull(changed.get(), "and nothing was reported, since nothing moved");
        assertTrue(spinner.isEditing(), "a step never ends the edit, refused or not");
        List<String> shown = painted();
        assertTrue(shown.contains("7") && !shown.contains("1.234"),
                "the editor shows the value that stands: " + shown);
        assertColor(Theme.of(spinner).danger(), border(), "and the border says it was refused");

        settle();
        assertColor(restingBorder(), border(), "the same brief flash Enter's refusal gets");
        press(Keys.UP, 0);
        assertEquals(8.0, spinner.value(), "the next press steps from the value it was shown");
        assertColor(restingBorder(), border(), "and a step that reads is not a refusal");

        // An emptied field is refused by Enter, since a spinner always holds a number, and a
        // step over it is refused the same way rather than stepping from a number no longer shown.
        press(Keys.BACKSPACE, 0);
        press(Keys.DOWN, 0);
        assertEquals(8.0, spinner.value(), "a blank is not a number to step from");
        assertColor(Theme.of(spinner).danger(), border(), "and it is refused as Enter refuses it");
    }

    // ------------------------------------------------------------- time mode

    @Test
    void theClockFaceIsTheSameTwentyFourHourFaceInEveryLanguage() {
        for (Locale locale : new Locale[]{GERMAN, SWEDISH, Locale.US}) {
            I18n.setLocale(locale);
            build(Spinner.time().setValue(7 * 60 + 30));
            assertEquals("07:30", spinner.text(), locale.toString());
            typeAndCommit("18:45");
            assertEquals(18 * 60 + 45.0, spinner.value(), locale.toString());
        }
        I18n.setLocale(ARABIC_EG);
        build(Spinner.time().setValue(7 * 60 + 30));
        assertEquals("٠٧:٣٠", spinner.text(), "only the digits change");
    }

    @Test
    void aTimeSpinnerStillTakesNoDecimalSeparator() {
        // The comma is not a character a clock face has, so it never reaches the editor: what is
        // typed is 730, which has no colon and is refused, rather than 7,30 read as half past.
        I18n.setLocale(GERMAN);
        build(Spinner.time().setValue(8 * 60));
        typeAndCommit("7,30");
        assertEquals(8 * 60.0, spinner.value());
        assertEquals("08:00", spinner.text());
    }

    // ------------------------------------------------------------------ helpers

    /** Every line the frame drew, by text. */
    private List<String> painted() {
        List<String> texts = new ArrayList<>();
        scene.renderFrame(new FakeCanvas(BOX_W, BOX_H) {
            @Override
            public void drawText(ShapedText text, float x, float y, Paint paint) {
                texts.add(text.text());
            }
        });
        return texts;
    }

    /**
     * The paint of the outer border, which is the last round rect a frame strokes. The spinner is
     * damaged first because a frame repaints only what moved, and a settled spinner has not.
     */
    private Color border() {
        spinner.invalidate();
        AtomicReference<Paint> last = new AtomicReference<>();
        scene.renderFrame(new FakeCanvas(BOX_W, BOX_H) {
            @Override
            public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) {
                last.set(paint);
            }
        });
        return (Color) last.get();
    }

    /**
     * Lets every running animation land: a first frame, because a ticker's first frame carries
     * {@code dt == 0} by contract, then frames a quarter of a second apart, because that is the
     * most one frame may step ({@link Scene#MAX_TICK_SECONDS}), until well past both the focus
     * fade and a refusal's flash, so each transition sits exactly on its target.
     */
    private void settle() {
        border();
        for (int frame = 0; frame < 6; frame++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
            border();
        }
    }

    /**
     * The border paint computes with no refusal showing, the outline lerped by a settled focus.
     * Computed rather than rendered, so that reading it cannot advance the clock past a flash.
     */
    private Color restingBorder() {
        Theme theme = Theme.of(spinner);
        return theme.outline().lerp(theme.focusRing(), spinner.isFocused() ? 1f : 0f);
    }

    private static void assertColor(Color expected, Color actual, String message) {
        float eps = 1e-5f;
        assertTrue(Math.abs(expected.r() - actual.r()) < eps
                        && Math.abs(expected.g() - actual.g()) < eps
                        && Math.abs(expected.b() - actual.b()) < eps
                        && Math.abs(expected.a() - actual.a()) < eps,
                message + ": expected " + expected + " but was " + actual);
    }
}

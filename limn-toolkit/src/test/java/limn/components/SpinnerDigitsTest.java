package limn.components;

import limn.graphics.Paint;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 033 at the widget: a spinner's value is displayed in the locale's digits, an Arabic
 * keyboard can type into it, and the editor — which is seeded from the localized display —
 * commits what it shows. The Latin default is asserted beside every localized case, because most
 * of the suite is the safety net for the default.
 */
class SpinnerDigitsTest extends ComponentTestBase {

    private static final Locale ARABIC = Locale.forLanguageTag("ar");
    private static final float BOX_W = 140;
    private static final float BOX_H = 32;

    private Locale original;
    private Spinner spinner;
    private Scene scene;

    @BeforeEach
    void rememberLocale() {
        original = I18n.locale();
        I18n.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void restoreLocale() {
        I18n.setLocale(original);
    }

    private void build(Spinner s) {
        spinner = s;
        scene = new Scene(spinner);
        scene.setTextRuler(RULER);
        scene.layoutPass(BOX_W, BOX_H);
    }

    /** Every line the frame drew, by text. */
    private List<String> painted() {
        var recorder = new FakeCanvas(BOX_W, BOX_H) {
            final List<String> texts = new ArrayList<>();

            @Override
            public void drawText(ShapedText text, float x, float y, Paint paint) {
                texts.add(text.text());
            }
        };
        scene.renderFrame(recorder);
        return recorder.texts;
    }

    private void typeInto(String text) {
        scene.requestFocus(spinner);
        text.codePoints().forEach(scene::charTyped);
        scene.inputBatchEnded();
    }

    @Test
    void theValueIsDisplayedInTheLocalesDigits() {
        I18n.setLocale(ARABIC);
        build(new Spinner(0, 100, 1).setValue(42));
        assertTrue(painted().contains("٤٢"), "an Arabic interface reads ٤٢, not 42");

        I18n.setLocale(Locale.ENGLISH);
        build(new Spinner(0, 100, 1).setValue(42));
        assertTrue(painted().contains("42"), "and the Latin default is exactly what it was");
    }

    @Test
    void aLocaleSwitchReachesAValueAlreadyOnScreen() {
        build(new Spinner(0, 100, 1).setValue(42));
        assertTrue(painted().contains("42"));

        I18n.setLocale(ARABIC);
        assertTrue(painted().contains("٤٢"),
                "the rendered-value memo keys on the i18n epoch: same value, new digits");
    }

    @Test
    void theClockFaceLocalizesItsFields() {
        I18n.setLocale(ARABIC);
        build(Spinner.time().setValue(7 * 60 + 30));
        List<String> texts = painted();
        assertTrue(texts.contains("٠٧"), "hours in the locale's digits; drew " + texts);
        assertTrue(texts.contains("٣٠"), "minutes too");
    }

    @Test
    void anArabicKeyboardTypesAndCommits() {
        I18n.setLocale(ARABIC);
        build(new Spinner(0, 100, 1).setValue(3));
        typeInto("٤٢");
        assertTrue(spinner.isEditing(), "U+0660s start an edit exactly as ASCII digits do");
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(42.0, spinner.value(), "the localized digits commit as the value they name");
    }

    @Test
    void theLocalizedDisplayItselfCommitsUnchanged() {
        // The editor is seeded from the display string, so committing an untouched edit must be
        // the identity whatever digits the display wore. This is the fact that forced parse to
        // fold digits rather than the display to stay ASCII.
        I18n.setLocale(ARABIC);
        build(new Spinner(0, 100, 1).setValue(42));
        typeInto("٥");
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(5.0, spinner.value());
        assertTrue(painted().contains("٥"), "and the committed value is displayed localized again");
    }
}

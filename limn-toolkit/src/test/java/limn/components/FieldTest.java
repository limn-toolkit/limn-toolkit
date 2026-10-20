package limn.components;

import limn.components.date.DateField;
import limn.components.date.DatePicker;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.i18n.I18nString;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A field's layout, its line and the border it asks of its control: the caption above, the control
 * at the field's width, the line under it reserved once and kept, the asterisk after the caption's
 * end in either direction, and the state on the border of each input that draws one.
 */
class FieldTest extends ComponentTestBase {

    private static final Clock TODAY = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC);

    /** Lays {@code field} out at the width of a form's column, its height its own. */
    private static Scene laidOut(Widget<?> field, float width) {
        Column form = new Column();
        form.crossAlignment(Flex.CrossAlignment.STRETCH);
        form.add(field);
        Scene scene = new Scene(new SizedBox(width, 400, form));
        scene.setTextRuler(SCALED_RULER);
        scene.layoutPass(width, 400);
        return scene;
    }

    private static List<Widget<?>> parts(Field field) {
        return field.children();
    }

    private static Label caption(Field field) {
        return (Label) parts(field).get(0);
    }

    private static Label mark(Field field) {
        return (Label) parts(field).get(1);
    }

    private static Label line(Field field) {
        return (Label) parts(field).get(3);
    }

    private static float gap(Widget<?> widget) {
        return Theme.of(widget).tokensFor(widget).gapLabel();
    }

    @Test
    void theCaptionIsAboveTheControlWhichTakesTheFieldsWidthAndNothingIsReservedUnderIt() {
        TextField email = new TextField();
        Field field = new Field("Email", email);
        laidOut(field, 320);

        assertEquals(0, caption(field).y());
        assertEquals(0, caption(field).x(), "the caption starts at the leading edge");
        assertEquals(caption(field).height() + gap(field), email.y(), 1e-3, "one label gap under the caption");
        assertEquals(320, email.width(), 1e-3, "the control is stretched to the field's width");
        assertEquals(email.y() + email.height(), field.height(), 1e-3,
                "a field with neither a helper nor a state reserves nothing under its control");
        assertFalse(line(field).isVisible());
    }

    @Test
    void aHelperReservesTheLineAndAStateOnceSetKeepsItReserved() {
        TextField email = new TextField();
        Field field = new Field("Email", email);
        Scene scene = laidOut(field, 320);
        float bare = field.height();

        field.setValidation(Validation.ERROR, "Enter an address");
        scene.layoutPass(320, 400);
        float withMessage = field.height();
        assertTrue(withMessage > bare, "the message took a line");
        assertEquals(email.y() + email.height() + gap(field), line(field).y(), 1e-3);
        assertEquals("Enter an address", line(field).text());

        field.clearValidation();
        scene.layoutPass(320, 400);
        assertEquals(withMessage, field.height(), 1e-3,
                "the line keeps its height once a state was shown, so the form does not move back");
        assertEquals("", line(field).text());

        Field helped = new Field("Phone", new TextField()).setHelperText("Include the area code");
        laidOut(helped, 320);
        assertTrue(line(helped).isVisible(), "helper text reserves the line from the start");
        assertEquals("Include the area code", line(helped).text());
    }

    @Test
    void theMessageReplacesTheHelperWhileItShowsAndTheHelperComesBack() {
        Field field = new Field("Phone", new TextField()).setHelperText("Include the area code");
        laidOut(field, 320);
        assertNull(line(field).tone(), "the helper is muted, with no tone");

        field.setValidation(Validation.WARNING, "That looks short");
        assertEquals("That looks short", line(field).text());
        assertEquals(Tone.WARNING, line(field).tone());

        field.setValidation(Validation.NONE, "Not shown while the state is none");
        assertEquals("Include the area code", line(field).text(), "no state, no message: the helper");

        field.setValidation(Validation.ERROR);
        assertEquals("Include the area code", line(field).text(), "a state with no message keeps the helper");
        assertSame(Validation.ERROR, field.validation());
    }

    @Test
    void theAsteriskFollowsTheCaptionsEndInBothDirections() {
        Field field = new Field("Email", new TextField()).setRequired(true);
        laidOut(field, 320);
        assertTrue(mark(field).isVisible());
        assertEquals(caption(field).x() + caption(field).width() + gap(field) / 2, mark(field).x(), 1e-3,
                "after the caption, half a label gap away");
        assertEquals("*", mark(field).text());
        assertEquals(Tone.DANGER, mark(field).tone());

        Field rtl = new Field("Email", new TextField()).setRequired(true);
        rtl.setLayoutDirection(LayoutDirection.RTL);
        laidOut(rtl, 320);
        assertEquals(320, caption(rtl).x() + caption(rtl).width(), 1e-3, "the caption starts at the right edge");
        assertEquals(caption(rtl).x() - gap(rtl) / 2, mark(rtl).x() + mark(rtl).width(), 1e-3,
                "and the asterisk follows its end, on its left");

        field.setRequired(false);
        assertFalse(mark(field).isVisible());
    }

    @Test
    void aFieldWithNoCaptionKeepsItsLineAndItsStates() {
        Checkbox terms = new Checkbox("I accept the terms");
        Field field = new Field(null, terms, terms).setRequired(true).setHelperText("Read them first");
        laidOut(field, 320);
        assertFalse(caption(field).isVisible());
        assertFalse(mark(field).isVisible(), "no caption, so no asterisk to follow it");
        assertEquals(0, terms.y(), "the control is at the top");
        assertTrue(line(field).isVisible());
        assertNull(field.label());
    }

    @Test
    void theTextGettersAnswerStringsInTheLanguageInEffectAndTheSourcesTheValuesBehind() {
        // As Label, Button and Card do: label() is the caption as it reads, the access letter's
        // mark taken out, and labelSource() the value a language change re-resolves.
        java.util.Locale portuguese = java.util.Locale.forLanguageTag("pt-BR");
        limn.i18n.StringBundle bundle = limn.i18n.PropertyBundle.of(portuguese, java.util.Map.of(
                "test.field.name", "&Nome", "test.field.help", "Como no documento",
                "test.field.error", "Obrigatório"));
        limn.i18n.I18n.addBundle(bundle);
        try {
            limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
            I18nString name = new I18nString("test.field.name", "&Name");
            I18nString help = new I18nString("test.field.help", "As on the card");
            I18nString error = new I18nString("test.field.error", "Required");
            Field field = new Field(name, new TextField()).setHelperText(help)
                    .setValidation(Validation.ERROR, error);
            String caption = field.label();
            assertEquals("Name", caption);
            assertEquals("As on the card", field.helperText());
            assertEquals("Required", field.validationMessage());
            assertSame(name, field.labelSource());
            assertSame(help, field.helperTextSource());
            assertSame(error, field.validationMessageSource());

            limn.i18n.I18n.setLocale(portuguese);
            assertEquals("Nome", field.label());
            assertEquals("Como no documento", field.helperText());
            assertEquals("Obrigatório", field.validationMessage());

            field.setLabel((I18nString) null).setHelperText((I18nString) null).clearValidation();
            assertNull(field.label());
            assertNull(field.labelSource());
            assertNull(field.helperText());
            assertNull(field.helperTextSource());
            assertNull(field.validationMessage());
            assertNull(field.validationMessageSource());
        } finally {
            limn.i18n.I18n.removeBundle(bundle);
            limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
        }
    }

    /** The texts one frame of {@code scene} draws, laid out again first at a form's size. */
    private static List<String> painted(Scene scene) {
        scene.layoutPass(360, 400);
        List<String> drawn = new ArrayList<>();
        scene.renderFrame(new FakeCanvas(360, 400) {
            @Override
            public void drawText(String text, float x, float y, Font font, Paint paint) {
                drawn.add(text);
            }
        });
        return drawn;
    }

    @Test
    void aValidationMessageReadsInTheLanguageOfTheControlItDescribes() {
        // The owner's rule: a Portuguese date field in an English window says why in Portuguese,
        // and the Field around it shows and returns that, where it showed the reason in its own
        // language and returned it in the caller's. The application's own message follows the
        // control too; with no language declared anywhere, everything is the window's.
        String english = "This date is not available";
        String portuguese = "Esta data não está disponível";
        java.util.Locale ptBr = java.util.Locale.forLanguageTag("pt-BR");
        limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
        try {
            for (String declares : List.of("date", "field", "none")) {
                DateField date = new DateField().setClock(TODAY).setDateFilter(d -> d.getDayOfMonth() != 13);
                Field field = new Field("Delivery", date);
                if (declares.equals("date")) {
                    date.setLocale(ptBr);
                } else if (declares.equals("field")) {
                    field.setLocale(ptBr);
                }
                Scene scene = laidOut(field, 360);
                date.setDate(LocalDate.of(2026, 11, 13));
                String expected = declares.equals("none") ? english : portuguese;
                assertEquals(expected, date.validationMessage(), declares + ": the date's own");
                assertEquals(expected, field.validationMessage(), declares + ": the field returns the control's");
                assertTrue(painted(scene).contains(expected), declares + ": and shows it: " + painted(scene));

                field.setValidation(Validation.ERROR, new I18nString("limn.date.invalid.unavailable", english));
                assertEquals(expected, field.validationMessage(), declares + ": the application's message");
                assertTrue(painted(scene).contains(expected), declares + ": painted " + painted(scene));
            }

            // The control stops declaring: the line goes back to the window's language.
            DateField date = new DateField().setClock(TODAY).setDateFilter(d -> d.getDayOfMonth() != 13);
            date.setLocale(ptBr);
            Field field = new Field("Delivery", date);
            Scene scene = laidOut(field, 360);
            date.setDate(LocalDate.of(2026, 11, 13));
            assertTrue(painted(scene).contains(portuguese));
            date.setLocale(null);
            assertEquals(english, field.validationMessage());
            List<String> after = painted(scene);
            assertTrue(after.contains(english) && !after.contains(portuguese), "followed: " + after);
        } finally {
            limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
        }
    }

    @Test
    void placedMustHoldTheControl() {
        TextField control = new TextField();
        assertThrows(IllegalArgumentException.class, () -> new Field(I18nString.literal("Name"), control,
                new SizedBox(10, 10)));
        SizedBox box = new SizedBox(200, 32, control);
        assertSame(control, new Field(I18nString.literal("Name"), control, box).control());
    }

    @Test
    void theStateIsDrawnOnTheBorderOfEveryInputThatHasOne() {
        TextField text = new TextField();
        new Field("Name", text).setValidation(Validation.WARNING);
        assertSame(Validation.WARNING, text.validation(), "a text field takes it as its own");

        TextArea area = new TextArea();
        new Field("Notes", area).setValidation(Validation.SUCCESS);
        assertSame(Validation.SUCCESS, area.validation());

        ComboBox<String> editable = new ComboBox<>(List.of("One", "Two")).setEditable(true);
        new Field("Choice", editable).setValidation(Validation.ERROR);
        TextField editor = (TextField) editable.children().stream()
                .filter(TextField.class::isInstance).findFirst().orElseThrow();
        assertSame(Validation.ERROR, editor.validation(), "an editable combo's editor draws its frame");

        Color danger = Theme.current().danger();
        assertEquals(danger, borderOf(new ComboBox<>(List.of("One", "Two")), Validation.ERROR));
        assertEquals(danger, borderOf(new Spinner(0, 10, 1), Validation.ERROR));
        assertEquals(Theme.current().warning(), borderOf(new Spinner(0, 10, 1), Validation.WARNING));
    }

    @Test
    void aDateThatRefusesADayStaysRedWhateverTheFieldSays() {
        DateField date = new DateField().setClock(TODAY).setMaxDate(LocalDate.of(2026, 10, 31));
        date.setDate(LocalDate.of(2026, 11, 5));
        assertFalse(date.isValid(), "past the last day it accepts");
        assertEquals(Theme.current().danger(), borderOf(date, Validation.WARNING),
                "its own error is larger than the field's warning");

        DateField fine = new DateField().setClock(TODAY);
        fine.setDate(LocalDate.of(2026, 10, 5));
        assertEquals(Theme.current().warning(), borderOf(fine, Validation.WARNING));

        DatePicker picker = new DatePicker().setClock(TODAY);
        picker.setDate(LocalDate.of(2026, 10, 5));
        assertEquals(Theme.current().success(), borderOf(picker, Validation.SUCCESS));
    }

    @Test
    void aDateFieldsOwnMessageIsShownUntilTheApplicationSaysSomething() {
        DateField date = new DateField().setClock(TODAY).setMaxDate(LocalDate.of(2026, 10, 31));
        Field field = new Field("Delivery", date).setHelperText("Weekdays only");
        Scene scene = laidOut(field, 320);
        assertEquals("Weekdays only", line(field).text());

        date.setDate(LocalDate.of(2026, 11, 5));
        String own = date.validationMessage();
        assertNotNull(own);
        assertEquals(own, line(field).text(), "the date's own reason, followed through its validity");
        assertEquals(Tone.DANGER, line(field).tone());

        field.setValidation(Validation.ERROR, "We do not deliver in November");
        assertEquals("We do not deliver in November", line(field).text(), "the application's replaces it");

        field.clearValidation();
        date.setDate(LocalDate.of(2026, 10, 6));
        assertTrue(date.isValid());
        assertEquals("Weekdays only", line(field).text(), "valid again: the helper");
        scene.layoutPass(320, 400);
    }

    @Test
    void aPaletteChangeRecoloursTheLineWithNoCodeOfTheApplications() {
        Field field = new Field("Email", new TextField()).setValidation(Validation.ERROR, "Enter an address");
        Scene scene = laidOut(field, 320);
        assertEquals(Theme.dark().danger(), lineInk(scene, "Enter an address"));
        Theme.setCurrent(Theme.light());
        assertEquals(Theme.light().danger(), lineInk(scene, "Enter an address"));
    }

    // ------------------------------------------------------------------------------ helpers

    /** The colour {@code input} draws its border in while a field around it holds {@code state}. */
    private static Color borderOf(Widget<?> input, Validation state) {
        new Field("Caption", input).setValidation(state);
        Scene scene = laidOut(input.parent(), 240);
        BorderRecorder canvas = new BorderRecorder();
        scene.requestRender();
        scene.renderFrame(canvas);
        assertFalse(canvas.borders.isEmpty(), "no border drawn by " + input.getClass().getSimpleName());
        // The input's own frame is the widest one drawn: the first that spans its box.
        for (Object[] border : canvas.borders) {
            if ((float) border[0] >= input.width() - 0.01f) {
                return (Color) border[1];
            }
        }
        throw new AssertionError("no border as wide as the " + input.getClass().getSimpleName());
    }

    private static Paint lineInk(Scene scene, String text) {
        InkRecorder canvas = new InkRecorder();
        scene.requestRender();
        scene.renderFrame(canvas);
        for (int i = 0; i < canvas.texts.size(); i++) {
            if (canvas.texts.get(i).equals(text)) {
                return canvas.inks.get(i);
            }
        }
        throw new AssertionError("\"" + text + "\" was not drawn: " + canvas.texts);
    }

    private static final class InkRecorder extends FakeCanvas {
        final List<String> texts = new ArrayList<>();
        final List<Paint> inks = new ArrayList<>();

        InkRecorder() {
            super(400, 400);
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            texts.add(text);
            inks.add(paint);
        }
    }

    /** Records every rounded border: its width and its colour. */
    private static final class BorderRecorder extends FakeCanvas {
        final List<Object[]> borders = new ArrayList<>();

        BorderRecorder() {
            super(400, 400);
        }

        @Override
        public void drawRoundRectBorder(float x, float y, float width, float height, float radius,
                                        float borderWidth, Paint paint) {
            borders.add(new Object[] {width, paint});
        }

        @Override
        public void fillRoundRectWithBorder(float x, float y, float width, float height, float radius,
                                            Paint fill, float borderWidth, Paint border) {
            borders.add(new Object[] {width, border});
        }
    }
}

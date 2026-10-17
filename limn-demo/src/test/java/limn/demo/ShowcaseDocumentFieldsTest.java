package limn.demo;

import limn.components.Label;
import limn.components.TextField;
import limn.components.Theme;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessUi;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The customer form's CPF, IBAN and card fields, typed into as a user types: nothing said while a
 * position is still to fill, then the check's verdict in the field's state and in the line under it.
 */
class ShowcaseDocumentFieldsTest {

    private HeadlessUi ui;
    private Scene scene;
    private HeadlessWindow window;

    @BeforeEach
    void openThePage() {
        ui = new HeadlessUi(() -> 0L);
        I18n.setLocale(Locale.ENGLISH);
        Theme.setCurrent(Theme.dark());
        TextRulers.install(HeadlessWindow.RULER);
        scene = new Scene(BasicsScene.content(), () -> 0L);
        window = new HeadlessBackend(ui.runtime()).open("inputs", 900, 2400);
        scene.bind(window);
        window.frame();
    }

    @AfterEach
    void closeThePage() {
        TextRulers.uninstall(HeadlessWindow.RULER);
        ui.close();
    }

    @Test
    void theCpfIsCheckedOnceItsElevenDigitsAreIn() {
        TextField cpf = field("000.000.000-00");
        assertEquals(TextField.Validation.SUCCESS, cpf.validation(), "the page opens on a valid CPF");
        assertEquals(BasicsScene.Text.CHECK_DIGITS_MATCH.get(), line(cpf).text());

        focusAtEnd(cpf).press(Keys.BACKSPACE);
        assertEquals("123.456.789-0", cpf.text());
        assertEquals(TextField.Validation.NONE, cpf.validation(), "a digit still to type");
        assertEquals("", line(cpf).text());

        drive(scene).type("8");
        assertEquals(TextField.Validation.ERROR, cpf.validation(), "123.456.789-08's last check digit is 9");
        assertEquals(BasicsScene.Text.CPF_INVALID.get(), line(cpf).text());
    }

    @Test
    void theIbanTypedWithoutItsCountryReadsAsAGermanOneAndPasses() {
        TextField iban = field("'DE'00 0000 0000 0000 0000 00");
        focusAtEnd(iban);
        for (int i = 0; i < 40 && !iban.text().isEmpty(); i++) {
            drive(scene).press(Keys.BACKSPACE);
        }
        assertEquals("", iban.text());
        assertEquals(TextField.Validation.NONE, iban.validation());

        drive(scene).type("89370400440532013000");
        assertEquals("DE89 3704 0044 0532 0130 00", iban.text(), "the country is the mask's");
        assertEquals(TextField.Validation.SUCCESS, iban.validation());
        assertEquals(BasicsScene.Text.CHECK_DIGITS_MATCH.get(), line(iban).text());
    }

    @Test
    void theCardsWrongLastDigitIsCaughtAndItsRightOnePasses() {
        TextField card = field("0000 0000 0000 0000");
        assertEquals(TextField.Validation.ERROR, card.validation(), "the page opens on 4111 1111 1111 1112");
        assertEquals(BasicsScene.Text.CARD_INVALID.get(), line(card).text());

        focusAtEnd(card).press(Keys.BACKSPACE).type("1");
        assertEquals("4111 1111 1111 1111", card.text());
        assertEquals(TextField.Validation.SUCCESS, card.validation());
        assertEquals(BasicsScene.Text.CARD_VALID.get(), line(card).text());
    }

    private limn.testing.SceneDriver focusAtEnd(TextField field) {
        scene.requestFocus(field);
        return drive(scene).press(Keys.END);
    }

    /** The page's field masked by {@code mask}. */
    private TextField field(String mask) {
        TextField found = find(scene.root(), mask);
        if (found == null) {
            throw new AssertionError("no field masked " + mask);
        }
        return found;
    }

    private static TextField find(Widget<?> at, String mask) {
        if (at instanceof TextField field && mask.equals(field.mask())) {
            return field;
        }
        for (Widget<?> child : at.children()) {
            TextField found = find(child, mask);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The message line under {@code field}: the second of the column the field and it share. */
    private static Label line(TextField field) {
        return (Label) field.parent().children().get(1);
    }
}

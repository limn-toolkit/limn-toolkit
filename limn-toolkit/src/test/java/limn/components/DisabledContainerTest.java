package limn.components;

import limn.accessibility.Accessible;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.ShapedText;
import limn.input.Keys;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * A control inside a disabled or hidden container is out of reach on every path, and not only on
 * the pointer's and Tab's: {@code isEnabled()} answers for the whole ancestry, so code moving the
 * focus, a dialog giving the focus back as it closes, the control's own paint and what assistive
 * technologies are told all read the same fact. Each case below was a workaround hunt's probe
 * before it was a test: the button took the focus and ran its action on Enter.
 */
class DisabledContainerTest extends AccessibleComponentTestBase {

    private final int[] fired = {0};
    private Button save;
    private TextField field;
    private Column section;

    /** A section holding a button and a field, bound to a scene, with the button focusable. */
    private void bindForm() {
        save = new Button("Save").onAction(() -> fired[0]++);
        field = new TextField();
        section = new Column();
        section.add(save);
        section.add(field);
        Column root = new Column();
        root.add(section);
        bind(root);
    }

    @Test
    void theEffectiveStateFollowsTheContainerAndTheOwnFlagComesBack() {
        bindForm();
        save.setEnabled(false);
        section.setEnabled(false);
        assertFalse(field.isEnabled(), "a field inside a disabled section is disabled");
        section.setEnabled(true);
        assertTrue(field.isEnabled(), "and enabled again with it");
        assertFalse(save.isEnabled(), "while a button disabled by its own flag stays disabled");
    }

    @Test
    void codeCannotFocusAControlInsideADisabledSection() {
        bindForm();
        section.setEnabled(false);

        save.requestFocus();
        assertFalse(save.isFocused(), "requestFocus refuses a button whose section is disabled");
        drive(scene).press(Keys.SPACE);
        drive(scene).press(Keys.ENTER);
        assertEquals(0, fired[0], "and the keyboard cannot run its action");

        field.requestFocus();
        assertFalse(field.isFocused(), "nor a field there");
        drive(scene).type("abc");
        assertEquals("", field.text(), "so typing changes nothing in it");
    }

    @Test
    void codeCannotFocusAControlInsideAHiddenSection() {
        bindForm();
        section.setVisible(false);
        frame();

        field.requestFocus();
        assertFalse(field.isFocused(), "requestFocus refuses a field whose section is hidden");
        drive(scene).type("xyz");
        assertEquals("", field.text(), "so typing changes nothing in it");
    }

    @Test
    void aClosingDialogDoesNotGiveTheFocusBackIntoASectionItsAnswerDisabled() {
        bindForm();
        save.requestFocus();
        assertTrue(save.isFocused());
        Button ok = new Button("OK");
        Column dialog = new Column();
        dialog.add(ok);
        scene.pushOverlay(dialog);
        frame();

        section.setEnabled(false); // the dialog's answer locks the form
        scene.removeOverlay(dialog);
        assertNotSame(save, scene.focusedWidget(),
                "the focus does not go back to a button whose section the answer disabled");
        drive(scene).press(Keys.ENTER);
        assertEquals(0, fired[0], "and Enter does not run it a second time");
    }

    @Test
    void aControlInsideADisabledSectionPaintsDisabledAndIsPublishedNotEnabled() {
        bindForm();
        section.setEnabled(false);
        frame();

        InkCanvas ink = new InkCanvas();
        save.invalidate();
        scene.renderFrame(ink);
        int at = ink.texts.indexOf("Save");
        assertTrue(at >= 0, "the caption was drawn: " + ink.texts);
        assertEquals(Theme.current().disabledText(), ink.paints.get(at),
                "a button inside a disabled section draws its caption in the disabled ink");
        assertFalse(node("Save").has(Accessible.State.ENABLED),
                "and is published as not enabled" + describe(tree()));

        section.setEnabled(true);
        frame();
        ink = new InkCanvas();
        save.invalidate();
        scene.renderFrame(ink);
        at = ink.texts.indexOf("Save");
        assertTrue(at >= 0, "the caption was drawn: " + ink.texts);
        assertNotEquals(Theme.current().disabledText(), ink.paints.get(at),
                "the section enabled again, the caption takes its ink back");
        assertTrue(node("Save").has(Accessible.State.ENABLED), describe(tree()));
    }

    /**
     * A radio group chooses its tab stop by each member's own flag: chosen by the effective state
     * while its form was disabled, the stop went to nobody, and nothing tells the group when the
     * form comes back.
     */
    @Test
    void aRadioGroupSelectedWhileItsFormIsDisabledKeepsATabStopForWhenItComesBack() {
        RadioButton small = new RadioButton("Small");
        RadioButton large = new RadioButton("Large");
        ButtonGroup group = new ButtonGroup().add(small).add(large);
        section = new Column();
        section.add(small);
        section.add(large);
        Column root = new Column();
        root.add(section);
        bind(root);

        section.setEnabled(false);
        group.setSelectedIndex(1); // the stop is chosen again while the form is disabled
        section.setEnabled(true);
        frame();

        scene.focusTraverse(false);
        assertSame(large, scene.focusedWidget(), "Tab reaches the selected member again");
    }

    /**
     * A combo made editable inside a disabled form: its field follows the combo by the effective
     * state, and has no flag of its own copied over that would outlast the form's.
     */
    @Test
    void aComboMadeEditableInsideADisabledFormTakesTheKeyboardWhenTheFormComesBack() {
        ComboBox<String> combo = new ComboBox<>(List.of("One", "Two"));
        section = new Column();
        section.add(combo);
        Column root = new Column();
        root.add(section);
        bind(root);

        section.setEnabled(false);
        combo.setEditable(true);
        section.setEnabled(true);
        frame();

        scene.focusTraverse(false);
        assertTrue(scene.focusedWidget() != null && scene.focusedWidget().parent() == combo,
                "Tab lands on the combo's field: " + scene.focusedWidget());
    }

    /** Records every caption drawn and the paint it was drawn with. */
    private static final class InkCanvas extends FakeCanvas {
        final List<String> texts = new ArrayList<>();
        final List<Paint> paints = new ArrayList<>();

        InkCanvas() {
            super(400, 300);
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            texts.add(text);
            paints.add(paint);
        }

        @Override
        public void drawText(ShapedText text, float x, float y, Paint paint) {
            texts.add(text.text());
            paints.add(paint);
        }
    }
}

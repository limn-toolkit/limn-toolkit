package limn.components;

import limn.graphics.Color;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Widget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colour well, driven through a real scene with no GL. The dialog it raises is
 * headless too: {@link Dialog} is a usable state machine with no window, and its panel
 * is laid out here the way {@link DialogTest} lays one out.
 *
 * <p>{@link #dismissingThePickerReportsTheColourItPutBack()} is the one to keep: the
 * button's contract is that {@link ColorPickerButton#onChange} always carries the current
 * answer, including the one Cancel restores. An application that simply applies what it is
 * handed is correct only because of that; every other spelling makes the caller remember
 * what the colour was before, which is what this control exists to avoid.
 */
class ColorPickerButtonTest extends ComponentTestBase {

    private ColorPickerButton button;
    private Scene scene;
    private final List<Color> changes = new ArrayList<>();

    @BeforeEach
    void layOutAButton() {
        button = new ColorPickerButton(Color.rgb(0xF59E0B));
        button.onChange(changes::add);
        scene = new Scene(button);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
    }

    /** Lays out the headless dialog's card and answers the picker inside it. */
    private ColorPicker openAndLayOutPicker() {
        button.openPicker();
        Dialog dialog = button.openDialog();
        assertNotNull(dialog, "the button did not raise a dialog");
        Scene card = new Scene(dialog.contentRoot());
        card.setTextRuler(RULER);
        card.layoutPass(400, 600);
        ColorPicker picker = findPicker(dialog.contentRoot());
        assertNotNull(picker, "the dialog's content is not a colour picker");
        return picker;
    }

    private static ColorPicker findPicker(Widget root) {
        if (root instanceof ColorPicker picker) {
            return picker;
        }
        for (Widget child : root.children()) {
            ColorPicker found = findPicker(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // --- the value ----------------------------------------------------------

    @Test
    void theButtonStartsOnTheColourItWasGiven() {
        assertEquals(Color.rgb(0xF59E0B), button.color());
        assertEquals("#F59E0B", button.text(), "the caption follows the colour by default");
    }

    @Test
    void settingTheColourDirectlyDoesNotNotify() {
        button.setColor(Color.rgb(0x3B82F6));
        assertEquals(Color.rgb(0x3B82F6), button.color());
        assertEquals(List.of(), changes,
                "the application is the source of this change; echoing it back invites a loop");
    }

    @Test
    void turningAlphaOffMakesTheAnswerOpaqueAtOnce() {
        button.setColor(Color.rgba(0x3B82F6, 0.4f));
        assertEquals(0.4f, button.color().a(), 0.01f);

        button.setAlphaEnabled(false);
        assertEquals(1f, button.color().a());
        assertEquals(Color.rgb(0x3B82F6), button.setColor(Color.rgba(0x3B82F6, 0.2f)).color(),
                "with alpha off, a translucent colour set later is opaque too");
    }

    // --- the dialog ---------------------------------------------------------

    @Test
    void thePickerOpensOnTheButtonsColour() {
        ColorPicker picker = openAndLayOutPicker();
        assertTrue(button.isPickerOpen());
        assertEquals(button.color().toHex(), picker.color().toHex());
        assertTrue(picker.isAlphaEnabled());
    }

    @Test
    void aSecondOpenDoesNotRaiseASecondDialog() {
        button.openPicker();
        Dialog first = button.openDialog();
        button.openPicker();
        assertSame(first, button.openDialog(),
                "two pickers over one button would each hold their own 'before' colour");
    }

    @Test
    void alphaOffReachesThePickerToo() {
        button.setAlphaEnabled(false);
        assertFalse(openAndLayOutPicker().isAlphaEnabled());
    }

    @Test
    void thePickerUpdatesTheButtonWhileItIsBeingUsed() {
        ColorPicker picker = openAndLayOutPicker();
        changes.clear();

        assertTrue(picker.applyHex("#00FF00"));
        assertEquals(Color.rgb(0x00FF00), button.color());
        assertEquals(List.of(Color.rgb(0x00FF00)), changes,
                "a picker shows its answer live; the button passes it straight on");
    }

    @Test
    void confirmingKeepsTheChosenColourAndClosesTheDialog() {
        ColorPicker picker = openAndLayOutPicker();
        picker.applyHex("#00FF00");
        changes.clear();

        button.openDialog().dismiss("ok");
        assertFalse(button.isPickerOpen());
        assertEquals(Color.rgb(0x00FF00), button.color());
        assertEquals(List.of(), changes, "OK confirms what was already reported");
    }

    @Test
    void dismissingThePickerReportsTheColourItPutBack() {
        Color before = button.color();
        ColorPicker picker = openAndLayOutPicker();
        picker.applyHex("#00FF00");
        changes.clear();

        button.openDialog().dismiss("cancel");
        assertFalse(button.isPickerOpen());
        assertEquals(before, button.color());
        assertEquals(List.of(before), changes,
                "the caller applied green live and is never told to put it back otherwise");
    }

    @Test
    void aClosedDialogCanBeOpenedAgain() {
        openAndLayOutPicker().applyHex("#00FF00");
        button.openDialog().dismiss("ok");

        ColorPicker second = openAndLayOutPicker();
        assertEquals("#00FF00", second.color().toHex(),
                "the second picker opens on what the first one settled");
    }

    @Test
    void spaceOpensThePickerOnTheReleaseAndConsumesTheKey() {
        button.requestFocus();
        assertTrue(button.isFocused());

        scene.keyEvent(Keys.SPACE, true, false, 0);
        scene.inputBatchEnded();
        assertFalse(button.isPickerOpen(), "the press arms; the release is what acts");

        scene.keyEvent(Keys.SPACE, false, false, 0);
        scene.inputBatchEnded();
        assertTrue(button.isPickerOpen());
    }

    @Test
    void clickingOpensThePicker() {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 10, 10);
        scene.inputBatchEnded();
        assertTrue(button.isPickerOpen());
    }

    // --- caption and layout -------------------------------------------------

    @Test
    void theCaptionCanBeReplacedAndPutBack() {
        button.setText("Accent");
        assertEquals("Accent", button.text());
        button.setColor(Color.BLACK);
        assertEquals("Accent", button.text(), "a caption that was set does not follow the colour");

        button.setTextFromColor();
        assertEquals("#000000", button.text());
    }

    @Test
    void theHexCaptionGrowsWhenTheColourGainsAlpha() {
        assertEquals("#F59E0B", button.text());
        button.setColor(Color.rgba(0xF59E0B, 0.5f));
        assertEquals("#F59E0B80", button.text());
    }

    /**
     * An empty caption leaves chrome and the chip (the form a dense inspector column
     * wants), and the box has to lose the caption's gap with it, not just its text.
     */
    @Test
    void anEmptyCaptionDropsItsGapToo() {
        float captioned = button.measure(Constraints.loose(400, 300)).width();
        button.setText("");
        float bare = button.measure(Constraints.loose(400, 300)).width();

        SizeTokens t = SizeTokens.MEDIUM;
        assertEquals(captioned - RULER.measure("#F59E0B", t.body()).width() - t.gapIcon(),
                bare, 0.01f);
    }

    @Test
    void theBoxFollowsTheSizeStep() {
        float medium = button.measure(Constraints.loose(400, 300)).height();
        button.withControlSize(ControlSize.LARGE);
        float large = button.measure(Constraints.loose(400, 300)).height();
        assertTrue(large > medium, "a control that ignores the step is one frozen at MEDIUM");
    }
}

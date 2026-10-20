package limn.components;

import limn.accessibility.Accessible;
import limn.input.Keys;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.AccessibleHarness;
import limn.testing.AccessibleTrees;
import limn.testing.StubWindow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An anchored popup drawn in the scene that is fading out has let go: a press during its fade
 * reaches what is beneath it and the keyboard goes there, as it does once the layer is gone. The
 * layer swallowed that press and took the focus, and when it went the keyboard was nowhere.
 */
class AnchoredPopupClosingLayerTest extends ComponentTestBase {

    @Test
    void aClickDuringAPopoversFadeOutReachesTheFieldBeneath() {
        aClickDuringTheFadeReachesTheFieldBeneath(false);
    }

    @Test
    void aClickDuringAComboListsFadeOutReachesTheFieldBeneath() {
        aClickDuringTheFadeReachesTheFieldBeneath(true);
    }

    private void aClickDuringTheFadeReachesTheFieldBeneath(boolean combo) {
        Button filter = new Button("Filter");
        TextField other = new TextField().setAccessibleName("Other");
        ComboBox<String> status = new ComboBox<>(List.of("Open", "Paid")).setDisplayMode(DisplayMode.IN_SCENE);
        // The field beside the popups, where a press passes their fading cards by.
        Column left = new Column();
        left.add(filter);
        left.add(status);
        limn.scene.layout.Row root = new limn.scene.layout.Row();
        root.add(left);
        root.add(new SizedBox(150, 10));
        root.add(new SizedBox(100, 30, other));
        AccessibleHarness harness = new AccessibleHarness(runtime, root, new StubWindow(false));
        harness.scene.setTextRuler(RULER);
        harness.frame();
        new Popover(new SizedBox(100, 30, new TextField().setAccessibleName("Inside")))
                .setDisplayMode(DisplayMode.IN_SCENE).attachTo(filter);
        drive(harness.scene).click(combo ? status : filter);
        harness.advanceTime(400, null);

        drive(harness.scene).press(Keys.ESCAPE);
        harness.advanceTime(30, null);
        drive(harness.scene).click(other);
        harness.frame();
        assertSame(other, harness.scene.focusedWidget(), "the click 30 ms into the fade reached the field");
        harness.advanceTime(400, null);
        assertSame(other, harness.scene.focusedWidget(), "and the keyboard stays there once the layer is gone");
        assertEquals(1, AccessibleTrees.withState(harness.tree(), Accessible.State.FOCUSED).size());
        drive(harness.scene).type("z");
        harness.frame();
        assertEquals("z", other.text());
        assertFalse(status.isOpen());
    }

    // ------------------------------------------------------------------ an editable combo's list

    /** An editable combo beside a button: its list leaves the keyboard in the combo's field. */
    private ComboBox<String> editable(Button other) {
        ComboBox<String> combo = new ComboBox<>(List.of("alpha", "beta", "gamma"))
                .setDisplayMode(DisplayMode.IN_SCENE).setEditable(true);
        limn.scene.layout.Row root = new limn.scene.layout.Row();
        root.add(combo);
        root.add(new SizedBox(150, 10));
        root.add(other);
        harness = new AccessibleHarness(runtime, root, new StubWindow(false));
        harness.scene.setTextRuler(RULER);
        harness.frame();
        harness.scene.requestFocus(combo.children().get(0));
        harness.frame();
        combo.open();
        harness.advanceTime(400, null);
        return combo;
    }

    private AccessibleHarness harness;

    @Test
    void aClickDuringAnEditableComboListsFadeOutReachesTheButtonBeside() {
        int[] clicks = {0};
        Button other = new Button("Other").onAction(() -> clicks[0]++);
        ComboBox<String> combo = editable(other);
        assertTrue(combo.isOpen(), "sanity");
        drive(harness.scene).press(Keys.ESCAPE);
        harness.advanceTime(30, null);
        drive(harness.scene).click(other);
        harness.frame();
        assertEquals(1, clicks[0], "the click 30 ms into the list's fade reached the button");
    }

    @Test
    void aClickBesideAnOpenEditableComboListLeavesTheKeyboardInItsField() {
        int[] clicks = {0};
        Button other = new Button("Other").onAction(() -> clicks[0]++);
        ComboBox<String> combo = editable(other);
        Widget<?> typing = harness.scene.focusedWidget();
        drive(harness.scene).click(other);
        harness.frame();
        assertFalse(combo.isOpen(), "a press beside the list closes it");
        harness.advanceTime(400, null);
        assertSame(typing, harness.scene.focusedWidget(), "and the keyboard is still in the combo's field");
        drive(harness.scene).type("x");
        harness.frame();
        assertTrue(combo.text().contains("x"), "typing reaches it: " + combo.text());
    }

    // ------------------------------------------------------------------ window shortcuts

    @Test
    void aWindowShortcutWorksDuringAPopoversFadeOut() {
        Button filter = new Button("Filter");
        limn.scene.layout.Row root = new limn.scene.layout.Row();
        root.add(filter);
        harness = new AccessibleHarness(runtime, root, new StubWindow(false));
        harness.scene.setTextRuler(RULER);
        harness.frame();
        int[] saves = {0};
        harness.scene.addShortcutHandler(event -> {
            if (event.key() == Keys.S && (event.modifiers() & Keys.MOD_CONTROL) != 0 && event.isPressed()) {
                saves[0]++;
                return true;
            }
            return false;
        });
        Popover popover = new Popover(new SizedBox(100, 30, new TextField().setAccessibleName("Inside")))
                .setDisplayMode(DisplayMode.IN_SCENE).attachTo(filter);
        drive(harness.scene).click(filter);
        harness.advanceTime(400, null);
        drive(harness.scene).press(Keys.S, Keys.MOD_CONTROL);
        assertEquals(0, saves[0], "sanity: not while the popover holds the keyboard");
        drive(harness.scene).press(Keys.ESCAPE);
        harness.advanceTime(30, null);
        assertFalse(popover.isOpen());
        drive(harness.scene).press(Keys.S, Keys.MOD_CONTROL);
        assertEquals(1, saves[0], "the keyboard is the window's again, its shortcuts with it");
    }

    @Test
    void aWindowShortcutWorksWhileAnEditableComboListIsOpen() {
        Button other = new Button("Other");
        ComboBox<String> combo = editable(other);
        int[] saves = {0};
        harness.scene.addShortcutHandler(event -> {
            if (event.key() == Keys.S && (event.modifiers() & Keys.MOD_CONTROL) != 0 && event.isPressed()) {
                saves[0]++;
                return true;
            }
            return false;
        });
        assertTrue(combo.isOpen(), "sanity");
        drive(harness.scene).press(Keys.S, Keys.MOD_CONTROL);
        assertEquals(1, saves[0], "the combo's field has the keyboard, and the window its shortcuts");
    }
}

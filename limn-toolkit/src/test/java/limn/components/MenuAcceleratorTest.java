package limn.components;

import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Accelerators and mnemonics as an application meets them: a chord typed anywhere in the scene
 * reaching the right item, and the chords that must <em>not</em> reach one.
 *
 * <p>The dispatch ordering this rests on (focused widget first, handlers second, Tab last) is
 * the scene's and is pinned in the toolkit's own suite. What is asserted here is everything the
 * menu system decides once it has been offered the key.
 */
class MenuAcceleratorTest extends ComponentTestBase {

    /** A focusable widget that can be told to claim a chord, standing in for a text field. */
    private static final class Greedy extends Widget {
        boolean claimEverything;

        Greedy() {
            setFocusable(true);
        }

        @Override
        protected limn.scene.Size onMeasure(limn.scene.Constraints constraints) {
            return constraints.constrain(40, 20);
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (claimEverything && event.isPressed()) {
                event.consume();
            }
        }
    }

    private final List<String> ran = new ArrayList<>();
    private Greedy field;
    private MenuBar bar;
    private Scene scene;

    /** A bar with File(Alt+F) → Save (Cmd/Ctrl+S) and a Recent submenu holding Reopen. */
    private MenuBar buildBar() {
        Menu recent = new Menu()
                .add(MenuItem.of("Reopen", () -> ran.add("reopen"))
                        .setAccelerator(Accelerator.command(Keys.R, Keys.MOD_SHIFT)));
        Menu file = new Menu()
                .add(MenuItem.of("Save", () -> ran.add("save"))
                        .setAccelerator(Accelerator.command(Keys.S)))
                .add(MenuItem.of("Print", () -> ran.add("print"))
                        .setAccelerator(Accelerator.command(Keys.P))
                        .setEnabled(false))
                .addSubmenu("Recent", recent);
        return new MenuBar().addMenu("File", 'F', file);
    }

    private void build() {
        field = new Greedy();
        bar = buildBar();
        Column page = new Column();
        page.add(bar);
        page.add(field);
        scene = new Scene(page);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
    }

    private void press(int key, int modifiers) {
        scene.keyEvent(key, true, false, modifiers);
        scene.inputBatchEnded();
    }

    private static int command() {
        return Accelerator.commandModifier();
    }

    // ------------------------------------------------------------------ dispatch

    @Test
    void aChordNobodyFocusedWantsRunsItsItem() {
        build();
        field.requestFocus();

        press(Keys.S, command());

        assertEquals(List.of("save"), ran);
    }

    @Test
    void theFocusedWidgetKeepsAChordItClaims() {
        build();
        field.claimEverything = true;
        field.requestFocus();

        press(Keys.S, command());

        assertEquals(List.of(), ran,
                "a field that consumed the key must keep it: this is the Ctrl+C case");
    }

    @Test
    void anItemInASubmenuIsReachable() {
        build();
        press(Keys.R, command() | Keys.MOD_SHIFT);
        assertEquals(List.of("reopen"), ran, "the search descends into submenus");
    }

    @Test
    void aDisabledItemsChordRunsNothing() {
        build();
        press(Keys.P, command());
        assertEquals(List.of(), ran,
                "a disabled row is no more reachable by its chord than by the pointer");
    }

    @Test
    void anExtraModifierIsADifferentChord() {
        build();
        press(Keys.S, command() | Keys.MOD_SHIFT);
        assertEquals(List.of(), ran,
                "Cmd+Shift+S is not Cmd+S, or the two could never be different commands");
    }

    /**
     * A held chord must not run its command once per repeat: Ctrl+W would close documents a
     * frame apart, and a chord on a check item would flip it at the keyboard's repeat rate.
     */
    @Test
    void anAutoRepeatIsNotASecondPress() {
        build();
        scene.keyEvent(Keys.S, true, false, command());
        scene.keyEvent(Keys.S, true, true, command());
        scene.keyEvent(Keys.S, true, true, command());
        scene.inputBatchEnded();

        assertEquals(List.of("save"), ran);
    }

    /**
     * A bar out of the tree must stop answering the scene's keyboard. Without the unhook the
     * scene keeps a handler for a widget nobody can see, and a chord runs a command from a
     * screen that has been replaced.
     */
    @Test
    void aDetachedBarStopsAnsweringForTheScene() {
        build();
        ((Column) scene.root()).remove(bar);
        scene.layoutPass(400, 300);

        press(Keys.S, command());

        assertEquals(List.of(), ran);
    }

    // ------------------------------------------------------------------ mnemonics

    @Test
    void altAndAnAccessLetterOpensThatMenu() {
        build();
        field.requestFocus();

        press(Keys.F, Keys.MOD_ALT);

        assertTrue(bar.isOpen(), "Alt+F opened the File menu");
    }

    /**
     * Alt has to activate on the release, because at press time a bare Alt and the start of
     * Alt+F are the same event; claiming it on the press would break every Alt chord.
     */
    @Test
    void aBareAltReachesTheBarOnReleaseAndAChordDoesNot() {
        build();
        field.requestFocus();

        scene.keyEvent(Keys.LEFT_ALT, true, false, Keys.MOD_ALT);
        scene.keyEvent(Keys.LEFT_ALT, false, false, 0);
        scene.inputBatchEnded();
        assertTrue(bar.isFocused(), "a bare Alt, released, moves focus to the bar");

        field.requestFocus();
        scene.keyEvent(Keys.LEFT_ALT, true, false, Keys.MOD_ALT);
        scene.keyEvent(Keys.F, true, false, Keys.MOD_ALT);
        scene.keyEvent(Keys.LEFT_ALT, false, false, 0);
        scene.inputBatchEnded();
        assertFalse(bar.isFocused(),
                "an Alt that was part of a chord must not also focus the bar on its way up");
    }

    @Test
    void f10ReachesTheBarToo() {
        build();
        field.requestFocus();
        press(Keys.F10, 0);
        assertTrue(bar.isFocused());
    }

    @Test
    void aTitleWithoutAnAccessLetterAnswersNothing() {
        Menu file = new Menu().addItem("New", () -> ran.add("new"));
        MenuBar plain = new MenuBar().addMenu("File", file);
        Scene host = new Scene(plain);
        host.setTextRuler(RULER);
        host.layoutPass(400, 300);

        host.keyEvent(Keys.F, true, false, Keys.MOD_ALT);
        host.inputBatchEnded();

        assertFalse(plain.isOpen(), "a title with no access letter answers no Alt chord");
    }

    @Test
    void aMnemonicMustBeALetterOrADigit() {
        assertThrows(IllegalArgumentException.class,
                () -> new MenuBar().addMenu("File", '+', new Menu()));
    }

    // ------------------------------------------------------------------ the value

    @Test
    void aSubmenuAndASeparatorRefuseAnAccelerator() {
        // A shortcut runs a command; opening a submenu is not one, and a separator is not a row.
        assertThrows(IllegalStateException.class, () -> MenuItem.submenu("Recent", new Menu())
                .setAccelerator(Accelerator.command(Keys.R)));
        assertThrows(IllegalStateException.class,
                () -> MenuItem.separator().setAccelerator(Accelerator.command(Keys.R)));
    }

    @Test
    void aModifierCannotBeTheKeyOfAChord() {
        // A chord whose key is Shift could never complete: the mask would have to be held while
        // the same physical key is also the one pressed.
        assertThrows(IllegalArgumentException.class,
                () -> Accelerator.of(Keys.LEFT_SHIFT, Keys.MOD_CONTROL));
        assertThrows(IllegalArgumentException.class, () -> Accelerator.of(0));
        assertThrows(IllegalArgumentException.class, () -> Accelerator.of(Keys.S, 0x40));
    }

    /**
     * The hint a row shows, on both platforms, through the seam the platform test is behind,
     * so the branch that is not this machine's is asserted rather than assumed.
     */
    @Test
    void theHintIsSpelledForThePlatform() {
        assertEquals("Ctrl+S", Accelerator.of(Keys.S, Keys.MOD_CONTROL).display(false));
        assertEquals("Meta+S", Accelerator.of(Keys.S, Keys.MOD_SUPER).display(false));
        assertEquals("Ctrl+Shift+S",
                Accelerator.of(Keys.S, Keys.MOD_CONTROL | Keys.MOD_SHIFT).display(false));
        assertEquals("F5", Accelerator.of(Keys.F5).display(false));
        assertEquals("Ctrl+Del", Accelerator.of(Keys.DELETE, Keys.MOD_CONTROL).display(false));
        // A wrong constant has to be visible in the menu rather than render as a bare modifier.
        assertTrue(Accelerator.of(9999, Keys.MOD_CONTROL).display(false).startsWith("Ctrl+Key"));
    }

    /**
     * The macOS form is symbols with no separator, in the platform's own modifier order, which
     * is Control, Option, Shift, Command, and is not the order the word form uses.
     */
    @Test
    void theMacHintIsAnUnbrokenRunOfSymbols() {
        assertEquals("\u2318S", Accelerator.of(Keys.S, Keys.MOD_SUPER).display(true));
        assertEquals("\u21E7\u2318S",
                Accelerator.of(Keys.S, Keys.MOD_SUPER | Keys.MOD_SHIFT).display(true));
        assertEquals("\u2303\u2325\u21E7\u2318S", Accelerator.of(Keys.S, Keys.MOD_CONTROL
                | Keys.MOD_ALT | Keys.MOD_SHIFT | Keys.MOD_SUPER).display(true));
        assertEquals("\u2318\u232B",
                Accelerator.of(Keys.BACKSPACE, Keys.MOD_SUPER).display(true));
        assertEquals("\u2318\u2190", Accelerator.of(Keys.LEFT, Keys.MOD_SUPER).display(true));
        assertEquals("F5", Accelerator.of(Keys.F5).display(true), "a function key is its name");
    }

    /**
     * Every symbol either branch can emit has to be one the bundled face carries, or a menu row
     * draws a box. The face's own coverage is asserted in the backend's suite; this is the other
     * half: that nothing here reaches for a symbol outside that set.
     */
    @Test
    void everyMacSymbolIsOneTheBundledFaceCarries() {
        String covered = "\u2318\u2325\u2303\u21E7\u21EA\u23CE\u2324\u232B\u2326"
                + "\u21E5\u238B\u2423\u21DE\u21DF\u2196\u2198\u2190\u2191\u2192\u2193";
        int[] keys = {Keys.S, Keys.F5, Keys.SPACE, Keys.ESCAPE, Keys.ENTER, Keys.TAB,
                Keys.BACKSPACE, Keys.INSERT, Keys.DELETE, Keys.RIGHT, Keys.LEFT, Keys.DOWN,
                Keys.UP, Keys.PAGE_UP, Keys.PAGE_DOWN, Keys.HOME, Keys.END};
        int all = Keys.MOD_CONTROL | Keys.MOD_ALT | Keys.MOD_SHIFT | Keys.MOD_SUPER;
        for (int key : keys) {
            String hint = Accelerator.of(key, all).display(true);
            for (int i = 0; i < hint.length(); i++) {
                char c = hint.charAt(i);
                assertTrue(c < 0x2000 || covered.indexOf(c) >= 0,
                        String.format("U+%04X in \"%s\" is outside the bundled face", (int) c, hint));
            }
        }
    }

    @Test
    void theCommandFormIsThePlatformsOwnModifier() {
        assertEquals(Accelerator.isMac(System.getProperty("os.name", ""))
                ? Keys.MOD_SUPER : Keys.MOD_CONTROL, Accelerator.commandModifier());
        assertTrue(Accelerator.isMac("Mac OS X"));
        assertFalse(Accelerator.isMac("Windows 11"));
        assertFalse(Accelerator.isMac("Linux"));
    }

    @Test
    void anItemCarriesTheAcceleratorItWasGivenAndCanGiveItBack() {
        MenuItem item = MenuItem.of("Save", () -> { });
        assertNull(item.accelerator());
        item.setAccelerator(Accelerator.command(Keys.S));
        assertNotNull(item.accelerator());
        assertEquals(Accelerator.command(Keys.S), item.accelerator(), "compared by value");
        item.setAccelerator(null);
        assertNull(item.accelerator(), "a shortcut can be taken away again");
    }

    // ------------------------------------------------------------------ the row

    /** A popup laid out headlessly, so what a row would draw can be read back. */
    private PopupMenu openPopup(Menu menu) {
        Scene host = new Scene(new Label("root"));
        host.setTextRuler(RULER);
        PopupMenu popup = new PopupMenu(menu);
        popup.showInSceneForTest(host, 20, 20, 0, 0);
        return popup;
    }

    /**
     * The hint has to be the row's, not the menu's: an item without an accelerator draws none,
     * and a hint drawn against the wrong row is the kind of error a screenshot finds and a green
     * build does not.
     */
    @Test
    void aRowShowsItsOwnHintAndOnlyItsOwn() {
        Accelerator save = Accelerator.of(Keys.S, Keys.MOD_CONTROL);
        PopupMenu popup = openPopup(new Menu()
                .add(MenuItem.of("Save", () -> { }).setAccelerator(save))
                .addItem("About", () -> { }));

        // Against the accelerator's own hint rather than a literal: which spelling is right is
        // the platform's business and is asserted on both branches elsewhere. What the row owes
        // is to draw what its item says, whichever spelling that is.
        assertEquals(save.display(), popup.accelTextForTest(0, 0));
        assertNull(popup.accelTextForTest(0, 1), "an item with no accelerator draws no hint");
    }

    /**
     * The access letter chooses a row while the menu is open, and repeating it walks the rows
     * that share it instead of firing the first one: the convention on every platform, and the
     * only behaviour that is usable when two commands begin with the same letter.
     */
    @Test
    void aMnemonicInsideAnOpenMenuCyclesRowsThatShareIt() {
        List<String> chosen = new ArrayList<>();
        PopupMenu popup = openPopup(new Menu()
                .add(MenuItem.of("Save", () -> chosen.add("save")).setMnemonic('S'))
                .add(MenuItem.of("Save As", () -> chosen.add("saveAs")).setMnemonic('S'))
                .add(MenuItem.of("Print", () -> chosen.add("print")).setMnemonic('P')));

        // The open menu highlights its first row, so the first S steps OFF it onto the other
        // row sharing the letter rather than firing the row already under the highlight.
        assertTrue(popup.keyForTest(Keys.S, 0));
        assertEquals(List.of(), chosen, "cycling chooses, it does not activate");
        assertTrue(popup.keyForTest(Keys.S, 0), "and pressing it again wraps to the first match");

        popup.keyForTest(Keys.ENTER);
        assertEquals(List.of("save"), chosen, "Enter runs the row the cycling landed on");
    }

    /** A letter that is a single row's runs it outright, since there is nothing to cycle. */
    @Test
    void aUniqueMnemonicRunsItsRow() {
        List<String> chosen = new ArrayList<>();
        PopupMenu popup = openPopup(new Menu()
                .add(MenuItem.of("Save", () -> chosen.add("save")).setMnemonic('S'))
                .add(MenuItem.of("Print", () -> chosen.add("print")).setMnemonic('P')));

        assertTrue(popup.keyForTest(Keys.P, 0));
        assertEquals(List.of("print"), chosen);
    }

    /** A modifier-carrying press is an accelerator's, never a mnemonic's. */
    @Test
    void anOpenMenuLeavesModifierChordsAlone() {
        PopupMenu popup = openPopup(new Menu()
                .add(MenuItem.of("Save", () -> { }).setMnemonic('S')));
        assertFalse(popup.keyForTest(Keys.S, Keys.MOD_CONTROL),
                "Ctrl+S inside an open menu is not the S mnemonic");
    }
}

package limn.components;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;
import limn.input.Keys;
import limn.scene.internal.AccessLetterSystem;
import limn.scene.internal.SceneAccess;
import limn.scene.layout.Column;
import limn.testing.StubWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an access letter does on Alt and the letter (ADR 070): presses a button, flips a box,
 * selects a radio, brings the keyboard to the field a label names; within the window or the
 * topmost modal, a control before the menu bar, several of one letter taking turns; drawn while
 * Alt is held; none of it on macOS. Run as each system answers, whatever the host.
 */
class AccessLetterDispatchTest extends AccessibleComponentTestBase {

    private boolean system;
    private limn.i18n.StringBundle bundle;

    @BeforeEach
    void letters() {
        system = AccessLetterSystem.setPresentForTests(true);
    }

    @AfterEach
    void restore() {
        AccessLetterSystem.setPresentForTests(system);
        if (bundle != null) {
            I18n.removeBundle(bundle);
        }
        I18n.setLocale(Locale.ENGLISH);
    }

    private void show(limn.scene.Widget<?>... widgets) {
        Column root = new Column();
        for (limn.scene.Widget<?> widget : widgets) {
            root.add(widget);
        }
        bind(root, new StubWindow(false));
        frame();
    }

    private void altAnd(int key) {
        drive(scene).press(key, Keys.MOD_ALT);
        frame();
    }

    @Test
    void altAndTheLetterPressesAButtonAndLeavesTheKeyboardWhereItWas() {
        AtomicInteger saved = new AtomicInteger();
        Button save = new Button("&Save").onAction(saved::incrementAndGet);
        TextField name = new TextField();
        show(name, save);
        scene.requestFocus(name);
        assertEquals("Save", save.text(), "the mark is not shown");

        altAnd(Keys.S);
        assertEquals(1, saved.get());
        assertSame(name, scene.focusedWidget(), "a press is not a move of the keyboard");
    }

    @Test
    void altAndTheLetterFlipsABoxAndSelectsARadioAndBringsTheKeyboardThere() {
        Checkbox notify = new Checkbox(Checkbox.Variant.BOX, "&Notify me");
        RadioButton yearly = new RadioButton("&Yearly");
        RadioButton monthly = new RadioButton("Monthly");
        ButtonGroup group = new ButtonGroup();
        group.add(monthly);
        group.add(yearly);
        monthly.select();
        show(notify, monthly, yearly);

        altAnd(Keys.N);
        assertTrue(notify.isChecked());
        assertSame(notify, scene.focusedWidget());

        altAnd(Keys.Y);
        assertTrue(yearly.isSelected());
        assertSame(yearly, scene.focusedWidget());
    }

    @Test
    void aLabelThatNamesAFieldBringsTheKeyboardToItAndAnyOtherLabelIsText() {
        TextField field = new TextField();
        Label name = new Label("&Name").setLabelFor(field);
        Label plain = new Label("AT&T");
        show(plain, name, field);
        assertEquals("Name", name.text());
        assertEquals("AT&T", plain.text(), "a label that names nothing shows its ampersand");

        altAnd(Keys.N);
        assertSame(field, scene.focusedWidget());
        altAnd(Keys.T);
        assertSame(field, scene.focusedWidget(), "AT&T marks nothing");
    }

    @Test
    void severalWithOneLetterTakeTurnsWithTheKeyboardAndNoneActs() {
        AtomicInteger acted = new AtomicInteger();
        Button save = new Button("&Save").onAction(acted::incrementAndGet);
        Button send = new Button("&Send").onAction(acted::incrementAndGet);
        show(save, send);

        altAnd(Keys.S);
        assertSame(save, scene.focusedWidget());
        altAnd(Keys.S);
        assertSame(send, scene.focusedWidget());
        altAnd(Keys.S);
        assertSame(save, scene.focusedWidget(), "and round again");
        assertEquals(0, acted.get());
    }

    @Test
    void hiddenAndDisabledControlsHaveNoLetter() {
        AtomicInteger acted = new AtomicInteger();
        Button hidden = new Button("&Hide").onAction(acted::incrementAndGet);
        hidden.setVisible(false);
        Button disabled = new Button("&Delete").onAction(acted::incrementAndGet);
        disabled.setEnabled(false);
        show(hidden, disabled);

        altAnd(Keys.H);
        altAnd(Keys.D);
        assertEquals(0, acted.get());
    }

    @Test
    void aControlTakesALetterBeforeTheMenuBar() {
        AtomicInteger sent = new AtomicInteger();
        MenuBar bar = new MenuBar().setDisplayMode(DisplayMode.IN_SCENE)
                .addMenu("&Sales", new Menu().addItem("Report", () -> { }));
        Button send = new Button("&Send").onAction(sent::incrementAndGet);
        show(bar, send);

        altAnd(Keys.S);
        assertEquals(1, sent.get(), "the form in use wins the letter");
        assertFalse(bar.isOpen());
    }

    /**
     * Alt and a control's letter, then Alt let go: the menu bar does not take the keyboard. The
     * letter never reached the bar, which read the release as Alt pressed alone (seen on Windows).
     */
    @Test
    void lettingGoOfAltAfterAControlsLetterLeavesTheMenuBarAlone() {
        MenuBar bar = new MenuBar().setDisplayMode(DisplayMode.IN_SCENE)
                .addMenu("&Sales", new Menu().addItem("Report", () -> { }));
        TextField field = new TextField();
        Label name = new Label("&Name").setLabelFor(field);
        show(bar, name, field);

        drive(scene).keyEvent(Keys.LEFT_ALT, -1, true, false, Keys.MOD_ALT);
        drive(scene).keyEvent(Keys.N, 'n', true, false, Keys.MOD_ALT);
        drive(scene).keyEvent(Keys.N, 'n', false, false, Keys.MOD_ALT);
        drive(scene).keyEvent(Keys.LEFT_ALT, -1, false, false, 0);
        drive(scene).inputBatchEnded();
        frame();
        assertSame(field, scene.focusedWidget(), "the keyboard stays where the letter took it");

        drive(scene).keyEvent(Keys.LEFT_ALT, -1, true, false, Keys.MOD_ALT);
        drive(scene).keyEvent(Keys.LEFT_ALT, -1, false, false, 0);
        drive(scene).inputBatchEnded();
        frame();
        assertSame(bar, scene.focusedWidget(), "and Alt alone still reaches the bar");
    }

    @Test
    void theTopmostModalTakesItsLettersAndNothingBeneathAnswers() {
        AtomicInteger killed = new AtomicInteger();
        Button kill = new Button("&Kill").onAction(killed::incrementAndGet);
        Column root = new Column();
        root.add(kill);
        bind(root, new OverlayHost()); // a window that can hold a dialog in its scene
        frame();
        AtomicReference<String> answer = new AtomicReference<>();
        new Dialog("Close?", "Unsaved changes.")
                .addButton("&Keep", "keep")
                .setDisplayMode(DisplayMode.IN_SCENE)
                .show(scene)
                .thenAccept(answer::set);
        advanceTime(600, null); // the card settles over the window

        altAnd(Keys.K);
        advanceTime(1000, null);
        assertEquals("keep", answer.get());
        assertEquals(0, killed.get(), "a letter never reaches past a modal");
    }

    @Test
    void theLettersAreShownWhileAltIsHeld() {
        show(new Button("&Save"));
        assertFalse(SceneAccess.accessLettersShown(scene));
        drive(scene).keyEvent(Keys.LEFT_ALT, -1, true, false, Keys.MOD_ALT);
        drive(scene).inputBatchEnded();
        assertTrue(SceneAccess.accessLettersShown(scene));
        drive(scene).keyEvent(Keys.LEFT_ALT, -1, false, false, 0);
        drive(scene).inputBatchEnded();
        assertFalse(SceneAccess.accessLettersShown(scene));
    }

    @Test
    void theLettersStayShownWhereTheSystemAsksForUnderlines() {
        show(new Button("&Save"));
        limn.scene.SystemPreferences was = limn.scene.SystemPreferences.current();
        try {
            limn.scene.SystemPreferences.report(new limn.scene.SystemPreferences(
                    limn.scene.SystemPreferences.ColorScheme.NO_PREFERENCE, false, false, true));
            assertTrue(SceneAccess.accessLettersShown(scene), "Windows' underline-access-keys setting");
        } finally {
            limn.scene.SystemPreferences.report(was);
        }
        assertFalse(SceneAccess.accessLettersShown(scene));
    }

    @Test
    void macOSHasNone() {
        AccessLetterSystem.setPresentForTests(false);
        AtomicInteger saved = new AtomicInteger();
        Button save = new Button("&Save").onAction(saved::incrementAndGet);
        show(save);
        assertEquals("Save", save.text(), "the word is kept");

        drive(scene).keyEvent(Keys.LEFT_ALT, -1, true, false, Keys.MOD_ALT);
        drive(scene).inputBatchEnded();
        assertFalse(SceneAccess.accessLettersShown(scene), "nothing is ever underlined");
        altAnd(Keys.S);
        assertEquals(0, saved.get(), "Option and a letter type a character, and press nothing");
        assertNull(node("Save").actions().keyBinding());
    }

    @Test
    void eachLanguageCarriesItsOwnLetter() {
        Locale portuguese = Locale.forLanguageTag("pt-BR");
        bundle = PropertyBundle.of(portuguese, Map.of("test.access.save", "&Gravar"));
        I18n.addBundle(bundle);
        AtomicInteger saved = new AtomicInteger();
        Button save = new Button(new I18nString("test.access.save", "&Save")).onAction(saved::incrementAndGet);
        show(save);

        I18n.setLocale(portuguese);
        frame();
        assertEquals("Gravar", save.text());
        altAnd(Keys.S);
        assertEquals(0, saved.get(), "the English letter went with the English word");
        altAnd(Keys.G);
        assertEquals(1, saved.get());
    }

    @Test
    void aReaderHearsTheWordAndTheChord() {
        show(new Button("&Save"));
        String binding = node("Save").actions().keyBinding();
        assertNotNull(binding, describe(tree()));
        assertTrue(binding.endsWith("S"), binding);
    }
}

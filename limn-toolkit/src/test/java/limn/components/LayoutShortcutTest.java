package limn.components;

import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.event.KeyEvent;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A shortcut is read off the keycap: the letter the active layout prints on the key, not where the
 * key sits on a US keyboard. On a French AZERTY keyboard the key where a US layout has Q is printed
 * A, and Ctrl+A there selects all; a layout whose letters are not Latin keeps the physical key, as
 * native applications do, so Ctrl+Z stays where Z is on a US keyboard.
 */
class LayoutShortcutTest extends ComponentTestBase {

    private static final int COMMAND = Accelerator.commandModifier();

    private TextField field;
    private Scene scene;

    private void build() {
        field = new TextField();
        field.setText("bonjour");
        scene = new Scene(field);
        scene.setTextRuler(RULER);
        scene.layoutPass(300, 40);
        scene.requestFocus(field);
        field.setCaretPosition(0);
    }

    private void press(int physical, int layoutCodePoint, int modifiers) {
        drive(scene).keyEvent(physical, layoutCodePoint, true, false, modifiers);
        drive(scene).keyEvent(physical, layoutCodePoint, false, false, modifiers);
        drive(scene).inputBatchEnded();
    }

    @Test
    void theEventTellsTheKeyAndTheKeycap() {
        KeyEvent azertyA = new KeyEvent(Keys.Q, 'a', true, false, 0);
        assertEquals(Keys.Q, azertyA.key(), "where it is");
        assertEquals(Keys.A, azertyA.shortcutKey(), "what it says");
        assertEquals(Keys.Z, new KeyEvent(Keys.Z, 'я', true, false, 0).shortcutKey(),
                "a Cyrillic keycap keeps the physical key");
        assertEquals(Keys.LEFT, new KeyEvent(Keys.LEFT, 0, true, false, 0).shortcutKey(),
                "a key that types nothing is itself");
        assertEquals(Keys.A, new KeyEvent(Keys.A, true, false, 0).shortcutKey(),
                "the four-argument event is a US layout");
    }

    @Test
    void selectAllIsTheKeyPrintedA() {
        build();
        press(Keys.Q, 'a', COMMAND); // AZERTY: the A keycap sits where a US keyboard has Q
        assertEquals("bonjour", field.selectedText());
    }

    @Test
    void theKeyWhereAUsKeyboardHasAIsNotSelectAllOnAzerty() {
        build();
        press(Keys.A, 'q', COMMAND); // AZERTY prints Q there
        assertEquals("", field.selectedText());
    }

    @Test
    void aCyrillicLayoutKeepsThePhysicalKey() {
        build();
        press(Keys.A, 'ф', COMMAND); // Russian prints ф on the US A key
        assertEquals("bonjour", field.selectedText());
    }

    @Test
    void aMenuAcceleratorIsTheKeycapToo() {
        AtomicInteger saved = new AtomicInteger();
        MenuBar bar = new MenuBar();
        bar.addMenu("File", new Menu().add(MenuItem.of("Save", saved::incrementAndGet)
                .setAccelerator(Accelerator.command(Keys.S))));
        Column root = new Column();
        root.add(bar);
        scene = new Scene(root);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 200);

        drive(scene).keyEvent(Keys.S, 'o', true, false, COMMAND); // a Dvorak layout prints O there
        drive(scene).inputBatchEnded();
        assertEquals(0, saved.get(), "the key printed O is not Save");

        // Dvorak's S keycap, on the key right of L, which Keys names no constant for (59, as ';').
        drive(scene).keyEvent(';', 's', true, false, COMMAND);
        drive(scene).inputBatchEnded();
        assertEquals(1, saved.get());
    }
}

package limn.scene;

import limn.input.Keys;
import limn.scene.event.KeyEvent;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scene-level shortcut hook and (the reason it exists) the order a key event is offered
 * around it: focused widget, then shortcut handlers, then the Tab fallback.
 *
 * <p>That order is invisible in a green build. Swap the first two and every menu accelerator
 * starts eating keystrokes out of text fields; drop the third and a chord nobody wants takes Tab
 * traversal down with it. Both failures look like "the shortcut works", so they are pinned here
 * rather than left to the components that depend on them.
 */
class SceneShortcutTest extends SceneTestBase {

    /** A focusable leaf that consumes exactly one key code and logs every key it is offered. */
    private static final class KeyEater extends FixedBox {
        private final int eats;
        final List<Integer> seen = new ArrayList<>();

        KeyEater(int eats) {
            super(20, 20);
            this.eats = eats;
            setFocusable(true);
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            seen.add(event.key());
            if (event.key() == eats) {
                event.consume();
            }
        }
    }

    /** Records the key events the hook was offered; consumes those matching {@code consumes}. */
    private static final class Hook implements java.util.function.Predicate<KeyEvent> {
        final List<Integer> seen = new ArrayList<>();
        int consumes = -1;

        @Override
        public boolean test(KeyEvent event) {
            seen.add(event.key());
            return event.key() == consumes;
        }
    }

    private static Scene sceneWith(Widget... children) {
        Column root = new Column();
        for (Widget child : children) {
            root.add(child);
        }
        Scene scene = new Scene(root);
        scene.layoutPass(200, 200);
        return scene;
    }

    private static void press(Scene scene, int key, int modifiers) {
        scene.keyEvent(key, true, false, modifiers);
        scene.inputBatchEnded();
    }

    @Test
    void theFocusedWidgetDeclinesBeforeAnyHandlerIsOffered() {
        // Ctrl+C inside a focused text field is the field's, never a menu's. The field is
        // modelled by the one thing that matters here: a focused widget that consumes the chord.
        KeyEater field = new KeyEater(Keys.C);
        Scene scene = sceneWith(field);
        Hook hook = new Hook();
        hook.consumes = Keys.C;
        scene.addShortcutHandler(hook);
        scene.requestFocus(field);

        press(scene, Keys.C, Keys.MOD_CONTROL);

        assertEquals(List.of(Keys.C), field.seen, "the field saw it first");
        assertTrue(hook.seen.isEmpty(), "and having consumed it, the hook was never offered it");
    }

    @Test
    void aChordTheFocusedWidgetDeclinesReachesTheHandlers() {
        KeyEater field = new KeyEater(Keys.C);
        Scene scene = sceneWith(field);
        Hook hook = new Hook();
        hook.consumes = Keys.S;
        scene.addShortcutHandler(hook);
        scene.requestFocus(field);

        press(scene, Keys.S, Keys.MOD_CONTROL);

        assertEquals(List.of(Keys.S), field.seen, "the field is still offered it first");
        assertEquals(List.of(Keys.S), hook.seen, "and declined it, so the hook got it");
    }

    @Test
    void aChordNobodyWantsAtAllStillLeavesTabTraversalWorking() {
        KeyEater first = new KeyEater(Keys.C);
        KeyEater second = new KeyEater(Keys.C);
        Scene scene = sceneWith(first, second);
        Hook hook = new Hook(); // consumes nothing
        scene.addShortcutHandler(hook);
        scene.requestFocus(first);

        press(scene, Keys.TAB, 0);

        assertEquals(List.of(Keys.TAB), hook.seen, "the hook was offered Tab before the fallback");
        assertSame(second, scene.focusedWidget(), "and declining it left traversal intact");
    }

    @Test
    void aHandlerThatConsumesTabTakesItFromTraversal() {
        KeyEater first = new KeyEater(Keys.C);
        KeyEater second = new KeyEater(Keys.C);
        Scene scene = sceneWith(first, second);
        Hook hook = new Hook();
        hook.consumes = Keys.TAB;
        scene.addShortcutHandler(hook);
        scene.requestFocus(first);

        press(scene, Keys.TAB, 0);

        assertSame(first, scene.focusedWidget(), "consumed before the fallback ran");
    }

    @Test
    void handlersAreOfferedReleasesAndRepeatsToo() {
        // The bare-Alt convention has nothing else to key on: a menu bar cannot tell "reaching
        // for the menu" from "starting Alt+F" until the release arrives.
        Scene scene = sceneWith(new KeyEater(-1));
        Hook hook = new Hook();
        scene.addShortcutHandler(hook);

        scene.keyEvent(Keys.LEFT_ALT, true, false, Keys.MOD_ALT);
        scene.keyEvent(Keys.LEFT_ALT, true, true, Keys.MOD_ALT);
        scene.keyEvent(Keys.LEFT_ALT, false, false, 0);
        scene.inputBatchEnded();

        assertEquals(3, hook.seen.size(), "press, auto-repeat and release all reached the hook");
    }

    @Test
    void aModalOverlayStopsShortcutsFromFiringBehindIt() {
        KeyEater content = new KeyEater(-1);
        Scene scene = sceneWith(content);
        Hook hook = new Hook();
        hook.consumes = Keys.S;
        scene.addShortcutHandler(hook);

        FixedBox overlay = new FixedBox(200, 200);
        scene.pushOverlay(overlay);
        press(scene, Keys.S, Keys.MOD_CONTROL);
        assertTrue(hook.seen.isEmpty(), "the dialog owns the keyboard; the menu behind it does not");

        scene.removeOverlay(overlay);
        press(scene, Keys.S, Keys.MOD_CONTROL);
        assertEquals(List.of(Keys.S), hook.seen, "and it answers again once the overlay is gone");
    }

    @Test
    void theReturnedRunnableUnregistersTheHandler() {
        Scene scene = sceneWith(new KeyEater(-1));
        Hook hook = new Hook();
        Runnable unhook = scene.addShortcutHandler(hook);

        press(scene, Keys.S, Keys.MOD_CONTROL);
        assertEquals(1, hook.seen.size());

        unhook.run();
        unhook.run(); // idempotent
        press(scene, Keys.S, Keys.MOD_CONTROL);
        assertEquals(1, hook.seen.size(), "no further events after unregistering");
    }

    @Test
    void aHandlerThatUnregistersItselfDoesNotSkipItsNeighbour() {
        // The ordinary shape: the first handler closes what it opened and unhooks, and the
        // second must still be offered the same event. An index walk over the live list drops it.
        Scene scene = sceneWith(new KeyEater(-1));
        Hook second = new Hook();
        Runnable[] unhookFirst = new Runnable[1];
        List<String> order = new ArrayList<>();
        unhookFirst[0] = scene.addShortcutHandler(event -> {
            order.add("first");
            unhookFirst[0].run();
            return false;
        });
        scene.addShortcutHandler(event -> {
            order.add("second");
            return second.test(event);
        });

        press(scene, Keys.S, Keys.MOD_CONTROL);

        assertEquals(List.of("first", "second"), order);
    }

    @Test
    void handlersRunOldestFirstAndStopAtTheFirstConsumer() {
        Scene scene = sceneWith(new KeyEater(-1));
        List<String> order = new ArrayList<>();
        scene.addShortcutHandler(event -> {
            order.add("first");
            return true;
        });
        scene.addShortcutHandler(event -> {
            order.add("second");
            return true;
        });

        press(scene, Keys.S, Keys.MOD_CONTROL);

        assertEquals(List.of("first"), order);
    }

    @Test
    void aHandlerIsOfferedTheEventWithNothingFocusedAtAll() {
        Scene scene = sceneWith(new FixedBox(20, 20)); // nothing focusable
        Hook hook = new Hook();
        hook.consumes = Keys.S;
        scene.addShortcutHandler(hook);
        assertNull(scene.focusedWidget());

        press(scene, Keys.S, Keys.MOD_CONTROL);

        assertEquals(List.of(Keys.S), hook.seen);
        assertFalse(hook.seen.isEmpty());
    }
}

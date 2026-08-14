package limn.components;

import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The context-menu gesture and the region that answers it.
 *
 * <p>Nothing here asserts that a popup appeared: {@link PopupMenu} needs a real native window
 * and the headless suite has none. What is assertable (and what actually went wrong in the
 * hand-rolled copies this class replaces) is <em>whether the region decided to raise one</em>
 * and for which events. So every supplier below answers {@code null}, which is a menu the
 * helper declines to open, and the count of calls is the observation.
 */
class ContextMenusTest extends ComponentTestBase {

    /** Fills whatever it is given, and optionally eats the right press the way a real widget does. */
    private static final class Region extends Widget {
        final AtomicInteger presses = new AtomicInteger();
        boolean eatsRightPress;

        Region() {
            setFocusable(true);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (ContextMenus.isRequest(event)) {
                presses.incrementAndGet();
                if (eatsRightPress) {
                    event.consume();
                }
            }
        }
    }

    // ------------------------------------------------------------------ the gesture

    @Test
    void onlyTheRightPressIsAPointerRequest() {
        assertTrue(ContextMenus.isRequest(mouse(MouseEvent.Type.PRESS, Keys.MOUSE_RIGHT)));
        assertFalse(ContextMenus.isRequest(mouse(MouseEvent.Type.PRESS, Keys.MOUSE_LEFT)));
        assertFalse(ContextMenus.isRequest(mouse(MouseEvent.Type.PRESS, Keys.MOUSE_MIDDLE)));
        // The menu opens on the way down on every desktop. A release-driven one would appear
        // under a button the user has already let go of.
        assertFalse(ContextMenus.isRequest(mouse(MouseEvent.Type.RELEASE, Keys.MOUSE_RIGHT)));
        assertFalse(ContextMenus.isRequest(mouse(MouseEvent.Type.CLICK, Keys.MOUSE_RIGHT)));
    }

    @Test
    void theKeyboardHasTwoRoutesAndNeitherRepeats() {
        assertTrue(ContextMenus.isRequest(new KeyEvent(Keys.MENU, true, false, 0)));
        assertTrue(ContextMenus.isRequest(new KeyEvent(Keys.F10, true, false, Keys.MOD_SHIFT)));

        assertFalse(ContextMenus.isRequest(new KeyEvent(Keys.F10, true, false, 0)),
                "bare F10 belongs to the menu bar, not to the context menu");
        assertFalse(ContextMenus.isRequest(new KeyEvent(Keys.F10, true, false, Keys.MOD_CONTROL)));
        assertFalse(ContextMenus.isRequest(new KeyEvent(Keys.MENU, false, false, 0)),
                "the release is not a second request");
        // Holding the key must not stack menus: every repeat is the same one press.
        assertFalse(ContextMenus.isRequest(new KeyEvent(Keys.MENU, true, true, 0)));
        assertFalse(ContextMenus.isRequest(new KeyEvent(Keys.F10, true, true, Keys.MOD_SHIFT)));
    }

    // ------------------------------------------------------------------ the region

    @Test
    void theWrapperIsInvisibleToLayout() {
        Region content = new Region();
        Widget attached = ContextMenus.attach(content, Menu::new);
        Scene scene = new Scene(attached);
        scene.setTextRuler(RULER);
        scene.layoutPass(300, 200);

        assertEquals(300, attached.width(), 0.001f);
        assertEquals(200, attached.height(), 0.001f);
        assertEquals(300, content.width(), 0.001f, "the content fills the wrapper");
        assertEquals(200, content.height(), 0.001f);
        assertEquals(0, content.x(), 0.001f);
        assertEquals(0, content.y(), 0.001f);
        assertSame(attached, content.parent());
    }

    @Test
    void aRightPressInsideTheRegionAsksForItsMenu() {
        AtomicInteger asked = new AtomicInteger();
        Region content = new Region();
        Scene scene = attachedScene(content, asked);

        scene.mouseButton(Keys.MOUSE_RIGHT, true, 0, 40, 40);
        scene.inputBatchEnded();

        assertEquals(1, asked.get(), "the region was asked for a menu");
    }

    @Test
    void aLeftPressAsksForNothing() {
        AtomicInteger asked = new AtomicInteger();
        Scene scene = attachedScene(new Region(), asked);

        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 40, 40);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 40, 40);
        scene.inputBatchEnded();

        assertEquals(0, asked.get());
    }

    /**
     * The ordering the wrapper rests on, and the reason a {@link TextField} inside an attached
     * region keeps its own Cut/Copy/Paste menu instead of the region's: events bubble and stop
     * at the first widget that consumes them. Without this the more specific menu (the one the
     * user is actually pointing at) would be shadowed by whatever wraps it.
     */
    @Test
    void aChildThatAnswersTheGestureKeepsIt() {
        AtomicInteger asked = new AtomicInteger();
        Region content = new Region();
        content.eatsRightPress = true;
        Scene scene = attachedScene(content, asked);

        scene.mouseButton(Keys.MOUSE_RIGHT, true, 0, 40, 40);
        scene.inputBatchEnded();

        assertEquals(1, content.presses.get(), "the child saw the press");
        assertEquals(0, asked.get(), "and the region did not ask for a menu behind it");
    }

    @Test
    void theKeyboardRouteReachesTheRegionFromTheFocusedChild() {
        AtomicInteger asked = new AtomicInteger();
        Region content = new Region();
        Scene scene = attachedScene(content, asked);
        content.requestFocus();

        scene.keyEvent(Keys.F10, true, false, Keys.MOD_SHIFT);
        scene.inputBatchEnded();
        assertEquals(1, asked.get(), "Shift+F10 bubbled from the focused child to the region");

        scene.keyEvent(Keys.MENU, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(2, asked.get(), "and so does the dedicated Menu key");
    }

    /**
     * A supplier that answers nothing is the documented way for a region to say "not here" for
     * one spot without the caller writing a second gesture check, so it has to be safe rather
     * than merely unlikely, including with no window anywhere to put a popup in.
     */
    @Test
    void anAbsentOrEmptyMenuOpensNothingAndThrowsNothing() {
        Region content = new Region();
        Scene scene = new Scene(ContextMenus.attach(content, () -> null));
        scene.setTextRuler(RULER);
        scene.layoutPass(300, 200);

        scene.mouseButton(Keys.MOUSE_RIGHT, true, 0, 40, 40);
        scene.inputBatchEnded();

        ContextMenus.showAt(content, null, 0, 0);
        ContextMenus.showAt(content, new Menu(), 0, 0);
        ContextMenus.showForFocus(content, new Menu());
    }

    private Scene attachedScene(Region content, AtomicInteger asked) {
        Scene scene = new Scene(ContextMenus.attach(content, () -> {
            asked.incrementAndGet();
            return null; // a popup needs a native window; the decision is what is under test
        }));
        scene.setTextRuler(RULER);
        scene.layoutPass(300, 200);
        return scene;
    }

    private static MouseEvent mouse(MouseEvent.Type type, int button) {
        return new MouseEvent(type, 0, 0, button, 0, 0, 0);
    }
}

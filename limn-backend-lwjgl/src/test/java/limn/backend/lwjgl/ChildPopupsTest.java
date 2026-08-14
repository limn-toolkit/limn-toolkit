package limn.backend.lwjgl;

import limn.backend.Backend;
import limn.backend.Clipboard;
import limn.backend.Cursor;
import limn.backend.FrameCallback;
import limn.backend.NativeWindow;
import limn.backend.WindowInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The owner-to-popup bookkeeping, driven with fake windows so it needs no GLFW.
 *
 * <p>What is being pinned is the reason the class exists: a popup is a separate top-level window
 * at an absolute screen position, and the OS ties it to nothing. If the owner moves and the
 * popups do not, an open dropdown is left stranded in the middle of the desktop.
 */
class ChildPopupsTest {

    /** A window that only knows where it is, whether it is closed, and how to be moved. */
    private static final class FakeWindow implements NativeWindow {
        int x;
        int y;
        private boolean closed;
        int moves;
        NativeWindow follower; // set to model a popup that owns a popup

        FakeWindow(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public void setScreenPosition(int newX, int newY) {
            int dx = newX - x;
            int dy = newY - y;
            x = newX;
            y = newY;
            moves++;
            if (follower != null) {
                // The real backend gets this from GLFW's position callback firing on the move.
                follower.setScreenPosition(follower.screenX() + dx, follower.screenY() + dy);
            }
        }

        @Override public int screenX() { return x; }
        @Override public int screenY() { return y; }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closed = true; }
        @Override public void requestClose() { closed = true; }

        @Override public void requestFrame() { }
        @Override public void setFrameCallback(FrameCallback callback) { }
        @Override public void setInput(WindowInput input) { }
        @Override public String title() { return ""; }
        @Override public void setTitle(String title) { }
        @Override public float logicalWidth() { return 0; }
        @Override public float logicalHeight() { return 0; }
        @Override public int framebufferWidth() { return 0; }
        @Override public int framebufferHeight() { return 0; }
        @Override public float contentScale() { return 1; }
        @Override public void overrideContentScale(float scale) { }
        @Override public void setSize(int width, int height) { }
        @Override public void show() { }
        @Override public void hide() { }
        @Override public void focus() { }
        @Override public boolean isVisible() { return true; }
        @Override public void enterFullscreen(int width, int height, int refreshRate) { }
        @Override public void exitFullscreen() { }
        @Override public boolean isFullscreen() { return false; }
        @Override public boolean isModalBlocked() { return false; }
        @Override public void registerChildPopup(NativeWindow child, PopupKind kind) { }
        @Override public void unregisterChildPopup(NativeWindow child) { }
        @Override public Backend backend() { return null; }
        @Override public Clipboard clipboard() { return null; }
        @Override public float logicalToScreenFactor() { return 1; }
        @Override public void captureNextFrame(java.util.function.Consumer<limn.graphics.Image> sink) { }
        @Override public void setContentScaleListener(ContentScaleListener listener) { }
        @Override public void setOpacity(float value) { }
        @Override public void setCursor(Cursor value) { }
        @Override public void setImageCursor(limn.backend.ImageCursor value) { }
        @Override public void setPointerMode(limn.backend.PointerMode mode) { }
        @Override public limn.backend.PointerMode pointerMode() { return limn.backend.PointerMode.NORMAL; }
        @Override public void setImeEnabled(boolean enabled) { }
        @Override public void setPreeditCaretRect(float px, float py, float w, float h) { }
        @Override public void resetPreedit() { }
    }

    @Test
    void everyPopupKeepsItsOffsetWhenTheOwnerMoves() {
        ChildPopups popups = new ChildPopups();
        FakeWindow dropdown = new FakeWindow(120, 300);
        FakeWindow menu = new FakeWindow(400, 180);
        popups.add(dropdown, NativeWindow.PopupKind.TRANSIENT);
        popups.add(menu, NativeWindow.PopupKind.TRANSIENT);

        popups.moveBy(-40, 25); // the owner was dragged left and down

        assertEquals(80, dropdown.x, "the dropdown travelled with the window");
        assertEquals(325, dropdown.y);
        assertEquals(360, menu.x, "and so did the menu, by the same delta");
        assertEquals(205, menu.y);
    }

    @Test
    void aMoveOfZeroTouchesNothing() {
        // The position callback also fires for resizes that keep the origin, and for the initial
        // placement. Re-positioning a window costs a round trip to the compositor.
        ChildPopups popups = new ChildPopups();
        FakeWindow dropdown = new FakeWindow(10, 10);
        popups.add(dropdown, NativeWindow.PopupKind.TRANSIENT);

        popups.moveBy(0, 0);

        assertEquals(0, dropdown.moves, "no delta, no work");
    }

    @Test
    void aClosedPopupIsSkippedRatherThanMoved() {
        // A popup fading out is already destroyed on the backend side; its position is not
        // something to touch, and the owner unregisters it a turn later.
        ChildPopups popups = new ChildPopups();
        FakeWindow dying = new FakeWindow(10, 10);
        FakeWindow live = new FakeWindow(50, 50);
        popups.add(dying, NativeWindow.PopupKind.TRANSIENT);
        popups.add(live, NativeWindow.PopupKind.TRANSIENT);
        dying.close();

        popups.moveBy(5, 5);

        assertEquals(0, dying.moves, "nothing is asked of a destroyed window");
        assertEquals(55, live.x, "its neighbour still follows");
    }

    @Test
    void theDeltaReachesAPopupOwnedByAPopup() {
        // A menu that opened a submenu window: moving the first fires ITS position callback,
        // which is what carries the delta down the chain: no recursion in this class.
        ChildPopups popups = new ChildPopups();
        FakeWindow menu = new FakeWindow(200, 200);
        FakeWindow submenu = new FakeWindow(340, 240);
        menu.follower = submenu;
        popups.add(menu, NativeWindow.PopupKind.TRANSIENT);

        popups.moveBy(-100, 0);

        assertEquals(100, menu.x);
        assertEquals(240, submenu.x, "the submenu moved with the menu that owns it");
        assertEquals(240, submenu.y, "and only on the axis that changed");
    }

    @Test
    void aPopupUnregisteredBeforeTheMoveStaysWhereItIs() {
        ChildPopups popups = new ChildPopups();
        FakeWindow gone = new FakeWindow(10, 10);
        popups.add(gone, NativeWindow.PopupKind.TRANSIENT);
        popups.remove(gone);

        popups.moveBy(30, 30);

        assertEquals(10, gone.x, "it is no longer this window's popup");
        assertTrue(popups.isEmpty());
    }

    @Test
    void closingTheOwnerClosesAndForgetsEveryPopup() {
        // The orphan case: an always-on-top popup outliving its owner has nothing left to dismiss
        // it, so it sits over the desktop until the process dies.
        ChildPopups popups = new ChildPopups();
        FakeWindow dropdown = new FakeWindow(0, 0);
        FakeWindow menu = new FakeWindow(0, 0);
        popups.add(dropdown, NativeWindow.PopupKind.TRANSIENT);
        popups.add(menu, NativeWindow.PopupKind.TRANSIENT);

        popups.closeAll();

        assertTrue(dropdown.isClosed(), "closed with the owner");
        assertTrue(menu.isClosed());
        assertTrue(popups.isEmpty(), "and dropped, so a second teardown finds nothing");
        assertFalse(popups.contains(dropdown));
    }
}

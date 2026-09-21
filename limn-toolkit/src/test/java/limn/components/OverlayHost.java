package limn.components;

import limn.testing.StubWindow;
import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.concurrent.UiRuntime;

/**
 * A window that can host an in-scene dialog: the presentation asks the window's backend to lock
 * the owner's sibling windows, and the stub's backend refuses to exist. This one answers that
 * request with a handle and nothing else, so a card can be drawn in scene under a listening bridge
 * without a display server.
 *
 * <p>It never claims to be modal-blocked, which is what keeps a second in-scene dialog in scene:
 * {@link Dialog} promotes an {@code IN_SCENE} request to a native window when the host is already
 * locked by a modal, and a stacked overlay is exactly the shape the overlay's own test needs.
 */
final class OverlayHost extends StubWindow implements Backend {
    /** The window the presentation named as the one that stays interactive. */
    NativeWindow sceneModalOwner;

    @Override public Backend backend() { return this; }
    @Override public UiRuntime uiRuntime() { return null; }
    @Override public NativeWindow createWindow(WindowConfig config) {
        throw new AssertionError("an in-scene dialog must not create a window");
    }
    @Override public void runEventLoop() { }
    @Override public void pushModal(NativeWindow modal, NativeWindow parent) { }
    @Override public void popModal(NativeWindow modal) { }
    @Override public SceneModalHandle pushSceneModal(NativeWindow owner, boolean toolkitScope) {
        sceneModalOwner = owner;
        return () -> sceneModalOwner = null;
    }
    @Override public void signalModalBlocked() { }
    @Override public void stop() { }
}

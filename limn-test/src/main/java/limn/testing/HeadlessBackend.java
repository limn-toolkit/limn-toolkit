package limn.testing;

import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.concurrent.UiRuntime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A backend that mints {@link HeadlessWindow}s and remembers them, so that a dialog, a menu or a
 * popup which opens as a window of its own opens somewhere a test can read. Install it with a
 * {@link HeadlessUi}'s runtime: {@code new HeadlessBackend(ui.runtime())}.
 *
 * <p>Modality is the two bits the toolkit asks a window about and nothing more: the modal's own
 * bit and its owner's blocked bit, set and cleared exactly as a platform would.
 */
public final class HeadlessBackend implements Backend {

    private final UiRuntime runtime;
    private final List<HeadlessWindow> windows = new ArrayList<>();

    /**
     * @param runtime the runtime the test installed, which is what {@link #uiRuntime()} answers
     */
    public HeadlessBackend(UiRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Opens the application's own window, the one a demo scene binds to.
     *
     * @param title  what the window is called, which is the tree's root name
     * @param width  logical width
     * @param height logical height
     * @return the window
     */
    public HeadlessWindow open(String title, int width, int height) {
        HeadlessWindow window = new HeadlessWindow(title, width, height, this);
        windows.add(window);
        return window;
    }

    /** @return every window this backend created, in the order it created them */
    public List<HeadlessWindow> windows() {
        return Collections.unmodifiableList(windows);
    }

    @Override
    public UiRuntime uiRuntime() {
        return runtime;
    }

    @Override
    public NativeWindow createWindow(WindowConfig config) {
        return open(config.title(), config.width(), config.height());
    }

    @Override
    public void runEventLoop() {
        throw new UnsupportedOperationException("a headless backend has no loop; call frame()");
    }

    @Override
    public void pushModal(NativeWindow modal, NativeWindow parent) {
        if (modal instanceof HeadlessWindow own) {
            own.modal = true;
        }
        if (parent instanceof HeadlessWindow owner) {
            owner.modalBlocked = true;
        }
    }

    @Override
    public void popModal(NativeWindow modal) {
        if (modal instanceof HeadlessWindow own) {
            own.modal = false;
        }
        for (HeadlessWindow window : windows) {
            window.modalBlocked = false;
        }
    }

    @Override
    public SceneModalHandle pushSceneModal(NativeWindow owner, boolean toolkitScope) {
        return () -> { };
    }

    @Override
    public void signalModalBlocked() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void close() {
    }
}

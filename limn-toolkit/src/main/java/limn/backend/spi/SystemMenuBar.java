package limn.backend.spi;

import limn.backend.NativeWindow;

/**
 * The menu bar a system keeps outside the windows, one for the application, as a backend offers
 * it to the toolkit. A backend on a system with none offers none:
 * {@link BackendPeer#systemMenuBar()} answers {@code null}.
 *
 * <p>Each window contributes at most one bar. Which one the system shows is the backend's to
 * decide from the window the user is working in; a window with none shows the application's
 * menu and little else.
 */
public interface SystemMenuBar {

    /**
     * A window's bar asks for the system's.
     *
     * @return whether it was taken; {@code false} when the window already contributes another,
     *         which then stays in the window
     */
    boolean attach(NativeWindow window, MenuBarHost host);

    /** The bar left its window, or asked to be drawn in it. Unknown pairs are ignored. */
    void detach(NativeWindow window, MenuBarHost host);

    /**
     * @return whether the system's bar holds this bar now. A bar that was taken is not held
     *         while its window is fullscreen in a way that covers the system's bar;
     *         {@link MenuBarHost#heldChanged()} says when the answer changes
     */
    boolean holds(NativeWindow window, MenuBarHost host);

    /** What the bar shows without being opened, its titles, may have changed. */
    void changed(MenuBarHost host);

    /**
     * Whether the system runs an accelerator of a bar it holds. What it does not run is the
     * toolkit's to run, the focused widget first.
     *
     * @param key       the key's name with no modifier, as {@link MenuRow#shortcutKey()}
     * @param modifiers the {@link limn.input.Keys} modifier bits
     */
    boolean runs(String key, int modifiers);
}

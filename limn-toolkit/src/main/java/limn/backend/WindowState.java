package limn.backend;

import java.util.Objects;

/**
 * Where a window is, how large and in which state, as one value: what {@link NativeWindow#state()}
 * answers and what {@link NativeWindow#observeState} hands a listener each time any part of it
 * changes.
 *
 * @param visible    whether the window is shown, as opposed to hidden; a minimized window is
 *                   still shown
 * @param minimized  whether it is minimized; always {@code false} under Wayland, where a window
 *                   is not told
 * @param maximized  whether it is maximized
 * @param fullscreen whether it is in fullscreen
 * @param bounds     its content's place and size in native screen coordinates, the ones
 *                   {@link NativeWindow#setScreenPosition} takes; under Wayland a window is not
 *                   told its place, and the place is the origin
 */
public record WindowState(boolean visible, boolean minimized, boolean maximized, boolean fullscreen,
        ScreenRect bounds) {

    /** Checks the bounds. */
    public WindowState {
        Objects.requireNonNull(bounds, "bounds");
    }
}

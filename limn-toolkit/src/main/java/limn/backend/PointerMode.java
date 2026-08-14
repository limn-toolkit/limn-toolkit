package limn.backend;

/**
 * How the window treats the mouse pointer: visibility and capture. Set via
 * {@link NativeWindow#setPointerMode}; {@link #NORMAL} is the default.
 *
 * <p>These are per-window states, not per-widget: a widget-level interaction
 * (a color picker hiding the cursor, a 3D viewport capturing it while
 * orbiting) sets the mode on ENTER/press and restores {@link #NORMAL} on
 * EXIT/release; see the cursors demo scene for the pattern.
 */
public enum PointerMode {

    /** Visible cursor, free to move. The default. */
    NORMAL,

    /**
     * Invisible while over this window's content area, but free to move and
     * still delivering positions: video playback, drawing surfaces where the
     * app paints its own cursor, screensaver-style scenes.
     */
    HIDDEN,

    /**
     * Visible but confined to the window's content area; the pointer cannot
     * leave until the mode is reset. Windowed games that must not let a click
     * land on another window. Best effort: platforms without confinement
     * keep {@link #NORMAL} behavior.
     */
    CONFINED,

    /**
     * Captured: the cursor is hidden, locked to the window, and motion arrives
     * as unbounded <em>deltas</em>, {@link WindowInput#mouseDelta} instead of
     * {@link WindowInput#mouseMoved} (the scene delivers them as
     * {@code MouseEvent.Type.MOTION} to the focused widget). Raw, unaccelerated
     * motion is used where the platform supports it. This is the FPS-camera /
     * trackball mode: position stops meaning anything, movement is everything.
     */
    RELATIVE
}

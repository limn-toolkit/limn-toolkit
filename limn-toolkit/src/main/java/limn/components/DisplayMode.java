package limn.components;

/**
 * Where a surface that floats above the page is actually drawn: in a window of its own, or as an
 * overlay inside the window that owns it. {@link Dialog}, {@link PopupMenu}, {@link ComboBox} and
 * {@link ColorPickerButton} all answer this question, and they answer it the same way.
 *
 * <p><b>It is a preference, never a guarantee.</b> Two platforms cannot hold one of these in a
 * window of its own, and on them {@link #NATIVE_WINDOW} is not available at all:
 *
 * <ul>
 *   <li><b>Wayland</b>, where a client cannot place a toplevel at an anchor. A menu window would
 *       open near the middle of the display wearing whatever frame the compositor puts on
 *       toplevels, which is worse than any overlay. See ADR 028.</li>
 *   <li><b>macOS exclusive fullscreen</b>, where a second window taking focus minimizes the
 *       fullscreen owner.</li>
 * </ul>
 *
 * <p>So a surface asked for {@code NATIVE_WINDOW} may be drawn in-scene anyway, and each of the
 * four types has a {@code displayMode()} that answers what actually happened rather than what was
 * requested. Code that branches on presentation must read that one; code that reads back the
 * setter is reading its own wish.
 *
 * <p><b>Why it is worth choosing rather than inheriting.</b> An in-scene surface is contained by
 * the window: it cannot extend past the edge, it composites with what is behind it, and a
 * screenshot of the window contains it. A native one is none of those things, and the last point
 * is not only a testing convenience: anything that records, streams or captures a window records
 * a native popup as absent. An application that documents itself with screenshots, or one whose
 * window is deliberately the whole of its presentation, wants {@code IN_SCENE} on purpose and on
 * every platform, not only where the platform forces it.
 */
public enum DisplayMode {

    /**
     * A separate window, positioned against the anchor. The default everywhere it is possible,
     * because it is what the platform's own menus and dialogs do: it can extend past the owner's
     * edge, and the compositor gives it the shadow and the stacking every other application gets.
     */
    NATIVE_WINDOW,

    /**
     * An overlay drawn inside the owner's window. Always available, on every platform, and the
     * only presentation on the two that cannot position a window at an anchor.
     */
    IN_SCENE
}

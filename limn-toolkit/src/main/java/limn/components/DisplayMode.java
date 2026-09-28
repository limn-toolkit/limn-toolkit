package limn.components;

import limn.backend.NativeWindow;

/**
 * Where a surface that floats above the page is actually drawn: in a window of its own, or as an
 * overlay inside the window that owns it. {@link Dialog}, {@link PopupMenu}, {@link ComboBox},
 * {@link ColorPickerButton} and {@link limn.components.date.DatePicker} all answer this question,
 * and they answer it by the same rules.
 *
 * <p><b>It is a preference, never a guarantee.</b> Two kinds of window cannot hold one of these
 * in a window of its own, and over them {@link #NATIVE_WINDOW} is not available at all:
 *
 * <ul>
 *   <li><b>A {@linkplain NativeWindow#isFullscreen() fullscreen} window</b>, of either kind. On a
 *       monitor a fullscreen window is minimized when another window takes the focus, and macOS
 *       native fullscreen is a Space of its own that a new window opens outside of, so a second
 *       window would take the person away from the one they were using. Every one of these
 *       surfaces is drawn in scene there, with one exception: a <b>non-modal</b> dialog stays a
 *       window, because an overlay captures its window's input and a non-modal dialog exists so
 *       that the window behind it stays usable, which leaves it no in-scene form to fall back
 *       to.</li>
 *   <li><b>Wayland</b>, or any window that cannot be
 *       {@linkplain NativeWindow#supportsAbsolutePositioning() positioned}, where a client cannot
 *       place a toplevel at an anchor. A menu window would open near the middle of the display
 *       wearing whatever frame the compositor puts on toplevels, which is worse than any overlay.
 *       This one holds only for the surfaces anchored inside their owner: a menu, a drop-down
 *       list, a calendar hanging from its field. A dialog, including the colour picker's, has no
 *       anchor to line up with, so it stays a window there and the compositor places it, which is
 *       where a dialog belongs.</li>
 * </ul>
 *
 * <p>So a surface asked for {@code NATIVE_WINDOW} may be drawn in-scene anyway, and each of these
 * types answers what actually happened rather than what was requested: {@code displayMode()} on
 * each, and {@link ColorPickerButton#pickerDisplayMode()} on the colour button. Code that branches
 * on presentation must read that one; code that reads back the setter is reading its own wish.
 * The choice is made each time a surface opens, over the window as it is then.
 *
 * <p><b>And a window of its own does not outlive a change that rules it out.</b> When the owner
 * enters or leaves fullscreen while a surface is open in a window of its own, a menu, a drop-down
 * list or a calendar closes as if dismissed, choosing nothing, as the platform's own menus close
 * on a change of their window's state; on macOS the person can make that change with the green
 * button or Control-Command-F while a list or a calendar is open, since neither takes the focus
 * from the window. A modal dialog, the colour picker's included, is a question still waiting for
 * its answer, so it moves into the scene instead, with what was typed in it. Two stay windows: a
 * non-modal dialog, for the reason above, and a modal one with another dialog or a modal menu open
 * over it, since moving it would close the window that one belongs to. A surface drawn in scene
 * stays where it is, and nothing moves back into a window when the owner leaves fullscreen.
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
     * only presentation over a fullscreen window and on a platform that cannot position a window
     * at an anchor.
     */
    IN_SCENE
}
